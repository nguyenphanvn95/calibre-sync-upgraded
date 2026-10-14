package net.mrphan.calibre.reader.convert

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Điểm vào DUY NHẤT mà phần còn lại của app nên gọi để chuyển 1 file MOBI/AZW/AZW3/TXT thành 1
 * file EPUB tạm dùng được với `EpubPublicationLoader`. Có cache theo (đường dẫn + kích thước +
 * thời gian sửa đổi cuối) của file gốc để KHÔNG convert lại mỗi lần mở sách -- convert 1 cuốn
 * MOBI vài MB có thể mất 1-3 giây, làm lại mỗi lần mở sách sẽ rất khó chịu.
 */
object EbookFormatConverter {

    private const val CACHE_SUBDIR = "converted_ebooks"

    val SUPPORTED_EXTENSIONS = setOf("mobi", "azw", "azw3", "txt")

    /** [uuid] dùng làm 1 phần tên file cache để dễ dọn rác theo sách (vd khi xoá sách khỏi Device
     * Library) -- không bắt buộc phải trùng khớp gì với MetadataDatabaseHelper, chỉ là 1 khoá ổn
     * định. Trả về file .epub đã sẵn sàng đưa vào EpubPublicationLoader. */
    fun convert(context: Context, sourceFile: File, uuid: String, extension: String): File {
        if (!sourceFile.exists() || !sourceFile.canRead()) {
            throw EbookConversionException.CorruptFile("source file does not exist or is not readable: ${sourceFile.path}")
        }
        val cacheFile = cacheFileFor(context, sourceFile, uuid)
        if (cacheFile.exists() && cacheFile.lastModified() >= sourceFile.lastModified()) {
            return cacheFile // cache con hop le, khong convert lai
        }

        val bytes = try {
            sourceFile.readBytes()
        } catch (e: OutOfMemoryError) {
            // Sach qua lon de doc het vao RAM 1 lan -- gioi han da biet cua chien luoc "doc het
            // vao ByteArray roi parse" o tren. Khong crash: bao loi cu the thay vi de
            // OutOfMemoryError lan ra ngoai lam chet ca process.
            throw EbookConversionException.CorruptFile("file too large to convert on this device (${sourceFile.length()} bytes)")
        } catch (e: Exception) {
            throw EbookConversionException.CorruptFile("cannot read source file bytes", e)
        }

        val parsed = try {
            when (extension.lowercase()) {
                "mobi", "azw", "azw3" -> MobiParser.parse(bytes)
                "txt" -> TxtParser.parse(bytes, suggestedTitle = sourceFile.nameWithoutExtension)
                else -> throw EbookConversionException.UnrecognizedFormat(
                    "extension '$extension' is not handled by EbookFormatConverter"
                )
            }
        } catch (e: EbookConversionException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw EbookConversionException.CorruptFile("out of memory while parsing ebook")
        } catch (e: Exception) {
            throw EbookConversionException.CorruptFile("unexpected error while parsing ebook", e)
        }

        SyntheticEpubBuilder.build(parsed, cacheFile)

        val markerFile = File(cacheFile.path + ".limited")
        if (parsed.isLimitedCompatibilityMode) {
            markerFile.createNewFile()
        } else if (markerFile.exists()) {
            markerFile.delete()
        }

        return cacheFile
    }

    /** true nếu book này ĐANG hiển thị ở chế độ tương thích giới hạn (KF8 best-effort) -- gọi lại
     * SAU KHI convert() đã chạy ít nhất 1 lần cho file này (đọc lại flag từ 1 file marker cạnh
     * cache, để không phải giữ trạng thái parse trong RAM giữa các lần mở app). */
    fun isLimitedCompatibilityCache(context: Context, sourceFile: File, uuid: String): Boolean =
        File(cacheFileFor(context, sourceFile, uuid).path + ".limited").exists()

    private fun cacheFileFor(context: Context, sourceFile: File, uuid: String): File {
        val dir = File(context.cacheDir, CACHE_SUBDIR)
        val key = sha1("$uuid|${sourceFile.absolutePath}|${sourceFile.length()}")
        return File(dir, "$key.epub")
    }

    private fun sha1(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
