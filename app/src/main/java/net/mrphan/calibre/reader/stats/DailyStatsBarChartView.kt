package net.mrphan.calibre.reader.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import net.mrphan.calibre.R

/**
 * Bieu do cot don gian: N cot, moi cot cao ti le theo [values]. Dung cho man hinh "Tien trinh" de
 * ve "Thoi gian doc theo ngay" (7 cot T7->T6) hoac theo thang/nam tuy tab dang chon. Khong
 * dependency ben ngoai (khong dung thu vien chart nao) -- ve bang Canvas thuan, giong cach
 * ReadingTimelineBarView da lam cho timeline chuong sach.
 */
class DailyStatsBarChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var values: List<Float> = emptyList()
    private var highlightIndex: Int = -1

    private val barColor = context.getColor(R.color.color_progress_bar_muted)
    private val highlightColor = context.getColor(R.color.color_progress_accent)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = barColor }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = highlightColor }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
        strokeWidth = resources.displayMetrics.density
    }

    /** @param values chieu cao cac cot, don vi bat ky (phut) -- tu quy doi tuong doi theo gia tri
     * lon nhat. @param highlightIndex cot nao duoc to mau nhan (mac dinh: cot cuoi cung, tuc
     * "hom nay"). */
    fun setData(values: List<Float>, highlightIndex: Int = values.lastIndex) {
        this.values = values
        this.highlightIndex = highlightIndex
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty() || width <= 0 || height <= 0) return
        val maxValue = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
        val barCount = values.size
        val gap = width * 0.02f
        val barWidth = ((width - gap * (barCount + 1)) / barCount).coerceAtLeast(1f)
        val chartHeight = height * 0.9f

        // 3 duong ke ngang mo (day + 2 muc chia phia tren), giong anh mau.
        for (i in 0..2) {
            val y = chartHeight - (chartHeight * i / 2f)
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
        }

        values.forEachIndexed { index, value ->
            val barHeight = ((value / maxValue) * chartHeight).coerceAtLeast(if (value > 0f) 4f else 0f)
            val left = gap + index * (barWidth + gap)
            val top = chartHeight - barHeight
            val paint = if (index == highlightIndex) highlightPaint else barPaint
            canvas.drawRoundRect(RectF(left, top, left + barWidth, chartHeight), 8f, 8f, paint)
        }
    }
}
