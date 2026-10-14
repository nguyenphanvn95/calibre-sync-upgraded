package net.mrphan.calibre.reader.chrome

/**
 * The contract between the shared reader "chrome" (toolbar overlay + gesture handling, see
 * [ReaderChromeOverlay]) and whichever concrete reader Activity is hosting it
 * (`EpubReaderActivity`, `PdfReaderActivity`, `CbzReaderActivity`). A [ReaderAction] only ever
 * talks to a [ReaderChromeHost], never to a concrete Activity class, so new actions can be added
 * without any of the three Activities being touched (see [ReaderActionRegistry]).
 *
 * NOTE on why every capability method below is abstract (no Kotlin default body), unlike the
 * `fun goBack(): Boolean = false` style sketched in the original spec: two of the three
 * implementers (`PdfReaderActivity`, `CbzReaderActivity`) are Java classes. Kotlin interface
 * methods with a body are only real JVM `default` methods when the module is compiled with
 * `-Xjvm-default=all` (or per-method `@JvmDefault`); this project's `build.gradle.kts` does not
 * opt into that, so from Java's point of view every one of those "default" methods would still be
 * abstract, and Java classes would be forced to implement all of them anyway while getting none of
 * the readability benefit. Making everything explicitly abstract here means the exact same amount
 * of code is required either way, but the contract is honest about it in both languages, and nothing
 * silently breaks if the Kotlin compiler flag changes later. A capability that a given reader
 * doesn't support should simply `return false` (or a sensible default like 0/""), which
 * [ReaderAction] implementations already treat as "not available" and report via [showMessage].
 */
interface ReaderChromeHost {

    /** Title shown in the chrome's top bar. */
    val bookTitle: String

    /** Which concrete reader this host is -- drives [ReaderAction.isRelevantFor] filtering. */
    val readerKind: ReaderKind

    /** 0..100, used for the bottom bar's "NN%" label. */
    fun currentProgressPercent(): Int

    /** "Quit": finishes the reader Activity outright (different from [toggleChrome], which only
     * hides the toolbar and keeps the book open). */
    fun close()

    /** Shows/hides the chrome overlay. Called by [ReaderChromeOverlay] itself in response to the
     * middle-of-screen tap gesture or the bottom bar's "Đóng" button -- actions should not call
     * this directly. */
    fun toggleChrome()

    /** Lets an action (or the host's own error paths) surface a short message to the user, e.g.
     * "Tính năng ... sẽ có trong bản cập nhật sau" or "Không hỗ trợ định dạng này". Concrete hosts
     * typically implement this with a `Toast`/`Snackbar`. */
    fun showMessage(message: String)

    /** Resolves and formats a string resource (`Context#getString`) so [ReaderAction]
     * implementations -- which are plain Kotlin objects with no `Context`/`Resources` of their
     * own -- can build localized messages (e.g. the "coming soon" text for
     * [PlaceholderReaderAction]) without every action needing a Context reference. */
    fun formatString(@androidx.annotation.StringRes resId: Int, vararg formatArgs: Any): String

    /** Lets [ReaderChromeOverlay] show [ReaderMenuSheet] (the hamburger-menu bottom sheet) without
     * needing a concrete Activity reference. All three concrete hosts are `AppCompatActivity`/
     * `FragmentActivity` subclasses, so this is just `supportFragmentManager`. */
    fun fragmentManager(): androidx.fragment.app.FragmentManager

    // --- Optional capabilities -------------------------------------------------------------
    // Each returns false when the concrete reader can't perform the action (e.g. PdfReaderActivity
    // has no outline API for openTableOfContents()); ReaderAction.invoke() reports that to the user
    // via showMessage() instead of silently doing nothing.

    /** Whether immersive/fullscreen mode (system status/nav bars hidden) is currently on -- read
     * by [FullscreenAction] (a [ReaderToggleAction]) to draw its checkmark. See
     * [toggleFullscreen]. */
    fun isImmersiveModeEnabled(): Boolean

