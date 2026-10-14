package net.mrphan.calibre.reader.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.mrphan.calibre.Logger
import net.mrphan.calibre.reader.tts.cache.TtsAudioCache
import net.mrphan.calibre.reader.tts.nghi.NghiImportedModel
import net.mrphan.calibre.reader.tts.nghi.NghiModelImporter
import net.mrphan.calibre.reader.tts.nghi.NghiModelStore
import kotlin.coroutines.resume

/**
 * TtsEngine backed by real NghiTTS (Piper/VITS ONNX, github.com/nghimestudio/nghitts) voices
 * that the user has copied into a folder they picked in Settings > "TTS Models". Runs fully
 * offline via sherpa-onnx's OfflineTts (see NghiModelImporter for how a raw nghitts
 * `.onnx`+`.onnx.json` pair becomes a sherpa-onnx-loadable voice).
 *
 * One imported model == one [TtsVoice] in this pass (multi-speaker models always speak with
 * their first/default speaker id; per-speaker sub-voices are a reasonable follow-up, see
 * [NghiImportedModel.speakerIdMap]).
 *
 * NOTE on the sherpa-onnx API surface: the exact constructor/field names of
 * `com.k2fsa.sherpa.onnx.OfflineTts*` below match the Kotlin API published at
 * k2-fsa.github.io/sherpa/onnx/kotlin-api/index.html at the time this was written, but that
 * surface has drifted across sherpa-onnx releases before. This sandbox has no Android SDK/the
 * actual `.aar` to compile against -- after dropping `app/libs/sherpa-onnx.aar` in place, run
 * `./gradlew :app:compileDebugKotlin` and fix any signature drift here first (same caveat
 * already documented for Readium kotlin-toolkit in app/build.gradle.kts).
 */
class NghiTtsEngine : TtsEngine {
    override val id = ENGINE_ID
    override val displayName = "NghiTTS"

    // Dynamic: true once at least one voice model has been imported -- mirrors how
    // TtsVoicePickerDialog / TtsEngineRegistry.default() already expect isAvailable to reflect
    // "does this engine currently have something to offer", same spirit as SystemTtsEngine
    // reporting `true` unconditionally because android.speech.tts.TextToSpeech always exists.
    override val isAvailable: Boolean
        get() = importedModels.isNotEmpty()

    private var importedModels: List<NghiImportedModel> = emptyList()
    private var currentModel: NghiImportedModel? = null
    private var currentSpeakerId: Int = 0
    private var speechRateScale = 1.0f // sherpa-onnx's `speed` multiplier: 1.0 = normal
    private var offlineTts: OfflineTts? = null
    private var appContext: Context? = null

    // MOI: mot AudioTrack DUY NHAT o MODE_STREAM, thay vi build lai MODE_STATIC moi cau --
    // giam han chi phi alloc/JNI/audio-driver moi cau, quan trong vi day la dong bo CPU-bound
    // (VITS). Chi build lai khi doi sampleRate (doi giong), xem getOrCreateAudioTrack().
    private var audioTrack: AudioTrack? = null
    private var audioTrackSampleRate: Int = -1

    // MOI: cache dia ben vung cho PCM da tong hop -- xem TtsAudioCache.
    private var audioCache: TtsAudioCache? = null
    private var cacheEnabled = true

    /** utteranceId -> already-generated PCM audio, filled in by [prefetch] while a previous
     * sentence is still playing back through [audioTrack] -- see [EdgeTtsEngine]'s equivalent
     * cache for the same rationale. sherpa-onnx's `OfflineTts.generate` is not called
     * concurrently with itself: by the time [prefetch] runs for sentence N+1, sentence N's own
     * `generate` call has already finished (only its playback, which doesn't touch
     * [offlineTts], is still in progress). */
    private val prefetchCache = java.util.concurrent.ConcurrentHashMap<String, GeneratedAudio>()

    override suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
        appContext = context.applicationContext
        audioCache = TtsAudioCache.getInstance(context.applicationContext)
        cacheEnabled = TtsSettingsStore(context.applicationContext).getCacheSynthesizedAudioEnabled()
        // Already loaded (e.g. the voice picker calling initialize() on every open) -- avoid
        // re-scanning the model folder and reloading sherpa-onnx for nothing. Settings' explicit
        // "Scan Now" / choosing a new model dir is the real refresh path.
        if (offlineTts != null && importedModels.isNotEmpty()) return@withContext true
        val store = NghiModelStore(context.applicationContext)

