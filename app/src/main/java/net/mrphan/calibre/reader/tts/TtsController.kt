package net.mrphan.calibre.reader.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mrphan.calibre.reader.tts.dictionary.TtsDictionaryReplacer
import net.mrphan.calibre.reader.tts.dictionary.TtsDictionaryStore
import net.mrphan.calibre.reader.tts.normalize.VietnameseTextNormalizer

/**
 * Orchestrates "read aloud": splits the current chapter into sentences
 * ([TtsSentenceSplitter]), speaks them one by one through whichever [TtsEngine] is selected,
 * automatically advances to the next chapter once sentences run out (via
 * [onRequestNextChapter]), and exposes playback state ([state]) for the UI (mini player, bottom
 * sheet) to observe.
 *
 * Modeled after Readest's TTSController
 * (readest/apps/readest-app/src/services/tts/TTSController.ts): plays by "mark" = one sentence,
 * supports play/pause/resume/stop as distinct operations, and doesn't depend on any concrete
 * engine (an engine only needs to implement [TtsEngine]).
 */
class TtsController(
    private val scope: CoroutineScope,
    private val settingsStore: TtsSettingsStore,
    private val onRequestNextChapter: suspend () -> ChapterText?,
    // Tham so moi cho "Tu dien phat am" TTS -- xem TtsPlaybackService cho cach khoi tao
    // TtsDictionaryStore va truyen vao day.
    private val dictionaryStore: TtsDictionaryStore,
    // Tham so moi cho "Chuan hoa van ban tieng Viet" (so/ngay/tien te -> chu) -- can Context de
    // VietnameseTextNormalizer nap acronyms.csv/non_vietnamese_words.csv tu assets. Dung
    // applicationContext (xem TtsPlaybackService), khong giu Activity Context de tranh leak.
    context: android.content.Context,
) {
    private val textNormalizer = VietnameseTextNormalizer.getInstance(context)
    /** The text of one chapter to read: [href] identifies which chapter this is (used as a
     * display key), [title] is shown on the mini player / bottom sheet. */
    data class ChapterText(val href: String, val title: String, val text: String)

    companion object {
        // Toc doc trung binh o rate = 1.0f, dung de uoc luong thoi luong "ao" cho tung cau
        // (xem estimateSentenceDurationsMs) -- khong phai timestamp audio that.
        private const val ESTIMATED_CHARS_PER_SECOND = 15.0

        // MOI: so ky tu ngu canh truoc/sau cau dang doc, dung cho Locator.Text.before/after
        // (xem TtsSentenceHighlighter.buildLocator) de Readium khop DOM chinh xac hon.
        private const val HIGHLIGHT_CONTEXT_CHARS = 40

        private const val SLEEP_TIMER_FADE_DURATION_MS = 10_000L
        private const val SLEEP_TIMER_FADE_STEPS = 20 // ~500ms/buoc, du muot ma khong qua nhieu wakeup

        private fun estimateSentenceDurationsMs(sentences: List<String>, rate: Float): List<Long> {
            val safeRate = rate.coerceAtLeast(0.1f)
            return sentences.map { sentence ->
                val seconds = sentence.length / ESTIMATED_CHARS_PER_SECOND / safeRate
                (seconds * 1000).toLong().coerceAtLeast(300L) // toi thieu 300ms/cau
            }
        }

        private fun hasReadableContent(text: String): Boolean =
            text.any { it.isLetterOrDigit() }
    }

    private val _state = MutableStateFlow(TtsPlaybackState.idle().copy(speechRate = settingsStore.getSpeechRate()))
    val state: StateFlow<TtsPlaybackState> = _state

    private var engine: TtsEngine = TtsEngineRegistry.default()
    private var sentences: List<String> = emptyList()
    // MOI: offset song song 1-1 voi `sentences` (cung do dai list), tinh tu van ban GOC cua
    // chuong -- xem TtsSentenceSplitter.TtsSentenceSpan. Dung de TtsSentenceHighlighter to sang
    // dung vi tri cau dang doc ngay trong noi dung sach.
    private var sentenceSpans: List<TtsSentenceSplitter.TtsSentenceSpan> = emptyList()
    // MOI: van ban GOC cua chuong (truoc moi buoc thay the), song song voi sentenceSpans -- dung
    // de cat ngu canh before/after cho highlighter, KHONG dung speechSentences (da qua tu dien/
    // chuan hoa, co the lech offset so voi DOM that cua sach).
    private var chapterRawText: String = ""
    // MOI: danh sach song song cung do dai voi `sentences`, da ap dung Tu dien phat am -- day
    // la thu THUC SU duoc dua vao engine.prefetch()/engine.speak() (xem speechSentenceAt() ben
    // duoi). `sentences` (ban goc) van la thu duy nhat dua vao `currentSentenceText` de hien
    // thi, khong doi -- xem TtsDictionaryReplacer kdoc.
    private var speechSentences: List<String> = emptyList()
    // MOI: tong do dai van ban chuong hien tai, dung lam mau so tinh progression.
    private var chapterTotalTextLength: Int = 0
    private var sentenceIndex = 0
    private var chapterTitle: String = ""
    private var chapterHref: String = ""
    // BUG FIX: tang moi lan startChapter() duoc goi -- xem cach dung trong resumePlaybackLoop().
    // utteranceId truoc day chi la "tts-$sentenceIndex" (KHONG gan voi chuong nao), nen prefetchCache
    // (RAM, trong EdgeTtsEngine/NghiTtsEngine) co the bi dung nham audio cua CHUONG CU: khi
    // onRequestNextChapter() tu dong chuyen sang chuong ke, startChapter() KHONG goi engine.stop()
    // (co chu dich) nen mot tac vu prefetch() con dang chay do cua chuong cu (vd dang tong hop cau
    // cuoi "tts-3") van co the ghi xong vao prefetchCache["tts-3"] SAU khi chuong moi da bat dau --
    // neu luc do chuong moi cung dang o cau index 3, speak() se lay NHAM audio cua chuong cu (dung
    // la trieu chung "doc nham 1 cau cua chuong truoc"). Ghep them chapterSessionId vao utteranceId
    // dam bao key khong bao gio trung giua 2 chuong, ke ca khi ghi tre xay ra.
    private var chapterSessionId: Long = 0L
    private var playJob: Job? = null
    private var sleepTimerJob: Job? = null

    /** Theo doi thoi gian tong hop trung binh truot (rolling average, N mau gan nhat) de tinh
     * prefetch window thich ung -- thay vi hang so co dinh tu Settings cho MOI engine. Gia tri
     * nguoi dung chinh trong Settings (getPrefetchAheadCount) van duoc dung lam TRAN TREN (khong
     * bao gio vuot qua), tranh prefetch qua da neu do do lech thuc te vi ly do nao do. */
    private class RollingLatencyTracker(private val windowSize: Int = 5) {
        private val samplesMs = ArrayDeque<Long>()

        fun record(latencyMs: Long) {
            samplesMs.addLast(latencyMs)
            if (samplesMs.size > windowSize) samplesMs.removeFirst()
        }

        fun averageMsOrNull(): Long? =
            if (samplesMs.isEmpty()) null else samplesMs.sum() / samplesMs.size
    }

    private val synthesisLatencyTracker = RollingLatencyTracker()

    // Uoc luong thoi luong 1 cau "trung binh" (dung do dai cau gan nhat da phat, hoac 60 ky tu
    // lam gia tri mac dinh hop ly neu chua co du lieu) -- dat rieng ham nho de test/tai su dung.
    private var lastSentenceLength: Int = 60

    /** Uoc luong so cau can prefetch truoc de "che" het do tre tong hop trung binh gan day, dua
     * tren toc do doc trung binh (ky tu/giay) da dung cho estimateSentenceDurationsMs. Vi du: do
     * tre tong hop trung binh 600ms, 1 cau ~15 ky tu doc mat ~15/15.0/rate giay -- neu do tre lon
     * hon thoi luong 1 cau, can prefetch 2+ cau truoc de khong bi khoang lang. Ket qua duoc gioi
     * han trong [1, tuy nguoi dung chon trong Settings] -- KHONG bao gio vuot qua tran nguoi dung
     * da dat, chi tu dong giam xuong duoi tran do neu engine hien tai von di rat nhanh (tiet kiem
     * CPU/pin cho NghiTTS). */
    private fun adaptivePrefetchAheadCount(userMaxCount: Int, rate: Float): Int {
        val avgLatencyMs = synthesisLatencyTracker.averageMsOrNull() ?: return userMaxCount
        val avgSentenceDurationMs = estimateSentenceDurationForAdaptive(rate)
        if (avgSentenceDurationMs <= 0L) return userMaxCount
        val neededCount = (avgLatencyMs / avgSentenceDurationMs.toDouble()).let { ratio ->
            kotlin.math.ceil(ratio).toInt().coerceAtLeast(1)
        }
        return neededCount.coerceIn(1, userMaxCount)
    }

    private fun estimateSentenceDurationForAdaptive(rate: Float): Long {
        val safeRate = rate.coerceAtLeast(0.1f)
        return (lastSentenceLength / ESTIMATED_CHARS_PER_SECOND / safeRate * 1000).toLong()
            .coerceAtLeast(300L)
    }

    suspend fun setEngine(newEngine: TtsEngine, context: android.content.Context) {
        engine.stop()
        engine = newEngine
        if (newEngine.isAvailable) newEngine.initialize(context)
        newEngine.setSpeechRate(_state.value.speechRate)
        settingsStore.saveSelectedEngineId(newEngine.id)
    }

    /** Applies [voice], switching the active engine first if [voice] belongs to a different
     * engine than the one currently playing (e.g. picking an Edge TTS voice while NghiTTS is
     * active). Previously this just called `engine.setVoice(voice)` on whatever engine happened
     * to already be active and saved the id under that engine's key -- so picking a voice from a
     * non-active engine silently did nothing (wrong engine ignored/misapplied the voice) and the
     * picker could never show the correct checkmark again on reopen. */
    suspend fun setVoice(voice: TtsVoice, context: android.content.Context) {
        if (voice.engineId != engine.id) {
            val targetEngine = TtsEngineRegistry.byId(voice.engineId) ?: return
            setEngine(targetEngine, context)
        }
        engine.setVoice(voice)
        settingsStore.saveSelectedVoiceId(engine.id, voice.id)
    }

    fun setSpeed(rate: Float) {
        engine.setSpeechRate(rate)
        settingsStore.saveSpeechRate(rate)
        // Tinh lai sentenceDurationsMs theo rate moi, tranh seekbar cua notification bi lech ty
        // le so voi toc do doc thuc te sau khi doi toc do giua chung.
        _state.update {
            it.copy(
                speechRate = rate,
                sentenceDurationsMs = estimateSentenceDurationsMs(sentences, rate),
            )
        }
    }

    /** Starts reading [chapter] from the beginning (or [startSentenceIndex]). Called when the
     * user first taps "Đọc cho nghe", or when [TtsController] auto-advances to the next
     * chapter. */
    fun startChapter(chapter: ChapterText, startSentenceIndex: Int = 0) {
        // BUG FIX: xem kdoc cua chapterSessionId -- moi phien chuong co 1 id rieng de utteranceId
        // (dung trong resumePlaybackLoop) khong bao gio trung voi chuong truoc/sau, ke ca khi doc
        // lai chinh chuong nay (vd nguoi dung tua lui).
        chapterSessionId++
        val spans = TtsSentenceSplitter.splitWithOffsets(chapter.text)
        sentences = spans.map { it.text }
        sentenceSpans = spans
        // Reset speechSentences ve rong ngay khi doi chuong -- neu chuong truoc do co nhieu cau
        // hon chuong nay, size cu se KHONG con khop voi `sentences` moi, nen speechSentenceAt()
        // se tu dong fallback ve cau goc cho toi khi entries duoi day nap xong (xem ghi chu
        // race-condition o duoi).
        speechSentences = emptyList()
        chapterRawText = chapter.text
        chapterTotalTextLength = chapter.text.length
        sentenceIndex = startSentenceIndex.coerceIn(0, (sentences.size - 1).coerceAtLeast(0))
        chapterTitle = chapter.title
        chapterHref = chapter.href
        _state.update {
            it.copy(sentenceDurationsMs = estimateSentenceDurationsMs(sentences, it.speechRate))
        }
        // Tai lai Tu dien phat am MOI LAN bat dau 1 chuong (thay vi cache vinh vien trong
        // TtsController), de nguoi dung them/sua/xoa tu dien trong luc dang doc van co hieu luc
        // tu chuong ke tiep ma khong can restart app. Chay trong 1 coroutine con cua chinh
        // `scope` (khong phai suspend hoa startChapter) -- xem ghi chu race-condition ben duoi
        // ve ly do va cach xu ly.
        //
        // Ghi chu race-condition (xem thao luan trong prompt nang cap tinh nang nay): coroutine
        // nay chay bat dong bo song song voi resumePlaybackLoop() ngay ben duoi, nen co the cau
        // dau tien cua chuong da duoc phat truoc khi entries kip nap xong. Chon huong xu ly (b):
        // speechSentenceAt() fallback ve `sentences[index]` (cau goc, chua qua tu dien) cho
        // rieng cau chua kip thay the, thay vi hoan doi startChapter thanh suspend fun (huong a)
        // -- vi startChapter dang duoc goi tu nhieu noi (bam "Doc cho nghe", tu dong chuyen
        // chuong trong resumePlaybackLoop(), seek...) va doi toan bo cac call site do sang
        // suspend se lan rong hon can thiet cho 1 tinh năng phu (phat am dung ten rieng), trong
        // khi cai gia toi thieu cua huong (b) chi la 1 cau duy nhat trong ca chuong co the duoc
        // phat bang van ban goc thay vi ban da thay the.
        scope.launch {
            val entries = withContext(Dispatchers.IO) { dictionaryStore.getAll() }
            val normalizeEnabled = settingsStore.getNormalizeVietnameseText()
            speechSentences = sentences.map { sentence ->
                val normalized = if (normalizeEnabled) {
                    textNormalizer.normalize(sentence)
                } else {
                    sentence
                }
                TtsDictionaryReplacer.apply(normalized, entries)
            }
        }
        resumePlaybackLoop()
    }

    /** Cau da qua Tu dien phat am de dua vao engine, hoac cau goc neu speechSentences chua kip
     * cap nhat xong cho chuong hien tai (xem ghi chu race-condition o startChapter()). */
    private fun speechSentenceAt(index: Int): String =
        speechSentences.takeIf { it.size == sentences.size }?.getOrNull(index)
            ?: sentences.getOrNull(index)
            ?: ""

    fun pause() {
        playJob?.cancel()
        engine.stop()
        _state.update { it.copy(isPlaying = false) }
    }

    fun resume() {
        if (sentences.isEmpty()) return
        resumePlaybackLoop()
    }

    fun stop() {
        playJob?.cancel()
        engine.stop()
        cancelSleepTimer()
        sentences = emptyList()
        sentenceSpans = emptyList()
        speechSentences = emptyList()
        chapterRawText = ""
        chapterTotalTextLength = 0
        sentenceIndex = 0
        _state.value = TtsPlaybackState.idle().copy(speechRate = _state.value.speechRate)
    }

    fun skipToNextSentence() {
        if (sentenceIndex < sentences.size - 1) {
            sentenceIndex++
            resumePlaybackLoop()
        }
    }

    fun skipToPreviousSentence() {
        if (sentenceIndex > 0) {
            sentenceIndex--
            resumePlaybackLoop()
        }
    }

    /** Nhay den cau [index] va tiep tuc phat tu do -- dung khi nguoi dung keo thanh tien trinh
     * trong notification (xem TtsPlaybackService.mediaSessionCallback.onSeekTo). */
    fun seekToSentence(index: Int) {
        if (sentences.isEmpty()) return
        sentenceIndex = index.coerceIn(0, sentences.size - 1)
        resumePlaybackLoop()
    }

    fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()
        sleepTimerJob = scope.launch {
            val totalMs = minutes * 60_000L
            val mainDelayMs = (totalMs - SLEEP_TIMER_FADE_DURATION_MS).coerceAtLeast(0L)
            delay(mainDelayMs)
            // MOI: fade-out am luong dan deu trong SLEEP_TIMER_FADE_DURATION_MS truoc khi
            // pause() that su -- cam giac tu nhien hon dung dot ngot, dac biet quan trong cho use
            // case nghe truoc khi ngu. engine.setPlaybackVolume() la no-op an toan cho engine
            // khong ho tro (xem TtsEngine kdoc), nen khong can kiem tra rieng o day.
            if (mainDelayMs > 0) {
                val stepDelayMs = SLEEP_TIMER_FADE_DURATION_MS / SLEEP_TIMER_FADE_STEPS
                for (step in SLEEP_TIMER_FADE_STEPS downTo 1) {
                    val volume = step.toFloat() / SLEEP_TIMER_FADE_STEPS
                    engine.setPlaybackVolume(volume)
                    delay(stepDelayMs)
                }
            }
            pause()
            engine.setPlaybackVolume(1f) // reset ve day am cho lan phat ke tiep (resume() thu cong)
            _state.update { it.copy(sleepTimerMinutesRemaining = null) }
        }
        _state.update { it.copy(sleepTimerMinutesRemaining = minutes) }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        engine.setPlaybackVolume(1f) // dam bao khong ket dinh volume thap neu huy giua luc dang fade
        _state.update { it.copy(sleepTimerMinutesRemaining = null) }
    }

    /** Chen 1 khoang nghi TRUOC khi phat cau [index], dung [kotlinx.coroutines.delay] voi thoi
     * gian THUC (mili-giay that), KHONG nhan theo speechRate -- day chinh la diem sua loi mo ta
     * trong readest#2837 ("FR: TTS long pauses"): truoc day khoang nghi giua cac cau bi ti le
     * thuan theo `sentence.length / ESTIMATED_CHARS_PER_SECOND / rate` (xem
     * estimateSentenceDurationsMs), nen o toc do doc nhanh (1.5x/2x) khoang nghi bi keo GIAN ra
     * thay vi ngan lai, lam trai nghiem cam giac cham hon toc do da chon. Tach rieng pause khoi
     * rate: dung dung gia tri nguoi dung dat trong Advanced Speed Settings
     * (TtsSettingsStore#getSentencePauseMs/getParagraphPauseMs), khong phu thuoc engine dang
     * chon hay toc do doc hien tai. Khong nghi truoc cau dau tien (index <= 0) vi luc do
     * chua co cau nao vua phat xong ca. */
    private suspend fun pauseBeforeSentence(index: Int) {
        if (index <= 0 || index >= sentenceSpans.size) return
        val pauseMs = if (sentenceSpans[index].isParagraphStart) {
            settingsStore.getParagraphPauseMs()
        } else {
            settingsStore.getSentencePauseMs()
        }
        if (pauseMs > 0) delay(pauseMs.toLong())
    }

    private fun resumePlaybackLoop() {
        playJob?.cancel()
        playJob = scope.launch {
            _state.update {
                it.copy(
                    isPlaying = true,
                    chapterTitle = chapterTitle,
                    chapterHref = chapterHref,
                    sentenceIndex = sentenceIndex,
                    sentenceCount = sentences.size,
                )
            }
            while (sentenceIndex < sentences.size) {
                _state.update {
                    it.copy(
                        sentenceIndex = sentenceIndex,
                        chapterHref = chapterHref,
                        currentSentenceText = sentences[sentenceIndex],
                        currentSentenceCharOffset = sentenceSpans.getOrNull(sentenceIndex)?.startOffset ?: -1,
                        currentSentenceCharLength = sentenceSpans.getOrNull(sentenceIndex)
                            ?.let { span -> span.endOffset - span.startOffset } ?: 0,
                        chapterTotalTextLength = chapterTotalTextLength,
                        progressFraction = if (sentences.isNotEmpty()) {
                            sentenceIndex / sentences.size.toFloat()
                        } else {
                            0f
                        },
                        currentSentenceContextBefore = contextBefore(sentenceIndex),
                        currentSentenceContextAfter = contextAfter(sentenceIndex),
                    )
                }
                // BUG FIX: gan them chapterSessionId (khong chi index) -- xem kdoc cua field nay
                // ve ly do (tranh prefetchCache cua engine tra nham audio cua chuong truoc do ghi
                // tre tu 1 tac vu prefetch() chua kip huy).
                val id = "tts-$chapterSessionId-$sentenceIndex"
                // MOI: prefetch CA MOT CUA SO N cau ke tiep (thay vi dung 1 cau nhu truoc) --
                // xem TtsSettingsStore.getPrefetchAheadCount(). Van la child coroutine cua
                // chinh playJob nay (khong phai scope.launch rieng) de pause()/stop()/
                // skipTo...() huy dung luc, giu nguyen tinh chat "khong bao gio de rot lai 1
                // prefetch mo coi" da co.
                //
                // Ghi chu: pass nay CO CHU DICH khong prefetch cau dau tien cua chuong ke tiep
                // (du prompt nang cap co de xuat) -- onRequestNextChapter() (truyen tu
                // EpubReaderActivity#loadNextChapterForTts) co side-effect thuc su la
                // navigatorFragment?.go(nextLink, animated = false), tuc la se nhay giao dien
                // sang trang/chuong ke tiep NGAY LUC GOI, khong phai chi lay noi dung van ban.
                // Goi ham nay som hon (truoc khi chuong hien tai THAT SU doc xong) se lam UI
                // nhay chuong trong khi audio con dang doc nhung cau cuoi cua chuong cu -- mot
                // loi UI ro rang. Tach rieng "lay truoc noi dung chuong ke tiep" (khong side-
                // effect) khoi "chuyen huong dieu huong" (co side-effect) can sua ca
                // EpubReaderActivity va nam ngoai pham vi an toan cua pass nay; xem tong ket
                // cuoi cung.
                val userMaxPrefetch = settingsStore.getPrefetchAheadCount().coerceIn(1, 4)
                val prefetchAhead = adaptivePrefetchAheadCount(userMaxPrefetch, _state.value.speechRate)
                lastSentenceLength = sentences.getOrNull(sentenceIndex)?.length ?: lastSentenceLength
                for (offset in 1..prefetchAhead) {
                    val idx = sentenceIndex + offset
                    if (idx >= sentences.size) break
                    val sentenceText = speechSentenceAt(idx)
                    val startedAtMs = System.currentTimeMillis()
                    launch {
                        engine.prefetch(sentenceText, "tts-$chapterSessionId-$idx")
                        // Chi ghi nhan do tre neu prefetch that su chay tong hop (khong tinh cache-hit --
                        // engine.prefetch() tra ve gan nhu ngay lap tuc khi doc tu TtsAudioCache, ghi nhan
                        // gia tri do vao rolling average se lam adaptivePrefetchAheadCount() danh gia sai
                        // thap do tre that su khi cache-miss). Nguong 50ms phan biet "cache hit" (thuong <
                        // 20ms doc dia) voi "tong hop that" (Edge TTS/NghiTTS deu >> 50ms trong thuc te).
                        val elapsedMs = System.currentTimeMillis() - startedAtMs
                        if (elapsedMs > 50) synthesisLatencyTracker.record(elapsedMs)
                    }
                }
                // Khoang nghi (Sentence/Paragraph Pause) truoc khi phat cau nay -- chay SAU khi
                // da kick off prefetch o tren (khong lam cham viec tong hop truoc cau ke tiep),
                // dung thoi gian THUC khong nhan theo rate (xem pauseBeforeSentence kdoc).
                pauseBeforeSentence(sentenceIndex)
                val speechText = speechSentenceAt(sentenceIndex)
                if (!hasReadableContent(speechText)) {
                    sentenceIndex++
                    continue
                }
                val ok = engine.speak(speechText, utteranceId = id)
                if (!ok) {
                    // Engines can return false for a sentence that normalizes to punctuation only
                    // (e.g. "...", a dangling closing quote after `."`, or an EPUB ornament).
                    // Treat that as an unspeakable sentence and keep advancing; pause/stop/seek
                    // cancel this playJob, so they don't rely on this false path to halt playback.
                    sentenceIndex++
                    continue
                }
                sentenceIndex++
            }
            if (sentenceIndex >= sentences.size) {
                val next = if (settingsStore.getAutoAdvanceChapter()) onRequestNextChapter() else null
                if (next != null) {
                    startChapter(next)
                } else {
                    _state.update { it.copy(isPlaying = false) }
                }
            }
        }
    }

    // MOI: cat ngu canh truoc/sau cau [index] tu chapterRawText (van ban GOC), dung lam
    // Locator.Text.before/after cho TtsSentenceHighlighter (xem HIGHLIGHT_CONTEXT_CHARS).
    private fun contextBefore(index: Int): String {
        val start = sentenceSpans.getOrNull(index)?.startOffset ?: return ""
        val from = (start - HIGHLIGHT_CONTEXT_CHARS).coerceAtLeast(0)
        return chapterRawText.substring(from, start)
    }

    private fun contextAfter(index: Int): String {
        val end = sentenceSpans.getOrNull(index)?.endOffset ?: return ""
        val to = (end + HIGHLIGHT_CONTEXT_CHARS).coerceAtMost(chapterRawText.length)
        return chapterRawText.substring(end, to)
    }

    fun release() {
        playJob?.cancel()
        sleepTimerJob?.cancel()
        TtsEngineRegistry.engines.forEach { it.shutdown() }
    }
}
