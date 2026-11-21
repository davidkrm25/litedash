package com.irvingguerra.litedash

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.irvingguerra.litedash.feature.dashboard.FuelParts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Divider
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.TextInput
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.watch
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.apps.rememberApps
import com.irvingguerra.litedash.feature.fuel.FuelGrade
import com.irvingguerra.litedash.feature.fuel.FuelReading
import com.irvingguerra.litedash.feature.fuel.LevelSource
import com.irvingguerra.litedash.feature.fuel.NearStation
import com.irvingguerra.litedash.feature.fuel.Station
import com.irvingguerra.litedash.feature.fuel.StationSort
import com.irvingguerra.litedash.feature.fuel.around
import java.util.Locale

/** Pantalla ancha (de auto, 7" o más): tres columnas; más angosta, una sola que se desliza. */
private val WIDE = 720.dp

internal enum class FuelDialog { FULL, ADD, TANK }

/**
 * La pantalla Autonomía: el medidor de cuánto te queda, los botones para anotar una carga, las gasolineras cerca con su
 * precio de la CRE (la más cerca o la más barata; al tocar una se abre la ruta) y lo que se sabe del consumo.
 * Mientras se ve, escucha el GPS y baja los precios si ya vencieron; al cerrarla, descansa.
 */
@Composable
fun FuelPanel(d: Dependencies, close: () -> Unit, onOwnMap: () -> Unit) {
    val fuel = d.fuel
    val context = LocalContext.current
    val apps = rememberApps(d.apps).orEmpty()
    val (granted, askGps) = rememberGpsPermission(d)
    if (granted) KeepTripRunning(d.trip)
    // Pide el nivel de gasolina al OBD solo mientras esta pantalla se ve (si no hay OBD, no pasa nada).
    d.bus.watch(Signal.FUEL)
    LaunchedEffect(Unit) { fuel.refreshStations() }
    var dialog by remember { mutableStateOf<FuelDialog?>(null) }
    val reading = fuel.reading()
    val locale = currentLocale()

    Box(Modifier.fillMaxSize()) {
        PanelScaffold(stringResource(R.string.fuel_title), close) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wide = maxWidth >= WIDE
                val gauge: @Composable (Modifier) -> Unit = { m ->
                    GaugeColumn(reading, m, onFull = { dialog = FuelDialog.FULL }, onAdd = { dialog = FuelDialog.ADD })
                }
                val stations: @Composable (Modifier) -> Unit = { m ->
                    StationsCard(d, granted, askGps, m) { s ->
                        val app = externalMapApp(d, apps)
                        if (app != null) goToStation(context, app, s)
                        else {
                            // Sin app de mapas externa: el mapa de LiteDash abre el lugar y desde ahí se traza la ruta.
                            PlaceRequests.place = pointHit(s.name, s.lat, s.lon)
                            onOwnMap()
                        }
                    }
                }
                val stats: @Composable (Modifier) -> Unit = { m ->
                    StatsColumn(reading, locale, m, onTank = { dialog = FuelDialog.TANK })
                }
                if (wide) Row(Modifier.fillMaxSize().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    gauge(Modifier.weight(1.15f).fillMaxSize())
                    stations(Modifier.weight(1.1f).fillMaxSize())
                    stats(Modifier.weight(0.9f).fillMaxSize())
                } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    gauge(Modifier.fillMaxWidth().height(380.dp))
                    stats(Modifier.fillMaxWidth())
                    stations(Modifier.fillMaxWidth().heightIn(min = 420.dp))
                }
            }
        }
        dialog?.let { which -> FuelDialogs(d, which, onClose = { dialog = null }) }
    }
}

// ───────────────────────────── medidor y botones ─────────────────────────────

