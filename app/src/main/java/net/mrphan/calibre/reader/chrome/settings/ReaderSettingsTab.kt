package net.mrphan.calibre.reader.chrome.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import net.mrphan.calibre.R
import net.mrphan.calibre.reader.chrome.ReaderKind

/**
 * Mot tab trong thanh icon ngang cua bang "Cai dat doc" (xem [ReaderSettingsSheet]), clone bo cuc
 * tab bar cua Readest (Aa / luoi / co mau / ban tay / loa) nhung chi giu tab co tinh nang that
 * dung sau no, xem prompt-nang-cap-reader-settings-tabbed.md muc 2. Moi tab tu quyet dinh no co
 * ap dung cho [ReaderKind] hien tai khong, giong cach [net.mrphan.calibre.reader.chrome.ReaderAction.isRelevantFor]
 * dang lam. Trong thuc te [ReaderSettingsSheet] hien chi duoc mo tu EpubReaderActivity (xem
 * openPreferences() cua PdfReaderActivity/CbzReaderActivity van tra ve false, khong doi) nen
 * FONT/LAYOUT/TTS luon duoc hien thi -- co Boolean nay van duoc giu de mot ban nang cap sau (wiring
 * PDF/CBZ) khong phai sua lai file nay.
 */
enum class ReaderSettingsTab(
    @DrawableRes val icon: Int,
    @StringRes val label: Int,
) {
    FONT(R.drawable.ic_reader_font_size, R.string.reader_settings_tab_font) {
        override fun isRelevantFor(kind: ReaderKind) = kind == ReaderKind.EPUB
    },
    LAYOUT(R.drawable.ic_reader_tab_layout, R.string.reader_settings_tab_layout) {
        override fun isRelevantFor(kind: ReaderKind) = kind == ReaderKind.EPUB
    },
    COLOR(R.drawable.ic_reader_tab_color, R.string.reader_settings_tab_color) {
        override fun isRelevantFor(kind: ReaderKind) = true
    },
    BEHAVIOR(R.drawable.ic_reader_tab_behavior, R.string.reader_settings_tab_behavior) {
        override fun isRelevantFor(kind: ReaderKind) = true
    },
    TTS(R.drawable.ic_reader_read_aloud, R.string.reader_settings_tab_tts) {
        override fun isRelevantFor(kind: ReaderKind) = kind == ReaderKind.EPUB
    },
    // MOI: luon hien thi, khong phu thuoc EPUB/PDF/CBZ (progress sync co y
    // nghia voi ca 3 loai reader, khac TTS chi co o EPUB).
    SYNC(R.drawable.ic_menu_cloud_upload, R.string.reader_settings_tab_sync) {
        override fun isRelevantFor(kind: ReaderKind) = true
    };

    abstract fun isRelevantFor(kind: ReaderKind): Boolean
}
