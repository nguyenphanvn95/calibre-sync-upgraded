package net.mrphan.calibre.reader.stats

/**
 * What [ReadingStatsDialog] needs from a concrete reader (EPUB/PDF/CBZ) to render real numbers.
 * Implemented once per format -- see EpubReadingStatsSource (net.mrphan.calibre.EpubReaderActivity)
 * and PageReadingStatsSource (shared by PdfReaderActivity/CbzReaderActivity).
 */
interface ReadingStatsSource {
    /** Builds the current snapshot. Suspends because computing unit weights can require reading
     * book content (e.g. EPUB chapter word counts) off the main thread; implementations should
     * cache that work so repeat calls (e.g. after "Reset statistics") are cheap. */
    suspend fun computeSnapshot(): ReadingStatsSnapshot

    /** Clears all persisted actual reading-time data for this book. */
    fun resetStats()
}
