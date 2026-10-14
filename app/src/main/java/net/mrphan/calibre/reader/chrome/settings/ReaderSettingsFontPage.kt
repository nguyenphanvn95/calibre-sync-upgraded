package net.mrphan.calibre.reader.chrome.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import net.mrphan.calibre.EpubReaderActivity
import net.mrphan.calibre.LayoutRanges
import net.mrphan.calibre.R
import net.mrphan.calibre.reader.chrome.ReaderChromeHost
import net.mrphan.calibre.reader.chrome.onItemSelected

/**
 * "Phông chữ" tab. Reads/writes [EpubReaderActivity.currentFontSizeMultiplier]/
 * [EpubReaderActivity.currentFontFamily]/[EpubReaderActivity.currentFontWeight]/
 * [EpubReaderActivity.publisherStylesOverrideEnabled] directly (cast, see [ReaderSettingsPage]'s
 * kdoc for why that's safe here) since none of those are on [ReaderChromeHost] -- this tab only
 * makes sense for EPUB's reflowed text (see [ReaderSettingsTab.FONT]), same reasoning
 * `FontSizeAction` already uses to hide itself for PDF/CBZ.
 *
 * Font size and line height already existed as a combined dialog
 * ([EpubReaderActivity.openFontSizeControls]); that dialog is still there but no longer wired to
 * anything in the normal UI -- the footer's "Cỡ chữ" button now opens this unified
 * [ReaderSettingsSheet] directly (see `FontSizeAction`). Line height itself moved to the Layout
 * tab here (grouped with the other line/paragraph spacing controls, prompt-nang-cap-reader-
 * settings-tabbed.md muc 5.2) -- font size, font family, and the two brand-new fields (font
 * weight, publisher-styles override) stay on this tab.
 */
internal class ReaderSettingsFontPage(private val host: ReaderChromeHost) : ReaderSettingsPage {

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup): View {
        val root = inflater.inflate(R.layout.page_reader_settings_font, parent, false)
        val card = root.findViewById<LinearLayout>(R.id.reader_settings_font_card)
        val activity = host as EpubReaderActivity
        val context = card.context

        ReaderSettingsRowBinder.addStepperRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_action_font_size,
            progress = LayoutRanges.FONT_SIZE.toProgress(activity.currentFontSizeMultiplier),
            max = LayoutRanges.FONT_SIZE.maxProgress,
            formatValue = { "${(LayoutRanges.FONT_SIZE.toValue(it) * 100).toInt()}%" },
        ) { progress ->
            activity.currentFontSizeMultiplier = LayoutRanges.FONT_SIZE.toValue(progress)
            activity.applyAndPersistReaderPreferences()
        }

        ReaderSettingsRowBinder.addStepperRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_settings_font_weight,
            progress = LayoutRanges.FONT_WEIGHT.toProgress(activity.currentFontWeight),
            max = LayoutRanges.FONT_WEIGHT.maxProgress,
            formatValue = { LayoutRanges.FONT_WEIGHT.toValue(it).toInt().toString() },
        ) { progress ->
            activity.currentFontWeight = LayoutRanges.FONT_WEIGHT.toValue(progress)
            activity.applyAndPersistReaderPreferences()
        }

        val familyLabel = android.widget.TextView(context).apply {
            setText(R.string.reader_display_settings_font_family)
            textSize = 14f
            setTextColor(0xDE000000.toInt())
            setPadding(0, dp(14), 0, 0)
        }
        card.addView(familyLabel)
        val familySpinner = Spinner(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = dp(4); it.bottomMargin = dp(6) }
        }
        val familyNames = EpubReaderActivity.FONT_FAMILY_CHOICES.map { activity.familyDisplayName(it) }
        familySpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, familyNames)
        familySpinner.setSelection(
            EpubReaderActivity.FONT_FAMILY_CHOICES.indexOfFirst { it.id == activity.currentFontChoiceId() }.coerceAtLeast(0)
        )
        familySpinner.onItemSelectedListener = onItemSelected { position ->
            activity.setCurrentFontChoice(
                EpubReaderActivity.FONT_FAMILY_CHOICES.getOrNull(position)
                    ?: EpubReaderActivity.FONT_FAMILY_CHOICES.first()
            )
            activity.applyAndPersistReaderPreferences()
        }
        card.addView(familySpinner)

        ReaderSettingsRowBinder.addGroupHeader(card, inflater, R.string.reader_settings_group_advanced)
        ReaderSettingsRowBinder.addSwitchRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_settings_publisher_styles_override,
            initialValue = activity.publisherStylesOverrideEnabled,
        ) { checked ->
            activity.publisherStylesOverrideEnabled = checked
            activity.applyAndPersistReaderPreferences()
        }

        return root
    }

    private fun dp(value: Int): Int =
        (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}
