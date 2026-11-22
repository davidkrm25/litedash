package com.irvingguerra.litedash

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.vehicle.Signal
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.vehicle.watch
import com.irvingguerra.litedash.feature.car.CarStill
import com.irvingguerra.litedash.feature.obd.CHARGING_MAX_V
import com.irvingguerra.litedash.feature.obd.HOT_COOLANT_C
import com.irvingguerra.litedash.feature.obd.ObdState
import com.irvingguerra.litedash.feature.obd.RESTING_MIN_V
import java.util.Locale

/**
 * Velocímetro en el estilo de su [GaugeLook] (de fábrica, la Esfera). Lee la velocidad del
 * auto (OBD) si hay adaptador conectado; si no, del GPS (pide permiso la primera vez).
 */
@Composable
fun SpeedometerTile(d: Dependencies, look: GaugeLook, dashito: Boolean = false, legend: Boolean = false) {
    // Con un adaptador ya guardado, se reconecta solo (sin abrir el Taller).
    val obdOn = rememberObd(d)?.state == ObdState.ON
    val (gps, ask) = rememberGpsPermission(d)
    if (!obdOn && !gps) {
        return ShortcutTile(Icons.speed, stringResource(R.string.tile_speedometer), stringResource(R.string.tap_to_enable), ask)
    }
    // Sin OBD, el GPS publica la velocidad en el bus mientras la pieza se ve.
    if (!obdOn) KeepTripRunning(d.trip)
    val kmh = d.bus.watch(Signal.SPEED)
    val miles = d.prefs.units.miles
    val front = rememberSpeedFront(d, look)
    val tag = if (legend) stringResource(R.string.dashito_speed_legend) else speedTag(d)
    if (!dashito) return SpeedGaugeCard((kmh ?: 0f).toMiles(miles), miles, tag, look, d.prefs.carColor, front)
    val c = LocalColors.current
    val rpm = d.bus.watch(Signal.RPM)
    val floorFront: (@Composable (Modifier) -> Unit)? = front?.let { car -> { m -> MovingFloor(kmh ?: 0f, c.accent, m, car) } }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SpeedGaugeCard((kmh ?: 0f).toMiles(miles), miles, tag, look, d.prefs.carColor, floorFront, frontScale = 0.9f)
            Label(
                stringResource(R.string.dashito_title).uppercase(), Typography.subtitle.copy(letterSpacing = 9.sp), c.text.copy(alpha = 0.85f), 1,
                Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            )
        }
        Row(
            Modifier.padding(bottom = 6.dp).clip(CircleShape).border(0.75.dp, c.accent.copy(alpha = 0.55f), CircleShape).padding(horizontal = 18.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Label("RPM", Typography.small, c.textMuted, 1)
            Label((rpm ?: 0f).toInt().toString(), Typography.subtitle, c.accent, 1)
        }
    }
}

/**
 * El piso bajo el auto: líneas en perspectiva que corren hacia el frente mientras el auto avanza
 * (más rápido, más veloz), con una elipse de luz bajo las ruedas. [car] es el auto, encima.
 */
