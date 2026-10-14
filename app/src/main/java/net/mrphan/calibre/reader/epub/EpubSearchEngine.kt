package net.mrphan.calibre.reader.epub

import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/** One search hit: [locator] is where to navigate to, the three snippet fields are the text
 * immediately before/at/after the match, for building a "...before **match** after..." row in
 * the results list. */
data class EpubSearchResult(
    val locator: Locator,
    val snippetBefore: String,
    val snippetMatch: String,
    val snippetAfter: String,
)

/**
 * Full-text search across an EPUB's reading order, backing the reader chrome's "Tìm kiếm" action
 * (see EpubReaderActivity#openSearch) -- this is the real implementation the previous TODO in
 * EpubReaderActivity pointed at.
 *
 * Built directly on [EpubTextExtractor] (Publication#readingOrder + Publication#get(Link), both
 * long-stable parts of the toolkit's public API) rather than Readium's own SearchService/Content
 * API, which its own docs mark "still experimental" -- same "no Android SDK here to verify the
 * exact API shape" reasoning already documented on EpubTextExtractor.
 *
 * Locations are approximate: each result's [Locator.Locations.progression] is the character
 * offset of the match divided by the chapter's total character count. That's precise enough to
 * jump to roughly the right spot (typically landing within the same paragraph for a normal-length
 * chapter) -- it is not a DOM Range or an EPUB CFI, so don't expect pixel-perfect highlighting.
 */
class EpubSearchEngine(
    private val publication: Publication,
    private val extractor: EpubTextExtractor,
) {
    companion object {
        private const val SNIPPET_CONTEXT_CHARS = 40
        private const val MAX_RESULTS = 200
    }

    suspend fun search(query: String): List<EpubSearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val results = mutableListOf<EpubSearchResult>()
        for (link in publication.readingOrder) {
            if (results.size >= MAX_RESULTS) break
            val mediaType = link.mediaType ?: continue // readingOrder entries always carry one in practice
            val text = try {
                extractor.extractText(link)
            } catch (e: Exception) {
                continue
            }
            if (text.isEmpty()) continue

            var searchFrom = 0
            while (results.size < MAX_RESULTS) {
                val matchIndex = text.indexOf(trimmed, searchFrom, ignoreCase = true)
                if (matchIndex < 0) break
                val progression = (matchIndex.toDouble() / text.length).coerceIn(0.0, 1.0)
                val before = text.substring((matchIndex - SNIPPET_CONTEXT_CHARS).coerceAtLeast(0), matchIndex)
                val matchEnd = matchIndex + trimmed.length
                val highlight = text.substring(matchIndex, matchEnd)
                val after = text.substring(matchEnd, (matchEnd + SNIPPET_CONTEXT_CHARS).coerceAtMost(text.length))

                val locator = Locator(
                    href = link.url(),
                    mediaType = mediaType,
                    title = link.title,
                    locations = Locator.Locations(progression = progression),
                    text = Locator.Text(before = before, highlight = highlight, after = after),
                )
                results += EpubSearchResult(locator, before, highlight, after)
                searchFrom = matchEnd
            }
        }
        return results
    }
}
