package net.mrphan.calibre.reader.chrome.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import net.mrphan.calibre.EpubReaderActivity
import net.mrphan.calibre.R
import net.mrphan.calibre.reader.chrome.ReaderChromeHost
import net.mrphan.calibre.reader.chrome.ReaderColorSwatch
import net.mrphan.calibre.reader.chrome.ReaderColorTheme
import net.mrphan.calibre.reader.chrome.ReaderThemeMode
import net.mrphan.calibre.reader.chrome.onProgressChanged

/**
 * "Màu" tab. Deliberately a straight port of [net.mrphan.calibre.EpubReaderActivity.openColorAndContrastSettings]'s
 * logic (screen brightness SeekBar + [ReaderColorTheme.ALL] swatch row + light/dark toggle) --
 * that dialog itself is untouched (still reachable from the footer's "Màu nền & Tương phản"
 * button), this is just the same controls inside the unified sheet. Everything here is already on
 * [ReaderChromeHost] ([ReaderChromeHost.setScreenBrightness]/[ReaderChromeHost.setColorTheme]/
 * [ReaderChromeHost.setThemeMode] and their `currentXxx()` counterparts), unlike the Font/Layout/
 * TTS tabs -- no `host as EpubReaderActivity` cast needed, and this tab applies to every
 * [net.mrphan.calibre.reader.chrome.ReaderKind] (see [ReaderSettingsTab.COLOR]).
 */
internal class ReaderSettingsColorPage(
    private val host: ReaderChromeHost,
    private val context: android.content.Context,
) : ReaderSettingsPage {

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup): View {
        val root = inflater.inflate(R.layout.page_reader_settings_color, parent, false)
        val brightnessBar = root.findViewById<SeekBar>(R.id.reader_settings_color_brightness_bar)
        val swatchRow = root.findViewById<LinearLayout>(R.id.reader_settings_color_swatch_row)
        val modeToggle = root.findViewById<ImageButton>(R.id.reader_settings_color_mode_toggle)
        val opacityGroup = root.findViewById<LinearLayout>(R.id.reader_settings_color_background_opacity_group)
        val opacityLabel = root.findViewById<TextView>(R.id.reader_settings_color_background_opacity_label)
        val opacityBar = root.findViewById<SeekBar>(R.id.reader_settings_color_background_opacity)
        val epubHost = host as? EpubReaderActivity

        val initialBrightness = host.currentScreenBrightness().let { if (it in 0..100) it else 50 }
        brightnessBar.progress = initialBrightness
        brightnessBar.setOnSeekBarChangeListener(onProgressChanged { progress ->
            host.setScreenBrightness(progress)
        })

        fun refreshOpacityControls() {
            val selectedTheme = ReaderColorTheme.findOrDefault(host.currentColorThemeId())
            val opacity = epubHost?.currentBackgroundImageOpacity() ?: 50
            opacityGroup.visibility = if (selectedTheme.backgroundImageAsset != null && epubHost != null) {
                View.VISIBLE
            } else {
                View.GONE
            }
            opacityLabel.text = context.getString(R.string.reader_background_opacity_value, opacity)
            if (opacityBar.progress != opacity) {
                opacityBar.progress = opacity
            }
        }

        opacityBar.progress = epubHost?.currentBackgroundImageOpacity() ?: 50
        opacityBar.setOnSeekBarChangeListener(onProgressChanged { progress ->
            epubHost?.setBackgroundImageOpacity(progress)
            refreshOpacityControls()
        })

        fun refreshSwatchSelection() {
            for (i in 0 until swatchRow.childCount) {
                val child = swatchRow.getChildAt(i) as TextView
                val theme = ReaderColorTheme.ALL[i]
                val isSelected = theme.id == (host.currentColorThemeId() ?: ReaderColorTheme.ALL.first().id)
                ReaderColorSwatch.bind(child, theme, isSelected)
            }
            refreshOpacityControls()
        }

        swatchRow.removeAllViews()
        for (theme in ReaderColorTheme.ALL) {
            val swatch = inflater.inflate(R.layout.item_reader_color_swatch, swatchRow, false) as TextView
            swatch.setOnClickListener {
                host.setColorTheme(theme.id)
                refreshSwatchSelection()
            }
            swatchRow.addView(swatch)
        }
        refreshSwatchSelection()

        modeToggle.setOnClickListener {
            val next = if (host.currentThemeMode() == ReaderThemeMode.DARK) ReaderThemeMode.LIGHT else ReaderThemeMode.DARK
            host.setThemeMode(next)
        }

        return root
    }
}
