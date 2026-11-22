package com.irvingguerra.litedash

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import kotlin.coroutines.resume
import com.irvingguerra.litedash.core.ui.Https

/**
 * Clima tal como se ve: temperaturas redondeadas en la unidad elegida (códigos WMO de Open-Meteo).
 * [temperature] es la de ahora (null si la última descarga ya es vieja: no se presume como actual),
 * [today] lo pronosticado para hoy (por fecha) y [next] los días que siguen.
 */
data class Weather(
    val temperature: Int?, val code: Int, val day: Boolean, val today: WeatherDay?, val next: List<WeatherDay>,
    /** Lo de la vista completa (sensación, humedad, viento, horas…); null si lo guardado es de una versión sin ello. */
    val more: WeatherMore? = null,
)

/** Un día en la unidad elegida. [rain] es la probabilidad de lluvia (%); [uv], [sunrise] y [sunset] ("HH:mm") solo vienen de Open-Meteo. */
data class WeatherDay(
    val date: String, val code: Int, val max: Int, val min: Int,
    val rain: Int? = null, val uv: Int? = null, val sunrise: String? = null, val sunset: String? = null,
)

/** Una hora próxima: [time] "HH:mm". */
data class WeatherHour(val time: String, val code: Int, val day: Boolean, val temp: Int)

/** Lo que muestra la vista completa del clima; el viento sigue en km/h (quien lo muestra lo pasa a mph). */
data class WeatherMore(
    val feels: Int?, val humidity: Int?, val windKmh: Double?, val windDeg: Int?, val cloud: Int?,
    val hours: List<WeatherHour>, val place: String?, val fetchedAt: Long,
)

/** Como llegó de Open-Meteo: °C con decimales, para que °F salga exacto (20.4 °C = 69 °F, no 68). */
data class Forecast(
    val temperature: Double, val code: Int, val day: Boolean, val days: List<ForecastDay>, val fetchedAt: Long,
    val feels: Double? = null, val humidity: Int? = null, val windKmh: Double? = null, val windDeg: Int? = null, val cloud: Int? = null,
    val hours: List<ForecastHour> = emptyList(), val place: String? = null,
)

/** Una hora del pronóstico; [time] "aaaa-mm-ddTHH:mm" en la zona del lugar. */
data class ForecastHour(val time: String, val code: Int, val day: Boolean, val temp: Double)

/** Un día del pronóstico; [date] "aaaa-mm-dd" en la zona del lugar (como [Day.toString]). */
data class ForecastDay(
    val date: String, val code: Int, val max: Double, val min: Double,
    val rain: Int? = null, val uv: Double? = null, val sunrise: String? = null, val sunset: String? = null,
)

/** Hasta esta edad la temperatura descargada se muestra como la de ahora; después solo vale el pronóstico del día. */
const val CURRENT_MAX_AGE_MS = 3 * 3_600_000L

/**
 * [this] a la hora [now] (ms) en el día [today], en °F ([fahrenheit]) o °C, redondeado al final. Lo
 * pronosticado se alinea por fecha (sin red, lo guardado de hace días no se toma por hoy) y lo vencido
 * no se muestra: null si ya no queda nada vigente (ni la temperatura ni el día de hoy).
 */
fun Forecast.at(now: Long, today: Day, fahrenheit: Boolean): Weather? {
    val shown: (Double) -> Int = { c -> Math.round(c.toFahrenheit(fahrenheit)).toInt() }
    fun ForecastDay.inUnits() = WeatherDay(date, code, shown(max), shown(min), rain, uv?.let { Math.round(it).toInt() }, sunrise?.hhmm(), sunset?.hhmm())
    val date = today.toString()
    // Con el reloj de la pantalla atrasado (sin pila, hasta que hay red) no se sabe su edad: se muestra lo guardado.
    val current = now - fetchedAt < CURRENT_MAX_AGE_MS
    val todays = days.firstOrNull { it.date == date }?.inUnits()
    if (!current && todays == null) return null
    // Las fechas "aaaa-mm-dd" se ordenan igual como texto.
    val next = days.filter { it.date > date }.take(3).map { it.inUnits() }
    val more = if (current) WeatherMore(
        feels?.let(shown), humidity, windKmh, windDeg, cloud,
        hours.map { WeatherHour(it.time.hhmm(), it.code, it.day, shown(it.temp)) }, place, fetchedAt,
    ) else null
    return if (current) Weather(shown(temperature), code, day, todays, next, more)
    else Weather(null, todays!!.code, true, todays, next)
}

/** "2026-11-21T06:48" → "06:48". */
private fun String.hhmm() = substringAfter('T', this).take(5)

