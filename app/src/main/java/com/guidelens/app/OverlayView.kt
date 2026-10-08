package com.guidelens.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Camera overlay: bounding boxes + danger zone band. All data flows in via [update]. */
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Box(val label: String, val rect: RectF, val sev: Int) // 0 info, 1 caution, 2 danger

    @Volatile private var boxes: List<Box> = emptyList()
    @Volatile private var zone: Segmenter.Zone? = null

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val zoneFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val zoneLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private val textBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 11, 15, 20) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        isFakeBoldText = true
    }

    fun update(b: List<Box>, z: Segmenter.Zone?) {
        boxes = b
        zone = z
        postInvalidate()
    }

    fun clear() {
        boxes = emptyList()
        zone = null
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val W = width.toFloat()
        val H = height.toFloat()

        // danger zone band
        zone?.let { z ->
            val y = z.yn * H
            val col = when {
                z.dist <= 3f -> Color.argb(77, 255, 77, 79)
                z.dist <= 5f -> Color.argb(64, 255, 176, 32)
                else -> Color.argb(46, 122, 184, 255)
            }
            zoneFill.color = col
            canvas.drawRect(W * 0.35f, y, W * 0.65f, H, zoneFill)
            zoneLine.color = when {
                z.dist <= 3f -> Color.rgb(255, 77, 79)
                z.dist <= 5f -> Color.rgb(255, 176, 32)
                else -> Color.rgb(122, 184, 255)
            }
            val p = Path().apply {
                moveTo(W * 0.35f, y)
                lineTo(W * 0.65f, y)
            }
            canvas.drawPath(p, zoneLine)
            val label = "obstacle %.1f m".format(z.dist)
            val tw = textPaint.measureText(label)
            val ty = (y - 12f).coerceAtLeast(textPaint.textSize + 8f)
            canvas.drawRect(W * 0.35f, ty - textPaint.textSize - 10f,
                W * 0.35f + tw + 24f, ty + 10f, textBg)
            canvas.drawText(label, W * 0.35f + 12f, ty, textPaint)
        }

        // detection boxes
        for (b in boxes) {
            boxPaint.color = when (b.sev) {
                2 -> Color.rgb(255, 77, 79)
                1 -> Color.rgb(255, 176, 32)
                else -> Color.rgb(122, 184, 255)
            }
            boxPaint.pathEffect = when (b.sev) {
                2 -> null
                1 -> DashPathEffect(floatArrayOf(16f, 12f), 0f)
                else -> DashPathEffect(floatArrayOf(6f, 8f), 0f)
            }
            canvas.drawRect(b.rect, boxPaint)
            val label = b.label
            val tw = textPaint.measureText(label)
            val ty = (b.rect.top - 10f).coerceAtLeast(textPaint.textSize + 8f)
            canvas.drawRect(b.rect.left, ty - textPaint.textSize - 10f,
                b.rect.left + tw + 24f, ty + 10f, textBg)
            canvas.drawText(label, b.rect.left + 12f, ty, textPaint)
        }
    }
}