@Composable
internal fun GaugeColumn(reading: FuelReading?, modifier: Modifier, onFull: () -> Unit, onAdd: () -> Unit) {
    val c = LocalColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            RangeGauge(reading)
        }
        Label(
            stringResource(
                when {
                    reading == null -> R.string.fuel_no_fill
                    reading.source == LevelSource.SENSOR -> R.string.fuel_source_sensor
                    else -> R.string.fuel_source_estimate
                },
            ),
            Typography.small, c.textMuted, maxLines = 2,
        )
        Box(
            Modifier.fillMaxWidth().height(56.dp).clip(CircleShape).background(c.accent).clickable(onClick = onFull),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.fuel, Color.White, 22.dp)
                Label(stringResource(R.string.fuel_filled), Typography.body.copy(fontWeight = FontWeight.SemiBold), Color.White, 1)
            }
        }
        Box(
            Modifier.fillMaxWidth().height(56.dp).clip(CircleShape).border(1.dp, c.accent.copy(alpha = 0.6f), CircleShape).clickable(onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.plus, c.accent, 22.dp)
                Label(stringResource(R.string.fuel_added), Typography.body.copy(fontWeight = FontWeight.SemiBold), c.accent, 1)
            }
        }
    }
}

/** El dial de cuánto te queda (como el de Dashito: marcas, números y aro): se llena con la autonomía y avisa cuando queda poco. */
@Composable
private fun RangeGauge(reading: FuelReading?) {
    val locale = currentLocale()
    val colors = LocalColors.current
    // La escala llega a la autonomía con el tanque lleno, redondeada a cientos (700 si aún no se sabe).
    val full = reading?.let { it.capacityL * it.kmPerL } ?: 700.0
    val top = (Math.ceil(full / 100.0).toInt() * 100).coerceIn(300, 1000)
    val steps = top / 100
    val labels = List(steps + 1) { String.format(locale, "%d", it * 100) }
    val fraction = reading?.let { (it.rangeKm / top).toFloat() } ?: 0f
    val low = reading != null && reading.levelL / reading.capacityL <= 0.12
    GaugeCard(
        number = if (reading == null) "--" else String.format(locale, "%.0f", reading.rangeKm),
        unit = stringResource(R.string.unit_km),
        tag = stringResource(R.string.fuel_range_label).uppercase(locale),
        fraction = fraction,
        ticks = steps,
        look = GaugeLook(numbers = true),
        carColor = colors.accent,
        labels = labels,
        redFrom = 1.1f,
        warning = low,
    )
}

// ───────────────────────────── tarjetas de datos ─────────────────────────────

@Composable
internal fun StatsColumn(reading: FuelReading?, locale: Locale, modifier: Modifier, onTank: () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (part in listOf(FuelParts.TANK, FuelParts.AVERAGE, FuelParts.SINCE, FuelParts.ACCURACY)) {
            FuelStat(part, reading, locale, Modifier.weight(1f), onTank)
        }
    }
}

/** Una de las tarjetas de datos de Autonomía: lo que hay en el tanque, el consumo, lo andado desde que llenaste o la precisión. */
@Composable
internal fun FuelStat(part: String, reading: FuelReading?, locale: Locale, modifier: Modifier, onTank: () -> Unit) {
    val dash = "--"
    when (part) {
        FuelParts.TANK -> StatCard(
            Icons.fuel, stringResource(R.string.fuel_in_tank),
            reading?.let { String.format(locale, "%.1f", it.levelL) } ?: dash,
            reading?.let { stringResource(R.string.fuel_of_capacity, String.format(locale, "%.0f", it.capacityL)) }.orEmpty(),
            detail = reading?.takeIf { it.levelL >= it.capacityL - 0.05 }?.let { stringResource(R.string.fuel_full) },
            progress = reading?.let { (it.levelL / it.capacityL).toFloat() },
            onClick = onTank, modifier = modifier,
        )
        FuelParts.AVERAGE -> StatCard(
            Icons.drop, stringResource(R.string.fuel_consumption),
            reading?.let { String.format(locale, "%.1f", it.kmPerL) } ?: dash, stringResource(R.string.fuel_unit_kml),
            detail = reading?.let { stringResource(if (it.calibrated) R.string.fuel_consumption_measured else R.string.fuel_consumption_assumed) },
            modifier = modifier,
        )
        FuelParts.SINCE -> StatCard(
            Icons.route, stringResource(R.string.fuel_since),
            reading?.kmSinceFill?.let { String.format(locale, "%.0f", it) } ?: "0", stringResource(R.string.unit_km),
            detail = reading?.let { r ->
                r.litersUsed?.let { stringResource(R.string.fuel_since_detail, dateText(r.lastFillAtMs, locale), String.format(locale, "%.1f", it)) }
            } ?: stringResource(R.string.fuel_since_none),
            modifier = modifier,
        )
        else -> StatCard(
            Icons.target, stringResource(R.string.fuel_accuracy),
            stringResource(if (reading?.calibrated == true) R.string.fuel_calibrated else R.string.fuel_uncalibrated), "",
            detail = if (reading?.calibrated == true) stringResource(R.string.fuel_calibrated_help, reading.burns) else stringResource(R.string.fuel_uncalibrated_help),
            modifier = modifier,
        )
    }
}

