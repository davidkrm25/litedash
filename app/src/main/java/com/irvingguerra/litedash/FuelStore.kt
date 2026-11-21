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

    private fun step(meters: Double) {
        // Sin una carga anotada no hay qué medir: no se acumula nada (el widget apagado no cuesta).
        if (meters <= 0.0 || tank.levelL == null) return
        odoMeters += meters
        if (odoMeters - savedOdo >= SAVE_EVERY_METERS) saveOdo()
    }

    /** Lo que se muestra ahora; el sensor del auto (OBD) manda si lo da. */
    fun reading(): FuelReading? = tank.reading(odoMeters, bus.value(Signal.FUEL))

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
    suspend fun refreshStations() {
        val stale = withContext(Dispatchers.IO) {
            snapshot = cre.cached()
            cre.stale()
        }
        if (!stale) return
        loading = true
        try {
            val ok = withContext(Dispatchers.IO) { cre.refresh() }
            offline = !ok
            if (ok) snapshot = withContext(Dispatchers.IO) { cre.cached() }
        } finally {
            loading = false
        }
    }

    private fun saveTank() = prefs.edit { putString(TANK, tank.toJson()) }

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
        const val SORT_CHEAP = "cheap"
        const val SORT_NEAR = "near"
        /** Cada cuánto se guarda el odómetro (menos escrituras; lo más que se pierde si se corta la luz). */
        const val SAVE_EVERY_METERS = 250.0
    }
}
