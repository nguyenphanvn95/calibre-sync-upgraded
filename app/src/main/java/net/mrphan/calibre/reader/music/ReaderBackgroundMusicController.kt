package net.mrphan.calibre.reader.music

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import kotlin.concurrent.thread

class ReaderBackgroundMusicController(private val context: Context) {
    interface Listener {
        fun onTrackChanged(track: ReaderMusicTrack?, playing: Boolean)
    }

    var listener: Listener? = null
    var currentTrack: ReaderMusicTrack? = null
        private set
    var isPlaying: Boolean = false
        private set

    private var mediaPlayer: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var playGeneration = 0

    fun progressFraction(): Float {
        val player = mediaPlayer ?: return 0f
        return runCatching {
            val duration = player.duration
            if (duration <= 0) 0f else player.currentPosition.toFloat() / duration.toFloat()
        }.getOrDefault(0f).coerceIn(0f, 1f)
    }

    fun play(track: ReaderMusicTrack) {
        val generation = ++playGeneration
        releasePlayer()
        currentTrack = track
        isPlaying = false
        listener?.onTrackChanged(track, false)
        thread(name = "reader-music-prepare") {
            val resolved = YouTubeAudioResolver.resolve(track.url)
            val playbackUrl = resolved?.audioUrl ?: track.url
            val displayTrack = if (resolved == null) {
                track
            } else {
                track.copy(
                    title = track.title.ifBlank { resolved.title },
                    artist = track.artist.ifBlank { resolved.artist },
                    thumbUrl = track.thumbUrl ?: resolved.thumbUrl,
                )
            }
            if (generation != playGeneration) return@thread
            if (resolved != null) {
                mainHandler.post {
                    if (generation == playGeneration) {
                        currentTrack = displayTrack
                        listener?.onTrackChanged(displayTrack, false)
                    }
                }
            }
            preparePlayer(generation, displayTrack, playbackUrl)
        }
    }

    fun togglePauseResume() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            isPlaying = false
        } else {
            player.start()
            isPlaying = true
        }
        listener?.onTrackChanged(currentTrack, isPlaying)
    }

    fun stop() {
        playGeneration++
        currentTrack = null
        isPlaying = false
        releasePlayer()
        listener?.onTrackChanged(null, false)
    }

    fun release() {
        stop()
        listener = null
    }

    private fun releasePlayer() {
        mediaPlayer?.runCatching {
            setOnPreparedListener(null)
            setOnCompletionListener(null)
            setOnErrorListener(null)
            stop()
            release()
        }
        mediaPlayer = null
    }

    private fun preparePlayer(generation: Int, track: ReaderMusicTrack, playbackUrl: String) {
        val player = MediaPlayer()
        mediaPlayer = player
        try {
            when {
                playbackUrl.startsWith("content://", ignoreCase = true) ||
                    playbackUrl.startsWith("file://", ignoreCase = true) -> {
                    player.setDataSource(context, Uri.parse(playbackUrl))
                }
                File(playbackUrl).exists() -> player.setDataSource(playbackUrl)
                else -> player.setDataSource(playbackUrl)
            }
            player.isLooping = true
            player.setOnPreparedListener {
                if (generation != playGeneration) {
                    it.release()
                    return@setOnPreparedListener
                }
                it.start()
                isPlaying = true
                mainHandler.post { listener?.onTrackChanged(track, true) }
            }
            player.setOnCompletionListener {
                isPlaying = false
                mainHandler.post { listener?.onTrackChanged(currentTrack, false) }
            }
            player.setOnErrorListener { _, _, _ ->
                isPlaying = false
                mainHandler.post { listener?.onTrackChanged(currentTrack, false) }
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            isPlaying = false
            releasePlayer()
            mainHandler.post { listener?.onTrackChanged(currentTrack, false) }
        }
    }
}
