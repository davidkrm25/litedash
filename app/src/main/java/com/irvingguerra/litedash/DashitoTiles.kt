package com.irvingguerra.litedash

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Divider
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.TilePadding
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.ui.fitFont
import com.irvingguerra.litedash.core.vehicle.watch
import com.irvingguerra.litedash.feature.apps.rememberApps
import com.irvingguerra.litedash.feature.dashboard.DiagParts
import com.irvingguerra.litedash.feature.dashboard.FuelParts
import com.irvingguerra.litedash.feature.dashboard.TileTypes
import com.irvingguerra.litedash.feature.dashboard.TripStatKinds
import com.irvingguerra.litedash.feature.obd.ObdState

/** Los widgets que trae el diseño de fábrica (no tienen opciones propias: se mueven y agrandan como los demás). */
val DASHITO_TYPES = setOf(TileTypes.TRIP_STAT, TileTypes.FUEL_PART, TileTypes.DIAG)

// ───────────────────────────── Viaje: una tarjeta por dato ─────────────────────────────

/**
 * Una tarjeta del viaje: distancia, tiempo de manejo, velocidad promedio y máxima, consumo promedio y gasolina usada;
 * o el botón de reiniciar. Usa el mismo viaje del GPS que el widget Viaje.
 */
@Composable
fun TripStatTile(d: Dependencies, kind: String) {
    val c = LocalColors.current
    val trip = d.trip
    val miles = d.prefs.units.miles
    val (granted, ask) = rememberGpsPermission(d)
    if (!granted) return ShortcutTile(Icons.location, stringResource(R.string.tile_trip), stringResource(R.string.tap_to_enable), ask)
    KeepTripRunning(trip)
    val stats = rememberTripStats(trip, miles)
    if (kind == TripStatKinds.NOTE) {
        Box(Modifier.fillMaxSize().padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
            Label(stringResource(R.string.dashito_trip_note), Typography.small, c.textMuted, 3)
        }
        return
    }
    if (kind == TripStatKinds.RESET) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            Box(
                Modifier.height(48.dp).fillMaxWidth().clip(CircleShape).border(1.dp, c.accent.copy(alpha = 0.7f), CircleShape).clickable { trip.reset() }.padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.refresh, c.accent, 20.dp)
                    Label(stringResource(R.string.trip_reset_long), Typography.body, c.accent, 1)
                }
            }
        }
        return
    }
    if (kind == TripStatKinds.NOW) {
        val leg = trip.current()
        StatBox(Icons.road, stringResource(R.string.dashito_trip_now), stats.distance, stats.unit, sub = hms(leg.movingMs), onClick = { d.dashboard.goTo(2) })
        return
    }
    val reading = d.fuel.reading()
    val kmPerL = reading?.kmPerL ?: d.fuel.tank.assumedKmPerL
    val leg = trip.current()
    val dash = "--"
    val (icon, name, value, unit) = when (kind) {
        TripStatKinds.DISTANCE -> Quad(Icons.route, R.string.dashito_distance, stats.distance, stats.unit)
        TripStatKinds.TIME -> Quad(Icons.stopwatch, R.string.dashito_drive_time, hms(leg.movingMs), "")
        TripStatKinds.AVERAGE -> Quad(Icons.speed, R.string.dashito_avg_speed, leg.averageKmh()?.let { speedText(it.toFloat(), miles) } ?: dash, stringResource(if (miles) R.string.unit_mph else R.string.unit_kmh))
        TripStatKinds.MAX -> Quad(Icons.trend, R.string.dashito_max_speed, speedText(trip.maxKmh, miles), stringResource(if (miles) R.string.unit_mph else R.string.unit_kmh))
        TripStatKinds.CONSUMPTION -> Quad(Icons.fuel, R.string.dashito_avg_consumption, if (leg.meters >= 500.0) String.format(currentLocale(), "%.1f", kmPerL) else dash, stringResource(R.string.fuel_unit_kml))
        else -> Quad(Icons.drops, R.string.dashito_fuel_used, if (leg.meters >= 500.0 && kmPerL > 0.0) String.format(currentLocale(), "%.1f", leg.meters / 1000.0 / kmPerL) else dash, "L")
    }
    // Todos los números del mismo tamaño (el de 00:00:00), como en Dashito.
    StatBox(icon, stringResource(name), value, unit, sizeFrom = "00:00:00")
}

