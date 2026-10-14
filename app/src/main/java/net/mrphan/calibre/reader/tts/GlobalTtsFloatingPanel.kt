package net.mrphan.calibre.reader.tts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.mrphan.calibre.R

/**
 * Floating "now playing" TTS mini player that can be attached to ANY [FragmentActivity] --
 * Device Library / Calibre Library browsing (MainActivity), Content Server browsing
 * (ContentServerBrowserActivity), book search (BookSearchActivity), the PDF reader
 * (PdfReaderActivity) -- so "Đọc cho nghe" audio visibly keeps playing in the background while
 * the user browses the library or reads a different book, the same way Readest's floating audio
 * bar behaves. See prompt that requested this (nâng cấp panel float audio cho chế độ đọc to).
 *
 * Unlike [TtsMiniPlayerView]'s existing usage in EpubReaderActivity (a bar statically
 * `<include>`d in activity_reader_epub.xml, only ever toggled VISIBLE/GONE), this class:
 *  1) inflates and adds its own panel to the Activity's content view AT RUNTIME (see
 *     [ensurePanelInflated]), so it works on any screen without touching every layout XML;
 *  2) auto-hides itself whenever a [DialogFragment]/BottomSheetDialogFragment is shown on top
 *     (Settings sheet, sort/filter/view-mode pickers that are Fragment-based, GDrive library
 *     bottom sheets, etc. -- see [fragmentCallbacks]) and re-shows once it's dismissed, if
 *     playback is still active. Dialogs built as a raw android.app.Dialog/AlertDialog (not a
 *     Fragment) already draw in their own Window on top of the whole Activity with a dimming
 *     scrim, so they naturally cover this panel too without needing special-casing here.
 *  3) only shows while a read-aloud session is actually active (isPlaying || sentenceCount > 0),
 *     the exact same rule TtsMiniPlayerView/TtsPlaybackService already use elsewhere.
 *
 * Binds/unbinds [TtsPlaybackService] itself, exactly like EpubReaderActivity's own
 * ttsServiceConnection -- Android ref-counts bindService() calls, so this is safe to run
 * alongside a real EpubReaderActivity session bound to the same (single, app-wide) Service.
 *
 * Usage: construct once per Activity (a field is enough), call [attach] from onStart() and
 * [detach] from onStop().
 *
 * @param onVisibilityChanged optional callback invoked every time the panel flips
 * shown <-> hidden, so the owning Activity can react (e.g. hide/show a FAB that would otherwise
 * sit right underneath the panel and overlap it -- see fab_continue_reading in MainActivity).
 * Defaults to a no-op for Activities that don't have anything overlapping the panel. Declared as
 * a `fun interface` (single abstract method returning Unit/void) rather than a plain Kotlin
 * `(Boolean) -> Unit` function type so Java callers (MainActivity.java is Java) can pass a plain
 * lambda -- a Java lambda targeting `(Boolean) -> Unit` directly would need to explicitly
 * `return kotlin.Unit.INSTANCE`, which is easy to get wrong from Java call sites.
 */
