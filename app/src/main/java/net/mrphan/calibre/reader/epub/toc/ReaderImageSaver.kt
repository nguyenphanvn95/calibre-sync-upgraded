package net.mrphan.calibre.reader.epub.toc

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import net.mrphan.calibre.Logger
import net.mrphan.calibre.R
import java.io.File
import java.io.FileOutputStream

/**
 * Lưu bytes ảnh trích từ EPUB vào thư mục Downloads công khai của máy (yêu cầu 2.4 "tải xuống"
 * của prompt-nang-cap-muc-luc-panel-truot.md). Dùng `MediaStore.Downloads` trên API 29+ (Scoped
 * Storage, không cần quyền runtime); fallback ghi thẳng vào
 * `Environment.DIRECTORY_DOWNLOADS` trên API < 29 (đã có `WRITE_EXTERNAL_STORAGE` khai báo sẵn
 * trong `AndroidManifest.xml` với `maxSdkVersion="29"`).
 */
object ReaderImageSaver {
    fun saveToDownloads(context: Context, bytes: ByteArray, suggestedFileName: String) {
        val fileName = sanitizeFileName(suggestedFileName)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeTypeFor(fileName))
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("insert() returned null")
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                FileOutputStream(File(dir, fileName)).use { it.write(bytes) }
            }
            Toast.makeText(context, R.string.reader_toc_image_saved, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Logger.e(e)
            Toast.makeText(context, R.string.reader_toc_image_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun sanitizeFileName(name: String): String =
        name.ifBlank { "image.jpg" }.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun mimeTypeFor(fileName: String): String = when {
        fileName.endsWith(".png", true) -> "image/png"
        fileName.endsWith(".gif", true) -> "image/gif"
        fileName.endsWith(".webp", true) -> "image/webp"
        else -> "image/jpeg"
    }
}