private data class Quad(val icon: ImageVector, val name: Int, val value: String, val unit: String)

private fun hms(ms: Long): String {
    val s = ms / 1000
    return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
}

/** Tarjeta con su ícono en un cuadro, el nombre en chico arriba y el valor grande con su unidad. */
@Composable
internal fun StatBox(
    icon: ImageVector, name: String, value: String, unit: String,
    sub: String? = null, bar: Float? = null, range: Pair<String, String>? = null, onClick: (() -> Unit)? = null,
    sizeFrom: String? = null,
) {
    val c = LocalColors.current
    Card(Modifier.fillMaxSize(), onClick = onClick) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
            val size = fitFont(sizeFrom ?: value, maxWidth * 0.55f, maxHeight, 0.34f, min = 22f, max = 56f, extraChars = 1.5f)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(c.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon(icon, c.accent, 26.dp) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Label(name.uppercase(currentLocale()), Typography.label.copy(fontSize = 10.5.sp, letterSpacing = 0.2.sp), c.textMuted, 1)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Label(value, Typography.clock.copy(fontSize = size.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        if (unit.isNotEmpty()) Label(unit, Typography.body, c.textMuted, 1, Modifier.padding(start = 6.dp, bottom = 4.dp))
                    }
                    if (sub != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.clock, c.textMuted, 16.dp)
                        Label(sub, Typography.small, c.text, 1)
                    }
                    if (bar != null) {
                        Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(4.dp).clip(CircleShape).background(c.text.copy(alpha = 0.1f))) {
                            Box(Modifier.fillMaxWidth(bar.coerceIn(0f, 1f)).fillMaxHeight().background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(com.irvingguerra.litedash.core.ui.AccentStart, c.accent))))
                        }
                        if (range != null) Row(Modifier.fillMaxWidth()) {
                            Label(range.first, Typography.label, c.textMuted, 1, Modifier.weight(1f))
                            Label(range.second, Typography.label, c.textMuted, 1)
                        }
                    }
                }
                if (onClick != null) Icon(Icons.chevron, c.textMuted, 18.dp)
            }
        }
    }
}

// ───────────────────────────── Autonomía en piezas ─────────────────────────────

/** El medidor con sus botones, las gasolineras o las tarjetas de datos de Autonomía, cada una como su widget. */
@Composable
fun FuelPartTile(d: Dependencies, part: String, onOwnMap: () -> Unit) {
    val fuel = d.fuel
    val context = LocalContext.current
    val apps = rememberApps(d.apps).orEmpty()
    val (granted, askGps) = rememberGpsPermission(d)
    if (granted) KeepTripRunning(d.trip)
    d.bus.watch(com.irvingguerra.litedash.core.vehicle.Signal.FUEL)
    // Para medir el consumo con el motor (gasto de gasolina, o MAF y correcciones de mezcla).
    d.bus.watch(com.irvingguerra.litedash.core.vehicle.Signal.FUEL_RATE)
    d.bus.watch(com.irvingguerra.litedash.core.vehicle.Signal.MAF)
    d.bus.watch(com.irvingguerra.litedash.core.vehicle.Signal.FUEL_TRIM_SHORT)
    d.bus.watch(com.irvingguerra.litedash.core.vehicle.Signal.FUEL_TRIM_LONG)
    LaunchedEffect(Unit) { fuel.refreshStations() }
    var dialog by remember { mutableStateOf<FuelDialog?>(null) }
    val reading = fuel.reading()
    when (part) {
        FuelParts.GAUGE -> GaugeColumn(reading, Modifier.fillMaxSize().padding(6.dp), onFull = { dialog = FuelDialog.FULL }, onAdd = { dialog = FuelDialog.ADD })
        FuelParts.STATIONS -> StationsCard(d, granted, askGps, Modifier.fillMaxSize()) { s ->
            val app = externalMapApp(d, apps)
            if (app != null) goToStation(context, app, s)
            else {
                PlaceRequests.place = pointHit(s.name, s.lat, s.lon)
                onOwnMap()
            }
        }
        FuelParts.STATS -> StatsColumn(reading, currentLocale(), Modifier.fillMaxSize(), onTank = { dialog = FuelDialog.TANK })
        else -> FuelStat(part, reading, currentLocale(), Modifier.fillMaxSize(), onTank = { dialog = FuelDialog.TANK })
    }
    dialog?.let { which -> FuelDialogs(d, which, onClose = { dialog = null }) }
}

