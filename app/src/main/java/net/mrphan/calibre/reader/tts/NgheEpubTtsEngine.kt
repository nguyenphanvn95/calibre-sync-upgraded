package net.mrphan.calibre.reader.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import com.pipodev.ngheepub.tts.PiperNcnn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.mrphan.calibre.Logger
import net.mrphan.calibre.reader.tts.nghe.NgheEpubImportedModel
import net.mrphan.calibre.reader.tts.nghe.NgheEpubModelImporter
import net.mrphan.calibre.reader.tts.nghe.NgheEpubModelStore
import net.mrphan.calibre.reader.tts.nghe.NgheEpubVoiceRef
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

class NgheEpubTtsEngine : TtsEngine {
    override val id = ENGINE_ID
    override val displayName = "Nghe Epub TTS"

    override val isAvailable: Boolean
        get() = supportsNativeAbi() && voiceRefs.isNotEmpty()

    private var appContext: Context? = null
    private var models: List<NgheEpubImportedModel> = emptyList()
    private var voiceRefs: List<NgheEpubVoiceRef> = emptyList()
    private var currentVoice: NgheEpubVoiceRef? = null
    private var piper: PiperNcnn? = null
    private var loadedModelId: String? = null
    private var speechRateScale = 1.0f
    private var audioTrack: AudioTrack? = null
    private val prefetchCache = java.util.concurrent.ConcurrentHashMap<String, ShortArray>()

    override suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
        appContext = context.applicationContext
        if (!supportsNativeAbi()) return@withContext false
        models = NgheEpubModelImporter.loadImportedModels(context.applicationContext)
        voiceRefs = models.flatMap { model -> model.speakers.map { NgheEpubVoiceRef(model, it) } }
        if (voiceRefs.isEmpty()) return@withContext false

        if (piper == null) {
            val g2pDir = ensureMinimalG2PDataExtracted(context.applicationContext)
            piper = try {
                PiperNcnn().also {
                    if (!it.initMinimalG2PData(g2pDir.absolutePath)) {
                        throw IllegalStateException("initMinimalG2PData failed: $g2pDir")
                    }
                }
            } catch (e: Throwable) {
                Logger.e(Exception(e))
                null
            }
        }

        val defaultVoiceId = NgheEpubModelStore(context.applicationContext).getDefaultVoiceId()
        currentVoice = voiceRefs.firstOrNull { it.voiceId == defaultVoiceId }
            ?: currentVoice?.let { current -> voiceRefs.firstOrNull { it.voiceId == current.voiceId } }
            ?: voiceRefs.first()
        loadCurrentModel()
        piper != null && loadedModelId == currentVoice?.model?.id
    }

    override suspend fun listVoices(): List<TtsVoice> =
        voiceRefs.map { ref ->
            TtsVoice(
                engineId = id,
                id = ref.voiceId,
                displayName = ref.displayName,
                localeTag = if (ref.model.lang.startsWith("en")) "en-US" else "vi-VN",
            )
        }

    override fun setVoice(voice: TtsVoice) {
        val ref = voiceRefs.firstOrNull { it.voiceId == voice.id } ?: return
        if (ref.voiceId == currentVoice?.voiceId) return
        currentVoice = ref
        prefetchCache.clear()
        loadCurrentModel()
    }

    override fun setSpeechRate(rate: Float) {
        speechRateScale = rate.coerceIn(0.5f, 2.0f)
        prefetchCache.clear()
    }

    override suspend fun prefetch(text: String, utteranceId: String) {
        val prepared = prepareTextForSynthesis(text)
        if (prepared.isBlank() || prefetchCache.containsKey(utteranceId)) return
        withContext(Dispatchers.IO) {
            try {
                synthesize(prepared)?.let { prefetchCache[utteranceId] = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e)
            }
        }
    }

    override suspend fun speak(text: String, utteranceId: String): Boolean {
        val prepared = prepareTextForSynthesis(text)
        if (prepared.isBlank()) return false
        return withContext(Dispatchers.IO) {
            try {
                val pcm = prefetchCache.remove(utteranceId) ?: synthesize(prepared) ?: return@withContext false
                playPcm16(pcm)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e)
                false
            }
        }
    }

    private fun prepareTextForSynthesis(text: String): String {
        val normalized = text
            .replace('…', '.')
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim { it.isBoundaryPunctuationForNgheTts() }
        return if (normalized.any { it.isLetterOrDigit() }) normalized else ""
    }

    private fun Char.isBoundaryPunctuationForNgheTts(): Boolean =
        isWhitespace() || this in setOf(
            '.', ',', ';', ':', '!', '?',
            '"', '\'', '“', '”', '‘', '’',
            '(', ')', '[', ']', '{', '}',
            '-', '–', '—', '―',
            '…',
        )

    private fun synthesize(text: String): ShortArray? {
        val tts = piper ?: return null
        val voice = currentVoice ?: return null
        loadCurrentModel()
        val normalized = text.lowercase(Locale.ROOT)
        val lengthScale = 1.0 / speechRateScale.toDouble()
        return tts.synthesizeToBuffer(normalized, voice.speaker.id, lengthScale)
            ?.takeIf { it.isNotEmpty() }
    }

    private fun loadCurrentModel() {
        val tts = piper ?: return
        val voice = currentVoice ?: return
        if (loadedModelId == voice.model.id) return
        try {
            if (tts.loadModelFromFile(voice.model.encPath, null, voice.model.lang, 0)) {
                loadedModelId = voice.model.id
                releaseAudioTrack()
            } else {
                loadedModelId = null
            }
        } catch (e: Exception) {
            Logger.e(e)
            loadedModelId = null
        }
    }

    private fun getOrCreateAudioTrack(): AudioTrack? {
        audioTrack?.let { return it }
        return try {
            val minBufferBytes = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes((minBufferBytes * 4).coerceAtLeast(minBufferBytes))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack = track
            track
        } catch (e: Exception) {
            Logger.e(e)
            null
        }
    }

    private suspend fun playPcm16(samples: ShortArray): Boolean {
        val track = getOrCreateAudioTrack() ?: return false
        return suspendCancellableCoroutine { cont ->
            try {
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

    private fun releaseAudioTrack() {
        try { audioTrack?.stop(); audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
    }

    override fun stop() {
        prefetchCache.clear()
        try {
            piper?.stop()
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (_: Exception) {
        }
    }

    override fun shutdown() {
        prefetchCache.clear()
        stop()
        releaseAudioTrack()
        piper = null
        loadedModelId = null
    }

    override fun setPlaybackVolume(volume: Float) {
        try { audioTrack?.setVolume(volume.coerceIn(0f, 1f)) } catch (_: Exception) {}
    }

    private fun ensureMinimalG2PDataExtracted(context: Context): File {
        val dest = File(context.filesDir, "minimal_g2p_data")
        val marker = File(dest, ".extracted")
        if (marker.exists()) return dest
        dest.mkdirs()
        copyAssetDirRecursive(context, "minimal_g2p_data", dest)
        marker.writeText("1")
        return dest
    }

    private fun copyAssetDirRecursive(context: Context, assetPath: String, dest: File) {
        val entries = context.assets.list(assetPath) ?: return
        if (entries.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        dest.mkdirs()
        entries.forEach { child ->
            copyAssetDirRecursive(context, "$assetPath/$child", File(dest, child))
        }
    }

    companion object {
        const val ENGINE_ID = "nghe_epub_tts"
        private const val SAMPLE_RATE = 24000

        fun supportsNativeAbi(): Boolean =
            Build.SUPPORTED_ABIS.any { it.equals("arm64-v8a", ignoreCase = true) }
    }
}
