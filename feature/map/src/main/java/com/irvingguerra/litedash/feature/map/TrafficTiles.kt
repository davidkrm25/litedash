package com.irvingguerra.litedash.feature.map

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * El tráfico en vivo de TomTom: teselas transparentes (verde fluye, rojo detenido) que se dibujan sobre el mapa.
 * Con la clave gratis de TomTom; se renuevan según su Cache-Control (cada minuto o dos).
 */
class TrafficTiles(private val key: String) : TileSource {
    override val folder = "map_traffic"
    override val attribution = "© TomTom"
    override val recolor = false
    override val gzip = false

    override fun url(origin: Long) =
        "https://api.tomtom.com/traffic/map/4/tile/flow/relative0/${keyZ(origin)}/${keyX(origin)}/${keyY(origin)}.png?tileSize=512&key=$key"

    override fun decode(data: ByteArray, key: Long, origin: Long): ImageBitmap? =
        BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap()
}
