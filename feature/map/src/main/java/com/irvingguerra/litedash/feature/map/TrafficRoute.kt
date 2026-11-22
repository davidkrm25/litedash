package com.irvingguerra.litedash.feature.map

import android.content.Context
import com.irvingguerra.litedash.core.ui.Https
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** Lo que dice TomTom de un viaje con el tráfico de ahora: cuánto se tarda y por dónde (pares lat, lon en grados). */
class TrafficTrip(val seconds: Int, val meters: Int, val points: List<DoubleArray>)

/**
 * El recálculo por tráfico: cada pocos minutos se compara cuánto tardaría TU ruta con el tráfico de ahora contra la mejor
 * que ve TomTom. Si la diferencia es grande, se arma una ruta propia (con el mapa de tu estado, para que la guía siga
 * igual) que pase por un punto de la mejor.
 */
object TrafficRoute {
    /** Cada cuánto se revisa (ms): ~2 consultas de las 2 500 gratis diarias de TomTom cada vez. */
    const val CHECK_MS = 3 * 60_000L

    /** Se cambia de ruta si se ahorran al menos [MIN_SAVE_S] y esa parte del tiempo de la actual. */
    const val MIN_SAVE_S = 240
    const val MIN_SAVE_FRACTION = 0.15

    /** El punto de la mejor ruta debe quedar al menos tan lejos (m) de la tuya: si no, es casi la misma. */
    const val MIN_APART_M = 300.0

    /** La ruta nueva no puede ser más larga que esto veces la mejor de TomTom. */
    const val MAX_LONGER = 1.5

    fun worthSwitching(ours: Int, best: Int) = ours - best >= MIN_SAVE_S && (ours - best) >= ours * MIN_SAVE_FRACTION

    /**
     * Un punto de [best] (entre el 15 % y el 85 % de su camino) lo más lejos posible de [mine] (pares lat, lon en millonésimas);
     * null si ninguno queda a [MIN_APART_M] o más (la misma ruta).
     */
    fun pickVia(best: List<DoubleArray>, mine: IntArray): DoubleArray? {
        if (best.size < 4 || mine.size < 4) return null
        var farthest = 0.0
        var pick: DoubleArray? = null
        val from = (best.size * 0.15).toInt()
        val to = (best.size * 0.85).toInt()
        for (i in from..to.coerceAtMost(best.lastIndex)) {
            val p = best[i]
            var near = Double.MAX_VALUE
            var j = 0
            while (j + 3 < mine.size) {
                val d = metersToSegment(p[0], p[1], mine[j] / 1e6, mine[j + 1] / 1e6, mine[j + 2] / 1e6, mine[j + 3] / 1e6)
                if (d < near) near = d
                j += 2
            }
            if (near > farthest) {
                farthest = near
                pick = p
            }
        }
        return if (farthest >= MIN_APART_M) pick else null
    }

    /** Metros del punto ([lat], [lon]) al tramo que va de ([aLat], [aLon]) a ([bLat], [bLon]), en plano (tramos cortos). */
    internal fun metersToSegment(lat: Double, lon: Double, aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val kx = Math.cos(Math.toRadians(lat)) * RoadGraph.M_PER_DEG
        val ky = RoadGraph.M_PER_DEG
        val px = (lon - aLon) * kx
        val py = (lat - aLat) * ky
        val dx = (bLon - aLon) * kx
        val dy = (bLat - aLat) * ky
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else ((px * dx + py * dy) / len2).coerceIn(0.0, 1.0)
        val ex = px - t * dx
        val ey = py - t * dy
        return Math.sqrt(ex * ex + ey * ey)
    }

    /** Hasta [max] puntos repartidos de lo que te falta de [route] (desde el punto [from]), terminando en la llegada. */
    fun waypoints(route: Route, from: Int, max: Int = 20): List<DoubleArray> {
        val n = route.pointCount
        if (from >= n - 1) return emptyList()
        val step = ((n - 1 - from) / max.toDouble()).coerceAtLeast(1.0)
        val out = ArrayList<DoubleArray>()
        var i = from.toDouble() + step
        while (i < n - 1 && out.size < max - 1) {
            val k = i.toInt()
            out += doubleArrayOf(route.points[2 * k] / 1e6, route.points[2 * k + 1] / 1e6)
            i += step
        }
        out += doubleArrayOf(route.points[2 * (n - 1)] / 1e6, route.points[2 * (n - 1) + 1] / 1e6)
        return out
    }