// ───────────────────────────── Diagnóstico ─────────────────────────────

/**
 * Una parte de la pantalla Diagnóstico: el estado del adaptador, cuántos datos reporta el auto, los códigos del check
 * engine, el botón para conectar, o la lista de datos del motor / códigos de falla. Todo sale del OBD de LiteDash.
 */
@Composable
fun DiagTile(d: Dependencies, part: String, onWorkshop: () -> Unit) {
    val c = LocalColors.current
    val obd = rememberObd(d)
    val on = obd?.state == ObdState.ON
    val connecting = obd?.state == ObdState.CONNECTING
    // Los códigos se leen una vez por conexión (si ya hay reporte, no se vuelven a leer).
    LaunchedEffect(obd, on) { if (on && obd?.lastReport == null) obd?.report() }
    val report = if (on) obd?.lastReport else null
    val codes = report?.let { (it.stored + it.pending + it.permanent).distinct() }
    when (part) {
        DiagParts.ADAPTER -> InfoCard(
            Icons.bluetooth, R.string.dashito_adapter,
            when {
                on -> stringResource(R.string.dashito_connected)
                connecting -> stringResource(R.string.obd_connecting_short)
                else -> stringResource(R.string.dashito_no_connection)
            },
            "", obd?.adapter ?: "—", onWorkshop,
        )
        DiagParts.PIDS -> {
            val count = if (on) obd?.supported?.size else null
            InfoCard(Icons.chip, R.string.dashito_pids_title, count?.toString() ?: "--", "PIDs", stringResource(if (on) R.string.dashito_pids_read else R.string.dashito_pids_wait), onWorkshop, chevron = true)
        }
        DiagParts.CHECK -> InfoCard(
            Icons.warning, R.string.dashito_check_engine,
            codes?.size?.toString() ?: "--", "",
            stringResource(if (codes == null) R.string.dashito_codes_unread else if (codes.isEmpty()) R.string.obd_no_codes else R.string.dashito_codes_found),
            onWorkshop, chevron = true,
        )
        DiagParts.SPACE -> Unit
        DiagParts.CONNECT -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Box(
                    Modifier.fillMaxWidth().height(52.dp).clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(com.irvingguerra.litedash.core.ui.AccentStart, c.accent)))
                        .clickable(onClick = onWorkshop),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.bluetooth, androidx.compose.ui.graphics.Color.White, 22.dp)
                        Label(stringResource(R.string.dashito_connect_adapter), Typography.body.copy(fontWeight = FontWeight.SemiBold), androidx.compose.ui.graphics.Color.White, 1)
                    }
                }
            }
        }
        else -> DiagList(d, on, codes)
    }
}

