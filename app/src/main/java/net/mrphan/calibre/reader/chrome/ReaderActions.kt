package net.mrphan.calibre.reader.chrome

import net.mrphan.calibre.R

/**
 * Calls [capability] and, if it reports the action isn't supported by the current reader
 * (returns false), surfaces that via [ReaderChromeHost.showMessage] instead of doing nothing --
 * see spec Definition of Done #3 ("action không áp dụng phải ... báo rõ 'không hỗ trợ' thay vì im
 * lặng không phản hồi").
 */
private inline fun invokeOrReportUnsupported(
    host: ReaderChromeHost,
    label: Int,
    capability: () -> Boolean,
) {
    if (!capability()) {
        val actionName = host.formatString(label)
        host.showMessage(host.formatString(R.string.reader_chrome_action_unsupported, actionName))
    }
}

// BackAction/ForwardAction keep slot = MENU (the interface default) but are deliberately left out
// of every ReaderActionRegistry.menuGroupsFor() group -- see the "PROGRESS_PANEL" note in
// prompt-nang-cap-reader-toolbar.md section 4.6. They are only ever invoked directly, as plain
// Kotlin objects, by ReaderProgressPanel's prev/next buttons. GotoLocationAction below is the one
// that actually stands in the footer and represents all three in the button-mapping sense.
object BackAction : ReaderAction {
    override val id = "back"
    override val icon = android.R.drawable.ic_media_previous
    override val label = R.string.reader_action_back
    override fun invoke(host: ReaderChromeHost) = invokeOrReportUnsupported(host, label, host::goBack)
}

object ForwardAction : ReaderAction {
    override val id = "forward"
    override val icon = android.R.drawable.ic_media_next
    override val label = R.string.reader_action_forward
    override fun invoke(host: ReaderChromeHost) = invokeOrReportUnsupported(host, label, host::goForward)
}

object SearchAction : ReaderAction {
    override val id = "search"
    override val icon = android.R.drawable.ic_menu_search
    override val label = R.string.reader_action_search
    override val slot = ReaderActionSlot.HEADER_START
    // Only EPUB has any full-text search story today (Readium); PdfRenderer/ZipFile give us no
    // text layer to search, so this action would just fail every time on Pdf/Cbz -- hide it there
    // instead, per spec section 5's "Reference/Highlights: ... để các action này trả false/ẩn".
    override fun isRelevantFor(kind: ReaderKind) = kind == ReaderKind.EPUB
    override fun invoke(host: ReaderChromeHost) = invokeOrReportUnsupported(host, label, host::openSearch)
}

// Stands in for Back/Forward/GotoLocation as a single "Tiến độ" icon in the footer -- see the
// ReaderActionSlot.FOOTER kdoc and prompt-nang-cap-reader-toolbar.md section 4.4/4.6. Tapping it
// in the footer opens ReaderProgressPanel instead of calling invoke() directly (ReaderChromeOverlay
// special-cases this action by identity), but invoke() is still implemented properly so the action
// behaves sensibly if ever invoked directly (e.g. from a future keyboard shortcut).
object GotoLocationAction : ReaderAction {
    override val id = "goto"
    override val icon = R.drawable.ic_reader_progress
    override val label = R.string.reader_action_goto
    override val slot = ReaderActionSlot.FOOTER
    // Relevant everywhere: "jump to a page number" is meaningful for Pdf/Cbz too, and "jump to a
    // location" is meaningful for Epub.
    override fun invoke(host: ReaderChromeHost) = invokeOrReportUnsupported(host, label, host::openGoToLocation)
}

object OpenBookAction : ReaderAction {
    override val id = "open"
    override val icon = android.R.drawable.ic_menu_view
    override val label = R.string.reader_action_open
    override fun invoke(host: ReaderChromeHost) = invokeOrReportUnsupported(host, label, host::openBookPicker)
}

