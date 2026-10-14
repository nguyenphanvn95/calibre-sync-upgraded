package net.mrphan.calibre.reader.epub

import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.getOrElse

/**
 * Plain-text extraction for EPUB chapters, shared by `EpubReadingStatsSource` (real per-chapter
 * word counts, so the "Thống kê" timeline weights a long chapter more than a short one instead of
 * treating every chapter as equal) and `EpubSearchEngine` (full-text search).
 *
 * IMPLEMENTATION NOTE / CAVEAT: this reads each chapter resource's raw bytes via
 * `Publication#get(Link)` (documented in readium.org/kotlin-toolkit's migration guide: "you can
 * replace any use of the Container API by publication.get(Link)") and strips HTML tags with a
 * regex, rather than depending on the toolkit's own Content/ContentService API -- which its own
 * docs mark "experimental" -- for the same "no Android SDK here to compile/verify the exact API
 * shape against" reasoning already flagged at the top of EpubReaderActivity.kt. A regex strip is
 * not a real HTML parser (it will mishandle e.g. a literal "<" inside inline JS), but it is simple
 * enough to reason about being correct for the overwhelmingly common case of EPUB XHTML content,
 * and `Publication#get`/readingOrder are long-stable parts of the public API.
 */
class EpubTextExtractor(private val publication: Publication) {

    private val cache = mutableMapOf<String, String>()

    /** Plain text of one reading-order resource, tags stripped and whitespace collapsed. Cached
     * per href since both callers (word-count weighting and search) ask for every chapter at
     * least once per session, and re-unzipping/re-stripping the same chapter repeatedly would be
     * wasted work. */
    suspend fun extractText(link: Link): String {
        val key = link.href.toString()
        cache[key]?.let { return it }
        val bytes = try {
            publication.get(link)?.read()?.getOrElse { ByteArray(0) } ?: ByteArray(0)
        } catch (e: Exception) {
            ByteArray(0)
        }
        val text = htmlToText(String(bytes, Charsets.UTF_8))
        cache[key] = text
        return text
    }

    suspend fun wordCount(link: Link): Int {
        val text = extractText(link)
        if (text.isBlank()) return 0
        return text.trim().split(WHITESPACE_REGEX).size
    }

    /** Chuyen HTML thanh plain text, GIU LAI ranh gioi doan van bang "\n\n" -- can cho TTS
     * (TtsSentenceSplitter#splitWithOffsets dung "\n\n" de biet cau nao la dau doan, tu do ap
     * dung Paragraph Pause rieng thay vi Sentence Pause, xem TtsSettingsStore). Truoc day ham
     * nay collapse thang moi whitespace (ke ca xuong dong) thanh 1 space duy nhat nen thong tin
     * ranh gioi doan bi mat hoan toan truoc khi toi splitter -- phai chen "\n\n" o day, TRUOC
     * khi strip tag va collapse whitespace, moi cho cac the block-level thuong gap trong EPUB
     * XHTML (p/div/li/h1-h6/blockquote/br+br). Khong lam thay doi ket qua cho wordCount() (van
     * trim + split theo WHITESPACE_REGEX nhu cu, "\n\n" van la whitespace binh thuong). */
    private fun htmlToText(html: String): String {
        val noScriptsStyles = html.replace(SCRIPT_STYLE_REGEX, " ")
        val withParagraphBreaks = noScriptsStyles
            .replace(BLOCK_CLOSE_TAG_REGEX, "\n\n")
            .replace(DOUBLE_BR_REGEX, "\n\n")
        val noTags = withParagraphBreaks.replace(TAG_REGEX, " ")
        val unescaped = noTags
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        // Collapse khoang trang THUONG (space/tab/1 lan xuong dong) ve 1 space, nhung giu lai
        // "\n\n" (2 lan xuong dong lien tiep tro len) lam danh dau ranh gioi doan cho splitter.
        return unescaped
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex(" *\\n{2,} *"), "\n\n")
            .replace(Regex("(?<!\\n)\\n(?!\\n)"), " ")
            .trim('\n', ' ')
    }

    companion object {
        private val SCRIPT_STYLE_REGEX = Regex("(?is)<(script|style)[^>]*>.*?</\\1>")
        private val TAG_REGEX = Regex("(?s)<[^>]+>")
        private val WHITESPACE_REGEX = Regex("\\s+")

        // Cac the dong block-level pho bien trong EPUB XHTML -- dong "close" (hoac self-close)
        // cua cac the nay danh dau ket thuc 1 doan van. Dung the dong (khong phai the mo) vi vi
        // tri chen "\n\n" can nam SAU noi dung doan, truoc khi noi dung doan ke tiep bat dau.
        private val BLOCK_CLOSE_TAG_REGEX = Regex(
            "(?is)</(p|div|li|h1|h2|h3|h4|h5|h6|blockquote|tr)\\s*>",
        )

        // <br/><br/> (co the co whitespace xen giua) thuong duoc dung thay cho <p> that trong
        // EPUB cu -- coi nhu 1 ranh gioi doan.
        private val DOUBLE_BR_REGEX = Regex("(?is)(<br\\s*/?>\\s*){2,}")
    }
}
