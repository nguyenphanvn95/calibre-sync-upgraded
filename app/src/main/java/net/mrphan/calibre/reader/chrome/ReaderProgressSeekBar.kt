package net.mrphan.calibre.reader.chrome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatSeekBar

/**
 * [AppCompatSeekBar] styled as a thick rounded "pill" (via `reader_progress_seekbar_track.xml` /
 * `reader_progress_seekbar_thumb.xml` set from `view_reader_progress_panel.xml`) that additionally
 * draws, directly on the canvas:
 *  1. thin chapter-boundary tick marks over the track -- ported from Readest's
 *     `StickyProgressBar.tsx` rendering (rounded track + tick marks clipped to it), and
 *  2. the current "NN%" label, embedded right at the fill/track boundary instead of living in a
 *     separate TextView beside the bar -- matches Readest's compact pill-with-embedded-percentage
 *     look (see prompt-nang-cap-thanh-tien-do-doc.md) more closely than a floating chip view
 *     would, without the extra plumbing a synced second View needs.
 *
 * Keeps Android's native touch/thumb/drag handling instead of reimplementing it (the thumb itself
 * is drawn fully transparent -- see reader_progress_seekbar_thumb.xml -- so this label is the only
 * visible "handle").
 */
class ReaderProgressSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.seekBarStyle,
) : AppCompatSeekBar(context, attrs, defStyleAttr) {

    /** 0f..1f fractions to draw tick marks at -- set by [ReaderChromeOverlay] from
     * [ReaderChromeHost.chapterTickFractions] each time the panel opens. */
    var tickFractions: List<Float> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val percentTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = context.resources.displayMetrics.density * 12f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        // Nhe shadow de chu luon doc duoc du dang nam tren phan da-doc (nen sang) hay chua-doc
        // (nen toi) cua pill -- xem shape mau trong reader_progress_seekbar_track.xml.
        setShadowLayer(2.5f, 0f, 0.5f, Color.argb(160, 0, 0, 0))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= paddingLeft + paddingRight) return
        val trackLeft = paddingLeft.toFloat()
        val trackWidth = (width - paddingLeft - paddingRight).toFloat()
        val top = paddingTop.toFloat() + 2f
        val bottom = (height - paddingBottom).toFloat() - 2f

        // Da bo ve chapter tick marks theo yeu cau -- chi con thanh tien trinh tron theo % (khong
        // con vach doc trang chi bao ranh gioi chuong). tickFractions van duoc giu lai/nhan tu
        // ReaderChromeOverlay de khong phai dong cham API cong khai, chi khong con dung de ve nua.

        val fraction = if (max > 0) progress.toFloat() / max.toFloat() else 0f
        // Ke gan sat 2 dau pill se lam chu bi cat/dinh vao phan bo tron -- kep vao khoang giua
        // de luon nam tron ven trong phan than thang cua pill. Neu pill qua hep (vd 1 lan do dac
        // thoang qua luc layout) de khong con "khoang giua" hop le, chi can can giua toan bo track
        // thay vi crash (coerceIn se nem loi neu min > max).
        val inset = PILL_LABEL_INSET_DP * resources.displayMetrics.density
        val labelX = if (trackWidth > inset * 2) {
            (trackLeft + trackWidth * fraction).coerceIn(trackLeft + inset, trackLeft + trackWidth - inset)
        } else {
            trackLeft + trackWidth / 2f
        }
        val labelY = (top + bottom) / 2f - (percentTextPaint.descent() + percentTextPaint.ascent()) / 2f
        canvas.drawText("${(fraction * 100).toInt()}%", labelX, labelY, percentTextPaint)
    }

    private companion object {
        const val PILL_LABEL_INSET_DP = 20f
    }
}