private fun dateText(atMs: Long?, locale: Locale): String =
    if (atMs == null) "" else java.text.SimpleDateFormat("d/M", locale).format(java.util.Date(atMs))

@Composable
private fun StatCard(
    icon: ImageVector,
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    progress: Float? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = LocalColors.current
    Card(modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(Radius.inner)).background(c.surfaceHigh), contentAlignment = Alignment.Center) { Icon(icon, c.accent, 24.dp) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Label(label.uppercase(currentLocale()), Typography.label, c.textMuted, 1)
                Row(verticalAlignment = Alignment.Bottom) {
                    Label(value, Typography.title, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    if (unit.isNotEmpty()) Label(unit, Typography.body, c.textMuted, 1, Modifier.padding(start = 6.dp, bottom = 3.dp))
                }
                if (detail != null) Label(detail, Typography.small, c.textMuted, 2)
                if (progress != null) {
                    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(5.dp).clip(CircleShape).background(c.line)) {
                        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(5.dp).clip(CircleShape).background(c.accent))
                    }
                }
            }
            if (onClick != null) Icon(Icons.chevron, c.textMuted.copy(alpha = 0.6f), 20.dp)
        }
    }
}

// ───────────────────────────── gasolineras ─────────────────────────────

@Composable
internal fun StationsCard(d: Dependencies, gpsGranted: Boolean, askGps: () -> Unit, modifier: Modifier, onPick: (Station) -> Unit) {
    val c = LocalColors.current
    val fuel = d.fuel
    val locale = currentLocale()
    val position = d.trip.position
    val snapshot = fuel.snapshot
    // Se recalcula al moverse ~100 m, no en cada punto del GPS.
    val lat = position?.latitude?.let { Math.round(it * 1000) / 1000.0 }
    val lon = position?.longitude?.let { Math.round(it * 1000) / 1000.0 }
    val list = remember(snapshot, lat, lon, fuel.grade, fuel.sort) {
        if (snapshot == null || lat == null || lon == null) emptyList() else snapshot.stations.around(lat, lon, fuel.grade, fuel.sort)
    }
    val cheapest = list.minOfOrNull { it.station.prices[fuel.grade] ?: Double.MAX_VALUE }
    Card(modifier) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.location, c.accent, 22.dp)
                Label(stringResource(R.string.fuel_stations).uppercase(locale), Typography.label, c.textMuted, 1, Modifier.padding(start = 6.dp).weight(1f))
                Segmented(
                    listOf(StationSort.NEAR to R.string.fuel_near, StationSort.CHEAP to R.string.fuel_cheap),
                    fuel.sort, fuel::select,
                )
            }
            Spacer(Modifier.height(10.dp))
            Segmented(
                listOf(FuelGrade.REGULAR to R.string.fuel_grade_regular, FuelGrade.PREMIUM to R.string.fuel_grade_premium, FuelGrade.DIESEL to R.string.fuel_grade_diesel),
                fuel.grade, fuel::select,
            )
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !gpsGranted -> FuelNotice(stringResource(R.string.fuel_no_gps), stringResource(R.string.tap_to_enable), askGps)
                    snapshot == null && fuel.loading -> FuelNotice(stringResource(R.string.fuel_updating))
                    snapshot == null -> FuelNotice(stringResource(if (fuel.offline) R.string.fuel_offline else R.string.fuel_no_prices))
                    position == null -> FuelNotice(stringResource(R.string.fuel_waiting_gps))
                    list.isEmpty() -> FuelNotice(stringResource(R.string.fuel_no_stations))
                    else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        for ((i, near) in list.withIndex()) {
                                                        StationRow(near, fuel.grade, highlight = near.station.prices[fuel.grade] == cheapest, locale = locale) { onPick(near.station) }
                        }
                    }
                }
            }
            val note = when {
                fuel.loading -> stringResource(R.string.fuel_updating)
                snapshot != null && fuel.offline -> stringResource(R.string.fuel_offline)
                snapshot != null -> stringResource(R.string.fuel_prices_source, agoText(snapshot.pricesAtMs))
                else -> null
            }
            if (note != null) Label(note, Typography.small, c.textMuted, 1, Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun agoText(atMs: Long): String {
    val minutes = ((System.currentTimeMillis() - atMs) / 60_000L).coerceAtLeast(0L).toInt()
    return when {
        minutes < 2 -> stringResource(R.string.fuel_ago_now)
        minutes < 60 -> stringResource(R.string.fuel_ago_min, minutes)
        minutes < 48 * 60 -> stringResource(R.string.fuel_ago_hours, minutes / 60)
        else -> stringResource(R.string.fuel_ago_days, minutes / (24 * 60))
    }
}

@Composable
private fun StationRow(near: NearStation, grade: FuelGrade, highlight: Boolean, locale: Locale, onClick: () -> Unit) {
    val c = LocalColors.current
    val s = near.station
    // Junto a la distancia, el precio de la otra gasolina común (Magna ↔ Premium), como referencia.
    val other = when (grade) {
        FuelGrade.REGULAR -> FuelGrade.PREMIUM
        FuelGrade.PREMIUM -> FuelGrade.REGULAR
        FuelGrade.DIESEL -> null
    }
    val km = String.format(locale, "%.1f", near.km)
    val otherPrice = other?.let { g -> s.prices[g]?.let { stringResource(R.string.fuel_other_price, gradeName(g), String.format(locale, "%.2f", it)) } }
    val detail = if (otherPrice != null) stringResource(R.string.fuel_station_detail, km, otherPrice) else "$km " + stringResource(R.string.unit_km)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.inner)).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Label(s.name.lowercase(locale).replaceFirstChar { it.titlecase(locale) }, Typography.subtitle.copy(fontSize = 15.sp), maxLines = 1)
            Label(detail, Typography.small, c.textMuted, 1)
        }
        val price = String.format(locale, "$%.2f", s.prices[grade] ?: 0.0)
        Box(
            Modifier.padding(start = 10.dp).clip(RoundedCornerShape(Radius.inner)).background(if (highlight) c.good.copy(alpha = 0.18f) else Color.Transparent)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) { Label(price, Typography.title.copy(fontSize = 20.sp), if (highlight) c.good else c.text, 1) }
    }
}

