package net.mrphan.calibre

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * User: sengjea
 * Date: 23/12/13
 * Time: 15:54
 *
 * Routes a book to the in-app reader for the formats it supports (EPUB/MOBI/AZW/AZW3/TXT/PDF/CBZ,
 * via ReaderActivity -- MOBI/AZW/AZW3/TXT are transparently converted to a temporary EPUB by
 * EpubReaderActivity/EpubPublicationLoader before being handed to Readium, see
 * reader/convert/EbookFormatConverter.kt), and falls back to the pre-existing "hand it to another
 * installed app via ACTION_VIEW" behaviour for every other format (FB2/RTF/DOC/HTML/PDB/CBR/...).
 * See README "In-app reader" for the reasoning.
 *
 * Chuyen tu `Activity` (thuan) sang `AppCompatActivity` va tu AsyncTask sang coroutine o lan
 * refactor bo AsyncTask (xem upgrade_1.md muc 3) -- `lifecycleScope` chi co tren
 * AppCompatActivity/ComponentActivity tro len, khong co tren `android.app.Activity` thuan ma
 * class nay dung truoc do. AppTheme (@style/AppTheme, ke thua boi Activity nay qua
 * <application android:theme>) da la Theme.MaterialComponents.DayNight -- mot theme con chau
 * cua Theme.AppCompat -- nen doi base class khong can sua gi them trong Manifest/styles.
 */
class OpenBookActivity : AppCompatActivity() {

    companion object {
        const val OPEN_BOOK = "net.mrphan.calibre.OPEN_BOOK"
        const val EXTRA_UUID = "uuid"
        const val EXTRA_URL = "url"

        /** Optional "Basic <base64>" Authorization header value, set when the download URL points
         * at a Content Server that has Basic Auth enabled (see README "OPDS Basic Auth"). Null/absent
         * for the plain Smart-Device-App-driven download path, which doesn't use this header. */
        const val EXTRA_AUTH_HEADER = "auth_header"

        /** When true, skip opening whatever's already cached for this uuid and always fetch
         * EXTRA_URL instead. Set by BookDetailActivity's "Download" button (but not "Read") so that
         * explicitly picking a format from the chooser dialog actually downloads that format instead
         * of silently reopening a previously-cached file of a different format -- see
         * BookDetailActivity#onDownloadClicked for the full explanation. Defaults to false so every
         * other caller (BookSearchActivity, CollectionFragment, the plain "Read" action) keeps the
         * original open-if-cached behaviour. */
        const val EXTRA_FORCE_DOWNLOAD = "force_download"

        /** When true, once the book is on disk (already cached, or freshly downloaded), open it in
         * "Nghe sách" (listen via TTS) mode -- straight to TtsChapterListActivity -- instead of the
         * normal text reader (ReaderActivity). Set by BookDetailActivity's headphones action button
         * for the Calibre Library/OPDS tab, which may need to download the EPUB first. See the
         * "Nâng cấp TTS chapter" feature notes. */
        const val EXTRA_OPEN_AS_AUDIO = "open_as_audio"

        // Giu dung hanh vi ban Java cu (static, dung chung) -- khong thuoc pham vi cua lan
        // refactor nay (chi bo AsyncTask), xem upgrade_1.md muc 3.
        private var mDB: MetadataDatabaseHelper? = null
        private var mContext: Context? = null
        private var rootDirectory: File? = null
        private var currentUuid: String? = null

        /**
         * Extracts a file extension (without the leading '.', lower-cased) from a plain filename.
         * MimeTypeMap.getFileExtensionFromUrl() is meant for URLs, not local filenames, and its
         * underlying regex only matches ASCII characters -- for a book title containing Vietnamese
         * diacritics (or any other non-ASCII characters) it silently returns "", which meant every
         * such book fell through the switch below into openWithExternalApp() even for epub/pdf/cbz,
         * which do have an in-app reader. Extracting the extension ourselves with plain substring
         * logic works for any Unicode filename.
         */
        @JvmStatic
        fun getExtension(filename: String?): String {
            if (filename == null) return ""
            val dot = filename.lastIndexOf('.')
            if (dot < 0 || dot == filename.length - 1) return ""
            return filename.substring(dot + 1).lowercase(Locale.ROOT)
        }
    }