@Composable
private fun MovingFloor(kmh: Float, color: Color, modifier: Modifier, car: @Composable (Modifier) -> Unit) {
    val speed = rememberUpdatedState(kmh)
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                if (last != 0L) phase = (phase + (t - last) / 1e9f * (speed.value / 60f).coerceIn(0f, 3f)) % 1f
                last = t
            }
        }
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val w = maxWidth
        val h = maxHeight
        Canvas(Modifier.wrapContentSize(unbounded = true).requiredSize(w * 1.9f, h * 1.6f)) {
            val cx = size.width / 2
            val top = size.height * 0.36f
            val base = size.height * 0.80f
            // Elipse de luz bajo las ruedas.
            val ovalAt = Offset(cx - size.width * 0.24f, base - size.height * 0.16f)
            val ovalSize = Size(size.width * 0.48f, size.height * 0.18f)
            drawOval(Brush.radialGradient(listOf(color.copy(alpha = 0.16f), Color.Transparent), Offset(cx, base - size.height * 0.07f), size.width * 0.22f), ovalAt, ovalSize)
            // Un contorno con brillo (como Dashito), en el degradado de azul claro a acento.
            drawOval(Brush.horizontalGradient(listOf(com.irvingguerra.litedash.core.ui.AccentStart.copy(alpha = 0.25f), color.copy(alpha = 0.25f)), ovalAt.x, ovalAt.x + ovalSize.width), ovalAt, ovalSize, style = androidx.compose.ui.graphics.drawscope.Stroke(9f))
            drawOval(Brush.horizontalGradient(listOf(com.irvingguerra.litedash.core.ui.AccentStart, color), ovalAt.x, ovalAt.x + ovalSize.width), ovalAt, ovalSize, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
            val fade = listOf(Color.Transparent, color.copy(alpha = 0.45f))
            // Rayos que salen del punto de fuga.
            for (i in -4..4) {
                val x1 = cx + i * size.width * 0.075f
                val x2 = cx + i * size.width * 0.16f
                drawLine(Brush.verticalGradient(fade, top, size.height), Offset(x1, top), Offset(x2, size.height), 1.2f)
            }
            // Líneas que avanzan: más juntas lejos, más separadas cerca.
            for (k in 0..5) {
                val f = (k + phase) / 6f
                val y = top + (size.height - top) * f * f
                drawLine(color.copy(alpha = 0.40f * f), Offset(cx - size.width * (0.1f + 0.4f * f), y), Offset(cx + size.width * (0.1f + 0.4f * f), y), 1.2f)
            }
        }
        car(Modifier.fillMaxSize())
    }
}

/**
 * Lo que va al frente del velocímetro: tu auto quieto (de frente, sin reflejo) o tu imagen; null si
 * no se pidió nada, no hay auto instalado o aún no hay imagen. Se arma una vez por cambio, no con cada lectura.
 */
@Composable
fun rememberSpeedFront(d: Dependencies, look: GaugeLook): (@Composable (Modifier) -> Unit)? {
    // Solo se busca el auto si va al frente (es barato: está en memoria).
    val pkg = if (look.front == GaugeFront.CAR) d.cars.pkg(d.prefs.carModel) else null
    val paint = d.prefs.carPaint
    return remember(look.front, look.image, pkg, paint) {
        when (look.front) {
            GaugeFront.NONE -> null
            GaugeFront.CAR -> pkg?.let { { m: Modifier -> CarStill(d.cars, it, paint, m, reflection = false) } }
                // Sin auto del catálogo: la foto propia de Ajustes → Tu auto (sin foto no se ve nada).
                ?: { m: Modifier -> SavedTileImage(MY_CAR_PHOTO, m) }
            GaugeFront.IMAGE -> look.image.takeIf { it.isNotEmpty() }?.let { name -> { m: Modifier -> SavedTileImage(name, m) } }
        }
    }
}

/** De dónde viene la velocidad (OBD, GPS, CAN…: el nombre de su fuente, que ya es la sigla), para la etiqueta del velocímetro. */
fun speedTag(d: Dependencies) = d.bus.source(Signal.SPEED)?.name.orEmpty()

/**
 * La tarjeta del velocímetro con [speed] ya en la unidad que se ve (km/h o mph): la
 * usan la pieza y la vista previa de Personalizar medidor. [front]: ver [rememberSpeedFront].
 */
