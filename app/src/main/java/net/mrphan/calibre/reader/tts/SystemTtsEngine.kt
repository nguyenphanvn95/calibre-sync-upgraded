package net.mrphan.calibre.reader.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import net.mrphan.calibre.Logger
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * TtsEngine wrapping `android.speech.tts.TextToSpeech` -- the voices already installed on the
 * device (Google Text-to-Speech, Samsung TTS, etc.), working offline, equivalent to Readest's
 * "Native (system) TTS". This is the one real engine in this pass.
 *
 * CAVEAT: `android.speech.tts.TextToSpeech` has no real pause/resume API in the middle of an
 * utterance already being spoken -- [speak] only returns after the WHOLE sentence has finished
 * (or been stopped). [TtsController] therefore only pauses/resumes at SENTENCE BOUNDARIES, never
 * mid-sentence -- a deliberate simplification, not a bug to fix later.
 *
 * GAPLESS PLAYBACK: uses `TextToSpeech.QUEUE_ADD` (never `QUEUE_FLUSH`) plus a persistent
 * `UtteranceProgressListener` keyed by utteranceId, instead of registering a fresh listener and
 * flushing on every [speak] call. This lets [prefetch] enqueue the NEXT sentence while the
 * CURRENT one is still speaking -- Android's TextToSpeech synthesizes queued utterances in the
 * background and plays them back-to-back with no gap, which is exactly the "câu sau đã có sẵn âm
 * thanh trước khi câu trước phát xong" behavior wanted. [speak] itself only enqueues if
 * [prefetch] hasn't already done so for that same utteranceId (tracked via [enqueuedIds]), so a
 * sentence is never spoken twice.
 */
class SystemTtsEngine : TtsEngine {

    override val id = ENGINE_ID
    override val displayName = "System TTS"
    override val isAvailable = true

    private var tts: TextToSpeech? = null
    private var ready = false

    /** One entry per utteranceId currently awaited via [speak] -- resolved from the single
     * persistent listener set up in [initialize], never from a per-call listener (that would
     * require QUEUE_FLUSH and defeat prefetching). */
    private val pending = ConcurrentHashMap<String, CancellableContinuation<Boolean>>()

