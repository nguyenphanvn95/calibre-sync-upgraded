package net.mrphan.calibre.reader.chrome

import android.content.Context
import androidx.annotation.StringRes
import net.mrphan.calibre.R
import org.readium.r2.navigator.preferences.FontFamily

data class ReaderFontFace(
    val fileName: String,
    val weight: Int = 400,
    val italic: Boolean = false,
)

data class ReaderFontChoice(
    val id: String,
    @StringRes val labelRes: Int? = null,
    val label: String? = null,
    val readiumFamily: FontFamily? = null,
    val customFamily: String? = null,
    val fallbackCss: String? = null,
    val faces: List<ReaderFontFace> = emptyList(),
) {
    val isDefault: Boolean get() = id == DEFAULT_ID
    val isCustom: Boolean get() = customFamily != null && faces.isNotEmpty()

    fun displayName(context: Context): String =
        labelRes?.let(context::getString) ?: label ?: id

    companion object {
        const val DEFAULT_ID = "default"
        private const val ASSET_BASE_URL = "file:///android_asset/reader_fonts/"
        private const val SERIF_FALLBACK =
            "Georgia, 'Iowan Old Style', 'Palatino Linotype', 'Book Antiqua', serif"
        private const val SANS_FALLBACK =
            "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif"
        private const val MONO_FALLBACK = "'Courier New', Courier, monospace"

        val ALL: List<ReaderFontChoice> = listOf(
            ReaderFontChoice(DEFAULT_ID, labelRes = R.string.reader_font_family_default),
            ReaderFontChoice(FontFamily.SERIF.name, labelRes = R.string.reader_font_family_serif, readiumFamily = FontFamily.SERIF),
            ReaderFontChoice(FontFamily.SANS_SERIF.name, labelRes = R.string.reader_font_family_sans_serif, readiumFamily = FontFamily.SANS_SERIF),
            ReaderFontChoice(FontFamily.OPEN_DYSLEXIC.name, labelRes = R.string.reader_font_family_open_dyslexic, readiumFamily = FontFamily.OPEN_DYSLEXIC),
            ReaderFontChoice(FontFamily.CURSIVE.name, labelRes = R.string.reader_font_family_cursive, readiumFamily = FontFamily.CURSIVE),
            ReaderFontChoice(FontFamily.MONOSPACE.name, labelRes = R.string.reader_font_family_monospace, readiumFamily = FontFamily.MONOSPACE),
            ReaderFontChoice(
                "bookerly",
                label = "Bookerly",
                customFamily = "CalibreSync Bookerly",
                fallbackCss = SERIF_FALLBACK,
                faces = listOf(
                    ReaderFontFace("bookerly.ttf", 400),
                    ReaderFontFace("bookerlyItalic.ttf", 400, italic = true),
                    ReaderFontFace("bookerlyBold.ttf", 700),
                    ReaderFontFace("bookerlyBoldItalic.ttf", 700, italic = true),
                ),
            ),
            ReaderFontChoice("icieldomaine", label = "iCiel Domaine", customFamily = "CalibreSync iCiel Domaine", fallbackCss = SERIF_FALLBACK, faces = listOf(ReaderFontFace("icieldomaine-regular.ttf"))),
            ReaderFontChoice("svntimes", label = "SVN-Times New Roman", customFamily = "CalibreSync SVN Times New Roman", fallbackCss = SERIF_FALLBACK, faces = listOf(ReaderFontFace("svntimesnewroman-regular.ttf"))),
            ReaderFontChoice("notosans", label = "Noto Sans", customFamily = "CalibreSync Noto Sans", fallbackCss = SANS_FALLBACK, faces = listOf(ReaderFontFace("notosans-regular.ttf"))),
            ReaderFontChoice(
                "netflixsans",
                label = "Netflix Sans",
                customFamily = "CalibreSync Netflix Sans",
                fallbackCss = SANS_FALLBACK,
                faces = listOf(
                    ReaderFontFace("netflixsans-light.otf", 300),
                    ReaderFontFace("netflixsans-regular.otf", 400),
                    ReaderFontFace("netflixsans-medium.otf", 500),
                    ReaderFontFace("netflixsans-bold.otf", 700),
                ),
            ),
            ReaderFontChoice("quicksand", label = "Quicksand", customFamily = "CalibreSync Quicksand", fallbackCss = SANS_FALLBACK, faces = listOf(ReaderFontFace("quicksand-regular.ttf"))),
            ReaderFontChoice("svnhelvetica", label = "SVN-Helvetica Neue", customFamily = "CalibreSync SVN Helvetica Neue", fallbackCss = SANS_FALLBACK, faces = listOf(ReaderFontFace("svnhelveticaneue-regular.ttf"))),
            ReaderFontChoice("mtodom", label = "MTO Dom", customFamily = "CalibreSync MTO Dom", fallbackCss = SANS_FALLBACK, faces = listOf(ReaderFontFace("mtodom-regular.ttf"))),
            ReaderFontChoice("pattaya", label = "Pattaya", customFamily = "CalibreSync Pattaya", fallbackCss = SANS_FALLBACK, faces = listOf(ReaderFontFace("pattaya-regular.ttf"))),
            ReaderFontChoice("p22typewriter", label = "P22 Typewriter", customFamily = "CalibreSync P22 Typewriter", fallbackCss = MONO_FALLBACK, faces = listOf(ReaderFontFace("p22typewriter-regular.ttf"))),
            ReaderFontChoice("trixipro", label = "Trixi Pro", customFamily = "CalibreSync Trixi Pro", fallbackCss = MONO_FALLBACK, faces = listOf(ReaderFontFace("trixipro-regular.ttf"))),
        )

        fun find(id: String?): ReaderFontChoice =
            ALL.firstOrNull { it.id == id } ?: ALL.first()

        fun assetUrl(fileName: String): String = ASSET_BASE_URL + fileName
    }
}