        importedModels = NghiModelImporter.loadImportedModels(context.applicationContext)
        if (importedModels.isEmpty()) return@withContext false

        val defaultId = store.getDefaultModelId()
        currentModel = importedModels.firstOrNull { it.id == defaultId } ?: importedModels.first()
        loadCurrentModelIntoSherpa()
        offlineTts != null
    }

    override suspend fun listVoices(): List<TtsVoice> =
        importedModels.map { model ->
            TtsVoice(
                engineId = id,
                id = model.id,
                displayName = model.displayName,
                localeTag = "vi-VN",
            )
        }

    override fun setVoice(voice: TtsVoice) {
        val model = importedModels.firstOrNull { it.id == voice.id } ?: return
        if (model.id == currentModel?.id) return
        currentModel = model
        currentSpeakerId = 0
        prefetchCache.clear()
        loadCurrentModelIntoSherpa()
        // MOI: giong moi co the co sampleRate khac -- huy audioTrack cu, se duoc tao lai
        // (dung sampleRate moi) o lan playPcmFloat() ke tiep, xem getOrCreateAudioTrack().
        releaseAudioTrack()
    }

    override fun setSpeechRate(rate: Float) {
        // sherpa-onnx's OfflineTts.generate(text, sid, speed) takes speed directly per call
        // (no need to reconstruct the engine), unlike VITS's constructor-time lengthScale.
        speechRateScale = rate.coerceIn(0.5f, 2.0f)
        prefetchCache.clear() // any cached audio was generated at the old rate
    }

    /** Cache key cho NghiTTS: engineId + modelId + speakerId + rate lam tron + van ban. */
    private fun cacheKeyFor(text: String): String {
        val model = currentModel ?: return ""
        val roundedRate = (speechRateScale * 10).toInt()
        return "$id|${model.id}|$currentSpeakerId|$roundedRate|$text"
    }

    override suspend fun prefetch(text: String, utteranceId: String) {
        val tts = offlineTts ?: return
        if (text.isBlank() || prefetchCache.containsKey(utteranceId)) return
        withContext(Dispatchers.IO) {
            try {
                val cache = audioCache
                val fromDisk = readFromDiskCache(cache, text)
                if (fromDisk != null) {
                    prefetchCache[utteranceId] = fromDisk
                    return@withContext
                }
                val generated = tts.generate(text, currentSpeakerId, speechRateScale)
                prefetchCache[utteranceId] = generated
                if (cacheEnabled && cache != null) {
                    cache.putPcm(cacheKeyFor(text), generated.samples, generated.sampleRate)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e)
            }
        }
    }

    override suspend fun speak(text: String, utteranceId: String): Boolean {
        val tts = offlineTts ?: return false
        if (text.isBlank()) return false
        val cache = audioCache

        return withContext(Dispatchers.IO) {
            try {
                val generated = prefetchCache.remove(utteranceId)
                    ?: readFromDiskCache(cache, text)
                    ?: tts.generate(text, currentSpeakerId, speechRateScale).also { fresh ->
                        if (cacheEnabled && cache != null) {
                            cache.putPcm(cacheKeyFor(text), fresh.samples, fresh.sampleRate)
                        }
                    }
                playPcmFloat(generated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e)
                false
            }
        }
    }

    /** Doc PCM tu cache dia (neu bat) va boc lai thanh [GeneratedAudio]-tuong-thich (dung field
     * name that cua sherpa-onnx: `samples: FloatArray`, `sampleRate: Int`). */
    private suspend fun readFromDiskCache(cache: TtsAudioCache?, text: String): GeneratedAudio? {
        if (!cacheEnabled || cache == null) return null
        val hit = cache.getPcm(cacheKeyFor(text)) ?: return null
        return GeneratedAudio(samples = hit.samples, sampleRate = hit.sampleRate)
    }

    /** Tao (hoac tai su dung) 1 [AudioTrack] o MODE_STREAM, thay vi build lai MODE_STATIC moi
     * cau nhu truoc day -- giam han chi phi alloc/JNI/audio-driver moi cau, quan trong vi day la
     * dong bo CPU-bound (VITS). Chi build lai khi doi sampleRate (doi giong). */
    private fun getOrCreateAudioTrack(sampleRate: Int): AudioTrack? {
        audioTrack?.let { existing ->
            if (audioTrackSampleRate == sampleRate) return existing
            try { existing.stop(); existing.release() } catch (_: Exception) {}
            audioTrack = null
        }
        return try {
            val minBufferBytes = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
            )
            // x4 min buffer de co du dem cho ca nhung cau dai, tranh underrun gay ngat tieng --
            // hop ly cho STREAM mode (khac MODE_STATIC truoc day duoc size dung theo tung cau).
            val bufferBytes = (minBufferBytes * 4).coerceAtLeast(minBufferBytes)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack = track
            audioTrackSampleRate = sampleRate
            track
        } catch (e: Exception) {
            Logger.e(e)
            null
        }
    }

    /** Plays a sherpa-onnx [GeneratedAudio] (mono float PCM in [-1, 1]) via the reusable
     * `AudioTrack` at MODE_STREAM: write() roi play(), doi ghi xong bang WRITE_BLOCKING (tra ve
     * khi da nap het buffer vao track, khong phai khi PHAT XONG) -- vi vay can markerPosition +
     * listener nhu truoc de biet khi nao THAT SU phat xong, dung getPlaybackHeadPosition tinh
     * tich luy vi MODE_STREAM khong reset position ve 0 giua cac lan write() nhu MODE_STATIC. */
    private suspend fun playPcmFloat(generated: GeneratedAudio): Boolean {
        val samples = generated.samples
        val sampleRate = generated.sampleRate
        if (samples.isEmpty()) return false
        val track = getOrCreateAudioTrack(sampleRate) ?: return false

        return suspendCancellableCoroutine { cont ->
            try {
                // MODE_STREAM: markerPosition la TUYET DOI theo tong so frame da tung ghi, nen
                // phai cong don voi vi tri hien tai truoc khi ghi batch moi -- neu khong marker
                // cu (da vuot qua tu cau truoc) se khong bao gio khop lai, treo mai mai.
                val startFrames = track.playbackHeadPosition
                val targetFrames = startFrames + samples.size
                track.setNotificationMarkerPosition(targetFrames)
                track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(t: AudioTrack) {
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onPeriodicNotification(t: AudioTrack) {}
                })
                cont.invokeOnCancellation {
                    try { track.pause(); track.flush() } catch (_: Exception) {}
                }
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            } catch (e: Exception) {
                Logger.e(e)
                if (cont.isActive) cont.resume(false)
            }
        }
    }

    private fun loadCurrentModelIntoSherpa() {
        val model = currentModel ?: return
        val context = appContext ?: return
        try {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = model.onnxPath,
                        tokens = model.tokensPath,
                        dataDir = NghiModelImporter.espeakDataDir(context).absolutePath,
                        lengthScale = 1.0f, // runtime rate is passed per-call instead
                    ),
                    numThreads = TtsSettingsStore(context)
                        .getSherpaThreadCount()
                        ?: net.mrphan.calibre.reader.tts.nghi.SherpaThreadRecommender.recommendedThreadCount(),
                    debug = false,
                    provider = "cpu",
                ),
            )
            offlineTts?.release()
            // assetManager stays null because our model/tokens/espeak-ng-data all live under
            // filesDir, not app assets -- OfflineTts only needs an AssetManager when its paths
            // point INTO the APK's own assets/.
            offlineTts = OfflineTts(assetManager = null, config = config)
        } catch (e: Exception) {
            Logger.e(e)
            offlineTts = null
        }
    }

    private fun releaseAudioTrack() {
        try { audioTrack?.stop(); audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
        audioTrackSampleRate = -1
    }

    override fun stop() {
        prefetchCache.clear()
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (_: Exception) {
        }
    }

    override fun shutdown() {
        prefetchCache.clear()
        releaseAudioTrack()
        offlineTts?.release()
        offlineTts = null
    }

    // AudioTrack ho tro setVolume() truc tiep -- dung cho fade-out cua Sleep Timer.
    override fun setPlaybackVolume(volume: Float) {
        try { audioTrack?.setVolume(volume.coerceIn(0f, 1f)) } catch (_: Exception) {}
    }

    companion object {
        const val ENGINE_ID = "nghi_tts"
    }
}
