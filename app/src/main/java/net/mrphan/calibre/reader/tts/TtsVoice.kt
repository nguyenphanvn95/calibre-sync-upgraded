package net.mrphan.calibre.reader.tts

/**
 * A specific voice offered by a [TtsEngine]. For [SystemTtsEngine], [id] is
 * `android.speech.tts.Voice.getName()`; for [NghiTtsEngine] (no real voices yet), the voices list
 * is always empty -- see [NghiTtsEngine].
 */
data class TtsVoice(
    val engineId: String,
    val id: String,
    val displayName: String,
    val localeTag: String,
)

/**
 * Voices grouped by engine, for the "Select Voice" UI (see [TtsVoicePickerDialog]) -- borrows the
 * TTSVoicesGroup concept from Readest (readest/apps/readest-app/src/services/tts/types.ts), where
 * each engine contributes its own group rather than one flat list of voices.
 */
data class TtsVoiceGroup(
    val engineId: String,
    val engineDisplayName: String,
    val voices: List<TtsVoice>,
    /** true if this engine isn't usable yet (not initialized, or a placeholder like
     * [NghiTtsEngine]) -- the UI disables tapping voices inside this group when true. */
    val disabled: Boolean,
)