object ReloadBookAction : ReaderAction {
    override val id = "reload_book"
    override val icon = android.R.drawable.ic_menu_rotate
    override val label = R.string.reader_action_reload
    override fun invoke(host: ReaderChromeHost) = invokeOrReportUnsupported(host, label, host::reloadBook)
}

object EditBookAction : ReaderAction {
    override val id = "edit_book"
    override val icon = android.R.drawable.ic_menu_edit
    override val label = R.string.reader_action_edit_book
    override fun invoke(host: ReaderChromeHost) = invokeOrReportUnsupported(host, label, host::openMetadataEditor)
}

object TableOfContentsAction : ReaderAction {
    override val id = "toc"
    override val icon = android.R.drawable.ic_menu_agenda
    override val label = R.string.reader_action_toc
    override val slot = ReaderActionSlot.FOOTER
    // PdfRenderer/ZipFile expose no outline/bookmark tree -- see spec section 5.
    override fun isRelevantFor(kind: ReaderKind) = kind == ReaderKind.EPUB
    override fun invoke(host: ReaderChromeHost) = invokeOrReportUnsupported(host, label, host::openTableOfContents)
}

// Thay the ReferenceModeAction cu (luon bao "khong ho tro") - mo BottomSheet "Mau nen & Tuong
// phan" (do sang man hinh + 11 mau nen/chu dat ten san + nut Light/Dark) - xem
// prompt-nang-cap-mau-nen-tien-do-doc.md muc 3.1.
object ColorContrastAction : ReaderAction {
    override val id = "color_contrast"
    override val icon = R.drawable.ic_reader_color_theme
    override val label = R.string.reader_action_color_contrast
    override val slot = ReaderActionSlot.FOOTER
    override fun isRelevantFor(kind: ReaderKind) = kind == ReaderKind.EPUB
    override fun invoke(host: ReaderChromeHost) =
        invokeOrReportUnsupported(host, label, host::openColorAndContrastSettings)
}

object HighlightsAction : ReaderAction {
    override val id = "toggle_highlights"
    override val icon = R.drawable.ic_reader_highlight
    override val label = R.string.reader_action_highlights
    override val slot = ReaderActionSlot.HEADER_START
    override fun isRelevantFor(kind: ReaderKind) = kind == ReaderKind.EPUB
    override fun invoke(host: ReaderChromeHost) = invokeOrReportUnsupported(host, label, host::toggleHighlightsBrowser)
}

object BookmarksAction : ReaderAction {
    override val id = "bookmarks"
    override val icon = R.drawable.ic_reader_bookmark
    override val label = R.string.reader_action_bookmarks
    override val slot = ReaderActionSlot.HEADER_START
    // Bookmarking a page number is just as meaningful for Pdf/Cbz as a locator is for Epub.
    override fun invoke(host: ReaderChromeHost) = invokeOrReportUnsupported(host, label, host::toggleBookmarksPanel)
}

object ReadingStatsAction : ReaderAction {
    override val id = "reading_stats"
    override val icon = R.drawable.ic_reader_stats
    override val label = R.string.reader_action_reading_stats
    override fun invoke(host: ReaderChromeHost) = invokeOrReportUnsupported(host, label, host::openReadingStats)
}

object ReaderBackgroundMusicAction : ReaderAction {
    override val id = "reader_background_music"
    override val icon = R.drawable.ic_reader_background_music_headphones
    override val label = R.string.reader_background_music_label
    override val slot = ReaderActionSlot.HEADER_END
    override fun isRelevantFor(kind: ReaderKind) = kind == ReaderKind.EPUB
    override fun invoke(host: ReaderChromeHost) =
        invokeOrReportUnsupported(host, label, host::openReaderBackgroundMusic)
}

