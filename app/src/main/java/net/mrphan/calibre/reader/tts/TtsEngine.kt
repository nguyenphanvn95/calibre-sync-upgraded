package net.mrphan.calibre.reader.tts

import android.content.Context

/**
 * A "text to speech" tool/model -- the plugin unit for TTS, in the same spirit as
 * [net.mrphan.calibre.reader.chrome.ReaderAction] for the toolbar: to add a new TTS model (e.g.
 * a real future NghiTTS model, or another cloud engine), just write a class implementing this
 * interface and add it to [TtsEngineRegistry.engines] -- nothing else (TtsController, UI) needs to
 * change.
 */
interface TtsEngine {
    /** Stable id, used as the preference key (selected voice per engine) and as
     * [TtsVoice.engineId] / [TtsVoiceGroup.engineId]. Must not change across app versions. */
    val id: String

    val displayName: String

    /** false for an engine that has no real voice yet (e.g. [NghiTtsEngine]) -- the UI shows it
     * greyed out / not selectable, the same way [net.mrphan.calibre.reader.chrome.ReaderAction.isRelevantFor]
     * hides an inapplicable toolbar button. */
    val isAvailable: Boolean

    /** Prepares the engine (e.g. initializes `android.speech.tts.TextToSpeech`). Returns false on
     * failure -- the caller must surface that via `showMessage(...)`, never fail silently. */
    suspend fun initialize(context: Context): Boolean

    /** Voices currently offered by this engine. Empty if [initialize] hasn't succeeded yet, or if
     * [isAvailable] is false. */
    suspend fun listVoices(): List<TtsVoice>

    fun setVoice(voice: TtsVoice)

    /** 0.5f..2.0f per TextToSpeech's usual supported range; each engine coerces to its own range. */
    fun setSpeechRate(rate: Float)

    /**
     * Optional readahead hook for smoother/gapless playback: kicks off synthesizing [text] in
     * the background WITHOUT playing anything, so that when [speak] is later called with the
     * same [utteranceId] the audio is already ready and playback can begin immediately instead
     * of paying synthesis latency at that point -- this is what removes the audible gap between
     * sentences. [TtsController.resumePlaybackLoop] calls this for sentence N+1 concurrently
     * (as a child coroutine) while sentence N is being spoken via [speak], so this function is
     * itself already running "in the background" by the time it's invoked -- it does not need to
     * fork its own background work.
     *
     * Fire-and-forget and best-effort: [speak] must always work correctly whether or not
     * [prefetch] was called first, finished in time, or was skipped past entirely (e.g. the user
     * jumps to a different sentence) -- callers fall back to synthesizing normally in that case.
     * Default no-op, for engines that don't benefit from/support pre-synthesis.
     */
    suspend fun prefetch(text: String, utteranceId: String) {}

    /**
     * Speaks one sentence and suspends until it finishes, is stopped, or errors.
     * @return true if it finished normally, false if it was stopped/errored (callers should treat
     * false as "couldn't speak this sentence, skip it" rather than an exception).
     */
    suspend fun speak(text: String, utteranceId: String): Boolean

    /** Stops whatever sentence is currently being spoken (used by pause/stop/skip). */
    fun stop()

    /** Releases engine resources (e.g. `TextToSpeech.shutdown()`). Called from
     * `EpubReaderActivity#onDestroy()` via `TtsController#release()` -- must not be skipped, or
     * the native TTS engine connection leaks. */
    fun shutdown()

    /**
     * Chỉnh âm lượng phát runtime, dùng cho fade-out của Sleep Timer (xem
     * [net.mrphan.calibre.reader.tts.TtsController.startSleepTimer]). [volume] trong khoảng
     * 0f..1f. Mặc định no-op cho engine không hỗ trợ chỉnh volume runtime (ví dụ engine chỉ có
     * API "nói câu tiếp theo" mà không giữ handle tới track/player đang phát).
     */
    fun setPlaybackVolume(volume: Float) {}
}
