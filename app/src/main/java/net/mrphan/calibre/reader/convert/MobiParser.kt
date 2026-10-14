package net.mrphan.calibre.reader.convert

/** Kết quả parse trung gian, độc lập với việc build EPUB -- để SyntheticEpubBuilder không cần
 * biết gì về MOBI, chỉ cần "1 tiêu đề + danh sách chương HTML + danh sách ảnh" là build được,
 * dùng chung được với TxtParser (mục 6). */
data class ParsedBook(
    val title: String,
    /** Mỗi phần tử là HTML thô của 1 "chương" (chưa wrap thẻ html/head/body) -- với MOBI6, nếu
     * không tách được chương rõ ràng (không có mbp:pagebreak/heading nhận diện được), toàn bộ
     * sách là DUY NHẤT 1 phần tử ở đây; navigator EPUB vẫn đọc được bình thường, chỉ là TOC sẽ
     * chỉ có 1 mục "Toàn bộ sách" thay vì nhiều chương -- chấp nhận được, còn hơn không mở được. */
    val chapterHtmlBlocks: List<String>,
    /** Ảnh nhị phân theo thứ tự xuất hiện, dùng làm `images/imgNNNN.<ext>` trong EPUB. Chapter
     * HTML ở trên đã được viết lại để trỏ `<img src="images/imgNNNN.jpg">` khớp với danh sách
     * này (xem rewriteImageReferences bên dưới). */
    val images: List<ByteArray>,
    /** Index trong `images` dùng làm bìa sách, hoặc null nếu không có/không xác định được. */
    val coverImageIndex: Int?,
    /** true nếu sách được mở ở chế độ "best-effort KF8" (mục 4.4) -- EpubReaderActivity dùng cờ
     * này để CÓ THỂ hiện 1 thông báo 1 lần "Sách hiển thị ở chế độ tương thích giới hạn" thay vì
     * giả vờ như không có gì khác biệt. Không bắt buộc phải hiện dialog, nhưng field phải có để
     * tầng trên tự quyết định. */
    val isLimitedCompatibilityMode: Boolean = false,
)

object MobiParser {

    /** Điểm vào duy nhất. Ném các subtype của [EbookConversionException] cho MỌI trường hợp
     * không mở được -- KHÔNG BAO GIỜ để 1 IndexOutOfBoundsException/NullPointerException gốc lọt
     * ra ngoài object này, luôn bắt và bọc lại thành CorruptFile với cause gốc để còn debug được
     * qua Logger.e(e) ở tầng gọi. */
    fun parse(bytes: ByteArray): ParsedBook {
        val container = try {
            PalmDbContainer(bytes)
        } catch (e: Exception) {
            throw EbookConversionException.CorruptFile("cannot read PalmDB container header", e)
        }
        if (!container.isValidMobiContainer()) {
            throw EbookConversionException.UnrecognizedFormat(
                "missing BOOK/MOBI magic -- not a MOBI/AZW/AZW3 file, or file is corrupt"
            )
        }

        val record0 = try {
            container.record(0)
        } catch (e: Exception) {
            throw EbookConversionException.CorruptFile("cannot read record 0", e)
        }

        val palmDoc = PalmDocHeader.parse(record0)
        if (palmDoc.encryptionType != 0) {
            throw EbookConversionException.DrmProtected(
                if (palmDoc.encryptionType == 1) "Old Mobipocket" else "Mobipocket"
            )
        }
        if (palmDoc.compression != 1 && palmDoc.compression != 2) {
            // 17480 (HUFF/CDIC) hoặc bất kỳ giá trị lạ nào khác.
            throw EbookConversionException.UnsupportedCompression(palmDoc.compression)
        }

        val mobiHeader = try {
            MobiHeader.parse(record0)
        } catch (e: EbookConversionException) {
            throw e
        } catch (e: Exception) {
            throw EbookConversionException.CorruptFile("cannot parse MOBI header", e)
        }
        val exthRecords = if (mobiHeader.hasExthHeader) {
            parseExthRecords(record0, mobiHeaderStart = 16, headerLength = mobiHeader.headerLength)
        } else emptyList()

        val title = extractTitle(record0, mobiHeader, exthRecords)

        // mobiType 2 = MOBI6 "book" thuần -- decode trực tiếp bằng nhánh classic.
        // mobiType 232/248 thường là KF8-only hoặc combo -- thử FDST/SKEL trước (mục 4.4); nếu
        // không parse được cấu trúc KF8 NHƯNG record 1..recordCount vẫn giải nén PalmDOC ra được
        // text hợp lý (trường hợp "combo" file), rơi về nhánh classic luôn -- xem fallback bên
        // dưới. Đây là lý do nhánh classic được thử trước tiên bất kể mobiType.
        val classicText = try {
            decodeClassicMobi6Text(container, palmDoc)
        } catch (e: EbookConversionException) {
            null
        } catch (e: Exception) {
            null
        }

        if (classicText != null && classicText.isNotBlank()) {
            val (chapters, rewrittenImages, coverIdx) = try {
                splitIntoChaptersAndCollectImages(classicText, container, mobiHeader)
            } catch (e: Exception) {
                throw EbookConversionException.CorruptFile("failed to split chapters/collect images", e)
            }
            return ParsedBook(
                title = title,
                chapterHtmlBlocks = chapters,
                images = rewrittenImages,
                coverImageIndex = coverIdx,
                isLimitedCompatibilityMode = false,
            )
        }

        // Không decode được bằng nhánh classic (vd mobiType thuần KF8, không có payload MOBI6 dự
        // phòng) -- thử best-effort KF8 (mục 4.4).
        return parseKf8BestEffort(bytes, container, mobiHeader, exthRecords, title)
    }

