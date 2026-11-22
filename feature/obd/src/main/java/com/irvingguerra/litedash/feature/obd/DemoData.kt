package com.irvingguerra.litedash.feature.obd

import com.irvingguerra.litedash.core.vehicle.Signal
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Datos de un auto inventado para ver cómo queda Diagnóstico y el tablero sin el auto: valores que se mueven con el
 * tiempo, 32 datos del modo 01, un código guardado (P0420) y uno pendiente (P0171).
 */
object DemoData {
    /** Los datos que "reporta" el auto simulado. */
    val supported: Set<Int> = PidCatalog.map { it.code }.toSet() - setOf(0x0A, 0x14, 0x3C, 0x4D, 0x4E, 0x5C, 0x5E)

    const val STORED = "P0420"
    const val PENDING = "P0171"

    /** Lo que mide cada señal [t] segundos después de empezar. */
    fun signals(t: Float): Map<String, Float> {
        val speed = (50f + 30f * sin(t * 0.35f)).coerceAtLeast(0f)
        return mapOf(
            Signal.SPEED to speed,
            Signal.RPM to 800f + speed * 32f + 120f * sin(t * 1.7f),
            Signal.LOAD to 25f + speed * 0.4f,
            Signal.MAP to 35f + speed * 0.7f,
            Signal.MAF to 3f + speed * 0.25f,
            Signal.COOLANT to 89f + 3f * sin(t * 0.05f),
            Signal.INTAKE to 28f,
            Signal.FUEL to 58f,
            Signal.VOLTAGE to 14.2f + 0.1f * sin(t * 0.3f),
            Signal.BARO to 86f,
            Signal.AMBIENT to 24f,
            Signal.THROTTLE to 14f + speed * 0.5f,
            Signal.FUEL_RATE to 1f + speed * 0.1f,
            Signal.FUEL_TRIM_SHORT to 3.1f + 1.5f * sin(t * 0.9f),
            Signal.FUEL_TRIM_LONG to 2.3f,
            Signal.FUEL_PRESSURE to 350f,
            Signal.TIMING to 18f + 3f * sin(t * 0.8f),
            Signal.RUN_TIME to 627f + t,
            Signal.DISTANCE_MIL to 0f,
            Signal.OIL to 95f,
        )
    }

    /** Los bytes que contestaría el auto a ese dato (lo contrario de lo que hace el catálogo). */
    fun raw(code: Int, v: Map<String, Float>, cleared: Boolean): IntArray? {
        fun f(s: String) = v[s] ?: 0f
        fun byte(x: Float) = x.roundToInt().coerceIn(0, 255)
        fun pct(x: Float) = byte(x * 255f / 100f)
        fun two(x: Int) = intArrayOf((x shr 8) and 0xFF, x and 0xFF)
        return when (code) {
            0x01 -> intArrayOf(if (cleared) 0x00 else 0x81, 0x07, 0xE5, 0x00)
            0x03 -> intArrayOf(2, 0)
            0x04 -> intArrayOf(pct(f(Signal.LOAD)))
            0x05 -> intArrayOf(byte(f(Signal.COOLANT) + 40f))
            0x06 -> intArrayOf(byte(f(Signal.FUEL_TRIM_SHORT) * 128f / 100f + 128f))
            0x07 -> intArrayOf(byte(f(Signal.FUEL_TRIM_LONG) * 128f / 100f + 128f))
            0x0B -> intArrayOf(byte(f(Signal.MAP)))
            0x0C -> two((f(Signal.RPM) * 4f).roundToInt())
            0x0D -> intArrayOf(byte(f(Signal.SPEED)))
            0x0E -> intArrayOf(byte((f(Signal.TIMING) + 64f) * 2f))
            0x0F -> intArrayOf(byte(f(Signal.INTAKE) + 40f))
            0x10 -> two((f(Signal.MAF) * 100f).roundToInt())
            0x11 -> intArrayOf(pct(f(Signal.THROTTLE)))
            0x13 -> intArrayOf(0x03)
            0x15 -> intArrayOf(150, 0xFF)
            0x1C -> intArrayOf(1)
            0x1F -> two(f(Signal.RUN_TIME).roundToInt())
            0x21 -> two(0)
            0x2E -> intArrayOf(pct(12f))
            0x2F -> intArrayOf(pct(f(Signal.FUEL)))
            0x30 -> intArrayOf(42)
            0x31 -> two(1234)
            0x33 -> intArrayOf(byte(f(Signal.BARO)))
            0x42 -> two((f(Signal.VOLTAGE) * 1000f).roundToInt())
            0x43 -> two((f(Signal.LOAD) * 255f / 100f * 1.1f).roundToInt())
            0x44 -> two(0x8000)
            0x45 -> intArrayOf(pct(f(Signal.THROTTLE) - 10f))
            0x46 -> intArrayOf(byte(f(Signal.AMBIENT) + 40f))
            0x47 -> intArrayOf(pct(f(Signal.THROTTLE) + 10f))
            0x49 -> intArrayOf(pct(f(Signal.THROTTLE) * 0.8f))
            0x4A -> intArrayOf(pct(f(Signal.THROTTLE) * 0.5f))
            0x4C -> intArrayOf(pct(f(Signal.THROTTLE) + 5f))
            else -> null
        }
    }

    fun report(cleared: Boolean) = EngineReport(
        readiness = Readiness(mil = !cleared, monitors = emptyList()),
        stored = if (cleared) emptyList() else listOf(STORED),
        pending = if (cleared) emptyList() else listOf(PENDING),
        permanent = emptyList(),
        vin = null,
        voltage = 14.2f,
    )
}
