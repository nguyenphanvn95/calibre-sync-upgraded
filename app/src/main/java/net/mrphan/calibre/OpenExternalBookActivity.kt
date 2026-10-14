package net.mrphan.calibre

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Điểm vào công khai (exported="true" trong AndroidManifest) để các app khác (trình quản lý
 * file, trình duyệt, share sheet) đưa thẳng một file EPUB/PDF/CBZ vào reader riêng của Calibre
 * Sync – ví dụ chọn "Calibre Sync" ở hộp thoại "Open with" khi bấm một file trong Cx File
 * Explorer / ZArchiver, đúng vị trí Moon+ Reader Pro và Readest đang chiếm.
 *
 * `ReaderActivity`/`EpubReaderActivity`/`PdfReaderActivity`/`CbzReaderActivity` chỉ chấp nhận
 * `java.io.File` (xem kdoc gốc của `ReaderActivity`: các reader này đọc file trực tiếp trong
 * cùng process, cố tình không cần Uri `content://`) – nên một Uri do app khác cấp phải được
 * copy ra một file thật trong cache riêng của app này trước, rồi mới mở.
 *
 * Kế thừa `androidx.activity.ComponentActivity` (không phải `AppCompatActivity`): vẫn có
 * `lifecycleScope` để copy file ở background thread một cách an toàn theo vòng đời, nhưng
 * không bị ép buộc dùng theme dẫn xuất từ `Theme.AppCompat` như `AppCompatActivity` đòi hỏi,
 * nhờ vậy dùng thẳng được theme trong suốt gốc của Android
 * (`@android:style/Theme.Translucent.NoTitleBar`, khai báo trong AndroidManifest) mà không
 * phải tự định nghĩa theme AppCompat tùy chỉnh cho riêng một Activity không có giao diện.
 * `android.app.AlertDialog` (framework, không phải AppCompat) vẫn hiển thị bình thường trên
 * Activity này, giống cách `OpenBookActivity` đã dùng `android.app.ProgressDialog` (cũng
 * framework) trong đúng hoàn cảnh tương tự.
 *
 * NÂNG CẤP (xem prompt-nang-cap-ui-va-import-device-library.md mục 2): sách mở theo đường này
 * mặc định KHÔNG được ghi vào bảng `metadata` của `MetadataDatabaseHelper` (bảng đó vốn dành
 * cho thư viện đồng bộ thật từ Calibre server) nên không resume được vị trí đọc giữa các lần mở.
 * Nay hỏi người dùng 1 lần/file qua dialog "Nhập vào Device Library?" -- nếu đồng ý, copy file
 * vào rootDirectory thật (nơi Device Library quét, xem StorageUtils#getRootDirectory) và ghi
 * metadata bằng đúng cơ chế BookCrawler#findNewBooks đang dùng cho "sách người dùng tự copy vào
 * thư mục sách", để từ đó sách này có uuid thật, lưu được last_locator, hiện trong Device
 * Library như mọi sách khác. Nếu từ chối, hành vi giữ y hệt trước đây (mở từ cache, uuid giả,
 * không resume). Nếu file này đã được nhập ở lần mở trước, không hỏi lại -- mở thẳng bản đã có
 * trong Device Library.
 */
class OpenExternalBookActivity : ComponentActivity() {

    /** Thư mục con trong rootDirectory chứa sách được nhập qua tính năng này -- tách riêng khỏi
     * sách Calibre desktop đồng bộ xuống, để người dùng dễ nhận ra nguồn gốc nếu duyệt bằng
     * trình quản lý file bên ngoài. */
    private val importSubfolder = "Imported"

    private lateinit var metadataDb: MetadataDatabaseHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        metadataDb = MetadataDatabaseHelper(applicationContext)

        val uri: Uri? = intent?.data
        if (uri == null) {
            Toast.makeText(this, R.string.toast_external_book_open_failed, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val displayName = queryDisplayName(uri) ?: uri.lastPathSegment.orEmpty()
        // Tái dùng nguyên hàm đã có, đã xử lý đúng tên file có dấu tiếng Việt – không viết
        // lại logic đoán phần mở rộng.
        val extension = OpenBookActivity.getExtension(displayName)
        val supportedExternalOpenExtensions = setOf("epub", "pdf", "cbz", "mobi", "azw", "azw3", "txt")
        if (extension !in supportedExternalOpenExtensions) {
            Toast.makeText(this, R.string.toast_external_book_unsupported_format, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val cacheFile = try {
                copyToPrivateCache(uri, displayName)
            } catch (e: IOException) {
                Logger.e(e)
                null
            } catch (e: SecurityException) {
                // Quyền đọc Uri (grant) hết hạn hoặc không hợp lệ – không phải lỗi hiếm, xử lý
                // như một lần mở thất bại thông thường thay vì để app crash.
                Logger.e(e)
                null
            }

            if (cacheFile == null) {
                launch(Dispatchers.Main) {
                    Toast.makeText(
                        this@OpenExternalBookActivity,
                        R.string.toast_external_book_open_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                    finish()
                }
                return@launch
            }

            // Đã nhập file cùng tên này vào Device Library ở 1 lần mở trước chưa? Chỉ so khớp
            // theo tên file đích (KHÔNG so nội dung/hash) -- xem lý do trong kdoc của
            // findExistingImport(). Nếu có, mở thẳng bản đã nhập, không hỏi lại, không copy lần
            // nữa.
            val existing = findExistingImport(displayName)
            if (existing != null) {
                launch(Dispatchers.Main) { openReader(existing.first, existing.second, extension) }
                return@launch
            }

            launch(Dispatchers.Main) {
                showImportDialog(
                    displayName = displayName,
                    onImport = {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val imported = try {
                                importIntoDeviceLibrary(cacheFile, displayName)
                            } catch (e: Exception) {
                                Logger.e(e)
                                null
                            }
                            launch(Dispatchers.Main) {
                                if (imported != null) {
                                    Toast.makeText(
                                        this@OpenExternalBookActivity,
                                        getString(R.string.toast_external_book_imported, imported.third),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    openReader(imported.first, imported.second, extension)
                                } else {
                                    Toast.makeText(
                                        this@OpenExternalBookActivity,
                                        R.string.toast_external_book_import_failed,
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    openReaderFromCache(cacheFile, displayName, extension)
                                }
                            }
                        }
                    },
                    onSkip = { openReaderFromCache(cacheFile, displayName, extension) },
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        metadataDb.close()
    }

    /** Dialog "Nhập vào Device Library?" -- Có/Không. Bấm ra ngoài (cancel) được coi như
     * "Không" (onSkip) để KHÔNG chặn việc đọc sách chỉ vì người dùng lỡ tay chạm ra ngoài, thay
     * vì setCancelable(false) ép buộc phải chọn 1 trong 2 nút. */
    private fun showImportDialog(displayName: String, onImport: () -> Unit, onSkip: () -> Unit) {
        val title = displayName.substringBeforeLast('.', displayName)
        AlertDialog.Builder(this)
            .setTitle(R.string.external_book_import_dialog_title)
            .setMessage(getString(R.string.external_book_import_dialog_message, title))
            .setPositiveButton(R.string.external_book_import_dialog_positive) { _, _ -> onImport() }
            .setNegativeButton(R.string.external_book_import_dialog_negative) { _, _ -> onSkip() }
            .setOnCancelListener { onSkip() }
            .show()
    }

    /** @return Triple(uuid, file, title) của sách sau khi nhập thành công vào Device Library,
     * hoặc null nếu nhập thất bại (ví dụ thiếu quyền MANAGE_EXTERNAL_STORAGE trên API 30+).
     *
     * Chạy trên background thread (Dispatchers.IO) -- copy file + query/insert SQLite. */
    private fun importIntoDeviceLibrary(cacheFile: File, displayName: String): Triple<String, File, String>? {
        if (!StorageUtils.hasBroadStorageAccess()) {
            // Cùng lý do OpenBookActivity đã xử lý cho luồng tải từ Content Server: ghi vào
            // rootDirectory trên API 30+ cần "All files access", thiếu quyền này
            // FileOutputStream sẽ ném EPERM. Báo lỗi rõ ràng thay vì để exception chung chung
            // rơi vào catch(Exception) ở nơi gọi.
            return null
        }
        val rootDirectory = StorageUtils.getRootDirectory(
            applicationContext,
            PreferenceManager.getDefaultSharedPreferences(applicationContext),
        )
        val importDir = File(rootDirectory, importSubfolder).apply { mkdirs() }
        val safeName = displayName.replace(Regex("""[\\/:*?"<>|]"""), "_")
        // resolveNonConflictingFile đã có sẵn trong FileNameUtils (dùng cho tải file từ Content
        // Server) -- tái dùng để tự thêm hậu tố "(1)", "(2)"... nếu trùng tên, không viết lại.
        val destFile = FileNameUtils.resolveNonConflictingFile(importDir, safeName)
        cacheFile.copyTo(destFile, overwrite = false)

        // Cùng cơ chế trích tiêu đề/tác giả/bìa mà BookCrawler#findNewBooks dùng cho sách người
        // dùng tự copy vào thư mục sách -- KHÔNG viết lại logic đọc EPUB/PDF/CBZ ở đây.
        val sniffed = BookMetadataSniffer.sniff(destFile)
        val relativePath = destFile.toString().substring(rootDirectory.toString().length + 1)
        var coverPath: String? = null
        if (sniffed.coverBytes != null && CoverManager.saveCover(rootDirectory, relativePath, sniffed.coverBytes)) {
            coverPath = CoverManager.relativeCoverPathFor(relativePath)
        }

        val uuid = UUID.randomUUID().toString()
        val metadataId = metadataDb.insertMetadata(
            uuid,
            relativePath,
            sniffed.author,
            sniffed.title,
            null,
            null,
            coverPath,
        )
        if (metadataId < 0) return null
        return Triple(uuid, destFile, sniffed.title)
    }

    /** Tìm sách đã nhập trước đó có cùng tên file đích (`Imported/<safeName>`), để KHÔNG hỏi lại
     * dialog import mỗi lần mở lại cùng 1 file.
     *
     * Giới hạn đã biết: so khớp CHỈ theo tên file đích gốc (chưa qua
     * resolveNonConflictingFile thêm hậu tố), KHÔNG so nội dung/hash -- nếu người dùng mở 2 file
     * khác nhau nhưng trùng tên gốc, lần nhập thứ 2 vẫn sẽ hỏi lại (resolveNonConflictingFile sẽ
     * tự đặt tên "(1)" cho nó), đây là đánh đổi chấp nhận được để tránh phải đọc/hash toàn bộ
     * nội dung file chỉ để chống trùng tên hiếm gặp.
     *
     * @return Pair(uuid, file) nếu tìm thấy, null nếu chưa từng nhập. */
    private fun findExistingImport(displayName: String): Pair<String, File>? {
        val rootDirectory = StorageUtils.getRootDirectory(
            applicationContext,
            PreferenceManager.getDefaultSharedPreferences(applicationContext),
        )
        val safeName = displayName.replace(Regex("""[\\/:*?"<>|]"""), "_")
        val relativePath = "$importSubfolder/$safeName"
        val cs = metadataDb.getByPath(arrayOf("uuid"), relativePath)
        try {
            if (!cs.moveToFirst()) return null
            val uuid = cs.getString(0) ?: return null
            val file = File(rootDirectory, relativePath)
            if (!file.exists()) return null
            return Pair(uuid, file)
        } finally {
            cs.close()
        }
    }

    private fun openReader(uuid: String, file: File, extension: String) {
        val title = file.nameWithoutExtension
        startActivity(ReaderActivity.buildIntent(this, file, uuid, title, extension))
        finish()
    }

    /** Hành vi cũ (trước bản nâng cấp này): mở từ file cache riêng, uuid giả, không resume vị
     * trí đọc -- dùng khi người dùng chọn "Chỉ đọc, không nhập" hoặc khi import thất bại. */
    private fun openReaderFromCache(cacheFile: File, displayName: String, extension: String) {
        val title = displayName.substringBeforeLast('.', displayName)
        val syntheticUuid = "external:" + cacheFile.absolutePath
        startActivity(ReaderActivity.buildIntent(this, cacheFile, syntheticUuid, title, extension))
        finish()
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) return cursor.getString(nameIndex)
        }
        return null
    }

    /**
     * Copy nội dung [uri] ra `cacheDir/opened_books/<displayName>`, ghi đè bản copy cũ cùng
     * tên nếu có – luôn copy lại từ đầu mỗi lần mở, để một file nguồn đã đổi nội dung ở phía
     * trình quản lý file luôn được cập nhật. Dùng `cacheDir` (không phải thư mục thư viện chính
     * qua `StorageUtils`) vì luôn truy cập được ngay, không phụ thuộc quyền
     * `MANAGE_EXTERNAL_STORAGE`/"All files access" -- bước này CHỈ để có 1 bản copy tạm đọc
     * được ngay; việc nhập thật vào Device Library (nếu người dùng đồng ý) là bước riêng ở
     * importIntoDeviceLibrary(), có kiểm tra quyền riêng.
     */
    private fun copyToPrivateCache(uri: Uri, displayName: String): File {
        val dir = File(cacheDir, "opened_books").apply { mkdirs() }
        val safeName = displayName.replace(Regex("""[\\/:*?"<>|]"""), "_")
        val dest = File(dir, safeName)
        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("openInputStream returned null for $uri")
        input.use { source ->
            dest.outputStream().use { output -> source.copyTo(output) }
        }
        return dest
    }
}
