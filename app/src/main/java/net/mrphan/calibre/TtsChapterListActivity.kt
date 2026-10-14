package net.mrphan.calibre

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mrphan.calibre.reader.epub.EpubPublicationLoader
import net.mrphan.calibre.reader.tts.TtsChapterProgressStore
import net.mrphan.calibre.reader.tts.TtsPlaybackService
import org.readium.r2.shared.publication.Link
import java.io.File

/**
 * Danh sách chương của 1 cuốn EPUB để nghe TTS - mở từ nút tai nghe ở BookDetailActivity /
 * DeviceBookDetailActivity (thay cho nút mắt placeholder cũ). Hiện % tiến trình đã nghe cho từng
 * chương (TtsChapterProgressStore), bấm vào 1 chương sẽ mở EpubReaderActivity ở chế độ nghe (xem
 * EpubReaderActivity.EXTRA_OPEN_AUDIO_MODE) bắt đầu từ vị trí đã lưu. KHÔNG tự động đóng Activity
 * này khi mở EpubReaderActivity - nó vẫn nằm trên back stack để nút "quay lại" từ màn hình đang
 * phát quay về đúng đây, thay vì quay về màn hình đọc chữ (yêu cầu 5 của tính năng).
 */
class TtsChapterListActivity : AppCompatActivity() {

    private lateinit var uuid: String
    private lateinit var bookFile: File
    private lateinit var bookTitle: String
    private lateinit var adapter: TtsChapterAdapter
    private var ttsService: TtsPlaybackService? = null
    private var ttsServiceBound = false
    private var ttsStateJob: Job? = null

    private val ttsServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val boundService = (service as? TtsPlaybackService.LocalBinder)?.service ?: return
            ttsService = boundService
            ttsServiceBound = true
            observeTtsState(boundService)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            ttsStateJob?.cancel()
            ttsStateJob = null
            ttsService = null
            ttsServiceBound = false
            adapter.updateActiveChapter(null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tts_chapter_list)

        val uuidExtra = intent.getStringExtra(EXTRA_UUID)
        val path = intent.getStringExtra(EXTRA_BOOK_PATH)
        if (uuidExtra == null || path == null) {
            finish()
            return
        }
        uuid = uuidExtra
        bookFile = File(path)
        bookTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""

        findViewById<TextView>(R.id.tts_chapter_list_title).text = bookTitle
        findViewById<ImageButton>(R.id.tts_chapter_list_back).setOnClickListener { finish() }

        val recyclerView = findViewById<RecyclerView>(R.id.tts_chapter_list_recycler)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = TtsChapterAdapter(emptyList()) { link -> openChapter(link) }
        recyclerView.adapter = adapter

        loadChapters()
    }

    /** Làm mới lại % tiến trình mỗi lần quay lại màn hình này (ví dụ sau khi nghe xong 1 chương
     * rồi bấm back từ full player) - không cần nạp lại toàn bộ Publication, chỉ đọc lại
     * TtsChapterProgressStore và bind lại adapter. */
    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized && adapter.itemCount > 0) refreshProgressOnly()
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, TtsPlaybackService::class.java),
            ttsServiceConnection,
            Context.BIND_AUTO_CREATE,
        )
    }

    override fun onStop() {
        ttsStateJob?.cancel()
        ttsStateJob = null
        if (ttsServiceBound) {
            unbindService(ttsServiceConnection)
            ttsServiceBound = false
        }
        ttsService = null
        super.onStop()
    }

    private fun observeTtsState(service: TtsPlaybackService) {
        ttsStateJob?.cancel()
        ttsStateJob = service.ttsController.state
            .onEach { state ->
                val activeHref = if (service.nowPlayingUuid == uuid && state.sentenceCount > 0) {
                    state.chapterHref
                } else {
                    null
                }
                adapter.updateActiveChapter(activeHref)
            }
            .launchIn(lifecycleScope)
    }

    private fun loadChapters() {
        lifecycleScope.launch {
            val publication = try {
                withContext(Dispatchers.IO) {
                    EpubPublicationLoader.open(applicationContext, bookFile, uuid)
                }
            } catch (e: net.mrphan.calibre.reader.convert.EbookConversionException) {
                Logger.e(e)
                Toast.makeText(this@TtsChapterListActivity, conversionErrorMessageRes(e), Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            if (publication == null) {
                Toast.makeText(this@TtsChapterListActivity, R.string.reader_error_open_failed, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            // TOC có thể rỗng với một số EPUB - fallback về readingOrder (spine), giống cách
            // EpubReaderActivity#openTocPicker đã làm.
            val chapters = publication.tableOfContents.ifEmpty { publication.readingOrder }
            val progressMap = withContext(Dispatchers.IO) {
                TtsChapterProgressStore(applicationContext).getAllProgress(uuid)
            }
            adapter.submit(chapters, progressMap)
        }
    }

    private fun refreshProgressOnly() {
        lifecycleScope.launch {
            val progressMap = withContext(Dispatchers.IO) {
                TtsChapterProgressStore(applicationContext).getAllProgress(uuid)
            }
            adapter.updateProgress(progressMap)
        }
    }

    private fun openChapter(link: Link) {
        startActivity(
            EpubReaderActivity.buildAudioSessionIntent(
                context = this,
                bookFile = bookFile,
                uuid = uuid,
                title = bookTitle,
                chapterHref = link.href.toString(),
            )
        )
    }

    /** Dùng chung logic map exception -> string res với EpubReaderActivity#showConversionErrorAndFinish. */
    private fun conversionErrorMessageRes(e: net.mrphan.calibre.reader.convert.EbookConversionException): Int =
        when (e) {
            is net.mrphan.calibre.reader.convert.EbookConversionException.DrmProtected -> R.string.reader_error_drm_protected
            is net.mrphan.calibre.reader.convert.EbookConversionException.UnsupportedCompression -> R.string.reader_error_unsupported_compression
            is net.mrphan.calibre.reader.convert.EbookConversionException.UnsupportedKf8Structure -> R.string.reader_error_unsupported_kf8
            is net.mrphan.calibre.reader.convert.EbookConversionException.UnrecognizedFormat -> R.string.reader_error_unrecognized_format
            is net.mrphan.calibre.reader.convert.EbookConversionException.CorruptFile -> R.string.reader_error_corrupt_file
            is net.mrphan.calibre.reader.convert.EbookConversionException.OutputWriteFailed -> R.string.reader_error_conversion_write_failed
        }

    companion object {
        const val EXTRA_UUID = "net.mrphan.calibre.tts.EXTRA_UUID"
        const val EXTRA_BOOK_PATH = "net.mrphan.calibre.tts.EXTRA_BOOK_PATH"
        const val EXTRA_TITLE = "net.mrphan.calibre.tts.EXTRA_TITLE"

        // @JvmStatic is required here: this is called as TtsChapterListActivity.buildIntent(...)
        // from 2 Java call sites (DeviceBookDetailActivity#onReadAloudClicked and
        // OpenBookActivity#openBook) -- without it, Kotlin only generates an instance method on
        // the synthesized Companion class, reachable from Java as
        // TtsChapterListActivity.Companion.buildIntent(...), not the shorter form used there.
        @JvmStatic
        fun buildIntent(context: Context, bookFile: File, uuid: String, title: String): Intent =
            Intent(context, TtsChapterListActivity::class.java)
                .putExtra(EXTRA_UUID, uuid)
                .putExtra(EXTRA_BOOK_PATH, bookFile.absolutePath)
                .putExtra(EXTRA_TITLE, title)
    }
}
