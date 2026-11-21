package com.irvingguerra.litedash.feature.dashboard

/** Cuántas páginas trae el diseño de fábrica (Inicio, Tablero, Viaje, Autonomía, Clima y Diagnóstico). */
const val FACTORY_PAGES = 6

/** Sube cuando cambia el diseño de fábrica: al abrir la app con un número menor se pone una vez. */
const val FACTORY_VERSION = 7

/** Qué trae cada tarjeta del viaje (van en [Tile.data] de [TileTypes.TRIP_STAT]). */
object TripStatKinds {
    const val DISTANCE = "distance"
    const val TIME = "time"
    const val AVERAGE = "average"
    const val MAX = "max"
    const val CONSUMPTION = "consumption"
    const val FUEL_USED = "fuel_used"
    const val RESET = "reset"
    /** La nota de cuándo empieza un viaje nuevo (va junto al botón de reiniciar). */
    const val NOTE = "note"
    /** El viaje de ahora (distancia y tiempo) en una tarjeta; al tocarla va a Viaje. */
    const val NOW = "now"
}

/** Las partes de Autonomía (van en [Tile.data] de [TileTypes.FUEL_PART]). */
object FuelParts {
    const val GAUGE = "gauge"
    const val STATIONS = "stations"
    const val STATS = "stats"
    /** Las cuatro tarjetas de datos, cada una como su widget (como en Dashito). */
    const val TANK = "tank"
    const val AVERAGE = "avg"
    const val SINCE = "since"
    const val ACCURACY = "accuracy"
}

/** Las partes de Diagnóstico (van en [Tile.data] de [TileTypes.DIAG]). */
object DiagParts {
    const val ADAPTER = "adapter"
    const val PIDS = "pids"
    const val CHECK = "check"
    const val LIST = "list"
    const val CONNECT = "connect"
}

/** Las señales del OBD que usa el diseño (las mismas de `Signal`, que vive en otro módulo). */
private object Gauge {
    const val RPM = "rpm"
    const val COOLANT = "coolant_temp"
    const val VOLTAGE = "voltage"
    const val CONSUMPTION = "consumption"
}

/** Una pieza de una columna del diseño: [weight] es su parte del alto; [keep] cuál se quita primero si no cabe (mayor = antes). */
private class Item(
    val type: String,
    val weight: Double = 1.0,
    val data: String = "",
    val plain: Boolean = false,
    val keep: Int = 0,
    val minH: Int? = null,
    val minW: Int? = null,
)

/** Una columna del diseño: [weight] es su parte del ancho; [keep] cuál se quita primero si no cabe (mayor = antes). */
private class Band(val weight: Double, val items: List<Item>, val keep: Int = 0)

/**
 * Reparte [total] celdas según [weights] sin bajar de [mins] (lo que sobra o falta se toma de las más grandes);
 * null si ni con los mínimos alcanza.
 */
internal fun split(total: Int, weights: List<Double>, mins: List<Int>): List<Int>? {
    if (weights.isEmpty() || mins.sum() > total) return null
    val sum = weights.sum()
    val raw = weights.map { it / sum * total }
    val sizes = raw.map { it.toInt() }.toMutableList()
    var left = total - sizes.sum()
    for (i in raw.indices.sortedByDescending { raw[it] - sizes[it] }) {
        if (left <= 0) break
        sizes[i]++
        left--
    }
    for (i in sizes.indices) {
        while (sizes[i] < mins[i]) {
            val donor = sizes.indices.filter { it != i && sizes[it] > mins[it] }.maxByOrNull { sizes[it] - mins[it] } ?: return null
            sizes[donor]--
            sizes[i]++
        }
    }
    return sizes
}