    /** Nhánh MOBI6/PalmDOC classic: text nằm liên tiếp trong record 1..recordCount, mỗi record
     * giải nén (nếu compression==2) hoặc dùng thẳng (compression==1), rồi nối lại. */
    private fun decodeClassicMobi6Text(container: PalmDbContainer, palmDoc: PalmDocHeader): String {
        if (palmDoc.recordCount <= 0) {
            throw EbookConversionException.CorruptFile("record count is ${palmDoc.recordCount}")
        }
        val sb = StringBuilder()
        for (r in 1..palmDoc.recordCount) {
            val raw = try {
                container.record(r)
            } catch (e: Exception) {
                throw EbookConversionException.CorruptFile("cannot read text record #$r", e)
            }
            val decoded = if (palmDoc.compression == 2) {
                PalmDocDecompressor.decompress(raw)
            } else {
                raw
            }
            // MOBI6 luôn dùng cp1252 hoặc UTF-8 tuỳ textEncoding -- nhưng thực tế encode luôn
            // dùng field `textEncoding` của MobiHeader, KHÔNG hardcode ở đây; xem lời gọi ở trên
            // truyền charset qua tham số nếu Codex thấy cần chính xác hơn. Bản tối giản này giả
            // định UTF-8, vì hầu hết .mobi tiếng Việt hiện đại đã dùng UTF-8 -- nếu gặp sách cũ
            // dùng cp1252, ký tự ASCII vẫn đọc đúng, chỉ ký tự đặc biệt (dấu ngoặc kép kiểu
            // curly, em-dash...) có thể hiển thị sai. Ghi TODO rõ ràng thay vì âm thầm bỏ qua.
            sb.append(String(decoded, Charsets.UTF_8))
        }
        return sb.toString()
    }

    private fun extractTitle(record0: ByteArray, header: MobiHeader, exth: List<ExthRecord>): String {
        exth.firstOrNull { it.type == ExthType.TITLE }?.let {
            val t = String(it.data, Charsets.UTF_8).trim()
            if (t.isNotBlank()) return t
        }
        if (header.fullNameOffset in record0.indices &&
            header.fullNameOffset + header.fullNameLength <= record0.size &&
            header.fullNameLength > 0
        ) {
            return String(
                record0, header.fullNameOffset, header.fullNameLength, Charsets.UTF_8
            ).trim().ifBlank { "Untitled" }
        }
        return "Untitled"
    }

