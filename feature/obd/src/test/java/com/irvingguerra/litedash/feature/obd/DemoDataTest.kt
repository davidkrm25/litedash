package com.irvingguerra.litedash.feature.obd

import com.irvingguerra.litedash.core.vehicle.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoDataTest {
    @Test fun reportsThirtyTwoData() = assertEquals(32, DemoData.supported.size)

    @Test fun everyDataHasBytesAndMeaning() {
        val v = DemoData.signals(10f)
        for (code in DemoData.supported) {
            val raw = DemoData.raw(code, v, cleared = false)
            assertNotNull("sin bytes para %02X".format(code), raw)
            assertNotNull("sin valor para %02X".format(code), pidSpec(code)!!.value(raw!!))
        }
    }

    @Test fun bytesDecodeBackToTheSignal() {
        val v = DemoData.signals(7f)
        fun back(code: Int) = (pidSpec(code)!!.value(DemoData.raw(code, v, false)!!) as PidValue.Num).v
        assertEquals(v.getValue(Signal.COOLANT), back(0x05), 1f)
        assertEquals(v.getValue(Signal.RPM), back(0x0C), 1f)
        assertEquals(v.getValue(Signal.VOLTAGE), back(0x42), 0.01f)
    }

    @Test fun codesClearAndLightGoesOff() {
        val on = DemoData.report(cleared = false)
        assertEquals(listOf("P0420"), on.stored)
        assertEquals(listOf("P0171"), on.pending)
        assertTrue(on.readiness!!.mil)
        val off = DemoData.report(cleared = true)
        assertTrue(off.stored.isEmpty() && off.pending.isEmpty() && !off.readiness!!.mil)
    }

    @Test fun speedNeverGoesNegative() = (0..200).forEach { assertTrue(DemoData.signals(it / 3f).getValue(Signal.SPEED) >= 0f) }
}