@Composable
private fun InfoCard(icon: ImageVector, name: Int, value: String, unit: String, detail: String, onClick: () -> Unit, chevron: Boolean = false) {
    val c = LocalColors.current
    Card(Modifier.fillMaxSize(), onClick = onClick) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
            // El valor se achica a lo que quepa ("Sin conexión" completo en vez de cortado).
            val size = fitFont(value, maxWidth * 0.55f, maxHeight, 0.34f, min = 18f, max = 30f, extraChars = 1.5f)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(c.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon(icon, c.accent, 28.dp) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Label(stringResource(name).uppercase(currentLocale()), Typography.label, c.textMuted, 1)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Label(value, Typography.clock.copy(fontSize = size.sp, fontWeight = FontWeight.Bold), maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        if (unit.isNotEmpty()) Label(unit, Typography.body, c.textMuted, 1, Modifier.padding(start = 6.dp, bottom = 3.dp))
                    }
                    if (detail.isNotEmpty()) Label(detail, Typography.small, c.textMuted, 2)
                }
                if (chevron) Icon(Icons.chevron, c.textMuted.copy(alpha = 0.6f), 18.dp)
            }
        }
    }
}

/** La lista grande: pestañas «Datos del motor» y «Códigos de falla»; sin OBD, un mensaje que invita a conectarlo. */
@Composable
private fun DiagList(d: Dependencies, connected: Boolean, codes: List<String>?) {
    val c = LocalColors.current
    var tab by remember { mutableStateOf(0) }
    val obd = rememberObd(d)
    val specs = remember(connected, obd?.supported) { GaugeSpecs.filter { obd?.canRead(it.signal) == true } }
    Card(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(TilePadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(
                    stringResource(if (connected) (if (tab == 0) R.string.dashito_engine_values else R.string.dashito_fault_codes) else R.string.dashito_connect_to_see),
                    Typography.small, c.textMuted, 1, Modifier.weight(1f),
                )
                Row(Modifier.clip(CircleShape).border(1.dp, c.accent.copy(alpha = 0.5f), CircleShape)) {
                    for ((i, name) in listOf(R.string.dashito_tab_engine, R.string.dashito_tab_codes).withIndex()) {
                        Box(
                            Modifier.background(if (tab == i) c.accent.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent).clickable { tab = i }.padding(horizontal = 16.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) { Label(stringResource(name), Typography.small.copy(fontWeight = FontWeight.SemiBold), if (tab == i) c.accent else c.text, 1) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    !connected -> Label(stringResource(R.string.dashito_diag_empty), Typography.body.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center), c.textMuted, 4, Modifier.padding(horizontal = 56.dp))
                    tab == 1 -> if (codes.isNullOrEmpty()) Label(stringResource(R.string.obd_no_codes), Typography.subtitle, c.textMuted)
                        else LazyColumn(Modifier.fillMaxSize()) {
                            items(codes) { code ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.warning, c.warning, 20.dp)
                                    Label(code, Typography.subtitle, maxLines = 1, modifier = Modifier.padding(start = 12.dp))
                                }
                                Divider()
                            }
                        }
                    else -> if (specs.isEmpty()) Label(stringResource(R.string.dashito_diag_empty), Typography.body.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center), c.textMuted, 4, Modifier.padding(horizontal = 56.dp))
                        else LazyColumn(Modifier.fillMaxSize()) {
                            items(specs) { spec -> LiveValueRow(d, spec); Divider() }
                        }
                }
            }
        }
    }
}

/** Una fila de datos del motor: el nombre y el valor en vivo (pide la señal solo mientras se ve). */
@Composable
private fun LiveValueRow(d: Dependencies, spec: GaugeSpec) {
    val c = LocalColors.current
    val value = d.bus.watch(spec.signal)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(spec.icon, c.accent, 22.dp)
        Label(stringResource(spec.name), Typography.subtitle, maxLines = 1, modifier = Modifier.padding(start = 12.dp).weight(1f))
        Label(value?.let { String.format(currentLocale(), "%.1f", it) } ?: "--", Typography.subtitle, c.accent, 1)
    }
}