    fun goBack(): Boolean
    fun goForward(): Boolean
    fun openSearch(): Boolean
    fun openGoToLocation(): Boolean
    fun openTableOfContents(): Boolean
    fun toggleHighlightsBrowser(): Boolean
    fun toggleBookmarksPanel(): Boolean
    fun openReadingStats(): Boolean
    fun openReaderBackgroundMusic(): Boolean
    fun openFontSizeControls(): Boolean
    fun openPreferences(): Boolean

    /**
     * Mo bang "Cấu hình tạo sẵn" ([net.mrphan.calibre.reader.chrome.settings.ReaderSettingsProfilesSheet]).
     * Cung mot dang voi openPreferences(): tra false ("khong ho tro") thay vi throw/no-op tren
     * cac ReaderKind chua wiring (Pdf/Cbz), giong moi capability khac tren interface nay.
     */
    fun openReaderSettingsProfiles(): Boolean

    fun toggleFullscreen(): Boolean
    fun openInspector(): Boolean
    fun resetInterfaceDefaults(): Boolean
    fun reloadBook(): Boolean
    fun openBookPicker(): Boolean

    /**
     * Xuat chuong dang doc hien tai ra PDF qua hop thoai In cua he thong (nguoi dung chon
     * "Save as PDF" de luu vao bo nho may, hoac chon may in that). Chi co y nghia cho EPUB (xem
     * PrintAction.isRelevantFor) -- Pdf/Cbz tra false, cung ly do openColorAndContrastSettings()
     * dang tra false o do (khong phai scope cua thay doi nay).
     *
     * Bat dong bo: tra ve true ngay neu buoc chuan bi da bat dau thanh cong (giong cach
     * openTableOfContents()/toggleReadAloud() dang lam), loi xay ra sau do (vi du doc resource
     * that bai) duoc bao qua showMessage() thay vi qua gia tri tra ve nay.
     */
    fun printCurrentChapterAsPdf(): Boolean

    /** Mở MetadataEditorActivity ("Hiệu đính") cho sách đang đọc -- xem EditBookAction trong
     * ReaderActions.kt. Trả về false nếu host không biết uuid của sách đang mở (không nên xảy
     * ra trong thực tế, cả 3 reader đều nhận uuid qua Intent extra khi mở sách). */
    fun openMetadataEditor(): Boolean

    /** Toggles the "Đọc cho nghe" (read aloud / TTS) mini player -- starts a new reading session
     * from the current chapter if none is active, or shows/hides the mini player if one already
     * is (see [net.mrphan.calibre.reader.tts.TtsController]). Only `EpubReaderActivity` supports
     * this (EPUB has a text layer to read); `PdfReaderActivity`/`CbzReaderActivity` return false. */
    fun toggleReadAloud(): Boolean

    /** True if a "Đọc cho nghe" (TTS/read-aloud) session is currently active (mini player
     * showing) -- used purely to tint [net.mrphan.calibre.reader.chrome.ReadAloudAction]'s
     * footer icon like Readest highlights its active toolbar buttons (see
     * [net.mrphan.calibre.reader.chrome.ReaderChromeOverlay.isFooterActionActive]). Distinct
     * from [toggleReadAloud]'s return value, which only reports whether starting/toggling the
     * session succeeded, not whether one is currently playing. `PdfReaderActivity`/
     * `CbzReaderActivity` have no read-aloud support and simply return false. */
    fun isReadAloudActive(): Boolean

    // --- Page/Scroll/Auto-scroll mode (EPUB only) -------------------------------------------
    // See ReadingModePageAction/ReadingModeScrollAction/AutoScrollAction in ReaderActions.kt.
    // PdfReaderActivity/CbzReaderActivity page through pre-rendered bitmaps and have no reflow
    // concept, so they implement all four by returning false/0 -- ReaderAction#isRelevantFor()
    // hides the three menu rows there anyway, this is only reached if something calls them
    // directly.

