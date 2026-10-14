package net.mrphan.calibre.reader.chrome.settings

/**
 * Cac "Cau hinh tao san" dung cung, khong the xoa/sua, hien luon dau danh sach trong
 * [ReaderSettingsProfilesSheet]. Gia tri duoc chon thu cong de phu hop ngu canh doc trong ten,
 * trong pham vi hop le cua net.mrphan.calibre.LayoutRanges (xem prompt muc 0).
 */
object ReaderSettingsProfilePresets {

    val DAY_INDOOR = ReaderSettingsProfile(
        id = "builtin_day_indoor",
        name = "Đọc ban ngày trong phòng",
        isBuiltIn = true,
        font = FontSettings(
            fontSizeMultiplier = 1.0,
            fontWeight = 400.0,
            fontFamilyName = null,
            publisherStylesOverrideEnabled = false,
        ),
        layout = LayoutSettings(
            lineHeight = 1.3,
            pageMargins = 1.0,
            twoColumnEnabled = false,
            paragraphSpacing = 0.0,
            paragraphIndent = 0.0,
            wordSpacing = 0.0,
            letterSpacing = 0.0,
            justifyTextEnabled = false,
            hyphensEnabled = false,
        ),
        color = ColorSettings(colorThemeId = "default", themeMode = "LIGHT", screenBrightness = 60),
        behavior = BehaviorSettings(keepScreenOnEnabled = false),
        tts = TtsSettings(speechRateMultiplier = 1.0f),
    )

    val OUTDOOR_DAYLIGHT = ReaderSettingsProfile(
        id = "builtin_outdoor_daylight",
        name = "Đọc ngoài trời",
        isBuiltIn = true,
        font = FontSettings(
            fontSizeMultiplier = 1.2,
            fontWeight = 500.0,
            fontFamilyName = null,
            publisherStylesOverrideEnabled = false,
        ),
        layout = LayoutSettings(
            lineHeight = 1.4,
            pageMargins = 1.1,
            twoColumnEnabled = false,
            paragraphSpacing = 0.0,
            paragraphIndent = 0.0,
            wordSpacing = 0.0,
            letterSpacing = 0.0,
            justifyTextEnabled = false,
            hyphensEnabled = false,
        ),
        // "contrast" = den/trang tuong phan toi da, de doc duoc duoi anh nang gay chua.
        color = ColorSettings(colorThemeId = "contrast", themeMode = "LIGHT", screenBrightness = 100),
        // Giu man hinh sang: ngoai troi de bi tat man hinh do cam ung khong on dinh/deo kinh rop.
        behavior = BehaviorSettings(keepScreenOnEnabled = true),
        tts = TtsSettings(speechRateMultiplier = 1.0f),
    )

    val NIGHT = ReaderSettingsProfile(
        id = "builtin_night",
        name = "Đọc ban đêm",
        isBuiltIn = true,
        font = FontSettings(
            fontSizeMultiplier = 1.0,
            fontWeight = 400.0,
            fontFamilyName = null,
            publisherStylesOverrideEnabled = false,
        ),
        layout = LayoutSettings(
            lineHeight = 1.3,
            pageMargins = 1.0,
            twoColumnEnabled = false,
            paragraphSpacing = 0.0,
            paragraphIndent = 0.0,
            wordSpacing = 0.0,
            letterSpacing = 0.0,
            justifyTextEnabled = false,
            hyphensEnabled = false,
        ),
        color = ColorSettings(colorThemeId = "default", themeMode = "DARK", screenBrightness = 15),
        behavior = BehaviorSettings(keepScreenOnEnabled = false),
        tts = TtsSettings(speechRateMultiplier = 1.0f),
    )

    val EYE_COMFORT = ReaderSettingsProfile(
        id = "builtin_eye_comfort",
        name = "Đọc dài, đỡ mỏi mắt",
        isBuiltIn = true,
        font = FontSettings(
            fontSizeMultiplier = 1.1,
            fontWeight = 400.0,
            fontFamilyName = null,
            // Bat override de paragraphSpacing/wordSpacing ben duoi thuc su co hieu luc
            // (xem ReaderSettingsLayoutPage's kdoc: 6/9 field Layout can publisherStylesOverrideEnabled = true).
            publisherStylesOverrideEnabled = true,
        ),
        layout = LayoutSettings(
            lineHeight = 1.5,
            pageMargins = 1.2,
            twoColumnEnabled = false,
            paragraphSpacing = 0.3,
            paragraphIndent = 0.0,
            wordSpacing = 0.1,
            letterSpacing = 0.0,
            justifyTextEnabled = false,
            hyphensEnabled = false,
        ),
        color = ColorSettings(colorThemeId = "sepia", themeMode = "LIGHT", screenBrightness = 35),
        behavior = BehaviorSettings(keepScreenOnEnabled = false),
        tts = TtsSettings(speechRateMultiplier = 1.0f),
    )

    val TTS_FAST = ReaderSettingsProfile(
        id = "builtin_tts_fast",
        name = "Nghe sách nhanh",
        isBuiltIn = true,
        font = DAY_INDOOR.font,
        layout = DAY_INDOOR.layout,
        color = DAY_INDOOR.color,
        // Tat giu man hinh sang: nghe la chinh, khong nhin man hinh lien tuc, tiet kiem pin.
        behavior = BehaviorSettings(keepScreenOnEnabled = false),
        tts = TtsSettings(speechRateMultiplier = 1.5f),
    )

    val TTS_SLOW = ReaderSettingsProfile(
        id = "builtin_tts_slow",
        name = "Nghe sách chậm",
        isBuiltIn = true,
        // Chu to hon mot chut: preset nay hop voi kieu vua nghe vua doc theo (hoc ngoai ngu,
        // nguoi moi lam quen sach noi), nen giu man hinh sang de theo doi chu.
        font = DAY_INDOOR.font.copy(fontSizeMultiplier = 1.1),
        layout = DAY_INDOOR.layout,
        color = DAY_INDOOR.color,
        behavior = BehaviorSettings(keepScreenOnEnabled = true),
        tts = TtsSettings(speechRateMultiplier = 0.7f),
    )

    /** Thu tu hien thi trong danh sach "Có sẵn". */
    val ALL: List<ReaderSettingsProfile> = listOf(
        DAY_INDOOR, OUTDOOR_DAYLIGHT, NIGHT, EYE_COMFORT, TTS_FAST, TTS_SLOW,
    )
}
