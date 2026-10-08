package com.guidelens.app

import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Camera + detection pipeline, fully offline:
 * CameraX preview -> grab frames from PreviewView -> COCO-SSD (named objects)
 * and optional DeepLab zones (poles/walls/fences) -> tiered alerts -> overlay.
 */
class CameraController(
    private val activity: androidx.activity.ComponentActivity,
    private val previewView: PreviewView,
    private val overlay: OverlayView,
    private val prefs: Prefs,
    private val speaker: Speaker,
    private val alerts: AlertCenter,
    private val onStatus: (String) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
    private val detector = Detector(activity)
    private val segmenter = Segmenter(activity)

    private var loopJob: Job? = null
    private var provider: ProcessCameraProvider? = null

    @Volatile var running = false
        private set

    @Volatile var lastSeen: List<Detector.Detection> = emptyList()
    @Volatile var lastZone: Segmenter.Zone? = null

    fun start() {
        if (running) return
        running = true
        if (!detector.available) {
            onStatus("Object model missing. Rebuild with the bundled detector model present.")
            speaker.speak("Object detection model is missing. It should have been downloaded when the app was built.")
            return
        }
        onStatus("Starting camera…")
        scope.launch {
            try {
                provider = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(activity).get() }
                bindPreview()
                val segNote = if (segmenter.available) "" else " Scene model unavailable on this device."
                onStatus("Detection running. Hold the phone at chest height, camera facing forward." + segNote)
                speaker.speak("Detection running. I'll warn you about obstacles.")
                loopJob = scope.launch(Dispatchers.Default) { detectionLoop() }
            } catch (e: Exception) {
                running = false
                onStatus("Camera unavailable: " + (e.message ?: "unknown error"))
                speaker.speak("I couldn't start the camera. Please check camera permission.")
            }
        }
    }

    private fun bindPreview() {
        val p = provider ?: return
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
        p.unbindAll()
        p.bindToLifecycle(activity as LifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
    }

    private suspend fun detectionLoop() {
        while (scope.coroutineContext.isActive && running) {
            val frame = try { previewView.bitmap } catch (e: Exception) { null }
            if (frame != null) {
                val dets = withContext(Dispatchers.IO) {
                    runCatching { detector.detect(frame) }.getOrElse { emptyList() }
                }
                val zone = withContext(Dispatchers.IO) {
                    if (segmenter.available) runCatching { segmenter.analyze(frame) }.getOrNull()
                    else null
                }
                lastSeen = dets
                lastZone = zone

                // spoken alerts
                var anyDanger = false
                for (d in dets) {
                    val dist = d.dist ?: continue
                    alerts.alert(d.label, d.side, dist)
                    if (dist <= 3f) anyDanger = true
                }
                zone?.let { z ->
                    if (System.currentTimeMillis() > alerts.namedDangerUntil) {
                        alerts.alert("obstacle", "ahead", z.dist)
                        if (z.dist <= 3f) anyDanger = true
                    }
                }

                // overlay
                val boxes = dets.map { d ->
                    val sev = when {
                        (d.dist ?: 99f) <= 3f -> 2
                        (d.dist ?: 99f) <= 5f -> 1
                        else -> 0
                    }
                    val label = d.dist?.let { "${d.label} %.1f m".format(it) } ?: d.label
                    OverlayView.Box(label, d.box, sev)
                }
                withContext(Dispatchers.Main) { overlay.update(boxes, zone) }
                if (anyDanger) onStatus("Stop! Obstacle ahead.")
            }
            delay(420)
        }
    }

    fun stop() {
        running = false
        loopJob?.cancel()
        loopJob = null
        try { provider?.unbindAll() } catch (e: Exception) { }
        lastSeen = emptyList()
        lastZone = null
        overlay.clear()
    }

    fun shutdown() {
        stop()
    }
}
