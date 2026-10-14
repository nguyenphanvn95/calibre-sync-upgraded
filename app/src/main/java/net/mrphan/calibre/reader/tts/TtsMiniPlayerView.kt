package net.mrphan.calibre.reader.tts

import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import net.mrphan.calibre.R

/**
 * Runtime binding for `view_tts_mini_player.xml` -- follows the same pattern as
 * [net.mrphan.calibre.reader.chrome.ReaderChromeOverlay] (a plain class that receives an
 * already-included View from the Activity, not a custom View subclass), to stay consistent with
 * the existing chrome code.
 */
class TtsMiniPlayerView(
    includedRoot: View,
    private val listener: Listener,
) {
    interface Listener {
        fun onTogglePlayPause()
        fun onClose()
        /** Tapping the mini player bar itself (not one of the child buttons) -- opens
         * [TtsPlayerBottomSheet]. */
        fun onExpand()
    }

    private val root: View = includedRoot.findViewById(R.id.tts_mini_player_root)
    private val icon: ImageView = includedRoot.findViewById(R.id.tts_mini_player_icon)
    private val titleText: TextView = includedRoot.findViewById(R.id.tts_mini_player_title)
    private val progressText: TextView = includedRoot.findViewById(R.id.tts_mini_player_progress)
    private val playPauseButton: ImageButton = includedRoot.findViewById(R.id.tts_mini_player_play_pause)
    private val prevButton: ImageButton = includedRoot.findViewById(R.id.tts_mini_player_prev)
    private val nextButton: ImageButton = includedRoot.findViewById(R.id.tts_mini_player_next)
    private val closeButton: ImageButton = includedRoot.findViewById(R.id.tts_mini_player_close)

    /** MOI: anh bia sach -- chi ton tai trong view_tts_floating_mini_player.xml (panel "noi"
     * hien thi ngoai man hinh doc, xem [net.mrphan.calibre.reader.tts.GlobalTtsFloatingPanel]),
     * KHONG co trong view_tts_mini_player.xml (thanh gan "chet" trong activity_reader_epub.xml)
     * -- findViewById tra ve null an toan o layout do, [bindCover] tu bien thanh no-op. */
    private val coverView: ImageView? = includedRoot.findViewById(R.id.tts_mini_player_cover)

    private var controllerRef: TtsController? = null

    /** True while the mini player bar is currently visible (i.e. a read-aloud session is active
     * and its mini player hasn't been collapsed/closed) -- used by
     * [net.mrphan.calibre.EpubReaderActivity.isReadAloudActive] to tint the footer's "Đọc to"
     * icon, see [net.mrphan.calibre.reader.chrome.ReaderChromeOverlay.isFooterActionActive]. */
    val isVisible: Boolean
        get() = root.visibility == View.VISIBLE

    init {
        playPauseButton.setOnClickListener { listener.onTogglePlayPause() }
        closeButton.setOnClickListener { listener.onClose() }
        prevButton.setOnClickListener { controllerRef?.skipToPreviousSentence() }
        nextButton.setOnClickListener { controllerRef?.skipToNextSentence() }
        root.setOnClickListener { listener.onExpand() }
        coverView?.let { cover ->
            cover.clipToOutline = true
        }
    }

    fun attach(controller: TtsController) {
        controllerRef = controller
    }

    fun bind(state: TtsPlaybackState) {
        titleText.text = state.chapterTitle
        progressText.text = if (state.sentenceCount > 0) {
            root.context.getString(
                R.string.reader_tts_sentence_progress,
                state.sentenceIndex + 1,
                state.sentenceCount,
            )
        } else {
            ""
        }
        playPauseButton.setImageResource(
            if (state.isPlaying) R.drawable.ic_tts_mini_pause else R.drawable.ic_tts_mini_play
        )
    }

    /** Sets the "now playing" cover art on the floating panel's cover thumbnail, if this instance
     * has one (see [coverView]). Safe to call on the in-reader docked mini player too (no-op
     * there, since it has no cover ImageView) -- callers don't need to know which layout they're
     * bound to. */
    fun bindCover(cover: android.graphics.Bitmap?) {
        coverView?.let {
            if (cover != null) it.setImageBitmap(cover) else it.setImageDrawable(null)
        }
    }

    fun show() {
        root.visibility = View.VISIBLE
    }

    fun hide() {
        root.visibility = View.GONE
    }

    fun toggleVisibility() {
        root.visibility = if (root.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }
}
