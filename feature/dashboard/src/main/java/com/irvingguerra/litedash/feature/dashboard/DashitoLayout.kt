package com.irvingguerra.litedash.feature.dashboard

import kotlin.math.roundToInt

/** Cuántas páginas trae el diseño de fábrica (Inicio, Tablero, Viaje, Autonomía, Clima y Diagnóstico). */
const val FACTORY_PAGES = 6

/** Sube cuando cambia el diseño de fábrica: al abrir la app con un número menor se pone una vez. */
const val FACTORY_VERSION = 1

/** Qué trae cada tarjeta del viaje (van en [Tile.data] de [TileTypes.TRIP_STAT]). */
object TripStatKinds {
    const val DISTANCE = "distance"
    const val TIME = "time"
    const val AVERAGE = "average"
    const val MAX = "max"
    const val CONSUMPTION = "consumption"
    const val FUEL_USED = "fuel_used"
    const val RESET = "reset"
}

/** Las partes de Autonomía (van en [Tile.data] de [TileTypes.FUEL_PART]). */
object FuelParts {
    const val GAUGE = "gauge"
    const val STATIONS = "stations"
    const val STATS = "stats"
}

/** Las partes de Diagnóstico (van en [Tile.data] de [TileTypes.DIAG]). */
object DiagParts {
    const val ADAPTER = "adapter"
    const val PIDS = "pids"
    const val CHECK = "check"
    const val LIST = "list"
    const val CONNECT = "connect"
}

/**
 * Las seis páginas de fábrica en la cuadrícula [c], con los mismos widgets que Dashito. Cada pieza se pone con
 * fracciones del ancho y del alto; las orillas compartidas dan el mismo entero, así nunca se enciman. Lo que no
 * alcance su tamaño mínimo ([limits]) se omite.
 */
fun factoryPages(c: Grid, limits: (String) -> SizeLimits = { SizeLimits(1, 1) }, skip: (String) -> Boolean = { false }): List<List<Tile>> {
    fun x(f: Double) = (f * c.columns).roundToInt()
    fun y(f: Double) = (f * c.rows).roundToInt()
    fun page(index: Int, build: PageBuilder.() -> Unit): List<Tile> {
        val b = PageBuilder(index, ::x, ::y, limits, skip)
        b.build()
        return b.tiles
    }
    val car = "front=car"
    return listOf(
        // Inicio: cuatro datos a la izquierda, el velocímetro con tu auto al centro, y música, clima y Android Auto a la derecha.
        page(0) {
            put(TileTypes.GAUGE, 0.0, 0.0, 0.22, 0.25, Gauge.COOLANT)
            put(TileTypes.TRIP, 0.0, 0.25, 0.22, 0.5)
            put(TileTypes.GAUGE, 0.0, 0.5, 0.22, 0.75, Gauge.VOLTAGE)
            put(TileTypes.GAUGE, 0.0, 0.75, 0.22, 1.0, Gauge.CONSUMPTION)
            put(TileTypes.SPEEDOMETER, 0.22, 0.0, 0.7, 1.0, car, plain = true)
            put(TileTypes.MUSIC, 0.7, 0.0, 1.0, 0.6)
            put(TileTypes.WEATHER, 0.7, 0.6, 1.0, 0.8)
            put(TileTypes.PROJECTION, 0.7, 0.8, 1.0, 1.0)
        },
        // Tablero: velocidad y revoluciones grandes, y tres datos en columna.
        page(1) {
            put(TileTypes.SPEEDOMETER, 0.0, 0.0, 0.4, 1.0, plain = true)
            put(TileTypes.GAUGE, 0.4, 0.0, 0.8, 1.0, Gauge.RPM, plain = true)
            put(TileTypes.GAUGE, 0.8, 0.0, 1.0, 0.34, Gauge.COOLANT)
            put(TileTypes.GAUGE, 0.8, 0.34, 1.0, 0.67, Gauge.VOLTAGE)
            put(TileTypes.GAUGE, 0.8, 0.67, 1.0, 1.0, Gauge.CONSUMPTION)
        },
        // Viaje: seis tarjetas, el botón de reiniciar y el mapa.
        page(2) {
            put(TileTypes.TRIP_STAT, 0.0, 0.0, 0.26, 0.28, TripStatKinds.DISTANCE)
            put(TileTypes.TRIP_STAT, 0.26, 0.0, 0.52, 0.28, TripStatKinds.TIME)
            put(TileTypes.TRIP_STAT, 0.0, 0.28, 0.26, 0.56, TripStatKinds.AVERAGE)
            put(TileTypes.TRIP_STAT, 0.26, 0.28, 0.52, 0.56, TripStatKinds.MAX)
            put(TileTypes.TRIP_STAT, 0.0, 0.56, 0.26, 0.84, TripStatKinds.CONSUMPTION)
            put(TileTypes.TRIP_STAT, 0.26, 0.56, 0.52, 0.84, TripStatKinds.FUEL_USED)
            put(TileTypes.TRIP_STAT, 0.0, 0.84, 0.52, 1.0, TripStatKinds.RESET, plain = true)
            put(TileTypes.MAP, 0.52, 0.0, 1.0, 1.0)
        },
        // Autonomía: el medidor con sus botones, las gasolineras y las tarjetas.
        page(3) {
            put(TileTypes.FUEL_PART, 0.0, 0.0, 0.5, 1.0, FuelParts.GAUGE, plain = true)
            put(TileTypes.FUEL_PART, 0.5, 0.0, 0.77, 1.0, FuelParts.STATIONS)
            put(TileTypes.FUEL_PART, 0.77, 0.0, 1.0, 1.0, FuelParts.STATS, plain = true)
        },
        // Clima: el widget completo.
        page(4) {
            put(TileTypes.WEATHER, 0.0, 0.0, 1.0, 1.0)
        },
        // Diagnóstico: tres tarjetas, conectar y la lista de datos del motor o de códigos.
        page(5) {
            put(TileTypes.DIAG, 0.0, 0.0, 0.29, 0.2, DiagParts.ADAPTER)
            put(TileTypes.DIAG, 0.0, 0.2, 0.29, 0.5, DiagParts.PIDS)
            put(TileTypes.DIAG, 0.0, 0.5, 0.29, 0.7, DiagParts.CHECK)
            put(TileTypes.DIAG, 0.0, 0.8, 0.29, 1.0, DiagParts.CONNECT, plain = true)
            put(TileTypes.DIAG, 0.29, 0.0, 1.0, 1.0, DiagParts.LIST)
        },
    )
}

/** Las señales del OBD que usa el diseño (las mismas de `Signal`, que vive en otro módulo). */
private object Gauge {
    const val RPM = "rpm"
    const val COOLANT = "coolant_temp"
    const val VOLTAGE = "voltage"
    const val CONSUMPTION = "consumption"
}

private class PageBuilder(
    private val page: Int,
    private val x: (Double) -> Int,
    private val y: (Double) -> Int,
    private val limits: (String) -> SizeLimits,
    private val skip: (String) -> Boolean,
) {
    val tiles = mutableListOf<Tile>()

    fun put(type: String, left: Double, top: Double, right: Double, bottom: Double, data: String = "", plain: Boolean = false) {
        if (skip(type)) return
        val col = x(left)
        val row = y(top)
        val w = x(right) - col
        val h = y(bottom) - row
        val m = limits(type)
        if (w < m.minWidth || h < m.minHeight) return
        tiles += Tile("$type-f$page-${tiles.size}", type, col, row, w, h, data, plain)
    }
}
