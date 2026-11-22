package com.irvingguerra.litedash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Divider
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.obd.ObdRepository
import com.irvingguerra.litedash.feature.obd.describe
import com.irvingguerra.litedash.feature.obd.diagPids
import com.irvingguerra.litedash.feature.obd.format
import com.irvingguerra.litedash.feature.obd.pidSpec
import com.irvingguerra.litedash.feature.obd.rawHex
import kotlinx.coroutines.launch
import com.irvingguerra.litedash.feature.obd.R as ObdR

/** Cómo se llama un dato: el del catálogo o, si LiteDash aún no lo conoce, "Dato 5E". */
@Composable
private fun pidName(code: Int): String =
    pidSpec(code)?.let { stringResource(it.name) } ?: stringResource(ObdR.string.pid_unknown_name, "%02X".format(code))

/** El valor de un dato listo para mostrar: del catálogo, los bytes si no se conoce, "--" si aún no contesta. */
@Composable
private fun pidValue(obd: ObdRepository, code: Int): String {
    val raw = obd.pidRaw[code] ?: return "--"
    val resources = LocalContext.current.resources
    val locale = currentLocale()
    return pidSpec(code)?.value?.invoke(raw)?.format(resources, locale) ?: rawHex(raw)
}

/**
 * Los datos que el auto reporta, de dos en dos: el PID en hexadecimal, el nombre, una línea que lo explica y el
 * valor en vivo. Tocar uno abre su detalle. Mientras se ve, el OBD pide todos (al irse deja de pedirlos).
 */
@Composable
internal fun PidGrid(obd: ObdRepository) {
    DisposableEffect(obd) {
        obd.diagWatch(true)
        onDispose { obd.diagWatch(false) }
    }
    var open by remember { mutableStateOf<Int?>(null) }
    val rows = diagPids(obd.supported).chunked(2)
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows) { row ->
            Row(Modifier.fillMaxWidth().height(78.dp), verticalAlignment = Alignment.CenterVertically) {
                for (code in row) PidCell(obd, code, Modifier.weight(1f)) { open = code }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    open?.let { PidDetail(obd, it) { open = null } }
}

@Composable
private fun PidCell(obd: ObdRepository, code: Int, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalColors.current
    Row(modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        HexChip(code)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Label(pidName(code), Typography.subtitle.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
            Label(pidSpec(code)?.let { stringResource(it.desc) } ?: stringResource(ObdR.string.pid_unknown_desc), Typography.small, c.textMuted, 1)
        }
        Label(pidValue(obd, code), Typography.subtitle.copy(fontWeight = FontWeight.Bold), c.accent, 1)
    }
}

@Composable
private fun HexChip(code: Int) {
    val c = LocalColors.current
    Box(Modifier.size(width = 52.dp, height = 40.dp).clip(RoundedCornerShape(10.dp)).background(c.accent.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
        Label("%02X".format(code), Typography.body.copy(fontWeight = FontWeight.Bold), c.accent, 1)
    }
}

/** El detalle de un dato sobre toda la pantalla: valor ahora, mínimo y máximo, bytes, y qué es, qué es normal, qué hacer y cómo se calcula. */
@Composable
private fun PidDetail(obd: ObdRepository, code: Int, onClose: () -> Unit) {
    val c = LocalColors.current
    val spec = pidSpec(code)
    val raw = obd.pidRaw[code]
    val resources = LocalContext.current.resources
    val locale = currentLocale()
    val range = obd.pidRange[code]
    Popup(popupPositionProvider = WholeWindow, properties = PopupProperties(focusable = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).pointerInput(Unit) { detectTapGestures { onClose() } },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(max = 1000.dp).fillMaxWidth().padding(24.dp).clip(RoundedCornerShape(28.dp)).background(c.surface)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HexChip(code)
                    Label(pidName(code), Typography.title.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 14.dp))
                    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) { Icon(Icons.close, c.textMuted, 26.dp) }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Label(stringResource(R.string.pid_now).uppercase(locale), Typography.label, c.textMuted, 1)
                        Label(pidValue(obd, code), Typography.clock.copy(fontSize = 52.sp, fontWeight = FontWeight.Bold), c.accent, 1)
                        raw?.let { Label(stringResource(R.string.pid_bytes, rawHex(it)), Typography.small, c.textMuted, 1) }
                    }
                    if (range != null && spec != null) {
                        val sample = spec.value(raw ?: intArrayOf(0))
                        fun text(n: Float) = (sample as? com.irvingguerra.litedash.feature.obd.PidValue.Num)?.copy(v = n)?.format(resources, locale) ?: "--"
                        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                            Column(horizontalAlignment = Alignment.End) {
                                Label(stringResource(R.string.pid_min).uppercase(locale), Typography.label, c.textMuted, 1)
                                Label(text(range.first), Typography.title.copy(fontWeight = FontWeight.Bold), maxLines = 1)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Label(stringResource(R.string.pid_max).uppercase(locale), Typography.label, c.textMuted, 1)
                                Label(text(range.second), Typography.title.copy(fontWeight = FontWeight.Bold), maxLines = 1)
                            }
                        }
                    }
                }
                Divider()
                Section(Icons.info, R.string.pid_what_title, spec?.what ?: ObdR.string.pid_unknown_what)
                Section(Icons.checkCircle, R.string.pid_normal_title, spec?.normal ?: ObdR.string.pid_unknown_normal)
                Section(Icons.warning, R.string.pid_odd_title, spec?.odd ?: ObdR.string.pid_unknown_odd, tint = c.warning)
                Section(Icons.calculator, R.string.pid_formula_title, null, literal = (spec?.formula ?: "—") + "  ·  " + stringResource(R.string.pid_mode_pid, "%02X".format(code)))
            }
        }
    }
}

