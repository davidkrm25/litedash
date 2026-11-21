package com.irvingguerra.litedash.feature.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FactoryPagesTest {
    private val grids = listOf(Grid(19, 10), Grid(26, 14), Grid(20, 8), Grid(12, 20), Grid(40, 22))

    @Test
    fun hasSixPagesAndNothingOverlapsOrLeavesTheGrid() {
        for (g in grids) {
            val pages = factoryPages(g)
            assertEquals(FACTORY_PAGES, pages.size)
            for (tiles in pages) {
                for (t in tiles) {
                    assertTrue("${t.type} fuera de $g", t.col >= 0 && t.row >= 0 && t.col + t.width <= g.columns && t.row + t.height <= g.rows)
                    assertTrue(t.width >= 1 && t.height >= 1)
                }
                for (i in tiles.indices) for (j in i + 1 until tiles.size) assertTrue("${tiles[i].type} se enciman con ${tiles[j].type} en $g", !tiles[i].overlaps(tiles[j]))
            }
        }
    }

    @Test
    fun tabletGridKeepsEveryWidget() {
        val pages = factoryPages(Grid(19, 10), limits = { SizeLimits(3, 2) })
        assertEquals(8, pages[0].size)
        assertEquals(5, pages[1].size)
        assertEquals(8, pages[2].size)
        assertEquals(3, pages[3].size)
        assertEquals(1, pages[4].size)
        assertEquals(5, pages[5].size)
    }
}