    /** utteranceIds already sent to `TextToSpeech.speak(..., QUEUE_ADD, ...)`, whether by
     * [prefetch] or by [speak] itself -- prevents [speak] from enqueuing (and thus speaking) the
     * same sentence a second time when it was already prefetched. */
    private val enqueuedIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    override suspend fun initialize(context: Context): Boolean = suspendCancellableCoroutine { cont ->
        var resumed = false
        val instance = TextToSpeech(context.applicationContext) { status ->
            if (resumed) return@TextToSpeech
            resumed = true
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.setLanguage(Locale.getDefault())
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        resolve(utteranceId, true)
                    }

                    @Deprecated("Deprecated since API 21, kept only because the base class requires it")
                    override fun onError(utteranceId: String?) {
                        resolve(utteranceId, false)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        resolve(utteranceId, false)
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        resolve(utteranceId, false)
                    }
                })
            }
            if (cont.isActive) cont.resume(ready)
        }
        tts = instance
        cont.invokeOnCancellation {
            instance.shutdown()
        }
    }

    private fun resolve(utteranceId: String?, result: Boolean) {
        val id = utteranceId ?: return
        enqueuedIds.remove(id)
        pending.remove(id)?.let { if (it.isActive) it.resume(result) }
    }

    override suspend fun listVoices(): List<TtsVoice> {
        val engine = tts ?: return emptyList()
        if (!ready) return emptyList()
        return try {
            engine.voices.orEmpty()
                .filterNotNull()
                .filter { !it.isNetworkConnectionRequired }
                // Chỉ hiện các giọng tiếng Việt (vi-VN) -- danh sách System TTS đầy đủ có hàng
                // trăm giọng cho mọi ngôn ngữ, không cần thiết cho app này.
                .filter { it.locale.language.equals("vi", ignoreCase = true) }
                .sortedBy { it.name }
                .map { voice ->
                    TtsVoice(
                        engineId = id,
                        id = voice.name,
                        displayName = voice.name,
                        localeTag = voice.locale.toLanguageTag(),
                    )
                }
        } catch (e: Exception) {
            Logger.e(e)
            emptyList()
        }
    }

    override fun setVoice(voice: TtsVoice) {
        val engine = tts ?: return
        val target = engine.voices?.firstOrNull { it.name == voice.id } ?: return
        try {
            engine.voice = target
        } catch (e: Exception) {
            Logger.e(e)
        }
    }

    override fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate.coerceIn(MIN_RATE, MAX_RATE))
    }

    override suspend fun prefetch(text: String, utteranceId: String) {
        val engine = tts ?: return
        if (!ready || text.isBlank() || utteranceId in enqueuedIds) return
        enqueuedIds += utteranceId
        val result = engine.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), utteranceId)
        if (result != TextToSpeech.SUCCESS) enqueuedIds.remove(utteranceId)
    }

    override suspend fun speak(text: String, utteranceId: String): Boolean {
        val engine = tts ?: return false
        if (!ready || text.isBlank()) return false
        return try {
            suspendCancellableCoroutine { cont ->
                pending[utteranceId] = cont
                cont.invokeOnCancellation {
                    pending.remove(utteranceId)
                    enqueuedIds.remove(utteranceId)
                    engine.stop()
                }
                // Nếu utteranceId này CHƯA được enqueue bởi prefetch() từ trước (ví dụ: câu đầu
                // tiên của cả phiên đọc, hoặc prefetch() bị bỏ qua vì đã ở cuối chương), enqueue
                // ngay tại đây -- QUEUE_ADD, không phải QUEUE_FLUSH, để không làm mất chỗ của bất
                // kỳ utterance nào Android đang phát/đã xếp hàng.
                if (utteranceId !in enqueuedIds) {
                    enqueuedIds += utteranceId
                    val result = engine.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), utteranceId)
                    if (result != TextToSpeech.SUCCESS) {
                        enqueuedIds.remove(utteranceId)
                        pending.remove(utteranceId)
                        if (cont.isActive) cont.resume(false)
                    }
                }
            }
        } catch (e: CancellationException) {
            // Not a real error -- pause()/stop()/skipTo...() cancel the playback Job while
            // engine.speak() is suspended. invokeOnCancellation above already called
            // engine.stop() to clean up; the exception must keep propagating unmodified so the
            // coroutine machinery correctly treats the Job as cancelled (never swallow this).
            throw e
        } catch (e: Exception) {
            Logger.e(e)
            false
        }
    }

    override fun stop() {
        // engine.stop() flushes TextToSpeech's own internal queue, so anything merely enqueued
        // (via prefetch or speak) but not yet actually playing will never fire its callback --
        // clear our own bookkeeping here too, or a later speak() for that same utteranceId would
        // wrongly think it's "already enqueued" and wait forever for a callback that can't come.
        enqueuedIds.clear()
        val stale = pending.keys.toList()
        stale.forEach { resolve(it, false) }
        tts?.stop()
    }

    override fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
        enqueuedIds.clear()
        pending.clear()
    }

    // android.speech.tts.TextToSpeech co tham so volume trong Bundle(KEY_PARAM_VOLUME) truyen o
    // moi lan speak(), KHONG chinh runtime giua chung duoc -- ghi ro no-op co ly do (dung mac
    // dinh cua TtsEngine), tranh nguoi doc sau nay tuong bi quen implement. Fade-out cua Sleep
    // Timer se khong ap dung cho System TTS (han che cua API android.speech.tts), chi Edge TTS
    // va NghiTTS moi fade duoc -- chap nhan duoc vi day la 2 engine chinh cua app (System TTS la
    // fallback it dung).

    companion object {
        const val ENGINE_ID = "system"
        private const val MIN_RATE = 0.5f
        private const val MAX_RATE = 2.0f
    }
}