object FontSizeAction : ReaderAction {
    override val id = "font_size"
    override val icon = R.drawable.ic_reader_font_size
    override val label = R.string.reader_action_font_size
    override val slot = ReaderActionSlot.FOOTER
    // Pdf/Cbz pages are rendered bitmaps, not reflowable text -- there is no font size to change.
    override fun isRelevantFor(kind: ReaderKind) = kind == ReaderKind.EPUB
    // Truoc day mo dialog_reader_display_settings rieng (chi co co chu/gian dong/font chu) --
    // gio mo thang ReaderSettingsSheet day du (tat ca cac tab: Phong chu/Bo cuc/Mau/Thao tac/Doc
    // to), giong het nut "Cai dat" trong menu ☰, vi nguoi dung phan anh openFontSizeControls() la
    // "tinh nang nang cao" khong con phu hop lam hanh dong nhanh o footer nua.
    override fun invoke(host: ReaderChromeHost) = invokeOrReportUnsupported(host, label, host::openPreferences)
}

object PreferencesAction : ReaderAction {
    override val id = "preferences"
    override val icon = android.R.drawable.ic_menu_preferences
    override val label = R.string.reader_action_preferences
    override fun invoke(host: ReaderChromeHost) = invokeOrReportUnsupported(host, label, host::openPreferences)
}

object ReadAloudAction : ReaderAction {
    override val id = "read_aloud"
    override val icon = R.drawable.ic_reader_read_aloud
    override val label = R.string.reader_action_read_aloud
    override val slot = ReaderActionSlot.FOOTER
    // Only EPUB has a text layer to read aloud -- PdfRenderer/ZipFile give us rendered bitmaps,
    // same reasoning as SearchAction/TableOfContentsAction/FontSizeAction above.
    override fun isRelevantFor(kind: ReaderKind) = kind == ReaderKind.EPUB
    override fun invoke(host: ReaderChromeHost) = invokeOrReportUnsupported(host, label, host::toggleReadAloud)
}

// "Cấu hình tạo sẵn" (profiles/presets) -- mo ReaderSettingsProfilesSheet qua
// ReaderChromeHost.openReaderSettingsProfiles(). EPUB-only trong thuc te (PdfReaderActivity/
// CbzReaderActivity tra false cho capability nay, giong openPreferences()), nhung khong can
// isRelevantFor rieng vi invokeOrReportUnsupported da bao "khong ho tro" dung cach cho cac
// ReaderKind chua wiring.
object ProfilesAction : ReaderAction {
    override val id = "profiles"
    override val icon = android.R.drawable.ic_menu_manage
    override val label = R.string.reader_action_profiles
    override fun invoke(host: ReaderChromeHost) =
        invokeOrReportUnsupported(host, label, host::openReaderSettingsProfiles)
}

// Was a plain ReaderAction; now a ReaderToggleAction so the menu row shows whether immersive
// mode is currently on (see prompt section 6.1) instead of a vô tri tap-and-dismiss line. The
// underlying capability (ReaderChromeHost#toggleFullscreen) and its behaviour are unchanged.
object FullscreenAction : ReaderToggleAction {
    override val id = "fullscreen"
    override val icon = R.drawable.ic_reader_fullscreen
    override val label = R.string.reader_action_fullscreen
    override fun isChecked(host: ReaderChromeHost) = host.isImmersiveModeEnabled()
    override fun invoke(host: ReaderChromeHost) = invokeOrReportUnsupported(host, label, host::toggleFullscreen)
}

// Quick Light/Dark switch -- see ReaderThemeMode kdoc. The person explicitly asked for this in
// the hamburger menu (not header, unlike Readest's HeaderBar sun/moon icon), see prompt section
// 6.2's final paragraph, so slot stays the interface default (MENU) rather than HEADER_END.
object ThemeToggleAction : ReaderToggleAction {
    override val id = "theme_toggle"
    override val icon = R.drawable.ic_reader_theme_toggle
    override val label = R.string.reader_action_theme_toggle
    override fun isChecked(host: ReaderChromeHost) = host.currentThemeMode() == ReaderThemeMode.DARK
    override fun invoke(host: ReaderChromeHost) = invokeOrReportUnsupported(host, label) {
        val next = if (host.currentThemeMode() == ReaderThemeMode.DARK) {
            ReaderThemeMode.LIGHT
        } else {
            ReaderThemeMode.DARK
        }
        host.setThemeMode(next)
    }
}

