package com.guidelens.app

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.channels.FileChannel

/**
 * Optional DeepLabV3 Cityscapes scene segmentation — catches poles, walls, fences,
 * traffic lights and traffic signs that COCO-SSD cannot label.
 *
 * Fully offline once assets/deeplab_cityscapes.tflite exists (downloaded by the
 * optional; if missing, [available] is false and the app skips it,
 * exactly like the web app's graceful degradation).
 *
 * Class indices use the Cityscapes trainIds: wall=3, fence=4, pole=5,
 * traffic light=6, traffic sign=7. If you swap in a different segmentation model,
 * adjust OBSTACLE_IDS to that model's label map.
 */
class Segmenter(context: Context) {

    companion object {
        val OBSTACLE_IDS = setOf(3, 4, 5, 6, 7)
        const val AREA_RATIO = 0.02f
        const val STRIP_X0 = 0.35f   // center vertical strip, as in the web app
        const val STRIP_X1 = 0.65f
        const val STRIP_Y0 = 0.45f
    }

    private var interpreter: Interpreter? = null

    val available: Boolean

    init {
        var ok = false
        try {
            val fd = context.assets.openFd("deeplab_cityscapes.tflite")
            val model = FileInputStream(fd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            interpreter = Interpreter(model, Interpreter.Options().apply { numThreads = 2 })
            ok = true
        } catch (e: Exception) { /* model absent -> silently disabled */ }
        available = ok
    }

    data class Zone(val dist: Float, val yn: Float)

    fun analyze(frame: Bitmap): Zone? {
        val itp = interpreter ?: return null
        return try { runInference(itp, frame) } catch (e: Exception) { null }
    }

    private fun runInference(itp: Interpreter, frame: Bitmap): Zone? {
        val inShape = itp.getInputTensor(0).shape()          // [1, h, w, 3]
        val ih = inShape[1]
        val iw = inShape[2]
        val outShape = itp.getOutputTensor(0).shape()        // [1, h2, w2, 1] or [1, h2, w2, C]
        val oh = outShape[1]
        val ow = outShape[2]
        val oc = if (outShape.size >= 4) outShape[3] else 1
        val outType = itp.getOutputTensor(0).dataType()

        val scaled = Bitmap.createScaledBitmap(frame, iw, ih, true)
        val px = IntArray(iw * ih)
        scaled.getPixels(px, 0, iw, 0, 0, iw, ih)

        // ---- build input ----
        val inType = itp.getInputTensor(0).dataType()
        val input: Any
        if (inType == DataType.UINT8) {
            val b = ByteBuffer.allocateDirect(iw * ih * 3).order(ByteOrder.nativeOrder())
            for (p in px) {
                b.put((p shr 16 and 0xFF).toByte())
                b.put((p shr 8 and 0xFF).toByte())
                b.put((p and 0xFF).toByte())
            }
            b.rewind()
            input = b
        } else {
            val f = ByteBuffer.allocateDirect(iw * ih * 3 * 4).order(ByteOrder.nativeOrder())
            for (p in px) {
                f.putFloat((p shr 16 and 0xFF) / 255f)
                f.putFloat((p shr 8 and 0xFF) / 255f)
                f.putFloat((p and 0xFF) / 255f)
            }
            f.rewind()
            input = f
        }
        if (scaled !== frame) scaled.recycle()

        // ---- allocate output and run ----
        val classes = IntArray(oh * ow)
        val output: Any = when (outType) {
            DataType.INT32 -> {
                val buf = IntBuffer.allocate(oh * ow * oc)
                itp.run(input, buf)
                val arr = buf.array()
                if (oc == 1) {
                    for (i in classes.indices) classes[i] = arr[i]
                } else {
                    for (i in classes.indices) {
                        var best = 0; var bv = Int.MIN_VALUE
                        for (c in 0 until oc) {
                            val v = arr[i * oc + c]
                            if (v > bv) { bv = v; best = c }
                        }
                        classes[i] = best
                    }
                }
                return scan(classes, ow, oh)
            }
            DataType.INT8, DataType.UINT8 -> {
                val buf = ByteBuffer.allocateDirect(oh * ow * oc).order(ByteOrder.nativeOrder())
                itp.run(input, buf)
                buf.rewind()
                for (i in classes.indices) {
                    if (oc == 1) {
                        classes[i] = buf.get().toInt() and 0xFF
                    } else {
                        var best = 0; var bv = -1
                        for (c in 0 until oc) {
                            val v = buf.get().toInt() and 0xFF
                            if (v > bv) { bv = v; best = c }
                        }
                        classes[i] = best
                    }
                }
                return scan(classes, ow, oh)
            }
            else -> {
                val buf = FloatBuffer.allocate(oh * ow * oc)
                itp.run(input, buf)
                val arr = buf.array()
                for (i in classes.indices) {
                    if (oc == 1) {
                        classes[i] = arr[i].toInt()
                    } else {
                        var best = 0; var bv = Float.NEGATIVE_INFINITY
                        for (c in 0 until oc) {
                            val v = arr[i * oc + c]
                            if (v > bv) { bv = v; best = c }
                        }
                        classes[i] = best
                    }
                }
                return scan(classes, ow, oh)
            }
        }
    }

    /** Scan the center strip for obstacle-class pixels; ground position -> distance band. */
    private fun scan(classes: IntArray, ow: Int, oh: Int): Zone? {
        val x0 = (ow * STRIP_X0).toInt()
        val x1 = (ow * STRIP_X1).toInt()
        val y0 = (oh * STRIP_Y0).toInt()
        var yBottom = -1
        var count = 0
        for (y in y0 until oh) {
            val row = y * ow
            for (x in x0 until x1) {
                if (classes[row + x] in OBSTACLE_IDS) {
                    count++
                    if (y > yBottom) yBottom = y
                }
            }
        }
        val stripArea = (x1 - x0) * (oh - y0)
        if (yBottom < 0 || count <= stripArea * AREA_RATIO) return null
        val yn = yBottom.toFloat() / oh
        val dist = ynToDist(yn) ?: return null
        return Zone(dist, yn)
    }

    /** Map normalized ground-line position to a distance band (same curve as the web app). */
    private fun ynToDist(yn: Float): Float? {
        val pts = arrayOf(
            floatArrayOf(0.50f, 9f), floatArrayOf(0.60f, 7f),
            floatArrayOf(0.72f, 5f), floatArrayOf(0.85f, 3f), floatArrayOf(1.0f, 1.2f)
        )
        if (yn <= pts[0][0]) return null
        if (yn >= 1f) return 1.2f
        for (i in 1 until pts.size) {
            if (yn <= pts[i][0]) {
                val (x0, d0) = pts[i - 1]
                val (x1, d1) = pts[i]
                return d0 + (d1 - d0) * (yn - x0) / (x1 - x0)
            }
        }
        return null
    }
}
