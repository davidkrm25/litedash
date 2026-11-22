package com.irvingguerra.litedash.feature.obd

import android.content.res.Resources
import java.util.Locale

/** Lo que dice un dato del motor: un número con su unidad, un texto del catálogo o un tiempo. */
sealed interface PidValue {
    /** [signed]: con signo (+3.1 %) para los ajustes de mezcla; [decimals]: cuántos decimales. */
    data class Num(val v: Float, val unit: String = "", val decimals: Int = 0, val signed: Boolean = false) : PidValue
    /** Un texto de los recursos; con [n] se usa como plural de esa cuenta. */
    data class Res(val res: Int, val n: Int? = null) : PidValue
    data class Lit(val text: String) : PidValue
    data class Time(val seconds: Int) : PidValue
}

/** Un dato estándar del modo 01 con sus textos: nombre, descripción corta y los cuatro apartados del detalle. */
class PidSpec(
    val code: Int,
    val name: Int,
    val desc: Int,
    val what: Int,
    val normal: Int,
    val odd: Int,
    val formula: String,
    val value: (IntArray) -> PidValue?,
)

/** "34 %", "0.75 V", "18.0°", "10:27 min": el valor listo para mostrar. */
fun PidValue.format(resources: Resources, locale: Locale): String = when (this) {
    is PidValue.Num -> {
        val number = String.format(locale, if (signed) "%+." + decimals + "f" else "%." + decimals + "f", v)
        when {
            unit.isEmpty() -> number
            unit == "°" -> number + unit
            else -> "$number $unit"
        }
    }
    is PidValue.Res -> if (n == null) resources.getString(res) else resources.getQuantityString(res, n, n)
    is PidValue.Lit -> text
    is PidValue.Time -> String.format(locale, "%d:%02d min", seconds / 60, seconds % 60)
}

/** El número de un valor (para llevar mínimo y máximo); null si es un texto. */
fun PidValue.number(): Float? = (this as? PidValue.Num)?.v

private fun num(v: Float, unit: String, decimals: Int, signed: Boolean = false) = PidValue.Num(v, unit, decimals, signed)

private fun mil(d: IntArray): PidValue? {
    val a = d.getOrNull(0) ?: return null
    val codes = a and 0x7F
    return if (codes == 0 && a and 0x80 == 0) PidValue.Res(R.string.pid_mil_none) else PidValue.Res(R.plurals.pid_mil_n, codes)
}

private fun fuelSystem(d: IntArray): PidValue? = when (d.getOrNull(0)) {
    2 -> PidValue.Res(R.string.pid_fuel_normal)
    1, 4 -> PidValue.Res(R.string.pid_fuel_open)
    8, 16 -> PidValue.Res(R.string.pid_fuel_fault)
    else -> null
}

private fun sensors(a: Int): PidValue = PidValue.Res(R.plurals.pid_sensors, Integer.bitCount(a and 0xFF))

private fun obdStandard(a: Int): PidValue = PidValue.Lit(
    when (a) {
        1 -> "OBD-II"; 2 -> "OBD"; 3 -> "OBD + OBD-II"; 4 -> "OBD-I"; 6 -> "EOBD"; 7 -> "EOBD + OBD-II"
        9 -> "EOBD + OBD"; 10 -> "EOBD + OBD + OBD-II"; 11 -> "JOBD"; else -> a.toString()
    },
)

