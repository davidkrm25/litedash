package com.irvingguerra.litedash.feature.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FactoryPagesTest {
    /** Los mínimos reales de cada tipo (celdas de 48 dp), como en Tiles.kt. */
    private val mins = mapOf(
        TileTypes.MUSIC to SizeLimits(6, 2), TileTypes.MAP to SizeLimits(4, 2), TileTypes.WEATHER to SizeLimits(4, 2),
        TileTypes.TRIP to SizeLimits(4, 2), TileTypes.SPEEDOMETER to SizeLimits(4, 2), TileTypes.GAUGE to SizeLimits(4, 2),
        TileTypes.PROJECTION to SizeLimits(4, 2), TileTypes.TRIP_STAT to SizeLimits(3, 1), TileTypes.FUEL_PART to SizeLimits(3, 2),
        TileTypes.DIAG to SizeLimits(3, 1),
    )
    private val limits = { type: String -> mins[type] ?: SizeLimits(1, 1) }
    private val grids = listOf(Grid(19, 10), Grid(26, 14), Grid(20, 8), Grid(17, 7), Grid(12, 20), Grid(40, 22))

    @Test
    fun hasSixPagesAndNothingOverlapsOrLeavesTheGrid() {
        for (g in grids) {
            val pages = factoryPages(g, limits)
            assertEquals(FACTORY_PAGES, pages.size)
            for (tiles in pages) {
                for (t in tiles) {
                    assertTrue("${t.type} fuera de $g", t.col >= 0 && t.row >= 0 && t.col + t.width <= g.columns && t.row + t.height <= g.rows)
                    val m = limits(t.type)
                    assertTrue("${t.type} bajo su mínimo en $g", t.width >= m.minWidth && t.height >= m.minHeight)
                }
                for (i in tiles.indices) for (j in i + 1 until tiles.size) assertTrue("${tiles[i].type} se enciman con ${tiles[j].type} en $g", !tiles[i].overlaps(tiles[j]))
            }
        }
    }

    @Test
    fun tabletGridKeepsEveryWidget() {
        val pages = factoryPages(Grid(26, 14), limits)
        assertEquals(listOf(8, 5, 8, 6, 1, 5), pages.map { it.size })
    }

    @Test
    fun phoneGridStillShowsEveryPage() {
        // 17×7 es un celular de lado: cabe casi todo, y de lo que no, se quita lo menos importante.
        val pages = factoryPages(Grid(17, 7), limits)
        for ((i, tiles) in pages.withIndex()) assertTrue("página $i vacía", tiles.isNotEmpty())
        val inicio = pages[0].map { it.type }
        assertTrue(TileTypes.MUSIC in inicio && TileTypes.SPEEDOMETER in inicio && TileTypes.WEATHER in inicio)
        assertEquals(6, pages[2].count { it.type == TileTypes.TRIP_STAT && it.data != TripStatKinds.RESET })
        assertTrue(pages[2].any { it.data == TripStatKinds.RESET })
        assertEquals(3, pages[1].count { it.type == TileTypes.GAUGE && !it.data.startsWith("rpm") })
        assertTrue(pages[5].any { it.data == DiagParts.ADAPTER } && pages[5].any { it.data == DiagParts.CONNECT })
        assertTrue(pages[3].any { it.data == FuelParts.STATIONS })
    }

    @Test
    fun splitRespectsMinimums() {
        assertEquals(listOf(4, 7, 6), split(17, listOf(0.22, 0.46, 0.32), listOf(4, 4, 6)))
        assertEquals(null, split(5, listOf(1.0, 1.0), listOf(3, 3)))
    }
}
