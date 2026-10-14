package net.mrphan.calibre.reader.convert

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Đọc 1 file MOBI/AZW/AZW3 dạng "PalmDB container": 1 header PalmDB 78 byte, theo sau là bảng
 * offset của N record, rồi tới nội dung từng record. Record 0 luôn là "MOBI header" (chứa
 * PalmDOC sub-header 16 byte + MOBI header + EXTH tuỳ chọn); record 1..N là dữ liệu (text nén,
 * ảnh, index...) tuỳ theo field trong record 0 chỉ ra loại gì nằm ở đâu. */
internal class PalmDbContainer(private val bytes: ByteArray) {

    private val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

    /** "BOOK" (type) + "MOBI" (creator) ở offset 60/64 -- offset 0 la ten sach (32 byte), roi den
     * cac field khac; type/creator la 2 chuoi 4 ky tu lien tiep bat dau tu offset 60. */
    fun isValidMobiContainer(): Boolean {
        if (bytes.size < 78) return false
        val type = String(bytes, 60, 4, Charsets.US_ASCII)
        val creator = String(bytes, 64, 4, Charsets.US_ASCII)
        return type == "BOOK" && creator == "MOBI"
    }

    val numRecords: Int get() = buf.getShort(76).toInt() and 0xFFFF

    /** Offset (tuyệt đối trong `bytes`) nơi record thứ [index] bắt đầu. Bảng record-info nằm
     * ngay sau header 78 byte, mỗi entry 8 byte (4 byte offset + 4 byte attributes/uniqueID) --
     * ta chỉ cần 4 byte offset đầu của mỗi entry. */
    fun recordOffset(index: Int): Int {
        val entryStart = 78 + index * 8
        require(entryStart + 4 <= bytes.size) {
            "Record #$index offset table out of bounds (numRecords=$numRecords, file size=${bytes.size})"
        }
        return buf.getInt(entryStart)
    }

    /** Bytes thô của record [index], từ offset của nó tới offset record kế tiếp (hoặc hết file
     * nếu là record cuối). */
    fun record(index: Int): ByteArray {
        val start = recordOffset(index)
        val end = if (index + 1 < numRecords) recordOffset(index + 1) else bytes.size
        require(start in 0..bytes.size && end in start..bytes.size) {
            "Record #$index range [$start, $end) out of bounds for file size ${bytes.size}"
        }
        return bytes.copyOfRange(start, end)
    }
}

/** PalmDOC sub-header, 16 byte đầu của record 0. */
internal data class PalmDocHeader(
    val compression: Int,       // 1 = none, 2 = PalmDOC/LZ77, 17480 = HUFF/CDIC (không hỗ trợ)
    val textLength: Long,       // tổng độ dài văn bản ĐÃ giải nén (byte)
    val recordCount: Int,       // số record text (record 1..recordCount)
    val recordSize: Int,        // kích thước tối đa mỗi record SAU KHI giải nén (thường 4096)
    val encryptionType: Int,    // 0 = không DRM, khác 0 = có DRM (Old Mobipocket / Mobipocket)
) {
    companion object {
        fun parse(record0: ByteArray): PalmDocHeader {
            val b = ByteBuffer.wrap(record0).order(ByteOrder.BIG_ENDIAN)
            return PalmDocHeader(
                compression = b.getShort(0).toInt() and 0xFFFF,
                textLength = b.getInt(4).toLong() and 0xFFFFFFFFL,
                recordCount = b.getShort(8).toInt() and 0xFFFF,
                recordSize = b.getShort(10).toInt() and 0xFFFF,
                encryptionType = b.getShort(12).toInt() and 0xFFFF,
            )
        }
    }
}

/** MOBI header thật sự, bắt đầu ngay tại byte offset 16 của record 0 (sau PalmDocHeader).
 * Chỉ lấy các field cần dùng cho việc tái tạo EPUB -- KHÔNG lấy hết toàn bộ spec MOBI (rất dài,
 * nhiều field liên quan tới Huffman/DRM/index không cần cho use-case đọc-only, không-DRM này). */
