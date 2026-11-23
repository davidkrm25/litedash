package com.irvingguerra.litedash.feature.fuel

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Una carga de gasolina anotada por el usuario. [liters] null = llenó pero no escribió cuántos. */
data class Fill(val atMs: Long, val odoMeters: Double, val liters: Double?, val pricePerLiter: Double?, val full: Boolean)

/** Lo que se gastó entre dos tanques llenos: [km] recorridos con [liters] quemados. */
data class Burn(val km: Double, val liters: Double)

/** De dónde sale el nivel del tanque que se muestra. */
enum class LevelSource { SENSOR, ESTIMATE }

/** Todo lo que se muestra en la pantalla Autonomía, ya calculado. */
data class FuelReading(
    val levelL: Double,
    val capacityL: Double,
    val rangeKm: Double,
    val kmPerL: Double,
    /** false = [kmPerL] es el supuesto (aún no hay dos tanques llenos con litros anotados). */
    val calibrated: Boolean,
    val burns: Int,
    val source: LevelSource,
    /** Km recorridos desde la última carga; null = nunca se anotó una. */
    val kmSinceFill: Double?,
    val litersUsed: Double?,
    val lastFillAtMs: Long?,
    /** true = [kmPerL] sale de lo que el motor ha gastado (MAF o gasto de gasolina), no del supuesto. */
    val fromEngine: Boolean = false,
    /** Km que faltan para que el motor mida el consumo (0 = ya se midió). */
    val engineKmLeft: Double = 0.0,
)

/**
 * El tanque del auto: capacidad, cuánta gasolina quedó en la última carga y cuánto se ha recorrido desde entonces.
 * Sin Android: el odómetro (metros acumulados del GPS) llega como número y todo se calcula aquí, así se prueba solo.
 *
 * Cómo se estima: al «llenar el tanque» se sabe que hay [capacityL]. Desde ahí, litros = km ÷ km/L. El km/L se
 * **calibra** con los tanques llenos seguidos si el usuario escribe los litros que entraron (lo que entró en el
 * segundo lleno es lo que se quemó entre los dos); mientras no hay, se usa [assumedKmPerL].
 * Si el auto da el nivel por OBD (en %), manda el sensor.
 */