@Composable
private fun gradeName(g: FuelGrade) = stringResource(
    when (g) {
        FuelGrade.REGULAR -> R.string.fuel_grade_regular
        FuelGrade.PREMIUM -> R.string.fuel_grade_premium
        FuelGrade.DIESEL -> R.string.fuel_grade_diesel
    },
)

/** Un aviso en el centro de la lista (sin ubicación, sin conexión…), con un toque opcional. */
@Composable
private fun FuelNotice(text: String, action: String? = null, onClick: (() -> Unit)? = null) {
    val c = LocalColors.current
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Label(text, Typography.body, c.textMuted)
        if (action != null && onClick != null) Button(action, onClick, Modifier.padding(top = 12.dp))
    }
}

/** Opciones en una píldora: la elegida se rellena. */
@Composable
private fun <T> Segmented(options: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit) {
    val c = LocalColors.current
    Row(Modifier.clip(CircleShape).background(c.surfaceHigh)) {
        for ((value, name) in options) {
            val on = value == selected
            Box(
                Modifier.clip(CircleShape).background(if (on) c.primary else Color.Transparent).clickable { onSelect(value) }.padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Label(stringResource(name), Typography.small.copy(fontWeight = FontWeight.SemiBold), if (on) c.onPrimary else c.text, 1) }
        }
    }
}

// ───────────────────────────── ir a la gasolinera ─────────────────────────────

/**
 * Abre la ruta a [s] en la app de mapas elegida (Waze y Google Maps arrancan la navegación directo; las demás abren el
 * punto). Con [app] null no hace nada aquí: el mapa de LiteDash lo atiende quien llama.
 */