/** Cuándo (ms) puede cambiar lo que se ve de [this] después de [now]: al vencer la temperatura o a medianoche. */
fun Forecast.nextChange(now: Long): Long {
    val expires = fetchedAt + CURRENT_MAX_AGE_MS
    return if (expires > now) minOf(expires, nextMidnight(now)) else nextMidnight(now)
}

/** La próxima medianoche (ms) después de [now], en la zona del equipo. */
fun nextMidnight(now: Long): Long = GregorianCalendar().apply {
    timeInMillis = now
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    add(Calendar.DAY_OF_MONTH, 1)
}.timeInMillis

/**
 * °F, mph y millas solo donde se usan (Estados Unidos y pocos más). Con la app en
 * español siempre métrico, aunque el sistema esté en inglés de EE. UU.
 */
fun imperialUnits(language: String) = language != "es" &&
    Locale.getDefault().country in setOf("US", "LR", "MM")

/** Qué cielo es cada código WMO, para el ícono y el texto. */
enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, RAIN, STORM, SNOW }

fun skyOf(code: Int) = when (code) {
    0 -> Sky.CLEAR
    1, 2 -> Sky.PARTLY
    3 -> Sky.CLOUDY
    45, 48 -> Sky.FOG
    in 51..67, in 80..82 -> Sky.RAIN
    in 71..77, 85, 86 -> Sky.SNOW
    in 95..99 -> Sky.STORM
    else -> Sky.CLOUDY
}

/** El tiempo con todo su detalle (código WMO de Open-Meteo): cada uno trae su propio icono, color y texto. */
enum class Kind {
    CLEAR, MOSTLY_CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, FREEZING_DRIZZLE, LIGHT_RAIN, RAIN, HEAVY_RAIN, FREEZING_RAIN,
    SHOWERS, HEAVY_SHOWERS, LIGHT_SNOW, SNOW, HEAVY_SNOW, SNOW_GRAINS, SNOW_SHOWERS, STORM, HAIL,
}

fun kindOf(code: Int) = when (code) {
    0 -> Kind.CLEAR
    1 -> Kind.MOSTLY_CLEAR
    2 -> Kind.PARTLY
    3 -> Kind.CLOUDY
    45, 48 -> Kind.FOG
    51, 53, 55 -> Kind.DRIZZLE
    56, 57 -> Kind.FREEZING_DRIZZLE
    61 -> Kind.LIGHT_RAIN
    63 -> Kind.RAIN
    65 -> Kind.HEAVY_RAIN
    66, 67 -> Kind.FREEZING_RAIN
    80, 81 -> Kind.SHOWERS
    82 -> Kind.HEAVY_SHOWERS
    71 -> Kind.LIGHT_SNOW
    73 -> Kind.SNOW
    75 -> Kind.HEAVY_SNOW
    77 -> Kind.SNOW_GRAINS
    85, 86 -> Kind.SNOW_SHOWERS
    95 -> Kind.STORM
    96, 99 -> Kind.HAIL
    else -> Kind.CLOUDY
}

/**
 * Clima con Open-Meteo: gratis, sin clave ni anuncios, y solo con la ubicación
 * aproximada. Nada corre en segundo plano: mientras se ve el inicio se pide si el
 * último tiene más de [STALE_MS] ([WeatherRefresh]), y se guarda para verlo al
 * instante (y sin red). Apagado (Ajustes → Inicio y apariencia) no pide ubicación ni va a la red.
 */
class WeatherRepository(private val context: Context, private val prefs: Preferences) {
    private val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    /** Lo último que llegó de Open-Meteo, en °C con decimales (puede ser viejo: se ve con [weatherAt]). */
    var forecast by mutableStateOf(read())
        private set

    /** La última falla al traerlo (para el reporte técnico); null si la última vez salió bien. */
    private var failure: String? = null

    /** En °F (de Ajustes): es estado, quien lo lee se vuelve a dibujar si cambia. */
    val fahrenheit get() = prefs.units.fahrenheit

    /** En millas (de Ajustes): el viento se ve en mph. */
    val miles get() = prefs.units.miles

    /** El clima vigente a la hora [now] en la unidad elegida; null si no hay nada vigente. */
    fun weatherAt(now: Long): Weather? = forecast?.at(now, Day.today(), fahrenheit)

    // Solo si el usuario lo activó: el permiso de ubicación también llega al activar
    // Viaje, y eso no debe encender el clima. Quien ya tenía clima guardado lo conserva.
    /** Prendido por el usuario (aunque aún falte el permiso). */
    var enabled by mutableStateOf(store.getBoolean(KEY_ON, store.contains(KEY)))
        private set

    /** Activado y con permiso de ubicación aproximada. */
    val active get() = enabled && context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun enable() = turn(true)

