package net.mrphan.calibre.reader.tts.edge

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.mrphan.calibre.Logger
import net.mrphan.calibre.reader.tts.TtsEngine
import net.mrphan.calibre.reader.tts.TtsSettingsStore
import net.mrphan.calibre.reader.tts.TtsVoice
import net.mrphan.calibre.reader.tts.cache.TtsAudioCache
import java.io.File
import kotlin.coroutines.resume

/**
 * TtsEngine backed by Microsoft Edge's unofficial "Read Aloud" cloud service (see
 * EdgeTtsClient) -- the same engine family Readest calls "Edge TTS". Needs Internet; each
 * sentence is synthesized on-the-fly unless already present in [TtsAudioCache] (persistent
 * disk cache -- see cache package kdoc).
 *
 * Unofficial API caveat: this can break if Microsoft changes the protocol. All failures
 * degrade to `speak()` returning false (TtsController just skips the sentence), never a crash --
 * same contract as every other TtsEngine.
 */
class EdgeTtsEngine : TtsEngine {
    override val id = ENGINE_ID
    override val displayName = "Edge TTS"
    override val isAvailable = true // needs network at speak()-time, not at construction time

    private val client = EdgeTtsClient()
    private var voices: List<TtsVoice> = emptyList()
    private var currentVoice: TtsVoice? = null
    private var speechRate = 1.0f
    private var appContext: Context? = null

    // MOI: mot MediaPlayer DUY NHAT song suot vong doi engine, thay vi tao/huy moi cau (xem
    // readest#1777: recreate player giua moi utterance la nguyen nhan gay khung). reset()
    // truoc moi lan setDataSource() moi -- KHONG release()/tao lai tru khi that su co loi.
    private var reusablePlayer: MediaPlayer? = null

    // THAY THE: 1 scratchFile duy nhat -> 2 file luan phien, tranh ghi de file dang duoc
    // MediaPlayer doc do (vd nguoi dung bam "cau tiep" lien tuc trong luc prepareAsync() cau
    // truoc con dang chay). Danh sach 2 file duoc tao san trong cacheDir, KHONG tao ten file moi
    // moi lan (van giu tinh chat "khong rac cacheDir" nhu thiet ke goc).
    private var scratchFiles: List<File>? = null
    private var scratchFileIndex = 0

    private fun nextScratchFile(context: Context): File {
        val files = scratchFiles ?: listOf(
            File(context.cacheDir, "edge_tts_reusable_0.mp3"),
            File(context.cacheDir, "edge_tts_reusable_1.mp3"),
        ).also { scratchFiles = it }
        val file = files[scratchFileIndex]
        scratchFileIndex = (scratchFileIndex + 1) % files.size
        return file
    }

    // MOI: cache dia ben vung (xem TtsAudioCache) -- doc lai gia tri bat/tat MOI LAN
    // initialize() de phan anh dung Settings hien tai (nguoi dung co the bat/tat Cache
    // Synthesized Audio giua cac phien doc).
    private var audioCache: TtsAudioCache? = null
    private var cacheEnabled = true

    /** utteranceId -> already-synthesized mp3 bytes, filled in by [prefetch] while a previous
     * sentence is still playing. [speak] consumes (removes) an entry here if present instead of
     * synthesizing again -- this is what removes the network round-trip gap between sentences.
     * Cleared on [stop]/[shutdown] so skipping around a lot can't grow it unbounded. */
    private val prefetchCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    override suspend fun initialize(context: Context): Boolean {
        appContext = context.applicationContext
        audioCache = TtsAudioCache.getInstance(context.applicationContext)
        cacheEnabled = TtsSettingsStore(context.applicationContext).getCacheSynthesizedAudioEnabled()
        if (voices.isNotEmpty()) return true // already fetched -- avoid a network call every time
        return withContext(Dispatchers.IO) {
            try {
                val fetched = client.fetchVoices()
                voices = fetched
                    // Chỉ hiện 2 giọng tiếng Việt tự nhiên nhất (HoaiMy, NamMinh) -- danh sách
                    // đầy đủ của Edge TTS có hàng trăm giọng cho mọi ngôn ngữ, không cần thiết.
                    .filter { it.shortName in ALLOWED_VOICES }
                    .sortedBy { it.locale + it.shortName }
                    .map {
                        TtsVoice(
                            engineId = id,
                            id = it.shortName,
                            displayName = "${it.friendlyName} (${it.locale})",
                            localeTag = it.locale,
                        )
                    }
                if (currentVoice == null) {
                    // Default to a Vietnamese voice if one exists (this app's primary
                    // audience), else just the first voice available.
                    currentVoice = voices.firstOrNull { it.id == DEFAULT_VI_VOICE }
                        ?: voices.firstOrNull()
                }
                voices.isNotEmpty()
            } catch (e: Exception) {
                Logger.e(e)
                false
            }
        }
    }

    override suspend fun listVoices(): List<TtsVoice> = voices

    override fun setVoice(voice: TtsVoice) {
        currentVoice = voice
        prefetchCache.clear()
    }

