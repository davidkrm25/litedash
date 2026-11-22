package com.irvingguerra.litedash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.dashboard.DashboardState

/** Un puntito y «SIN OBD» / «OBD»: si el adaptador está conectado. */
@Composable
fun ObdPill(on: Boolean, demo: Boolean = false) {
    val c = LocalColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.bluetooth, c.text.copy(alpha = 0.8f), 20.dp)
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (demo) c.warning else if (on) c.good else c.text.copy(alpha = 0.45f)))
        Label(stringResource(if (demo) R.string.dashito_obd_demo else if (on) R.string.dashito_obd_on else R.string.dashito_obd_off), Typography.label, if (demo) c.warning else c.text.copy(alpha = 0.8f), 1)
    }
}

private val TabIcons = listOf<ImageVector>(Icons.home, Icons.speed, Icons.location, Icons.fuel, Icons.partlyCloudy, Icons.engine)
private val TabNames = listOf(
    R.string.dashito_tab_home, R.string.dashito_tab_dash, R.string.dashito_tab_trip,
    R.string.dashito_tab_fuel, R.string.dashito_tab_weather, R.string.dashito_tab_diag,
)

/**
 * Las pestañas de las páginas (Inicio, Tablero, Viaje, Autonomía, Clima, Diagnóstico; las páginas de más se llaman por
 * su número) y al final Ajustes. La de la página que se ve va marcada; tocar una cambia de página.
 */
@Composable
fun PageTabs(pages: DashboardState, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxHeight(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until pages.pageCount) {
            val name = TabNames.getOrNull(i)?.let { stringResource(it) } ?: stringResource(R.string.page_n, i + 1)
            Tab(TabIcons.getOrElse(i) { Icons.dashboard }, name, pages.page == i) { pages.goTo(i) }
        }
    }
}

@Composable
private fun Tab(icon: ImageVector, name: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(16.dp)
    val short = isShortScreen()
    // Sin marcar, el ícono y el nombre se leen sobre cualquier fondo (con el gris tenue se perdían sobre una foto).
    val idle = c.text.copy(alpha = 0.85f)
    Column(
        Modifier
            .widthIn(min = 56.dp)
            .clip(shape)
            .then(if (selected) Modifier.background(c.accent.copy(alpha = 0.16f)).border(1.dp, c.accent.copy(alpha = 0.6f), shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = if (short) 8.dp else 8.dp, vertical = if (short) 3.dp else 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, if (selected) c.accent else idle, if (short) 22.dp else 24.dp)
        Label(name.uppercase(currentLocale()), Typography.label, if (selected) c.accent else idle, 1)
    }
}
