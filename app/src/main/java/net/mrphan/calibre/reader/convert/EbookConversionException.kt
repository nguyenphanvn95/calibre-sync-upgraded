package net.mrphan.calibre.reader.convert

/**
 * Mọi lỗi xảy ra trong quá trình chuyển MOBI/AZW/AZW3/TXT -> EPUB tạm trước khi đưa vào Readium.
 * Cố tình tách riêng khỏi lỗi "Readium không mở được EPUB" (EpubPublicationLoader#open() trả về
 * null, xử lý bởi EpubReaderActivity#showOpenErrorAndFinish() sẵn có) -- hai loại lỗi này có
 * nguyên nhân và thông báo khác nhau, gộp chung sẽ khiến người dùng không biết sách của họ có vấn
 * đề gì để tự khắc phục (ví dụ convert lại bằng Calibre desktop).
 *
 * QUAN TRỌNG: mọi nơi throw các exception này PHẢI được bọc try/catch ở lớp gọi
 * (EbookFormatConverter#convert() là input/output duy nhất ra bên ngoài package này) -- không để
 * lọt ra ngoài làm crash Activity. Xem EpubPublicationLoader.kt và EpubReaderActivity.kt phần sửa
 * bên dưới.
 */
sealed class EbookConversionException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** File không đúng magic header PalmDB ("BOOKMOBI"/"TEXtREAd") hoặc không phải .txt đọc
     * được -- nhiều khả năng file bị hỏng, tải dở, hoặc thực chất là 1 định dạng khác đội lốt
     * đuôi file (vd .azw thực chất là PDF đổi tên). */
    class UnrecognizedFormat(detail: String) :
        EbookConversionException("Unrecognized ebook container: $detail")

    /** Record 0 báo compression = 17480 (HUFF/CDIC) -- nén từ điển, hầu như luôn đi kèm DRM hoặc
     * cần bảng Huffman riêng mà bản này không parse. Không cố đoán, từ chối rõ ràng. */
    class UnsupportedCompression(compressionType: Int) :
        EbookConversionException("Unsupported MOBI compression type: $compressionType")

    /** EXTH/record 0 báo Encryption Type != 0 (Old Mobipocket / Mobipocket DRM). Không có cách
     * nào đọc được nội dung nếu không có key -- luôn từ chối, không thử "đọc phần chưa mã hoá". */
    class DrmProtected(scheme: String) :
        EbookConversionException("DRM-protected ebook ($scheme), cannot be opened in-app")

    /** Offset/record index trỏ ra ngoài kích thước file thực tế, hoặc PalmDOC decompression tạo
     * ra dữ liệu vô nghĩa (ví dụ length tính ra âm) -- dấu hiệu file bị cắt cụt/hỏng giữa chừng
     * (ví dụ tải dở dang). */
    class CorruptFile(detail: String, cause: Throwable? = null) :
        EbookConversionException("Corrupt ebook file: $detail", cause)

    /** File là AZW3/KF8-only (không có phần MOBI6 dự phòng) và cấu trúc FDST/SKEL không parse
     * được theo logic best-effort ở MobiParser -- xem mục 4.4 trong prompt gốc để biết vì sao
     * đây là giới hạn đã biết chứ không phải bug. */
    class UnsupportedKf8Structure(detail: String) :
        EbookConversionException("Unsupported/unparseable KF8 (AZW3) structure: $detail")

    /** Lỗi I/O khi ghi file EPUB tổng hợp ra cache (hết dung lượng, quyền ghi, ...) -- khác hẳn
     * về nguyên nhân với các lỗi parse ở trên nên tách riêng để thông báo đúng ("hết bộ nhớ" thay
     * vì "sách bị lỗi"). */
    class OutputWriteFailed(cause: Throwable) :
        EbookConversionException("Failed to write converted EPUB to cache", cause)
}
