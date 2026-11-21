package com.irvingguerra.litedash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.TilePadding
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.watch

/**
 * Pieza Autonomía: cuánto te queda (km) y cuánta gasolina hay, con el nivel en una barra. Un toque abre la pantalla
 * completa ([FuelPanel]). Sin ninguna carga anotada es un acceso ("Toca para empezar"). Mientras se ve y ya hay una
 * carga, mantiene el GPS prendido para contar lo recorrido.
 */
@Composable
fun FuelTile(d: Dependencies, onOpen: () -> Unit) {
    val c = LocalColors.current
    // El sensor del auto, si lo hay (pide el dato al OBD solo mientras la pieza se ve).
    d.bus.watch(Signal.FUEL)
    val reading = d.fuel.reading()
        ?: return ShortcutTile(Icons.fuel, stringResource(R.string.tile_fuel), stringResource(R.string.fuel_no_fill), onOpen, chevron = true)
    KeepTripRunning(d.trip)
    val locale = currentLocale()
    Card(Modifier.fillMaxSize(), onClick = onOpen) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(TilePadding)) {
            val tall = maxHeight > 120.dp
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.fuel, c.accent, 16.dp)
                    Label(stringResource(R.string.tile_fuel).uppercase(locale), Typography.label, c.textMuted, 1, Modifier.padding(start = 8.dp).weight(1f))
                    Label(
                        stringResource(R.string.fuel_of_capacity, String.format(locale, "%.0f", reading.capacityL)).let { String.format(locale, "%.1f", reading.levelL) + " " + it },
                        Typography.small, c.textMuted, 1,
                    )
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Label(String.format(locale, "%.0f", reading.rangeKm), Typography.clock.copy(fontSize = if (tall) 56.sp else 36.sp), maxLines = 1)
                    Label(stringResource(R.string.unit_km), Typography.subtitle, c.textMuted, 1, Modifier.padding(start = 6.dp, bottom = if (tall) 8.dp else 4.dp))
                }
                val fraction = (reading.levelL / reading.capacityL).toFloat().coerceIn(0f, 1f)
                val color = when {
                    fraction > 0.25f -> c.good
                    fraction > 0.12f -> c.warning
                    else -> c.danger
                }
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.line)) {
                    Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(CircleShape).background(color))
                }
            }
        }
    }
}
