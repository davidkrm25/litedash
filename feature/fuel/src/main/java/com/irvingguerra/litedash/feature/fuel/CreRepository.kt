package com.irvingguerra.litedash.feature.fuel

import android.content.Context
import com.irvingguerra.litedash.core.ui.Https
import java.io.File
import java.io.IOException

/** Las gasolineras con precio y de cuándo son los precios (reloj de pared, ms). */
class StationSnapshot(val stations: List<Station>, val pricesAtMs: Long)

/**
 * Precios de gasolina de la CRE (datos abiertos del gobierno de México). Dos archivos: los lugares (casi no cambian,
 * se bajan cada [PLACES_MAX_AGE_MS]) y los precios (cada [PRICES_MAX_AGE_MS]). Solo se baja cuando la pantalla
 * Autonomía se ve y lo guardado ya venció: nunca en segundo plano ni por reloj. Con falla se queda con lo guardado y no
 * reintenta antes de [RETRY_MS]. Todo es bloqueante: llamar fuera del hilo principal.
 */
class CreRepository(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.cacheDir, "cre")
    private val placesFile = File(dir, "places.xml")
    private val pricesFile = File(dir, "prices.xml")

    @Volatile private var memory: StationSnapshot? = null
    @Volatile private var memoryKey = ""
    @Volatile private var failedAt = 0L

    /** Lo guardado en el equipo (null = nunca se ha bajado). Lo ya leído se reutiliza mientras los archivos no cambien. */
    fun cached(): StationSnapshot? {
        if (!placesFile.isFile || !pricesFile.isFile) return null
        val key = "${placesFile.lastModified()}/${pricesFile.lastModified()}"
        memory?.let { if (key == memoryKey) return it }
        val places = CreFeed.parsePlaces(placesFile.readText())
        val prices = CreFeed.parsePrices(pricesFile.readText())
        val snapshot = StationSnapshot(CreFeed.merge(places, prices), pricesFile.lastModified())
        if (snapshot.stations.isEmpty()) return null
        memory = snapshot
        memoryKey = key
        return snapshot
    }

    /** ¿Los precios guardados ya vencieron (o no hay) y vale la pena intentar bajarlos? */
    fun stale(now: Long = System.currentTimeMillis()): Boolean {
        if (now - failedAt < RETRY_MS) return false
        return !pricesFile.isFile || !placesFile.isFile || now - pricesFile.lastModified() > PRICES_MAX_AGE_MS
    }

    /**
     * Baja lo que haya vencido. true = quedó al día; false = no hubo red o el archivo vino mal (se conserva lo anterior).
     * Cada archivo se baja a uno temporal y solo reemplaza al bueno si se entendió.
     */
    fun refresh(now: Long = System.currentTimeMillis()): Boolean {
        dir.mkdirs()
        return try {
            if (!placesFile.isFile || now - placesFile.lastModified() > PLACES_MAX_AGE_MS) {
                download(PLACES_URL, placesFile) { CreFeed.parsePlaces(it).size >= MIN_ROWS }
            }
            download(PRICES_URL, pricesFile) { CreFeed.parsePrices(it).size >= MIN_ROWS }
            true
        } catch (e: IOException) {
            failedAt = now
            false
        }
    }

    private fun download(url: String, target: File, valid: (String) -> Boolean) {
        val tmp = File(dir, target.name + ".tmp")
        val connection = Https.get(app, url, "application/xml")
        try {
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.removePrefix("﻿")
            if (!valid(text)) throw IOException("archivo de la CRE no entendido: ${target.name}")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("no se pudo guardar ${target.name}")
            }
        } finally {
            connection.disconnect()
            tmp.delete()
        }
    }

    companion object {
        const val PLACES_URL = "https://publicacionexterna.azurewebsites.net/publicaciones/places"
        const val PRICES_URL = "https://publicacionexterna.azurewebsites.net/publicaciones/prices"
        const val PRICES_MAX_AGE_MS = 6 * 3_600_000L
        const val PLACES_MAX_AGE_MS = 14 * 24 * 3_600_000L
        const val RETRY_MS = 10 * 60_000L
        /** Menos filas que esto es un archivo roto o una página de error, no la lista del país (son más de 10 000). */
        private const val MIN_ROWS = 500
    }
}
