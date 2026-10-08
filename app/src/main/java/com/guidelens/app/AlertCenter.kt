package com.guidelens.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.roundToInt

/**
 * Distance-tiered spoken + vibration alerts, mirroring the web app:
 *   <= 7 m : "Person on your left, about 7 meters"   (max twice, 4.5 s window)
 *   <= 5 m : "Person coming close"                   (repeats every 5 s)
 *   <= 3 m : "Stop! Obstacle ahead" (priority)       (repeats every 2.5 s, triple buzz)
 */
class AlertCenter(
    private val speaker: Speaker,
    private val prefs: Prefs,
    private val vibrator: Vibrator?
) {
    data class St(var t1: Long = 0, var c1: Int = 0, var t2: Long = 0, var t3: Long = 0)

    private val state = HashMap<String, St>()
    var namedDangerUntil: Long = 0
        private set

    private fun clean() {
        val now = System.currentTimeMillis()
        state.entries.removeIf {
            val st = it.value
            now - maxOf(st.t1, st.t2, st.t3) > 20000
        }
    }

    private fun buzz(pattern: LongArray) {
        if (!prefs.haptics || vibrator == null) return
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern.sum())
            }
        } catch (e: Exception) { }
    }

    private fun distWord(d: Float): String =
        if (d < 2f) "right in front of you" else "about " + (d.roundToInt().coerceAtLeast(1)) + " meters"

    fun alert(name: String, side: String, dist: Float) {
        val mult = when (prefs.sensitivity) {
            "near" -> 0.8f
            "far" -> 1.3f
            else -> 1.0f
        }
        if (dist > 7f * mult) return
        clean()
        val now = System.currentTimeMillis()
        val key = "$name|$side"
        val st = state.getOrPut(key) { St() }

        if (dist <= 3f * mult) {
            if (now - st.t3 > 2500) {
                st.t3 = now
                speaker.speak("Stop! $name ahead.", priority = true)
                buzz(longArrayOf(120, 80, 120, 80, 120))
                namedDangerUntil = now + 3000
            }
        } else if (dist <= 5f * mult) {
            if (prefs.verbosity != "minimal" && now - st.t2 > 5000) {
                st.t2 = now
                speaker.speak("$name coming close, ${distWord(dist)}.")
                buzz(longArrayOf(100, 60, 100))
            }
        } else {
            if (prefs.verbosity != "minimal" && now - st.t1 > 4500 && st.c1 < 2) {
                st.t1 = now
                st.c1++
                speaker.speak("$name $side, ${distWord(dist)}.")
                buzz(longArrayOf(60))
            }
        }
    }

    companion object {
        fun vibratorOf(context: Context): Vibrator? =
            try {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } catch (e: Exception) { null }
    }
}