@Composable
fun SpeedGaugeCard(speed: Float, miles: Boolean, tag: String, look: GaugeLook, carColor: Color, front: (@Composable (Modifier) -> Unit)? = null, frontScale: Float = 0.62f) {
    val max = look.speedMax(miles).toFloat()
    // Marcas cada 20 km/h (10 mph); con 240 o más, cada 40 (20 mph) para que no se amontonen.
    val ticks = ticksFor(max, miles)
    val locale = currentLocale()
    // Los números de las marcas, con los dígitos del idioma: una vez por tope, no en cada cuadro.
    val labels = remember(max, ticks, locale) { List(ticks + 1) { i -> String.format(locale, "%d", (max * i / ticks).toInt()) } }
    GaugeCard(
        number = String.format(locale, "%.0f", speed),
        unit = speedUnit(miles),
        tag = tag,
        fraction = speed / max,
        ticks = ticks,
        look = look,
        carColor = carColor,
        labels = labels,
        front = front,
        title = stringResource(R.string.speed_title),
        arc = true,
        frontScale = frontScale,
    )
}

/** Cuántas marcas lleva un velocímetro que llega a [max]. */
fun ticksFor(max: Float, miles: Boolean): Int {
    val step = if (miles) (if (max > 160f) 20f else 10f) else (if (max >= 240f) 40f else 20f)
    return (max / step).toInt().coerceAtLeast(1)
}

/**
 * Un dato del auto que se puede poner como medidor: cómo se muestra (unidad y
 * decimales, en km o millas), su rango para el arco y cuándo preocupa.
 */
class GaugeSpec(
    val signal: String,
    val name: Int,
    /** Cortito, en mayúsculas en la tarjeta: dice qué número es (null si la unidad ya lo dice). */
    val tag: Int?,
    val icon: ImageVector,
    val ticks: Int,
    /** De la unidad del bus (km/h, °C, kPa…) a la que se muestra. */
    val shown: (value: Float, units: UnitChoice) -> Float,
    val unit: (units: UnitChoice) -> Int,
    val decimals: Int,
    /** El rango del arco, en la unidad del bus. */
    val min: Float,
    val max: Float,
    val warning: (Float) -> Boolean = { false },
    /** Desde dónde va la zona roja (unidad del bus); null = el último 20 %. */
    val red: Float? = null,
) {
    /** Qué tanto del arco se llena, con el valor del bus. */
    fun fraction(v: Float) = (v - min) / (max - min)

    /** Dónde empieza la zona roja (0–1). */
    val redFrom get() = red?.let(::fraction) ?: 0.8f

    /** El número de la marca [i] de [ticks], en la unidad que se ve (las rpm en miles). */
    fun label(i: Int, units: UnitChoice, locale: Locale): String {
        val v = shown(min + (max - min) * i / ticks, units)
        return String.format(locale, "%.0f", if (shown(max, units) >= 1000f) v / 1000f else v)
    }
}