    override fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.5f, 2.0f)
        prefetchCache.clear() // any cached mp3 was synthesized at the old rate
    }

    /** Cache key: engineId + voiceId + rate lam tron 1 chu so thap phan (tranh moi thay doi
     * nho cua rate float lam cache-miss vo ich) + van ban DA qua Tu dien phat am (chinh la
     * `text` truyen vao day tu TtsController.speechSentenceAt()). */
    private fun cacheKeyFor(text: String, voice: TtsVoice): String {
        val roundedRate = (speechRate * 10).toInt() // vd 1.25 -> 12
        return "$id|${voice.id}|$roundedRate|$text"
    }

    override suspend fun prefetch(text: String, utteranceId: String) {
        val voice = currentVoice ?: return
        if (text.isBlank() || prefetchCache.containsKey(utteranceId)) return
        val cache = audioCache
        if (cacheEnabled && cache != null) {
            val hit = cache.get(cacheKeyFor(text, voice))
            if (hit != null) {
                prefetchCache[utteranceId] = hit
                return
            }
        }
        val ratePercent = ((speechRate - 1.0f) * 100).toInt()
        try {
            val bytes = client.synthesize(text, voice.id, voice.localeTag, ratePercent)
            if (bytes != null) {
                prefetchCache[utteranceId] = bytes
                if (cacheEnabled && cache != null) cache.put(cacheKeyFor(text, voice), bytes)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(e)
        }
    }

    override suspend fun speak(text: String, utteranceId: String): Boolean {
        val voice = currentVoice ?: return false
        if (text.isBlank()) return false
        val cache = audioCache

        val mp3Bytes = prefetchCache.remove(utteranceId)
            ?: (if (cacheEnabled && cache != null) cache.get(cacheKeyFor(text, voice)) else null)
            ?: try {
                val ratePercent = ((speechRate - 1.0f) * 100).toInt()
                val synthesized = client.synthesize(text, voice.id, voice.localeTag, ratePercent)
                if (synthesized != null && cacheEnabled && cache != null) {
                    cache.put(cacheKeyFor(text, voice), synthesized)
                }
                synthesized
            } catch (e: Exception) {
                Logger.e(e)
                null
            } ?: return false

        return playMp3Bytes(mp3Bytes)
    }

    /** Phat mp3Bytes qua [reusablePlayer], ghi vao 1 trong 2 file luan phien (xem nextScratchFile)
     * thay vi luon ghi de cung 1 file -- tranh rang buoc ghi/doc chong cheo khi nguoi dung skip
     * nhanh lien tuc. player.reset() truoc moi lan setDataSource() moi, KHONG bao gio release()/tao
     * lai tru khi loi that su xay ra (giu nguyen fix chinh cho readest#1777 da co). */
    private suspend fun playMp3Bytes(bytes: ByteArray): Boolean {
        val context = appContext ?: return false
        val file = nextScratchFile(context)
        file.writeBytes(bytes)
        return suspendCancellableCoroutine { cont ->
            val player = getOrCreatePlayer()
            try {
                player.reset()
                player.setOnCompletionListener {
                    if (cont.isActive) cont.resume(true)
                }
                player.setOnErrorListener { _, _, _ ->
                    if (cont.isActive) cont.resume(false)
                    true // da xu ly, khong de MediaPlayer tu goi them onCompletion
                }
                cont.invokeOnCancellation {
                    try { player.stop() } catch (_: Exception) {}
                }
                player.setDataSource(file.absolutePath)
                player.setOnPreparedListener { it.start() }
                player.prepareAsync()
            } catch (e: Exception) {
                Logger.e(e)
                // setDataSource/prepareAsync that bai co the de player o trang thai xau --
                // huy player nay, lan speak() ke tiep se tao lai tu dau qua getOrCreatePlayer().
                releasePlayerAfterError()
                if (cont.isActive) cont.resume(false)
            }
        }
    }

    private fun getOrCreatePlayer(): MediaPlayer {
        reusablePlayer?.let { return it }
        val player = MediaPlayer()
        reusablePlayer = player
        return player
    }

    private fun releasePlayerAfterError() {
        try { reusablePlayer?.release() } catch (_: Exception) {}
        reusablePlayer = null
    }

    override fun stop() {
        prefetchCache.clear()
        try {
            reusablePlayer?.stop()
        } catch (_: Exception) {
        }
    }

    override fun shutdown() {
        prefetchCache.clear()
        try { reusablePlayer?.release() } catch (_: Exception) {}
        reusablePlayer = null
        scratchFiles?.forEach { try { it.delete() } catch (_: Exception) {} }
        scratchFiles = null
    }

    // MediaPlayer ho tro setVolume(left, right) -- dung cho fade-out cua Sleep Timer.
    override fun setPlaybackVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        try { reusablePlayer?.setVolume(v, v) } catch (_: Exception) {}
    }

    companion object {
        const val ENGINE_ID = "edge_tts"
        private const val DEFAULT_VI_VOICE = "vi-VN-HoaiMyNeural"

        /** Chỉ 2 giọng tiếng Việt được phép hiển thị trong danh sách Edge TTS. */
        private val ALLOWED_VOICES = setOf("vi-VN-HoaiMyNeural", "vi-VN-NamMinhNeural")
    }
}
