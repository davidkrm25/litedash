package com.irvingguerra.litedash

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Cuándo avisar una vuelta: a 500 m, a 150 m y casi encima (40 m). Devuelve el nivel (0, 1 o 2) en que está la vuelta
 * a [toNextM] metros, o -1 si aún falta más de 500 m. Sin Android: se prueba en JUnit.
 */
internal object VoiceRules {
    val LEVELS_M = doubleArrayOf(500.0, 150.0, 40.0)

    fun level(toNextM: Double): Int = LEVELS_M.indexOfLast { toNextM <= it }

    /** ¿Hay que decir algo ahora? Solo cuando el nivel sube sobre el último dicho de esa vuelta (nunca se repite ni retrocede). */
    fun shouldSpeak(level: Int, spoken: Int) = level >= 0 && level > spoken
}

/**
 * La voz de la guía: el motor de voz de Android (sin costo ni internet si la voz está bajada), por el canal de navegación
 * y bajando un momento la música mientras habla.
 */
internal class VoiceGuide(context: Context) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private var ready = false
    private var focus: AudioFocusRequest? = null
    private var pending: String? = null
    private val tts: TextToSpeech = TextToSpeech(app) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            setLanguage()
            pending?.let { speak(it) }
            pending = null
        }
    }

    init {
        tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = release()
            @Deprecated("Android 6–14") override fun onError(utteranceId: String?) = release()
        })
    }

    private fun setLanguage() {
        val wanted = primaryLocale(app.resources.configuration)
        val result = tts.setLanguage(wanted)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.getDefault())
    }

    /** Dice [text] (corta lo que estuviera diciendo: la vuelta nueva importa más). */
    fun speak(text: String) {
        if (!ready) {
            pending = text
            return
        }
        grab()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "guide")
    }

    private fun grab() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build()
            focus = request
            audio.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION") audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    private fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focus?.let { audio.abandonAudioFocusRequest(it) }
        else @Suppress("DEPRECATION") audio.abandonAudioFocus(null)
        focus = null
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        release()
    }
}
