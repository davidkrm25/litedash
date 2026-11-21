package com.irvingguerra.litedash

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/** Lado de entrada del modelo U²-Net (u2netp). */
internal const val AI_SIZE = 320

private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

/** Pasa [pixels] (ARGB w×h) a un tensor NCHW 3×320×320 normalizado como pide el modelo. Función pura. */
internal fun aiInput(pixels: IntArray, w: Int, h: Int): FloatArray {
    val plane = AI_SIZE * AI_SIZE
    val out = FloatArray(3 * plane)
    var max = 1
    for (p in pixels) {
        max = maxOf(max, (p shr 16) and 255, (p shr 8) and 255, p and 255)
    }
    for (y in 0 until AI_SIZE) {
        val sy = minOf(h - 1, y * h / AI_SIZE)
        for (x in 0 until AI_SIZE) {
            val p = pixels[sy * w + minOf(w - 1, x * w / AI_SIZE)]
            val i = y * AI_SIZE + x
            out[i] = (((p shr 16) and 255) / max.toFloat() - MEAN[0]) / STD[0]
            out[plane + i] = (((p shr 8) and 255) / max.toFloat() - MEAN[1]) / STD[1]
            out[2 * plane + i] = ((p and 255) / max.toFloat() - MEAN[2]) / STD[2]
        }
    }
    return out
}

/**
 * Aplica la [mask] (320×320, cualquier rango) como transparencia a [pixels]: normaliza min-max y la
 * amplía con interpolación bilineal al tamaño original. Función pura.
 */
internal fun applyMask(pixels: IntArray, w: Int, h: Int, mask: FloatArray): IntArray {
    var lo = Float.MAX_VALUE
    var hi = -Float.MAX_VALUE
    for (v in mask) { if (v < lo) lo = v; if (v > hi) hi = v }
    val span = (hi - lo).takeIf { it > 1e-6f } ?: 1f
    val out = IntArray(w * h)
    for (y in 0 until h) {
        val fy = ((y + 0.5f) * AI_SIZE / h - 0.5f).coerceIn(0f, AI_SIZE - 1f)
        val y0 = fy.toInt(); val y1 = minOf(AI_SIZE - 1, y0 + 1); val ty = fy - y0
        for (x in 0 until w) {
            val fx = ((x + 0.5f) * AI_SIZE / w - 0.5f).coerceIn(0f, AI_SIZE - 1f)
            val x0 = fx.toInt(); val x1 = minOf(AI_SIZE - 1, x0 + 1); val tx = fx - x0
            val top = mask[y0 * AI_SIZE + x0] * (1 - tx) + mask[y0 * AI_SIZE + x1] * tx
            val bottom = mask[y1 * AI_SIZE + x0] * (1 - tx) + mask[y1 * AI_SIZE + x1] * tx
            val m = (((top * (1 - ty) + bottom * ty) - lo) / span).coerceIn(0f, 1f)
            // Contraste suave: borra la neblina del modelo sin endurecer el borde.
            val a = ((m - 0.15f) / 0.7f).coerceIn(0f, 1f)
            val p = pixels[y * w + x]
            val alpha = ((p ushr 24) * a).toInt()
            out[y * w + x] = if (alpha == 0) 0 else (alpha shl 24) or (p and 0xFFFFFF)
        }
    }
    return out
}

/** El rectángulo (izq, arr, der, abajo; der y abajo exclusivos) donde hay algo visible, con [margin] px de aire; null si no hay nada. Función pura. */
internal fun contentBounds(pixels: IntArray, w: Int, h: Int, margin: Int = 4): IntArray? {
    var left = w; var top = h; var right = -1; var bottom = -1
    for (y in 0 until h) for (x in 0 until w) {
        if ((pixels[y * w + x] ushr 24) > 24) {
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
    }
    if (right < 0) return null
    return intArrayOf(maxOf(0, left - margin), maxOf(0, top - margin), minOf(w, right + 1 + margin), minOf(h, bottom + 1 + margin))
}

/** Quita el fondo con IA, sin conexión (modelo en assets). Devuelve null si no se pudo (sin memoria, modelo ausente). */
internal object AiCutout {
    private var session: OrtSession? = null

    @Synchronized
    private fun session(context: Context): OrtSession {
        session?.let { return it }
        val bytes = context.applicationContext.assets.open("u2netp.onnx").use { it.readBytes() }
        return OrtEnvironment.getEnvironment().createSession(bytes, OrtSession.SessionOptions()).also { session = it }
    }

    fun cut(context: Context, source: Bitmap): Bitmap? = try {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h).also { source.getPixels(it, 0, w, 0, 0, w, h) }
        val env = OrtEnvironment.getEnvironment()
        val s = session(context)
        val shape = longArrayOf(1, 3, AI_SIZE.toLong(), AI_SIZE.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(aiInput(pixels, w, h)), shape).use { input ->
            s.run(mapOf(s.inputNames.first() to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val raw = (result[0].value as Array<Array<Array<FloatArray>>>)[0][0]
                val mask = FloatArray(AI_SIZE * AI_SIZE).also { m -> raw.forEachIndexed { y, row -> row.copyInto(m, y * AI_SIZE) } }
                val out = applyMask(pixels, w, h, mask)
                val full = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
                // Sin los bordes vacíos, el auto llena su lugar en vez de quedar chiquito en medio de la foto.
                contentBounds(out, w, h)?.let { b -> Bitmap.createBitmap(full, b[0], b[1], b[2] - b[0], b[3] - b[1]) } ?: full
            }
        }
    } catch (_: Throwable) {
        null
    }
}
