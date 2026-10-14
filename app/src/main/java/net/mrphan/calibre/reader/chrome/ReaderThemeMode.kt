package net.mrphan.calibre.reader.chrome

/**
 * Quick Light/Dark switch exposed on the reader chrome (see [ReaderChromeHost.currentThemeMode] /
 * [ReaderChromeHost.setThemeMode] and [ThemeToggleAction] in ReaderActions.kt). Deliberately only
 * two values -- unlike Readium's own `org.readium.r2.navigator.preferences.Theme`, which also has
 * `SEPIA` -- because the user-facing request here was specifically "chuyen nhanh Light/Dark", not
 * a full theme picker (that already exists via openPreferences()/the font & layout dialog, see
 * EpubReaderActivity#openFontSizeControls()). EpubReaderActivity maps this to Readium's `Theme`
 * enum when calling `submitPreferences()`.
 */
enum class ReaderThemeMode {
    LIGHT,
    DARK,
}
