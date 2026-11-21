package com.irvingguerra.litedash

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Colors
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.LocalPlainCard
import com.irvingguerra.litedash.core.ui.SwitchRow
import com.irvingguerra.litedash.core.ui.TilePadding
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.ui.fitFont
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat

fun iconOf(sky: Sky, day: Boolean) = when (sky) {
    Sky.CLEAR -> if (day) Icons.sun else Icons.moon
    Sky.PARTLY -> Icons.partlyCloudy
    Sky.CLOUDY -> Icons.cloud
    Sky.FOG -> Icons.fog
    Sky.RAIN -> Icons.rain
    Sky.STORM -> Icons.storm
    Sky.SNOW -> Icons.snow
}

/** La descripción más fina (por el código del cielo): despejado, mayormente despejado, parcialmente nublado, nublado… */
fun textOfCode(code: Int) = if (code == 1) R.string.weather_mostly_clear else textOf(skyOf(code))

/** El color propio de cada cielo (sol amarillo, lluvia azul…) para la vista completa. */
fun skyColor(sky: Sky, day: Boolean, c: Colors): Color = when (sky) {
    Sky.CLEAR -> if (day) Color(0xFFFFC83D) else Color(0xFFB8C4E8)
    Sky.PARTLY -> Color(0xFFFFC83D)
    Sky.CLOUDY -> c.textMuted
    Sky.FOG -> c.textMuted
    Sky.RAIN -> Color(0xFF4FC3F7)
    Sky.STORM -> Color(0xFF7E8CFF)
    Sky.SNOW -> Color(0xFF9FD8FF)
}

fun textOf(sky: Sky) = when (sky) {
    Sky.CLEAR -> R.string.weather_clear
    Sky.PARTLY -> R.string.weather_partly
    Sky.CLOUDY -> R.string.weather_cloudy
    Sky.FOG -> R.string.weather_fog
    Sky.RAIN -> R.string.weather_rain
    Sky.STORM -> R.string.weather_storm
    Sky.SNOW -> R.string.weather_snow
}

/** Pide la ubicación aproximada y, si la dan, trae el clima al momento. */
@Composable
fun rememberEnableWeather(repo: WeatherRepository): () -> Unit {
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            repo.enable()
            scope.launch { repo.refreshIfStale(force = true) }
        }
    }
    return { launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
}

/**
 * Mantiene el clima al día mientras el inicio se ve: al volver ([revision]) y cada
 * [WeatherRepository.STALE_MS] con LiteDash al frente. Apagado o en segundo plano, nada.
 */
@Composable
fun WeatherRefresh(repo: WeatherRepository, revision: Int) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val on = repo.enabled
    LaunchedEffect(repo, revision, on) {
        if (!on) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                // Si falló (sin red o sin ubicación), se reintenta antes: si no, "Buscando el clima…" duraba media hora.
                val ok = repo.refreshIfStale()
                delay(if (ok) WeatherRepository.STALE_MS else WeatherRepository.RETRY_MS)
            }
        }
    }
}

/** Ajustes → Inicio y apariencia: prender o apagar el clima (al prenderlo pide la ubicación aproximada). */
@Composable
fun WeatherSwitch(repo: WeatherRepository) {
    val enable = rememberEnableWeather(repo)
    SwitchRow(
        stringResource(R.string.weather_switch),
        repo.enabled,
        { on -> if (on) enable() else repo.disable() },
        stringResource(R.string.weather_switch_help),
    )
}

/**
 * El clima vigente (ver [Forecast.at]). Se pone al día cuando llega otro, al vencer la temperatura, a
 * medianoche y al volver a la app (con la pantalla dormida la espera no avanza); nada corre entre medio.
 */
@Composable
fun rememberWeather(repo: WeatherRepository): Weather? {
    val forecast = repo.forecast
    val fahrenheit = repo.fahrenheit
    val starts = rememberStarts()
    val initial = remember { repo.weatherAt(System.currentTimeMillis()) }
    val weather by produceState(initial, forecast, fahrenheit, starts) {
        while (true) {
            val now = System.currentTimeMillis()
            value = repo.weatherAt(now)
            delay((forecast ?: break).nextChange(now) - now)
        }
    }
    return weather
}

