package com.guidelens.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Hold-to-talk voice input.
 * Requests the on-device (offline) recognizer via EXTRA_PREFER_OFFLINE. Whether offline
 * recognition actually works depends on the phone's speech service (e.g. Google speech
 * with the English offline pack downloaded); if not available the error callback fires
 * and the app says so out loud. Buttons always work regardless.
 */
class VoiceController(
    private val context: Context,
    private val onCommand: (String) -> Unit,
    private val onError: () -> Unit
) {
    private var rec: SpeechRecognizer? = null
    private var active = false

    val isSupported: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (active || !isSupported) {
            if (!isSupported) onError()
            return
        }
        try {
            rec?.destroy()
            rec = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(listener)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500)
                }
                startListening(intent)
            }
            active = true
        } catch (e: Exception) {
            active = false
            onError()
        }
    }

    fun stop() {
        if (!active) return
        try { rec?.stop() } catch (e: Exception) { }
        active = false
    }

    fun destroy() {
        try { rec?.destroy() } catch (e: Exception) { }
        rec = null
        active = false
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { active = false }
        override fun onError(error: Int) { active = false; onError() }
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onResults(results: Bundle?) {
            active = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val heard = matches?.firstOrNull()
            if (heard != null) onCommand(heard) else onError()
        }
        override fun onPartialResults(partialResults: Bundle?) {}
    }
}