    /** Whether the EPUB is currently rendered with continuous scroll instead of paginated. */
    fun isScrollModeEnabled(): Boolean

    /** Switches between paginated (false) and continuous-scroll (true) rendering. Returns false
     * if the navigator isn't ready yet or the underlying Readium call failed. */
    fun setScrollMode(enabled: Boolean): Boolean

    /** Whether the page is currently auto-advancing on its own. */
    fun isAutoScrollEnabled(): Boolean

    /** Starts auto scroll if it's off, stops it if it's on. Requires scroll mode to already be
     * on (auto scroll "trôi trang" only makes sense in continuous-scroll mode) -- returns false
     * and lets the caller surface a message otherwise. */
    fun toggleAutoScroll(): Boolean

    /** Opens a small Chậm/Vừa/Nhanh speed picker for auto scroll (see [AutoScrollAction]'s
     * [ReaderAction.onLongPress] override) -- prompt section 3.3's simplified 3-level stand-in
     * for Readest's continuous speed slider. */
    fun openAutoScrollSpeedControls(): Boolean

    // --- Bookmarks (a specific saved location, distinct from the single "last locator" that's
    // always auto-saved) -----------------------------------------------------------------------

    /** Whether the current reading position already has a bookmark. */
    fun isCurrentLocationBookmarked(): Boolean

    /** Adds a bookmark at the current position, or removes it if one is already there. */
    fun toggleBookmarkAtCurrentLocation(): Boolean

    // --- Quick Light/Dark theme switch (see ReaderThemeMode) --------------------------------

    /** The reader's current Light/Dark mode. */
    fun currentThemeMode(): ReaderThemeMode

    /** Switches the reader (and, where the concrete reader supports it, the chrome UI around it)
     * to [mode]. Returns false if the concrete reader doesn't support changing theme. */
    fun setThemeMode(mode: ReaderThemeMode): Boolean

    // --- Color/background theme + screen brightness (see prompt-nang-cap-mau-nen-tien-do-doc.md) ---

    /** Opens the "Màu nền & Tương phản" bottom sheet (screen brightness slider + named
     * background/text color presets). EPUB only -- Pdf/Cbz return false, same reasoning as
     * [openFontSizeControls]. */
    fun openColorAndContrastSettings(): Boolean

    /** Current screen brightness as the user last set it via the panel, 0..100. Returns -1 if
     * this reader doesn't track/support it (falls back to system brightness in that case). */
    fun currentScreenBrightness(): Int

    /** Applies [value] (0..100) as the window's screen brightness. Returns false if unsupported. */
    fun setScreenBrightness(value: Int): Boolean

    /** Stable id (e.g. "sepia", "contrast") of the currently applied background/text color
     * theme, or null if none has been explicitly chosen yet (falls back to the Light/Dark theme
     * from [currentThemeMode]). See [net.mrphan.calibre.reader.chrome.ReaderColorTheme]. */
    fun currentColorThemeId(): String?

    /** Applies the named [ReaderColorTheme] with this [themeId] (backgroundColor/textColor) to
     * the current EPUB. Returns false if [themeId] is unknown or unsupported by this reader. */
    fun setColorTheme(themeId: String): Boolean

    // --- Reading progress bar with chapter tick marks (see same prompt doc) ---

    /** 0f..1f fractions (interior chapter boundaries only, first/last excluded) where the thanh
     * tiến độ should draw a tick mark. Empty if the reader has no table of contents or can't
     * compute this cheaply. */
    fun chapterTickFractions(): List<Float>

    /** Same value as [currentProgressPercent] but as a 0f..1f fraction -- needed so the seekbar
     * and the tick marks share the exact same coordinate space. */
    fun currentProgressFraction(): Float

