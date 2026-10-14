package net.mrphan.calibre.reader.convert

import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.MalformedInputException

object TxtParser {

    fun parse(bytes: ByteArray, suggestedTitle: String): ParsedBook {
        if (bytes.isEmpty()) {
            throw EbookConversionException.CorruptFile("txt file is empty")
        }
        val text = decodeWithBestGuessCharset(bytes)
        val chapters = splitIntoChapters(text)
        return ParsedBook(
            title = suggestedTitle,
            chapterHtmlBlocks = chapters.map { paragraph -> plainTextToHtml(paragraph) },
            images = emptyList(),
            coverImageIndex = null,
            isLimitedCompatibilityMode = false,
        )
    }

    /**
     * Dò encoding theo thứ tự: BOM tường minh (UTF-8/UTF-16LE/UTF-16BE) -> thử UTF-8 nghiêm ngặt
     * (báo lỗi nếu gặp byte không hợp lệ, thay vì U+FFFD lặng lẽ) -> fallback Windows-1258 (trang
     * mã Windows cho tiếng Việt, phổ biến với file .txt cũ export từ Windows) -> cuối cùng
     * ISO-8859-1 (không bao giờ lỗi decode, vì map 1-1 mọi byte -- "an toàn cuối cùng" dù có thể
     * hiển thị sai ký tự có dấu).
     *
     * KHÔNG xử lý bảng mã font rời (TCVN3/VNI-Windows) -- đây là font riêng dùng vùng mã Unicode
     * Private Use hoặc ánh xạ ASCII tuỳ ý theo từng font, về bản chất không phải 1 charset chuẩn
     * nên không thể tự động dò bằng CharsetDecoder. Sách .txt dùng font này sẽ hiển thị được
     * nhưng SAI dấu tiếng Việt -- đây là giới hạn đã biết, không phải bug, không cần fix trong
     * phạm vi nâng cấp này.
     */
    private fun decodeWithBestGuessCharset(bytes: ByteArray): String {
        when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        try {
            return strictDecode(bytes, Charsets.UTF_8)
        } catch (e: MalformedInputException) {
            // Khong phai UTF-8 hop le -- thu tiep cac charset pho bien voi van ban tieng Viet cu.
        }
        val windows1258 = try { Charset.forName("windows-1258") } catch (e: Exception) { null }
        if (windows1258 != null) {
            try {
                return strictDecode(bytes, windows1258)
            } catch (e: MalformedInputException) {
                // rơi xuống ISO-8859-1 bên dưới
            }
        }
        // ISO-8859-1: anh xa 1-1 tung byte, khong bao gio nem MalformedInputException -- luon la
        // "an toan cuoi cung" du co the hien sai mot so ky tu co dau.
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset): String {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }

    /** Tách chương theo 2 heuristics kết hợp:
     *  1. Dòng trống liên tiếp (>= 2 dòng \n\n) luôn là ranh giới đoạn văn (paragraph) -- áp dụng
     *     để build HTML <p> đúng, không liên quan tới việc tách CHƯƠNG.
     *  2. 1 dòng NGẮN (<= 80 ký tự) khớp regex "chương/chapter/phần/part + số" ở đầu dòng được
     *     coi là tiêu đề chương mới -- vd "Chương 1", "Chương I:", "CHAPTER 12", "Phần 3".
     * Nếu không tìm thấy heading nào theo (2), toàn bộ file là 1 chương duy nhất -- KHÔNG chia
     * theo độ dài cố định (dễ cắt giữa câu/từ, đặc biệt xấu với văn bản tiếng Việt có dấu).
     */
    private val CHAPTER_HEADING_REGEX = Regex(
        "^\\s{0,4}(ch(ươ|uơ)ng|chapter|phần|part)\\s+[ivxlcdm0-9]+\\b.{0,60}$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )

    internal fun splitIntoChapters(text: String): List<String> {
        val headingMatches = CHAPTER_HEADING_REGEX.findAll(text).map { it.range.first }.toList()
        if (headingMatches.isEmpty()) return listOf(text)
        val bounds = headingMatches + listOf(text.length)
        return (0 until bounds.size - 1).map { idx -> text.substring(bounds[idx], bounds[idx + 1]) }
            .filter { it.isNotBlank() }
    }

    /** Bọc 1 khối plain text thành các đoạn `<p>`, escape đúng các ký tự đặc biệt XHTML (&, <, >)
     * -- KHÔNG được bỏ qua bước escape này, nội dung .txt bất kỳ có thể chứa `<`/`&` (ví dụ email,
     * code, hoặc chính văn bản HTML dán nhầm vào .txt) mà nếu không escape sẽ tạo ra 1 file
     * XHTML KHÔNG HỢP LỆ, khiến Readium's WebView render sai hoặc rỗng trắng trang. */
    private fun plainTextToHtml(paragraphBlock: String): String {
        val paragraphs = paragraphBlock.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val sb = StringBuilder()
        for (p in paragraphs) {
            sb.append("<p>")
            sb.append(escapeXhtml(p).replace("\n", "<br/>"))
            sb.append("</p>\n")
        }
        return sb.toString()
    }

    private fun escapeXhtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
