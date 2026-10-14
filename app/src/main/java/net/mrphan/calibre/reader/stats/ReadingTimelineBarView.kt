package net.mrphan.calibre.reader.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Draws the whole-book "chapter reading times" timeline as one horizontal stacked bar, one
 * segment per [StatUnit], each segment's width proportional to its [StatUnit.weight] relative to
 * the book total -- so a long chapter visibly takes up more of the bar than a short one, matching
 * how calibre desktop's own reading-stats bar behaves. Colors follow [UnitStatus]; the current
 * unit is additionally split into an "actual so far" / "estimated remaining" sub-segment using
 * [ReadingStatsSnapshot.currentUnitProgress].
 */
class ReadingTimelineBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private data class Segment(val startFraction: Float, val endFraction: Float, val color: Int)

    private var segments: List<Segment> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()

    fun setSnapshot(snapshot: ReadingStatsSnapshot) {
        val units = snapshot.units
        val totalWeight = units.sumOf { it.weight }.takeIf { it > 0.0 } ?: 1.0
        val result = mutableListOf<Segment>()
        var cursor = 0.0
        for (unit in units) {
            val unitFraction = unit.weight / totalWeight
            val start = cursor
            val end = cursor + unitFraction
            when (snapshot.statusFor(unit)) {
                UnitStatus.ACTUAL_PAST -> result += Segment(start.toFloat(), end.toFloat(), COLOR_ACTUAL)
                UnitStatus.ESTIMATED_PAST -> result += Segment(start.toFloat(), end.toFloat(), COLOR_ESTIMATED_OTHER)
                UnitStatus.ESTIMATED_FUTURE -> result += Segment(start.toFloat(), end.toFloat(), COLOR_ESTIMATED_OTHER)
                UnitStatus.CURRENT -> {
                    val progress = snapshot.currentUnitProgress.coerceIn(0.0, 1.0)
                    val mid = start + unitFraction * progress
                    if (mid > start) result += Segment(start.toFloat(), mid.toFloat(), COLOR_ACTUAL)
                    if (end > mid) result += Segment(mid.toFloat(), end.toFloat(), COLOR_ESTIMATED_CURRENT)
                }
            }
            cursor = end
        }
        segments = result
        currentUnitRange = units.getOrNull(snapshot.currentIndex)?.let {
            val unitFraction = it.weight / totalWeight
            val start = units.filter { u -> u.index < it.index }.sumOf { u -> u.weight } / totalWeight
            start.toFloat() to (start + unitFraction).toFloat()
        }
        invalidate()
    }

    private var currentUnitRange: Pair<Float, Float>? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (segments.isEmpty() || width <= 0 || height <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()

        paint.style = Paint.Style.FILL
        for (segment in segments) {
            paint.color = segment.color
            canvas.drawRect(segment.startFraction * w, 0f, segment.endFraction * w, h, paint)
        }

        // Orange outline around the current chapter's slot, as its own visual "you are here"
        // marker distinct from the actual/estimated fill colors inside it.
        currentUnitRange?.let { (start, end) ->
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = resources.displayMetrics.density * 2f
            paint.color = COLOR_CURRENT_MARKER
            bounds.set(start * w + paint.strokeWidth / 2, paint.strokeWidth / 2, end * w - paint.strokeWidth / 2, h - paint.strokeWidth / 2)
            canvas.drawRect(bounds, paint)
        }
    }

    companion object {
        val COLOR_ACTUAL = Color.parseColor("#4CAF50")
        val COLOR_CURRENT_MARKER = Color.parseColor("#FF9800")
        val COLOR_ESTIMATED_CURRENT = Color.parseColor("#64B5F6")
        val COLOR_ESTIMATED_OTHER = Color.parseColor("#9E9E9E")
    }
}
