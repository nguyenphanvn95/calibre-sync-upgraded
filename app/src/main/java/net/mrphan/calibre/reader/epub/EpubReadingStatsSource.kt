package net.mrphan.calibre.reader.epub

import net.mrphan.calibre.reader.stats.ReadingStatsSnapshot
import net.mrphan.calibre.reader.stats.ReadingStatsSource
import net.mrphan.calibre.reader.stats.ReadingTimeTracker
import net.mrphan.calibre.reader.stats.StatUnit
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/**
 * [ReadingStatsSource] for EPUB: each [StatUnit] is one `publication.readingOrder` entry (a
 * chapter/spine item), weighted by its actual word count (via [EpubTextExtractor]) so a 200-word
 * afterword doesn't count the same as a 6,000-word chapter when estimating time remaining --
 * closer to how calibre desktop's own EPUB reading-stats panel weights chapters than a flat
 * "every chapter is worth the same" would be.
 */
class EpubReadingStatsSource(
    private val publication: Publication,
    private val unitLabel: String,
    private val tracker: ReadingTimeTracker,
    private val extractor: EpubTextExtractor,
    private val currentLocator: () -> Locator?,
) : ReadingStatsSource {

    private var cachedUnits: List<StatUnit>? = null

    companion object {
        /** Used only before any real per-word reading speed has been measured yet (see
         * [ReadingStatsSnapshot.isStillMeasuring] / `computeEta`'s fallback path) -- an average
         * adult silent-reading pace is commonly cited as ~200-250 words/minute; 220 wpm works out
         * to ~272ms/word. Also reused by EpubReaderActivity as the "Tốc độ đọc" (reading speed)
         * estimate fed into DailyReadingStatsStore (DATABASE_VERSION 17) -- see
         * ReadingTimeTracker's wordsPerMsProvider -- since [StatUnit.weight] for EPUB chapters
         * IS the chapter word count, so 1/DEFAULT_MS_PER_WORD is already "words per ms" at the
         * generic default pace, no separate constant needed.
         */
        const val DEFAULT_MS_PER_WORD = 272.0
    }

    /** Chapter word counts only need computing once per book-open (the EPUB's content doesn't
     * change under us), so the result is cached across repeat [computeSnapshot] calls -- notably
     * the one right after "Reset statistics", which must not re-pay the extraction cost. */
    private suspend fun unitsOrBuild(): List<StatUnit> {
        cachedUnits?.let { return it }
        val readingOrder = publication.readingOrder
        val toc = publication.tableOfContents
        val fallbackLabel = unitLabel.replaceFirstChar { it.titlecase() }
        val built = readingOrder.mapIndexed { index, link ->
            val hrefKey = link.href.toString()
            val title = toc.firstOrNull { it.href.toString() == hrefKey }?.title
                ?: link.title
                ?: "$fallbackLabel ${index + 1}"
            val words = try {
                extractor.wordCount(link).coerceAtLeast(1)
            } catch (e: Exception) {
                1
            }
            StatUnit(index = index, title = title, key = hrefKey, weight = words.toDouble())
        }
        cachedUnits = built
        return built
    }

    override suspend fun computeSnapshot(): ReadingStatsSnapshot {
        val units = unitsOrBuild()
        val locator = currentLocator()
        val hrefKey = locator?.href?.toString()
        var currentIndex = if (hrefKey != null) units.indexOfFirst { it.key == hrefKey } else -1
        if (currentIndex < 0 && hrefKey != null) {
            // Fall back to matching ignoring any #fragment, in case the navigator's locator
            // carries a fragment the reading-order Link itself doesn't.
            val base = hrefKey.substringBefore('#')
            currentIndex = units.indexOfFirst { it.key.substringBefore('#') == base }
        }
        if (currentIndex < 0) currentIndex = 0
        val progress = locator?.locations?.progression ?: 0.0
        val actualMsByUnit = tracker.snapshotActualMsByUnit()
        return ReadingStatsSnapshot(
            unitLabel = unitLabel,
            units = units,
            currentIndex = currentIndex,
            currentUnitProgress = progress,
            actualMsByUnit = actualMsByUnit,
            defaultMsPerWeightUnit = DEFAULT_MS_PER_WORD,
            isStillMeasuring = actualMsByUnit.values.none { it > 0L },
        )
    }

    override fun resetStats() {
        tracker.resetAll()
    }
}