@Composable
private fun Section(icon: ImageVector, title: Int, body: Int?, tint: Color = LocalColors.current.accent, literal: String? = null) {
    val c = LocalColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, tint, 26.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Label(stringResource(title), Typography.body.copy(fontWeight = FontWeight.Bold))
            Label(literal ?: stringResource(body ?: return@Column), Typography.body, c.textMuted)
        }
    }
}

/**
 * Los códigos de falla: en rojo los guardados (prenden el check engine) y en amarillo los pendientes (todavía no), cada
 * uno con su explicación; abajo, Borrar (con confirmación) y Leer otra vez.
 */
@Composable
internal fun CodesPane(obd: ObdRepository) {
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    val resources = LocalContext.current.resources
    val report = obd.lastReport
    var reading by remember { mutableStateOf(false) }
    val stored = report?.let { (it.stored + it.permanent).distinct() }.orEmpty()
    val pending = report?.pending.orEmpty().filter { it !in stored }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                report == null -> Label(stringResource(if (reading) R.string.pid_reading else R.string.obd_read_failed), Typography.subtitle, c.textMuted, modifier = Modifier.align(Alignment.Center))
                stored.isEmpty() && pending.isEmpty() -> Label(stringResource(R.string.obd_no_codes), Typography.subtitle, c.textMuted, modifier = Modifier.align(Alignment.Center))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(stored) { CodeRow(it, describe(it, resources), c.danger, R.string.pid_code_stored) }
                    items(pending) { CodeRow(it, describe(it, resources), c.warning, R.string.pid_code_pending) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            if (obd.simulated) Label(stringResource(R.string.dashito_codes_demo), Typography.small, c.textMuted, 1, Modifier.weight(1f))
            if (stored.isNotEmpty() || pending.isNotEmpty() || report?.readiness?.mil == true) {
                Confirmed(
                    stringResource(R.string.obd_clear), stringResource(R.string.obd_clear_question), stringResource(R.string.obd_clear_confirm),
                    stringResource(R.string.obd_clear_warning),
                ) { scope.launch { obd.clearCodes(); obd.report(again = true) } }
            }
            Button(stringResource(R.string.obd_read_again), { scope.launch { reading = true; obd.report(again = true); reading = false } }, primary = true)
        }
    }
}

@Composable
private fun CodeRow(code: String, text: String, color: Color, kind: Int) {
    val c = LocalColors.current
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(width = 120.dp, height = 44.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.18f)).border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) { Label(code, Typography.title.copy(fontWeight = FontWeight.Bold), color, 1) }
            Column(Modifier.weight(1f).padding(start = 18.dp)) {
                Label(text, Typography.subtitle.copy(fontWeight = FontWeight.Bold), maxLines = 2)
                Label(stringResource(kind), Typography.body, c.textMuted, 2)
            }
        }
        Divider()
    }
}
