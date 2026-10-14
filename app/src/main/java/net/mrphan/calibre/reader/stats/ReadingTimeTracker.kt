package net.mrphan.calibre.reader.stats

import android.os.SystemClock
import net.mrphan.calibre.MetadataDatabaseHelper

/**
 * Measures how much *actual* wall-clock time the user spends on each "unit" of a book -- an EPUB
 * chapter (keyed by its readingOrder href) or a PDF/CBZ page (keyed by "page:N") -- and persists
 * it via [MetadataDatabaseHelper.addReadingTimeMs] so the "Thống kê" (reading stats) dialog can
 * show real, measured numbers instead of a permanently-stuck "Đang tính toán..." placeholder.
 *
 * [dailyStats]/[wordsPerMsProvider] (both optional, DATABASE_VERSION 17) additionally mirror each
 * flushed segment into [DailyReadingStatsStore] -- a SEPARATE, day-keyed, whole-library total used
 * only by the "Tiến trình" screen (ProgressActivity). This is a parallel bookkeeping system: it
 * does not read from or alter [MetadataDatabaseHelper.addReadingTimeMs]/TABLE_READING_TIME in any
 * way, it just observes the same flush() calls. [wordsPerMsProvider] lets the caller (the reader
 * Activity, which knows the current chapter/page's word weight) supply an estimated
 * words-per-millisecond rate for "Tốc độ đọc" (reading speed); formats with no natural word count
 * (PDF/CBZ) simply leave it at the default, which records 0 words -- only elapsed time is still
 * counted for those.
 *
 * Usage from a reader Activity:
 *  - call [onResume]/[onPause] from the matching Activity lifecycle callbacks so time spent with
 *    the app backgrounded (or the screen off) is never counted as reading time;
 *  - call [onUnitChanged] whenever the current chapter/page changes;
 *  - call [flush] before reading accumulated totals (e.g. right before showing the stats dialog)
 *    so in-flight time on the current unit is included without waiting for the next pause/change.
 *
 * Not thread-safe; intended to be driven entirely from the main thread, same as the Activity
 * lifecycle callbacks and navigator listeners that call it.
 */
class ReadingTimeTracker @JvmOverloads constructor(
    private val db: MetadataDatabaseHelper,
    private val uuid: String,
    private val dailyStats: DailyReadingStatsStore? = null,
    private val wordsPerMsProvider: () -> Double = { 0.0 },
) {
    private var currentUnitKey: String? = null
    private var segmentStartElapsedRealtime: Long? = null
    private var resumed = false

    /** Call from the host Activity's onResume(). Starts (or resumes) the timer for whatever unit
     * is current, if any has been set via [onUnitChanged]. */
    fun onResume() {
        resumed = true
        if (currentUnitKey != null && segmentStartElapsedRealtime == null) {
            segmentStartElapsedRealtime = SystemClock.elapsedRealtime()
        }
    }

    /** Call from the host Activity's onPause(). Flushes whatever time has accumulated in the
     * current unit so it isn't lost, and stops the timer until the next [onResume]. */
    fun onPause() {
        resumed = false
        flush()
    }

    /**
     * Call whenever the current chapter/page changes. Flushes the elapsed time recorded against
     * the previous unit (if any) and starts a fresh timer for [newUnitKey]. A no-op if
     * [newUnitKey] is the same as the unit already being timed, so callers can invoke this freely
     * on every locator update without needing to pre-filter for "did the chapter actually change".
     */
    fun onUnitChanged(newUnitKey: String) {
        if (newUnitKey == currentUnitKey) return
        flush()
        currentUnitKey = newUnitKey
        segmentStartElapsedRealtime = if (resumed) SystemClock.elapsedRealtime() else null
    }

    /** Persists whatever time has accumulated in the in-flight segment (if any) to the database
     * and restarts the segment clock, without changing the current unit. Safe to call at any
     * time, e.g. right before reading stats back out. */
    fun flush() {
        val key = currentUnitKey ?: return
        val start = segmentStartElapsedRealtime ?: return
        val now = SystemClock.elapsedRealtime()
        val deltaMs = now - start
        segmentStartElapsedRealtime = if (resumed) now else null
        if (deltaMs > 0) {
            db.addReadingTimeMs(uuid, key, deltaMs)
            val estimatedWords = (deltaMs * wordsPerMsProvider()).toLong()
            dailyStats?.recordReadingSession(deltaMs, estimatedWords)
        }
    }

    /** @return actual accumulated reading time for every unit tracked so far for this book,
     * keyed by unit key, including whatever is currently in-flight (calls [flush] first). */
    fun snapshotActualMsByUnit(): Map<String, Long> {
        flush()
        return db.getReadingTimes(uuid)
    }

    /** Clears all recorded actual reading time for this book. Backs the "Reset statistics"
     * button; the in-flight segment timer is restarted cleanly afterwards. Deliberately does NOT
     * touch [dailyStats] -- the day-by-day "Tiến trình" history is intentionally independent of
     * any single book's per-chapter stats being reset (see class kdoc). */
    fun resetAll() {
        db.resetReadingTimes(uuid)
        segmentStartElapsedRealtime = if (resumed && currentUnitKey != null) {
            SystemClock.elapsedRealtime()
        } else {
            null
        }
    }
}
