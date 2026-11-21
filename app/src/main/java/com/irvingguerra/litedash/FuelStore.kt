package com.irvingguerra.litedash

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.VehicleBus
import com.irvingguerra.litedash.feature.fuel.CreRepository
import com.irvingguerra.litedash.feature.fuel.FuelGrade
import com.irvingguerra.litedash.feature.fuel.FuelReading
import com.irvingguerra.litedash.feature.fuel.FuelTank
import com.irvingguerra.litedash.feature.fuel.StationSnapshot
import com.irvingguerra.litedash.feature.fuel.StationSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * La pantalla Autonomía: el tanque ([FuelTank], con sus cargas), el odómetro que lo mueve (los metros del GPS del
 * viaje, [TripRepository.onStep]) y las gasolineras de la CRE. Guarda en su propio archivo (`fuel`), aparte de los
 * ajustes. No hace nada hasta que el usuario anota una carga: sin ella no cuenta metros ni toca el disco.
 */
class FuelStore(context: Context, trip: TripRepository, private val bus: VehicleBus) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val cre = CreRepository(app)

    var tank by mutableStateOf(FuelTank.fromJson(prefs.getString(TANK, null)))
        private set

    /** Metros que el GPS ha contado desde que se anotó la primera carga (el odómetro del tanque). */
    var odoMeters by mutableDoubleStateOf(prefs.getString(ODO, null)?.toDoubleOrNull() ?: 0.0)
        private set

    var grade by mutableStateOf(FuelGrade.fromStored(prefs.getString(GRADE, null)))
        private set
    var sort by mutableStateOf(if (prefs.getString(SORT, null) == SORT_CHEAP) StationSort.CHEAP else StationSort.NEAR)
        private set

    /** Las gasolineras con precio (null = todavía no se baja nada). */
    var snapshot by mutableStateOf<StationSnapshot?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    /** La última puesta al día de precios falló (sin red): se muestra lo guardado, si hay. */
    var offline by mutableStateOf(false)
        private set

    private var savedOdo = odoMeters

    init {
        trip.onStep = ::step
    }

    /** Lo que el motor ha gastado: km recorridos y litros quemados, medidos con el gasto de gasolina o el MAF (se guardan). */
    private var engineKm = prefs.getString(ENGINE_KM, null)?.toDoubleOrNull() ?: 0.0
    private var engineLiters = prefs.getString(ENGINE_L, null)?.toDoubleOrNull() ?: 0.0
    private var lastStepAt = 0L
    private var savedEngineKm = engineKm

    /** km/L medido por el motor; null mientras no haya al menos [MIN_ENGINE_KM] km con datos. */
    val engineKmPerL: Double? get() = if (engineKm >= MIN_ENGINE_KM && engineLiters > 0.0) engineKm / engineLiters else null

    /** Litros por hora ahora: el gasto que da el auto (PID 5E) o, si no, el MAF (g/s) con las correcciones de mezcla. */
    private fun litersPerHour(): Double? {
        bus.value(Signal.FUEL_RATE)?.takeIf { it > 0f }?.let { return it.toDouble() }
        val maf = bus.value(Signal.MAF)?.takeIf { it > 0f } ?: return null
        // Gasolina: mezcla ideal 14.7 g de aire por g de gasolina y 745 g por litro; las correcciones de mezcla del auto
        // (a corto y largo plazo) dicen cuánto más o menos gasolina echa en realidad.
        val trim = (bus.value(Signal.FUEL_TRIM_SHORT) ?: 0f) + (bus.value(Signal.FUEL_TRIM_LONG) ?: 0f)
        return maf * 3600.0 / (14.7 * 745.0) * (1.0 + trim / 100.0).coerceIn(0.7, 1.4)
    }

    private fun step(meters: Double) {
        val now = System.currentTimeMillis()
        val dt = (now - lastStepAt) / 1000.0
        lastStepAt = now
        // El consumo medido por el motor se va sumando mientras haya datos (sin una carga anotada también).
        if (meters > 0.0 && dt in 0.2..15.0) {
            val lph = litersPerHour()
            if (lph != null) {
                engineKm += meters / 1000.0
                engineLiters += lph * dt / 3600.0
                if (engineKm - savedEngineKm >= 1.0) saveEngine()
            }
        }
        // Sin una carga anotada no hay qué medir del tanque: no se acumula nada (el widget apagado no cuesta).
        if (meters <= 0.0 || tank.levelL == null) return
        odoMeters += meters
        if (odoMeters - savedOdo >= SAVE_EVERY_METERS) saveOdo()
    }

    /** Lo que se muestra ahora; el sensor del auto (OBD) manda si lo da. */
    fun reading(): FuelReading? = tank.reading(odoMeters, bus.value(Signal.FUEL), engineKmPerL)

    fun fillUp(liters: Double?, price: Double?) {
        tank = tank.filledUp(odoMeters, System.currentTimeMillis(), liters, price)
        saveTank()
        saveOdo()
    }

    fun addLiters(liters: Double, price: Double?) {
        tank = tank.added(odoMeters, System.currentTimeMillis(), liters, price)
        saveTank()
        saveOdo()
    }

    fun setTank(capacityL: Double, assumedKmPerL: Double) {
        tank = tank.withCapacity(capacityL, odoMeters).withAssumedKmPerL(assumedKmPerL)
        saveTank()
        saveOdo()
    }

    fun select(grade: FuelGrade) {
        this.grade = grade
        prefs.edit { putString(GRADE, grade.stored) }
    }

    fun select(sort: StationSort) {
        this.sort = sort
        prefs.edit { putString(SORT, if (sort == StationSort.CHEAP) SORT_CHEAP else SORT_NEAR) }
    }

    /**
     * Pone al día las gasolineras: primero lo guardado (sale al instante) y, si ya venció, baja los precios de la CRE.
     * Se llama solo cuando la pantalla se ve; sin red se queda con lo que haya.
     */
    fun refreshStations() {
        // En su propio alcance: al pasar a otra página la pieza se va de la pantalla y su corrutina se cancelaría a medias
        // (la descarga de la CRE tarda y nunca llegaba a terminar).
        if (refreshJob?.isActive == true) return
        refreshJob = refreshScope.launch { updateStations() }
    }

    private var refreshJob: kotlinx.coroutines.Job? = null
    private val refreshScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)

    private suspend fun updateStations() {
        val stale = withContext(Dispatchers.IO) {
            snapshot = try { cre.cached() } catch (e: Throwable) { null }
            try { cre.stale() } catch (e: Throwable) { false }
        }
        if (!stale) return
        loading = true
        try {
            val ok = withContext(Dispatchers.IO) { try { cre.refresh() } catch (e: Throwable) { false } }
            if (ok) snapshot = withContext(Dispatchers.IO) { try { cre.cached() } catch (e: Throwable) { null } }
            offline = !ok || snapshot == null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            offline = true
        } finally {
            loading = false
        }
    }

    private fun saveTank() = prefs.edit { putString(TANK, tank.toJson()) }

    private fun saveEngine() {
        savedEngineKm = engineKm
        prefs.edit { putString(ENGINE_KM, engineKm.toString()); putString(ENGINE_L, engineLiters.toString()) }
    }

    private fun saveOdo() {
        savedOdo = odoMeters
        prefs.edit { putString(ODO, odoMeters.toString()) }
    }

    private companion object {
        const val FILE = "fuel"
        const val TANK = "tank"
        const val ODO = "odo_meters"
        const val GRADE = "grade"
        const val SORT = "sort"
        const val ENGINE_KM = "engine_km"
        const val ENGINE_L = "engine_liters"
        /** Con menos km que estos, lo medido por el motor aún no es confiable. */
        const val MIN_ENGINE_KM = 5.0
        const val SORT_CHEAP = "cheap"
        const val SORT_NEAR = "near"
        /** Cada cuánto se guarda el odómetro (menos escrituras; lo más que se pierde si se corta la luz). */
        const val SAVE_EVERY_METERS = 250.0
    }
}
