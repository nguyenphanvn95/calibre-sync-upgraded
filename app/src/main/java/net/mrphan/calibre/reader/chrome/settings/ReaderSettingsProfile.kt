package net.mrphan.calibre.reader.chrome.settings

/**
 * Mot "Cau hinh tao san" (preset/profile) cho bang Cai dat doc -- gom du gia tri cua ca 5 tab
 * (Font/Bo cuc/Mau/Thao tac/Doc to) de ap dung dong loat bang 1 thao tac, thay vi nguoi dung phai
 * tu chinh tung thanh truot o tung tab (xem prompt-hoan-thien-cau-hinh-tao-san.md).
 *
 * Gia tri cua tung field duoc lay dung theo don vi ma cac Page hien tai dang doc/ghi (vi du
 * [FontSettings.fontWeight] la thang CSS 100..900 nhu [net.mrphan.calibre.LayoutRanges.FONT_WEIGHT],
 * KHONG PHAI gia tri normalized ~1.0 ma Readium can -- viec normalize hoa van do
 * EpubReaderActivity.buildCurrentEpubPreferences() lo, giu nguyen, khong dung lai o day).
 */
data class ReaderSettingsProfile(
    val id: String,
    val name: String,
    val isBuiltIn: Boolean,
    val font: FontSettings,
    val layout: LayoutSettings,
    val color: ColorSettings,
    val behavior: BehaviorSettings,
    val tts: TtsSettings,
)

data class FontSettings(
    val fontSizeMultiplier: Double,
    val fontWeight: Double,
    /** Ten cua [net.mrphan.calibre.FontFamily] (vi du "SERIF"), hoac null = mac dinh sach. */
    val fontFamilyName: String?,
    val publisherStylesOverrideEnabled: Boolean,
)

data class LayoutSettings(
    val lineHeight: Double,
    val pageMargins: Double,
    val twoColumnEnabled: Boolean,
    val paragraphSpacing: Double,
    val paragraphIndent: Double,
    val wordSpacing: Double,
    val letterSpacing: Double,
    val justifyTextEnabled: Boolean,
    val hyphensEnabled: Boolean,
)

data class ColorSettings(
    val colorThemeId: String,
    /** "LIGHT" hoac "DARK", khop ten cua [net.mrphan.calibre.reader.chrome.ReaderThemeMode]. */
    val themeMode: String,
    /** 0..100, hoac -1 = giu nguyen do sang hien tai (dung dung sentinel EpubReaderActivity dang
     * dung cho currentScreenBrightness, xem muc 0). */
    val screenBrightness: Int,
)

// TODO(prompt-nang-cap-hieu-ung-lat-trang.md muc 4): pageTurnAnimation hien chi luu rieng qua
// EpubReaderActivity.PREF_READER_PAGE_TURN_ANIMATION (SharedPreferences chung), KHONG theo tung
// ho so doc (profile) nhu keepScreenOnEnabled ben duoi -- mo rong BehaviorSettings de lam duoc
// vay se dung tiep ReaderSettingsProfilePresets.kt (6 diem khoi tao) va
// ReaderSettingsProfileStore.kt (serialize/deserialize), ngoai pham vi prompt nay.
data class BehaviorSettings(
    val keepScreenOnEnabled: Boolean,
)

data class TtsSettings(
    /** 0.5..2.0, ap dung thang cho TtsSettingsStore.saveSpeechRate() -- KHONG dung don vi progress
     * 0..15 cua stepper. */
    val speechRateMultiplier: Float,
)