val GaugeSpecs = listOf(
    GaugeSpec(
        Signal.RPM, R.string.gauge_rpm, null, Icons.tachometer, ticks = 8,
        shown = { v, _ -> v }, unit = { R.string.unit_rpm }, decimals = 0,
        min = 0f, max = 8000f, warning = { it >= 6500f }, red = 6500f,
    ),
    GaugeSpec(
        Signal.COOLANT, R.string.gauge_coolant, R.string.gauge_coolant_tag, Icons.thermometer, ticks = 9,
        shown = { v, u -> v.toFahrenheit(u.fahrenheit) }, unit = { if (it.fahrenheit) R.string.unit_f else R.string.unit_c }, decimals = 0,
        min = 40f, max = 130f, warning = { it >= HOT_COOLANT_C }, red = HOT_COOLANT_C,
    ),
    GaugeSpec(
        Signal.FUEL, R.string.gauge_fuel, R.string.gauge_fuel_tag, Icons.fuel, ticks = 4,
        shown = { v, _ -> v }, unit = { R.string.unit_percent }, decimals = 0,
        min = 0f, max = 100f, warning = { it <= 12f },
    ),
    GaugeSpec(
        Signal.VOLTAGE, R.string.gauge_voltage, R.string.gauge_voltage_tag, Icons.battery, ticks = 6,
        shown = { v, _ -> v }, unit = { R.string.unit_volts }, decimals = 1,
        min = 10f, max = 16f, warning = { it < RESTING_MIN_V || it > CHARGING_MAX_V },
    ),
    GaugeSpec(
        Signal.CONSUMPTION, R.string.gauge_consumption, R.string.gauge_consumption_tag, Icons.drop, ticks = 6,
        // km/l a millas por galón (EE. UU.).
        shown = { v, u -> if (u.miles) v * 2.352f else v }, unit = { if (it.miles) R.string.unit_mpg else R.string.unit_kml }, decimals = 1,
        min = 0f, max = 30f,
    ),
    GaugeSpec(
        Signal.BOOST, R.string.gauge_boost, R.string.gauge_boost_tag, Icons.turbo, ticks = 8,
        // El turbo se habla en psi (también en México).
        shown = { v, _ -> v * 0.145f }, unit = { R.string.unit_psi }, decimals = 1,
        min = -100f, max = 170f,
    ),
    GaugeSpec(
        Signal.THROTTLE, R.string.gauge_throttle, R.string.gauge_throttle_tag, Icons.pedal, ticks = 5,
        shown = { v, _ -> v }, unit = { R.string.unit_percent }, decimals = 0,
        min = 0f, max = 100f,
    ),
    GaugeSpec(
        Signal.OIL, R.string.gauge_oil, R.string.gauge_oil_tag, Icons.oil, ticks = 9,
        shown = { v, u -> v.toFahrenheit(u.fahrenheit) }, unit = { if (it.fahrenheit) R.string.unit_f else R.string.unit_c }, decimals = 0,
        min = 40f, max = 150f, warning = { it >= 130f }, red = 130f,
    ),
    GaugeSpec(
        Signal.FUEL_RATE, R.string.gauge_fuel_rate, R.string.gauge_fuel_rate_tag, Icons.fuel, ticks = 6,
        // Litros por hora a galones (EE. UU.) por hora.
        shown = { v, u -> if (u.miles) v * 0.2642f else v }, unit = { if (it.miles) R.string.unit_galh else R.string.unit_lh }, decimals = 1,
        min = 0f, max = 30f,
    ),
)

fun gaugeOf(signal: String) = GaugeSpecs.firstOrNull { it.signal == signal }

/**
 * Pieza "Medidor": un dato del auto por OBD (el que guarda [signal]). Vacía invita a
 * elegir; sin adaptador lleva al Taller; si el auto no da ese dato, lo dice.
 */
