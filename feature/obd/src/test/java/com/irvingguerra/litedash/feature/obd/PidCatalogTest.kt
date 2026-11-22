package com.irvingguerra.litedash.feature.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PidCatalogTest {
    private fun num(code: Int, vararg bytes: Int) = (pidSpec(code)!!.value(bytes) as PidValue.Num).v

    @Test fun coolantMinus40() = assertEquals(89f, num(0x05, 0x81), 0.01f)

    @Test fun rpmQuarter() = assertEquals(1726f, num(0x0C, 0x1A, 0xF8), 0.01f)

    @Test fun lambdaAndVoltage() {
        assertEquals(1.0f, num(0x44, 0x80, 0x00), 0.001f)
        assertEquals(14.2f, num(0x42, 0x37, 0x78), 0.001f)
    }

    @Test fun fuelTrimIsSigned() {
        val v = pidSpec(0x06)!!.value(intArrayOf(0x84)) as PidValue.Num
        assertTrue(v.signed)
        assertEquals(3.125f, v.v, 0.001f)
    }

    @Test fun timeSinceStart() = assertEquals(627, (pidSpec(0x1F)!!.value(intArrayOf(0x02, 0x73)) as PidValue.Time).seconds)

    @Test fun oxygenSensorsAreCounted() {
        val v = pidSpec(0x13)!!.value(intArrayOf(0x03)) as PidValue.Res
        assertEquals(2, v.n)
    }

    @Test fun obdStandardNames() = assertEquals("OBD-II", (pidSpec(0x1C)!!.value(intArrayOf(1)) as PidValue.Lit).text)

    @Test fun checkEngineCountsCodesAndLight() {
        assertEquals(0, (pidSpec(0x01)!!.value(intArrayOf(0x00)) as PidValue.Res).n ?: 0)
        assertEquals(2, (pidSpec(0x01)!!.value(intArrayOf(0x82)) as PidValue.Res).n)
    }

    @Test fun shortReplyGivesNothing() = assertNull(pidSpec(0x0C)!!.value(intArrayOf()))

    @Test fun listHidesTheSupportBitmaps() {
        assertEquals(listOf(0x04, 0x05, 0x21), diagPids(setOf(0x00, 0x04, 0x05, 0x20, 0x21, 0x40)))
        assertNotNull(pidSpec(0x2E))
    }
}
