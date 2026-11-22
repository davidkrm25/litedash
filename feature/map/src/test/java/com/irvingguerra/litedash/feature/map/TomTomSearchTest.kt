package com.irvingguerra.litedash.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TomTomSearchTest {
    private val json = """{"results":[
      {"type":"POI","position":{"lat":19.5,"lon":-99.2},"poi":{"name":"Mi Empresa","categories":["shop"]},
       "address":{"freeformAddress":"Calle 1 100, Tlalnepantla","municipality":"Tlalnepantla"}},
      {"type":"Street","position":{"lat":19.4,"lon":-99.1},"address":{"streetName":"Reforma","freeformAddress":"Reforma, CDMX"}},
      {"type":"POI","poi":{"name":"Sin posición"}}]}"""

    @Test fun parsesPlacesAndStreets() {
        val hits = parseTomTom(json, null, null)!!
        assertEquals(2, hits.size)
        assertEquals("Mi Empresa", hits[0].name)
        assertEquals(PlaceCategory.SHOP, hits[0].category)
        assertEquals("Calle 1 100, Tlalnepantla", hits[0].address)
        assertEquals(PlaceKind.STREET, hits[1].kind)
    }

    @Test fun badJsonIsNull() = assertNull(parseTomTom("<html>", null, null))

    @Test fun mergeSkipsRepeatedNearby() {
        val a = parseTomTom(json, null, null)!!
        val merged = mergeHits(a, a, 10)
        assertEquals(2, merged.size)
    }
}

class MyPlaceTest {
    private val p = MyPlace("SERINYCO", "Av. 5 de Mayo 58", listOf("empresa"))

    @Test fun matchesNameAliasAndPartial() {
        org.junit.Assert.assertTrue(p.matches("serinyco"))
        org.junit.Assert.assertTrue(p.matches("serin"))
        org.junit.Assert.assertTrue(p.matches("mi empresa"))
        org.junit.Assert.assertFalse(p.matches("ab"))
        org.junit.Assert.assertFalse(p.matches("gasolinera"))
    }
}
