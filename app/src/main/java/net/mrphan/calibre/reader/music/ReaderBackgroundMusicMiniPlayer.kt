package net.mrphan.calibre.reader.music

import android.animation.ObjectAnimator
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.view.animation.LinearInterpolator
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mrphan.calibre.R
import okhttp3.OkHttpClient
import okhttp3.Request

class ReaderBackgroundMusicMiniPlayer(
    root: View,
    private val controller: ReaderBackgroundMusicController,
    private val lifecycleOwner: LifecycleOwner,
    private val onOpenLibrary: () -> Unit,
) : ReaderBackgroundMusicController.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private val container = root.findViewById<View>(R.id.reader_music_player_root)
    private val pill = root.findViewById<View>(R.id.reader_music_player_pill)
    private val bubble = root.findViewById<View>(R.id.reader_music_player_bubble)
    private val thumb = root.findViewById<ImageView>(R.id.reader_music_player_thumb_image)
    private val bubbleImage = root.findViewById<ImageView>(R.id.reader_music_player_bubble_image)
    private val thumbRing = root.findViewById<ReaderMusicProgressRingView>(R.id.reader_music_player_thumb_ring)
    private val bubbleRing = root.findViewById<ReaderMusicProgressRingView>(R.id.reader_music_player_bubble_ring)
    private val title = root.findViewById<TextView>(R.id.reader_music_player_title)
    private val artist = root.findViewById<TextView>(R.id.reader_music_player_artist)
    private val playPause = root.findViewById<android.widget.ImageButton>(R.id.reader_music_player_play_pause)
    private val close = root.findViewById<android.widget.ImageButton>(R.id.reader_music_player_close)

    private val collapseRunnable = Runnable { collapseToBubble() }
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            if (controller.currentTrack != null) handler.postDelayed(this, 500L)
        }
    }
    private val httpClient = OkHttpClient()
    private var thumbAnimator: ObjectAnimator? = null
    private var bubbleAnimator: ObjectAnimator? = null

    init {
        controller.listener = this
        playPause.setOnClickListener { controller.togglePauseResume() }
        close.setOnClickListener { controller.stop() }
        pill.setOnClickListener { expandPill() }
        bubble.setOnClickListener { expandPill() }
        thumb.setOnClickListener { onOpenLibrary() }
        title.setOnClickListener { onOpenLibrary() }
        artist.setOnClickListener { onOpenLibrary() }
        thumb.clipToOutline = true
        bubbleImage.clipToOutline = true
    }

    override fun onTrackChanged(track: ReaderMusicTrack?, playing: Boolean) {
        if (track == null) {
            handler.removeCallbacks(collapseRunnable)
            handler.removeCallbacks(progressRunnable)
            stopSpin()
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        title.text = track.title
        artist.text = track.artist
        playPause.setImageResource(if (playing) R.drawable.ic_reader_music_pause else R.drawable.ic_reader_music_play)
        setDefaultThumb()
        loadThumb(track.thumbUrl)
        updateProgress()
        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)
        setSpinPlaying(playing)
        expandPill()
    }

    fun release() {
        handler.removeCallbacks(collapseRunnable)
        handler.removeCallbacks(progressRunnable)
        stopSpin()
        controller.listener = null
    }

    private fun expandPill() {
        handler.removeCallbacks(collapseRunnable)
        pill.visibility = View.VISIBLE
        bubble.visibility = View.GONE
        handler.postDelayed(collapseRunnable, 5000L)
    }

    private fun collapseToBubble() {
        if (controller.currentTrack == null) return
        pill.visibility = View.GONE
        bubble.visibility = View.VISIBLE
    }

    private fun setDefaultThumb() {
        thumb.setImageResource(R.drawable.ic_reader_background_music_note)
        bubbleImage.setImageResource(R.drawable.ic_reader_background_music_note)
        thumb.setPadding(dp(8), dp(8), dp(8), dp(8))
        bubbleImage.setPadding(dp(8), dp(8), dp(8), dp(8))
        thumb.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
        bubbleImage.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
    }

    private fun loadThumb(url: String?) {
        if (url.isNullOrBlank()) return
        lifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder().url(url).build()
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@withContext null
                        response.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
                    }
                }.getOrNull()
            }
            if (bitmap != null) {
                thumb.imageTintList = null
                bubbleImage.imageTintList = null
                thumb.setPadding(0, 0, 0, 0)
                bubbleImage.setPadding(0, 0, 0, 0)
                thumb.setImageBitmap(bitmap)
                bubbleImage.setImageBitmap(bitmap)
            }
        }
    }

    private fun dp(value: Int): Int = (value * thumb.resources.displayMetrics.density).toInt()

    private fun updateProgress() {
        val progress = controller.progressFraction()
        thumbRing.progress = progress
        bubbleRing.progress = progress
    }

    private fun setSpinPlaying(playing: Boolean) {
        if (playing) {
            ensureSpin(thumb)
            ensureSpin(bubbleImage)
            thumbAnimator?.resumeOrStart()
            bubbleAnimator?.resumeOrStart()
        } else {
            thumbAnimator?.pause()
            bubbleAnimator?.pause()
        }
    }

    private fun ensureSpin(view: ImageView) {
        val current = if (view === thumb) thumbAnimator else bubbleAnimator
        if (current != null) return
        val animator = ObjectAnimator.ofFloat(view, View.ROTATION, view.rotation, view.rotation + 360f).apply {
            duration = 12000L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        if (view === thumb) {
            thumbAnimator = animator
        } else {
            bubbleAnimator = animator
        }
    }

    private fun ObjectAnimator.resumeOrStart() {
        if (isStarted) {
            resume()
        } else {
            start()
        }
    }

    private fun stopSpin() {
        thumbAnimator?.cancel()
        bubbleAnimator?.cancel()
        thumbAnimator = null
        bubbleAnimator = null
        thumb.rotation = 0f
        bubbleImage.rotation = 0f
    }
}
