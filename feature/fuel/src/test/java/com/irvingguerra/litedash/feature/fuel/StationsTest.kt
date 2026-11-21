package com.irvingguerra.litedash.feature.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los archivos de la CRE y el orden de la lista. */
class StationsTest {
    private val places = """
        <?xml version="1.0" encoding="utf-8"?>
        <places>
          <place place_id="1"><name>GASOLINERA A &amp; B</name><cre_id>PL/1/EXP/ES/2015</cre_id><location><x>-99.1000</x><y>19.4000</y></location></place>
          <place place_id="2"><name>Cerca y cara</name><cre_id>PL/2/EXP/ES/2015</cre_id><location><x>-99.1100</x><y>19.4100</y></location></place>
          <place place_id="3"><name>Lejos y barata</name><cre_id>PL/3/EXP/ES/2015</cre_id><location><x>-99.2000</x><y>19.5000</y></location></place>
          <place place_id="4"><name>Sin precio</name><cre_id>PL/4/EXP/ES/2015</cre_id><location><x>-99.1</x><y>19.41</y></location></place>
          <place place_id="5"><name>Sin ubicación</name><cre_id>PL/5/EXP/ES/2015</cre_id></place>
          <place place_id="6"><name>En el mar</name><cre_id>PL/6/EXP/ES/2015</cre_id><location><x>0</x><y>0</y></location></place>
        </places>
    """.trimIndent()

    private val prices = """
        <places>
          <place place_id="1"><gas_price type="regular">23.99</gas_price><gas_price type="premium">29.49</gas_price></place>
          <place place_id="2"><gas_price type="regular">25.10</gas_price><gas_price type="diesel">26.00</gas_price></place>
          <place place_id="3"><gas_price type="regular">22.50</gas_price></place>
          <place place_id="6"><gas_price type="regular">0</gas_price></place>
          <place place_id="7"><gas_price type="regular">9999</gas_price><gas_price type="otro">20</gas_price></place>
        </places>
    """.trimIndent()

    private val stations = CreFeed.merge(CreFeed.parsePlaces(places), CreFeed.parsePrices(prices))

    @Test
    fun placesSkipWhatHasNoPoint() {
        val p = CreFeed.parsePlaces(places).associateBy { it.id }
        assertEquals(setOf("1", "2", "3", "4"), p.keys)
        assertEquals("GASOLINERA A & B", p.getValue("1").name)
        assertEquals(19.4, p.getValue("1").lat, 1e-9)
        assertEquals(-99.1, p.getValue("1").lon, 1e-9)
    }

    @Test
    fun pricesIgnoreNonsense() {
        val p = CreFeed.parsePrices(prices)
        assertEquals(setOf("1", "2", "3"), p.keys)
        assertEquals(23.99, p.getValue("1").getValue(FuelGrade.REGULAR), 1e-9)
        assertEquals(29.49, p.getValue("1").getValue(FuelGrade.PREMIUM), 1e-9)
        assertEquals(26.0, p.getValue("2").getValue(FuelGrade.DIESEL), 1e-9)
    }

    @Test
    fun onlyStationsWithPriceAndPointAreKept() {
        assertEquals(setOf("1", "2", "3"), stations.map { it.id }.toSet())
    }

    @Test
    fun nearestFirstAndCheapestFirst() {
        val near = stations.around(19.40, -99.10, FuelGrade.REGULAR, StationSort.NEAR)
        assertEquals(listOf("1", "2", "3"), near.map { it.station.id })
        val cheap = stations.around(19.40, -99.10, FuelGrade.REGULAR, StationSort.CHEAP)
        assertEquals(listOf("3", "1", "2"), cheap.map { it.station.id })
    }

    @Test
    fun gradeFiltersWhoDoesNotSellIt() {
        assertEquals(listOf("1"), stations.around(19.40, -99.10, FuelGrade.PREMIUM, StationSort.NEAR).map { it.station.id })
        assertEquals(listOf("2"), stations.around(19.40, -99.10, FuelGrade.DIESEL, StationSort.NEAR).map { it.station.id })
    }

    @Test
    fun radiusAndLimit() {
        assertTrue(stations.around(19.40, -99.10, FuelGrade.REGULAR, StationSort.NEAR, radiusKm = 3.0).map { it.station.id } == listOf("1", "2"))
        assertEquals(1, stations.around(19.40, -99.10, FuelGrade.REGULAR, StationSort.NEAR, limit = 1).size)
    }

    @Test
    fun distanceIsRoughlyRight() {
        // 0.01° de latitud ≈ 1.11 km.
        assertEquals(1.11, distanceKm(19.40, -99.10, 19.41, -99.10), 0.02)
    }

    @Test
    fun garbageNeverCrashes() {
        assertEquals(0, CreFeed.parsePlaces("<html>502 Bad Gateway</html>").size)
        assertEquals(0, CreFeed.parsePrices("").size)
        assertNull(CreFeed.parsePrices("<place place_id=\"1\"><gas_price type=\"regular\">abc</gas_price></place>")["1"])
    }
}
