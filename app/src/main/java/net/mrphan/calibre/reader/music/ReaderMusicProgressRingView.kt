package net.mrphan.calibre.reader.music

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils

class ReaderMusicProgressRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val bounds = RectF()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ColorUtils.setAlphaComponent(Color.WHITE, 70)
    }
    private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFF18D3A7.toInt()
    }

    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = (resources.displayMetrics.density * 2.4f).coerceAtLeast(2f)
        bgPaint.strokeWidth = stroke
        fgPaint.strokeWidth = stroke
        val inset = stroke / 2f + 1f
        bounds.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(bounds, -90f, 360f, false, bgPaint)
        canvas.drawArc(bounds, -90f, 360f * progress, false, fgPaint)
    }
}
