package net.mrphan.calibre.reader.tts

/**
 * Splits a chapter's text (already extracted via
 * [net.mrphan.calibre.reader.epub.EpubTextExtractor]) into sentences to speak one by one. No
 * external NLP library: it mirrors Nghe Epub's splitter by ending sentences on strong
 * punctuation, then splitting only overlong pieces at soft punctuation before falling back to
 * whitespace. This keeps natural sentence boundaries for read-aloud while preventing a single
 * utterance from becoming too large for a TTS engine.
 */
object TtsSentenceSplitter {
    private val SENTENCE_END_REGEX = Regex("[.!?…。！？;；:]+\\s*")
    private val SOFT_BREAK_REGEX = Regex("\\s*[,，、•·]\\s+|\\s+-\\s+|\\s+(?=[•·▪◦])")
    private const val MAX_CHUNK_LENGTH = 300

    /** Mot cau da tach, kem vi tri ky tu CHINH XAC trong chuoi goc truyen vao
     * [splitWithOffsets] -- dung de TtsSentenceHighlighter tinh progression cho Locator.
     * [isParagraphStart] = true neu day la cau DAU TIEN cua 1 doan van (paragraph) moi -- dung
     * de TtsController#pauseBeforeSentence chon Paragraph Pause thay vi Sentence Pause (xem
     * TtsSettingsStore). Ranh gioi doan duoc [net.mrphan.calibre.reader.epub.EpubTextExtractor]
     * danh dau bang "\n\n" trong [text] goc TRUOC khi bi .trim()/gop khoang trang o day. */
    data class TtsSentenceSpan(
        val text: String,
        val startOffset: Int,
        val endOffset: Int,
        val isParagraphStart: Boolean = false,
    )

    fun split(text: String): List<String> = splitWithOffsets(text).map { it.text }

    /** Giu nguyen thuat toan tach cau cua [split], chi khac o cho tra them offset ky tu tuyet doi
     * trong [text] goc cho moi cau. */
    fun splitWithOffsets(text: String): List<TtsSentenceSpan> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val leadingTrim = text.indexOf(trimmed.first())
        // Vi tri (trong `text` GOC, chua trim) ngay SAU moi lan xuong dong kep "\n{2,}" -- moi
        // cau bat dau tai hoac ngay sau 1 trong cac vi tri nay la cau dau doan. Dung `text` goc
        // (khong phai `trimmed`) vi absoluteStart o duoi cung duoc tinh tren he toa do cua
        // `text` goc.
        val paragraphBreakEnds = Regex("\\n{2,}").findAll(text).map { it.range.last + 1 }.toSet()
        val result = mutableListOf<TtsSentenceSpan>()
        var segmentStart = 0
        var isFirstSentenceOfChapter = true

        fun appendPiece(rawPiece: String, startInTrimmed: Int) {
            val p = rawPiece.trim()
            if (p.isEmpty()) return
            val pieceStartInRaw = rawPiece.indexOf(p)
            if (pieceStartInRaw < 0) return
            val absoluteStart = leadingTrim + startInTrimmed + pieceStartInRaw
            // Cau dau tien cua ca chuong luon tinh la dau doan (khong co pause nao truoc do ca vi
            // TtsController#pauseBeforeSentence bo qua index <= 0), va bat ky cau nao bat dau
            // ngay sau 1 ranh gioi "\n\n" cung vay -- do gan mot khoang dung sai nho (4 ky tu) vi
            // piece da qua .trim() nen absoluteStart la ky tu KHONG-whitespace dau tien, co the
            // lech vai ky tu whitespace so voi vi tri "\n\n" trong text goc.
            val paragraphStart = isFirstSentenceOfChapter ||
                paragraphBreakEnds.any { it in searchRangeStart(absoluteStart)..absoluteStart }
            isFirstSentenceOfChapter = false
            val chunks = splitLongChunkWithOffsets(p, absoluteStart)
            chunks.forEachIndexed { index, chunk ->
                // Chi chunk DAU TIEN cua 1 cau qua dai moi ke thua co dau-doan cua ca cau -- cac
                // chunk sau chi la phan tiep tuc cung 1 cau bi cat ngan vi qua dai, khong phai
                // doan van moi.
                result += if (index == 0) chunk.copy(isParagraphStart = paragraphStart) else chunk
            }
        }

        SENTENCE_END_REGEX.findAll(trimmed).forEach { match ->
            val segmentEnd = match.range.last + 1
            appendPiece(trimmed.substring(segmentStart, segmentEnd), segmentStart)
            segmentStart = segmentEnd
        }
        if (segmentStart < trimmed.length) {
            appendPiece(trimmed.substring(segmentStart), segmentStart)
        }
        return result
    }

    /** Vi tri bat dau khoang tim ranh gioi doan ngay truoc [absoluteStart] -- gioi han 4 ky tu
     * lui lai la du de bat cac truong hop whitespace/dau nhay con sot lai giua "\n\n" va ky tu
     * dau tien cua cau. */
    private fun searchRangeStart(absoluteStart: Int): Int = (absoluteStart - 4).coerceAtLeast(0)

    private fun splitLongChunkWithOffsets(text: String, baseOffset: Int): List<TtsSentenceSpan> {
        if (text.length <= MAX_CHUNK_LENGTH) {
            return listOf(TtsSentenceSpan(text, baseOffset, baseOffset + text.length))
        }

        val softChunks = mutableListOf<TtsSentenceSpan>()
        var segmentStart = 0
        SOFT_BREAK_REGEX.findAll(text).forEach { match ->
            appendTrimmedChunk(text, segmentStart, match.range.first, baseOffset, softChunks)
            segmentStart = match.range.last + 1
        }
        appendTrimmedChunk(text, segmentStart, text.length, baseOffset, softChunks)
        if (softChunks.size > 1) {
            return softChunks.flatMap { chunk ->
                splitLongChunkWithOffsets(chunk.text, chunk.startOffset)
            }
        }

        val chunks = mutableListOf<TtsSentenceSpan>()
        var cursor = 0
        while (cursor < text.length) {
            val remaining = text.length - cursor
            if (remaining <= MAX_CHUNK_LENGTH) {
                appendTrimmedChunk(text, cursor, text.length, baseOffset, chunks)
                break
            }
            val limit = cursor + MAX_CHUNK_LENGTH
            val lastSpace = text.lastIndexOf(' ', limit - 1).takeIf { it >= cursor } ?: -1
            val cut = if (lastSpace - cursor > MAX_CHUNK_LENGTH / 2) lastSpace else limit
            appendTrimmedChunk(text, cursor, cut, baseOffset, chunks)
            cursor = cut
            while (cursor < text.length && text[cursor].isWhitespace()) cursor++
        }
        return chunks
    }

    private fun appendTrimmedChunk(
        source: String,
        start: Int,
        end: Int,
        baseOffset: Int,
        chunks: MutableList<TtsSentenceSpan>,
    ) {
        if (start >= end) return
        val raw = source.substring(start, end)
        val chunkText = raw.trim()
        if (chunkText.isEmpty()) return
        val chunkStartInRaw = raw.indexOf(chunkText)
        if (chunkStartInRaw < 0) return
        val chunkStart = start + chunkStartInRaw
        chunks += TtsSentenceSpan(
            chunkText,
            baseOffset + chunkStart,
            baseOffset + chunkStart + chunkText.length,
        )
    }
}