internal fun goToStation(context: Context, app: App?, s: Station) {
    if (app == null) return
    val point = String.format(Locale.ROOT, "%.6f,%.6f", s.lat, s.lon)
    val intent = when (app.pkg) {
        "com.waze" -> Intent(Intent.ACTION_VIEW, "https://waze.com/ul?ll=$point&navigate=yes".toUri()).setPackage(app.pkg)
        "com.google.android.apps.maps" -> Intent(Intent.ACTION_VIEW, "google.navigation:q=$point".toUri()).setPackage(app.pkg)
        else -> Intent(Intent.ACTION_VIEW, "geo:$point?q=${Uri.encode("$point(${s.name})")}".toUri()).setPackage(app.pkg)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (SplitScreen.open(context, intent, app.pkg)) return
    // La app elegida no entendió el punto: que lo abra cualquiera que sepa de mapas.
    if (!context.tryStart(intent)) context.tryStart(Intent(Intent.ACTION_VIEW, "geo:$point?q=$point".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ───────────────────────────── cuadros para anotar ─────────────────────────────

@Composable
internal fun FuelDialogs(d: Dependencies, which: FuelDialog, onClose: () -> Unit) {
    val fuel = d.fuel
    var liters by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var capacity by remember { mutableStateOf(trim(fuel.tank.capacityL)) }
    var assumed by remember { mutableStateOf(trim(fuel.tank.assumedKmPerL)) }
    val litersValue = number(liters)
    when (which) {
        FuelDialog.FULL -> FuelDialogFrame(
            stringResource(R.string.fuel_dialog_full_title), stringResource(R.string.fuel_dialog_full_help),
            confirmEnabled = liters.isBlank() || litersValue != null,
            onConfirm = { fuel.fillUp(litersValue, number(price)); onClose() }, onCancel = onClose,
        ) {
            NumberField(liters, { liters = it }, stringResource(R.string.fuel_liters_optional), stringResource(R.string.fuel_unit_l))
            NumberField(price, { price = it }, stringResource(R.string.fuel_price_optional), "$/L")
        }
        FuelDialog.ADD -> FuelDialogFrame(
            stringResource(R.string.fuel_dialog_add_title), stringResource(R.string.fuel_dialog_add_help),
            confirmEnabled = litersValue != null,
            onConfirm = { fuel.addLiters(litersValue ?: return@FuelDialogFrame, number(price)); onClose() }, onCancel = onClose,
        ) {
            NumberField(liters, { liters = it }, stringResource(R.string.fuel_liters), stringResource(R.string.fuel_unit_l))
            NumberField(price, { price = it }, stringResource(R.string.fuel_price_optional), "$/L")
        }
        FuelDialog.TANK -> FuelDialogFrame(
            stringResource(R.string.fuel_dialog_tank_title), stringResource(R.string.fuel_dialog_tank_help),
            confirmEnabled = number(capacity) != null && number(assumed) != null,
            onConfirm = { fuel.setTank(number(capacity) ?: return@FuelDialogFrame, number(assumed) ?: return@FuelDialogFrame); onClose() }, onCancel = onClose,
        ) {
            NumberField(capacity, { capacity = it }, stringResource(R.string.fuel_capacity), stringResource(R.string.fuel_unit_l))
            NumberField(assumed, { assumed = it }, stringResource(R.string.fuel_assumed_kml), stringResource(R.string.fuel_unit_kml))
        }
    }
}

/** "45" en vez de "45.0" para los campos. */
private fun trim(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else String.format(Locale.ROOT, "%.1f", v)

/** El número que escribió el usuario (con punto o coma); null si no es uno válido y mayor que cero. */
private fun number(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, unit: String) {
    val c = LocalColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(label, Typography.small, c.textMuted, 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextInput(value, { onChange(it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }.take(8)) }, "0", Modifier.weight(1f))
            Label(unit, Typography.body, c.textMuted, 1, Modifier.padding(start = 12.dp).widthIn(min = 28.dp))
        }
    }
}

/** La tarjeta al centro sobre lo oscurecido (como las confirmaciones de LiteDash); atrás = Cancelar. */
@Composable
private fun FuelDialogFrame(
    title: String,
    help: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalColors.current
    BackHandler(onBack = onCancel)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(24.dp).clip(RoundedCornerShape(24.dp)).background(c.surface)
                .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Label(title, Typography.title)
            Label(help, Typography.body, c.textMuted)
            content()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Button(stringResource(R.string.cancel), onCancel)
                Button(stringResource(R.string.fuel_save), onConfirm, primary = true, enabled = confirmEnabled)
            }
        }
    }
}
