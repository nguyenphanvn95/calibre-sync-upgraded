package net.mrphan.calibre.reader.chrome.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.mrphan.calibre.EpubReaderActivity
import net.mrphan.calibre.R
import net.mrphan.calibre.reader.chrome.ReaderChromeHost
import net.mrphan.calibre.reader.tts.TtsEngineRegistry
import net.mrphan.calibre.reader.tts.TtsVoice
import net.mrphan.calibre.reader.tts.TtsVoicePickerDialog
import net.mrphan.calibre.reader.tts.music.BackgroundMusicSettingsDialog

/**
 * "Đọc to" tab -- sets the *default* voice/speed used the next time read-aloud starts (see
 * [EpubReaderActivity.toggleReadAloud], which reads [net.mrphan.calibre.reader.tts.TtsSettingsStore]
 * at session start), and a shortcut into the background-music dialog. Reuses the exact same
 * building blocks as the in-session "Tier 3" UI (prompt-nang-cap-reader-settings-tabbed.md muc
 * 5.4): [TtsEngineRegistry], [net.mrphan.calibre.reader.tts.TtsSettingsStore],
 * [TtsVoicePickerDialog], [BackgroundMusicSettingsDialog].
 *
 * Both [TtsVoicePickerDialog.show] and [BackgroundMusicSettingsDialog.show] hard-require a live
 * [net.mrphan.calibre.reader.tts.TtsController]/`BackgroundMusicController`, which only exist
 * once a read-aloud session has actually started and bound
 * [net.mrphan.calibre.reader.tts.TtsPlaybackService] (see [EpubReaderActivity.activeTtsController]/
 * [EpubReaderActivity.activeTtsService]'s kdoc) -- there is no fabricated "voiceless" controller
 * here. When no session is active yet: voice picking falls back to writing straight to
 * [net.mrphan.calibre.reader.tts.TtsSettingsStore] (still exactly what the next session reads),
 * and the background-music row honestly reports it needs an active session via
 * [ReaderChromeHost.showMessage] instead of silently no-op'ing.
 */
