package net.mrphan.calibre.reader.tts.music

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import net.mrphan.calibre.Logger
import net.mrphan.calibre.reader.music.YouTubeAudioResolver
import kotlin.concurrent.thread

class BackgroundMusicController(
    private val context: Context,
    private val settingsStore: BackgroundMusicSettingsStore,
) {
    private var mediaPlayer: MediaPlayer? = null
    private var currentTrackIndex = 0

    @Volatile
    private var shouldPlayWhenReady = false
    private var playGeneration = 0

    fun onTtsPlayingChanged(ttsIsPlaying: Boolean) {
        shouldPlayWhenReady = ttsIsPlaying
        if (!settingsStore.isEnabled()) {
            stop()
            return
        }
        if (ttsIsPlaying) resumeOrStart() else pause()
    }

    private fun resumeOrStart() {
        val tracks = settingsStore.getTracks()
        if (tracks.isEmpty()) return
        val player = mediaPlayer
        if (player != null) {
            if (!player.isPlaying) player.start()
            return
        }
        playTrack(tracks[currentTrackIndex.coerceIn(0, tracks.size - 1)], tracks)
    }

    private fun playTrack(track: BackgroundMusicTrack, allTracks: List<BackgroundMusicTrack>) {
        val generation = ++playGeneration
        releasePlayer()
        thread(name = "tts-bg-music-prepare") {
            try {
                val resolved = YouTubeAudioResolver.resolve(track.uri.toString())
                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    if (resolved != null) {
                        setDataSource(resolved.audioUrl)
                    } else {
                        setDataSource(context, track.uri)
                    }
                    setVolume(volumeFloat(), volumeFloat())
                    setOnCompletionListener {
                        currentTrackIndex = (currentTrackIndex + 1) % allTracks.size
                        playTrack(allTracks[currentTrackIndex], allTracks)
                    }
                    prepare()
                }
                if (generation != playGeneration || !shouldPlayWhenReady) {
                    player.release()
                    return@thread
                }
                mediaPlayer = player
                player.start()
            } catch (e: Exception) {
                Logger.e(e)
            }
        }
    }

    private fun volumeFloat(): Float = settingsStore.getVolumePercent() / 100f

    fun applyVolumeNow() {
        mediaPlayer?.setVolume(volumeFloat(), volumeFloat())
    }

    fun pause() {
        shouldPlayWhenReady = false
        mediaPlayer?.takeIf { it.isPlaying }?.pause()
    }

    fun stop() {
        playGeneration++
        shouldPlayWhenReady = false
        releasePlayer()
    }

    private fun releasePlayer() {
        mediaPlayer?.let {
            try {
                it.stop()
            } catch (e: Exception) {
                Logger.e(e)
            }
            it.release()
        }
        mediaPlayer = null
    }

    fun release() {
        stop()
    }
}
