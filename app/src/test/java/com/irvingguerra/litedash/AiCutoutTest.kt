package com.irvingguerra.litedash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCutoutTest {
    @Test fun inputHasModelShape() {
        val t = aiInput(IntArray(64 * 32) { 0xFFFF8040.toInt() }, 64, 32)
        assertEquals(3 * AI_SIZE * AI_SIZE, t.size)
    }

    @Test fun maskKeepsForegroundAndClearsBackground() {
        val w = 40; val h = 20
        val mask = FloatArray(AI_SIZE * AI_SIZE) { if ((it % AI_SIZE) < AI_SIZE / 2) 1f else 0f }
        val out = applyMask(IntArray(w * h) { 0xFF336699.toInt() }, w, h, mask)
        assertEquals(0xFF, out[5] ushr 24)
        assertEquals(0, out[w - 2] ushr 24)
        assertTrue(out[5] and 0xFFFFFF == 0x336699)
    }

    @Test fun boundsTrimEmptyBorders() {
        val w = 20; val h = 10
        val px = IntArray(w * h).also { for (y in 3..5) for (x in 6..12) it[y * w + x] = 0xFF112233.toInt() }
        assertEquals(listOf(2, 0, 17, 10), contentBounds(px, w, h)!!.toList())
        assertEquals(null, contentBounds(IntArray(w * h), w, h))
    }
}
