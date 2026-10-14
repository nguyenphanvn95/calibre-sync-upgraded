package net.mrphan.calibre.reader.stats

/**
 * [ReadingStatsSource] for the page-based formats (PDF via PdfReaderActivity, CBZ via
 * CbzReaderActivity): every page is given the same [StatUnit.weight] (`1.0`), since -- unlike an
 * EPUB chapter -- a rendered page has no natural "amount of content" measure to weight by, and
 * calibre desktop's own PDF/CBZ view treats pages the same way.
 *
 * Deliberately constructed fresh (cheap -- no chapter-text extraction to cache, unlike
 * EpubReadingStatsSource) right before showing the dialog, with [pageCount]/[currentPageIndex]
 * read directly from the host Activity's live fields at that moment, rather than taking Kotlin
 * function-type suppliers -- PdfReaderActivity/CbzReaderActivity are Java, and a plain-value
 * constructor is much simpler to call correctly from Java than `Function0<Integer>` lambdas.
 */
class PageReadingStatsSource(
    private val unitLabel: String,
    private val tracker: ReadingTimeTracker,
    private val pageCount: Int,
    private val currentPageIndex: Int,
) : ReadingStatsSource {

    companion object {
        /** page:N unit key, matching what ReadingTimeTracker.onUnitChanged() is fed for pages. */
        @JvmStatic
        fun keyFor(pageIndex: Int) = "page:$pageIndex"

        /** No per-page word count to seed a smarter default from -- a few seconds per page is a
         * reasonable placeholder only used before any real measurement exists at all (see
         * ReadingStatsSnapshot.isStillMeasuring / computeEta's fallback path). */
        private const val DEFAULT_MS_PER_PAGE = 8_000.0
    }

    override suspend fun computeSnapshot(): ReadingStatsSnapshot {
        val count = pageCount.coerceAtLeast(1)
        val current = currentPageIndex.coerceIn(0, count - 1)
        val titlePrefix = unitLabel.replaceFirstChar { it.titlecase() }
        val units = (0 until count).map { index ->
            StatUnit(index = index, title = "$titlePrefix ${index + 1}", key = keyFor(index), weight = 1.0)
        }
        val actualMsByUnit = tracker.snapshotActualMsByUnit()
        return ReadingStatsSnapshot(
            unitLabel = unitLabel,
            units = units,
            currentIndex = current,
            currentUnitProgress = 1.0, // a page is either being looked at (counts as "in progress") or not
            actualMsByUnit = actualMsByUnit,
            defaultMsPerWeightUnit = DEFAULT_MS_PER_PAGE,
            isStillMeasuring = actualMsByUnit.values.none { it > 0L },
        )
    }

    override fun resetStats() {
        tracker.resetAll()
    }
}
