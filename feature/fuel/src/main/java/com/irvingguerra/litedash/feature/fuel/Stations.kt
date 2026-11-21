package com.irvingguerra.litedash.feature.fuel

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Tipo de gasolina que publica la CRE. [xml] es el valor de `type` en su archivo de precios. */
enum class FuelGrade(val xml: String, val stored: String) {
    REGULAR("regular", "regular"),
    PREMIUM("premium", "premium"),
    DIESEL("diesel", "diesel");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.stored == value } ?: REGULAR
        fun fromXml(value: String?) = entries.firstOrNull { it.xml.equals(value, ignoreCase = true) }
    }
}

/** Una gasolinera: su nombre, dónde está y lo que cobra por cada tipo ($/L). */
data class Station(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val prices: Map<FuelGrade, Double>,
)

/** Una gasolinera con su distancia en línea recta a [km] del auto. */
data class NearStation(val station: Station, val km: Double)

/** Cómo se ordena la lista: la más cerca primero, o la más barata primero. */
enum class StationSort { NEAR, CHEAP }

/** Una fila del archivo de lugares de la CRE (sin precios). */
data class Place(val id: String, val name: String, val lat: Double, val lon: Double)

/**
 * Lee los dos archivos abiertos de la CRE (datos abiertos de precios de gasolina):
 *
 * - **lugares** (`/publicaciones/places`): `<place place_id="2039"><name>…</name><cre_id>PL/…</cre_id>
 *   <location><x>-99.1</x><y>19.4</y></location></place>` (x = longitud, y = latitud).
 * - **precios** (`/publicaciones/prices`): `<place place_id="2039"><gas_price type="regular">22.95</gas_price>…</place>`.
 *
 * Se lee con un recorrido de texto (no con un parser XML): son ~1 MB y así lo mismo corre en el equipo y en las
 * pruebas. Lo que no se entiende se salta, nunca truena: el archivo es de un tercero.
 */
object CreFeed {
    private val PLACE_START = Regex("<place\\b([^>]*)>")
    private val ATTR_ID = Regex("""place_id\s*=\s*["']([^"']*)["']""")
    private val PRICE = Regex("""<gas_price\b[^>]*?type\s*=\s*["']([^"']*)["'][^>]*>\s*([^<\s]*)\s*</gas_price>""")

    fun parsePlaces(xml: String): List<Place> = parsePlaces(java.io.StringReader(xml))

    fun parsePrices(xml: String): Map<String, Map<FuelGrade, Double>> = parsePrices(java.io.StringReader(xml))

    /** Recorre [reader] por pedazos (un `<place>` a la vez) para no cargar el archivo entero en memoria. */
    fun parsePlaces(reader: java.io.Reader): List<Place> {
        val out = ArrayList<Place>()
        eachBlock(reader) { id, body ->
            val lon = tag(body, "x")?.toDoubleOrNull()
            val lat = tag(body, "y")?.toDoubleOrNull()
            if (lon != null && lat != null && validPoint(lat, lon)) {
                val name = tag(body, "name")?.let(::decode)?.trim().orEmpty().ifEmpty { "Gasolinera" }
                out += Place(id, name, lat, lon)
            }
        }
        return out
    }

    fun parsePrices(reader: java.io.Reader): Map<String, Map<FuelGrade, Double>> {
        val out = HashMap<String, Map<FuelGrade, Double>>()
        eachBlock(reader) { id, body ->
            val prices = gradesIn(body)
            if (prices.isNotEmpty()) out[id] = prices
        }
        return out
    }

    private inline fun eachBlock(reader: java.io.Reader, onBlock: (String, String) -> Unit) {
        val buf = CharArray(16 * 1024)
        val pending = StringBuilder()
        while (true) {
            val n = reader.read(buf)
            if (n < 0) break
            pending.append(buf, 0, n)
            var consumed = 0
            while (true) {
                val close = pending.indexOf("</place>", consumed)
                if (close < 0) break
                val chunk = pending.substring(consumed, close)
                consumed = close + 8
                val open = PLACE_START.find(chunk) ?: continue
                val id = ATTR_ID.find(open.groupValues[1])?.groupValues?.get(1) ?: continue
                onBlock(id, chunk.substring(open.range.last + 1))
            }
            if (consumed > 0) pending.delete(0, consumed)
            // Un pedazo sin cierre que crece sin parar no es un archivo válido: se descarta.
            if (pending.length > 1_000_000) pending.setLength(0)
        }
    }

    /** Lugares con al menos un precio publicado. */
    fun merge(places: List<Place>, prices: Map<String, Map<FuelGrade, Double>>): List<Station> =
        places.mapNotNull { p -> prices[p.id]?.let { Station(p.id, p.name, p.lat, p.lon, it) } }

    private fun gradesIn(body: String): Map<FuelGrade, Double> {
        val m = LinkedHashMap<FuelGrade, Double>()
        for (hit in PRICE.findAll(body)) {
            val grade = FuelGrade.fromXml(hit.groupValues[1]) ?: continue
            val price = hit.groupValues[2].toDoubleOrNull() ?: continue
            // Los precios de verdad andan entre 5 y 100 $/L; un 0 o un 9999 es un error del que reporta.
            if (price in MIN_PRICE..MAX_PRICE) m[grade] = price
        }
        return m
    }

    private fun tag(body: String, name: String): String? {
        val open = body.indexOf("<$name>")
        if (open < 0) return null
        val from = open + name.length + 2
        val close = body.indexOf("</$name>", from)
        return if (close < 0) null else body.substring(from, close).trim()
    }

    private fun decode(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")

    private fun validPoint(lat: Double, lon: Double) = abs(lat) <= 90 && abs(lon) <= 180 && !(lat == 0.0 && lon == 0.0)

    const val MIN_PRICE = 5.0
    const val MAX_PRICE = 100.0
}

/** Distancia en línea recta entre dos puntos, en km (haversine). */
fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * 6371.0 * asin(sqrt(a))
}

/** Hasta dónde se busca: más lejos de esto no es "cerca" para tanque. */
const val SEARCH_RADIUS_KM = 25.0

/**
 * Las [limit] gasolineras de [grade] más convenientes cerca de ([lat], [lon]):
 * [StationSort.NEAR] = la más cerca primero; [StationSort.CHEAP] = la más barata primero (a igual precio, la más cerca).
 * Solo cuentan las que publican precio de [grade] y están a menos de [radiusKm].
 */
fun List<Station>.around(
    lat: Double,
    lon: Double,
    grade: FuelGrade,
    sort: StationSort,
    limit: Int = 5,
    radiusKm: Double = SEARCH_RADIUS_KM,
): List<NearStation> {
    // Caja barata antes del haversine: 1° de latitud son ~111 km.
    val dLat = radiusKm / 111.0
    val dLon = radiusKm / (111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.05))
    val near = ArrayList<NearStation>()
    for (s in this) {
        if (grade !in s.prices) continue
        if (abs(s.lat - lat) > dLat || abs(s.lon - lon) > dLon) continue
        val km = distanceKm(lat, lon, s.lat, s.lon)
        if (km <= radiusKm) near += NearStation(s, km)
    }
    val sorted = when (sort) {
        StationSort.NEAR -> near.sortedBy { it.km }
        StationSort.CHEAP -> near.sortedWith(compareBy({ it.station.prices.getValue(grade) }, { it.km }))
    }
    return sorted.take(limit)
}