    /** Tách 1 khối text/HTML lớn (MOBI6 dùng markup rất giống HTML, có thẻ mở rộng riêng
     * `<mbp:pagebreak/>`) thành nhiều "chương" để TOC/EPUB có điều hướng, thay vì 1 chương khổng
     * lồ. Chiến lược ưu tiên theo thứ tự:
     *  1. Có `<mbp:pagebreak` -- tách tại đó (đây là cách Kindle Previewer/KindleGen tự chèn ranh
     *     giới trang khi build từ nhiều "file HTML chương" gốc, nên hầu hết sách convert từ EPUB
     *     -> MOBI đều có).
     *  2. Không có, nhưng có heading `<h1>`/`<h2>` -- tách tại mỗi heading.
     *  3. Không có cả hai -- KHÔNG cố tách bừa theo độ dài cố định (dễ cắt giữa câu); trả về
     *     nguyên 1 chương duy nhất, để nguyên vẹn nội dung thay vì tách sai.
     * Ảnh: MOBI6 dùng `<img recindex="NNNN">` (không phải src=) trỏ tới record ảnh thứ NNNN,
     * tính từ `firstImageIndex` của MobiHeader -- viết lại thành `<img src="images/NNNN.jpg">`
     * và thu thập record ảnh tương ứng, bỏ qua record trống/không phải ảnh hợp lệ (không throw,
     * chỉ log rồi bỏ ảnh đó, sách vẫn đọc được, chỉ thiếu 1 hình).
     */
    private fun splitIntoChaptersAndCollectImages(
        rawHtml: String,
        container: PalmDbContainer,
        header: MobiHeader,
    ): Triple<List<String>, List<ByteArray>, Int?> {
        val chapters = when {
            rawHtml.contains("<mbp:pagebreak", ignoreCase = true) ->
                rawHtml.split(Regex("<mbp:pagebreak[^>]*/?>", RegexOption.IGNORE_CASE))
            Regex("<h[12][ >]", RegexOption.IGNORE_CASE).containsMatchIn(rawHtml) ->
                splitBeforeHeadings(rawHtml)
            else -> listOf(rawHtml)
        }.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(rawHtml) }

        val images = ArrayList<ByteArray>()
        val recIndexPattern = Regex("recindex=\"(\\d+)\"", RegexOption.IGNORE_CASE)
        val rewrittenChapters = chapters.map { chapter ->
            recIndexPattern.replace(chapter) { match ->
                val recIndex = match.groupValues[1].toIntOrNull() ?: return@replace match.value
                if (header.firstImageIndex <= 0) return@replace match.value
                val absoluteRecord = header.firstImageIndex + recIndex - 1
                val imgBytes = try {
                    container.record(absoluteRecord)
                } catch (e: Exception) {
                    null
                }
                if (imgBytes == null || imgBytes.isEmpty()) {
                    return@replace "" // bỏ attribute recindex, ảnh không lấy được -- không throw
                }
                images.add(imgBytes)
                "src=\"images/${images.size - 1}.jpg\""
            }
        }