@Composable
fun GaugeTile(d: Dependencies, signal: String, look: GaugeLook, card: Boolean = false, arrow: Boolean = false, legend: Boolean = false, onPick: () -> Unit, onWorkshop: () -> Unit) {
    val spec = gaugeOf(signal)
        ?: return ShortcutTile(Icons.plus, stringResource(R.string.tile_gauge), stringResource(R.string.gauge_pick), onPick)
    // Con un adaptador ya guardado, se reconecta solo (sin abrir el Taller).
    val obd = rememberObd(d)
    if (card) {
        // Tarjeta de datos como en Dashito: ícono, nombre, valor y barra; al tocarla va a su pantalla.
        val units = d.prefs.units
        val locale = currentLocale()
        val live = obd?.state == ObdState.ON && obd.canRead(spec.signal)
        val value = if (live) d.bus.watch(spec.signal) else null
        // Rango de la barra como en Dashito: 40–120 °C y 11–15 V.
        val barRange = when (spec.signal) {
            com.irvingguerra.litedash.core.vehicle.Signal.COOLANT -> 40f to 120f
            com.irvingguerra.litedash.core.vehicle.Signal.VOLTAGE -> 11f to 15f
            else -> spec.min to spec.max
        }
        val cardName = when {
            spec.signal == com.irvingguerra.litedash.core.vehicle.Signal.COOLANT -> R.string.dashito_coolant
            spec.signal == com.irvingguerra.litedash.core.vehicle.Signal.VOLTAGE -> R.string.dashito_voltage
            spec.signal == com.irvingguerra.litedash.core.vehicle.Signal.CONSUMPTION && arrow -> R.string.dashito_avg
            else -> null
        }
        val withBar = spec.signal == com.irvingguerra.litedash.core.vehicle.Signal.COOLANT || spec.signal == com.irvingguerra.litedash.core.vehicle.Signal.VOLTAGE
        StatBox(
            spec.icon, stringResource(cardName ?: if (!arrow && spec.signal == com.irvingguerra.litedash.core.vehicle.Signal.CONSUMPTION) R.string.dashito_instant else spec.name), value?.let { gaugeText(spec, it, units, locale) } ?: "--", stringResource(spec.unit(units)),
            bar = if (withBar) (value?.let { ((it - barRange.first) / (barRange.second - barRange.first)).coerceIn(0f, 1f) } ?: 0f) else null,
            range = if (withBar) String.format(locale, "%.0f", spec.shown(barRange.first, units)) to String.format(locale, "%.0f", spec.shown(barRange.second, units)) else null,
            onClick = if (!arrow) null else { { d.dashboard.goTo(if (spec.signal == com.irvingguerra.litedash.core.vehicle.Signal.CONSUMPTION) 3 else 5) } },
        )
        return
    }
    // Sin adaptador (o sin ese dato) se ve igual, vacío y con "—", y una nota corta: no un texto largo.
    if (obd?.state != ObdState.ON) {
        val note = if (legend) "RPM" else stringResource(if (obd?.state == ObdState.CONNECTING) R.string.obd_connecting_short else R.string.gauge_no_obd)
        return SpecGaugeCard(spec, null, d.prefs.units, look, d.prefs.carColor, onClick = onWorkshop, note = note, empty = if (legend) "0" else "—")
    }
    if (!obd.canRead(spec.signal)) return SpecGaugeCard(spec, null, d.prefs.units, look, d.prefs.carColor, onClick = onPick, note = stringResource(R.string.gauge_no_data))
    val value = d.bus.watch(spec.signal)
    // Un toque cambia qué dato muestra.
    SpecGaugeCard(spec, value, d.prefs.units, look, d.prefs.carColor, onClick = onPick)
}

/**
 * La tarjeta de un medidor de [spec] con [value] en la unidad del bus (null = sin
 * lectura: "—"); la usan la pieza y la vista previa de Personalizar medidor.
 */
@Composable
fun SpecGaugeCard(spec: GaugeSpec, value: Float?, units: UnitChoice, look: GaugeLook, carColor: Color, onClick: (() -> Unit)? = null, note: String? = null, empty: String = "—") {
    val locale = currentLocale()
    val labels = remember(spec, units, locale) { List(spec.ticks + 1) { spec.label(it, units, locale) } }
    GaugeCard(
        number = value?.let { gaugeText(spec, it, units, locale) } ?: empty,
        unit = stringResource(spec.unit(units)),
        // La nota (sin OBD, conectando…) va donde va la etiqueta del dato.
        tag = note ?: spec.tag?.let { stringResource(it) } ?: "",
        fraction = value?.let(spec::fraction) ?: 0f,
        ticks = spec.ticks,
        look = look,
        carColor = carColor,
        labels = labels,
        redFrom = spec.redFrom,
        warning = value != null && spec.warning(value),
        onClick = onClick,
        title = stringResource(spec.name),
    )
}

/** El número como se lee en la tarjeta (sin unidad). */
fun gaugeText(spec: GaugeSpec, value: Float, units: UnitChoice, locale: Locale): String =
    String.format(locale, "%.${spec.decimals}f", spec.shown(value, units))

/** Nombre de la foto del auto que se sube en Ajustes → Tu auto (files/images/mycar.png). */
const val MY_CAR_PHOTO = "mycar"
