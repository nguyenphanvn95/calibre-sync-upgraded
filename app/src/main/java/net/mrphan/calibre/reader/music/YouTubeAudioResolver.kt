package net.mrphan.calibre.reader.music

import android.net.Uri
import net.mrphan.calibre.Logger
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.Locale

object YouTubeAudioResolver {
    data class ResolvedAudio(
        val audioUrl: String,
        val title: String,
        val artist: String,
        val thumbUrl: String?,
    )

    @Volatile
    private var initialized = false

    fun isYouTubeUrl(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase(Locale.US)?.removePrefix("www.") ?: return false
        return host == "youtube.com" ||
            host == "m.youtube.com" ||
            host == "music.youtube.com" ||
            host == "youtu.be" ||
            host == "youtube-nocookie.com" ||
            host.endsWith(".youtube.com")
    }

    fun resolve(url: String): ResolvedAudio? {
        if (!isYouTubeUrl(url)) return null
        ensureInitialized()
        return runCatching {
            val info = StreamInfo.getInfo(ServiceList.YouTube, url)
            val audio = pickBestAudio(info.getAudioStreams()) ?: return null
            ResolvedAudio(
                audioUrl = audio.url ?: return null,
                title = info.name.orEmpty().ifBlank { "YouTube" },
                artist = info.uploaderName.orEmpty(),
                thumbUrl = info.thumbnails.maxByOrNull { it.width * it.height }?.url,
            )
        }.onFailure { Logger.e(it) }.getOrNull()
    }

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            NewPipe.init(OkHttpNewPipeDownloader())
            initialized = true
        }
    }

    private fun pickBestAudio(streams: List<AudioStream>): AudioStream? {
        val playableStreams = streams.filter { it.isUrl && !it.url.isNullOrBlank() }
        if (playableStreams.isEmpty()) return null
        val preferredFormats = setOf(MediaFormat.M4A, MediaFormat.MPEG_4, MediaFormat.MP3)
        return playableStreams
            .filter { it.format?.let(preferredFormats::contains) == true }
            .maxByOrNull { it.averageBitrate }
            ?: playableStreams.maxByOrNull { it.averageBitrate }
    }

    private class OkHttpNewPipeDownloader : Downloader() {
        private val client = OkHttpClient()

        override fun execute(request: Request): Response {
            val method = request.httpMethod().uppercase(Locale.US)
            val body = request.dataToSend()?.toRequestBody()
            val okRequest = okhttp3.Request.Builder()
                .url(request.url())
                .apply {
                    request.headers().forEach { (name, values) ->
                        values.forEach { value -> addHeader(name, value) }
                    }
                    when (method) {
                        "GET" -> get()
                        "HEAD" -> head()
                        else -> method(method, body ?: ByteArray(0).toRequestBody())
                    }
                }
                .build()

            client.newCall(okRequest).execute().use { response ->
                return Response(
                    response.code,
                    response.message,
                    response.headers.toMultimap(),
                    response.body?.string().orEmpty(),
                    response.request.url.toString(),
                )
            }
        }
    }
}