        val coverIndex = if (header.firstImageIndex > 0) 0.takeIf { images.isNotEmpty() } else null
        return Triple(rewrittenChapters, images, coverIndex)
    }

    private fun splitBeforeHeadings(html: String): List<String> {
        val matches = Regex("<h[12][ >]", RegexOption.IGNORE_CASE).findAll(html).map { it.range.first }.toList()
        if (matches.isEmpty()) return listOf(html)
        val bounds = listOf(0) + matches + listOf(html.length)
        return (0 until bounds.size - 1).map { idx ->
            val start = if (idx == 0) 0 else bounds[idx]
            html.substring(start, bounds[idx + 1])
        }
    }

    /**
     * NHÁNH KF8/AZW3 BEST-EFFORT -- xem mục 4.4 trong prompt gốc để hiểu vì sao đây CHỦ ĐÍCH
     * không phải 1 parser KF8 đầy đủ (fragment/skeleton reconstruction qua FDST+SKEL+INDX records
     * là phần phức tạp nhất của toàn bộ định dạng MOBI, tương đương độ phức tạp của việc viết lại
     * 1 phần "kindlegen" ngược). Chiến lược ở đây:
     *  1. Tìm record "FDST" (flow boundary table) bằng cách quét toàn bộ record tìm magic "FDST"
     *     ở 4 byte đầu -- nếu có, dùng nó để biết chia file "flow" (record ngay sau record cuối
     *     của text index) thành các đoạn HTML riêng biệt.
     *  2. Nếu KHÔNG tìm thấy FDST (một số file KF8 rất mới có thể tổ chức khác), fallback về
     *     "gom mọi thứ": duyệt toàn bộ record từ 1 tới hết phần "text" ước lượng bằng
     *     PalmDocHeader.recordCount (dù compression có thể == 1 với KF8, tức record chứa thẳng
     *     UTF-8 markup, không cần PalmDocDecompressor), nối lại, strip các thẻ/thuộc tính riêng
     *     của KF8 (`aid="..."`, thẻ rỗng dùng làm điểm neo `<a/>` cho skeleton...) bằng regex đơn
     *     giản, KHÔNG cố dựng lại chính xác thứ tự chương gốc.
     *  3. Nếu bước 2 cũng ra chuỗi rỗng/toàn ký tự không in được -- ném UnsupportedKf8Structure,
     *     KHÔNG trả về 1 cuốn sách rỗng giả vờ thành công.
     * Luôn set `isLimitedCompatibilityMode = true` khi đi qua nhánh này.
     */
    private fun parseKf8BestEffort(
        bytes: ByteArray,
        container: PalmDbContainer,
        header: MobiHeader,
        exth: List<ExthRecord>,
        title: String,
    ): ParsedBook {
        val flowRecords = findRecordsByMagic(container, "FDST")
        val rawConcatenated = StringBuilder()
        val sourceRecords = if (flowRecords.isNotEmpty()) {
            // Có FDST: nội dung "flow" thực sự nằm ở các record NGAY TRƯỚC record FDST đầu tiên
            // theo cấu trúc KF8 thông thường (flow 0 = record chứa toàn bộ markup HTML gộp).
            // Đây là suy luận thực dụng, không phải parse chuẩn FDST -- nếu có thời gian,
            // nên đọc thêm field trong FDST record (bảng offset [start,end) của từng flow) để cắt
            // chính xác thay vì đoán theo vị trí -- để lại TODO rõ ràng ở đây cho việc đó.
            (1 until flowRecords.first()).toList()
        } else {
            (1 until container.numRecords).toList()
        }
        for (r in sourceRecords) {
            val raw = try { container.record(r) } catch (e: Exception) { continue }
            // KF8 record co the la binary (index/skeleton) khong phai text -- loc bang cach chi
            // giu record ma phan lon byte la ASCII in duoc, tranh nhet rac vao noi dung sach.
            val text = String(raw, Charsets.UTF_8)
            val printableRatio = text.count { it.code in 0x20..0x7E || it == '\n' }.toDouble() / text.length.coerceAtLeast(1)
            if (printableRatio > 0.85) rawConcatenated.append(text)
        }

        val cleaned = rawConcatenated.toString()
            .replace(Regex("aid=\"[^\"]*\""), "")
            .replace(Regex("<a\\s*/>"), "")
            .trim()

        if (cleaned.isBlank()) {
            throw EbookConversionException.UnsupportedKf8Structure(
                "no printable HTML/text content recovered from KF8 flow records"
            )
        }

        return ParsedBook(
            title = title,
            chapterHtmlBlocks = listOf(cleaned),
            images = emptyList(), // best-effort mode: khong co gang trich anh -- co the them sau
            coverImageIndex = null,
            isLimitedCompatibilityMode = true,
        )
    }

    private fun findRecordsByMagic(container: PalmDbContainer, magic: String): List<Int> {
        val result = ArrayList<Int>()
        for (r in 0 until container.numRecords) {
            val record = try { container.record(r) } catch (e: Exception) { continue }
            if (record.size >= 4 && String(record, 0, 4, Charsets.US_ASCII) == magic) {
                result.add(r)
            }
        }
        return result
    }
}