    /** Apagado: se esconde y ya no pide ubicación ni va a la red (lo guardado se queda). */
    fun disable() = turn(false)

    private fun turn(on: Boolean) {
        enabled = on
        store.edit { putBoolean(KEY_ON, on) }
    }

    /**
     * Lo trae de nuevo si el último tiene más de [STALE_MS] (o con [force]). Devuelve false si lo intentó y
     * falló (sin ubicación o sin red): quien lo llama reintenta antes ([RETRY_MS]).
     */
    suspend fun refreshIfStale(force: Boolean = false): Boolean {
        if (!active) return true
        val last = forecast
        // Una edad negativa es el reloj atrasado: se trae de nuevo en cuanto se pueda.
        // Lo guardado por una versión anterior no trae humedad, viento ni horas (hours vacío): se baja de nuevo.
        val complete = last != null && last.hours.isNotEmpty()
        if (!force && last != null && complete && System.currentTimeMillis() - last.fetchedAt in 0 until STALE_MS) return true
        val place = location()
        if (place == null) {
            failure = "sin ubicación"
            return false
        }
        val fresh = try {
            fetch(place.latitude, place.longitude, placeName(place))
        } catch (e: IOException) {
            failure = "red: ${e.message}" // también un 4xx ("HTTP 404")
            return false
        } catch (e: JSONException) {
            failure = "respuesta: ${e.message}"
            return false
        }
        failure = null
        forecast = fresh
        withContext(Dispatchers.IO) { store.edit(commit = true) { putString(KEY, fresh.toJson().toString()) } }
        return true
    }

    /** Para el reporte técnico: si está prendido, de cuándo es lo guardado y la última falla. */
    fun describe(now: Long = System.currentTimeMillis()): String =
        "Clima: " + (if (!enabled) "apagado" else if (!active) "sin permiso" else forecast?.let { "de hace ${(now - it.fetchedAt) / 60_000} min" } ?: "sin datos") +
            (failure?.let { " · falló $it" } ?: "")