    private var mProgressDialog: ProgressDialog? = null
    private var downloadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mDB = MetadataDatabaseHelper(applicationContext)
        mContext = applicationContext
        rootDirectory = StorageUtils.getRootDirectory(
            applicationContext,
            PreferenceManager.getDefaultSharedPreferences(applicationContext)
        )
    }

    override fun onResume() {
        super.onResume()
        currentUuid = intent.getStringExtra(EXTRA_UUID)
        val forceDownload = intent.getBooleanExtra(EXTRA_FORCE_DOWNLOAD, false)

        // BUG FIX (release-only "Could not open this book in the reader" / "Lưu thất bại" khi mở
        // MỘT SÁCH ĐÃ TẢI VỀ SẴN): trước đây hasBroadStorageAccess() chỉ được kiểm tra ở nhánh
        // TẢI VỀ bên dưới -- nhánh openBook() mở một file ĐÃ có sẵn trên đĩa (qua java.io.File
        // thẳng vào rootDirectory, xem openBook()) cũng cần đúng permission
        // MANAGE_EXTERNAL_STORAGE ("All files access") trên API 30+ y hệt, vì rootDirectory nằm
        // NGOÀI thư mục riêng của app (kể cả thư mục mặc định "eBooks/" cạnh Android/, xem
        // StorageUtils.getDefaultRootDirectory()) -- không có permission này,
        // ParcelFileDescriptor.open()/FileInputStream bên trong PdfReaderActivity/
        // EpubReaderActivity/CbzReaderActivity văng permission-denied IOException, và các Activity
        // đó chỉ báo được thông báo chung chung "Could not open this book in the reader" (đúng
        // format lỗi này, xem reader_error_open_failed) thay vì lỗi permission thật sự -- rất dễ
        // nhầm là bug đọc file/parse PDF. Rất dễ gặp ngay sau khi cài lại app: bản debug và bản
        // release ký bằng 2 keystore khác nhau nên không thể "update tại chỗ" lên nhau -- chuyển
        // qua lại giữa 2 bản bắt buộc phải gỡ cài đặt trước, và Android LUÔN thu hồi
        // MANAGE_EXTERNAL_STORAGE mỗi lần gỡ cài đặt (không giữ lại như khi chỉ update). Trước
        // fix này, chỉ ai bấm "Tải về" một sách MỚI mới thấy được lời nhắc cấp quyền; sách đã có
        // sẵn từ trước lần gỡ cài đặt lại rơi thẳng vào lỗi chung chung khó hiểu bên trên. Kiểm
        // tra ngay từ đầu onResume(), trước cả openBook(), để cả 2 nhánh (mở sách có sẵn / tải
        // sách mới) đều được bảo vệ như nhau.
        if (currentUuid != null && !StorageUtils.hasBroadStorageAccess()) {
            // Settings khong con la Activity rieng (SettingsActivity) sau khi chuyen sang
            // SettingsSheet (BottomSheetDialogFragment) -- va OpenBookActivity la android.app.Activity
            // thuong, khong co FragmentManager de host bottom sheet. Di thang toi man hinh he
            // thong "All files access" (dung noi SettingsSheet/Book Folder cuoi cung cung se
            // dan nguoi dung toi) thay vi vong qua man hinh Settings cua app.
            Toast.makeText(mContext, R.string.toast_need_all_files_access, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            finish()
            return
        }

        // openBook() has the side effect of launching the reader/external app when it returns
        // true, so it must not be called at all when forcing a download -- calling it and
        // discarding a "true" result isn't an option, it would've already opened the stale file.
        val openedCached = currentUuid != null && !forceDownload && openBook()
        if (currentUuid != null && !openedCached && intent.hasExtra(EXTRA_URL)) {
            // Storage access was already confirmed above -- nothing left to check before
            // downloading into rootDirectory.
            mProgressDialog = ProgressDialog(this)
            startDownload(
                intent.getStringExtra(EXTRA_URL)!!,
                rootDirectory.toString(),
                intent.getStringExtra(EXTRA_AUTH_HEADER)
            )
        } else if (currentUuid != null && !openedCached) {
            // Không mở được sách cục bộ (lpath null/không tồn tại) VÀ cũng không có EXTRA_URL để
            // tải về -- trước đây rơi vào đây thì không làm gì cả, và vì onCreate() không hề gọi
            // setContentView(), Activity đứng yên với màn hình đen vô thời hạn thay vì báo lỗi
            // hay tự đóng. Ví dụ thực tế: tìm kiếm ra một cuốn sách mà metadata đã được cache lúc
            // duyệt OPDS nhưng chưa từng tải về máy.
            Toast.makeText(mContext, R.string.toast_book_not_available, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun startDownload(url: String, outputDir: String, authHeader: String?) {
        mProgressDialog?.apply {
            setMessage(mContext?.getString(R.string.dialog_downloading_book))
            isIndeterminate = true
            max = 100
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            progress = 0
            setCancelable(false)
            show()
        }
        downloadJob = lifecycleScope.launch {
            try {
                val result = AsyncDownloader.download(url, outputDir, authHeader) { percent ->
                    mProgressDialog?.apply {
                        isIndeterminate = false
                        progress = percent
                    }
                }
                onDownloadFinished(result)
            } catch (e: CancellationException) {
                // Tuong duong AsyncTask.onCancelled() cu: bao cho nguoi dung biet, dung mContext
                // (applicationContext) nen Toast hien thi duoc du Activity dang trong qua trinh
                // huy/finish() -- xem onStop() ben duoi.
                Toast.makeText(mContext, R.string.toast_download_cancelled, Toast.LENGTH_LONG).show()
                throw e
            }
        }
    }

    private fun onDownloadFinished(result: AsyncDownloader.Result) {
        val lpath = (result as? AsyncDownloader.Result.Success)?.outputPath
        val db = mDB
        if (db != null && lpath != null && currentUuid != null && db.insertBook(currentUuid, lpath)) {
            openBook()
        } else if (lpath == null) {
            // Download failed (bad/expired URL, network error, HTTP error, wrong
            // credentials...) -- AsyncDownloader.download() returns Result.Failure in that case
            // and there's no on-disk file to clean up, so just report it.
            Toast.makeText(mContext, R.string.toast_download_cancelled, Toast.LENGTH_LONG).show()
        } else if (File(rootDirectory, lpath).delete()) {
            Toast.makeText(mContext, R.string.toast_download_cancelled, Toast.LENGTH_LONG).show()
        }
        mProgressDialog?.dismiss()
        finish()
    }

    override fun onStop() {
        super.onStop()
        // Job.cancel() tren mot job da xong la no-op an toan -- khong can tu kiem tra
        // "dang chay hay khong" nhu ban AsyncTask cu (`downloadTask.getStatus() == RUNNING`).
        downloadJob?.cancel()
        mDB?.close()
    }

    private fun openBook(): Boolean {
        // currentUuid null: getByUuid(..., null) below won't match any row (same as before this
        // refactor), so cs.moveToFirst() would be false and this returns false anyway -- bailing
        // out here directly just avoids passing a nullable uuid into TtsChapterListActivity/
        // ReaderActivity.buildIntent(), which declare it non-null.
        val uuid = currentUuid ?: return false
        val cs = mDB!!.getByUuid(arrayOf("lpath", "title"), uuid)
        try {
            if (!cs.moveToFirst() || cs.getString(0) == null) return false
            val bookFile = File(rootDirectory, cs.getString(0))
            val title = cs.getString(1)
            val ext = getExtension(bookFile.name)
            try {
                val openAsAudio = intent.getBooleanExtra(EXTRA_OPEN_AS_AUDIO, false)
                if (openAsAudio && ext == "epub") {
                    // "Nghe sách" (listen via TTS) -- only supported for EPUB (plain text), see
                    // EXTRA_OPEN_AS_AUDIO's Javadoc. Goes straight to the chapter list rather
                    // than ReaderActivity's normal text reader.
                    startActivity(TtsChapterListActivity.buildIntent(this, bookFile, uuid, title))
                } else {
                    when (ext) {
                        "epub", "mobi", "azw", "azw3", "txt", "pdf", "cbz" -> {
                            val coverFile = File(rootDirectory, CoverManager.relativeCoverPathFor(cs.getString(0)))
                            startActivity(ReaderActivity.buildIntent(this, bookFile, uuid, title, ext, coverFile))
                        }
                        else -> openWithExternalApp(bookFile)
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(applicationContext, R.string.toast_no_app_installed, Toast.LENGTH_LONG).show()
                return false
            }
            // The book genuinely opened at this point (startActivity()/openWithExternalApp()
            // above didn't throw). The periodic "rate this app" prompt is purely best-effort and
            // must never affect that outcome: it used to run *inside* the try above with
            // getApplicationContext(), and Dialog.show() requires an actual Activity window --
            // once the launch-count/day thresholds were met, every single book open threw
            // BadTokenException here, which the catch above mistook for an open failure (showing
            // the misleading "No compatible app installed" toast even though the reader had
            // already launched) and, worse, skipped finish() below -- leaving this activity stuck
            // in the back stack so the reader's close button had nothing valid to return to.
            try {
                AppRater.app_launched(this)
            } catch (e: Exception) {
                // Ignore: rating-prompt failures must never block finishing this activity.
            }
            finish()
            return true
        } finally {
            cs.close()
        }
    }

    /** Pre-existing behaviour for formats the in-app reader doesn't handle (MOBI, AZW3, FB2,
     * RTF, DOC, HTML, TXT, PDB, CBR, ...): hand it off to whatever app the user has installed
     * that can open it. See README "In-app reader" for why these formats are out of scope. */
    private fun openWithExternalApp(bookFile: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", bookFile)
        val mimeExtension = getExtension(bookFile.name)
        val openBookIntent = Intent(Intent.ACTION_VIEW)
        openBookIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        openBookIntent.setDataAndType(uri, MimeTypeMap.getSingleton().getMimeTypeFromExtension(mimeExtension))
        startActivity(openBookIntent)
    }
}
