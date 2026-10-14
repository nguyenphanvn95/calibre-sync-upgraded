package net.mrphan.calibre.reader.chrome

import android.graphics.Color as AndroidColor
import androidx.annotation.StringRes
import net.mrphan.calibre.R

/**
 * Named background/text color presets for the "Màu nền & Tương phản" panel -- ported from
 * Readest's `apps/readest-app/src/styles/themes.ts` (`themes` array), keeping only the `light`
 * palette's `bg`/`fg` hex values (Readium's own Light/Dark [ReaderThemeMode] already covers the
 * dark-mode axis, so we don't need Readest's separate per-theme dark palette too -- see
 * prompt-nang-cap-mau-nen-tien-do-doc.md section 3.1).
 */
data class ReaderColorTheme(
    val id: String,
    @StringRes val label: Int,
    val backgroundColorInt: Int,
    val textColorInt: Int,
    val backgroundImageAsset: String? = null,
) {
    val thumbnailImageAsset: String?
        get() = backgroundImageAsset?.let { asset ->
            val extensionIndex = asset.lastIndexOf('.')
            if (extensionIndex < 0) {
                asset.replace("-bg", "-bg-thumbnail")
            } else {
                asset.substring(0, extensionIndex)
                    .replace("-bg", "-bg-thumbnail") + asset.substring(extensionIndex)
            }
        }

    companion object {
        private const val BACKGROUND_ASSET_BASE_URL = "file:///android_asset/reader_backgrounds/"

        val ALL: List<ReaderColorTheme> = listOf(
            ReaderColorTheme("default", R.string.reader_color_theme_default, AndroidColor.parseColor("#ffffff"), AndroidColor.parseColor("#171717")),
            ReaderColorTheme("waka_light", R.string.reader_color_theme_waka_light, AndroidColor.parseColor("#faf7f0"), AndroidColor.parseColor("#2b2b28")),
            ReaderColorTheme("gray", R.string.reader_color_theme_gray, AndroidColor.parseColor("#e0e0e0"), AndroidColor.parseColor("#222222")),
            ReaderColorTheme("waka_gray", R.string.reader_color_theme_waka_gray, AndroidColor.parseColor("#e8e8e8"), AndroidColor.parseColor("#2b2b28")),
            ReaderColorTheme("sepia", R.string.reader_color_theme_sepia, AndroidColor.parseColor("#f1e8d0"), AndroidColor.parseColor("#5b4636")),
            ReaderColorTheme("grass", R.string.reader_color_theme_grass, AndroidColor.parseColor("#d7dbbd"), AndroidColor.parseColor("#232c16")),
            ReaderColorTheme("cherry", R.string.reader_color_theme_cherry, AndroidColor.parseColor("#f0d1d5"), AndroidColor.parseColor("#4e1609")),
            ReaderColorTheme("sky", R.string.reader_color_theme_sky, AndroidColor.parseColor("#cedef5"), AndroidColor.parseColor("#262d48")),
            ReaderColorTheme("solarized", R.string.reader_color_theme_solarized, AndroidColor.parseColor("#fdf6e3"), AndroidColor.parseColor("#586e75")),
            ReaderColorTheme("gruvbox", R.string.reader_color_theme_gruvbox, AndroidColor.parseColor("#fbf1c7"), AndroidColor.parseColor("#3c3836")),
            ReaderColorTheme("nord", R.string.reader_color_theme_nord, AndroidColor.parseColor("#eceff4"), AndroidColor.parseColor("#2e3440")),
            // "Contrast": tương phản tối đa đen/trắng -- xem readest themes.ts, name: 'contrast'.
            ReaderColorTheme("contrast", R.string.reader_color_theme_contrast, AndroidColor.parseColor("#ffffff"), AndroidColor.parseColor("#000000")),
            ReaderColorTheme("sunset", R.string.reader_color_theme_sunset, AndroidColor.parseColor("#fff7f0"), AndroidColor.parseColor("#423126")),
            ReaderColorTheme("waka_dark", R.string.reader_color_theme_waka_dark, AndroidColor.parseColor("#1b1f24"), AndroidColor.parseColor("#d8dee5")),
            ReaderColorTheme("waka_bg_black", R.string.reader_color_theme_waka_bg_black, AndroidColor.parseColor("#1b1f24"), AndroidColor.parseColor("#d8dee5"), "black-bg.jpg"),
            ReaderColorTheme("waka_bg_blue", R.string.reader_color_theme_waka_bg_blue, AndroidColor.parseColor("#1b1f24"), AndroidColor.parseColor("#d8dee5"), "blue-bg.jpg"),
            ReaderColorTheme("waka_bg_green", R.string.reader_color_theme_waka_bg_green, AndroidColor.parseColor("#1b1f24"), AndroidColor.parseColor("#d8dee5"), "green-bg.jpg"),
            ReaderColorTheme("waka_bg_green2", R.string.reader_color_theme_waka_bg_green2, AndroidColor.parseColor("#1b1f24"), AndroidColor.parseColor("#d8dee5"), "green2-bg.jpg"),
            ReaderColorTheme("waka_bg_white", R.string.reader_color_theme_waka_bg_white, AndroidColor.parseColor("#ffffff"), AndroidColor.parseColor("#1a1a1a"), "white-bg.jpg"),
            ReaderColorTheme("waka_bg_scrapbook", R.string.reader_color_theme_waka_bg_scrapbook, AndroidColor.parseColor("#f1e7d0"), AndroidColor.parseColor("#4b3621"), "scrapbook-bg.jpg"),
            ReaderColorTheme("waka_bg_sand", R.string.reader_color_theme_waka_bg_sand, AndroidColor.parseColor("#ffffff"), AndroidColor.parseColor("#1a1a1a"), "sand-bg.jpg"),
            ReaderColorTheme("waka_bg_moonsky", R.string.reader_color_theme_waka_bg_moonsky, AndroidColor.parseColor("#1b1f24"), AndroidColor.parseColor("#d8dee5"), "moonsky-bg.jpg"),
            ReaderColorTheme("waka_bg_leaves", R.string.reader_color_theme_waka_bg_leaves, AndroidColor.parseColor("#ffffff"), AndroidColor.parseColor("#1a1a1a"), "leaves-bg.jpg"),
            ReaderColorTheme("vip_city", R.string.reader_color_theme_vip_city, AndroidColor.parseColor("#1b1f24"), AndroidColor.parseColor("#f1f4f8"), "vip_city-bg.jpg"),
            ReaderColorTheme("vip_hoa_dep", R.string.reader_color_theme_vip_hoa_dep, AndroidColor.parseColor("#fff7fb"), AndroidColor.parseColor("#1a1a1a"), "vip_hoa_dep-bg.jpg"),
            ReaderColorTheme("vip_hoa_dep_2", R.string.reader_color_theme_vip_hoa_dep_2, AndroidColor.parseColor("#fff7fb"), AndroidColor.parseColor("#1a1a1a"), "vip_hoa_dep_2-bg.jpg"),
            ReaderColorTheme("vip_hoa_nuoc", R.string.reader_color_theme_vip_hoa_nuoc, AndroidColor.parseColor("#effafa"), AndroidColor.parseColor("#163232"), "vip_hoa_nuoc-bg.jpg"),
            ReaderColorTheme("vip_nui_non", R.string.reader_color_theme_vip_nui_non, AndroidColor.parseColor("#eff3ea"), AndroidColor.parseColor("#1f2b1f"), "vip_nui_non-bg.jpg"),
            ReaderColorTheme("vip_nuoc_bien", R.string.reader_color_theme_vip_nuoc_bien, AndroidColor.parseColor("#eaf8ff"), AndroidColor.parseColor("#132c38"), "vip_nuoc_bien-bg.jpg"),
            ReaderColorTheme("vip_sua_bien", R.string.reader_color_theme_vip_sua_bien, AndroidColor.parseColor("#e9fbfb"), AndroidColor.parseColor("#132e32"), "vip_sua_bien-bg.jpg"),
            ReaderColorTheme("vip_to_quoc", R.string.reader_color_theme_vip_to_quoc, AndroidColor.parseColor("#1b1f24"), AndroidColor.parseColor("#f1f4f8"), "vip_to_quoc-bg.png"),
            ReaderColorTheme("vip_to_quoc_2", R.string.reader_color_theme_vip_to_quoc_2, AndroidColor.parseColor("#1b1f24"), AndroidColor.parseColor("#f1f4f8"), "vip_to_quoc_2-bg.png"),
            ReaderColorTheme("vip_tuoi_tho", R.string.reader_color_theme_vip_tuoi_tho, AndroidColor.parseColor("#fff7f0"), AndroidColor.parseColor("#2c241f"), "vip_tuoi_tho-bg.jpg"),
            ReaderColorTheme("vip_vo_dinh", R.string.reader_color_theme_vip_vo_dinh, AndroidColor.parseColor("#f3f0ed"), AndroidColor.parseColor("#1f1d1b"), "vip_vo_dinh-bg.jpg"),
        )

        fun findOrDefault(id: String?): ReaderColorTheme = ALL.find { it.id == id } ?: ALL.first()

        fun backgroundAssetUrl(fileName: String): String = BACKGROUND_ASSET_BASE_URL + fileName
    }
}
