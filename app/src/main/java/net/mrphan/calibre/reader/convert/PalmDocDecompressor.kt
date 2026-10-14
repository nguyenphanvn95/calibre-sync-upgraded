package net.mrphan.calibre.reader.convert

/**
 * Giải nén 1 "text record" của MOBI/PalmDOC khi header báo compression == 2 (PalmDOC/LZ77).
 * Không dùng cho compression == 1 (không nén, dùng thẳng bytes gốc) hay 17480 (HUFF/CDIC, xem
 * EbookConversionException.UnsupportedCompression -- không implement ở đây).
 *
 * Thuật toán theo đúng spec công khai (mobileread.com/wiki/PalmDOC), 1 byte điều khiển mỗi lần,
 * 4 nhóm giá trị byte điều khiển `c` (0..255):
 *  - c == 0            : literal, chép nguyên 1 byte 0x00
 *  - 0x01..0x08        : "literal run" -- c byte NGAY SAU byte điều khiển được chép nguyên văn
 *  - 0x09..0x7F        : literal, chính byte điều khiển là 1 ký tự ASCII, chép nguyên
 *  - 0x80..0xBF        : cặp 2 byte length-distance kiểu LZ77 (đọc thêm 1 byte nữa)
 *  - 0xC0..0xFF        : 2 ký tự literal ngầm định: dấu cách (0x20) + (c xor 0x80)
 *
 * Cặp length-distance (0x80..0xBF): gộp 6 bit thấp của byte điều khiển đầu với 8 bit của byte
 * thứ hai thành số 14 bit `temp`; distance = temp >> 3 (11 bit, lùi lại bao nhiêu byte trong
 * OUTPUT đã giải nén, không phải input), length = (temp & 0x7) + 3 (3..10 byte). Việc copy có thể
 * "tự chồng lên chính nó" (self-overlapping), giống LZ77 thường -- copy TỪNG BYTE MỘT theo thứ
 * tự tăng dần, không dùng System.arraycopy nguyên khối, vì phần vừa ghi có thể được đọc lại ngay
 * trong cùng 1 lần copy (ví dụ lặp lại 1 ký tự nhiều lần).
 */
object PalmDocDecompressor {

    fun decompress(input: ByteArray): ByteArray {
        // Hệ số 3 là ước lượng tỉ lệ nén trung bình của PalmDOC cho văn bản thường (~2.5-3x) --
        // chỉ để giảm số lần ArrayList tự resize, không ảnh hưởng tính đúng đắn nếu ước lượng sai.
        val out = ArrayList<Byte>(input.size * 3)
        var i = 0
        while (i < input.size) {
            val c = input[i].toInt() and 0xFF
            i++
            when {
                c == 0 -> out.add(0)
                c in 0x01..0x08 -> {
                    var copied = 0
                    while (copied < c && i < input.size) {
                        out.add(input[i])
                        i++
                        copied++
                    }
                    // Nếu file bị cắt cụt giữa 1 literal run (i chạy hết input trước khi đủ `c`
                    // byte), vòng lặp trên tự dừng sớm thay vì đọc ra ngoài mảng -- kết quả là
                    // record cuối bị thiếu vài ký tự, KHÔNG throw, vì đây thường là record cuối
                    // của cả cuốn sách và không đáng để coi cả file là hỏng.
                }
                c in 0x09..0x7F -> out.add(c.toByte())
                c in 0x80..0xBF -> {
                    if (i >= input.size) break // byte thứ 2 của cặp bị thiếu -- record cụt, dừng êm
                    val c2 = input[i].toInt() and 0xFF
                    i++
                    val temp = ((c and 0x3F) shl 8) or c2
                    val distance = temp shr 3
                    val length = (temp and 0x07) + 3
                    var srcPos = out.size - distance
                    if (srcPos < 0) {
                        // distance trỏ về trước cả điểm bắt đầu output -- record hỏng/offset sai.
                        // Không throw ở tầng này (1 record lỗi không nên làm hỏng cả cuốn sách):
                        // đệm khoảng trắng cho đủ length rồi tiếp tục, để MobiParser ở tầng trên
                        // quyết định có coi cả file là CorruptFile hay không dựa trên việc kết quả
                        // cuối cùng có "rỗng bất thường" hay không.
                        repeat(length) { out.add(' '.code.toByte()) }
                    } else {
                        repeat(length) {
                            out.add(out[srcPos])
                            srcPos++
                        }
                    }
                }
                else -> { // 0xC0..0xFF
                    out.add(' '.code.toByte())
                    out.add((c xor 0x80).toByte())
                }
            }
        }
        return out.toByteArray()
    }
}