private class PageBuilder(
    private val page: Int,
    private val c: Grid,
    private val limits: (String) -> SizeLimits,
    private val skip: (String) -> Boolean,
) {
    val tiles = mutableListOf<Tile>()

    private fun add(type: String, col: Int, row: Int, w: Int, h: Int, data: String, plain: Boolean) {
        tiles += Tile("$type-f$page-${tiles.size}", type, col, row, w, h, data, plain)
    }

    private fun Item.width() = minW ?: limits(type).minWidth
    private fun Item.height() = minH ?: limits(type).minHeight

    /** Apila [items] de arriba abajo en la franja [col]..[col]+[w]; lo que no cabe a su mínimo se quita (primero lo de [Item.keep] mayor). */
    private fun stack(col: Int, w: Int, items: List<Item>) {
        var list = items.filter { !skip(it.type) && it.width() <= w }
        var heights: List<Int>? = null
        while (list.isNotEmpty()) {
            heights = split(c.rows, list.map { it.weight }, list.map { it.height() })
            if (heights != null) break
            val drop = list.indices.maxByOrNull { list[it].keep * 100 + it } ?: break
            list = list.filterIndexed { i, _ -> i != drop }
        }
        if (heights == null) return
        var row = 0
        for ((item, h) in list.zip(heights)) {
            add(item.type, col, row, w, h, item.data, item.plain)
            row += h
        }
    }

    /** Pone las [bands] una junto a otra a todo lo ancho; la que no cabe a su mínimo se quita. */
    fun bands(bands: List<Band>) {
        var list = bands.filter { b -> b.items.any { !skip(it.type) } }
        var widths: List<Int>? = null
        while (list.isNotEmpty()) {
            widths = split(c.columns, list.map { it.weight }, list.map { b -> b.items.filter { !skip(it.type) }.maxOf { it.width() } })
            if (widths != null) break
            val drop = list.indices.maxByOrNull { list[it].keep * 100 + it } ?: break
            list = list.filterIndexed { i, _ -> i != drop }
        }
        if (widths == null) return
        var col = 0
        for ((band, w) in list.zip(widths)) {
            stack(col, w, band.items)
            col += w
        }
    }
}

/**
 * Las seis páginas de fábrica en la cuadrícula [c], con los mismos widgets que Dashito. Cada columna y cada pieza
 * piden su parte del ancho y del alto, pero nunca menos de su mínimo ([limits]): en una cuadrícula chica (un celular)
 * se quita lo menos importante en vez de dejar piezas que no caben. Nada se enciman y todo queda dentro. Lo que
 * [skip] diga (un módulo apagado) no aparta lugar.
 */
