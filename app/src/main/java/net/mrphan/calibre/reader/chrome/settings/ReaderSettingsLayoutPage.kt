package net.mrphan.calibre.reader.chrome.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import net.mrphan.calibre.EpubReaderActivity
import net.mrphan.calibre.LayoutRanges
import net.mrphan.calibre.R
import net.mrphan.calibre.reader.chrome.ReaderChromeHost

/**
 * "Bố cục" tab. Every field here is a real
 * `org.readium.r2.navigator.epub.EpubPreferences` property, verified against the Readium 3.1.2
 * navigator/preferences guide (prompt-nang-cap-reader-settings-tabbed.md muc 3): `lineHeight`
 * (moved here from the Font tab, see [ReaderSettingsFontPage]'s kdoc), `paragraphSpacing`,
 * `paragraphIndent`, `wordSpacing`, `letterSpacing`, `textAlign` (Justify on/off), `hyphens`,
 * `columnCount` (2-column on/off), `pageMargins`.
 *
 * Six of those nine (everything except line height, column count, and page margins) only take
 * effect once [EpubReaderActivity.publisherStylesOverrideEnabled] is on -- Readium requires
 * `publisherStyles = false` first (see [EpubReaderActivity.buildCurrentEpubPreferences]'s kdoc).
 * That switch itself lives on the Font tab (per prompt muc 5.1's own placement of "Ghi đè font
 * sách"); this page just greys the six dependent rows out and points back to it, rather than
 * duplicating a second switch that would fight over the same preference.
 */
internal class ReaderSettingsLayoutPage(private val host: ReaderChromeHost) : ReaderSettingsPage {

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup): View {
        val root = inflater.inflate(R.layout.page_reader_settings_layout, parent, false)
        val card = root.findViewById<LinearLayout>(R.id.reader_settings_layout_card)
        val activity = host as EpubReaderActivity
        val overrideOn = activity.publisherStylesOverrideEnabled

        ReaderSettingsRowBinder.addGroupHeader(card, inflater, R.string.reader_settings_group_spacing)

        ReaderSettingsRowBinder.addStepperRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_display_settings_line_height,
            progress = LayoutRanges.LINE_HEIGHT.toProgress(activity.currentLineHeight),
            max = LayoutRanges.LINE_HEIGHT.maxProgress,
            formatValue = { "%.1f".format(LayoutRanges.LINE_HEIGHT.toValue(it)) },
        ) { progress ->
            activity.currentLineHeight = LayoutRanges.LINE_HEIGHT.toValue(progress)
            activity.applyAndPersistReaderPreferences()
        }

        ReaderSettingsRowBinder.addStepperRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_settings_page_margins,
            progress = LayoutRanges.PAGE_MARGINS.toProgress(activity.currentPageMargins),
            max = LayoutRanges.PAGE_MARGINS.maxProgress,
            formatValue = { "%.1f".format(LayoutRanges.PAGE_MARGINS.toValue(it)) },
        ) { progress ->
            activity.currentPageMargins = LayoutRanges.PAGE_MARGINS.toValue(progress)
            activity.applyAndPersistReaderPreferences()
        }

        ReaderSettingsRowBinder.addSwitchRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_settings_two_column,
            initialValue = activity.twoColumnEnabled,
        ) { checked ->
            activity.twoColumnEnabled = checked
            activity.applyAndPersistReaderPreferences()
        }

        ReaderSettingsRowBinder.addGroupHeader(card, inflater, R.string.reader_settings_group_advanced)

        val hint = android.widget.TextView(card.context).apply {
            setText(R.string.reader_settings_advanced_requires_override)
            textSize = 12f
            setTextColor(0x8A000000.toInt())
            visibility = if (overrideOn) View.GONE else View.VISIBLE
        }
        card.addView(hint)

        val paragraphSpacingRow = ReaderSettingsRowBinder.addStepperRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_settings_paragraph_spacing,
            progress = LayoutRanges.PARAGRAPH_SPACING.toProgress(activity.currentParagraphSpacing),
            max = LayoutRanges.PARAGRAPH_SPACING.maxProgress,
            formatValue = { "%.2f".format(LayoutRanges.PARAGRAPH_SPACING.toValue(it)) },
        ) { progress ->
            activity.currentParagraphSpacing = LayoutRanges.PARAGRAPH_SPACING.toValue(progress)
            activity.applyAndPersistReaderPreferences()
        }

        val paragraphIndentRow = ReaderSettingsRowBinder.addStepperRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_settings_paragraph_indent,
            progress = LayoutRanges.PARAGRAPH_INDENT.toProgress(activity.currentParagraphIndent),
            max = LayoutRanges.PARAGRAPH_INDENT.maxProgress,
            formatValue = { "%.2f".format(LayoutRanges.PARAGRAPH_INDENT.toValue(it)) },
        ) { progress ->
            activity.currentParagraphIndent = LayoutRanges.PARAGRAPH_INDENT.toValue(progress)
            activity.applyAndPersistReaderPreferences()
        }

        val wordSpacingRow = ReaderSettingsRowBinder.addStepperRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_settings_word_spacing,
            progress = LayoutRanges.WORD_SPACING.toProgress(activity.currentWordSpacing),
            max = LayoutRanges.WORD_SPACING.maxProgress,
            formatValue = { "%.2f".format(LayoutRanges.WORD_SPACING.toValue(it)) },
        ) { progress ->
            activity.currentWordSpacing = LayoutRanges.WORD_SPACING.toValue(progress)
            activity.applyAndPersistReaderPreferences()
        }

        val letterSpacingRow = ReaderSettingsRowBinder.addStepperRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_settings_letter_spacing,
            progress = LayoutRanges.LETTER_SPACING.toProgress(activity.currentLetterSpacing),
            max = LayoutRanges.LETTER_SPACING.maxProgress,
            formatValue = { "%.2f".format(LayoutRanges.LETTER_SPACING.toValue(it)) },
        ) { progress ->
            activity.currentLetterSpacing = LayoutRanges.LETTER_SPACING.toValue(progress)
            activity.applyAndPersistReaderPreferences()
        }

        val justifyRow = ReaderSettingsRowBinder.addSwitchRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_settings_justify_text,
            initialValue = activity.justifyTextEnabled,
        ) { checked ->
            activity.justifyTextEnabled = checked
            activity.applyAndPersistReaderPreferences()
        }

        val hyphensRow = ReaderSettingsRowBinder.addSwitchRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_settings_hyphens,
            initialValue = activity.hyphensEnabled,
        ) { checked ->
            activity.hyphensEnabled = checked
            activity.applyAndPersistReaderPreferences()
        }

        for (row in listOf(paragraphSpacingRow, paragraphIndentRow, wordSpacingRow, letterSpacingRow, justifyRow, hyphensRow)) {
            row.isEnabled = overrideOn
            setDescendantsEnabled(row, overrideOn)
        }

        return root
    }

    /** `View.isEnabled = false` on a container doesn't automatically greyed out/disable its
     * children in a plain LinearLayout (unlike e.g. a ListView row) -- has to be applied to every
     * descendant so the SeekBar/Switch itself actually stops responding to touches while the
     * override switch on the Font tab is off. */
    private fun setDescendantsEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                setDescendantsEnabled(view.getChildAt(i), enabled)
            }
        }
    }
}