class GlobalTtsFloatingPanel @JvmOverloads constructor(
    private val activity: FragmentActivity,
    private val onVisibilityChanged: VisibilityListener = VisibilityListener {},
) {

    /** See [onVisibilityChanged]. */
    fun interface VisibilityListener {
        fun onVisibilityChanged(visible: Boolean)
    }

    private var panelRoot: View? = null
    private var miniPlayerView: TtsMiniPlayerView? = null

    /** Whether the floating panel is currently shown. Lets other screens (e.g.
     * CollectionFragment's multi-select pill in Device Library) reposition themselves above it
     * without needing a reference to [panelRoot] itself. */
    val isPanelVisible: Boolean
        get() = panelRoot?.visibility == View.VISIBLE

    /** The panel's current on-screen height in px, or 0 before it has ever been laid out (see
     * onVisibilityChanged callers -- height may briefly read 0 right after visibility flips to
     * VISIBLE but before the next layout pass). */
    val panelHeightPx: Int
        get() = panelRoot?.height ?: 0

    private var service: TtsPlaybackService? = null
    private var serviceBound = false
    private var stateCollectJob: Job? = null
    private var lastState: TtsPlaybackState? = null

    /** Fragments currently shown as a dialog/bottom sheet on top of this Activity -- non-empty
     * means the floating panel should stay hidden even if a read-aloud session is active, so it
     * doesn't visually clash with e.g. the Settings sheet. A [Set] (not just a counter) so
     * mismatched resume/pause/destroy callbacks for the SAME fragment can't push the count
     * negative or leave it stuck above zero. */
    private val activeDialogFragments = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<Fragment, Boolean>(),
    )

    private val fragmentCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            if (f is DialogFragment) {
                activeDialogFragments.add(f)
                updateVisibility()
            }
        }

        override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
            if (f is DialogFragment) {
                activeDialogFragments.remove(f)
                updateVisibility()
            }
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (f is DialogFragment) {
                activeDialogFragments.remove(f)
                updateVisibility()
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val boundService = (binder as TtsPlaybackService.LocalBinder).service
            service = boundService
            miniPlayerView?.attach(boundService.ttsController)
            miniPlayerView?.bindCover(boundService.nowPlayingCover)
            stateCollectJob?.cancel()
            stateCollectJob = activity.lifecycleScope.launch {
                boundService.ttsController.state.collect { state ->
                    lastState = state
                    miniPlayerView?.bind(state)
                    updateVisibility()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            lastState = null
            // Route through updateVisibility() (instead of setting panelRoot?.visibility
            // directly) so onVisibilityChanged() is notified too -- otherwise a FAB hidden while
            // the panel was showing would never get told to come back once the Service dies.
            updateVisibility()
        }
    }

    /** Call from Activity#onStart(). Safe to call more than once (e.g. defensively) -- guarded
     * by [serviceBound]. */
    fun attach() {
        ensurePanelInflated()
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentCallbacks, true)
        activity.bindService(
            Intent(activity, TtsPlaybackService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        serviceBound = true
    }

    /** Call from Activity#onStop(). Deliberately does NOT stop playback (same rule as
     * EpubReaderActivity#onStop) -- only unbinds so the Service (and any real read-aloud session)
     * keeps running independently of this Activity's lifecycle. */
    fun detach() {
        if (serviceBound) {
            activity.unbindService(connection)
            serviceBound = false
        }
        activity.supportFragmentManager.unregisterFragmentLifecycleCallbacks(fragmentCallbacks)
        stateCollectJob?.cancel()
        stateCollectJob = null
        service = null
    }

    private fun ensurePanelInflated() {
        if (panelRoot != null) return
        val contentFrame = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = LayoutInflater.from(activity)
            .inflate(R.layout.view_tts_floating_mini_player, contentFrame, false)
        contentFrame.addView(root)
        panelRoot = root

        // Nudge the panel above the gesture/navigation bar on edge-to-edge screens (targetSdk 35
        // enforces this by default) -- adds the systemBars bottom inset on top of the XML's own
        // 16dp margin instead of replacing it, so there's still a visible gap either way.
        val baseBottomMarginPx = (root.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.bottomMargin = baseBottomMarginPx + bars.bottom
                v.layoutParams = lp
            }
            insets
        }

        miniPlayerView = TtsMiniPlayerView(
            includedRoot = root,
            listener = object : TtsMiniPlayerView.Listener {
                override fun onTogglePlayPause() {
                    val controller = service?.ttsController ?: return
                    if (controller.state.value.isPlaying) controller.pause() else controller.resume()
                }

                override fun onClose() {
                    service?.stopReadAloud()
                }

                override fun onExpand() {
                    val boundService = service ?: return
                    TtsPlayerBottomSheet.show(activity, boundService)
                }
            },
        )
    }

    private fun updateVisibility() {
        val root = panelRoot ?: return
        val state = lastState
        val sessionActive = state != null && (state.isPlaying || state.sentenceCount > 0)
        val visible = sessionActive && activeDialogFragments.isEmpty()
        root.visibility = if (visible) View.VISIBLE else View.GONE
        onVisibilityChanged.onVisibilityChanged(visible)
    }
}