/** Ícono y temperatura, para la barra de arriba (solo con una temperatura vigente). */
@Composable
fun WeatherChip(repo: WeatherRepository) {
    if (!repo.active) return
    WeatherChip(rememberWeather(repo) ?: return)
}

/** Ícono y temperatura de [w] (nada si su temperatura ya venció); el reloj lo usa con el clima que ya leyó. */
@Composable
fun WeatherChip(w: Weather) {
    val temperature = w.temperature ?: return
    val c = LocalColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(iconOf(skyOf(w.code), w.day), c.accent, 22.dp)
        Label(stringResource(R.string.weather_degrees, temperature), Typography.subtitle, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
    }
}

/**
 * Pieza Clima: hoy en grande y, si cabe, los próximos días. Sin permiso es un
 * botón para activarlo (lo pide solo entonces). Si la temperatura de ahora ya venció (sin red), en
 * grande va lo pronosticado para hoy; sin nada vigente, como si aún no llegara.
 */
@Composable
fun WeatherTile(repo: WeatherRepository, days: Boolean = true, range: Boolean = true) {
    val c = LocalColors.current
    val enable = rememberEnableWeather(repo)
    val w = if (repo.active) rememberWeather(repo) else null
    if (w == null) {
        return ShortcutTile(Icons.sun, stringResource(R.string.tile_weather), stringResource(if (repo.active) R.string.weather_loading else R.string.tap_to_enable), enable)
    }
    val sky = skyOf(w.code)
    val today = w.today
    val big = w.temperature?.let { stringResource(R.string.weather_degrees, it) }
        ?: stringResource(R.string.weather_range, today!!.max, today.min)
    val skyText = stringResource(textOfCode(w.code))
    // La máxima y la mínima van abajo solo si arriba va la temperatura de ahora (si no, ya están en grande).
    val detail = if (range && today != null && w.temperature != null) {
        stringResource(R.string.two_parts, skyText, stringResource(R.string.weather_range, today.max, today.min))
    } else skyText
    val locale = currentLocale()
    // El formato se crea una vez por idioma, no en cada recomposición.
    val weekday = remember(locale) { SimpleDateFormat("EEE", locale) }
    fun dayName(date: String) = try {
        weekday.format(Day.parse(date).toDate()).replaceFirstChar { it.uppercase(locale) }
    } catch (e: IllegalArgumentException) {
        "" // una fecha que no se entiende: el día va sin nombre
    }
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val rich = w.more != null && maxHeight > 300.dp && maxWidth > 280.dp
    Card(Modifier.fillMaxSize()) {
        // Un tinte suave del color del cielo desde la esquina (como el clima de iOS); nada se anima.
        // (Sin tarjeta no: sería un rectángulo de color sobre el fondo.) Hasta la esquina contraria, en cualquier pantalla.
        // En la vista completa va tarjeta limpia, como Dashito.
        if (!LocalPlainCard.current && !rich) Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(skyTint(sky, w.day, c), Color.Transparent), end = Offset.Infinite)))
        BoxWithConstraints(Modifier.fillMaxSize().padding(TilePadding)) {
            // El tamaño se lee aquí: dentro de las filas y columnas no se alcanza.
            val width = maxWidth
            val height = maxHeight
            val tall = height > 120.dp
            val nextDays = if (days) w.next else emptyList()
            val more = w.more
            if (rich && more != null) {
                RichWeather(w, more, big, repo.miles, ::dayName) { scope.launch { repo.refreshIfStale(force = true) } }
                return@BoxWithConstraints
            }
            if (!tall) {
                // Una fila: ícono, temperatura y cómo está; si es ancha, los próximos días.
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(iconOf(sky, w.day), c.accent, 34.dp)
                    Column(Modifier.weight(1f)) {
                        Label(big, Typography.clock.copy(fontSize = fitFont(big, width / 3, height, 0.5f, max = 40f).sp), maxLines = 1)
                        Label(detail, Typography.small, c.textMuted, maxLines = 1)
                    }
                    // Los días solo si caben a lo alto (si no, salían cortados).
                    if (width > 340.dp && height > 64.dp) for (d in nextDays) DayColumn(dayName(d.date), iconOf(skyOf(d.code), true), stringResource(R.string.weather_degrees, d.max))
                }
                return@BoxWithConstraints
            }
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Label(stringResource(R.string.tile_weather).uppercase(locale), Typography.label, c.textMuted, maxLines = 1)
                        Label(big, Typography.clock.copy(fontSize = fitFont(big, width * 0.6f, height, 0.32f, min = 28f, max = 72f).sp), maxLines = 1)
                    }
                    Icon(iconOf(sky, w.day), c.accent, (height * 0.22f).coerceIn(32.dp, 56.dp))
                }
                // La leyenda del cielo ("Mayormente soleado") siempre a la vista, en su propia línea.
                Label(skyText, Typography.body, c.text, maxLines = 1)
                if (detail != skyText) Label(detail.removePrefix(skyText).trimStart(' ', '·', '-', '•'), Typography.small, c.textMuted, maxLines = 1)
                // Lo de hoy junto arriba; los próximos días, abajo.
                Spacer(Modifier.weight(1f))
                // Los próximos días en renglón, si hay lugar (a lo ancho cabe uno por cada ~80 dp).
                val fit = (width / 80.dp).toInt().coerceAtMost(nextDays.size)
                if (fit > 0 && height > 150.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (d in nextDays.take(fit)) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Label(dayName(d.date), Typography.small, c.textMuted, maxLines = 1)
                        Icon(iconOf(skyOf(d.code), true), c.text, 18.dp)
                        Label(stringResource(R.string.weather_degrees, d.max), Typography.small, maxLines = 1)
                    }
                }
            }
        }
    }
    }
}

