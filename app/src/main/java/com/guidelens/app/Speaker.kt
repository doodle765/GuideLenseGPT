package com.guidelens.app

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Text-to-speech wrapper. All user-facing spoken feedback goes through here. */
class Speaker(context: Context, private val prefs: Prefs) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ready = false
    var lastSpoken: String = ""
        private set

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            tts?.language = Locale.US
            tts?.setSpeechRate(prefs.rate)
        }
    }

    fun setRate(r: Float) {
        tts?.setSpeechRate(r)
    }

    fun speak(text: String, priority: Boolean = false) {
        lastSpoken = text
        if (!prefs.audio) return
        val t = tts ?: return
        if (!ready) return
        if (priority) {
            t.stop()
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "gl")
        } else {
            t.speak(text, TextToSpeech.QUEUE_ADD, null, "gl")
        }
    }

    fun repeat() {
        if (lastSpoken.isNotEmpty()) speak(lastSpoken, priority = true)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
