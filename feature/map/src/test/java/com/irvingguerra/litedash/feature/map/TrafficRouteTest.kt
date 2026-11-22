package com.irvingguerra.litedash.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficRouteTest {
    @Test fun switchesOnlyWhenSavingIsBigEnough() {
        assertFalse(TrafficRoute.worthSwitching(1000, 900))
        assertFalse(TrafficRoute.worthSwitching(3600, 3500))
        assertTrue(TrafficRoute.worthSwitching(1500, 1000))
        assertFalse(TrafficRoute.worthSwitching(6000, 5700)) // 5 min pero menos del 15 %
    }

    @Test fun parsesTrip() {
        val json = """{"routes":[{"summary":{"travelTimeInSeconds":900,"lengthInMeters":8000},
          "legs":[{"points":[{"latitude":19.1,"longitude":-99.1},{"latitude":19.2,"longitude":-99.2}]}]}]}"""
        val t = TrafficRoute.parseTrip(json)!!
        assertEquals(900, t.seconds)
        assertEquals(2, t.points.size)
        assertNull(TrafficRoute.parseTrip("<html>"))
    }

    @Test fun viaIsFarFromMyRouteOrNull() {
        val mine = IntArray(0).let { intArrayOf(19_000_000, -99_000_000, 19_010_000, -99_000_000, 19_020_000, -99_000_000, 19_030_000, -99_000_000) }
        val same = (0..9).map { doubleArrayOf(19.0 + it * 0.003, -99.0) }
        assertNull(TrafficRoute.pickVia(same, mine))
        val apart = (0..9).map { doubleArrayOf(19.0 + it * 0.003, -99.0 + 0.02) }
        assertNotNull(TrafficRoute.pickVia(apart, mine))
    }

    @Test fun joinKeepsTotalsAndManeuvers() {
        fun leg(lat0: Int, lon0: Int, lat1: Int, lon1: Int, type: ManeuverType) = Route(
            intArrayOf(lat0, lon0, lat1, lon1), 1000.0, 100.0, "A",
            listOf(Maneuver(ManeuverType.DEPART, 0f, "A", 0, 0.0), Maneuver(type, 0f, "", 1, 1000.0)),
            doubleArrayOf(0.0, 1000.0), doubleArrayOf(0.0, 100.0),
        )
        val a = leg(19_000_000, -99_000_000, 19_010_000, -99_000_000, ManeuverType.ARRIVE)
        val b = leg(19_010_000, -99_000_000, 19_010_000, -99_010_000, ManeuverType.ARRIVE)
        val j = TrafficRoute.join(a, b)
        assertEquals(3, j.pointCount)
        assertEquals(2000.0, j.distanceM, 0.1)
        assertEquals(2000.0, j.atM[2], 0.1)
        assertEquals(ManeuverType.ARRIVE, j.maneuvers.last().type)
        assertTrue(j.maneuvers.any { it.type == ManeuverType.LEFT || it.type == ManeuverType.SHARP_LEFT })
    }
}