/** Un día del pronóstico en una fila: nombre, ícono y máxima, uno sobre otro. */
@Composable
private fun DayColumn(name: String, icon: ImageVector, max: String) {
    val c = LocalColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label(name, Typography.small, c.textMuted, maxLines = 1)
        Icon(icon, c.text, 22.dp)
        Label(max, Typography.small, maxLines = 1)
    }
}

/** El tinte del cielo: ámbar con sol, azul con nubes, lluvia o noche despejada, gris con niebla; de noche, más tenue. */
private fun skyTint(sky: Sky, day: Boolean, c: Colors): Color {
    val base = when (sky) {
        Sky.CLEAR -> if (day) c.accent else SkyBlue
        Sky.PARTLY, Sky.CLOUDY, Sky.RAIN, Sky.STORM -> SkyBlue
        Sky.FOG, Sky.SNOW -> c.textMuted
    }
    return base.copy(alpha = if (day) 0.16f else 0.1f)
}

private val SkyBlue = Color(0xFF5B8CFF)


private val CompassEs = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
private val CompassEn = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

/** La vista completa (como la página Clima de Dashito): ahora, datos, próximas horas y próximos días; lo de abajo solo si cabe. */
@Composable
private fun RichWeather(w: Weather, m: WeatherMore, big: String, miles: Boolean, dayName: (String) -> String, onRefresh: () -> Unit) {
    val c = LocalColors.current
    val locale = currentLocale()
    val today = w.today
    val context = androidx.compose.ui.platform.LocalContext.current
    val sky = skyOf(w.code)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = maxHeight
        val hours = m.hours.isNotEmpty() && h > 360.dp
        val days = w.next.isNotEmpty() && h > 470.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (h > 520.dp) 10.dp else 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(iconOf(sky, w.day), skyColor(sky, w.day, c), 64.dp)
                Label(big, Typography.clock.copy(fontSize = 64.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Light), maxLines = 1, modifier = Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clickable(onClick = onRefresh).padding(8.dp)) {
                    Icon(Icons.refresh, c.textMuted, 28.dp)
                    Label(android.text.format.DateFormat.getTimeFormat(context).format(m.fetchedAt), Typography.small, c.textMuted, maxLines = 1)
                }
            }
            Label(stringResource(textOfCode(w.code)), Typography.title.copy(fontSize = 24.sp), maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                m.feels?.let { Label(stringResource(R.string.weather_feels, it), Typography.body, c.textMuted, maxLines = 1) }
                Icon(Icons.location, c.textMuted, 18.dp)
                Label(m.place ?: stringResource(R.string.weather_place_unknown), Typography.body, c.textMuted, maxLines = 1)
            }
            val dirs = if (locale.language == "es") CompassEs else CompassEn
            val wind = m.windKmh?.let {
                val v = Math.round(if (miles) it * 0.621371 else it).toInt()
                val dir = m.windDeg?.let { d -> " " + dirs[((d + 22) / 45) % 8] }.orEmpty()
                stringResource(if (miles) R.string.weather_mph else R.string.weather_kmh, v) + dir
            }
            val uv = today?.uv?.let { u ->
                val level = stringResource(when { u <= 2 -> R.string.uv_low; u <= 5 -> R.string.uv_moderate; u <= 7 -> R.string.uv_high; u <= 10 -> R.string.uv_very_high; else -> R.string.uv_extreme })
                stringResource(R.string.weather_uv_value, u, level)
            }
            val chips = listOfNotNull(
                today?.let { Icons.thermometer to "↑${it.max}° ↓${it.min}°" },
                m.humidity?.let { Icons.drop to stringResource(R.string.weather_percent_spaced, it) },
                wind?.let { Icons.wind to it },
                uv?.let { Icons.sun to it },
                m.cloud?.let { Icons.cloud to stringResource(R.string.weather_clouds_value, it) },
                if (today?.sunrise != null && today.sunset != null) Icons.sunrise to "${today.sunrise}–${today.sunset}" else null,
            )
            for (row in chips.chunked(3)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((icon, text) in row) Row(
                    Modifier.weight(1f).background(c.text.copy(alpha = 0.07f), androidx.compose.foundation.shape.CircleShape).padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, c.accent, 20.dp)
                    Label(text, Typography.body, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            if (hours) {
                Label(stringResource(R.string.weather_next_hours), Typography.label, c.textMuted, maxLines = 1)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (x in m.hours.take(5)) Column(
                        Modifier.weight(1f).background(c.text.copy(alpha = 0.07f), androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Label(x.time, Typography.label, c.textMuted, maxLines = 1)
                        val s = skyOf(x.code)
                        Icon(iconOf(s, x.day), skyColor(s, x.day, c), 26.dp)
                        Label(stringResource(R.string.weather_degrees, x.temp), Typography.subtitle, maxLines = 1)
                    }
                }
            }
            if (days) {
                Label(stringResource(R.string.weather_next_days), Typography.label, c.textMuted, maxLines = 1)
                val low = w.next.minOf { it.min }
                val high = w.next.maxOf { it.max }
                val span = (high - low).coerceAtLeast(1).toFloat()
                for (d in w.next.take(3)) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Label(dayName(d.date).trimEnd('.'), Typography.subtitle, maxLines = 1, modifier = Modifier.width(54.dp))
                    val s = skyOf(d.code)
                    Icon(iconOf(s, true), skyColor(s, true, c), 26.dp)
                    Label(d.rain?.let { stringResource(R.string.weather_percent_spaced, it) }.orEmpty(), Typography.body, skyColor(Sky.RAIN, true, c), maxLines = 1, modifier = Modifier.width(52.dp))
                    Label(stringResource(R.string.weather_degrees, d.min), Typography.body, c.textMuted, maxLines = 1)
                    Box(Modifier.weight(1f).height(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c.text.copy(alpha = 0.08f))) {
                        val from = (d.min - low) / span
                        val to = (d.max - low) / span
                        Row(Modifier.fillMaxSize()) {
                            Spacer(Modifier.weight(from.coerceIn(0f, 1f).coerceAtLeast(0.0001f)))
                            Box(
                                Modifier.weight((to - from).coerceAtLeast(0.02f)).fillMaxSize().clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(Brush.horizontalGradient(listOf(com.irvingguerra.litedash.core.ui.AccentStart, c.accent))),
                            )
                            Spacer(Modifier.weight((1f - to).coerceAtLeast(0.0001f)))
                        }
                    }
                    Label(stringResource(R.string.weather_degrees, d.max), Typography.subtitle, maxLines = 1)
                }
            }
        }
    }
}
