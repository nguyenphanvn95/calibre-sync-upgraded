package net.mrphan.calibre.reader.tts

import android.content.Context

/**
 * Persists TTS preferences (selected engine/voice per engine, speech rate) in their own
 * SharedPreferences file, following the same precedent as `AppRater`
 * (`context.getSharedPreferences("apprater", 0)`) rather than
 * `PreferenceManager.getDefaultSharedPreferences` (used by `SettingsActivity`). Not tied into
 * `MetadataDatabaseHelper` -- these are app-wide TTS preferences, not per-book data, so no DB
 * schema/migration is needed for this feature.
 */
class TtsSettingsStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSelectedEngineId(): String? = prefs.getString(KEY_ENGINE_ID, null)

    fun saveSelectedEngineId(engineId: String) {
        prefs.edit().putString(KEY_ENGINE_ID, engineId).apply()
    }

    fun getSelectedVoiceId(engineId: String): String? =
        prefs.getString(KEY_VOICE_PREFIX + engineId, null)

    fun saveSelectedVoiceId(engineId: String, voiceId: String) {
        prefs.edit().putString(KEY_VOICE_PREFIX + engineId, voiceId).apply()
    }

    fun getSpeechRate(): Float = prefs.getFloat(KEY_RATE, 1.0f)

    fun saveSpeechRate(rate: Float) {
        prefs.edit().putFloat(KEY_RATE, rate).apply()
    }

    /** Khoang nghi (ms) truoc moi cau KHONG phai dau doan van -- tach rieng khoi speechRate,
     * dung thoi gian thuc (TtsController#pauseBeforeSentence dung delay() thang, khong nhan
     * theo rate) de khong bi keo gian ty le o toc do doc nhanh (1.5x/2x). Mac dinh 150ms. */
    fun getSentencePauseMs(): Int = prefs.getInt(KEY_SENTENCE_PAUSE_MS, DEFAULT_SENTENCE_PAUSE_MS)

    fun saveSentencePauseMs(ms: Int) {
        prefs.edit().putInt(KEY_SENTENCE_PAUSE_MS, ms.coerceIn(0, 600)).apply()
    }

    /** Khoang nghi (ms) truoc cau dau tien cua 1 doan van moi (xem
     * TtsSentenceSplitter.TtsSentenceSpan#isParagraphStart) -- dai hon sentence pause vi day la
     * ranh gioi doan, khong phai giua 2 cau lien tiep trong cung doan. Mac dinh 300ms. */
    fun getParagraphPauseMs(): Int = prefs.getInt(KEY_PARAGRAPH_PAUSE_MS, DEFAULT_PARAGRAPH_PAUSE_MS)

    fun saveParagraphPauseMs(ms: Int) {
        prefs.edit().putInt(KEY_PARAGRAPH_PAUSE_MS, ms.coerceIn(0, 2000)).apply()
    }

    /** Whether TtsController should automatically move on to the next chapter once the current
     * one finishes, or just stop (see TtsController#resumePlaybackLoop). Defaults to true, which
     * matches the pre-existing always-auto-advance behaviour. */
    fun getAutoAdvanceChapter(): Boolean = prefs.getBoolean(KEY_AUTO_ADVANCE_CHAPTER, true)

    fun saveAutoAdvanceChapter(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_ADVANCE_CHAPTER, enabled).apply()
    }

    /** Co bat/tat tinh nang to sang cau dang doc ngay trong noi dung sach (khong phai chi trong
     * player) -- xem TtsSentenceHighlighter. Mac dinh BAT. */
    fun getHighlightCurrentSentence(): Boolean = prefs.getBoolean(KEY_HIGHLIGHT_SENTENCE, true)

    fun saveHighlightCurrentSentence(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HIGHLIGHT_SENTENCE, enabled).apply()
    }

    /** Co bat/tat tu dong cuon/lat trang theo cau dang doc (nav.go(locator) moi khi doi cau) --
     * xem EpubReaderActivity#applyTtsSentenceHighlightIfNeeded. Mac dinh BAT, giong
     * [getHighlightCurrentSentence]. Doc lap voi co do: nguoi dung co the bat to sang nhung tat
     * tu dong cuon (vd de tu lat trang bang tay ma van thay cau dang doc duoc to sang khi no
     * nam trong trang hien tai). */
    fun getAutoFollowCurrentSentence(): Boolean = prefs.getBoolean(KEY_AUTO_FOLLOW_SENTENCE, true)

    fun saveAutoFollowCurrentSentence(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_FOLLOW_SENTENCE, enabled).apply()
    }

    /** So luong CPU sherpa-onnx dung de tong hop giong NghiTTS (xem
     * NghiTtsEngine#loadCurrentModelIntoSherpa). null = chua nguoi dung nao chinh tay,
     * dung SherpaThreadRecommender.recommendedThreadCount() lam mac dinh. */
    fun getSherpaThreadCount(): Int? =
        prefs.getInt(KEY_SHERPA_THREADS, -1).takeIf { it > 0 }

    fun saveSherpaThreadCount(threads: Int) {
        prefs.edit().putInt(KEY_SHERPA_THREADS, threads).apply()
    }

    /** Bat/tat cache audio TTS ben vung tren dia (xem TtsAudioCache) -- mac dinh BAT vi loi ich
     * (giam round-trip mang / suy luan ONNX khi tua lai) lon hon chi phi dung luong dia trong
     * hau het truong hop, va da co gioi han dung luong (xem getCacheStorageLimitMb). */
    fun getCacheSynthesizedAudioEnabled(): Boolean =
        prefs.getBoolean(KEY_CACHE_AUDIO_ENABLED, true)

    fun saveCacheSynthesizedAudioEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CACHE_AUDIO_ENABLED, enabled).apply()
    }

    /** Han dung luong (MB) cho TtsAudioCache -- mac dinh 200MB, khop voi mockup UI. Cac gia
     * tri cho phep: 50/100/200/500 (xem UI trong ReaderSettingsTtsPage). */
    fun getCacheStorageLimitMb(): Int = prefs.getInt(KEY_CACHE_STORAGE_LIMIT_MB, 200)

    fun saveCacheStorageLimitMb(mb: Int) {
        prefs.edit().putInt(KEY_CACHE_STORAGE_LIMIT_MB, mb.coerceIn(20, 2000)).apply()
    }

    /** So cau se duoc prefetch() truoc, tinh tu cau dang phat -- xem
     * TtsController#resumePlaybackLoop. Mac dinh 2: du de xoa khoang lang giua cac cau ma
     * khong tao qua nhieu prefetch() dong thoi (moi prefetch() la 1 request mang that su voi
     * Edge TTS, hoac 1 lan suy luan ONNX that su voi NghiTTS -- prefetch qua sau se ton tai
     * nguyen/pin ma khong tang loi ich thuc te vi nguoi dung nghe tuan tu). */
    fun getPrefetchAheadCount(): Int = prefs.getInt(KEY_PREFETCH_AHEAD_COUNT, 2)

    fun savePrefetchAheadCount(count: Int) {
        prefs.edit().putInt(KEY_PREFETCH_AHEAD_COUNT, count.coerceIn(1, 4)).apply()
    }

    /** Bat/tat buoc chuan hoa van ban tieng Viet (so/ngay/tien te/... -> chu) truoc khi dua vao
     * TtsEngine -- xem VietnameseTextNormalizer. Mac dinh BAT vi hau het nguoi dung nghe sach/bao
     * tieng Viet se muon so duoc doc thanh loi noi thay vi tung ky tu. */
    fun getNormalizeVietnameseText(): Boolean = prefs.getBoolean(KEY_NORMALIZE_VI_TEXT, true)

    fun saveNormalizeVietnameseText(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NORMALIZE_VI_TEXT, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "tts_settings"
        private const val KEY_ENGINE_ID = "engine_id"
        private const val KEY_VOICE_PREFIX = "voice_id_"
        private const val KEY_RATE = "speech_rate"
        private const val KEY_SENTENCE_PAUSE_MS = "sentence_pause_ms"
        private const val KEY_PARAGRAPH_PAUSE_MS = "paragraph_pause_ms"
        private const val DEFAULT_SENTENCE_PAUSE_MS = 150
        private const val DEFAULT_PARAGRAPH_PAUSE_MS = 300
        private const val KEY_AUTO_ADVANCE_CHAPTER = "auto_advance_chapter"
        private const val KEY_HIGHLIGHT_SENTENCE = "highlight_current_sentence"
        private const val KEY_AUTO_FOLLOW_SENTENCE = "auto_follow_current_sentence"
        private const val KEY_SHERPA_THREADS = "sherpa_thread_count"
        private const val KEY_CACHE_AUDIO_ENABLED = "cache_synthesized_audio_enabled"
        private const val KEY_CACHE_STORAGE_LIMIT_MB = "cache_storage_limit_mb"
        private const val KEY_PREFETCH_AHEAD_COUNT = "prefetch_ahead_count"
        private const val KEY_NORMALIZE_VI_TEXT = "normalize_vi_text"
    }
}
