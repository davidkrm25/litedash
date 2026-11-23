package com.irvingguerra.litedash.feature.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El tanque: nivel, autonomía y calibración con las cargas. */
class FuelTankTest {
    private fun km(k: Double) = k * 1000.0

    @Test
    fun withoutAFillThereIsNothingToShow() {
        assertNull(FuelTank().reading(km(10.0)))
    }

    @Test
    fun fullTankRangeUsesAssumedConsumption() {
        val t = FuelTank(capacityL = 45.0, assumedKmPerL = 15.0).filledUp(km(1000.0), now = 1)
        val r = t.reading(km(1000.0))!!
        assertEquals(45.0, r.levelL, 1e-9)
        assertEquals(675.0, r.rangeKm, 1e-9)
        assertFalse(r.calibrated)
        assertEquals(LevelSource.ESTIMATE, r.source)
    }

    @Test
    fun levelDropsWithDistance() {
        val t = FuelTank(capacityL = 45.0, assumedKmPerL = 15.0).filledUp(km(1000.0), now = 1)
        val r = t.reading(km(1150.0))!!
        assertEquals(35.0, r.levelL, 1e-9)
        assertEquals(150.0, r.kmSinceFill!!, 1e-9)
        assertEquals(10.0, r.litersUsed!!, 1e-9)
        assertEquals(525.0, r.rangeKm, 1e-9)
    }

    @Test
    fun levelNeverGoesBelowZero() {
        val t = FuelTank(capacityL = 10.0, assumedKmPerL = 10.0).filledUp(0.0, now = 1)
        assertEquals(0.0, t.reading(km(500.0))!!.levelL, 1e-9)
    }

    @Test
    fun twoFullTanksWithLitersCalibrate() {
        // Lleno, 300 km, lleno con 20 L: 15 km/L.
        val t = FuelTank(assumedKmPerL = 8.0)
            .filledUp(km(0.0), now = 1)
            .filledUp(km(300.0), now = 2, liters = 20.0)
        assertEquals(15.0, t.measuredKmPerL!!, 1e-9)
        val r = t.reading(km(300.0))!!
        assertTrue(r.calibrated)
        assertEquals(1, r.burns)
        assertEquals(15.0 * 45.0, r.rangeKm, 1e-9)
    }

    @Test
    fun partialAddsCountAsBurnedFuel() {
        // 300 km, entre medio se agregaron 10 L y al llenar entraron 10 L: se quemaron 20 L → 15 km/L.
        val t = FuelTank().filledUp(km(0.0), now = 1)
            .added(km(100.0), now = 2, liters = 10.0)
            .filledUp(km(300.0), now = 3, liters = 10.0)
        assertEquals(15.0, t.measuredKmPerL!!, 1e-9)
        assertEquals(0.0, t.addedSinceFull, 1e-9)
    }

    @Test
    fun fillWithoutLitersOrTooShortDoesNotCalibrate() {
        val noLiters = FuelTank().filledUp(0.0, 1).filledUp(km(300.0), 2)
        assertNull(noLiters.measuredKmPerL)
        val tooShort = FuelTank().filledUp(0.0, 1).filledUp(km(5.0), 2, liters = 1.0)
        assertNull(tooShort.measuredKmPerL)
        // 300 km con 1 L (60 km/L) es imposible: no calibra.
        val absurd = FuelTank().filledUp(0.0, 1).filledUp(km(300.0), 2, liters = 5.0)
        assertNull(absurd.measuredKmPerL)
    }

    @Test
    fun addedLitersNeverExceedCapacity() {
        val t = FuelTank(capacityL = 45.0, assumedKmPerL = 10.0).filledUp(0.0, 1)
            .added(km(100.0), 2, liters = 30.0)
        assertEquals(45.0, t.levelAt(km(100.0))!!, 1e-9)
    }

    @Test
    fun addedWithoutPriorFillStartsFromZero() {
        val t = FuelTank().added(km(10.0), 1, liters = 20.0)
        assertEquals(20.0, t.levelAt(km(10.0))!!, 1e-9)
    }

