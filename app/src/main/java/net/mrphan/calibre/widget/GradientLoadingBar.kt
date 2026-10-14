package net.mrphan.calibre.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Thanh loading vo dinh (indeterminate) hien thi mot dai sang gradient
 * xanh duong - cyan truot lien tuc tu trai sang phai va lap lai vo han.
 *
 * Cach dung: giu nguyen id trong layout va logic setVisibility(VISIBLE/GONE)
 * o noi goi (vi du OPDSBrowserFragment), view nay tu dong start/stop animation
 * dua theo visibility, khong can sua logic show/hide da co san.
 */
class GradientLoadingBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // Mau nen (track) cua thanh: xanh navy dam, mo
    var trackColor: Int = 0x330A2472

    // Vien ngoai cua thanh
    var borderColor: Int = 0x554A90E2

    // Cac diem mau cua dai sang, lap lai de tao hieu ung cuon vo tan qua TileMode.REPEAT
    var glowColors: IntArray = intArrayOf(
        0x000A2472.toInt(), // xanh navy, trong suot (dau dai)
        0xFF1E90FF.toInt(), // xanh duong sang
        0xFF3ED2FF.toInt(), // cyan dien
        0xFF1E90FF.toInt(), // xanh duong sang
        0x000A2472.toInt()  // xanh navy, trong suot (cuoi dai)
    )

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val trackRect = RectF()
    private val gradientMatrix = Matrix()
    private var bandWidthPx = 0f

    // Toc do lap: mot vong quet mat khoang 1.4s, chay tuyen tinh, lap vo han
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener {
            val fraction = it.animatedValue as Float
            gradientMatrix.setTranslate(fraction * bandWidthPx, 0f)
            glowPaint.shader?.setLocalMatrix(gradientMatrix)
            invalidate()
        }
    }

    init {
        trackPaint.color = trackColor
        borderPaint.color = borderColor
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        trackRect.set(
            borderPaint.strokeWidth / 2f,
            borderPaint.strokeWidth / 2f,
            w - borderPaint.strokeWidth / 2f,
            h - borderPaint.strokeWidth / 2f
        )

        // Dai gradient rong bang 60% chieu rong thanh, TileMode.REPEAT giup
        // khi dich chuyen matrix thi dai mau noi tiep lien tuc, khong bi giat
        bandWidthPx = w * 0.6f
        val shader = LinearGradient(
            0f, 0f, bandWidthPx, 0f,
            glowColors, null,
            Shader.TileMode.REPEAT
        )
        glowPaint.shader = shader
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cornerRadius = height / 2f

        // Nen (track)
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, trackPaint)

        // Dai sang gradient dang truot
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, glowPaint)

        // Vien ngoai
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, borderPaint)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
        }
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}