// "Che do doc": Page mode / Scroll mode / Auto scroll -- see prompt section 3. EPUB only, same
// reasoning as FontSizeAction/ReadAloudAction above (Pdf/Cbz have no reflow to scroll).
object ReadingModePageAction : ReaderToggleAction {
    override val id = "reading_mode_page"
    override val icon = R.drawable.ic_view_list
    override val label = R.string.reader_action_reading_mode_page
    override val isExclusiveChoice = true
    override fun isRelevantFor(kind: ReaderKind) = kind == ReaderKind.EPUB
    override fun isChecked(host: ReaderChromeHost) = !host.isScrollModeEnabled()
    override fun invoke(host: ReaderChromeHost) =
        invokeOrReportUnsupported(host, label) { host.setScrollMode(false) }
}

object ReadingModeScrollAction : ReaderToggleAction {
    override val id = "reading_mode_scroll"
    override val icon = R.drawable.ic_view_carousel
    override val label = R.string.reader_action_reading_mode_scroll
    override val isExclusiveChoice = true
    override fun isRelevantFor(kind: ReaderKind) = kind == ReaderKind.EPUB
    override fun isChecked(host: ReaderChromeHost) = host.isScrollModeEnabled()
    override fun invoke(host: ReaderChromeHost) =
        invokeOrReportUnsupported(host, label) { host.setScrollMode(true) }
}

object AutoScrollAction : ReaderToggleAction {
    override val id = "auto_scroll"
    override val icon = android.R.drawable.ic_media_play
    override val label = R.string.reader_action_auto_scroll
    override fun isRelevantFor(kind: ReaderKind) = kind == ReaderKind.EPUB
    override fun isChecked(host: ReaderChromeHost) = host.isAutoScrollEnabled()
    override fun invoke(host: ReaderChromeHost) = invokeOrReportUnsupported(host, label, host::toggleAutoScroll)
    override fun onLongPress(host: ReaderChromeHost): Boolean = host.openAutoScrollSpeedControls()
}

// EPUB-only: xuat 1 chuong sang PDF can doc duoc HTML/reading order cua Readium, khong
// co y nghia cho Pdf (da la file PDF san)/Cbz (anh, khong co "chuong" theo nghia nay).
object PrintAction : ReaderAction {
    override val id = "print"
    override val icon = R.drawable.ic_reader_print
    override val label = R.string.reader_action_print
    override fun isRelevantFor(kind: ReaderKind) = kind == ReaderKind.EPUB
    override fun invoke(host: ReaderChromeHost) =
        invokeOrReportUnsupported(host, label, host::printCurrentChapterAsPdf)
}

object InspectorAction : ReaderAction {
    override val id = "inspector"
    override val icon = android.R.drawable.ic_menu_sort_by_size
    override val label = R.string.reader_action_inspector
    override fun invoke(host: ReaderChromeHost) = invokeOrReportUnsupported(host, label, host::openInspector)
}

object ResetInterfaceAction : ReaderAction {
    override val id = "reset_interface"
    override val icon = android.R.drawable.ic_menu_revert
    override val label = R.string.reader_action_reset_interface
    override fun invoke(host: ReaderChromeHost) = invokeOrReportUnsupported(host, label, host::resetInterfaceDefaults)
}

object QuitAction : ReaderAction {
    override val id = "quit"
    override val icon = android.R.drawable.ic_menu_close_clear_cancel
    override val label = R.string.reader_action_quit
    override fun invoke(host: ReaderChromeHost) = host.close()
}
