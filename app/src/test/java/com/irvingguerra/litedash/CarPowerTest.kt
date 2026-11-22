package com.irvingguerra.litedash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPowerTest {
    @Test
    fun duermeSoloSinCorrienteYConPermiso() {
        assertTrue(sleepDue(charging = false, adminActive = true))
        assertFalse(sleepDue(charging = true, adminActive = true))
        assertFalse(sleepDue(charging = false, adminActive = false))
    }

    @Test
    fun esperaTreintaSegundos() {
        assertEquals(30_000L, CAR_SLEEP_DELAY_MS)
    }
}