internal class ReaderSettingsTtsPage(
    private val host: ReaderChromeHost,
    private val context: android.content.Context,
) : ReaderSettingsPage {

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup): View {
        val root = inflater.inflate(R.layout.page_reader_settings_tts, parent, false)
        val card = root.findViewById<LinearLayout>(R.id.reader_settings_tts_card)
        val activity = host as EpubReaderActivity
        val activityContext = context as AppCompatActivity

        val voiceRow = ReaderSettingsRowBinder.addLinkRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_tts_voice_picker_title,
            value = context.getString(R.string.reader_settings_tts_voice_loading),
        ) {
            val controller = activity.activeTtsController()
            if (controller != null) {
                TtsVoicePickerDialog.show(activityContext, controller) {
                    refreshVoiceLabel(card, activity, activityContext)
                }
            } else {
                showFallbackVoicePicker(activity, activityContext) {
                    refreshVoiceLabel(card, activity, activityContext)
                }
            }
        }
        voiceRow.tag = VOICE_ROW_TAG
        refreshVoiceLabel(card, activity, activityContext)

        ReaderSettingsRowBinder.addStepperRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_tts_speed_label,
            progress = ((activity.ttsSettingsStore.getSpeechRate() - 0.5f) / 0.1f).toInt().coerceIn(0, 15),
            max = 15,
            formatValue = { "%.1f×".format(0.5f + it * 0.1f) },
        ) { progress ->
            val rate = 0.5f + progress * 0.1f
            activity.ttsSettingsStore.saveSpeechRate(rate)
            activity.activeTtsController()?.setSpeed(rate)
        }

        ReaderSettingsRowBinder.addSwitchRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_tts_auto_advance_chapter_label,
            initialValue = activity.ttsSettingsStore.getAutoAdvanceChapter(),
        ) { enabled ->
            activity.ttsSettingsStore.saveAutoAdvanceChapter(enabled)
        }

        ReaderSettingsRowBinder.addSwitchRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_tts_highlight_current_sentence_label,
            initialValue = activity.ttsSettingsStore.getHighlightCurrentSentence(),
        ) { enabled ->
            activity.ttsSettingsStore.saveHighlightCurrentSentence(enabled)
            if (!enabled) activity.clearTtsSentenceHighlightNow()
        }

        ReaderSettingsRowBinder.addSwitchRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_tts_auto_follow_sentence_label,
            initialValue = activity.ttsSettingsStore.getAutoFollowCurrentSentence(),
        ) { enabled ->
            activity.ttsSettingsStore.saveAutoFollowCurrentSentence(enabled)
        }

        ReaderSettingsRowBinder.addSwitchRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_tts_normalize_vi_text_label,
            initialValue = activity.ttsSettingsStore.getNormalizeVietnameseText(),
        ) { enabled ->
            activity.ttsSettingsStore.saveNormalizeVietnameseText(enabled)
        }

        ReaderSettingsRowBinder.addGroupHeader(card, inflater, R.string.reader_settings_group_tts_engine)
        ReaderSettingsRowBinder.addLinkRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_tts_sherpa_settings_title,
            value = context.getString(R.string.reader_settings_tts_open_label),
        ) {
            net.mrphan.calibre.reader.tts.nghi.SherpaTtsSettingsDialog.show(activityContext) {
                // Neu dang co phien doc song, ap dung numThreads moi ngay se can 1 ham reload
                // cong khai tren NghiTtsEngine (loadCurrentModelIntoSherpa hien la private).
                // Pass nay chi luu preference; lan load model tiep theo (setVoice()/initialize())
                // se tu dong doc gia tri moi, xem TtsSettingsStore#getSherpaThreadCount.
            }
        }

        ReaderSettingsRowBinder.addGroupHeader(card, inflater, R.string.reader_settings_group_tts_music)
        ReaderSettingsRowBinder.addLinkRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_tts_background_music_label,
            value = context.getString(R.string.reader_settings_tts_open_label),
        ) {
            val service = activity.activeTtsService()
            if (service != null) {
                BackgroundMusicSettingsDialog.show(activityContext, service.musicController)
            } else {
                host.showMessage(context.getString(R.string.reader_settings_tts_music_requires_session))
            }
        }

        // MOI: muc "Audio Cache" -- xem TtsAudioCache va TtsSettingsStore#getCache*.
        ReaderSettingsRowBinder.addGroupHeader(card, inflater, R.string.reader_settings_group_tts_audio_cache)

        ReaderSettingsRowBinder.addSwitchRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_tts_cache_audio_label,
            initialValue = activity.ttsSettingsStore.getCacheSynthesizedAudioEnabled(),
        ) { enabled ->
            activity.ttsSettingsStore.saveCacheSynthesizedAudioEnabled(enabled)
        }

        val cacheStorageRow = ReaderSettingsRowBinder.addLinkRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_tts_cache_storage_limit_label,
            value = "${activity.ttsSettingsStore.getCacheStorageLimitMb()} MB",
        ) {
            val options = intArrayOf(50, 100, 200, 500)
            val labels = options.map { "$it MB" }.toTypedArray()
            val currentIndex = options.indexOf(activity.ttsSettingsStore.getCacheStorageLimitMb())
                .coerceAtLeast(0)
            AlertDialog.Builder(activityContext)
                .setTitle(R.string.reader_tts_cache_storage_limit_label)
                .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                    activity.ttsSettingsStore.saveCacheStorageLimitMb(options[which])
                    dialog.dismiss()
                    refreshCacheStorageLabel(card, activity)
                }
                .setNegativeButton(R.string.reader_chrome_close, null)
                .show()
        }
        cacheStorageRow.tag = CACHE_STORAGE_ROW_TAG

        ReaderSettingsRowBinder.addLinkRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_tts_clear_cache_label,
            value = context.getString(R.string.reader_settings_tts_open_label),
        ) {
            activityContext.lifecycleScope.launch {
                net.mrphan.calibre.reader.tts.cache.TtsAudioCache.getInstance(context).clear()
                host.showMessage(context.getString(R.string.reader_tts_cache_cleared_message))
            }
        }

        return root
    }

    private fun refreshCacheStorageLabel(card: LinearLayout, activity: EpubReaderActivity) {
        val row = card.findViewWithTag<View>(CACHE_STORAGE_ROW_TAG) ?: return
        val valueLabel = row.findViewById<android.widget.TextView>(R.id.reader_settings_row_value)
        valueLabel.text = "${activity.ttsSettingsStore.getCacheStorageLimitMb()} MB"
    }

    private fun refreshVoiceLabel(card: LinearLayout, activity: EpubReaderActivity, activityContext: AppCompatActivity) {
        val voiceRow = card.findViewWithTag<View>(VOICE_ROW_TAG) ?: return
        val valueLabel = voiceRow.findViewById<android.widget.TextView>(R.id.reader_settings_row_value)
        activityContext.lifecycleScope.launch {
            val groups = TtsEngineRegistry.buildVoiceGroups(context)
            val engineId = activity.ttsSettingsStore.getSelectedEngineId() ?: TtsEngineRegistry.default().id
            val voiceId = activity.ttsSettingsStore.getSelectedVoiceId(engineId)
            val voice = groups.firstOrNull { it.engineId == engineId }?.voices?.firstOrNull { it.id == voiceId }
            valueLabel.text = voice?.displayName
                ?: groups.firstOrNull { it.engineId == engineId }?.engineDisplayName
                ?: context.getString(R.string.reader_font_family_default)
        }
    }

    /** Minimal stand-in for [TtsVoicePickerDialog] when no session is active yet (that dialog
     * hard-requires a live `TtsController` to call `setVoice()` on) -- a flat, single-level list
     * instead of the grouped ListView adapter, since this is only choosing a *default* for the
     * next session, not the richer in-session "Tier 3" experience. Saves straight to
     * [net.mrphan.calibre.reader.tts.TtsSettingsStore], exactly what
     * [EpubReaderActivity.toggleReadAloud] reads at session start. */
    private fun showFallbackVoicePicker(
        activity: EpubReaderActivity,
        activityContext: AppCompatActivity,
        onVoiceChanged: () -> Unit,
    ) {
        activityContext.lifecycleScope.launch {
            val groups = TtsEngineRegistry.buildVoiceGroups(context).filter { !it.disabled && it.voices.isNotEmpty() }
            val flatVoices = groups.flatMap { it.voices }
            if (flatVoices.isEmpty()) {
                host.showMessage(context.getString(R.string.reader_tts_init_failed))
                return@launch
            }
            val labels = flatVoices.map { voice: TtsVoice -> voice.displayName }
            AlertDialog.Builder(activityContext)
                .setTitle(R.string.reader_tts_voice_picker_title)
                .setItems(labels.toTypedArray()) { _, which ->
                    val voice = flatVoices[which]
                    activity.ttsSettingsStore.saveSelectedEngineId(voice.engineId)
                    activity.ttsSettingsStore.saveSelectedVoiceId(voice.engineId, voice.id)
                    onVoiceChanged()
                }
                .setNegativeButton(R.string.reader_chrome_close, null)
                .show()
        }
    }

    companion object {
        private const val VOICE_ROW_TAG = "reader_settings_tts_voice_row"
        private const val CACHE_STORAGE_ROW_TAG = "reader_settings_tts_cache_storage_row"
    }
}