    @SuppressLint("MissingPermission") // [active] revisa el permiso antes
    private suspend fun location(): Location? {
        val manager = context.getSystemService(LocationManager::class.java)
        // La última conocida y qué proveedor está prendido se leen del sistema (binder): fuera del hilo principal.
        val (last, provider) = withContext(Dispatchers.IO) {
            val last = manager.newestLastKnown(manager.getProviders(true))
            // "fused" existe desde Android 12.
            val fused = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(LocationManager.FUSED_PROVIDER) else emptyList()
            last to (listOf(LocationManager.NETWORK_PROVIDER) + fused + LocationManager.GPS_PROVIDER).firstOrNull { manager.isProviderEnabled(it) }
        }
        if (last != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return last
        if (provider == null) return null
        return withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                manager.getCurrentLocation(provider, cancel, context.mainExecutor) { cont.resume(it) }
            }
        }
    }

    /** El barrio o la ciudad del lugar (para la vista completa); null si el equipo no lo sabe (necesita el servicio de mapas). */
    private suspend fun placeName(at: Location): String? = withContext(Dispatchers.IO) {
        try {
            @Suppress("DEPRECATION")
            android.location.Geocoder(context, Locale.getDefault()).getFromLocation(at.latitude, at.longitude, 1)?.firstOrNull()
                ?.let { it.subLocality ?: it.locality ?: it.subAdminArea }
        } catch (e: Throwable) {
            null
        }
    }

    private suspend fun fetch(lat: Double, lon: Double, place: String?): Forecast = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f".format(Locale.US, lat, lon) +
            "&current=temperature_2m,weather_code,is_day,apparent_temperature,relative_humidity_2m,wind_speed_10m,wind_direction_10m,cloud_cover" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,uv_index_max,sunrise,sunset" +
            "&hourly=temperature_2m,weather_code,is_day&forecast_hours=7&timezone=auto&forecast_days=4"
        // Https.get revisa el código (un 5xx se reintenta solo) y aquí se cierra la conexión al terminar.
        val connection = Https.get(context, url)
        val json = try {
            connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally {
            connection.disconnect()
        }
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val dates = daily.getJSONArray("time")
        val hourly = json.optJSONObject("hourly")
        val hourTimes = hourly?.optJSONArray("time")
        Forecast(
            temperature = current.getDouble("temperature_2m"),
            code = current.getInt("weather_code"),
            day = current.optInt("is_day", 1) == 1,
            days = (0 until dates.length()).map { i ->
                ForecastDay(
                    date = dates.getString(i),
                    code = daily.getJSONArray("weather_code").getInt(i),
                    max = daily.getJSONArray("temperature_2m_max").getDouble(i),
                    min = daily.getJSONArray("temperature_2m_min").getDouble(i),
                    rain = daily.optJSONArray("precipitation_probability_max")?.takeIf { !it.isNull(i) }?.getInt(i),
                    uv = daily.optJSONArray("uv_index_max")?.takeIf { !it.isNull(i) }?.getDouble(i),
                    sunrise = daily.optJSONArray("sunrise")?.optString(i)?.takeIf { it.isNotEmpty() },
                    sunset = daily.optJSONArray("sunset")?.optString(i)?.takeIf { it.isNotEmpty() },
                )
            },
            fetchedAt = System.currentTimeMillis(),
            feels = current.optDouble("apparent_temperature").takeIf { !it.isNaN() },
            humidity = current.optInt("relative_humidity_2m", -1).takeIf { it >= 0 },
            windKmh = current.optDouble("wind_speed_10m").takeIf { !it.isNaN() },
            windDeg = current.optInt("wind_direction_10m", -1).takeIf { it >= 0 },
            cloud = current.optInt("cloud_cover", -1).takeIf { it >= 0 },
            hours = if (hourly == null || hourTimes == null) emptyList() else // La primera es la hora en curso: las próximas horas empiezan en la siguiente (como Dashito).
            (1 until hourTimes.length()).map { i ->
                ForecastHour(
                    hourTimes.getString(i),
                    hourly.getJSONArray("weather_code").getInt(i),
                    hourly.optJSONArray("is_day")?.optInt(i, 1) != 0,
                    hourly.getJSONArray("temperature_2m").getDouble(i),
                )
            },
            place = place,
        )
    }

    /** Lo guardado (en °C); si no se puede leer, como si no hubiera nada (se vuelve a pedir). */
    private fun read(): Forecast? = store.getString(KEY, null)?.let { text ->
        try {
            val o = JSONObject(text)
            val days = o.getJSONArray("days")
            Forecast(
                o.getDouble("t"), o.getInt("c"), o.getBoolean("d"),
                (0 until days.length()).map { i ->
                    val x = days.getJSONObject(i)
                    ForecastDay(
                        x.getString("date"), x.getInt("c"), x.getDouble("max"), x.getDouble("min"),
                        x.optInt("rain", -1).takeIf { it >= 0 }, x.optDouble("uv").takeIf { !it.isNaN() },
                        x.optString("sr").takeIf { it.isNotEmpty() }, x.optString("ss").takeIf { it.isNotEmpty() },
                    )
                },
                o.getLong("at"),
                feels = o.optDouble("f").takeIf { !it.isNaN() },
                humidity = o.optInt("h", -1).takeIf { it >= 0 },
                windKmh = o.optDouble("w").takeIf { !it.isNaN() },
                windDeg = o.optInt("wd", -1).takeIf { it >= 0 },
                cloud = o.optInt("cl", -1).takeIf { it >= 0 },
                hours = o.optJSONArray("hrs")?.let { a ->
                    (0 until a.length()).map { i ->
                        val x = a.getJSONObject(i)
                        ForecastHour(x.getString("t"), x.getInt("c"), x.getBoolean("d"), x.getDouble("v"))
                    }
                }.orEmpty(),
                place = o.optString("pl").takeIf { it.isNotEmpty() },
            )
        } catch (e: JSONException) {
            null
        }
    }

    private fun Forecast.toJson() = JSONObject()
        .put("t", temperature).put("c", code).put("d", day).put("at", fetchedAt)
        .apply {
            feels?.let { put("f", it) }; humidity?.let { put("h", it) }; windKmh?.let { put("w", it) }
            windDeg?.let { put("wd", it) }; cloud?.let { put("cl", it) }; place?.let { put("pl", it) }
        }
        .put("hrs", JSONArray(hours.map { JSONObject().put("t", it.time).put("c", it.code).put("d", it.day).put("v", it.temp) }))
        .put("days", JSONArray(days.map { d ->
            JSONObject().put("date", d.date).put("c", d.code).put("max", d.max).put("min", d.min).apply {
                d.rain?.let { put("rain", it) }; d.uv?.let { put("uv", it) }; d.sunrise?.let { put("sr", it) }; d.sunset?.let { put("ss", it) }
            }
        }))

    companion object {
        private const val STORE = "weather"
        private const val KEY = "last"
        private const val KEY_ON = "enabled"

        /** Cada cuánto se renueva mientras se ve el inicio. */
        const val STALE_MS = 15 * 60_000L

        /** Tras una falla (sin red o sin ubicación), cuándo se vuelve a intentar. */
        const val RETRY_MS = 5 * 60_000L
    }
}