internal data class MobiHeader(
    val headerLength: Int,      // offset 20 (tính từ đầu record0): độ dài phần MOBI header
    val mobiType: Int,          // offset 24: 2 = MOBI book, 232/248 = KF8 hoặc combo -- xem mục 4.4
    val textEncoding: Int,      // offset 28: 1252 = Windows-1252, 65001 = UTF-8
    val fullNameOffset: Int,    // offset 84: offset (từ đầu record0) tới chuỗi tên sách đầy đủ
    val fullNameLength: Int,    // offset 88
    val firstImageIndex: Int,   // offset 108: index record ảnh đầu tiên (0xFFFFFFFF nếu không có ảnh)
    val exthFlags: Long,        // offset 128: bit 0x40 = có EXTH header theo sau MOBI header
) {
    companion object {
        fun parse(record0: ByteArray): MobiHeader {
            val b = ByteBuffer.wrap(record0).order(ByteOrder.BIG_ENDIAN)
            require(record0.size >= 132) {
                "record 0 too small to contain a MOBI header (${record0.size} bytes)"
            }
            val magic = String(record0, 16, 4, Charsets.US_ASCII)
            if (magic != "MOBI") {
                throw EbookConversionException.UnrecognizedFormat(
                    "record 0 missing 'MOBI' magic at offset 16 (found '$magic')"
                )
            }
            return MobiHeader(
                headerLength = b.getInt(20),
                mobiType = b.getInt(24),
                textEncoding = b.getInt(28),
                fullNameOffset = b.getInt(84),
                fullNameLength = b.getInt(88),
                firstImageIndex = b.getInt(108),
                exthFlags = b.getInt(128).toLong() and 0xFFFFFFFFL,
            )
        }
    }

    val hasExthHeader: Boolean get() = (exthFlags and 0x40L) != 0L
}

/** 1 record metadata trong EXTH header (vd tiêu đề, tác giả, cover index...). Chỉ những `type`
 * ta thực sự dùng được liệt kê làm hằng số bên dưới -- EXTH có ~40 loại type khác nhau, phần lớn
 * không liên quan tới việc dựng lại EPUB để đọc. */
internal data class ExthRecord(val type: Int, val data: ByteArray)

internal object ExthType {
    const val COVER_OFFSET = 201   // giá trị là INDEX (không phải offset byte) tính từ firstImageIndex
    const val THUMBNAIL_OFFSET = 202
    const val TITLE = 503          // tên sách "đầy đủ" thay thế, đôi khi khác fullNameOffset ở trên
}

/** Parse EXTH header nếu `MobiHeader.hasExthHeader == true`. Nằm ngay sau MOBI header, bắt đầu
 * bằng magic "EXTH" (4 byte) + độ dài header (4 byte) + số record (4 byte), rồi tới các record
 * (mỗi record: type 4 byte + length 4 byte [tính cả 8 byte header] + data). */
internal fun parseExthRecords(record0: ByteArray, mobiHeaderStart: Int, headerLength: Int): List<ExthRecord> {
    val exthStart = mobiHeaderStart + headerLength
    if (exthStart + 12 > record0.size) return emptyList()
    val magic = String(record0, exthStart, 4, Charsets.US_ASCII)
    if (magic != "EXTH") return emptyList()
    val b = ByteBuffer.wrap(record0).order(ByteOrder.BIG_ENDIAN)
    val recordCount = b.getInt(exthStart + 8)
    val result = ArrayList<ExthRecord>(recordCount)
    var pos = exthStart + 12
    repeat(recordCount) {
        if (pos + 8 > record0.size) return result // header khai sai recordCount -- dừng êm, không throw
        val type = b.getInt(pos)
        val length = b.getInt(pos + 4)
        val dataLen = (length - 8).coerceAtLeast(0)
        if (pos + 8 + dataLen > record0.size) return result
        result.add(ExthRecord(type, record0.copyOfRange(pos + 8, pos + 8 + dataLen)))
        pos += length
    }
    return result
}