    /** Jumps the reader to [fraction] (0f..1f) of the whole book -- this is what "Lật tới"'s
     * slide-up panel calls on drag-release instead of the old, always-failing
     * [openGoToLocation]. Returns false if the reader can't resolve a Locator for that fraction. */
    fun jumpToProgressFraction(fraction: Float): Boolean

    /** Jumps to the table-of-contents entry immediately before/after the current position --
     * backs the new "Chương trước/sau" buttons in the progress panel. Returns false if there is
     * no table of contents or no such neighbour (already at the first/last chapter). */
    fun goToPreviousChapter(): Boolean
    fun goToNextChapter(): Boolean

    /** Ten chuong (TOC) tuong ung voi vi tri doc hien tai, hoac chuoi rong neu khong xac dinh
     * duoc (khong co TOC, hoac reader khong ho tro khai niem "chuong" -- Pdf/Cbz tra ve rong).
     * Hien thi duoi thanh tien do dang pill trong ReaderProgressPanel, kieu Readest. */
    fun currentChapterTitle(): String

    // --- Cloud progress sync (nhe, chi progress -- xem prompt-nang-cap-reader-sync.md) ---------

    /** Co it nhat 1 CloudRemote da ket noi ho tro device-library-sync
     * (CloudRemote.Type#supportsDeviceLibrarySync(), giong dieu kien
     * CollectionFragment#syncBooksToCloud dang dung) hay khong -- false thi an/
     * disable toan bo UI Sync thay vi hien 1 nut luon that bai. */
    fun isProgressSyncAvailable(): Boolean

    /** Epoch millis cua lan dong bo THANH CONG gan nhat cho SACH DANG MO, hoac
     * null neu chua tung dong bo. */
    fun lastProgressSyncedAt(): Long?

    /** Dang co 1 lan dong bo chay dang do hay khong -- dung de disable nut Sync/
     * hien spinner, tranh bam nhieu lan xep hang nhieu job trung nhau. */
    fun isProgressSyncInProgress(): Boolean

    /** Kich hoat 1 lan dong bo tien trinh doc ngay lap tuc. [onDone] duoc goi
     * ĐÚNG 1 LẦN tren main thread khi job ket thuc (true = thanh cong). Tra ve
     * false NGAY (khong bao gio goi [onDone]) neu [isProgressSyncAvailable] la
     * false hoac dang co 1 job khac chay ([isProgressSyncInProgress] = true). */
    fun syncProgressNow(onDone: (Boolean) -> Unit): Boolean

    /** Mo BookSyncInfoSheet cho sach dang mo. */
    fun openBookSyncInfo(): Boolean

    /** Chu ky auto-sync hien tai (phut). 0 = tat. */
    fun autoSyncIntervalMinutes(): Int

    fun setAutoSyncIntervalMinutes(minutes: Int)

    // --- Tap-zone page turning (phím âm lượng xem trực tiếp trong EpubReaderActivity, không
    // cần qua ReaderChromeHost vì đó là 1 cơ chế nội bộ tự-đóng-gói của riêng Activity, giống
    // pattern keepScreenOnEnabled -- xem kdoc ReaderSettingsBehaviorPage.kt) ------------------

    /** Có bật "chạm vào mép trái/phải màn hình để lật trang" hay không. Đọc bởi
     * [net.mrphan.calibre.reader.chrome.ReaderChromeOverlay]'s tap-zone gesture detector --
     * `PdfReaderActivity`/`CbzReaderActivity` trả false (chưa nằm trong phạm vi nâng cấp này). */
    fun isEdgeTapPageTurnEnabled(): Boolean

    /** Đảo chiều 2 vùng mép: mặc định (false) mép trái = lùi trang, mép phải = lật tới; true thì
     * ngược lại. Chỉ có ý nghĩa khi [isEdgeTapPageTurnEnabled] trả true. */
    fun isEdgeTapPageTurnInverted(): Boolean
}
