package net.mrphan.calibre.reader.stats

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.mrphan.calibre.R

/**
 * Builds and drives the "Thống kê" (reading stats) dialog (see dialog_reading_stats.xml) from a
 * [ReadingStatsSource]. Every number shown comes from [ReadingStatsSnapshot] /
 * [computeEta] -- see those files' kdoc for why nothing here is a fabricated placeholder.
 *
 * Usage from a reader Activity (see EpubReaderActivity#openReadingStats /
 * PdfReaderActivity#openReadingStats):
 * ```
 * ReadingStatsDialog.show(this, lifecycleScope, statsSource)
 * ```
 */
object ReadingStatsDialog {

    fun show(activity: Activity, scope: CoroutineScope, source: ReadingStatsSource) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_reading_stats, null, false)
        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .create()

        view.findViewById<ImageButton>(R.id.reading_stats_close).setOnClickListener { dialog.dismiss() }

        fun refresh() {
            scope.launch {
                val snapshot = source.computeSnapshot()
                if (dialog.isShowing) bind(activity, view, snapshot)
            }
        }

        view.findViewById<Button>(R.id.reading_stats_reset).setOnClickListener {
            source.resetStats()
            Toast.makeText(activity, R.string.reader_stats_reset_done, Toast.LENGTH_SHORT).show()
            refresh()
        }

        dialog.show()
        refresh()
    }

    private fun bind(context: Context, view: View, snapshot: ReadingStatsSnapshot) {
        val eta = computeEta(snapshot)
        val unitLabel = snapshot.unitLabel
        val unitLabelCapitalized = unitLabel.replaceFirstChar { it.titlecase() }

        view.findViewById<TextView>(R.id.reading_stats_progress_line).text = context.getString(
            R.string.reader_stats_progress_line,
            unitLabelCapitalized,
            snapshot.currentIndex + 1,
            snapshot.units.size,
            snapshot.currentPercent,
        )

        val etaCurrentText = if (snapshot.isStillMeasuring) {
            context.getString(R.string.reader_stats_calculating)
        } else {
            formatDuration(context, eta.etaCurrentUnitMs)
        }
        view.findViewById<TextView>(R.id.reading_stats_chapter_eta_line).text = context.getString(
            R.string.reader_stats_current_eta, unitLabel, etaCurrentText
        )

        val etaBookText = if (snapshot.isStillMeasuring) {
            context.getString(R.string.reader_stats_calculating)
        } else {
            formatDuration(context, eta.etaBookMs)
        }
        view.findViewById<TextView>(R.id.reading_stats_book_eta_line).text = context.getString(
            R.string.reader_stats_book_eta, etaBookText
        )

        val lastUnitNote = view.findViewById<TextView>(R.id.reading_stats_last_unit_note)
        if (snapshot.isLastUnit) {
            lastUnitNote.visibility = View.VISIBLE
            lastUnitNote.text = context.getString(R.string.reader_stats_last_unit_note, unitLabel)
        } else {
            lastUnitNote.visibility = View.GONE
        }

        view.findViewById<TextView>(R.id.reading_stats_heading).text = context.getString(
            R.string.reader_stats_chapter_times_heading, unitLabel
        )
        view.findViewById<TextView>(R.id.reading_stats_subheading).text = if (snapshot.isStillMeasuring) {
            context.getString(R.string.reader_stats_subheading_measuring)
        } else {
            context.getString(R.string.reader_stats_subheading_ready, unitLabel)
        }

        view.findViewById<ReadingTimelineBarView>(R.id.reading_stats_timeline).setSnapshot(snapshot)

        val isChapterSource = unitLabel == context.getString(R.string.reader_stats_unit_chapter)
        view.findViewById<TextView>(R.id.reading_stats_explainer).text = context.getString(
            if (isChapterSource) R.string.reader_stats_explainer_epub else R.string.reader_stats_explainer_pages
        )

        bindUnitList(context, view.findViewById(R.id.reading_stats_unit_list), snapshot)
    }

    /** Real per-unit ("chapter"/"page") breakdown, replacing calibre desktop's hover-tooltip on
     * the timeline bar with an always-visible list (touch UIs don't have hover) -- built from the
     * exact same [ReadingStatsSnapshot] data the timeline bar itself draws from, so the two never
     * disagree. */
    private fun bindUnitList(context: Context, container: LinearLayout, snapshot: ReadingStatsSnapshot) {
        container.removeAllViews()
        for (unit in snapshot.units) {
            val status = snapshot.statusFor(unit)
            val text = when (status) {
                UnitStatus.CURRENT -> context.getString(
                    R.string.reader_stats_unit_current, unit.title, snapshot.currentPercent
                )
                UnitStatus.ACTUAL_PAST -> {
                    val actualMs = snapshot.actualMsByUnit[unit.key] ?: 0L
                    context.getString(R.string.reader_stats_unit_actual, unit.title, formatDuration(context, actualMs))
                }
                UnitStatus.ESTIMATED_PAST, UnitStatus.ESTIMATED_FUTURE ->
                    context.getString(R.string.reader_stats_unit_unread, unit.title)
            }
            val row = TextView(context).apply {
                this.text = text
                textSize = 13f
                setTextColor(if (status == UnitStatus.CURRENT) 0xFFFF9800.toInt() else 0xFF555555.toInt())
                setPadding(0, dp(context, 3), 0, dp(context, 3))
                if (status == UnitStatus.CURRENT) setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            container.addView(row)
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun formatDuration(context: Context, ms: Long): String {
        if (ms <= 0) return context.getString(R.string.reader_stats_almost_done)
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> context.getString(R.string.reader_stats_duration_hm, hours, minutes)
            minutes > 0 -> context.getString(R.string.reader_stats_duration_m, minutes)
            else -> context.getString(R.string.reader_stats_duration_s, seconds)
        }
    }
}
