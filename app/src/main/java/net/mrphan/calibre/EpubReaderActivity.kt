@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package net.mrphan.calibre

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Base64
import androidx.activity.addCallback
import androidx.core.content.ContextCompat
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mrphan.calibre.reader.chrome.ReaderChromeHost
import net.mrphan.calibre.reader.chrome.ReaderChromeOverlay
import net.mrphan.calibre.reader.chrome.ReaderColorSwatch
import net.mrphan.calibre.reader.chrome.ReaderColorTheme
import net.mrphan.calibre.reader.chrome.ReaderFontChoice
import net.mrphan.calibre.reader.chrome.ReaderKind
import net.mrphan.calibre.reader.chrome.ReaderThemeMode
import net.mrphan.calibre.reader.chrome.onItemSelected
import net.mrphan.calibre.reader.chrome.onProgressChanged
import net.mrphan.calibre.reader.chrome.settings.BehaviorSettings
import net.mrphan.calibre.reader.chrome.settings.ColorSettings
import net.mrphan.calibre.reader.chrome.settings.FontSettings
import net.mrphan.calibre.reader.chrome.settings.LayoutSettings
import net.mrphan.calibre.reader.chrome.settings.ReaderSettingsProfile
import net.mrphan.calibre.reader.chrome.settings.ReaderSettingsProfilesSheet
import net.mrphan.calibre.reader.chrome.settings.ReaderSettingsSheet
import net.mrphan.calibre.reader.chrome.settings.TtsSettings
import net.mrphan.calibre.reader.epub.EpubReadingStatsSource
import net.mrphan.calibre.reader.epub.EpubSearchEngine
import net.mrphan.calibre.reader.epub.EpubSearchResult
import net.mrphan.calibre.reader.epub.EpubTextExtractor
import net.mrphan.calibre.reader.epub.ReaderAnnotation
import net.mrphan.calibre.reader.epub.ReaderHighlightColor
import net.mrphan.calibre.reader.epub.ReaderHighlightStyle
import net.mrphan.calibre.reader.epub.ReaderHighlightsSheet
import net.mrphan.calibre.reader.epub.ReaderSelectionActionHost
import net.mrphan.calibre.reader.epub.ReaderSelectionSnapshot
import net.mrphan.calibre.reader.epub.ReaderSelectionToolbar
import net.mrphan.calibre.reader.epub.TtsSentenceHighlighter
import net.mrphan.calibre.reader.epub.toc.EpubImageEntry
import net.mrphan.calibre.reader.epub.toc.ReaderImageTapHost
import net.mrphan.calibre.reader.epub.toc.ReaderImageTapWatcher
import net.mrphan.calibre.reader.epub.toc.ReaderImageToolDialog
import net.mrphan.calibre.reader.epub.toc.ReaderTocPanel
import net.mrphan.calibre.reader.epub.toc.ReaderTocTab
import net.mrphan.calibre.reader.epub.decodeColorStyle
import net.mrphan.calibre.reader.epub.decodeLocator
import net.mrphan.calibre.reader.epub.encodeColorStyle
import net.mrphan.calibre.reader.epub.encodeLocator
import net.mrphan.calibre.reader.lookup.LookupBottomSheetFragment
import net.mrphan.calibre.reader.music.ReaderBackgroundMusicController
import net.mrphan.calibre.reader.music.ReaderBackgroundMusicMiniPlayer
import net.mrphan.calibre.reader.music.ReaderBackgroundMusicPanel
import net.mrphan.calibre.reader.music.ReaderBackgroundMusicStore
import net.mrphan.calibre.reader.stats.DailyReadingStatsStore
import net.mrphan.calibre.reader.stats.ReadingStatsDialog
import net.mrphan.calibre.reader.stats.ReadingTimeTracker
import net.mrphan.calibre.reader.tts.TtsController
import net.mrphan.calibre.reader.tts.TtsEngineRegistry
import net.mrphan.calibre.reader.tts.TtsMiniPlayerView
import net.mrphan.calibre.reader.tts.TtsPlaybackService
import net.mrphan.calibre.reader.tts.TtsPlayerBottomSheet
import net.mrphan.calibre.reader.tts.TtsSessionCoordinator
import net.mrphan.calibre.reader.tts.TtsSettingsStore
import net.mrphan.calibre.reader.tts.music.BackgroundMusicSettingsDialog
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.cover
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import java.io.File

/**
 * In-app EPUB reader built on Readium's kotlin-toolkit (readium-shared / readium-streamer /
 * readium-navigator, 3.1.2 -- see app/build.gradle.kts; an earlier version of this comment said
 * 3.3.0, which was never actually the pinned version), following the documented "no local HTTP
 * server needed" flow for 2.x+/3.x: AssetRetriever -> PublicationOpener -> EpubNavigatorFactory ->
 * fragment factory -> EpubNavigatorFragment hosted in a FragmentContainerView.
 *
 * IMPORTANT CAVEAT: this sandbox has no Android SDK/emulator, so nothing here could actually be
 * compiled or run. Everything that touches Readium's API in this file and in the reader.epub/
 * reader.chrome packages (EpubPreferences/FontFamily/Theme construction, SelectableNavigator/
 * DecorableNavigator/Decoration usage, EpubNavigatorFragment.Configuration, createFragmentFactory)
 * was written after cloning github.com/readium/kotlin-toolkit at tag 3.1.2 (the exact version this
 * project pins) and reading the real source for each API used -- not from memory or the newer
 * 3.3.0 docs. Still: build this module (`./gradlew :app:compileDebugKotlin`) before shipping and
 * treat any compile error as a signal to re-check the corresponding file in that checkout, since
 * no amount of source-reading here substitutes for an actual compiler run.
 *
 * Reading-progress persistence: every navigator locator change is serialized to JSON and saved
 * via MetadataDatabaseHelper#saveLastLocator(uuid, json) (DATABASE_VERSION 5), and restored as
 * the EpubNavigatorFragment's initial locator on next open -- this is what lets Calibre-side
 * "reading position" sync (see README "Reading progress sync") read/write the same value.
 *
 * Reader chrome: the floating toolbar overlay (net.mrphan.calibre.reader.chrome package) is
 * shared with PdfReaderActivity/CbzReaderActivity. This Activity's only job re: chrome is (a)
 * implement ReaderChromeHost so the shared actions have something to call, and (b) forward touch
 * events from its content view into ReaderChromeOverlay#dispatchContentTouch() so the
 * middle-of-screen tap gesture works -- see dispatchTouchEvent() below for why that's done at the
 * Activity level rather than via a plain View.setOnTouchListener on the FragmentContainerView.
 */
class EpubReaderActivity : AppCompatActivity(), ReaderChromeHost, ReaderSelectionActionHost, ReaderImageTapHost {

    private lateinit var mDB: MetadataDatabaseHelper
    private lateinit var uuid: String

    /** Cho TtsPlayerBottomSheet (yeu cau 2 cua prompt-codex-nangcap-now-playing.md) so sanh xem
     * dialog "Now Playing" dang mo co dung dang la Activity cua CHINH cuon sach dang duoc nghe
     * hay khong -- neu dung, bam vao "cau dang doc" chi can trigger to sang tren Activity co san
     * (xem [focusCurrentTtsSentence]) thay vi mo them 1 Activity moi. */
    internal val currentBookUuid: String get() = uuid
    private var navigatorFragment: EpubNavigatorFragment? = null
    private var publication: Publication? = null
    private var lastLocator: Locator? = null
    /** Giu tham chieu manh toi WebView dang duoc PrintManager render/ghi PDF -- xem kdoc cua
     * ChapterPdfPrinter ve ly do can giu field nay thay vi de bien cuc bo bi GC giua chung. */
    private var chapterPrintWebView: android.webkit.WebView? = null
    // Visibility widened from `private` to `internal` (not touched otherwise) so the
    // ReaderSettingsSheet pages in reader.chrome.settings -- same module, so `internal` is enough,
    // no need to route everything through the ReaderChromeHost interface -- can read/write these
    // directly instead of duplicating the state. See ReaderSettingsPage's kdoc for why a direct
    // `host as EpubReaderActivity` cast is safe here (the sheet is only ever opened from EPUB).
    internal var currentFontSizeMultiplier: Double = 1.0
    internal var currentLineHeight: Double = 1.2
    internal var currentFontFamily: FontFamily? = null
    internal var currentCustomFontId: String? = null
    private var scrollModeEnabled = false
    private var pageTurnAnimation: net.mrphan.calibre.reader.epub.PageTurnAnimation =
        net.mrphan.calibre.reader.epub.PageTurnAnimation.NONE

    /** "Cong trang" that (Play Books/Moon Reader style) - gan/go boi [syncCurlOverlay] moi khi
     * [applyPageTurnAnimation] chay. Null khi pageTurnAnimation != CURL hoac dang o scroll mode;
     * xem net.mrphan.calibre.reader.epub.curl.PageCurlOverlayView cho phan ve/gesture that. */
    private var curlOverlayView: net.mrphan.calibre.reader.epub.curl.PageCurlOverlayView? = null

    /** Cac ViewPager da duoc dang ky page-change-listener de tu resetImmediately() overlay khi
     * trang doi (xem syncCurlOverlay) - dung de tranh dang ky TRUNG listener neu nguoi dung bat/
     * tat CURL nhieu lan lien tiep ma pager (view) van la CUNG 1 instance (fragment chua bi tao
     * lai giua cac lan do). WeakHashMap de khong giu tham chieu song pager sau khi fragment/
     * Activity bi huy. */
    private val pagersWithCurlResetListener =
        java.util.WeakHashMap<androidx.viewpager.widget.ViewPager, Boolean>()

    // --- Reader Settings sheet: Layout tab state (prompt-nang-cap-reader-settings-tabbed.md muc
    // 5.2) -- each field mirrors a real org.readium.r2.navigator.epub.EpubPreferences property
    // (verified against the Readium 3.1.2 docs, see ReaderSettingsLayoutPage's kdoc for the exact
    // list), never a made-up one. `publisherStylesOverrideEnabled` is the master switch: Readium
    // requires `publisherStyles = false` before hyphens/letterSpacing/paragraphSpacing/
    // paragraphIndent/textAlign/wordSpacing take effect at all, so those five are only actually
    // submitted to the navigator while this is on (see buildCurrentEpubPreferences()).
    internal var publisherStylesOverrideEnabled: Boolean = false
    internal var currentFontWeight: Double = 400.0
    internal var currentWordSpacing: Double = 0.0
    internal var currentLetterSpacing: Double = 0.0
    internal var currentParagraphSpacing: Double = 0.0
    internal var currentParagraphIndent: Double = 0.0
    internal var currentPageMargins: Double = 1.0
    internal var twoColumnEnabled: Boolean = false
    internal var justifyTextEnabled: Boolean = false
    internal var hyphensEnabled: Boolean = false

    // --- Reader Settings sheet: Behavior tab state (prompt section 5.3) -- controls that are
    // both real and don't need any new Readium/EpubPreferences field, just a window flag / the
    // Activity's requestedOrientation.
    internal var keepScreenOnEnabled: Boolean = false
        private set

    /** Cho hang "Xoay man hinh" trong ReaderSettingsBehaviorPage, xem
     * prompt-nang-cap-cai-dat-xoay-man-hinh-reader.md. Cung pham vi voi keepScreenOnEnabled:
     * chi ap dung cho EpubReaderActivity dang mo, KHONG dua vao ReaderSettingsProfile (theo dung
     * tien le cua pageTurnAnimation, xem TODO trong ReaderSettingsProfile.kt). */
    internal var currentScreenOrientationMode: net.mrphan.calibre.reader.epub.ScreenOrientationMode =
        net.mrphan.calibre.reader.epub.ScreenOrientationMode.SYSTEM
        private set

    /** Cho tab "Thao tac" (ReaderSettingsBehaviorPage), xem
     * prompt-nang-cap-phim-am-luong-vung-cham-lat-trang.md. Cung pham vi voi keepScreenOnEnabled/
     * currentScreenOrientationMode: chi luu qua SharedPreferences truc tiep, khong qua
     * ReaderSettingsProfile/BehaviorSettings. */
    internal var volumeKeyPageTurnEnabled: Boolean = false
        private set
    internal var volumeKeyPageTurnInverted: Boolean = false
        private set
    internal var edgeTapPageTurnEnabled: Boolean = false
        private set
    internal var edgeTapPageTurnInverted: Boolean = false
        private set
    private var autoScrollJob: Job? = null
    private var autoScrollSpeedLevel: Int = AUTO_SCROLL_SPEED_MEDIUM
    private var currentThemeMode: ReaderThemeMode = ReaderThemeMode.LIGHT
    private var currentColorThemeId: String? = null
    private var currentScreenBrightness: Int = -1
    private var currentBackgroundImageOpacity: Int = DEFAULT_READER_BACKGROUND_IMAGE_OPACITY
    private var fullscreenOn = false

    /** Duong dan file anh bia doc tu EXTRA_COVER_PATH NGAY LUC onCreate() (truoc khi co reload
     * nao xoa extra do) -- dung cho ca hieu ung MO (playOpenIfEnabled) lan hieu ung DONG
     * (playCloseIfEnabled trong close()), thay vi doc lai intent.getStringExtra() luc dong vi
     * reloadBook() ben duoi da chu dong xoa extra nay truoc khi restart Activity. Xem kdoc
     * BookOpenCloseDoorEffect va reloadBook(). */
    private var openBookCoverPath: String? = null
    private lateinit var chromeOverlay: ReaderChromeOverlay
    private lateinit var tocPanel: ReaderTocPanel
    private lateinit var readerBackgroundMusicPanel: ReaderBackgroundMusicPanel
    private lateinit var readerBackgroundMusicController: ReaderBackgroundMusicController
    private var readerBackgroundMusicMiniPlayer: ReaderBackgroundMusicMiniPlayer? = null
    private lateinit var contentRoot: View
    private var readerBackgroundImageView: ImageView? = null
    private lateinit var readingTimeTracker: ReadingTimeTracker
    private lateinit var settingsPrefs: SharedPreferences
    private var textExtractor: EpubTextExtractor? = null
    private var searchEngine: EpubSearchEngine? = null
    private var statsSource: EpubReadingStatsSource? = null
    private var ttsMiniPlayer: TtsMiniPlayerView? = null
    internal lateinit var ttsSettingsStore: TtsSettingsStore
    private val readerBackgroundDataUriCache = mutableMapOf<String, String>()

    // --- TTS background playback: bind to TtsPlaybackService instead of owning TtsController
    // directly. This is what lets "Đọc cho nghe" keep playing after this Activity is destroyed --
    // the Service lives on its own, independent of any Activity lifecycle. See
    // TtsPlaybackService's kdoc for the full rationale.
    private var ttsService: TtsPlaybackService? = null
    private var ttsServiceBound = false
    private val ttsController: TtsController? get() = ttsService?.ttsController

    // --- To sang + tu dong cuon/lat trang theo cau TTS dang doc ngay trong noi dung sach, cong
    // voi bind mini player -- xem TtsSessionCoordinator (tach ra tu Activity nay, xem prompt
    // nang cap reader/tts muc 5) va TtsSentenceHighlighter.
    private val ttsSessionCoordinator: TtsSessionCoordinator by lazy {
        TtsSessionCoordinator(
            lifecycleOwner = this,
            getTtsSettingsStore = { ttsSettingsStore },
            miniPlayerBinder = { state -> ttsMiniPlayer?.bind(state) },
            getNavigatorFragment = { navigatorFragment },
            getPublication = { publication },
            // BUG FIX (crash_2026-08-02_07-40-26.txt): day la lambda auto-follow duoc goi TU DONG
            // moi khi TTS doi cau (khong phai do nguoi dung bam), nen no de dang chay dung luc
            // R2EpubPageFragment (trang con ben trong ViewPager cua EpubNavigatorFragment) dang
            // trong qua trinh huy/tao lai View (vd. nguoi dung vua vuot trang, xoay man hinh, hay
            // dong reader) -- Readium goi getViewLifecycleOwner() ben trong luc do va throw
            // IllegalStateException vi getView() == null. TAT CA cac cho khac trong file nay goi
            // navigatorFragment?.go(...) deu da boc try-catch (xem jumpTo(), onJumpToChapterLink,
            // loadNextChapterForTts(), ket qua Tim kiem, v.v.) -- rieng cho nay bi bo sot vi no
            // duoc viet truc tiep trong constructor cua TtsSessionCoordinator. An toan de bo qua
            // 1 lan auto-follow bi loi: cau/trang tiep theo se tu dieu chinh lai dung vi tri, va
            // nguoi dung khong mat du lieu gi (khac voi decoration/note that su).
            autoFollow = { locator ->
                try {
                    navigatorFragment?.go(locator, animated = true)
                } catch (e: IllegalStateException) {
                    Logger.e(e)
                }
            },
            // BUG FIX: "sau khi tat doc to, boi den van ban khong hien menu Danh dau/Ghi chu nua"
            // -- xem ReaderSelectionToolbar.reinstallWatcher() kdoc. Goi ngay khi phien TTS vua
            // ket thuc (bat ke dung tu dau: mini player, full player, hay thong bao), truoc khi
            // nguoi dung kip boi den van ban lan tiep theo.
            onSessionEnded = { navigatorFragment?.let { selectionToolbar.reinstallWatcher(it) } },
        )
    }

    // --- "Nghe sách" (Nâng cấp TTS chapter): audio session mode -- opened from
    // TtsChapterListActivity via buildAudioSessionIntent(), starts playback immediately at the
    // chosen chapter and shows the full "Now playing" player right away instead of the normal
    // toggleReadAloud() flow. See startAudioSessionIfNeeded(). ---
    private var audioSessionMode = false
    private var audioSessionChapterHref: String? = null

    // --- Yeu cau 2 cua prompt-codex-nangcap-now-playing.md: nguoi dung bam vao "cau dang doc"
    // trong TtsPlayerBottomSheet tu MOT Activity/man hinh khac (vd Device Library), can mo lai
    // Activity nay roi ep to sang + cuon toi dung cau TTS hien tai NGAY khi du du lieu, xem
    // focusCurrentTtsSentence(). Field nay chi la co "co lam hay khong lam" doc 1 lan trong
    // onCreate() -- ban than viec cho du lieu (ttsService/publication/navigatorFragment/state)
    // san sang nam trong focusCurrentTtsSentence(), khong can theo doi tiep o day. ---
    private var focusCurrentTtsSentenceOnOpen = false

    /** For [net.mrphan.calibre.reader.chrome.settings.ReaderSettingsTtsPage]: null when no read-
     * aloud session is active yet (see [toggleReadAloud]) -- the page falls back to reading/writing
     * [ttsSettingsStore] directly in that case rather than requiring a session just to change the
     * default voice/speed. */
    internal fun activeTtsController(): TtsController? = ttsController

    /** For the same page's "Nhạc nền" row -- [net.mrphan.calibre.reader.tts.music.BackgroundMusicSettingsDialog]
     * needs a live [TtsPlaybackService.musicController], which only exists once a session has
     * bound the Service (see [ttsServiceConnection]). */
    internal fun activeTtsService(): TtsPlaybackService? = ttsService

    /** Cached EPUB cover art for the full player -- fetched once via Readium's
     * `Publication.cover()` after `publication` is assigned, since re-decoding it on every full
     * player open would be wasteful. Null means "no cover found in this EPUB"; the full player
     * falls back to `ic_reader_cover_placeholder` in that case rather than leaving the ImageView
     * blank. */
    var fullPlayerCoverBitmap: android.graphics.Bitmap? = null
        private set

