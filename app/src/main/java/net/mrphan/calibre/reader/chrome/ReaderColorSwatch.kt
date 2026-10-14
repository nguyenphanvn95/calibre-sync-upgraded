package net.mrphan.calibre.reader.chrome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.widget.TextView

object ReaderColorSwatch {
    fun bind(view: TextView, theme: ReaderColorTheme, selected: Boolean) {
        val context = view.context
        val density = context.resources.displayMetrics.density
        view.text = if (theme.thumbnailImageAsset == null) {
            context.getString(net.mrphan.calibre.R.string.reader_color_swatch_sample)
        } else {
            ""
        }
        view.contentDescription = context.getString(theme.label)
        view.setTextColor(theme.textColorInt)
        view.background = buildBackground(context, theme, selected, 8f * density)
    }

    private fun buildBackground(
        context: Context,
        theme: ReaderColorTheme,
        selected: Boolean,
        cornerRadius: Float,
    ): Drawable {
        val strokeWidth = (2f * context.resources.displayMetrics.density).toInt()
        val strokeColor = if (selected) theme.textColorInt else Color.TRANSPARENT
        val stroke = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(strokeWidth, strokeColor)
            this.cornerRadius = cornerRadius
        }
        val thumbnail = theme.thumbnailImageAsset
        if (thumbnail == null) {
            stroke.setColor(theme.backgroundColorInt)
            return stroke
        }

        val image = try {
            context.assets.open("reader_background_thumbnails/$thumbnail").use { input ->
                RoundedBitmapDrawable(BitmapFactory.decodeStream(input), cornerRadius)
            }
        } catch (_: Exception) {
            stroke.setColor(theme.backgroundColorInt)
            return stroke
        }
        return LayerDrawable(arrayOf(image, stroke))
    }

    private class RoundedBitmapDrawable(
        bitmap: Bitmap,
        private val cornerRadius: Float,
    ) : Drawable() {
        private val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            this.shader = this@RoundedBitmapDrawable.shader
        }
        private val shaderMatrix = Matrix()
        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            val bounds = bounds
            if (bounds.isEmpty) return
            rect.set(bounds)
            val bitmapWidth = shader.bitmapWidth().coerceAtLeast(1)
            val bitmapHeight = shader.bitmapHeight().coerceAtLeast(1)
            val scale = maxOf(
                bounds.width() / bitmapWidth.toFloat(),
                bounds.height() / bitmapHeight.toFloat(),
            )
            val dx = bounds.left + (bounds.width() - bitmapWidth * scale) / 2f
            val dy = bounds.top + (bounds.height() - bitmapHeight * scale) / 2f
            shaderMatrix.reset()
            shaderMatrix.setScale(scale, scale)
            shaderMatrix.postTranslate(dx, dy)
            shader.setLocalMatrix(shaderMatrix)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        private fun BitmapShader.bitmapWidth(): Int =
            this@RoundedBitmapDrawable.intrinsicBitmapWidth

        private fun BitmapShader.bitmapHeight(): Int =
            this@RoundedBitmapDrawable.intrinsicBitmapHeight

        private val intrinsicBitmapWidth = bitmap.width
        private val intrinsicBitmapHeight = bitmap.height
    }
}