fun factoryPages(c: Grid, limits: (String) -> SizeLimits = { SizeLimits(1, 1) }, skip: (String) -> Boolean = { false }): List<List<Tile>> {
    fun page(index: Int, build: PageBuilder.() -> Unit): List<Tile> {
        val b = PageBuilder(index, c, limits, skip)
        b.build()
        return b.tiles
    }
    val car = "front=car;title=1"
    return listOf(
        // Inicio: cuatro datos a la izquierda, el velocímetro con tu auto al centro, y música, clima y Android Auto a la derecha.
        page(0) {
            bands(
                listOf(
                    Band(
                        0.22,
                        listOf(
                            Item(TileTypes.GAUGE, data = "${Gauge.COOLANT};card=1;arrow=1", keep = 1),
                            Item(TileTypes.TRIP_STAT, data = TripStatKinds.NOW, keep = 0),
                            Item(TileTypes.GAUGE, data = "${Gauge.VOLTAGE};card=1;arrow=1", keep = 3),
                            Item(TileTypes.GAUGE, data = "${Gauge.CONSUMPTION};card=1;arrow=1", keep = 2),
                        ),
                        keep = 1,
                    ),
                    Band(0.46, listOf(Item(TileTypes.SPEEDOMETER, data = car, plain = true)), keep = 0),
                    Band(
                        0.32,
                        listOf(
                            Item(TileTypes.MUSIC, weight = 3.0),
                            Item(TileTypes.WEATHER, keep = 1),
                            Item(TileTypes.PROJECTION, keep = 2),
                        ),
                        keep = 2,
                    ),
                ),
            )
        },
        // Tablero: velocidad y revoluciones grandes, con sus números, y tres datos en columna.
        page(1) {
            bands(
                listOf(
                    Band(0.4, listOf(Item(TileTypes.SPEEDOMETER, data = "numbers=1;legend=1", plain = true)), keep = 0),
                    Band(0.4, listOf(Item(TileTypes.GAUGE, data = "${Gauge.RPM};numbers=1;legend=1", plain = true)), keep = 1),
                    Band(
                        0.2,
                        listOf(
                            Item(TileTypes.GAUGE, data = "${Gauge.COOLANT};card=1", keep = 0),
                            Item(TileTypes.GAUGE, data = "${Gauge.VOLTAGE};card=1", keep = 2),
                            Item(TileTypes.GAUGE, data = "${Gauge.CONSUMPTION};card=1", keep = 1),
                        ),
                        keep = 2,
                    ),
                ),
            )
        },
        // Viaje: seis tarjetas en dos columnas, el botón de reiniciar debajo y el mapa.
        page(2) {
            val stat = { kind: String, keep: Int -> Item(TileTypes.TRIP_STAT, data = kind, keep = keep, minH = 2) }
            val reset = Item(TileTypes.TRIP_STAT, weight = 0.5, data = TripStatKinds.RESET, plain = true, keep = 5, minH = 1)
            val note = Item(TileTypes.TRIP_STAT, weight = 0.5, data = TripStatKinds.NOTE, plain = true, keep = 5, minH = 1)
            bands(
                listOf(
                    Band(
                        0.26,
                        listOf(stat(TripStatKinds.DISTANCE, 0), stat(TripStatKinds.AVERAGE, 2), stat(TripStatKinds.CONSUMPTION, 4), note),
                        keep = 1,
                    ),
                    Band(
                        0.26,
                        listOf(stat(TripStatKinds.TIME, 1), stat(TripStatKinds.MAX, 3), stat(TripStatKinds.FUEL_USED, 5), reset),
                        keep = 1,
                    ),
                    Band(0.48, listOf(Item(TileTypes.MAP)), keep = 0),
                ),
            )
        },
        // Autonomía: el medidor con sus botones, las gasolineras y cuatro tarjetas de datos.
        page(3) {
            bands(
                listOf(
                    Band(0.36, listOf(Item(TileTypes.FUEL_PART, data = FuelParts.GAUGE, plain = true, minH = 4)), keep = 0),
                    Band(0.34, listOf(Item(TileTypes.FUEL_PART, data = FuelParts.STATIONS, minH = 4, minW = 5)), keep = 1),
                    Band(
                        0.30,
                        listOf(
                            Item(TileTypes.FUEL_PART, data = FuelParts.TANK, minH = 2, keep = 0),
                            Item(TileTypes.FUEL_PART, data = FuelParts.AVERAGE, minH = 2, keep = 1),
                            Item(TileTypes.FUEL_PART, data = FuelParts.SINCE, minH = 2, keep = 2),
                            Item(TileTypes.FUEL_PART, data = FuelParts.ACCURACY, minH = 2, keep = 3),
                        ),
                        keep = 2,
                    ),
                ),
            )
        },
        // Clima: el widget completo.
        page(4) {
            bands(listOf(Band(1.0, listOf(Item(TileTypes.WEATHER)))))
        },
        // Diagnóstico: tres tarjetas y el botón de conectar a la izquierda; a la derecha, la lista de datos o de códigos.
        page(5) {
            bands(
                listOf(
                    Band(
                        0.34,
                        listOf(
                            Item(TileTypes.DIAG, data = DiagParts.ADAPTER, minH = 2, keep = 1),
                            Item(TileTypes.DIAG, weight = 1.3, data = DiagParts.PIDS, minH = 2, keep = 2),
                            Item(TileTypes.DIAG, data = DiagParts.CHECK, minH = 2, keep = 3),
                            Item(TileTypes.DIAG, weight = 0.6, data = DiagParts.CONNECT, plain = true, minH = 1, keep = 0),
                        ),
                        keep = 1,
                    ),
                    Band(0.66, listOf(Item(TileTypes.DIAG, data = DiagParts.LIST)), keep = 0),
                ),
            )
        },
    )
}