    /** [a] seguida de [b] (que sale de donde llega [a]): una sola ruta con sus vueltas; la unión lleva su vuelta si la hay. */
    fun join(a: Route, b: Route): Route {
        val na = a.pointCount
        val points = a.points + b.points.copyOfRange(2, b.points.size)
        val atM = DoubleArray(points.size / 2)
        val atS = DoubleArray(points.size / 2)
        for (i in 0 until na) {
            atM[i] = a.atM.getOrElse(i) { 0.0 }
            atS[i] = a.atS.getOrElse(i) { 0.0 }
        }
        for (i in 1 until b.pointCount) {
            atM[na - 1 + i] = a.distanceM + b.atM.getOrElse(i) { 0.0 }
            atS[na - 1 + i] = a.seconds + b.atS.getOrElse(i) { 0.0 }
        }
        val maneuvers = ArrayList<Maneuver>()
        maneuvers += a.maneuvers.filter { it.type != ManeuverType.ARRIVE }
        if (na >= 2 && b.pointCount >= 2) {
            val before = bearing(a.points, na - 2, na - 1)
            val after = bearing(b.points, 0, 1)
            var turn = after - before
            while (turn > 180) turn -= 360
            while (turn < -180) turn += 360
            val type = when {
                turn > 150 || turn < -150 -> ManeuverType.UTURN
                turn > 100 -> ManeuverType.SHARP_RIGHT
                turn > 45 -> ManeuverType.RIGHT
                turn > 20 -> ManeuverType.SLIGHT_RIGHT
                turn < -100 -> ManeuverType.SHARP_LEFT
                turn < -45 -> ManeuverType.LEFT
                turn < -20 -> ManeuverType.SLIGHT_LEFT
                else -> null
            }
            if (type != null) maneuvers += Maneuver(type, turn.toFloat(), b.mainRoad, na - 1, a.distanceM)
        }
        for (m in b.maneuvers) {
            if (m.type == ManeuverType.DEPART) continue
            maneuvers += Maneuver(m.type, m.angle, m.road, m.point + na - 1, m.atM + a.distanceM)
        }
        return Route(points, a.distanceM + b.distanceM, a.seconds + b.seconds, a.mainRoad.ifEmpty { b.mainRoad }, maneuvers, atM, atS)
    }

    /** El viaje de TomTom con tráfico por [via] (en orden, la llegada al final) desde ([fromLat], [fromLon]); null = sin red o falló. */
    fun trip(context: Context, key: String, fromLat: Double, fromLon: Double, via: List<DoubleArray>): TrafficTrip? {
        if (via.isEmpty()) return null
        val path = buildString {
            append(fromLat).append(',').append(fromLon)
            for (p in via) append(':').append(p[0]).append(',').append(p[1])
        }
        val url = "https://api.tomtom.com/routing/1/calculateRoute/$path/json?key=$key&traffic=true&travelMode=car&routeType=fast"
        val body = try {
            val c = Https.get(context, url, "application/json")
            try {
                c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                c.disconnect()
            }
        } catch (e: IOException) {
            return null
        }
        return parseTrip(body)
    }

    internal fun parseTrip(json: String): TrafficTrip? = try {
        val r = JSONObject(json).getJSONArray("routes").getJSONObject(0)
        val s = r.getJSONObject("summary")
        val legs = r.getJSONArray("legs")
        val pts = ArrayList<DoubleArray>()
        for (i in 0 until legs.length()) {
            val ps = legs.getJSONObject(i).getJSONArray("points")
            for (j in 0 until ps.length()) ps.getJSONObject(j).let { pts += doubleArrayOf(it.getDouble("latitude"), it.getDouble("longitude")) }
        }
        TrafficTrip(s.getInt("travelTimeInSeconds"), s.getInt("lengthInMeters"), pts)
    } catch (_: JSONException) {
        null
    }
}