    private val ttsServiceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName, binder: IBinder) {
            val service = (binder as TtsPlaybackService.LocalBinder).service
            ttsService = service
            service.nextChapterProvider = { loadNextChapterForTts() }
            service.nowPlayingBookTitle = bookTitle
            service.nowPlayingUuid = uuid
            service.nowPlayingBookPath = intent.getStringExtra(ReaderActivity.EXTRA_BOOK_PATH)
            service.nowPlayingCover = fullPlayerCoverBitmap
            // Dong bo ngay trang thai foreground luc vua bind xong -- bindService() la bat dong
            // bo (co the ket noi SAU onResume() da chay), nen neu khong lam buoc nay o day, dong
            // ho tinh gio nghe cua Service (xem TtsPlaybackService.readerActivityForeground) co
            // the chay nham trong luc Activity van dang o foreground, dem trung voi
            // readingTimeTracker cua chinh Activity nay.
            service.readerActivityForeground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            attachMiniPlayerAndFullPlayer(service)
        }

        override fun onServiceDisconnected(name: android.content.ComponentName) {
            ttsService = null
        }
    }

    // Held as a field (not built inline where it's used, like before) because it now needs to be
    // reached from two other places besides Configuration: registerJavascriptInterface's factory
    // lambda (jsBridge) and the currentLocator.onEach loop (onLocatorChanged) -- see
    // ReaderSelectionToolbar's kdoc for why both of those exist now.
    private val selectionToolbar by lazy { ReaderSelectionToolbar(this, this) }

    // Khong the dung `by lazy` nhu selectionToolbar vi ReaderImageTapWatcher can `openedPublication`,
    // von chi la 1 local val ben trong setUpNavigatorFragmentFactory's lifecycleScope.launch (sau khi
    // mo sach xong) -- khoi tao ngay tai do (cung cho nav.currentLocator.onEach lay).
    private lateinit var imageTapWatcher: ReaderImageTapWatcher

    override val bookTitle: String by lazy {
        intent.getStringExtra(ReaderActivity.EXTRA_TITLE).orEmpty()
    }
    override val readerKind: ReaderKind = ReaderKind.EPUB

    override fun onCreate(savedInstanceState: Bundle?) {
        uuid = intent.getStringExtra(ReaderActivity.EXTRA_UUID)!!
        mDB = MetadataDatabaseHelper(applicationContext)
        readingTimeTracker = ReadingTimeTracker(
            db = mDB,
            uuid = uuid,
            dailyStats = DailyReadingStatsStore(mDB),
            // EPUB StatUnit.weight IS the chapter's word count (xem EpubReadingStatsSource kdoc),
            // nen "tu/ms" o toc do doc mac dinh chinh la 1/DEFAULT_MS_PER_WORD -- khong can doi
            // nguoc lai wpm do luong thuc te (statsSource chua san sang tai thoi diem nay, EPUB
            // con dang mo bat dong bo), du de "Toc do doc" tren man hinh Tien trinh co so lieu
            // thay vi luon bang 0.
            wordsPerMsProvider = { 1.0 / EpubReadingStatsSource.DEFAULT_MS_PER_WORD },
        )
        pullProgressOnOpen()
        settingsPrefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        restoreDisplaySettings()
        restoreLayoutSettings()
        keepScreenOnEnabled = settingsPrefs.getBoolean(PREF_READER_KEEP_SCREEN_ON, false)
        if (keepScreenOnEnabled) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        scrollModeEnabled = settingsPrefs.getBoolean(PREF_READER_SCROLL_MODE, false)
        volumeKeyPageTurnEnabled = settingsPrefs.getBoolean(PREF_READER_VOLUME_KEY_PAGE_TURN, false)
        volumeKeyPageTurnInverted = settingsPrefs.getBoolean(PREF_READER_VOLUME_KEY_INVERT, false)
        edgeTapPageTurnEnabled = settingsPrefs.getBoolean(PREF_READER_EDGE_TAP_PAGE_TURN, false)
        edgeTapPageTurnInverted = settingsPrefs.getBoolean(PREF_READER_EDGE_TAP_INVERT, false)
        pageTurnAnimation = net.mrphan.calibre.reader.epub.PageTurnAnimation.fromPrefName(
            settingsPrefs.getString(PREF_READER_PAGE_TURN_ANIMATION, null),
        )
        // Immersive mode deliberately DOES persist across sessions (prompt section 6.1), unlike
        // auto scroll (prompt checklist item 3) which never should.
        fullscreenOn = settingsPrefs.getBoolean(PREF_READER_IMMERSIVE_MODE, false)
        currentThemeMode = ReaderThemeMode.entries.find {
            it.name == settingsPrefs.getString(PREF_READER_THEME_MODE, null)
        } ?: ReaderThemeMode.LIGHT
        currentColorThemeId = settingsPrefs.getString(PREF_READER_COLOR_THEME, null)
        currentScreenBrightness = settingsPrefs.getInt(PREF_READER_SCREEN_BRIGHTNESS, -1)
        currentBackgroundImageOpacity = settingsPrefs.getInt(
            PREF_READER_BACKGROUND_IMAGE_OPACITY,
            DEFAULT_READER_BACKGROUND_IMAGE_OPACITY,
        ).coerceIn(0, 100)
        val bookPath = intent.getStringExtra(ReaderActivity.EXTRA_BOOK_PATH)!!
        audioSessionMode = intent.getBooleanExtra(EXTRA_OPEN_AUDIO_MODE, false)
        audioSessionChapterHref = intent.getStringExtra(EXTRA_AUDIO_CHAPTER_HREF)
        focusCurrentTtsSentenceOnOpen = intent.getBooleanExtra(EXTRA_FOCUS_CURRENT_TTS_SENTENCE, false)

        // NOTE: setting up the navigator's fragment factory requires opening and parsing the
        // EPUB file, which is asynchronous (IO-bound). It CANNOT actually be ready before
        // super.onCreate() returns despite the old comment here claiming otherwise. Adding the
        // EpubNavigatorFragment before the custom factory is assigned made FragmentManager fall
        // back to its default (reflection-based, no-arg constructor) factory, which cannot
        // instantiate EpubNavigatorFragment and crashed the app on every book open. The fragment
        // is now only added from setUpNavigatorFragmentFactory(), after the real factory has
        // been assigned.
        //
        // BUG FIX (crash on ANY activity relaunch -- rotation, split-screen resize, "don't keep
        // activities", process-death restore -- see Fragment$InstantiationException /
        // NoSuchMethodException for EpubNavigatorFragment.<init> in crash reports): this used to
        // be `savedInstanceState == null`, only true on a genuine first launch. On every relaunch
        // savedInstanceState is non-null, so this evaluated to false -- but FragmentManager still
        // tries to auto-restore the previously added EpubNavigatorFragment from that
        // savedInstanceState DURING super.onCreate() below, using its own reflection-based
        // default FragmentFactory, since our real factory can't be installed that early (needs
        // the async-parsed EPUB, see NOTE above). Readium's EpubNavigatorFragment has no public
        // no-arg constructor, so that reflection restore always threw and crashed the app on
        // every rotation/relaunch, not just first launch -- exactly the "known follow-up" called
        // out in reloadBook()'s kdoc further down.
        //
        // Fixed by stripping FragmentManager's own saved-state entry ("android:support:fragments"
        // -- the key FragmentActivity/FragmentManager has used since the original support-library
        // days, still used by the current AndroidX Fragment library) from savedInstanceState
        // right here, before handing it to super.onCreate(). This only affects what gets fed into
        // a genuine RE-CREATION of the Activity -- it does nothing while this Activity instance
        // is merely backgrounded/foregrounded without being destroyed, so the live reading screen
        // is never touched. With that entry gone, FragmentManager has nothing left to attempt to
        // auto-restore, so setUpNavigatorFragmentFactory() below always re-adds a fresh
        // EpubNavigatorFragment itself once the real factory is ready, exactly like a first
        // launch. Safe here because this Activity never adds any OTHER fragment to
        // supportFragmentManager (only ever this one, see EPUB_NAVIGATOR_FRAGMENT_TAG below) --
        // nothing else is relying on that saved-state entry.
        savedInstanceState?.remove("android:support:fragments")
        val shouldAddFragment = true

        super.onCreate(savedInstanceState)
        configureReaderWindowForEdgeToEdge()

        // requestedOrientation phai duoc gan SAU khi super.onCreate() da chay xong (khac voi
        // keepScreenOnEnabled ben tren, thuc ra nam TRUOC super.onCreate() trong file nay -- xem
        // prompt-nang-cap-cai-dat-xoay-man-hinh-reader.md muc 6: goi truoc do se nem
        // IllegalStateException vi Activity chua gan Window).
        currentScreenOrientationMode = net.mrphan.calibre.reader.epub.ScreenOrientationMode.fromPrefName(
            settingsPrefs.getString(PREF_READER_SCREEN_ORIENTATION, null),
        )
        requestedOrientation = currentScreenOrientationMode.toActivityInfoOrientation()

        setContentView(R.layout.activity_reader_epub)
        disableReaderContentInsets()

        // Hieu ung "cua doi" khi mo sach -- chi phat khi Intent nay den tu OpenBookActivity#
        // openBook() (co EXTRA_COVER_PATH); reloadBook() ben duoi da chu dong xoa extra nay truoc
        // khi restart nen doi setting doc se KHONG lam phat lai hieu ung. Day chi la 1 lop phu
        // doc lap tren cung nen khong can cho fragment/publication load xong.
        //
        // Doc va luu vao field openBookCoverPath NGAY O DAY (truoc khi reloadBook() co co hoi
        // xoa extra) de close() ben duoi van co anh bia dung cho hieu ung dong, ke ca khi user da
        // reload sach (doi font/theme doc...) truoc khi dong.
        openBookCoverPath = intent.getStringExtra(ReaderActivity.EXTRA_COVER_PATH)
        net.mrphan.calibre.reader.BookOpenCloseDoorEffect.playOpenIfEnabled(
            this,
            openBookCoverPath?.let { java.io.File(it) },
        )

        contentRoot = findViewById(R.id.epub_navigator_container)
        readerBackgroundImageView = findViewById(R.id.epub_reader_background_image)
        disableReaderContentInsets()
        applyNativeReaderBackground()
        chromeOverlay = ReaderChromeOverlay(window.decorView, this)
        tocPanel = ReaderTocPanel(
            root = findViewById(R.id.reader_toc_panel_root),
            context = this,
            scope = lifecycleScope,
            mDB = mDB,
            uuid = uuid,
            publicationProvider = { publication },
            currentLocatorProvider = { lastLocator },
            onJumpTo = { locator -> jumpTo(locator) },
            onJumpToChapterLink = { link ->
                lifecycleScope.launch {
                    try {
                        navigatorFragment?.go(link, animated = true)
                    } catch (e: Exception) {
                        Logger.e(e)
                    }
                }
            },
            onBookmarksChanged = { chromeOverlay.refreshActions() },
        )
        readerBackgroundMusicController = ReaderBackgroundMusicController(this)
        readerBackgroundMusicMiniPlayer = ReaderBackgroundMusicMiniPlayer(
            root = window.decorView,
            controller = readerBackgroundMusicController,
            lifecycleOwner = this,
            onOpenLibrary = {
                if (::readerBackgroundMusicPanel.isInitialized) {
                    readerBackgroundMusicPanel.show()
                }
            },
        )
        readerBackgroundMusicPanel = ReaderBackgroundMusicPanel(
            root = findViewById(R.id.reader_music_panel_root),
            context = this,
            scope = lifecycleScope,
            store = ReaderBackgroundMusicStore(this),
            controller = readerBackgroundMusicController,
        )
        applyImmersiveModeToWindow(fullscreenOn)

        if (audioSessionMode) {
            // Chặn giao diện đọc chữ hiện ra thoáng qua trước khi TtsPlayerBottomSheet kịp mở --
            // setUpNavigatorFragmentFactory vẫn add EpubNavigatorFragment như bình thường (cần
            // thiết để navigatorFragment?.go(...) hoạt động), nhưng người dùng không cần thấy
            // trang sách trong lúc chờ. Không cần khôi phục lại visibility: khi audio-mode kết
            // thúc, cả Activity finish() luôn.
            contentRoot.visibility = View.INVISIBLE
        }

        setUpNavigatorFragmentFactory(File(bookPath), shouldAddFragment)

        supportFragmentManager.setFragmentResultListener(
            EPUB_NAVIGATOR_FRAGMENT_TAG, this
        ) { _, _ -> }

        // Yêu cầu 5 của tính năng "Nghe sách": back trong lúc audio-mode phải quay về
        // TtsChapterListActivity (finish() luôn Activity này), KHÔNG lộ ra giao diện đọc chữ bên
        // dưới. Ngoài audio-mode, hành vi back giữ nguyên như mặc định của hệ thống.
        onBackPressedDispatcher.addCallback(this) {
            if (::readerBackgroundMusicPanel.isInitialized && readerBackgroundMusicPanel.isShown()) {
                readerBackgroundMusicPanel.hide()
            } else if (::tocPanel.isInitialized && tocPanel.isShown()) {
                tocPanel.hide()
            } else if (audioSessionMode) {
                finish()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        if (focusCurrentTtsSentenceOnOpen) {
            focusCurrentTtsSentenceOnOpen = false
            focusCurrentTtsSentence()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::readingTimeTracker.isInitialized) readingTimeTracker.onResume()
        // Nhuong lai viec tinh gio "nghe" cho Service trong khi Activity o nen -- xem
        // TtsPlaybackService.readerActivityForeground kdoc.
        ttsService?.readerActivityForeground = true
        startAutoSyncLoopIfEnabled()
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, TtsPlaybackService::class.java),
            ttsServiceConnection,
            Context.BIND_AUTO_CREATE,
        )
        ttsServiceBound = true
    }

    override fun onStop() {
        super.onStop()
        if (ttsServiceBound) {
            unbindService(ttsServiceConnection)
            ttsServiceBound = false
        }
        // Cố ý KHÔNG gọi ttsService?.stopReadAloud() ở đây -- đây chính là hành vi "chỉ dừng khi
        // bấm X trên mini player hoặc nút dừng trên notification" mà bản nâng cấp v3.0 yêu cầu.
        ttsSessionCoordinator.unbind()
    }

    override fun onPause() {
        super.onPause()
        if (::readingTimeTracker.isInitialized) readingTimeTracker.onPause()
        // Neu TTS van dang phat sau khi Activity nay khuat nen, day la luc dong ho tinh gio
        // "nghe" cua TtsPlaybackService can tiep quan (xem kdoc cua no) -- phai dat co NAY truoc,
        // vi readingTimeTracker.onPause() o tren da chot xong doan thoi gian "nhin man hinh".
        ttsService?.readerActivityForeground = false
        // KHONG huy hoan toan neu TtsPlaybackService dang tiep quan auto-sync cho dung cuon sach
        // nay (xem startAutoSyncLoopIfEnabled) -- goi stop() o day van an toan trong ca 2 truong
        // hop vi loop se duoc [start] lai dung cach o onResume() ke tiep.
        autoSyncLoop.stop()
    }

    /** See ReaderChromeOverlay#dispatchContentTouch() kdoc for why this is done here (Activity
     * dispatch) rather than a touch listener on the content view -- the event is always forwarded
     * to super afterwards unmodified, so the EpubNavigatorFragment's own WebView touch handling
     * (scrolling, link taps, text selection, ...) is completely unaffected by this. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::chromeOverlay.isInitialized && ::contentRoot.isInitialized) {
            chromeOverlay.dispatchContentTouch(ev, contentRoot.width)
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Lat trang bang phim am luong khi nguoi dung da bat trong tab "Thao tac". Tra ve true (tieu
     * thu su kien, khong cho he thong tang/giam am luong) CHI KHI thuc su xu ly -- neu dang co
     * phien "Doc cho nghe" (TTS) dang phat, co tinh KHONG can thiep (goi super) de nguoi dung van
     * chinh duoc am luong phat audio bang phim vat ly nhu binh thuong; day la ly do kiem tra
     * isReadAloudActive() truoc khi tieu thu su kien. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (volumeKeyPageTurnEnabled && !isReadAloudActive() &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            // Mac dinh: phim am luong DUOI (VOLUME_DOWN) = lat toi trang sau, phim TREN
            // (VOLUME_UP) = lui ve trang truoc -- dao lai khi volumeKeyPageTurnInverted = true.
            val turnsForward = if (volumeKeyPageTurnInverted) {
                keyCode == KeyEvent.KEYCODE_VOLUME_UP
            } else {
                keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
            }
            if (turnsForward) goForward() else goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun setUpNavigatorFragmentFactory(bookFile: File, addFragment: Boolean) {
        val needsConversion = net.mrphan.calibre.reader.convert.EbookFormatConverter.SUPPORTED_EXTENSIONS
            .contains(OpenBookActivity.getExtension(bookFile.name))
        val progressView = findViewById<android.widget.ProgressBar>(R.id.epub_reader_converting_progress)
        if (needsConversion) progressView?.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val openedPublication = try {
                    net.mrphan.calibre.reader.epub.EpubPublicationLoader.open(applicationContext, bookFile, uuid)
                } catch (e: net.mrphan.calibre.reader.convert.EbookConversionException) {
                    Logger.e(e)
                    progressView?.visibility = View.GONE
                    findViewById<android.widget.ProgressBar>(R.id.epub_reader_loading_progress)
                        ?.visibility = View.GONE
                    showConversionErrorAndFinish(e)
                    return@launch
                } ?: run {
                    progressView?.visibility = View.GONE
                    findViewById<android.widget.ProgressBar>(R.id.epub_reader_loading_progress)
                        ?.visibility = View.GONE
                    showOpenErrorAndFinish()
                    return@launch
                }
                publication = openedPublication
                progressView?.visibility = View.GONE

                if (needsConversion &&
                    net.mrphan.calibre.reader.convert.EbookFormatConverter.isLimitedCompatibilityCache(
                        applicationContext, bookFile, uuid,
                    )
                ) {
                    Toast.makeText(
                        this@EpubReaderActivity,
                        R.string.reader_limited_compatibility_notice,
                        Toast.LENGTH_LONG,
                    ).show()
                }

                val extractor = EpubTextExtractor(openedPublication)
                textExtractor = extractor
                searchEngine = EpubSearchEngine(openedPublication, extractor)
                statsSource = EpubReadingStatsSource(
                    publication = openedPublication,
                    unitLabel = getString(R.string.reader_stats_unit_chapter),
                    tracker = readingTimeTracker,
                    extractor = extractor,
                    currentLocator = { lastLocator },
                )

                ttsSettingsStore = TtsSettingsStore(applicationContext)
                startAudioSessionIfNeeded()

                // Bìa sách cho full player -- lấy 1 lần từ Readium, cache lại thay vì decode lại
                // mỗi khi mở full player. Nếu EPUB không có ảnh bìa hợp lệ, cover ở null và full
                // player tự fallback về ic_reader_cover_placeholder.
                lifecycleScope.launch {
                    val cover = try {
                        openedPublication.cover()
                    } catch (e: Exception) {
                        Logger.e(e)
                        null
                    }
                    fullPlayerCoverBitmap = cover
                    ttsService?.nowPlayingCover = cover
                }

                val initialLocator = restoreLastLocator()
                lastLocator = initialLocator

                imageTapWatcher = ReaderImageTapWatcher(this@EpubReaderActivity, openedPublication)

                val navigatorFactory = EpubNavigatorFactory(publication = openedPublication)
                val fragmentFactory = navigatorFactory.createFragmentFactory(
                    initialLocator = initialLocator,
                    // Applying the restored font/line-height/family/scroll/theme settings here,
                    // instead of waiting for the user to reopen the display-settings dialog, is
                    // what actually fixes the "cỡ chữ mất khi mở lại sách" bug (prompt section
                    // 4.1/4.3) at the earliest possible point -- the very first frame rendered
                    // already reflects them, not just the *next* preferences update.
                    initialPreferences = buildCurrentEpubPreferences(),
                    listener = null,
                    configuration = EpubNavigatorFragment.Configuration(
                        // ActionMode fallback only -- see ReaderSelectionToolbar's kdoc. The
                        // Readest-style pill (primary path) is driven separately below, through
                        // registerJavascriptInterface + onLocatorChanged, once `nav` exists.
                        selectionActionModeCallback = selectionToolbar.buildActionModeCallback(),
                    ).apply {
                        registerJavascriptInterface(ReaderSelectionToolbar.JS_INTERFACE_NAME) { _ ->
                            selectionToolbar.jsBridge
                        }
                        registerJavascriptInterface(ReaderImageTapWatcher.JS_INTERFACE_NAME) { _ ->
                            imageTapWatcher.jsBridge
                        }
                    },
                )
                supportFragmentManager.fragmentFactory = fragmentFactory

                // Only now (factory registered) is it safe to add the fragment. On process
                // recreation (savedInstanceState != null) FragmentManager already restored --
                // or attempted to restore -- the fragment during super.onCreate(), before this
                // factory existed; that rotation edge case isn't fully solved here and is a
                // known follow-up (would need e.g. deferring restoration entirely). For the
                // normal "tap a book to open it" flow (savedInstanceState == null), this fixes
                // the crash.
                if (addFragment) {
                    supportFragmentManager.commitNow {
                        add(
                            R.id.epub_navigator_container,
                            EpubNavigatorFragment::class.java,
                            Bundle(),
                            EPUB_NAVIGATOR_FRAGMENT_TAG
                        )
                    }
                }

                supportFragmentManager
                    .findFragmentByTag(EPUB_NAVIGATOR_FRAGMENT_TAG)
                    ?.let { frag ->
                        (frag as? EpubNavigatorFragment)?.let { nav ->
                            navigatorFragment = nav
                            nav.currentLocator
                                .onEach { locator ->
                                    lastLocator = locator
                                    saveLastLocator(locator)
                                    if (::tocPanel.isInitialized) {
                                        tocPanel.refreshCurrentChapterLabel(currentChapterTitle())
                                    }
                                    // Fragment-stripped so this always matches the bare
                                    // readingOrder href used as StatUnit.key in
                                    // EpubReadingStatsSource -- a locator mid-chapter can carry
                                    // a "#id" fragment that the reading-order Link itself never
                                    // has, and actual-time lookups are by exact key match.
                                    val bareHref = locator.href.toString().substringBefore('#')
                                    readingTimeTracker.onUnitChanged(bareHref)
                                    // Re-installs ReaderSelectionToolbar's watcher script into the
                                    // now-visible resource's WebView -- each resource is its own
                                    // WebView instance in Readium, so this has to happen per
                                    // resource, not once for the whole book. onLocatorChanged
                                    // itself no-ops unless bareHref actually changed.
                                    selectionToolbar.onLocatorChanged(nav, bareHref)
                                    imageTapWatcher.onLocatorChanged(nav, bareHref)
                                    reapplyReaderBackgroundSoon(nav)
                                }
                                .launchIn(lifecycleScope)
                            reapplyReaderBackgroundSoon(nav)
                            loadAndApplyHighlights(nav)
                            applyPageTurnAnimation()
                        }
                    }
                // Sach da mo xong va navigatorFragment da gan -- an man hinh loading mac dinh
                // (xem view_reader_loading_progress trong activity_reader_epub.xml), du
                // addFragment o tren la true (mo sach lan dau) hay false (fragment da duoc
                // FragmentManager tu restore tu savedInstanceState).
                findViewById<android.widget.ProgressBar>(R.id.epub_reader_loading_progress)
                    ?.visibility = View.GONE
            } catch (e: Exception) {
                Logger.e(e)
                findViewById<android.widget.ProgressBar>(R.id.epub_reader_loading_progress)
                    ?.visibility = View.GONE
                showOpenErrorAndFinish()
            }
        }
    }

    private fun showOpenErrorAndFinish() {
        Toast.makeText(this, R.string.reader_error_open_failed, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun showConversionErrorAndFinish(e: net.mrphan.calibre.reader.convert.EbookConversionException) {
        val messageRes = when (e) {
            is net.mrphan.calibre.reader.convert.EbookConversionException.DrmProtected ->
                R.string.reader_error_drm_protected
            is net.mrphan.calibre.reader.convert.EbookConversionException.UnsupportedCompression ->
                R.string.reader_error_unsupported_compression
            is net.mrphan.calibre.reader.convert.EbookConversionException.UnsupportedKf8Structure ->
                R.string.reader_error_unsupported_kf8
            is net.mrphan.calibre.reader.convert.EbookConversionException.UnrecognizedFormat ->
                R.string.reader_error_unrecognized_format
            is net.mrphan.calibre.reader.convert.EbookConversionException.CorruptFile ->
                R.string.reader_error_corrupt_file
            is net.mrphan.calibre.reader.convert.EbookConversionException.OutputWriteFailed ->
                R.string.reader_error_conversion_write_failed
        }
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
        finish()
    }

    /** Loads the next chapter's text for [TtsController] to auto-advance into, and moves the
     * navigator there so the on-screen page stays in sync with what's being read aloud. Uses the
     * same `publication.readingOrder` + `textExtractor` pattern as `EpubSearchEngine` (see the
     * spec's reference to that pattern). Returns null when there's no next chapter (end of book)
     * or when required state isn't ready. */
    private suspend fun loadNextChapterForTts(): TtsController.ChapterText? {
        val pub = publication ?: return null
        val extractor = textExtractor ?: return null
        val currentHref = lastLocator?.href?.toString()?.substringBefore('#')
        val readingOrder = pub.readingOrder
        val currentIndex = readingOrder.indexOfFirst { it.href.toString() == currentHref }
        val nextLink = readingOrder.getOrNull(currentIndex + 1) ?: return null
        try {
            navigatorFragment?.go(nextLink, animated = false)
        } catch (e: Exception) {
            Logger.e(e)
        }
        val text = try {
            extractor.extractText(nextLink)
        } catch (e: Exception) {
            Logger.e(e)
            return null
        }
        return TtsController.ChapterText(
            href = nextLink.href.toString(),
            title = nextLink.title ?: bookTitle,
            text = text,
        )
    }

    private fun toggleTtsPlayPause() {
        val controller = ttsController ?: return
        if (controller.state.value.isPlaying) controller.pause() else controller.resume()
    }

    private fun stopReadAloud() {
        ttsService?.stopReadAloud()
        ttsMiniPlayer?.hide()
        clearTtsSentenceHighlightNow()
    }

    /** Called once per bind, right after [TtsPlaybackService] connects, to (re)create the mini
     * player against the now-available [TtsController] and start collecting state for it. Safe
     * to call again on every reconnect (e.g. after process death) -- always tears down any
     * previous mini player/collector first. */
    private fun attachMiniPlayerAndFullPlayer(service: TtsPlaybackService) {
        ttsMiniPlayer = TtsMiniPlayerView(
            includedRoot = window.decorView,
            listener = object : TtsMiniPlayerView.Listener {
                override fun onTogglePlayPause() = toggleTtsPlayPause()
                override fun onClose() = stopReadAloud()
                override fun onExpand() {
                    TtsPlayerBottomSheet.show(this@EpubReaderActivity, service)
                }
            },
        )
        ttsMiniPlayer?.attach(service.ttsController)
        if (service.ttsController.state.value.sentenceCount > 0) {
            ttsMiniPlayer?.show()
        }
        ttsSessionCoordinator.bind(service.ttsController)
    }

    /** @return the saved Readium Locator to resume from, or null to start from the beginning. */
    private fun restoreLastLocator(): Locator? {
        val json = mDB.getLastLocator(uuid) ?: return null
        return try {
            val obj = JSONObject(json)
            if (obj.optString("type") == "epub_locator") {
                Locator.fromJSON(org.json.JSONObject(obj.getString("locator")))
            } else null
        } catch (e: Exception) {
            Logger.e(e)
            null
        }
    }

    private fun saveLastLocator(locator: Locator) {
        try {
            val wrapper = JSONObject()
                .put("type", "epub_locator")
                .put("locator", locator.toJSON())
            mDB.saveLastLocator(uuid, wrapper.toString())
            hasUnsyncedProgress = true
        } catch (e: Exception) {
            Logger.e(e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        readerBackgroundMusicMiniPlayer?.release()
        if (::readerBackgroundMusicController.isInitialized) readerBackgroundMusicController.release()
        if (::readingTimeTracker.isInitialized) readingTimeTracker.flush()
        mDB.close()
        clearChapterPrintWebView()
    }

    // --- ReaderChromeHost --------------------------------------------------------------------

    override fun currentProgressPercent(): Int {
        val progression = lastLocator?.locations?.totalProgression ?: return 0
        return (progression * 100).toInt().coerceIn(0, 100)
    }

    override fun close() {
        // Hieu ung dong sach chay xong roi moi finish() -- xem kdoc BookOpenCloseDoorEffect va
        // field openBookCoverPath o tren. Neu setting tat/khong co anh bia, playCloseIfEnabled()
        // goi finish() ngay lap tuc (dong bo), khong lam cham viec dong sach.
        net.mrphan.calibre.reader.BookOpenCloseDoorEffect.playCloseIfEnabled(
            this,
            openBookCoverPath?.let { java.io.File(it) },
        ) { finish() }
    }

    override fun toggleChrome() {
        // Only the gesture/close-button paths inside ReaderChromeOverlay itself call this; kept
        // here only to satisfy the interface plus give a single obvious place to hook analytics
        // later if wanted.
        chromeOverlay.toggle()
    }

    override fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun formatString(resId: Int, vararg formatArgs: Any): String =
        getString(resId, *formatArgs)

    override fun fragmentManager() = supportFragmentManager

    override fun goBack(): Boolean {
        val overlay = curlOverlayView
        if (overlay != null && overlay.host?.isCurlEnabled() == true) {
            overlay.animateAutomatic(forward = false)
            return true
        }
        return try {
            navigatorFragment?.goBackward(animated = true) ?: false
        } catch (e: Exception) {
            Logger.e(e)
            false
        }
    }

    override fun goForward(): Boolean {
        val overlay = curlOverlayView
        if (overlay != null && overlay.host?.isCurlEnabled() == true) {
            overlay.animateAutomatic(forward = true)
            return true
        }
        return try {
            navigatorFragment?.goForward(animated = true) ?: false
        } catch (e: Exception) {
            Logger.e(e)
            false
        }
    }

    override fun openSearch(): Boolean = openSearchDialog("")

    /** Shared by [openSearch] (empty query, per the header search icon) and
     * [openSearchInBook] (pre-filled with the current text selection, per the selection
     * toolbar's Search item) -- same dialog either way, just optionally pre-filled and
     * auto-run. */
    private fun openSearchDialog(prefillQuery: String): Boolean {
        val engine = searchEngine ?: return false
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_reader_search, null, false)
        val queryField = view.findViewById<EditText>(R.id.reader_search_query)
        val statusText = view.findViewById<TextView>(R.id.reader_search_status)
        val resultsList = view.findViewById<android.widget.ListView>(R.id.reader_search_results)
        if (prefillQuery.isNotBlank()) {
            queryField.setText(prefillQuery)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.reader_search_dialog_title)
            .setView(view)
            .setNegativeButton(R.string.reader_chrome_close, null)
            .create()

        var results: List<EpubSearchResult> = emptyList()
        val adapter = object : ArrayAdapter<EpubSearchResult>(this, R.layout.item_reader_search_result, R.id.reader_search_result_text, mutableListOf()) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val row = super.getView(position, convertView, parent)
                val result = getItem(position)
                val textView = row.findViewById<TextView>(R.id.reader_search_result_text)
                if (result != null) {
                    val builder = SpannableStringBuilder()
                    builder.append(result.snippetBefore)
                    val boldStart = builder.length
                    builder.append(result.snippetMatch)
                    builder.setSpan(StyleSpan(android.graphics.Typeface.BOLD), boldStart, builder.length, 0)
                    builder.append(result.snippetAfter)
                    textView.text = builder
                }
                return row
            }
        }
        resultsList.adapter = adapter

        fun runSearch() {
            val query = queryField.text?.toString().orEmpty()
            if (query.isBlank()) return
            statusText.text = getString(R.string.reader_search_searching)
            lifecycleScope.launch {
                results = try {
                    engine.search(query)
                } catch (e: Exception) {
                    Logger.e(e)
                    emptyList()
                }
                adapter.clear()
                adapter.addAll(results)
                statusText.text = if (results.isEmpty()) {
                    getString(R.string.reader_search_no_results)
                } else {
                    getString(R.string.reader_search_result_count, results.size)
                }
            }
        }

        view.findViewById<android.widget.Button>(R.id.reader_search_go).setOnClickListener { runSearch() }
        queryField.setOnEditorActionListener { _, _, _ -> runSearch(); true }
        resultsList.setOnItemClickListener { _, _, position, _ ->
            val result = results.getOrNull(position) ?: return@setOnItemClickListener
            lifecycleScope.launch {
                try {
                    navigatorFragment?.go(result.locator, animated = true)
                    dialog.dismiss()
                } catch (e: Exception) {
                    Logger.e(e)
                    showMessage(getString(R.string.reader_error_open_failed))
                }
            }
        }

        dialog.show()
        if (prefillQuery.isNotBlank()) {
            runSearch()
        }
        return true
    }

    // TODO: no "jump to page/percent" dialog yet; would need a small EditText+seek UI plus
    // Locator construction from a target progression. Left as a follow-up, same reasoning as
    // openSearch() above.
    override fun openGoToLocation(): Boolean = false

    override fun openTableOfContents(): Boolean {
        val toc = publication?.tableOfContents ?: return false
        if (toc.isEmpty()) return false
        tocPanel.refreshCurrentChapterLabel(currentChapterTitle())
        tocPanel.show(ReaderTocTab.CHAPTERS)
        return true
    }

    override fun printCurrentChapterAsPdf(): Boolean {
        val pub = publication ?: return false
        val currentHref = lastLocator?.href?.toString()?.substringBefore('#') ?: return false
        val link = pub.readingOrder.firstOrNull { it.href.toString() == currentHref } ?: return false

        showMessage(getString(R.string.reader_print_preparing))
        lifecycleScope.launch {
            try {
                clearChapterPrintWebView()
                val html = net.mrphan.calibre.reader.epub.ChapterHtmlBuilder(pub).buildSelfContainedHtml(link)
                val jobName = buildChapterPrintJobName(link)
                net.mrphan.calibre.reader.print.ChapterPdfPrinter.print(
                    context = this@EpubReaderActivity,
                    html = html,
                    jobName = jobName,
                    onWebViewCreated = { webView -> chapterPrintWebView = webView },
                )
            } catch (e: Exception) {
                Logger.e(e)
                showMessage(getString(R.string.reader_print_failed))
            }
        }
        return true
    }

    private fun clearChapterPrintWebView() {
        chapterPrintWebView?.let { webView ->
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        chapterPrintWebView = null
    }

    private fun buildChapterPrintJobName(link: org.readium.r2.shared.publication.Link): String {
        val tocTitle = publication?.tableOfContents?.let { findTocTitleForHref(it, link.href.toString()) }
        val chapterLabel = tocTitle ?: link.title ?: getString(R.string.reader_print_untitled_chapter)
        return "$bookTitle - $chapterLabel".take(120)
    }

    private fun findTocTitleForHref(
        links: List<org.readium.r2.shared.publication.Link>,
        href: String,
    ): String? {
        for (l in links) {
            if (l.href.toString().substringBefore('#') == href) return l.title
            val childMatch = findTocTitleForHref(l.children, href)
            if (childMatch != null) return childMatch
        }
        return null
    }

    // TODO: "reference mode" removed - see ColorContrastAction/openColorAndContrastSettings below,
    // which replaces this footer slot entirely (prompt-nang-cap-mau-nen-tien-do-doc.md muc 3.1).

    override fun toggleHighlightsBrowser(): Boolean {
        val nav = navigatorFragment ?: return false
        ReaderHighlightsSheet(
            mDB = mDB,
            uuid = uuid,
            onJumpTo = { locator -> jumpTo(locator) },
            onHighlightsChanged = { loadAndApplyHighlights(nav) },
        ).show(supportFragmentManager, ReaderHighlightsSheet.TAG)
        return true
    }

    override fun toggleBookmarksPanel(): Boolean {
        if (publication == null) return false
        tocPanel.refreshCurrentChapterLabel(currentChapterTitle())
        tocPanel.show(ReaderTocTab.BOOKMARKS)
        return true
    }

    override fun isCurrentLocationBookmarked(): Boolean {
        val locator = lastLocator ?: return false
        return mDB.findBookmarkId(uuid, encodeLocator(locator)) >= 0
    }

    override fun toggleBookmarkAtCurrentLocation(): Boolean {
        val locator = lastLocator ?: return false
        val locatorJson = encodeLocator(locator)
        val existingId = mDB.findBookmarkId(uuid, locatorJson)
        if (existingId >= 0) {
            mDB.removeAnnotation(existingId)
        } else {
            mDB.addAnnotation(uuid, "bookmark", locatorJson, null, null, null)
        }
        return true
    }

    private fun jumpTo(locator: Locator) {
        lifecycleScope.launch {
            try {
                navigatorFragment?.go(locator, animated = true)
            } catch (e: Exception) {
                Logger.e(e)
            }
        }
    }

    override fun openReadingStats(): Boolean {
        val source = statsSource ?: return false
        ReadingStatsDialog.show(this, lifecycleScope, source)
        return true
    }

    override fun openReaderBackgroundMusic(): Boolean {
        val service = ttsService
        val ttsState = service?.ttsController?.state?.value
        if (service != null && ttsState != null && ttsState.sentenceCount > 0) {
            if (::readerBackgroundMusicPanel.isInitialized && readerBackgroundMusicPanel.isShown()) {
                readerBackgroundMusicPanel.hide()
            }
            stopReaderBackgroundMusicForTts()
            BackgroundMusicSettingsDialog.show(this, service.musicController)
            return true
        }
        if (!::readerBackgroundMusicPanel.isInitialized) return false
        readerBackgroundMusicPanel.show()
        return true
    }

    override fun openFontSizeControls(): Boolean {
        if (navigatorFragment == null) return false
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_reader_display_settings, null, false)
        val fontSizeBar = view.findViewById<SeekBar>(R.id.display_settings_font_size)
        val lineHeightBar = view.findViewById<SeekBar>(R.id.display_settings_line_height)
        val fontFamilySpinner = view.findViewById<Spinner>(R.id.display_settings_font_family)

        fontSizeBar.progress = fontSizeToProgress(currentFontSizeMultiplier)
        lineHeightBar.progress = lineHeightToProgress(currentLineHeight)
        val familyNames = FONT_FAMILY_CHOICES.map { it.displayName(this) }
        fontFamilySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, familyNames)
        fontFamilySpinner.setSelection(
            FONT_FAMILY_CHOICES.indexOfFirst { it.id == currentFontChoiceId() }.coerceAtLeast(0)
        )

        fontSizeBar.setOnSeekBarChangeListener(onProgressChanged { progress ->
            currentFontSizeMultiplier = progressToFontSize(progress)
            applyAllEpubPreferences()
        })
        lineHeightBar.setOnSeekBarChangeListener(onProgressChanged { progress ->
            currentLineHeight = progressToLineHeight(progress)
            applyAllEpubPreferences()
        })
        fontFamilySpinner.onItemSelectedListener = onItemSelected { position ->
            setCurrentFontChoice(FONT_FAMILY_CHOICES.getOrNull(position) ?: FONT_FAMILY_CHOICES.first())
            applyAllEpubPreferences()
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.reader_action_font_size)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ -> persistDisplaySettings() }
            .setOnDismissListener { persistDisplaySettings() }
            .show()
        return true
    }

    /** [FontFamily.name] is the raw CSS generic-family keyword ("serif", "sans-serif", ...) --
     * not something to show a Vietnamese-speaking user directly. Maps each of the fixed
     * [FONT_FAMILY_CHOICES] entries to its localized label instead. Visibility widened to
     * `internal` (not otherwise changed) so [ReaderSettingsFontPage] can build its own font-family
     * Spinner without duplicating this `when`. */
    internal fun familyDisplayName(choice: ReaderFontChoice): String = choice.displayName(this)

    internal fun currentFontChoiceId(): String =
        currentCustomFontId ?: currentFontFamily?.name ?: ReaderFontChoice.DEFAULT_ID

    internal fun setCurrentFontChoice(choice: ReaderFontChoice) {
        currentCustomFontId = choice.id.takeIf { choice.isCustom }
        currentFontFamily = choice.readiumFamily
    }

    /**
     * Opens [ReaderSettingsSheet] -- the unified, tabbed "Cài đặt đọc" panel (prompt-nang-cap-
     * reader-settings-tabbed.md) reached from the ☰ menu's "Hiển thị" group. Previously this just
     * `return false`d ("không hỗ trợ") since no such panel existed; the individual footer buttons
     * (`FontSizeAction`/`ColorContrastAction` -> [openFontSizeControls]/
     * [openColorAndContrastSettings]) are untouched and keep working exactly as before.
     */
    override fun openPreferences(): Boolean {
        ReaderSettingsSheet(host = this, readerKind = readerKind)
            .show(supportFragmentManager, ReaderSettingsSheet.TAG)
        return true
    }

    override fun openReaderSettingsProfiles(): Boolean {
        ReaderSettingsProfilesSheet(activity = this)
            .show(supportFragmentManager, ReaderSettingsProfilesSheet.TAG)
        return true
    }

    /**
     * Gathers every currently-known EPUB display/layout setting -- font, layout, color/theme,
     * scroll mode -- into a single [EpubPreferences], and submits it to the navigator in one call.
     *
     * Readium's `submitPreferences()` *replaces* the whole preferences set rather than merging it
     * with whatever was submitted before (see the Readium 3.1.2 navigator/preferences guide).
     * Before this prompt's change, [setColorTheme]/[setThemeMode]/[setScrollMode] and the font-size
     * dialog each submitted only the couple of fields they cared about, so e.g. changing the font
     * size would silently reset the color theme back to Readium's own default on the next render.
     * Routing every mutation through this single function -- instead of only the *new* Layout tab
     * fields -- fixes that pre-existing cross-tab regression rather than adding a new one.
     *
     * `wordSpacing`/`letterSpacing`/`paragraphSpacing`/`paragraphIndent`/`textAlign`/`hyphens` are
     * only actually passed through (non-null) while [publisherStylesOverrideEnabled] is on --
     * Readium requires `publisherStyles = false` before it will honor any of them at all (verified
     * against the "Advanced settings" section of the preferences guide), so submitting them while
     * still `null`/off would just be a value Readium ignores.
     */
    private fun buildCurrentEpubPreferences(): EpubPreferences {
        val theme = currentColorThemeId
            ?.takeIf { currentThemeMode == ReaderThemeMode.LIGHT }
            ?.let { ReaderColorTheme.findOrDefault(it) }
        val overridingPublisherStyles = publisherStylesOverrideEnabled
        // Readium's EpubPreferences.fontWeight is a normalized multiplier (~1.0 = normal weight),
        // NOT the raw CSS font-weight scale (100-900) that LayoutRanges.FONT_WEIGHT's slider uses
        // for currentFontWeight. Passing the raw 100-900 value (e.g. the 400.0 default) straight
        // through fails Readium's internal `require()` validation and throws
        // IllegalArgumentException on every single reader open. Normalize against 400 (CSS
        // "normal") so the default 400.0 maps to Readium's expected ~1.0.
        val normalizedFontWeight = currentFontWeight / 400.0
        return EpubPreferences(
            fontSize = currentFontSizeMultiplier,
            fontFamily = currentFontFamily,
            fontWeight = normalizedFontWeight,
            lineHeight = currentLineHeight,
            pageMargins = currentPageMargins,
            columnCount = if (twoColumnEnabled) ColumnCount.TWO else ColumnCount.AUTO,
            scroll = scrollModeEnabled,
            theme = themeToReadiumTheme(currentThemeMode),
            backgroundColor = theme?.let { ReadiumColor(it.backgroundColorInt) },
            textColor = theme?.let { ReadiumColor(it.textColorInt) },
            publisherStyles = if (overridingPublisherStyles) false else null,
            wordSpacing = currentWordSpacing.takeIf { overridingPublisherStyles },
            letterSpacing = currentLetterSpacing.takeIf { overridingPublisherStyles },
            paragraphSpacing = currentParagraphSpacing.takeIf { overridingPublisherStyles },
            paragraphIndent = currentParagraphIndent.takeIf { overridingPublisherStyles },
            textAlign = if (overridingPublisherStyles && justifyTextEnabled) TextAlign.JUSTIFY else null,
            hyphens = if (overridingPublisherStyles) hyphensEnabled else null,
        )
    }

    /** Submits [buildCurrentEpubPreferences] to the navigator. Returns false (and logs) if there's
     * no navigator yet or Readium rejects the preferences, matching how every other `setXxx()` in
     * this class already reports failure back to [ReaderChromeHost] callers. */
    private fun applyAllEpubPreferences(): Boolean {
        val nav = navigatorFragment ?: return false
        return try {
            nav.submitPreferences(buildCurrentEpubPreferences())
            applyNativeReaderBackground()
            applyReaderFontCss(nav)
            true
        } catch (e: Exception) {
            Logger.e(e)
            false
        }
    }

    private fun applyReaderFontCss(nav: EpubNavigatorFragment) {
        val css = listOfNotNull(buildReaderBackgroundCss(), buildReaderFontCss())
            .joinToString("\n\n")
            .takeIf { it.isNotBlank() }
        val js = if (css == null) {
            """
            (function(){
              function remove(doc) {
                var old = doc.getElementById ? doc.getElementById('calibre-sync-reader-font') : doc.querySelector('#calibre-sync-reader-font');
                if (old) old.remove();
                doc.querySelectorAll('*').forEach(function(el) {
                  if (el.shadowRoot) remove(el.shadowRoot);
                });
              }
              remove(document);
              document.querySelectorAll('iframe').forEach(function(frame) {
                try { if (frame.contentDocument) remove(frame.contentDocument); } catch (e) {}
              });
            })();
            """.trimIndent()
        } else {
            """
            (function(){
              function install(doc) {
                var style = doc.getElementById ? doc.getElementById('calibre-sync-reader-font') : doc.querySelector('#calibre-sync-reader-font');
                if (!style) {
                  style = (doc.createElement ? doc : doc.ownerDocument).createElement('style');
                  style.id = 'calibre-sync-reader-font';
                  (doc.head || doc.documentElement || doc).appendChild(style);
                }
                style.textContent = ${JSONObject.quote(css)};
                doc.querySelectorAll('iframe').forEach(function(frame) {
                  try { if (frame.contentDocument) install(frame.contentDocument); } catch (e) {}
                });
                doc.querySelectorAll('*').forEach(function(el) {
                  if (el.shadowRoot) install(el.shadowRoot);
                });
              }
              install(document);
            })();
            """.trimIndent()
        }
        lifecycleScope.launch {
            try {
                nav.evaluateJavascript(js)
            } catch (e: Exception) {
                Logger.e(e)
            }
        }
    }

    private fun buildReaderFontCss(): String? {
        val choice = ReaderFontChoice.find(currentCustomFontId).takeIf { it.isCustom } ?: return null
        val family = choice.customFamily ?: return null
        val fallback = choice.fallbackCss.orEmpty()
        val fontFaces = choice.faces.joinToString("\n") { face ->
            val format = if (face.fileName.endsWith(".otf", ignoreCase = true)) "opentype" else "truetype"
            val style = if (face.italic) "italic" else "normal"
            """
            @font-face {
              font-family: '$family';
              src: url('${ReaderFontChoice.assetUrl(face.fileName)}') format('$format');
              font-weight: ${face.weight};
              font-style: $style;
              font-display: swap;
            }
            """.trimIndent()
        }
        return """
        $fontFaces
        html, body, body :not(svg):not(path) {
          font-family: '$family', $fallback !important;
        }
        """.trimIndent()
    }

    private fun buildReaderBackgroundCss(): String? {
        val theme = currentColorThemeId
            ?.let { ReaderColorTheme.findOrDefault(it) }
            ?.takeIf { it.backgroundImageAsset != null }
            ?: return null
        val bg = colorToCss(theme.backgroundColorInt)
        val fg = colorToCss(theme.textColorInt)
        val imageUrl = readerBackgroundDataUri(theme.backgroundImageAsset!!) ?: return null
        val overlay = colorToCssRgba(theme.backgroundColorInt, 1f - currentBackgroundImageOpacity / 100f)
        return """
        :root {
          color-scheme: ${if (currentThemeMode == ReaderThemeMode.DARK) "dark" else "light"};
        }
        html, body, :root {
          min-height: 100vh !important;
          height: 100vh !important;
          margin: 0 !important;
          background-color: $bg !important;
          background-image: linear-gradient($overlay, $overlay), url('$imageUrl') !important;
          background-position: center center !important;
          background-size: cover !important;
          background-attachment: fixed !important;
          background-repeat: no-repeat !important;
          color: $fg !important;
        }
        html::before, body::before {
          content: "" !important;
          position: fixed !important;
          inset: 0 !important;
          z-index: -1 !important;
          pointer-events: none !important;
          background-color: $bg !important;
          background-image: linear-gradient($overlay, $overlay), url('$imageUrl') !important;
          background-position: center center !important;
          background-size: cover !important;
          background-attachment: fixed !important;
          background-repeat: no-repeat !important;
        }
        iframe,
        main, article, section,
        [id="root"], [id="app"],
        [class*="readium"], [class*="viewport"], [class*="spread"],
        [class*="page"], [class*="container"], [class*="wrapper"] {
          background: transparent !important;
        }
        main, article, section, div, p {
          background-color: transparent !important;
        }
        body :not(svg):not(path) {
          color: inherit !important;
        }
        """.trimIndent()
    }

    private fun readerBackgroundDataUri(fileName: String): String? =
        readerBackgroundDataUriCache.getOrPut(fileName) {
            val bytes = assets.open("reader_backgrounds/$fileName").use { it.readBytes() }
            "data:${readerBackgroundMimeType(fileName)};base64," +
                Base64.encodeToString(bytes, Base64.NO_WRAP)
        }

    private fun readerBackgroundMimeType(fileName: String): String =
        when {
            fileName.endsWith(".gif", ignoreCase = true) -> "image/gif"
            fileName.endsWith(".png", ignoreCase = true) -> "image/png"
            else -> "image/jpeg"
        }

    private fun colorToCss(color: Int): String =
        "#%06X".format(0xFFFFFF and color)

    private fun colorToCssRgba(color: Int, alpha: Float): String {
        val clamped = alpha.coerceIn(0f, 1f)
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return "rgba($r, $g, $b, ${"%.3f".format(java.util.Locale.US, clamped)})"
    }

    private fun currentReaderColorTheme(): ReaderColorTheme =
        currentColorThemeId?.let { ReaderColorTheme.findOrDefault(it) } ?: ReaderColorTheme.ALL.first()

    private fun applyNativeReaderBackground() {
        val theme = currentReaderColorTheme()
        findViewById<View>(R.id.epub_reader_root)?.setBackgroundColor(theme.backgroundColorInt)
        contentRoot.setBackgroundColor(Color.TRANSPARENT)
        makeReaderWebViewsTransparent(contentRoot)
        makeReaderWebViewsTransparent(navigatorFragment?.view)
        applyReaderSystemBars(theme)

        val imageAsset = theme.backgroundImageAsset
        applyReaderDecorBackground(theme, imageAsset)

        val imageView = readerBackgroundImageView ?: return
        if (imageAsset == null) {
            imageView.setImageDrawable(null)
            imageView.visibility = View.GONE
            return
        }

        val drawable = loadReaderBackgroundDrawable(imageAsset)
        imageView.setImageDrawable(drawable)
        imageView.alpha = currentBackgroundImageOpacity / 100f
        imageView.visibility = if (drawable == null) View.GONE else View.VISIBLE
    }

    private fun loadReaderBackgroundDrawable(fileName: String): BitmapDrawable? =
        try {
            assets.open("reader_backgrounds/$fileName").use { input ->
                BitmapDrawable(resources, BitmapFactory.decodeStream(input)).apply {
                    gravity = Gravity.FILL
                }
            }
        } catch (e: Exception) {
            Logger.e(e)
            null
        }

    private fun applyReaderDecorBackground(theme: ReaderColorTheme, imageAsset: String?) {
        if (imageAsset == null) {
            window.decorView.setBackgroundColor(theme.backgroundColorInt)
            return
        }
        val drawable = loadReaderBackgroundDrawable(imageAsset)
        if (drawable == null) {
            window.decorView.setBackgroundColor(theme.backgroundColorInt)
            return
        }
        drawable.alpha = (currentBackgroundImageOpacity * 255 / 100).coerceIn(0, 255)
        window.decorView.background = LayerDrawable(
            arrayOf(
                ColorDrawable(theme.backgroundColorInt),
                drawable,
            ),
        )
    }

    private fun configureReaderWindowForEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION,
        )
        window.decorView.systemUiVisibility = readerLayoutSystemUiFlags()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    private fun drawReaderBackgroundOnCanvas(canvas: Canvas, width: Int, height: Int) {
        val theme = currentReaderColorTheme()
        canvas.drawColor(theme.backgroundColorInt)
        val imageAsset = theme.backgroundImageAsset ?: return
        val drawable = loadReaderBackgroundDrawable(imageAsset) ?: return
        drawable.alpha = (currentBackgroundImageOpacity * 255 / 100).coerceIn(0, 255)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
    }

    private fun disableReaderContentInsets() {
        val contentHost = window.decorView.findViewById<View>(android.R.id.content)
        val readerRoot = findViewById<View>(R.id.epub_reader_root)
        listOfNotNull(contentHost, readerRoot).forEach { view ->
            view.fitsSystemWindows = false
            view.setPadding(0, 0, 0, 0)
            ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
                target.setPadding(0, 0, 0, 0)
                insets
            }
            ViewCompat.requestApplyInsets(view)
        }
    }

    private fun readerLayoutSystemUiFlags(): Int =
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

    private fun applyReaderSystemBars(theme: ReaderColorTheme) {
        val usesImageBackground = theme.backgroundImageAsset != null
        val barColor = if (usesImageBackground) Color.TRANSPARENT else theme.backgroundColorInt
        window.statusBarColor = barColor
        window.navigationBarColor = barColor

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        val lightBars = isLightColor(theme.backgroundColorInt)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
    }

    private fun isLightColor(color: Int): Boolean {
        val red = Color.red(color) / 255.0
        val green = Color.green(color) / 255.0
        val blue = Color.blue(color) / 255.0
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue > 0.55
    }

    private fun makeReaderWebViewsTransparent(view: View?) {
        when (view) {
            null -> return
            is android.webkit.WebView -> view.setBackgroundColor(Color.TRANSPARENT)
            is android.view.ViewGroup -> {
                view.setBackgroundColor(Color.TRANSPARENT)
                for (i in 0 until view.childCount) {
                    makeReaderWebViewsTransparent(view.getChildAt(i))
                }
            }
            else -> view.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun reapplyReaderBackgroundSoon(nav: EpubNavigatorFragment? = navigatorFragment) {
        applyNativeReaderBackground()
        nav?.let { applyReaderFontCss(it) }
        val root = if (::contentRoot.isInitialized) contentRoot else return
        listOf(80L, 260L).forEach { delayMs ->
            root.postDelayed({
                applyNativeReaderBackground()
                nav?.let { applyReaderFontCss(it) }
            }, delayMs)
        }
    }

    /** For [net.mrphan.calibre.reader.chrome.settings.ReaderSettingsFontPage]/
     * [net.mrphan.calibre.reader.chrome.settings.ReaderSettingsLayoutPage]: apply the field the
     * page just mutated to the live navigator *and* persist it, in one call -- each row in those
     * two pages takes effect immediately (no separate "OK" button), same as every other reader
     * setting in this app. */
    internal fun applyAndPersistReaderPreferences() {
        applyAllEpubPreferences()
        persistDisplaySettings()
        persistLayoutSettings()
    }

    /** For [net.mrphan.calibre.reader.chrome.settings.ReaderSettingsProfilesSheet]'s "Lưu cấu
     * hình hiện tại...": snapshots every field covered by a [ReaderSettingsProfile] (all 5 tabs)
     * from this activity's current in-memory state -- see prompt-hoan-thien-cau-hinh-tao-san.md
     * mục 0/1.4 for exactly which fields and why they're read this way (direct field access, not
     * through ReaderChromeHost). */
    internal fun captureCurrentSettingsAsProfile(): ReaderSettingsProfile =
        ReaderSettingsProfile(
            id = "",
            name = "",
            isBuiltIn = false,
            font = FontSettings(
                fontSizeMultiplier = currentFontSizeMultiplier,
                fontWeight = currentFontWeight,
                fontFamilyName = currentFontChoiceId().takeUnless { it == ReaderFontChoice.DEFAULT_ID },
                publisherStylesOverrideEnabled = publisherStylesOverrideEnabled,
            ),
            layout = LayoutSettings(
                lineHeight = currentLineHeight,
                pageMargins = currentPageMargins,
                twoColumnEnabled = twoColumnEnabled,
                paragraphSpacing = currentParagraphSpacing,
                paragraphIndent = currentParagraphIndent,
                wordSpacing = currentWordSpacing,
                letterSpacing = currentLetterSpacing,
                justifyTextEnabled = justifyTextEnabled,
                hyphensEnabled = hyphensEnabled,
            ),
            color = ColorSettings(
                colorThemeId = currentColorThemeId() ?: "default",
                themeMode = currentThemeMode().name,
                screenBrightness = currentScreenBrightness(),
            ),
            behavior = BehaviorSettings(keepScreenOnEnabled = keepScreenOnEnabled),
            tts = TtsSettings(speechRateMultiplier = ttsSettingsStore.getSpeechRate()),
        )

    /** Áp dụng một [ReaderSettingsProfile] ("Cấu hình tạo sẵn") vào cả 5 tab cùng lúc -- xem
     * prompt-hoan-thien-cau-hinh-tao-san.md mục 1.5. */
    internal fun applySettingsProfile(profile: ReaderSettingsProfile) {
        // Font
        currentFontSizeMultiplier = profile.font.fontSizeMultiplier
        currentFontWeight = profile.font.fontWeight
        setCurrentFontChoice(ReaderFontChoice.find(profile.font.fontFamilyName))
        publisherStylesOverrideEnabled = profile.font.publisherStylesOverrideEnabled

        // Layout
        currentLineHeight = profile.layout.lineHeight
        currentPageMargins = profile.layout.pageMargins
        twoColumnEnabled = profile.layout.twoColumnEnabled
        currentParagraphSpacing = profile.layout.paragraphSpacing
        currentParagraphIndent = profile.layout.paragraphIndent
        currentWordSpacing = profile.layout.wordSpacing
        currentLetterSpacing = profile.layout.letterSpacing
        justifyTextEnabled = profile.layout.justifyTextEnabled
        hyphensEnabled = profile.layout.hyphensEnabled

        // Font + Layout ap dung va luu cung 1 luot, giong het cach moi Page rieng le dang lam.
        applyAndPersistReaderPreferences()

        // Behavior
        setKeepScreenOnEnabled(profile.behavior.keepScreenOnEnabled)

        // Color -- 3 ham nay da co san tren ReaderChromeHost, tu luu SharedPreferences ben trong.
        setColorTheme(profile.color.colorThemeId)
        setThemeMode(ReaderThemeMode.valueOf(profile.color.themeMode))
        if (profile.color.screenBrightness in 0..100) {
            setScreenBrightness(profile.color.screenBrightness)
        }

        // TTS: chi doi toc do mac dinh cho phien doc to TIEP THEO (giong cach tab TTS dang lam) --
        // neu dang co phien doc to, doi luon toc do phien do cho khop.
        ttsSettingsStore.saveSpeechRate(profile.tts.speechRateMultiplier)
        activeTtsController()?.setSpeed(profile.tts.speechRateMultiplier)
    }

    // --- Color/background theme + screen brightness (see ColorContrastAction /
    // prompt-nang-cap-mau-nen-tien-do-doc.md muc 3.1/4.10) ------------------------------------

    override fun openColorAndContrastSettings(): Boolean {
        val nav = navigatorFragment ?: return false
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_reader_color_settings, null, false)
        val brightnessBar = view.findViewById<SeekBar>(R.id.color_settings_brightness)
        val swatchRow = view.findViewById<LinearLayout>(R.id.color_settings_swatch_row)
        val modeToggle = view.findViewById<ImageButton>(R.id.color_settings_mode_toggle)
        val opacityGroup = view.findViewById<LinearLayout>(R.id.color_settings_background_opacity_group)
        val opacityLabel = view.findViewById<TextView>(R.id.color_settings_background_opacity_label)
        val opacityBar = view.findViewById<SeekBar>(R.id.color_settings_background_opacity)

        val initialBrightness = if (currentScreenBrightness in 0..100) currentScreenBrightness else 50
        brightnessBar.progress = initialBrightness
        brightnessBar.setOnSeekBarChangeListener(onProgressChanged { progress ->
            setScreenBrightness(progress)
        })

        fun refreshOpacityControls() {
            val hasBackgroundImage = currentReaderColorTheme().backgroundImageAsset != null
            opacityGroup.visibility = if (hasBackgroundImage) View.VISIBLE else View.GONE
            opacityLabel.text = getString(
                R.string.reader_background_opacity_value,
                currentBackgroundImageOpacity(),
            )
            if (opacityBar.progress != currentBackgroundImageOpacity()) {
                opacityBar.progress = currentBackgroundImageOpacity()
            }
        }

        opacityBar.progress = currentBackgroundImageOpacity()
        opacityBar.setOnSeekBarChangeListener(onProgressChanged { progress ->
            setBackgroundImageOpacity(progress)
            refreshOpacityControls()
        })

        fun refreshSwatchSelection() {
            for (i in 0 until swatchRow.childCount) {
                val child = swatchRow.getChildAt(i) as TextView
                val theme = ReaderColorTheme.ALL[i]
                val isSelected = theme.id == (currentColorThemeId ?: ReaderColorTheme.ALL.first().id)
                ReaderColorSwatch.bind(child, theme, isSelected)
            }
            refreshOpacityControls()
        }

        swatchRow.removeAllViews()
        for (theme in ReaderColorTheme.ALL) {
            val swatch = LayoutInflater.from(this)
                .inflate(R.layout.item_reader_color_swatch, swatchRow, false) as TextView
            swatch.setOnClickListener {
                setColorTheme(theme.id)
                refreshSwatchSelection()
            }
            swatchRow.addView(swatch)
        }
        refreshSwatchSelection()

        modeToggle.setOnClickListener {
            val next = if (currentThemeMode == ReaderThemeMode.DARK) ReaderThemeMode.LIGHT else ReaderThemeMode.DARK
            setThemeMode(next)
        }

        sheet.setContentView(view)
        sheet.show()
        return true
    }

    override fun currentScreenBrightness(): Int = currentScreenBrightness

    override fun setScreenBrightness(value: Int): Boolean {
        val clamped = value.coerceIn(0, 100)
        currentScreenBrightness = clamped
        window.attributes = window.attributes.apply { screenBrightness = clamped / 100f }
        settingsPrefs.edit().putInt(PREF_READER_SCREEN_BRIGHTNESS, clamped).apply()
        return true
    }

    internal fun currentBackgroundImageOpacity(): Int = currentBackgroundImageOpacity

    internal fun setBackgroundImageOpacity(value: Int): Boolean {
        currentBackgroundImageOpacity = value.coerceIn(0, 100)
        settingsPrefs.edit()
            .putInt(PREF_READER_BACKGROUND_IMAGE_OPACITY, currentBackgroundImageOpacity)
            .apply()
        val nav = navigatorFragment
        if (nav != null) {
            applyNativeReaderBackground()
            applyReaderFontCss(nav)
        } else {
            applyNativeReaderBackground()
        }
        return true
    }

    /** For [net.mrphan.calibre.reader.chrome.settings.ReaderSettingsBehaviorPage]'s "Giữ màn hình
     * sáng khi đọc" switch (prompt-nang-cap-reader-settings-tabbed.md muc 5.3) -- not part of
     * [ReaderChromeHost] since it's EPUB-only UI for now and PdfReaderActivity/CbzReaderActivity
     * are out of this prompt's scope; see [ReaderSettingsPage]'s kdoc for why a direct cast is
     * used here instead of widening the interface. */
    internal fun setKeepScreenOnEnabled(enabled: Boolean) {
        keepScreenOnEnabled = enabled
        settingsPrefs.edit().putBoolean(PREF_READER_KEEP_SCREEN_ON, enabled).apply()
        if (enabled) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** For [net.mrphan.calibre.reader.chrome.settings.ReaderSettingsBehaviorPage]'s "Xoay man
     * hinh" row (prompt-nang-cap-cai-dat-xoay-man-hinh-reader.md) -- dat ngay sau
     * setKeepScreenOnEnabled() vi cung 1 kieu cai dat: doc field Activity truc tiep, khong qua
     * Readium/EpubPreferences. */
    internal fun setScreenOrientationMode(mode: net.mrphan.calibre.reader.epub.ScreenOrientationMode) {
        currentScreenOrientationMode = mode
        settingsPrefs.edit().putString(PREF_READER_SCREEN_ORIENTATION, mode.name).apply()
        requestedOrientation = mode.toActivityInfoOrientation()
    }

    /** Doi hieu ung lat trang cho che do paginated (khong anh huong scroll mode) -- xem
     * PageTurnTransformers.kt cho chi tiet 2 hieu ung SLIDE/CURL, va
     * prompt-nang-cap-hieu-ung-lat-trang.md cho ly do KHONG di qua EpubPreferences. */
    internal fun setPageTurnAnimation(animation: net.mrphan.calibre.reader.epub.PageTurnAnimation) {
        if (pageTurnAnimation == animation) return
        pageTurnAnimation = animation
        settingsPrefs.edit().putString(PREF_READER_PAGE_TURN_ANIMATION, animation.name).apply()
        applyPageTurnAnimation()
        reapplyReaderBackgroundSoon()
    }

    internal fun getPageTurnAnimation(): net.mrphan.calibre.reader.epub.PageTurnAnimation =
        pageTurnAnimation

    override fun currentColorThemeId(): String? = currentColorThemeId

    override fun setColorTheme(themeId: String): Boolean {
        if (navigatorFragment == null) return false
        val theme = ReaderColorTheme.findOrDefault(themeId)
        currentColorThemeId = theme.id
        val applied = applyAllEpubPreferences()
        if (!applied) {
            Logger.e(IllegalStateException("Failed to submit Readium color theme; applying native/CSS fallback"))
            applyNativeReaderBackground()
            navigatorFragment?.let { applyReaderFontCss(it) }
        }
        settingsPrefs.edit().putString(PREF_READER_COLOR_THEME, theme.id).apply()
        return applied
    }

    // --- Reading progress with chapter tick marks (see ReaderProgressSeekBar /
    // prompt-nang-cap-mau-nen-tien-do-doc.md muc 3.2/4.10) -------------------------------------

    override fun chapterTickFractions(): List<Float> {
        val pub = publication ?: return emptyList()
        val toc = pub.tableOfContents
        if (toc.isEmpty()) return emptyList()
        val readingOrder = pub.readingOrder
        if (readingOrder.size < 3) return emptyList()
        return try {
            val positions = kotlinx.coroutines.runBlocking { pub.positionsByReadingOrder() }
            val hrefs = mutableListOf<String>()
            fun collect(links: List<org.readium.r2.shared.publication.Link>) {
                for (link in links) {
                    hrefs.add(link.href.toString())
                    collect(link.children)
                }
            }
            collect(toc)
            val ticks = sortedSetOf<Float>()
            for (href in hrefs) {
                val index = readingOrder.indexOfFirst { it.href.toString() == href }
                if (index in 1 until (readingOrder.size - 1)) {
                    val fraction = positions.getOrNull(index)?.firstOrNull()?.locations?.totalProgression
                    if (fraction != null) ticks.add(fraction.toFloat())
                }
            }
            ticks.filter { it > 0f && it < 1f }
        } catch (e: Exception) {
            Logger.e(e)
            emptyList()
        }
    }

    override fun currentProgressFraction(): Float =
        (lastLocator?.locations?.totalProgression ?: 0.0).toFloat().coerceIn(0f, 1f)

    override fun jumpToProgressFraction(fraction: Float): Boolean {
        val pub = publication ?: return false
        return try {
            val locator = kotlinx.coroutines.runBlocking {
                pub.locateProgression(fraction.toDouble().coerceIn(0.0, 1.0))
            } ?: return false
            jumpTo(locator)
            true
        } catch (e: Exception) {
            Logger.e(e)
            false
        }
    }

    override fun goToPreviousChapter(): Boolean = jumpToAdjacentChapter(forward = false)

    override fun goToNextChapter(): Boolean = jumpToAdjacentChapter(forward = true)

    override fun currentChapterTitle(): String {
        val pub = publication ?: return ""
        val href = lastLocator?.href?.toString()?.substringBefore('#') ?: return ""
        val tocTitle = pub.tableOfContents.let { findTocTitleForHref(it, href) }
        if (tocTitle != null) return tocTitle
        return pub.readingOrder.firstOrNull { it.href.toString() == href }?.title.orEmpty()
    }

    // --- Cloud progress sync -----------------------------------------------------------------

    private var progressSyncInProgress = false
    private var progressSyncWorkId: java.util.UUID? = null
    /** Bat len khi saveLastLocator() ghi 1 vi tri MOI -- auto-sync chi day len khi
     * thuc su co gi thay doi, tranh spam job rong moi tick. */
    private var hasUnsyncedProgress = false

    /** Vong lap dinh thoi cho auto-sync, tach ra dung chung voi TtsPlaybackService -- xem
     * ReaderProgressAutoSyncLoop va startAutoSyncLoopIfEnabled() ben duoi. */
    private val autoSyncLoop by lazy {
        net.mrphan.calibre.reader.chrome.ReaderProgressAutoSyncLoop(
            scope = lifecycleScope,
            getIntervalMinutes = { autoSyncIntervalMinutes() },
            isAvailable = { isProgressSyncAvailable() },
            hasUnsyncedProgress = { hasUnsyncedProgress },
            isSyncInProgress = { progressSyncInProgress },
            trySync = { syncProgressNow { } },
        )
    }

    /** true sau khi ĐÃ THỬ pull progress lúc mở sách trong phiên Activity hiện tại (thành
     * công hay thất bại đều tính) -- đảm bảo chỉ chạy 1 lần mỗi lần mở sách thật sự, không
     * lặp lại mỗi onResume (khoá màn hình / chuyển app). Reset về false chỉ xảy ra tự nhiên
     * khi Activity bị tạo lại (biến instance mới), không cần tự tay reset. */
    private var initialProgressPullAttempted = false

    private fun firstSyncableRemoteOrNull(): CloudRemote? =
        CloudRemoteStore.getAll(this).firstOrNull { it.type.supportsDeviceLibrarySync() }

    override fun isProgressSyncAvailable(): Boolean = firstSyncableRemoteOrNull() != null

    /** Pull tien trinh/bookmark tu cloud VE NEN ngay khi mo sach, khong chan man hinh doc (sach
     * van mo ngay bang vi tri cuc bo hien co nhu truoc). Neu sau khi tai ve phat hien vi tri MOI
     * HON va KHAC vi tri dang hien thi, hoi nguoi dung co muon nhay toi khong -- khong tu dong
     * nhay de tranh giat trang khi nguoi dung da doc tiep trong luc cho mang. Chi chay 1 lan moi
     * phien mo sach (xem initialProgressPullAttempted). Day chinh la fix cho loi "may B khong
     * kep duoc tien trinh cua may A xuong" -- truoc day KHONG co buoc pull nao o day ca, chi co
     * push khi hasUnsyncedProgress lam nguoi doc tu ghi de. */
    private fun pullProgressOnOpen() {
        if (initialProgressPullAttempted) return
        initialProgressPullAttempted = true
        if (!isProgressSyncAvailable()) return

        val updatedAtBefore = mDB.getLastLocatorUpdatedAt(uuid)
        val remote = firstSyncableRemoteOrNull() ?: return
        val workId = DeviceLibrarySyncScheduler.enqueueProgressSync(this, remote.id)
        androidx.work.WorkManager.getInstance(this)
            .getWorkInfoByIdLiveData(workId)
            .observe(this) { info ->
                if (info == null || !info.state.isFinished) return@observe
                if (info.state != androidx.work.WorkInfo.State.SUCCEEDED) return@observe

                val updatedAtAfter = mDB.getLastLocatorUpdatedAt(uuid)
                if (updatedAtAfter <= updatedAtBefore) return@observe // khong co gi moi hon

                val newLocatorJson = mDB.getLastLocator(uuid) ?: return@observe
                // Tai su dung dung wrapper/parse logic voi restoreLastLocator()/saveLastLocator()
                // (dinh dang {"type":"epub_locator","locator":{...}}) thay vi viet lai -- xem
                // decodeLocator() trong reader/epub/ReaderAnnotation.kt.
                val newLocator = decodeLocator(newLocatorJson) ?: return@observe
                // Khong nhay toi neu trung voi vi tri dang hien thi (vd chinh may nay vua
                // upload xong trong luc cho, roi tai lai dung ban cua minh).
                if (newLocator.href == lastLocator?.href
                    && newLocator.locations == lastLocator?.locations
                ) {
                    return@observe
                }

                showRemoteProgressAvailableDialog(newLocator)
            }
    }

    private fun showRemoteProgressAvailableDialog(newLocator: Locator) {
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_remote_progress_found_title)
            .setMessage(R.string.reader_remote_progress_found_message)
            .setPositiveButton(R.string.reader_remote_progress_jump) { _, _ ->
                jumpTo(newLocator)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun lastProgressSyncedAt(): Long? =
        net.mrphan.calibre.reader.chrome.ReaderSyncSettingsStore.getLastProgressSyncedAt(this, uuid)

    override fun isProgressSyncInProgress(): Boolean = progressSyncInProgress

    override fun syncProgressNow(onDone: (Boolean) -> Unit): Boolean {
        if (progressSyncInProgress) return false
        val remote = firstSyncableRemoteOrNull() ?: return false
        progressSyncInProgress = true
        val workId = DeviceLibrarySyncScheduler.enqueueProgressSync(this, remote.id)
        progressSyncWorkId = workId
        androidx.work.WorkManager.getInstance(this)
            .getWorkInfoByIdLiveData(workId)
            .observe(this) { info ->
                if (info != null && info.state.isFinished) {
                    progressSyncInProgress = false
                    val success = info.state == androidx.work.WorkInfo.State.SUCCEEDED
                    if (success) {
                        hasUnsyncedProgress = false
                        net.mrphan.calibre.reader.chrome.ReaderSyncSettingsStore
                            .setLastProgressSyncedAt(this, uuid, System.currentTimeMillis())
                    }
                    onDone(success)
                }
            }
        return true
    }

    override fun openBookSyncInfo(): Boolean {
        val bookPath = intent.getStringExtra(ReaderActivity.EXTRA_BOOK_PATH) ?: return false
        val author = mDB.getByUuid(arrayOf("author"), uuid)?.use { c ->
            if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow("author")) else null
        }.orEmpty()
        net.mrphan.calibre.reader.chrome.BookSyncInfoSheet.newInstance(
            uuid = uuid,
            title = bookTitle,
            author = author,
            bookFilePath = bookPath,
            lastSyncedAtMillis = lastProgressSyncedAt(),
        ).show(supportFragmentManager, net.mrphan.calibre.reader.chrome.BookSyncInfoSheet.TAG)
        return true
    }

    override fun autoSyncIntervalMinutes(): Int =
        net.mrphan.calibre.reader.chrome.ReaderSyncSettingsStore.getAutoSyncIntervalMinutes(this)

    override fun setAutoSyncIntervalMinutes(minutes: Int) {
        net.mrphan.calibre.reader.chrome.ReaderSyncSettingsStore.setAutoSyncIntervalMinutes(this, minutes)
        // resetSchedule=true: nguoi dung chu dong doi chu ky nen ap dung ngay, khong doi tick
        // hien tai het han.
        startAutoSyncLoopIfEnabled(resetSchedule = true)
    }

    /** Vong lap song theo Activity -- xem prompt-nang-cap-reader-sync.md muc 0.3 ve ly do KHONG
     * dung PeriodicWorkRequest (toi thieu 15 phut, khong dap ung mac dinh 3 phut). Logic dinh
     * thoi gian (bao gom fix bug dong ho bi reset ve 0 moi lan onPause/onResume, va fix bug tick
     * bi bo qua lam tre ca chu ky) da duoc tach sang ReaderProgressAutoSyncLoop dung chung voi
     * TtsPlaybackService -- xem class do de biet chi tiet.
     *
     * BUG DA SUA (rieng o Activity nay): khi nguoi dung bam "Doc cho nghe" roi thoat man hinh
     * doc, TtsPlaybackService song doc lap va tu chay auto-sync rieng cho dung cuon sach do (xem
     * TtsPlaybackService.autoSyncLoop) trong suot thoi gian Activity nay khong o foreground. Neu
     * Activity nay ALSO tu chay vong lap cua no ngay khi resume tro lai (vi du nguoi dung mo lai
     * dung man hinh doc trong luc van dang nghe), 2 noi co the cung enqueue sync chong cheo cho
     * cung 1 uuid. Cach sua: chi khoi dong vong lap CUA ACTIVITY khi KHONG co phien "Doc cho
     * nghe" nao dang active cho DUNG cuon sach nay -- neu co, coi TtsPlaybackService la nguon
     * auto-sync DUY NHAT (xem isTtsSessionActiveForThisBook()). */
    private fun startAutoSyncLoopIfEnabled(resetSchedule: Boolean = false) {
        if (isTtsSessionActiveForThisBook()) {
            autoSyncLoop.stop()
            return
        }
        autoSyncLoop.start(resetSchedule = resetSchedule)
    }

    /** true neu TtsPlaybackService dang phat (hoac dang co phien dang do, tuc sentenceCount > 0)
     * DUNG cuon sach dang mo o Activity nay -- dung de tranh 2 noi cung auto-sync 1 luc (xem
     * ghi chu trong startAutoSyncLoopIfEnabled). Doc qua binding ttsServiceConnection da co san;
     * neu chua bind duoc Service (vi du nguoi dung chua bao gio bam "Doc cho nghe"), coi nhu
     * khong co phien nao dang active. */
    private fun isTtsSessionActiveForThisBook(): Boolean {
        val boundService = ttsService ?: return false
        if (boundService.nowPlayingUuid != uuid) return false
        val state = boundService.ttsController.state.value
        return state.isPlaying || state.sentenceCount > 0
    }

    private fun jumpToAdjacentChapter(forward: Boolean): Boolean {
        val pub = publication ?: return false
        val toc = pub.tableOfContents
        if (toc.isEmpty()) return false
        val currentFraction = currentProgressFraction()
        val ticks = chapterTickFractions()
        val target = if (forward) {
            ticks.firstOrNull { it > currentFraction + 0.001f }
        } else {
            ticks.lastOrNull { it < currentFraction - 0.001f }
        } ?: return false
        return jumpToProgressFraction(target)
    }

    override fun isImmersiveModeEnabled(): Boolean = fullscreenOn

    override fun toggleFullscreen(): Boolean {
        applyImmersiveModeToWindow(!fullscreenOn)
        settingsPrefs.edit().putBoolean(PREF_READER_IMMERSIVE_MODE, fullscreenOn).apply()
        return true
    }

    /** Actually shows/hides the system bars and updates [fullscreenOn] -- split out from
     * [toggleFullscreen] so `onCreate()` can also apply a *restored* (not toggled) immersive
     * state to a freshly-created window without going through the "flip + persist" logic meant
     * for the user's own taps. */
    private fun applyImmersiveModeToWindow(enabled: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        fullscreenOn = enabled
        if (fullscreenOn) {
            window.decorView.systemUiVisibility = readerLayoutSystemUiFlags() or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            window.decorView.systemUiVisibility = readerLayoutSystemUiFlags()
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun currentThemeMode(): ReaderThemeMode = currentThemeMode

    override fun setThemeMode(mode: ReaderThemeMode): Boolean {
        if (navigatorFragment == null) return false
        currentThemeMode = mode
        if (!applyAllEpubPreferences()) return false
        settingsPrefs.edit().putString(PREF_READER_THEME_MODE, mode.name).apply()
        if (::chromeOverlay.isInitialized) {
            chromeOverlay.refreshActions()
        }
        return true
    }

    override fun isScrollModeEnabled(): Boolean = scrollModeEnabled

    override fun setScrollMode(enabled: Boolean): Boolean {
        if (navigatorFragment == null) return false
        if (scrollModeEnabled == enabled) return true
        if (!enabled) stopAutoScroll() // continuous scroll turning off must stop any auto-scroll.
        scrollModeEnabled = enabled
        if (!applyAllEpubPreferences()) return false
        settingsPrefs.edit().putBoolean(PREF_READER_SCROLL_MODE, enabled).apply()
        return true
    }

    override fun isEdgeTapPageTurnEnabled(): Boolean = edgeTapPageTurnEnabled

    override fun isEdgeTapPageTurnInverted(): Boolean = edgeTapPageTurnInverted

    internal fun setVolumeKeyPageTurnEnabled(enabled: Boolean) {
        volumeKeyPageTurnEnabled = enabled
        settingsPrefs.edit().putBoolean(PREF_READER_VOLUME_KEY_PAGE_TURN, enabled).apply()
    }

    internal fun setVolumeKeyPageTurnInverted(inverted: Boolean) {
        volumeKeyPageTurnInverted = inverted
        settingsPrefs.edit().putBoolean(PREF_READER_VOLUME_KEY_INVERT, inverted).apply()
    }

    internal fun setEdgeTapPageTurnEnabled(enabled: Boolean) {
        edgeTapPageTurnEnabled = enabled
        settingsPrefs.edit().putBoolean(PREF_READER_EDGE_TAP_PAGE_TURN, enabled).apply()
    }

    internal fun setEdgeTapPageTurnInverted(inverted: Boolean) {
        edgeTapPageTurnInverted = inverted
        settingsPrefs.edit().putBoolean(PREF_READER_EDGE_TAP_INVERT, inverted).apply()
    }

    override fun isAutoScrollEnabled(): Boolean = autoScrollJob?.isActive == true

    override fun toggleAutoScroll(): Boolean {
        if (!scrollModeEnabled) {
            showMessage(getString(R.string.reader_auto_scroll_requires_scroll_mode))
            return false
        }
        if (isAutoScrollEnabled()) {
            stopAutoScroll()
        } else {
            startAutoScroll()
        }
        return true
    }

    override fun openAutoScrollSpeedControls(): Boolean {
        val labels = arrayOf(
            getString(R.string.reader_auto_scroll_speed_slow),
            getString(R.string.reader_auto_scroll_speed_medium),
            getString(R.string.reader_auto_scroll_speed_fast),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_auto_scroll_speed_title)
            .setSingleChoiceItems(labels, autoScrollSpeedLevel) { dialog, which ->
                autoScrollSpeedLevel = which
                settingsPrefs.edit().putInt(PREF_READER_AUTO_SCROLL_SPEED, which).apply()
                if (isAutoScrollEnabled()) {
                    // Restart at the new speed immediately, instead of waiting for the next
                    // stop/start, so the change is felt right away.
                    stopAutoScroll()
                    startAutoScroll()
                }
                dialog.dismiss()
            }
            .show()
        return true
    }

    /** Auto scroll is implemented as a coroutine loop calling `goForward()` on a fixed cadence,
     * rather than a real pixel-by-pixel WebView scroll animation: EpubNavigatorFragment doesn't
     * expose a raw "scroll by N px" primitive over its public API (only `go`/`goForward`/
     * `goBackward` to a Locator or the next viewport), so this is the closest approximation
     * achievable without reaching into Readium internals. It reads as "trôi trang" one screen at
     * a time rather than a perfectly smooth pixel drift -- documented here rather than presented
     * as the smooth drift the prompt describes, per this project's "state uncertainty, don't
     * silently downgrade" rule. */
    private fun startAutoScroll() {
        autoScrollJob?.cancel()
        val intervalMs = when (autoScrollSpeedLevel) {
            AUTO_SCROLL_SPEED_SLOW -> 6000L
            AUTO_SCROLL_SPEED_FAST -> 2000L
            else -> 3800L
        }
        autoScrollJob = lifecycleScope.launch {
            while (isActive) {
                delay(intervalMs)
                val advanced = try {
                    navigatorFragment?.goForward(animated = true) ?: false
                } catch (e: Exception) {
                    Logger.e(e)
                    false
                }
                if (!advanced) break // reached the end of the book -- stop instead of spinning.
            }
        }
    }

    private fun stopAutoScroll() {
        autoScrollJob?.cancel()
        autoScrollJob = null
    }

    // --- Highlights: text-selection popup + Decoration rendering ----------------------------

    /**
     * [ReaderSelectionActionHost] implementation backing [ReaderSelectionToolbar] -- see that
     * class's kdoc for *why* the selection toolbar is still an `ActionMode` under the hood
     * (Readium 3.1.2 has no API for a fully freestanding replacement) and for what actually
     * changed vs. the old design (icon-only items, color picker moved into its own floating
     * popup instead of four always-visible menu entries, real Note/Search/Share actions, and
     * Dictionary/Translate now opening a real lookup bottom sheet -- see
     * [showDictionaryLookup]/[showTranslation] below and `net.mrphan.calibre.reader.lookup`).
     */
    override fun anchorView(): View? = navigatorFragment?.view

    override suspend fun currentSelectionSnapshot(): ReaderSelectionSnapshot? {
        val nav = navigatorFragment ?: return null
        val selection = try {
            nav.currentSelection()
        } catch (e: Exception) {
            Logger.e(e)
            null
        } ?: return null
        val text = selection.locator.text
        val excerpt = text.highlight ?: (text.before.orEmpty() + text.after.orEmpty())
        return ReaderSelectionSnapshot(
            locatorJson = encodeLocator(selection.locator),
            text = excerpt,
            rect = selection.rect,
        )
    }

    override fun addHighlight(
        locatorJson: String,
        color: ReaderHighlightColor,
        note: String?,
        excerpt: String?,
        style: ReaderHighlightStyle,
    ) {
        val nav = navigatorFragment
        Logger.d(
            "addHighlight: color=${color.storageKey} style=$style nav=${nav != null} " +
                "locatorJson=${locatorJson.take(120)}"
        )
        // BUG FIX: verify locatorJson actually round-trips through decodeLocator() BEFORE writing
        // it to the DB -- loadAndApplyHighlights() and ReaderHighlightsSheet both silently
        // `continue`/skip any row whose locator fails to decode (by design, so one bad row
        // doesn't crash the whole list), which is exactly what "highlight saved but never tints
        // the text or shows up in the list" looks like from the outside: the DB write itself can
        // succeed while the row is permanently unusable. Catching that here, at write time,
        // turns a silent no-op into a visible message instead.
        if (decodeLocator(locatorJson) == null) {
            Logger.d("addHighlight: locatorJson failed decodeLocator() round-trip, aborting save")
            showMessage(getString(R.string.reader_highlight_add_failed))
            return
        }
        lifecycleScope.launch {
            val newId = mDB.addAnnotation(
                uuid,
                "highlight",
                locatorJson,
                // encodeColorStyle() packs style into the same TEXT column color always used --
                // see ReaderHighlightStyle's kdoc for why this needed no DATABASE_VERSION bump.
                encodeColorStyle(color, style),
                note,
                excerpt?.take(HIGHLIGHT_EXCERPT_MAX_LENGTH),
            )
            Logger.d("addHighlight: mDB.addAnnotation() returned id=$newId")
            if (newId <= 0) {
                showMessage(getString(R.string.reader_highlight_add_failed))
                return@launch
            }
            try {
                nav?.clearSelection()
            } catch (e: Exception) {
                Logger.e(e)
            }
            nav?.let { loadAndApplyHighlights(it) } ?: Logger.d("addHighlight: navigatorFragment is null, cannot render")
        }
    }

    override fun openSearchInBook(query: String) {
        openSearchDialog(query)
    }

    override fun shareSelectedText(text: String) {
        if (text.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.reader_selection_share_chooser_title)))
        } catch (e: Exception) {
            Logger.e(e)
        }
    }

    // --- ReaderImageTapHost --------------------------------------------------------------------

    override fun imageTapScope(): CoroutineScope = lifecycleScope

    override fun openImageTool(entries: List<EpubImageEntry>, startIndex: Int) {
        val openedPublication = publication ?: return
        ReaderImageToolDialog(openedPublication, entries, startIndex)
            .show(supportFragmentManager, ReaderImageToolDialog.TAG)
    }

    /**
     * Chi tra 1 tu/cum tu ngan -- neu nguoi dung boi den ca doan van dai roi bam nut Tu dien, cat
     * bot ve [MAX_LOOKUP_WORD_LENGTH] ky tu (tranh goi API tra tu voi input vo nghia, du khong
     * gay loi gi nghiem trong, ket qua tra ve chac chan khong huu ich).
     */
    override fun showDictionaryLookup(word: String) {
        if (word.isBlank()) return
        val trimmed = word.trim().take(MAX_LOOKUP_WORD_LENGTH)
        LookupBottomSheetFragment.forDictionary(trimmed).show(supportFragmentManager, "lookup_dictionary")
    }

    /**
     * Cho phep dich ca doan dai hon tu dien, nhung van gioi han o [MAX_LOOKUP_TRANSLATE_LENGTH] ky
     * tu de tranh goi API voi input qua lon (DeepL free tier tinh phi theo ky tu; endpoint khong
     * chinh thuc cua Google cung co the tu choi request qua dai).
     */
    override fun showTranslation(text: String) {
        if (text.isBlank()) return
        val trimmed = text.trim().take(MAX_LOOKUP_TRANSLATE_LENGTH)
        LookupBottomSheetFragment.forTranslate(trimmed).show(supportFragmentManager, "lookup_translate")
    }

    override fun selectionScope() = lifecycleScope

    /** Do tim ViewPager noi bo ma EpubNavigatorFragment dung de chuyen trang o che do
     * paginated -- Readium Kotlin Toolkit 3.1.2 khong expose field nay qua API public, nen
     * phai duyet cay View cong khai (ViewGroup.getChildAt(), khong phai reflection field
     * private). Tra ve null an toan neu khong tim thay (vi du Readium doi kien truc o ban
     * sau) -- goi code phai luon xu ly null, KHONG duoc gia dinh luon tim thay.
     *
     * QUAN TRONG (da xac minh bang cach doc source Readium Kotlin Toolkit 3.1.2 -- xem
     * EpubNavigatorFragment.kt, field `resourcePager: R2ViewPager`): EpubNavigatorFragment
     * KHONG dung androidx.viewpager2.widget.ViewPager2 nhu code cu o day tung gia dinh. No
     * dung mot class noi bo ten R2ViewPager (ke thua R2RTLViewPager, ke thua truc tiep
     * androidx.viewpager.widget.ViewPager -- ban ViewPager "v1" cu, dua tren PagerAdapter,
     * KHONG dua tren RecyclerView). Vi R2ViewPager la `internal class` cua module
     * readium-navigator nen app khong the tham chieu ten class do, nhung van detect duoc
     * qua sieu lop CONG KHAI cua no bang toan tu `is`. Truoc ban fix nay, ham nay tim
     * ViewPager2 -- thu KHONG BAO GIO ton tai trong cay view cua EpubNavigatorFragment -- nen
     * applyPageTurnAnimation() luon retry het MAX_APPLY_PAGE_TURN_RETRIES lan roi bo cuoc,
     * syncCurlOverlay() khong bao gio duoc goi, va hieu ung "cong trang that"
     * (PageCurlOverlayView) khong bao gio kich hoat duoc, o moi thiet bi, moi lan mo sach --
     * day chinh la nguyen nhan cua log "van khong tim thay ViewPager2 sau 8 lan thu". */
    private fun findInnerViewPager(root: View): androidx.viewpager.widget.ViewPager? {
        if (root is androidx.viewpager.widget.ViewPager) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findInnerViewPager(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /** Ap dung lai PageTransformer hien tai (theo [pageTurnAnimation]) len ViewPager noi bo
     * cua navigatorFragment -- goi 1 lan ngay sau khi fragment duoc attach, VA moi khi nguoi
     * dung doi lua chon trong Cai dat (xem ReaderSettingsBehaviorPage). Chi co tac dung o che
     * do paginated -- khi scrollModeEnabled = true, ViewPager noi bo cua Readium khong duoc
     * dung de cuon nen set PageTransformer khong co y nghia (khong hai gi neu van goi, chi
     * don gian la vo tac dung). */
    private fun applyPageTurnAnimation(retryCount: Int = 0) {
        val nav = navigatorFragment ?: return
        val navView = nav.view ?: return
        val pager = findInnerViewPager(navView)
        if (pager == null) {
            // QUAN TRONG: ngay sau commitNow() them EpubNavigatorFragment, nav.view da ton tai
            // nhung ViewPager noi bo cua no thuong CHUA duoc tao xong trong cung 1 frame (xem
            // log thuc te: dong nay luon in ra o lan goi dau tien tu onEach fragment-attach, tuc
            // la ca setPageTransformer lan syncCurlOverlay chua bao gio chay -- nguoi dung nhin
            // thay hieu ung "truot" la hieu ung MAC DINH cua rieng ViewPager khi chua co
            // PageTransformer nao duoc set, KHONG phai SlidePageTransformer/curl cua minh). Thay
            // vi bo cuoc ngay, thu lai vai lan voi delay ngan cho toi khi ViewPager xuat hien.
            if (retryCount < MAX_APPLY_PAGE_TURN_RETRIES) {
                Logger.d("applyPageTurnAnimation: chua tim thay ViewPager (lan $retryCount), thu lai sau ${APPLY_PAGE_TURN_RETRY_DELAY_MS}ms")
                navView.postDelayed(
                    { applyPageTurnAnimation(retryCount + 1) },
                    APPLY_PAGE_TURN_RETRY_DELAY_MS,
                )
            } else {
                Logger.d("applyPageTurnAnimation: van khong tim thay ViewPager sau $retryCount lan thu -- Readium co the da doi kien truc, bo qua hieu ung lat trang.")
            }
            return
        }
        // reverseDrawingOrder=false: giu thu tu ve mac dinh (trang duoi cung ve truoc) - dung
        // voi cach SlidePageTransformer tinh alpha/translationX theo position ben duoi.
        pager.setPageTransformer(
            false,
            when (pageTurnAnimation) {
                net.mrphan.calibre.reader.epub.PageTurnAnimation.SLIDE ->
                    net.mrphan.calibre.reader.epub.SlidePageTransformer()
                // CURL khong con dung PageTransformer nua (xem PageCurlOverlayView) - van tra
                // null o day de ViewPager khong tu ap dung hieu ung phang cu cua no chong len
                // overlay cong trang that ben tren.
                net.mrphan.calibre.reader.epub.PageTurnAnimation.CURL -> null
                net.mrphan.calibre.reader.epub.PageTurnAnimation.NONE -> null
            },
        )
        syncCurlOverlay(pager, navView, nav)
    }

    /** Gan/go net.mrphan.calibre.reader.epub.curl.PageCurlOverlayView tuy theo
     * [pageTurnAnimation]/[scrollModeEnabled] hien tai. Goi lai moi khi [applyPageTurnAnimation]
     * chay - tuc la ca khi nguoi dung doi lua chon trong Cai dat, lan khi fragment moi attach.
     *
     * QUAN TRONG (crash thuc te tren thiet bi, xem EXCEPTION "Views added to a
     * FragmentContainerView must be associated with a Fragment" trong log): navView.parent
     * KHONG PHAI mot ViewGroup thuong nhu comment cu (va ca code cu) gia dinh -- do la chinh
     * androidx.fragment.app.FragmentContainerView co id epub_navigator_container trong
     * activity_reader_epub.xml, noi EpubNavigatorFragment duoc add() vao (xem
     * setUpNavigatorFragmentFactory). FragmentContainerView.addView() tu API 28 CHU DONG throw
     * IllegalStateException voi bat ky View nao khong phai la view goc cua 1 Fragment - PageCurlOverlayView
     * la custom View thuong nen KHONG THE them truc tiep vao do. Phai leo len 1 cap nua: them
     * overlay lam sibling cua CHINH epub_navigator_container, trong FrameLayout goc cua
     * activity_reader_epub.xml (khong dong cham gi toi file XML do) -- FragmentContainerView do
     * da match_parent trong FrameLayout goc nen overlay MATCH_PARENT o day van trung khop chinh
     * xac voi vung ve cua trang sach. Chen ngay sau index cua epub_navigator_container (khong
     * phai addView() mac dinh len tren cung) de overlay nam DUOI view_reader_chrome_overlay/
     * TtsMiniPlayerView/view_reader_toc_panel (cac include phia sau trong XML) - dam bao khi
     * curl dang keo, toolbar/mini-player/panel muc luc van hien thi va bam duoc binh thuong. */
    private fun syncCurlOverlay(
        pager: androidx.viewpager.widget.ViewPager,
        navView: View,
        nav: EpubNavigatorFragment,
    ) {
        val fragmentContainer = navView.parent as? android.view.ViewGroup ?: return
        val parent = fragmentContainer.parent as? android.view.ViewGroup ?: return
        if (pageTurnAnimation != net.mrphan.calibre.reader.epub.PageTurnAnimation.CURL || scrollModeEnabled) {
            curlOverlayView?.let { parent.removeView(it) }
            curlOverlayView = null
            // Tra ve mac dinh khi khong con dung curl nua, tranh giu them 2 fragment/WebView
            // khong can thiet cho SLIDE/NONE.
            pager.offscreenPageLimit = 1
            return
        }
        // QUAN TRONG: ViewPager (v1, PagerAdapter-based) mac dinh CUNG chi giu 1 trang ke moi
        // ben (offscreenPageLimit=1 la default cua chinh no) nen dong nay chu yeu de tuong minh
        // + phong truong hop code khac lo doi gia tri nay noi khac. Neu offscreenPageLimit=0,
        // PageBitmapCapture.capture() cho trang ke se LUON tra ve null (fragment trang ke chua
        // duoc instantiateItem()) => nextBitmap luon null => overlay tu huy gesture ngay
        // ACTION_MOVE dau tien (xem PageCurlOverlayView) => nhin nhu hieu ung curl khong bao
        // gio chay, du trang van lat binh thuong ben duoi.
        pager.offscreenPageLimit = 1
        val existing = curlOverlayView
        if (existing != null) {
            // Neu EpubNavigatorFragment da bi tao lai (vd xoay man hinh, doi chuong) thi parent
            // cu cua overlay co the da bi huy cung view cu -- luon cap nhat lai host de tro
            // dung pager/navView moi, va chi tao View moi neu view cu khong con dinh dung cay.
            existing.host = curlOverlayHost(pager, nav)
            if (existing.parent !== parent) {
                (existing.parent as? android.view.ViewGroup)?.removeView(existing)
                val insertIndex = parent.indexOfChild(fragmentContainer) + 1
                parent.addView(existing, insertIndex)
            }
            // pager co the la 1 INSTANCE MOI (fragment/navigator vua bi tao lai) du overlay duoc
            // tai su dung - dang ky lai (co dedup guard) de dam bao pager moi nay cung duoc theo
            // doi, xem kdoc registerCurlResetListenerOnce.
            registerCurlResetListenerOnce(pager)
            return
        }
        val overlay = net.mrphan.calibre.reader.epub.curl.PageCurlOverlayView(this).apply {
            host = curlOverlayHost(pager, nav)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        val insertIndex = parent.indexOfChild(fragmentContainer) + 1
        parent.addView(overlay, insertIndex)
        curlOverlayView = overlay
        registerCurlResetListenerOnce(pager)
        Logger.d("syncCurlOverlay: da gan PageCurlOverlayView vao parent=$parent, offscreenPageLimit=${pager.offscreenPageLimit}")
    }

    /** Dang ky (chi 1 lan cho moi instance ViewPager, xem [pagersWithCurlResetListener]) 1
     * OnPageChangeListener goi [PageCurlOverlayView.resetImmediately] moi khi trang doi - sua loi
     * "chong chap 2 trang" khi TTS dang doc (xem kdoc PageCurlOverlayView#resetImmediately):
     * pager.currentItem co the doi vi NHIEU ly do KHAC voi chinh cu keo/commit cua overlay nay
     * (TTS autoFollow nhay locator, nguoi dung bam muc luc/ket qua tim kiem/highlight, auto-scroll
     * - xem cac noi goi thang navigatorFragment.go()/goForward()/goBackward() trong Activity nay).
     * An toan goi du la ket qua tu chinh overlay tu commit (xem kdoc resetImmediately). */
    private fun registerCurlResetListenerOnce(pager: androidx.viewpager.widget.ViewPager) {
        if (pagersWithCurlResetListener[pager] == true) return
        pager.addOnPageChangeListener(object : androidx.viewpager.widget.ViewPager.OnPageChangeListener {
            override fun onPageScrollStateChanged(state: Int) = Unit
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) = Unit
            override fun onPageSelected(position: Int) {
                curlOverlayView?.resetImmediately()
                reapplyReaderBackgroundSoon()
            }
        })
        pagersWithCurlResetListener[pager] = true
    }

    private fun curlOverlayHost(
        pager: androidx.viewpager.widget.ViewPager,
        nav: EpubNavigatorFragment,
    ): net.mrphan.calibre.reader.epub.curl.PageCurlOverlayView.Host =
        object : net.mrphan.calibre.reader.epub.curl.PageCurlOverlayView.Host {
            override fun isCurlEnabled() =
                pageTurnAnimation == net.mrphan.calibre.reader.epub.PageTurnAnimation.CURL && !scrollModeEnabled

            override fun captureCurrentBitmap() =
                net.mrphan.calibre.reader.epub.curl.PageBitmapCapture.capture(
                    nav,
                    pager.currentItem,
                    ::drawReaderBackgroundOnCanvas,
                )

            override fun captureNeighbourBitmap(delta: Int) =
                net.mrphan.calibre.reader.epub.curl.PageBitmapCapture.captureShiftedViewport(
                    nav,
                    pager.currentItem,
                    delta,
                    ::drawReaderBackgroundOnCanvas,
                ) ?: net.mrphan.calibre.reader.epub.curl.PageBitmapCapture.captureShiftedViewport(
                    nav,
                    pager.currentItem,
                    -delta,
                    ::drawReaderBackgroundOnCanvas,
                ) ?: net.mrphan.calibre.reader.epub.curl.PageBitmapCapture.capture(
                    nav,
                    pager.currentItem,
                    ::drawReaderBackgroundOnCanvas,
                )

            override fun drawReaderBackground(canvas: Canvas, width: Int, height: Int) {
                drawReaderBackgroundOnCanvas(canvas, width, height)
            }

            override fun onCurlCommitted(forward: Boolean) {
                // QUAN TRONG: goi thang navigatorFragment, KHONG goi override goForward()/
                // goBack() o duoi - nhung ham do se kiem tra lai isCurlEnabled() va tu goi
                // overlay.animateAutomatic() mot lan nua, tao vong lap vo han vi onCurlCommitted
                // chinh la ket qua cua mot lan animate da hoan tat.
                try {
                    if (forward) {
                        navigatorFragment?.goForward(animated = true)
                    } else {
                        navigatorFragment?.goBackward(animated = true)
                    }
                } catch (e: Exception) {
                    Logger.e(e)
                }
            }
        }

    /** Re-reads every saved highlight for this book and re-renders them as Readium
     * [Decoration]s -- called after any add/edit/delete so the on-page rendering never drifts
     * from what [ReaderHighlightsSheet] and the DB show. Uses the "highlight" decoration style,
     * which every navigator that implements [DecorableNavigator] is required to support out of
     * the box (unlike custom styles, which need `decorationTemplates` registered up front). */
    private fun loadAndApplyHighlights(nav: EpubNavigatorFragment) {
        lifecycleScope.launch {
            val cursor = mDB.getAnnotations(uuid, "highlight")
            val annotations = try {
                ReaderAnnotation.readAll(cursor)
            } finally {
                cursor.close()
            }
            Logger.d("loadAndApplyHighlights: ${annotations.size} annotation row(s) in DB for this book")
            val decorations = annotations.mapNotNull { annotation ->
                val locator = decodeLocator(annotation.locatorJson)
                if (locator == null) {
                    Logger.d("loadAndApplyHighlights: annotation id=${annotation.id} failed decodeLocator(), skipped")
                    return@mapNotNull null
                }
                // decodeColorStyle() reverses encodeColorStyle() from addHighlight() above -- old
                // rows (created before the style option existed) have no "_u" suffix and decode
                // as (color, FILL), rendering exactly as they always did.
                val (highlightColor, highlightStyle) = decodeColorStyle(annotation.color)
                val decorationStyle = when (highlightStyle) {
                    ReaderHighlightStyle.UNDERLINE -> Decoration.Style.Underline(tint = highlightColor.argb)
                    ReaderHighlightStyle.FILL -> Decoration.Style.Highlight(tint = highlightColor.argb)
                }
                Decoration(
                    id = "highlight-${annotation.id}",
                    locator = locator,
                    style = decorationStyle,
                )
            }
            Logger.d("loadAndApplyHighlights: applying ${decorations.size} decoration(s)")
            try {
                nav.applyDecorations(decorations, HIGHLIGHT_DECORATION_GROUP)
                Logger.d("loadAndApplyHighlights: applyDecorations() completed without throwing")
            } catch (e: Exception) {
                Logger.d("loadAndApplyHighlights: applyDecorations() THREW")
                Logger.e(e)
            }
        }
    }

    /** Goi tu ReaderSettingsTtsPage khi nguoi dung tat cong tac "To sang cau dang doc", de xoa
     * ngay highlight dang hien thi thay vi doi den khi TtsController doi sang cau ke tiep (co
     * the mat vai giay neu toc do doc cham). Cung duoc goi khi nguoi dung bam dong han "Doc cho
     * nghe" (xem stopReadAloud()). Logic thuc su nam trong TtsSessionCoordinator, xem prompt
     * nang cap reader/tts muc 5. */
    internal fun clearTtsSentenceHighlightNow() {
        ttsSessionCoordinator.clearHighlightNow()
    }

    private fun themeToReadiumTheme(mode: ReaderThemeMode): Theme = when (mode) {
        ReaderThemeMode.LIGHT -> Theme.LIGHT
        ReaderThemeMode.DARK -> Theme.DARK
    }

    // --- Display-settings persistence (font size / line height / font family) ---------------
    // Fixes the "cỡ chữ không được lưu, phải chỉnh lại mỗi lần mở sách" bug described in the
    // prompt (section 4.1): previously font size lived only in an in-memory field and was never
    // read back on the next EpubReaderActivity instance. Persisted under this project's usual
    // PreferenceManager-backed SharedPreferences (see OPDSBrowserFragment's `mSettings` for the
    // existing convention this follows) -- deliberately reader-wide, not per-book, matching how
    // `currentFontSizeMultiplier`'s old in-memory default was already reader-wide, not per-book.

    private fun restoreDisplaySettings() {
        currentFontSizeMultiplier = settingsPrefs.getFloat(PREF_READER_FONT_SIZE, 1.0f).toDouble()
        currentLineHeight = settingsPrefs.getFloat(PREF_READER_LINE_HEIGHT, 1.2f).toDouble()
        setCurrentFontChoice(ReaderFontChoice.find(settingsPrefs.getString(PREF_READER_FONT_FAMILY, null)))
        autoScrollSpeedLevel = settingsPrefs.getInt(PREF_READER_AUTO_SCROLL_SPEED, AUTO_SCROLL_SPEED_MEDIUM)
    }

    private fun persistDisplaySettings() {
        settingsPrefs.edit()
            .putFloat(PREF_READER_FONT_SIZE, currentFontSizeMultiplier.toFloat())
            .putFloat(PREF_READER_LINE_HEIGHT, currentLineHeight.toFloat())
            .putString(PREF_READER_FONT_FAMILY, currentFontChoiceId().takeUnless { it == ReaderFontChoice.DEFAULT_ID })
            .apply()
    }

    /** Restores the Layout tab's fields (prompt-nang-cap-reader-settings-tabbed.md muc 5.2) plus
     * the Font tab's font-weight/publisher-styles-override -- called once from `onCreate()` right
     * next to [restoreDisplaySettings], same reasoning as that function's own kdoc: applying this
     * at `initialPreferences` time (see [buildCurrentEpubPreferences]) means the very first frame
     * already reflects it, not just after the user reopens the sheet. */
    private fun restoreLayoutSettings() {
        publisherStylesOverrideEnabled = settingsPrefs.getBoolean(PREF_READER_PUBLISHER_STYLES_OVERRIDE, false)
        currentFontWeight = settingsPrefs.getFloat(PREF_READER_FONT_WEIGHT, 400f).toDouble()
        currentWordSpacing = settingsPrefs.getFloat(PREF_READER_WORD_SPACING, 0f).toDouble()
        currentLetterSpacing = settingsPrefs.getFloat(PREF_READER_LETTER_SPACING, 0f).toDouble()
        currentParagraphSpacing = settingsPrefs.getFloat(PREF_READER_PARAGRAPH_SPACING, 0f).toDouble()
        currentParagraphIndent = settingsPrefs.getFloat(PREF_READER_PARAGRAPH_INDENT, 0f).toDouble()
        currentPageMargins = settingsPrefs.getFloat(PREF_READER_PAGE_MARGINS, 1.0f).toDouble()
        twoColumnEnabled = settingsPrefs.getBoolean(PREF_READER_TWO_COLUMN, false)
        justifyTextEnabled = settingsPrefs.getBoolean(PREF_READER_JUSTIFY_TEXT, false)
        hyphensEnabled = settingsPrefs.getBoolean(PREF_READER_HYPHENS, false)
    }

    private fun persistLayoutSettings() {
        settingsPrefs.edit()
            .putBoolean(PREF_READER_PUBLISHER_STYLES_OVERRIDE, publisherStylesOverrideEnabled)
            .putFloat(PREF_READER_FONT_WEIGHT, currentFontWeight.toFloat())
            .putFloat(PREF_READER_WORD_SPACING, currentWordSpacing.toFloat())
            .putFloat(PREF_READER_LETTER_SPACING, currentLetterSpacing.toFloat())
            .putFloat(PREF_READER_PARAGRAPH_SPACING, currentParagraphSpacing.toFloat())
            .putFloat(PREF_READER_PARAGRAPH_INDENT, currentParagraphIndent.toFloat())
            .putFloat(PREF_READER_PAGE_MARGINS, currentPageMargins.toFloat())
            .putBoolean(PREF_READER_TWO_COLUMN, twoColumnEnabled)
            .putBoolean(PREF_READER_JUSTIFY_TEXT, justifyTextEnabled)
            .putBoolean(PREF_READER_HYPHENS, hyphensEnabled)
            .apply()
    }

    private fun progressToFontSize(progress: Int): Double =
        (FONT_SIZE_MIN + progress * FONT_SIZE_STEP).coerceIn(FONT_SIZE_MIN, FONT_SIZE_MAX)

    private fun fontSizeToProgress(fontSize: Double): Int =
        ((fontSize - FONT_SIZE_MIN) / FONT_SIZE_STEP).toInt().coerceIn(0, 25)

    private fun progressToLineHeight(progress: Int): Double =
        (LINE_HEIGHT_MIN + progress * LINE_HEIGHT_STEP).coerceIn(LINE_HEIGHT_MIN, LINE_HEIGHT_MAX)

    private fun lineHeightToProgress(lineHeight: Double): Int =
        ((lineHeight - LINE_HEIGHT_MIN) / LINE_HEIGHT_STEP).toInt().coerceIn(0, 16)

    override fun openInspector(): Boolean = false

    override fun resetInterfaceDefaults(): Boolean {
        chromeOverlay.refreshActions()
        return true
    }

    /**
     * "Nạp lại" (reload): re-reads the currently-open book file from disk into a fresh
     * navigator/publication, picking back up at the last-saved locator. Deliberately does NOT use
     * `recreate()` -- even though `onCreate()`'s `shouldAddFragment` no longer crashes on a
     * non-null `savedInstanceState` (see the fix described there), starting a brand-new Activity
     * instance with the same Intent is still simpler and cheaper here: it always takes the plain
     * "tap a book to open it" path with a clean `savedInstanceState == null`-shaped state, with no
     * dependency on exactly what FragmentManager did or didn't restore.
     */
    override fun reloadBook(): Boolean {
        if (::readingTimeTracker.isInitialized) readingTimeTracker.flush()
        val restartIntent = Intent(this, EpubReaderActivity::class.java).apply {
            putExtras(intent)
            removeExtra(ReaderActivity.EXTRA_COVER_PATH)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        startActivity(restartIntent)
        finish()
        overridePendingTransition(0, 0)
        return true
    }

    override fun openBookPicker(): Boolean {
        startActivity(android.content.Intent(this, MainActivity::class.java))
        finish()
        return true
    }

    override fun openMetadataEditor(): Boolean {
        startActivity(
            android.content.Intent(this, MetadataEditorActivity::class.java)
                .putExtra(MetadataEditorActivity.EXTRA_UUID, uuid)
        )
        return true
    }

    override fun isReadAloudActive(): Boolean = ttsMiniPlayer?.isVisible == true

    private fun stopReaderBackgroundMusicForTts() {
        if (::readerBackgroundMusicController.isInitialized) {
            readerBackgroundMusicController.stop()
        }
    }

    override fun toggleReadAloud(): Boolean {
        val extractor = textExtractor ?: return false
        val pub = publication ?: return false
        stopReaderBackgroundMusicForTts()
        val controller = ttsController
        if (controller != null &&
            (controller.state.value.isPlaying || controller.state.value.sentenceCount > 0)
        ) {
            // A reading session is already active -- tapping the toolbar button again just
            // shows/hides the mini player, it does not reset playback.
            ttsMiniPlayer?.toggleVisibility()
            return true
        }
        val currentHref = lastLocator?.href?.toString()?.substringBefore('#')
        val currentLink = pub.readingOrder.firstOrNull { it.href.toString() == currentHref }
            ?: pub.readingOrder.firstOrNull()
            ?: return false
        // Start the Service in the foreground BEFORE playback begins -- binding alone is not
        // enough to survive this Activity going away, since a plain bound-only Service is killed
        // as soon as the last client unbinds. See TtsPlaybackService's kdoc.
        ContextCompat.startForegroundService(this, Intent(this, TtsPlaybackService::class.java))
        lifecycleScope.launch {
            val engine = TtsEngineRegistry.byId(ttsSettingsStore.getSelectedEngineId() ?: "")
                ?: TtsEngineRegistry.default()
            val initialized = engine.initialize(applicationContext)
            if (!initialized) {
                showMessage(getString(R.string.reader_tts_init_failed))
                return@launch
            }
            // The service may take one binder round-trip to connect after
            // startForegroundService() above -- wait briefly for it rather than assuming it's
            // already bound.
            var attempts = 0
            while (ttsService == null && attempts < 50) {
                delay(20)
                attempts++
            }
            val activeController = ttsController ?: return@launch
            activeController.setEngine(engine, applicationContext)

            val text = try {
                extractor.extractText(currentLink)
            } catch (e: Exception) {
                Logger.e(e)
                showMessage(getString(R.string.reader_error_open_failed))
                return@launch
            }
            activeController.startChapter(
                TtsController.ChapterText(
                    href = currentLink.href.toString(),
                    title = currentLink.title ?: bookTitle,
                    text = text,
                )
            )
            ttsService?.ensureForeground(activeController.state.value)
            ttsMiniPlayer?.show()
        }
        return true
    }

    /** Yêu cầu 2 của prompt-codex-nangcap-now-playing.md: bấm vào "câu đang đọc" trong
     * TtsPlayerBottomSheet phải khiến câu TTS hiện tại được tô sáng + cuộn tới NGAY LẬP TỨC,
     * đúng 1 lần, bất kể 2 setting [TtsSettingsStore.getHighlightCurrentSentence]/
     * [TtsSettingsStore.getAutoFollowCurrentSentence] đang bật hay tắt -- đây là hành động người
     * dùng CHỦ ĐỘNG yêu cầu xem câu đó nằm ở đâu trong sách, khác với hành vi tự động thường
     * xuyên của [ttsSessionCoordinator] (vẫn tiếp tục chạy song song không đổi sau lần ép này,
     * xem kdoc của coordinator). KHÔNG tự ý bật 2 setting đó lên -- không ghi đè lựa chọn cá nhân
     * của người dùng trong Settings.
     *
     * Dùng được từ 2 nơi: (a) [onCreate], khi Activity được mở lại bằng
     * [EXTRA_FOCUS_CURRENT_TTS_SENTENCE] từ một màn hình khác (vd Device Library) -- lúc đó
     * publication/navigatorFragment/ttsService đều CHƯA sẵn sàng ngay, nên tự poll ngắn (cùng
     * pattern với [startAudioSessionIfNeeded]) thay vì yêu cầu caller tự canh thời điểm gọi; và
     * (b) trực tiếp từ [TtsPlayerBottomSheet] khi dialog đang mở ngay trên chính Activity của
     * đúng cuốn sách đang nghe -- lúc đó mọi thứ đã sẵn sàng nên vòng lặp bên dưới thoát ngay ở
     * lần kiểm tra đầu tiên. */
    internal fun focusCurrentTtsSentence() {
        lifecycleScope.launch {
            var attempts = 0
            while ((ttsService == null || navigatorFragment == null || publication == null) && attempts < 100) {
                delay(20)
                attempts++
            }
            val service = ttsService ?: return@launch
            val nav = navigatorFragment ?: return@launch
            val pub = publication ?: return@launch
            // Phien TTS dang active KHONG phai cua cuon sach nay (vd nguoi dung da chuyen sang
            // nghe cuon khac giua chung, hoac phien da ket thuc) -- khong co gi de to sang.
            if (service.nowPlayingUuid != uuid) return@launch

            var stateAttempts = 0
            var state = service.ttsController.state.value
            while (state.sentenceCount <= 0 && stateAttempts < 100) {
                delay(20)
                state = service.ttsController.state.value
                stateAttempts++
            }
            if (state.sentenceCount <= 0 || service.nowPlayingUuid != uuid) return@launch

            val locator = TtsSentenceHighlighter.buildLocator(pub, state) ?: return@launch
            TtsSentenceHighlighter.apply(nav, locator)
            // BUG DA SUA: khi TtsPlayerBottomSheet dang mo tren CHINH Activity dang o
            // audio-session (mo tu TtsChapterListActivity qua startAudioSessionIfNeeded(), noi
            // contentRoot.visibility bi dat INVISIBLE luc onCreate va truoc gio KHONG bao gio
            // duoc phuc hoi lai -- xem comment o do), nhanh "toi uu" trong
            // TtsPlayerBottomSheet.sentenceText.setOnClickListener() chi goi thang
            // focusCurrentTtsSentence() tren instance Activity co san ma khong lam gi voi
            // contentRoot -- ket qua la nguyen trang doc van AN, nguoi dung chi thay "trang den"
            // dang sau dialog vua dismiss du sentence highlight/nav.go da chay dung. Phai hien lai
            // contentRoot o day: day chinh la "mo trang doc thuong" ma kdoc ham nay mo ta, nen
            // luon dam bao noi dung thuc su hien ra bat ke Activity dang o che do nao khi ham nay
            // duoc goi.
            if (::contentRoot.isInitialized) contentRoot.visibility = View.VISIBLE
            try {
                nav.go(locator, animated = true)
            } catch (e: Exception) {
                Logger.e(e)
            }
        }
    }

    /** "Nghe sách" (Nâng cấp TTS chapter): when opened via [EXTRA_OPEN_AUDIO_MODE] from
     * TtsChapterListActivity, starts playback immediately at [audioSessionChapterHref] (resuming
     * from any previously saved TtsChapterProgressStore position), then opens the full "Now
     * playing" player right away -- unlike [toggleReadAloud], which just shows/expands the mini
     * player. Back out of that player finishes this Activity (see the onBackPressedDispatcher
     * callback registered in onCreate), returning the user to the chapter list rather than the
     * normal text reader. */
    private fun startAudioSessionIfNeeded() {
        if (!audioSessionMode) return
        val href = audioSessionChapterHref ?: return
        val pub = publication ?: return
        val extractor = textExtractor ?: return
        val link = pub.readingOrder.firstOrNull { it.href.toString() == href }
            ?: pub.readingOrder.firstOrNull() ?: return

        stopReaderBackgroundMusicForTts()
        ContextCompat.startForegroundService(this, Intent(this, TtsPlaybackService::class.java))
        lifecycleScope.launch {
            var attempts = 0
            while (ttsService == null && attempts < 50) {
                delay(20)
                attempts++
            }
            val boundService = ttsService ?: return@launch

            // Mo "Now playing" NGAY sau khi service da bind xong, TRUOC khi khoi tao engine TTS/
            // trich xuat text/bat dau chuong: TtsPlayerBottomSheet.show() da tu lang nghe
            // controller.state kieu reactive nen khong can du lieu chuong san sang tai thoi diem
            // goi show(), no se tu cap nhat dan len man hinh da mo san khi cac buoc phia sau xong.
            // Xem prompt-fix-tts-now-playing-mo-truoc-audio-sau.md. Giu tham chieu dialog de co
            // the dong lai neu cac buoc khoi tao phia sau that bai.
            val nowPlayingDialog = TtsPlayerBottomSheet.show(
                this@EpubReaderActivity, boundService, isAudioSession = true,
            ) {
                finish()
            }

            val engine = TtsEngineRegistry.byId(ttsSettingsStore.getSelectedEngineId() ?: "")
                ?: TtsEngineRegistry.default()
            if (!engine.initialize(applicationContext)) {
                if (!isFinishing) nowPlayingDialog.dismiss()
                showMessage(getString(R.string.reader_tts_init_failed))
                return@launch
            }
            val activeController = ttsController ?: run {
                if (!isFinishing) nowPlayingDialog.dismiss()
                return@launch
            }
            activeController.setEngine(engine, applicationContext)

            try {
                navigatorFragment?.go(link, animated = false)
            } catch (e: Exception) {
                Logger.e(e)
            }

            val text = try {
                extractor.extractText(link)
            } catch (e: Exception) {
                Logger.e(e)
                if (!isFinishing) nowPlayingDialog.dismiss()
                showMessage(getString(R.string.reader_error_open_failed))
                return@launch
            }
            val savedProgress = withContext(Dispatchers.IO) {
                net.mrphan.calibre.reader.tts.TtsChapterProgressStore(applicationContext)
                    .getProgress(uuid, href)
            }
            activeController.startChapter(
                TtsController.ChapterText(href = href, title = link.title ?: bookTitle, text = text),
                startSentenceIndex = savedProgress?.sentenceIndex ?: 0,
            )
            ttsService?.nowPlayingUuid = uuid
            ttsService?.ensureForeground(activeController.state.value)
        }
    }

    companion object {
        private const val EPUB_NAVIGATOR_FRAGMENT_TAG = "epub_navigator"
        private const val FONT_SIZE_STEP = 0.1
        private const val FONT_SIZE_MIN = 0.5
        private const val FONT_SIZE_MAX = 3.0
        private const val LINE_HEIGHT_STEP = 0.1
        private const val LINE_HEIGHT_MIN = 1.0
        private const val LINE_HEIGHT_MAX = 2.6

        // applyPageTurnAnimation() thu lai toi da chung nay lan, moi lan cach nhau chung nay ms,
        // cho ViewPager noi bo cua EpubNavigatorFragment kip duoc tao xong sau khi fragment
        // attach (xem kdoc applyPageTurnAnimation). 8 x 150ms = 1.2s la du du cho hau het thiet
        // bi, ke ca thiet bi yeu.
        private const val MAX_APPLY_PAGE_TURN_RETRIES = 8
        private const val APPLY_PAGE_TURN_RETRY_DELAY_MS = 150L

        private const val PREF_READER_FONT_SIZE = "reader_font_size"
        private const val PREF_READER_LINE_HEIGHT = "reader_line_height"
        private const val PREF_READER_FONT_FAMILY = "reader_font_family"
        private const val PREF_READER_SCROLL_MODE = "reader_scroll_mode"
        private const val PREF_READER_PAGE_TURN_ANIMATION = "reader_page_turn_animation"
        private const val PREF_READER_IMMERSIVE_MODE = "reader_immersive_mode"
        private const val PREF_READER_THEME_MODE = "reader_theme_mode"
        private const val PREF_READER_AUTO_SCROLL_SPEED = "reader_auto_scroll_speed"
        private const val PREF_READER_COLOR_THEME = "reader_color_theme"
        private const val PREF_READER_SCREEN_BRIGHTNESS = "reader_screen_brightness"
        private const val PREF_READER_BACKGROUND_IMAGE_OPACITY = "reader_background_image_opacity"
        private const val DEFAULT_READER_BACKGROUND_IMAGE_OPACITY = 50

        // --- Reader Settings sheet: Layout tab (prompt-nang-cap-reader-settings-tabbed.md muc
        // 5.2) + Behavior tab's keep-screen-on (muc 5.3) -----------------------------------------
        private const val PREF_READER_PUBLISHER_STYLES_OVERRIDE = "reader_publisher_styles_override"
        private const val PREF_READER_FONT_WEIGHT = "reader_font_weight"
        private const val PREF_READER_WORD_SPACING = "reader_word_spacing"
        private const val PREF_READER_LETTER_SPACING = "reader_letter_spacing"
        private const val PREF_READER_PARAGRAPH_SPACING = "reader_paragraph_spacing"
        private const val PREF_READER_PARAGRAPH_INDENT = "reader_paragraph_indent"
        private const val PREF_READER_PAGE_MARGINS = "reader_page_margins"
        private const val PREF_READER_TWO_COLUMN = "reader_two_column"
        private const val PREF_READER_JUSTIFY_TEXT = "reader_justify_text"
        private const val PREF_READER_HYPHENS = "reader_hyphens"
        private const val PREF_READER_KEEP_SCREEN_ON = "reader_keep_screen_on"
        private const val PREF_READER_SCREEN_ORIENTATION = "reader_screen_orientation"
        private const val PREF_READER_VOLUME_KEY_PAGE_TURN = "reader_volume_key_page_turn"
        private const val PREF_READER_VOLUME_KEY_INVERT = "reader_volume_key_invert"
        private const val PREF_READER_EDGE_TAP_PAGE_TURN = "reader_edge_tap_page_turn"
        private const val PREF_READER_EDGE_TAP_INVERT = "reader_edge_tap_invert"

        const val AUTO_SCROLL_SPEED_SLOW = 0
        const val AUTO_SCROLL_SPEED_MEDIUM = 1
        const val AUTO_SCROLL_SPEED_FAST = 2

        private const val HIGHLIGHT_DECORATION_GROUP = "highlights"
        private const val HIGHLIGHT_EXCERPT_MAX_LENGTH = 240

        // Tu dien: chi tra 1 tu/cum tu ngan (xem showDictionaryLookup).
        private const val MAX_LOOKUP_WORD_LENGTH = 100
        // Dich: cho phep doan dai hon nhung van co gioi han (xem showTranslation).
        private const val MAX_LOOKUP_TRANSLATE_LENGTH = 5000

        /** `null` stands for "Mặc định" (the EPUB's own stylesheet, i.e. don't override font
         * family) -- see [openFontSizeControls]'s Spinner setup, which maps that back to the
         * localized "Mặc định" label instead of a font name. Visibility widened to `internal`
         * (list contents also extended with CURSIVE/MONOSPACE, both real
         * `org.readium.r2.navigator.preferences.FontFamily` constants) so
         * [net.mrphan.calibre.reader.chrome.settings.ReaderSettingsFontPage] can build its own
         * font-family Spinner without duplicating this list. */
        internal val FONT_FAMILY_CHOICES: List<ReaderFontChoice> = ReaderFontChoice.ALL

        /** Builds an Intent that reopens this book, reusing the exact same 3 extras
         * `ReaderActivity` already defines (`EXTRA_UUID`/`EXTRA_BOOK_PATH`/`EXTRA_TITLE`) rather
         * than inventing a new Intent format -- used by [TtsPlaybackService]'s notification tap
         * target so tapping it returns to the book currently being read aloud, even if this
         * Activity instance was destroyed. */
        fun buildReopenIntent(
            context: android.content.Context,
            uuid: String,
            bookPath: String,
            title: String,
        ): Intent = Intent(context, EpubReaderActivity::class.java).apply {
            putExtra(ReaderActivity.EXTRA_UUID, uuid)
            putExtra(ReaderActivity.EXTRA_BOOK_PATH, bookPath)
            putExtra(ReaderActivity.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        /** Set when this Activity should open straight into "chế độ nghe" (audio session mode)
         * from TtsChapterListActivity, instead of the normal read-aloud toggle flow -- see
         * startAudioSessionIfNeeded(). */
        const val EXTRA_OPEN_AUDIO_MODE = "net.mrphan.calibre.reader.EXTRA_OPEN_AUDIO_MODE"
        /** href (readingOrder key) of the chapter to start listening from, picked by the user in
         * TtsChapterListActivity. */
        const val EXTRA_AUDIO_CHAPTER_HREF = "net.mrphan.calibre.reader.EXTRA_AUDIO_CHAPTER_HREF"

        /** Yêu cầu 2 của prompt-codex-nangcap-now-playing.md: đặt khi Activity này được mở lại
         * (qua [buildReopenIntent]) từ việc người dùng bấm vào "câu đang đọc" trong
         * TtsPlayerBottomSheet -- báo cho [onCreate] gọi [focusCurrentTtsSentence] một lần ngay
         * khi đủ dữ liệu, ép tô sáng + cuộn tới đúng câu TTS hiện tại bất kể 2 setting tô sáng/
         * tự động cuộn đang bật hay tắt. */
        const val EXTRA_FOCUS_CURRENT_TTS_SENTENCE =
            "net.mrphan.calibre.reader.EXTRA_FOCUS_CURRENT_TTS_SENTENCE"

        /** Builds the Intent TtsChapterListActivity uses to open a specific chapter in audio
         * session mode -- just ReaderActivity's normal EPUB intent plus the two audio-mode
         * extras above.
         *
         * @JvmStatic is required here: this is called as EpubReaderActivity.buildAudioSessionIntent(...)
         * from MainActivity#openLastActivity (Java) as well as from TtsChapterListActivity
         * (Kotlin) -- without it, Kotlin only generates an instance method on the synthesized
         * Companion class, reachable from Java as EpubReaderActivity.Companion.buildAudioSessionIntent(...),
         * not the shorter form MainActivity.java uses. Mirrors TtsChapterListActivity.buildIntent's
         * own @JvmStatic, same reason. */
        @JvmStatic
        fun buildAudioSessionIntent(
            context: android.content.Context,
            bookFile: File,
            uuid: String,
            title: String,
            chapterHref: String,
        ): Intent = ReaderActivity.buildIntent(context, bookFile, uuid, title, "epub")
            .putExtra(EXTRA_OPEN_AUDIO_MODE, true)
            .putExtra(EXTRA_AUDIO_CHAPTER_HREF, chapterHref)
    }
}

/** Small helper so onCreate() can use the `commitNow { ... }` DSL without a second import block. */
private inline fun androidx.fragment.app.FragmentManager.commitNow(
    body: androidx.fragment.app.FragmentTransaction.() -> Unit
) {
    val transaction = beginTransaction()
    transaction.body()
    transaction.commitNow()
}

/**
 * A `[min, max]` range walked in fixed-size [step]s, converting between a SeekBar's Int progress
 * (0-based) and the real Double value Readium expects (e.g. an em multiplier). Used by
 * [net.mrphan.calibre.reader.chrome.settings.ReaderSettingsFontPage]/
 * [net.mrphan.calibre.reader.chrome.settings.ReaderSettingsLayoutPage] instead of writing a
 * `progressToXxx`/`xxxToProgress` pair for each of the 6 new fields (font weight, word/letter/
 * paragraph spacing, paragraph indent, page margins), mirroring the pattern
 * [EpubReaderActivity]'s own `progressToFontSize`/`fontSizeToProgress` already used for the two
 * pre-existing fields.
 */
internal data class LinearRange(val min: Double, val max: Double, val step: Double) {
    /** Highest valid SeekBar progress (SeekBar.max) for this range. */
    val maxProgress: Int get() = ((max - min) / step).toInt()

    fun toProgress(value: Double): Int = ((value - min) / step).toInt().coerceIn(0, maxProgress)

    fun toValue(progress: Int): Double = (min + progress * step).coerceIn(min, max)
}

/** The concrete ranges for every Font/Layout tab field that isn't already covered by
 * [EpubReaderActivity]'s own font-size/line-height constants. Values are this app's own UI-range
 * choice (Readium's [org.readium.r2.navigator.epub.EpubPreferences] doesn't itself constrain or
 * publish suggested ranges for fields submitted outside its `PreferencesEditor`, same as this
 * class already does for font size/line height) -- not something read out of Readium itself. */
internal object LayoutRanges {
    /** Mirrors [EpubReaderActivity]'s own private `FONT_SIZE_MIN`/`MAX`/`STEP` exactly (0.5/3.0/
     * 0.1) -- that class's constants stay `private` (untouched), this is a deliberate duplicate so
     * the Font tab's SeekBar behaves identically to [EpubReaderActivity.openFontSizeControls]'s. */
    val FONT_SIZE = LinearRange(min = 0.5, max = 3.0, step = 0.1)

    /** Mirrors [EpubReaderActivity]'s own private `LINE_HEIGHT_MIN`/`MAX`/`STEP` (1.0/2.6/0.1). */
    val LINE_HEIGHT = LinearRange(min = 1.0, max = 2.6, step = 0.1)

    val FONT_WEIGHT = LinearRange(min = 100.0, max = 900.0, step = 100.0)
    val WORD_SPACING = LinearRange(min = 0.0, max = 1.0, step = 0.05)
    val LETTER_SPACING = LinearRange(min = 0.0, max = 0.5, step = 0.05)
    val PARAGRAPH_SPACING = LinearRange(min = 0.0, max = 2.0, step = 0.1)
    val PARAGRAPH_INDENT = LinearRange(min = 0.0, max = 3.0, step = 0.25)
    val PAGE_MARGINS = LinearRange(min = 0.5, max = 2.0, step = 0.1)
}