data class FuelTank(
    val capacityL: Double = DEFAULT_CAPACITY_L,
    val assumedKmPerL: Double = DEFAULT_KM_PER_L,
    /** Litros en el tanque en el punto [levelOdo] del odómetro; null = nunca se anotó una carga. */
    val levelL: Double? = null,
    val levelOdo: Double = 0.0,
    val lastFullOdo: Double? = null,
    /** Litros agregados con «Cargué unos litros» desde el último lleno. */
    val addedSinceFull: Double = 0.0,
    val burns: List<Burn> = emptyList(),
    val fills: List<Fill> = emptyList(),
) {
    /** km/L medido con los últimos [BURNS_USED] tanques; null mientras no hay ninguno. */
    val measuredKmPerL: Double?
        get() = burns.takeLast(BURNS_USED).let { last ->
            val liters = last.sumOf { it.liters }
            if (last.isEmpty() || liters <= 0.0) null else last.sumOf { it.km } / liters
        }

    val kmPerL get() = measuredKmPerL ?: assumedKmPerL

    /** El km/L con el que se cuenta: el de tus cargas, si no el medido por el motor ([engineKmPerL]) y, al final, el supuesto. */
    fun kmPerL(engineKmPerL: Double? = null) = measuredKmPerL ?: engineKmPerL?.takeIf { it in MIN_KM_PER_L..MAX_KM_PER_L } ?: assumedKmPerL

    /** Litros que quedan en [odoMeters], sin pasar de lo que cabe ni bajar de cero. */
    fun levelAt(odoMeters: Double, kmPerL: Double = this.kmPerL): Double? {
        val base = levelL ?: return null
        val km = ((odoMeters - levelOdo) / 1000.0).coerceAtLeast(0.0)
        return (base - km / kmPerL).coerceIn(0.0, capacityL)
    }

    /**
     * «Llené el tanque» a [now] con el odómetro en [odoMeters]. Si se anotaron los [liters] que entraron (y ya había
     * un lleno antes), de ahí sale una medida de consumo.
     */
    fun filledUp(odoMeters: Double, now: Long, liters: Double? = null, price: Double? = null): FuelTank {
        val entered = liters?.takeIf { it > 0.0 }
        val km = lastFullOdo?.let { (odoMeters - it) / 1000.0 }
        val burn = if (entered != null && km != null && km >= MIN_BURN_KM) {
            val burned = entered + addedSinceFull
            Burn(km, burned).takeIf { km / burned in PLAUSIBLE_KM_PER_L }
        } else null
        return copy(
            levelL = capacityL,
            levelOdo = odoMeters,
            lastFullOdo = odoMeters,
            addedSinceFull = 0.0,
            burns = if (burn != null) (burns + burn).takeLast(MAX_BURNS) else burns,
            fills = (fills + Fill(now, odoMeters, entered, price?.takeIf { it > 0.0 }, full = true)).takeLast(MAX_FILLS),
        )
    }

    /** «Cargué unos litros»: [liters] más en el tanque (sin pasar de la capacidad). */
    fun added(odoMeters: Double, now: Long, liters: Double, price: Double? = null): FuelTank {
        if (liters <= 0.0) return this
        val current = levelAt(odoMeters) ?: 0.0
        return copy(
            levelL = (current + liters).coerceAtMost(capacityL),
            levelOdo = odoMeters,
            addedSinceFull = addedSinceFull + liters,
            fills = (fills + Fill(now, odoMeters, liters, price?.takeIf { it > 0.0 }, full = false)).takeLast(MAX_FILLS),
        )
    }

    /** Otra capacidad: lo que había queda tal cual, sin pasar de lo que cabe. */
    fun withCapacity(liters: Double, odoMeters: Double): FuelTank {
        val c = liters.coerceIn(MIN_CAPACITY_L, MAX_CAPACITY_L)
        val current = levelAt(odoMeters)
        return copy(capacityL = c, levelL = current?.coerceAtMost(c), levelOdo = if (current != null) odoMeters else levelOdo)
    }

    fun withAssumedKmPerL(value: Double) = copy(assumedKmPerL = value.coerceIn(MIN_KM_PER_L, MAX_KM_PER_L))

    /**
     * Lo que se muestra con el odómetro en [odoMeters]. [sensorPercent] es el nivel que da el auto por OBD (0–100), si lo
     * da: manda sobre la estimación. null si no hay de dónde sacar el nivel (nunca se anotó una carga y no hay sensor).
     */
    fun reading(odoMeters: Double, sensorPercent: Float? = null, engineKmPerL: Double? = null): FuelReading? {
        val perL = kmPerL(engineKmPerL)
        // Un 0 % exacto casi siempre es un sensor que no da dato (muchos autos/adaptadores responden 0): no manda sobre lo anotado.
        val sensor = sensorPercent?.takeIf { it > 0.5f && it <= 100f }
        val level = if (sensor != null) capacityL * sensor / 100.0 else levelAt(odoMeters, perL) ?: return null
        val since = lastFullOdo?.let { ((odoMeters - it) / 1000.0).coerceAtLeast(0.0) }
        return FuelReading(
            levelL = level,
            capacityL = capacityL,
            rangeKm = level * perL,
            kmPerL = perL,
            calibrated = measuredKmPerL != null,
            burns = burns.size,
            source = if (sensor != null) LevelSource.SENSOR else LevelSource.ESTIMATE,
            kmSinceFill = since,
            litersUsed = since?.let { it / perL },
            lastFillAtMs = fills.lastOrNull { it.full }?.atMs,
            fromEngine = measuredKmPerL == null && engineKmPerL?.let { it in MIN_KM_PER_L..MAX_KM_PER_L } == true,
        )
    }

    fun toJson(): String = JSONObject()
        .put("cap", capacityL).put("assumed", assumedKmPerL)
        .apply {
            levelL?.let { put("level", it) }
            lastFullOdo?.let { put("fullOdo", it) }
        }
        .put("levelOdo", levelOdo).put("added", addedSinceFull)
        .put("burns", JSONArray().also { a -> burns.forEach { a.put(JSONObject().put("km", it.km).put("l", it.liters)) } })
        .put(
            "fills",
            JSONArray().also { a ->
                fills.forEach { f ->
                    a.put(
                        JSONObject().put("t", f.atMs).put("odo", f.odoMeters).put("full", f.full)
                            .apply {
                                f.liters?.let { put("l", it) }
                                f.pricePerLiter?.let { put("p", it) }
                            },
                    )
                }
            },
        )
        .toString()

    companion object {
        const val DEFAULT_CAPACITY_L = 45.0
        /** El supuesto de fábrica (14.7 km/L = 6.8 L/100 km, el promedio real del auto): con 45 L da ~660 km. */
        const val DEFAULT_KM_PER_L = 14.7
        /** El supuesto de las versiones anteriores: si es lo que quedó guardado, nadie lo eligió y pasa al nuevo. */
        private val OLD_DEFAULTS = setOf(12.0, 15.4)
        const val MIN_CAPACITY_L = 5.0
        const val MAX_CAPACITY_L = 400.0
        const val MIN_KM_PER_L = 2.0
        const val MAX_KM_PER_L = 40.0
        /** Menos que esto entre dos llenos es ruido (un lleno de más, el mismo ticket): no calibra. */
        const val MIN_BURN_KM = 20.0
        const val BURNS_USED = 5
        private const val MAX_BURNS = 20
        private const val MAX_FILLS = 50
        private val PLAUSIBLE_KM_PER_L = 2.0..40.0

        /** Lo guardado; si no se entiende (o no hay), un tanque nuevo. Nunca truena: es un archivo del propio equipo. */
        fun fromJson(text: String?): FuelTank {
            if (text.isNullOrBlank()) return FuelTank()
            return try {
                val o = JSONObject(text)
                val cap = o.optDouble("cap", DEFAULT_CAPACITY_L).coerceIn(MIN_CAPACITY_L, MAX_CAPACITY_L)
                FuelTank(
                    capacityL = cap,
                    assumedKmPerL = o.optDouble("assumed", DEFAULT_KM_PER_L).let { if (it in OLD_DEFAULTS) DEFAULT_KM_PER_L else it }.coerceIn(MIN_KM_PER_L, MAX_KM_PER_L),
                    levelL = if (o.has("level")) o.getDouble("level").coerceIn(0.0, cap) else null,
                    levelOdo = o.optDouble("levelOdo", 0.0),
                    lastFullOdo = if (o.has("fullOdo")) o.getDouble("fullOdo") else null,
                    addedSinceFull = o.optDouble("added", 0.0).coerceAtLeast(0.0),
                    burns = o.optJSONArray("burns").objects().map { Burn(it.getDouble("km"), it.getDouble("l")) },
                    fills = o.optJSONArray("fills").objects().map {
                        Fill(
                            it.getLong("t"), it.getDouble("odo"), if (it.has("l")) it.getDouble("l") else null,
                            if (it.has("p")) it.getDouble("p") else null, it.optBoolean("full", true),
                        )
                    },
                )
            } catch (e: JSONException) {
                FuelTank()
            }
        }

        private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    }
}
