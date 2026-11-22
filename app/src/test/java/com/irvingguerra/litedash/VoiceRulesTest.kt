package com.irvingguerra.litedash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRulesTest {
    @Test fun levelsByDistance() {
        assertEquals(-1, VoiceRules.level(900.0))
        assertEquals(0, VoiceRules.level(500.0))
        assertEquals(0, VoiceRules.level(300.0))
        assertEquals(1, VoiceRules.level(150.0))
        assertEquals(1, VoiceRules.level(80.0))
        assertEquals(2, VoiceRules.level(40.0))
        assertEquals(2, VoiceRules.level(5.0))
    }

    @Test fun speaksOnlyWhenLevelRises() {
        assertFalse(VoiceRules.shouldSpeak(-1, -1))
        assertTrue(VoiceRules.shouldSpeak(0, -1))
        assertFalse(VoiceRules.shouldSpeak(0, 0))
        assertTrue(VoiceRules.shouldSpeak(1, 0))
        // Empezar ya cerca de la vuelta: dice solo el nivel en que está, sin repetir los de antes.
        assertTrue(VoiceRules.shouldSpeak(1, -1))
        assertFalse(VoiceRules.shouldSpeak(1, 2))
    }
}