/** Los datos del modo 01 que LiteDash sabe explicar, por orden de PID. */
val PidCatalog: List<PidSpec> = listOf(
    PidSpec(0x01, R.string.pid_01_name, R.string.pid_01_desc, R.string.pid_01_what, R.string.pid_01_normal, R.string.pid_01_odd, "A: bit 7 = foco, bits 0–6 = códigos") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; mil(it) },
    PidSpec(0x03, R.string.pid_03_name, R.string.pid_03_desc, R.string.pid_03_what, R.string.pid_03_normal, R.string.pid_03_odd, "A: 2 = lazo cerrado (normal), 1 / 4 = abierto, 8 / 16 = falla") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; fuelSystem(it) },
    PidSpec(0x04, R.string.pid_04_name, R.string.pid_04_desc, R.string.pid_04_what, R.string.pid_04_normal, R.string.pid_04_odd, "A × 100 / 255") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(a * 100f / 255, "%", 0) },
    PidSpec(0x05, R.string.pid_05_name, R.string.pid_05_desc, R.string.pid_05_what, R.string.pid_05_normal, R.string.pid_05_odd, "A – 40") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(a - 40f, "°C", 0) },
    PidSpec(0x06, R.string.pid_06_name, R.string.pid_06_desc, R.string.pid_06_what, R.string.pid_06_normal, R.string.pid_06_odd, "(A – 128) × 100 / 128") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num((a - 128f) * 100f / 128, "%", 1, true) },
    PidSpec(0x07, R.string.pid_07_name, R.string.pid_07_desc, R.string.pid_07_what, R.string.pid_07_normal, R.string.pid_07_odd, "(A – 128) × 100 / 128") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num((a - 128f) * 100f / 128, "%", 1, true) },
    PidSpec(0x0A, R.string.pid_0a_name, R.string.pid_0a_desc, R.string.pid_0a_what, R.string.pid_0a_normal, R.string.pid_0a_odd, "A × 3") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(a * 3f, "kPa", 0) },
    PidSpec(0x0B, R.string.pid_0b_name, R.string.pid_0b_desc, R.string.pid_0b_what, R.string.pid_0b_normal, R.string.pid_0b_odd, "A") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(a.toFloat(), "kPa", 0) },
    PidSpec(0x0C, R.string.pid_0c_name, R.string.pid_0c_desc, R.string.pid_0c_what, R.string.pid_0c_normal, R.string.pid_0c_odd, "(256 × A + B) / 4") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(ab / 4f, "rpm", 0) },
    PidSpec(0x0D, R.string.pid_0d_name, R.string.pid_0d_desc, R.string.pid_0d_what, R.string.pid_0d_normal, R.string.pid_0d_odd, "A") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(a.toFloat(), "km/h", 0) },
    PidSpec(0x0E, R.string.pid_0e_name, R.string.pid_0e_desc, R.string.pid_0e_what, R.string.pid_0e_normal, R.string.pid_0e_odd, "A / 2 – 64") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(a / 2f - 64f, "°", 1, true) },
    PidSpec(0x0F, R.string.pid_0f_name, R.string.pid_0f_desc, R.string.pid_0f_what, R.string.pid_0f_normal, R.string.pid_0f_odd, "A – 40") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(a - 40f, "°C", 0) },
    PidSpec(0x10, R.string.pid_10_name, R.string.pid_10_desc, R.string.pid_10_what, R.string.pid_10_normal, R.string.pid_10_odd, "(256 × A + B) / 100") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(ab / 100f, "g/s", 1) },
    PidSpec(0x11, R.string.pid_11_name, R.string.pid_11_desc, R.string.pid_11_what, R.string.pid_11_normal, R.string.pid_11_odd, "A × 100 / 255") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(a * 100f / 255, "%", 0) },
    PidSpec(0x13, R.string.pid_13_name, R.string.pid_13_desc, R.string.pid_13_what, R.string.pid_13_normal, R.string.pid_13_odd, "Cuenta de bits de A") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; sensors(a) },
    PidSpec(0x14, R.string.pid_14_name, R.string.pid_14_desc, R.string.pid_14_what, R.string.pid_14_normal, R.string.pid_14_odd, "A / 200") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(a / 200f, "V", 2) },
    PidSpec(0x15, R.string.pid_15_name, R.string.pid_15_desc, R.string.pid_15_what, R.string.pid_15_normal, R.string.pid_15_odd, "A / 200") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(a / 200f, "V", 2) },
    PidSpec(0x1C, R.string.pid_1c_name, R.string.pid_1c_desc, R.string.pid_1c_what, R.string.pid_1c_normal, R.string.pid_1c_odd, "A (tabla SAE J1979)") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; obdStandard(a) },
    PidSpec(0x1F, R.string.pid_1f_name, R.string.pid_1f_desc, R.string.pid_1f_what, R.string.pid_1f_normal, R.string.pid_1f_odd, "256 × A + B (segundos)") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; PidValue.Time(ab) },
    PidSpec(0x21, R.string.pid_21_name, R.string.pid_21_desc, R.string.pid_21_what, R.string.pid_21_normal, R.string.pid_21_odd, "256 × A + B") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(ab.toFloat(), "km", 0) },
    PidSpec(0x2E, R.string.pid_2e_name, R.string.pid_2e_desc, R.string.pid_2e_what, R.string.pid_2e_normal, R.string.pid_2e_odd, "A × 100 / 255") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(a * 100f / 255, "%", 0) },
    PidSpec(0x2F, R.string.pid_2f_name, R.string.pid_2f_desc, R.string.pid_2f_what, R.string.pid_2f_normal, R.string.pid_2f_odd, "A × 100 / 255") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(a * 100f / 255, "%", 0) },
    PidSpec(0x30, R.string.pid_30_name, R.string.pid_30_desc, R.string.pid_30_what, R.string.pid_30_normal, R.string.pid_30_odd, "A") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(a.toFloat(), "", 0) },
    PidSpec(0x31, R.string.pid_31_name, R.string.pid_31_desc, R.string.pid_31_what, R.string.pid_31_normal, R.string.pid_31_odd, "256 × A + B") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(ab.toFloat(), "km", 0) },
    PidSpec(0x33, R.string.pid_33_name, R.string.pid_33_desc, R.string.pid_33_what, R.string.pid_33_normal, R.string.pid_33_odd, "A") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(a.toFloat(), "kPa", 0) },
    PidSpec(0x3C, R.string.pid_3c_name, R.string.pid_3c_desc, R.string.pid_3c_what, R.string.pid_3c_normal, R.string.pid_3c_odd, "(256 × A + B) / 10 – 40") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(ab / 10f - 40f, "°C", 0) },
    PidSpec(0x42, R.string.pid_42_name, R.string.pid_42_desc, R.string.pid_42_what, R.string.pid_42_normal, R.string.pid_42_odd, "(256 × A + B) / 1000") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(ab / 1000f, "V", 1) },
    PidSpec(0x43, R.string.pid_43_name, R.string.pid_43_desc, R.string.pid_43_what, R.string.pid_43_normal, R.string.pid_43_odd, "(256 × A + B) × 100 / 255") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(ab * 100f / 255, "%", 0) },
    PidSpec(0x44, R.string.pid_44_name, R.string.pid_44_desc, R.string.pid_44_what, R.string.pid_44_normal, R.string.pid_44_odd, "(256 × A + B) × 2 / 65536") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(ab * 2f / 65536, "", 2) },
    PidSpec(0x45, R.string.pid_45_name, R.string.pid_45_desc, R.string.pid_45_what, R.string.pid_45_normal, R.string.pid_45_odd, "A × 100 / 255") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(a * 100f / 255, "%", 0) },
    PidSpec(0x46, R.string.pid_46_name, R.string.pid_46_desc, R.string.pid_46_what, R.string.pid_46_normal, R.string.pid_46_odd, "A – 40") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(a - 40f, "°C", 0) },
    PidSpec(0x47, R.string.pid_47_name, R.string.pid_47_desc, R.string.pid_47_what, R.string.pid_47_normal, R.string.pid_47_odd, "A × 100 / 255") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(a * 100f / 255, "%", 0) },
    PidSpec(0x49, R.string.pid_49_name, R.string.pid_49_desc, R.string.pid_49_what, R.string.pid_49_normal, R.string.pid_49_odd, "A × 100 / 255") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(a * 100f / 255, "%", 0) },
    PidSpec(0x4A, R.string.pid_4a_name, R.string.pid_4a_desc, R.string.pid_4a_what, R.string.pid_4a_normal, R.string.pid_4a_odd, "A × 100 / 255") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(a * 100f / 255, "%", 0) },
    PidSpec(0x4C, R.string.pid_4c_name, R.string.pid_4c_desc, R.string.pid_4c_what, R.string.pid_4c_normal, R.string.pid_4c_odd, "A × 100 / 255") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(a * 100f / 255, "%", 0) },
    PidSpec(0x4D, R.string.pid_4d_name, R.string.pid_4d_desc, R.string.pid_4d_what, R.string.pid_4d_normal, R.string.pid_4d_odd, "256 × A + B") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(ab.toFloat(), "min", 0) },
    PidSpec(0x4E, R.string.pid_4e_name, R.string.pid_4e_desc, R.string.pid_4e_what, R.string.pid_4e_normal, R.string.pid_4e_odd, "256 × A + B") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(ab.toFloat(), "min", 0) },
    PidSpec(0x5C, R.string.pid_5c_name, R.string.pid_5c_desc, R.string.pid_5c_what, R.string.pid_5c_normal, R.string.pid_5c_odd, "A – 40") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(a - 40f, "°C", 0) },
    PidSpec(0x5E, R.string.pid_5e_name, R.string.pid_5e_desc, R.string.pid_5e_what, R.string.pid_5e_normal, R.string.pid_5e_odd, "(256 × A + B) / 20") { val a = it.getOrNull(0) ?: return@PidSpec null; val b = it.getOrNull(1) ?: 0; val ab = a * 256 + b; num(ab / 20f, "L/h", 1) },
)

private val byCode = PidCatalog.associateBy { it.code }

fun pidSpec(code: Int): PidSpec? = byCode[code]

/** Los datos que el auto reporta para mostrar: sin los que solo dicen cuáles otros existen (00, 20, 40…). */
fun diagPids(supported: Set<Int>): List<Int> = supported.filter { it % 0x20 != 0 }.sorted()

/** Los bytes tal cual llegaron, en hexadecimal ("81" o "1A F4"). */
fun rawHex(bytes: IntArray): String = bytes.joinToString(" ") { "%02X".format(it) }