    @Test
    fun sensorWinsOverEstimate() {
        val t = FuelTank(capacityL = 40.0, assumedKmPerL = 10.0).filledUp(0.0, 1)
        val r = t.reading(km(100.0), sensorPercent = 50f)!!
        assertEquals(LevelSource.SENSOR, r.source)
        assertEquals(20.0, r.levelL, 1e-9)
        assertEquals(200.0, r.rangeKm, 1e-9)
    }

    @Test
    fun sensorAtZeroDoesNotOverrideTheFill() {
        val t = FuelTank(capacityL = 45.0, assumedKmPerL = 14.7).filledUp(0.0, 0L)
        val r = t.reading(km(100.0), sensorPercent = 0f)!!
        assertEquals(LevelSource.ESTIMATE, r.source)
        assertTrue(r.levelL > 30.0)
    }

    @Test
    fun sensorWorksEvenWithoutAFill() {
        val r = FuelTank(capacityL = 40.0, assumedKmPerL = 10.0).reading(0.0, sensorPercent = 25f)
        assertNotNull(r)
        assertEquals(10.0, r!!.levelL, 1e-9)
        assertNull(r.kmSinceFill)
    }

    @Test
    fun changingCapacityKeepsWhatWasThereWithinTheNewLimit() {
        val t = FuelTank(capacityL = 45.0, assumedKmPerL = 10.0).filledUp(0.0, 1).withCapacity(40.0, km(0.0))
        assertEquals(40.0, t.levelAt(0.0)!!, 1e-9)
        assertEquals(40.0, t.capacityL, 1e-9)
    }

    @Test
    fun jsonRoundTrip() {
        val t = FuelTank(capacityL = 50.0, assumedKmPerL = 11.0)
            .filledUp(km(0.0), 1, liters = null, price = 22.5)
            .added(km(50.0), 2, liters = 5.0)
            .filledUp(km(400.0), 3, liters = 30.0, price = 23.0)
        val back = FuelTank.fromJson(t.toJson())
        assertEquals(t, back)
    }

    @Test
    fun brokenJsonGivesAFreshTank() {
        assertEquals(FuelTank(), FuelTank.fromJson("{no es json"))
        assertEquals(FuelTank(), FuelTank.fromJson(null))
        assertEquals(FuelTank(), FuelTank.fromJson(""))
    }

    @Test
    fun engineMeasurementReplacesAssumedButNotYourFillUps() {
        val tank = FuelTank().filledUp(0.0, 0L)
        val engine = tank.reading(50_000.0, null, engineKmPerL = 15.0)!!
        assertEquals(15.0, engine.kmPerL, 1e-9)
        assertTrue(engine.fromEngine)
        // Un dato absurdo del motor se ignora y vuelve el supuesto.
        assertEquals(FuelTank.DEFAULT_KM_PER_L, tank.reading(50_000.0, null, engineKmPerL = 90.0)!!.kmPerL, 1e-9)
        // Con tanques llenos medidos, mandan tus cargas.
        val measured = tank.copy(burns = listOf(Burn(400.0, 40.0)))
        val r = measured.reading(0.0, null, engineKmPerL = 15.0)!!
        assertEquals(10.0, r.kmPerL, 1e-9)
        assertTrue(!r.fromEngine)
    }

    @Test
    fun fullTankWithDefaultAssumptionGivesAbout660Km() {
        val r = FuelTank().filledUp(0.0, now = 1).reading(0.0)!!
        assertEquals(661.5, r.rangeKm, 1.0)
    }

    @Test
    fun savedOldDefaultMovesToTheNewOne() {
        val old = FuelTank(assumedKmPerL = 12.0).toJson()
        assertEquals(FuelTank.DEFAULT_KM_PER_L, FuelTank.fromJson(old).assumedKmPerL, 1e-9)
        assertEquals(FuelTank.DEFAULT_KM_PER_L, FuelTank.fromJson(FuelTank(assumedKmPerL = 15.4).toJson()).assumedKmPerL, 1e-9)
        val own = FuelTank(assumedKmPerL = 9.5).toJson()
        assertEquals(9.5, FuelTank.fromJson(own).assumedKmPerL, 1e-9)
    }
}
