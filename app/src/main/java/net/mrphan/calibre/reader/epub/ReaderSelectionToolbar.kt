package net.mrphan.calibre.reader.epub

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupWindow
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.MenuItemCompat
import androidx.core.view.ViewCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.mrphan.calibre.Logger
import net.mrphan.calibre.R
import org.readium.r2.navigator.epub.EpubNavigatorFragment

/**
 * One text selection, snapshotted at the moment a selection-toolbar icon is tapped -- [locatorJson]
 * is already run through [encodeLocator] (same envelope [ReaderAnnotation]/`reader_annotations`
 * uses), [text] is the selection's excerpt for Note/Search/Share, and [rect] is
 * `Selection.rect` (readium-navigator, `org.readium.r2.navigator.Selection.rect: RectF?`) in the
 * coordinate space of [ReaderSelectionActionHost.anchorView] -- i.e. already relative to that
 * view's top-left corner, *not* screen coordinates. It's nullable because Readium documents it as
 * nullable too (not every selection reports pixel bounds, e.g. right after a rotation); when it's
 * null [ReaderSelectionToolbar] falls back to an approximate anchor instead of failing outright.
 */
data class ReaderSelectionSnapshot(
    val locatorJson: String,
    val text: String,
    val rect: RectF?,
)

/**
 * Everything [ReaderSelectionToolbar] needs from the host Activity (`EpubReaderActivity`), kept
 * as a narrow interface instead of passing the Activity itself so this file doesn't need to know
 * about `MetadataDatabaseHelper`/`EpubNavigatorFragment`/etc. directly.
 */
interface ReaderSelectionActionHost {
    /** The view selection [ReaderSelectionSnapshot.rect] is relative to -- in practice the
     * EpubNavigatorFragment's root view -- also used as the anchor for [PopupWindow.showAtLocation]. */
    fun anchorView(): View?

    /** Reads the navigator's current selection right now. Returns null if there is no selection
     * any more (e.g. the user already dismissed it) -- callers must handle that quietly. */
    suspend fun currentSelectionSnapshot(): ReaderSelectionSnapshot?

    /** Persists a highlight (optionally with a note) for [locatorJson] and re-renders decorations.
     * [style] picks fill-background vs colored-underline rendering -- see [ReaderHighlightStyle];
     * defaults to [ReaderHighlightStyle.FILL], the only style that existed before the pill
     * toolbar's style buttons were added, so any pre-existing call site still compiles unchanged. */
    fun addHighlight(
        locatorJson: String,
        color: ReaderHighlightColor,
        note: String?,
        excerpt: String?,
        style: ReaderHighlightStyle = ReaderHighlightStyle.FILL,
    )

    /** Opens the in-book search dialog pre-filled with [query] and runs the search immediately. */
    fun openSearchInBook(query: String)

    /** Shares [text] as plain text via the system share sheet. */
    fun shareSelectedText(text: String)

    /** Opens the dictionary-lookup bottom sheet ([net.mrphan.calibre.reader.lookup.LookupBottomSheetFragment])
     * for [word]. */
    fun showDictionaryLookup(word: String)

    /** Opens the translation bottom sheet ([net.mrphan.calibre.reader.lookup.LookupBottomSheetFragment])
     * for [text]. */
    fun showTranslation(text: String)

    fun showMessage(message: String)

    fun formatString(resId: Int, vararg formatArgs: Any): String

    /** Scope to fetch [currentSelectionSnapshot] on, without this file needing `lifecycleScope`
     * or Activity lifecycle imports of its own. */
    fun selectionScope(): CoroutineScope
}

/**
 * Builds the EPUB reader's text-selection toolbar, now upgraded to look and behave like Readest
 * Android's: a dark, fully-rounded floating pill of icon-only buttons that tracks the selection
 * and appears *instead of* the system `Copy / Select all / Share` bar, rather than the old design
 * where six icons were merely added into that system `ActionMode` bar.
 *
 * ## How Readest itself does this, and why that approach isn't directly portable here
 * Readest is a web app (foliate-js rendering EPUB content inside a WebView it fully controls) --
 * its own project notes (`readest-app/.claude/memory/android-system-selection-menu-one-off.md`)
 * confirm there is "no native ActionMode override"; the *only* mechanism it uses is calling
 * `event.preventDefault()` on the DOM `contextmenu` event from injected JS, which stops the
 * system floating toolbar from ever starting, and it then draws its own popup (`AnnotationPopup`)
 * positioned via `selectionchange`-derived coordinates. It also replaces the native drag handles
 * with fully custom ones (`SelectionRangeEditor`) -- a separate, much larger undertaking full of
 * its own edge cases (see that project's `hyphen-selection-bounds`, `keyboard-selection-adjust`,
 * `cross-page-selection-autoturn` memory files), which this change deliberately does NOT attempt:
 * native Android selection handles are left as-is here, only the floating toolbar changes.
 *
 * ## What's portable, and what this class does
 * `EpubNavigatorFragment` exposes `evaluateJavascript()` and
 * `Configuration.registerJavascriptInterface()` (readium.org/kotlin-toolkit docs, "Extract
 * publication content" / 2.3.0 changelog) as supported public API -- these are the same two
 * primitives Readest's own JS layer uses, just reached through Readium's sanctioned extension
 * points instead of owning the WebView directly. So this class:
 *  1. Injects [SELECTION_WATCH_JS] into the *currently visible* resource (re-injected by the host
 *     every time the visible resource's href changes -- see [onLocatorChanged]) which (a) calls
 *     `preventDefault()` on `contextmenu` while a selection is non-empty, same as Readest, and
 *     (b) reports selection-active/selection-cleared over `window.selectionchange`, debounced
 *     100-120ms, to [jsBridge].
 *  2. On "active", fetches [ReaderSelectionActionHost.currentSelectionSnapshot] (the same Readium
 *     `Selection.rect` API the old color-swatch popup already used) and shows/repositions
 *     [showPillToolbar] -- a `PopupWindow` styled like Readest's dark pill
 *     (`view_reader_selection_pill_toolbar.xml`) -- instead of relying on `ActionMode` at all.
 *  3. On "cleared", dismisses it.
 *
 * `selectionActionModeCallback` is *also* still wired to [buildActionModeCallback] below (the
 * previous icon-in-ActionMode design, unchanged) as a fallback, not because it's expected to run
 * in the normal case -- when JS suppression engages, the system never calls
 * `startActionMode()` in the first place -- but because JS injection timing is inherently a race
 * (first long-press right after a resource loads, before [SELECTION_WATCH_JS] finishes running,
 * or a WebView build where `contextmenu` isn't fired for a long-press selection at all). Readest's
 * own bug tracker shows this exact race happening to *them* even after years in production (see
 * `android-system-selection-menu-one-off.md`: the system bar appeared "over the reader alongside
 * Readest's own toolbar" once, on a real device, unreproducible on demand) -- so the graceful
 * degradation here is deliberately "both bars visible for that one rare case", never "nothing".
 *
 * Dictionary and Translate now open [net.mrphan.calibre.reader.lookup.LookupBottomSheetFragment]
 * via [ReaderSelectionActionHost.showDictionaryLookup]/[ReaderSelectionActionHost.showTranslation]
 * -- real network lookups (dictionaryapi.dev/Wiktionary for Dictionary, an unofficial Google
 * Translate endpoint or optional DeepL for Translate), not placeholders. See that package's
 * `ReaderLookupSettingsStore`/provider classes for the actual network calls and their
 * not-build-verified caveats.
 *
 * **Not build/run-verified** (no Android SDK / no network to maven.google.com in the sandbox this
 * was written in, same limitation the previous round of this file hit) -- three things to check
 * first on a real device/emulator:
 *  - That `evaluateJavascript()` really is a `suspend fun` returning the eval result as it reads
 *    from the docs snippet (`val result = navigator.evaluateJavascript(...)`); if it's actually
 *    callback-based this call site (see [injectWatcherScript]) needs a small adjustment.
 *  - That `Configuration.registerJavascriptInterface(name) { link -> ... }`'s factory lambda is
 *    called once per navigator (safe to return the same [jsBridge] singleton every time, as done
 *    here) rather than needing a fresh instance per resource.
 *  - That `contextmenu.preventDefault()` still suppresses the ActionMode bar on the WebView
 *    builds actually in the field -- Readest's own note above flags this isn't 100% guaranteed on
 *    every WebView version, which is exactly why the ActionMode fallback below stays in place.
 */
class ReaderSelectionToolbar(
    private val context: Context,
    private val host: ReaderSelectionActionHost,
) {

    // --- New: JS-driven suppression + Readest-style pill toolbar -----------------------------

    /** `@JavascriptInterface`-annotated bridge object registered into the navigator's WebView via
     * `Configuration.registerJavascriptInterface(JS_INTERFACE_NAME) { _ -> jsBridge }`. Runs on
     * the WebView's own JS thread, so both callbacks hop back to the main thread before touching
     * any View/PopupWindow. */
    val jsBridge: Any = object {
        @JavascriptInterface
        fun onSelectionActive() {
            Logger.d("SelectionToolbar: JS bridge onSelectionActive() called")
            mainHandler.post { handleSelectionActive() }
        }

        @JavascriptInterface
        fun onSelectionCleared() {
            Logger.d("SelectionToolbar: JS bridge onSelectionCleared() called")
            mainHandler.post { handleSelectionCleared() }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Discards a stale [ReaderSelectionActionHost.currentSelectionSnapshot] result that comes
     * back after a newer selectionchange already superseded it (both are async, and drag-to-extend
     * selection fires this repeatedly). */
    private var selectionRequestToken = 0

    /** Tracks the resource href [SELECTION_WATCH_JS] was last injected into, so
     * [onLocatorChanged] only re-injects on an actual resource change (the script no-ops on
     * repeat injection into the *same* resource anyway via its own `__csyncSelWatcherInstalled`
     * guard, but avoiding the redundant `evaluateJavascript` call on every locator/scroll update
     * within one resource is simple enough to do here too). */
    private var lastInjectedHref: String? = null

    /** Call once the navigator fragment exists (right after it's attached) and again every time
     * [locatorHref] (the visible resource's href, fragment/anchor stripped) changes -- each
     * resource is served by Readium in its own WebView instance, so the watcher script has to be
     * (re-)installed per resource, not once for the whole book. */
    fun onLocatorChanged(navigator: EpubNavigatorFragment, locatorHref: String) {
        if (locatorHref == lastInjectedHref) {
            Logger.d("SelectionToolbar: onLocatorChanged($locatorHref) skipped, same as last")
            return
        }
        lastInjectedHref = locatorHref
        Logger.d("SelectionToolbar: onLocatorChanged($locatorHref), injecting watcher script")
        injectWatcherScript(navigator)
    }

    private fun injectWatcherScript(navigator: EpubNavigatorFragment, forceReset: Boolean = false) {
        host.selectionScope().launch {
            try {
                if (forceReset) {
                    // The watcher script no-ops on repeat injection via its own
                    // `__csyncSelWatcherInstalled` guard (see kdoc on SELECTION_WATCH_JS) -- that's
                    // normally the right behavior (don't double-register listeners on every
                    // locator tick), but it also means a plain re-injection is a NO-OP if the flag
                    // is still (wrongly) `true` while the actual listeners it guards are gone or
                    // broken -- e.g. after heavy DOM churn from repeated TtsSentenceHighlighter
                    // apply()/clear() calls during a read-aloud session (see reinstallWatcher()).
                    // Clearing the flag first forces the full re-install to actually run.
                    navigator.evaluateJavascript("window.__csyncSelWatcherInstalled = false;")
                }
                val result = navigator.evaluateJavascript(SELECTION_WATCH_JS)
                Logger.d("SelectionToolbar: evaluateJavascript() returned '$result' (forceReset=$forceReset)")
            } catch (e: Exception) {
                // Non-fatal: worst case this resource keeps the ActionMode fallback bar instead
                // of the Readest-style pill. See class kdoc's "not build/run-verified" note.
                Logger.d("SelectionToolbar: evaluateJavascript() threw, watcher NOT installed")
                Logger.e(e)
            }
        }
    }

    /**
     * BUG FIX: forces [SELECTION_WATCH_JS] to be fully re-installed into the currently-visible
     * resource, bypassing both the app-side [lastInjectedHref] guard (which only re-injects on an
     * actual chapter/href change) and the script's own JS-side install guard (see
     * [injectWatcherScript]'s `forceReset` param). Call this once a TTS read-aloud session ends --
     * the TtsSentenceHighlighter.apply()/clear() decoration churn that happens many times per
     * chapter during playback (EpubReaderActivity/TtsSessionCoordinator) can leave the page's
     * selection watcher (installed once, at chapter-load time, well before playback started) in a
     * broken state without ever changing the visible href -- which is exactly the scenario
     * reported: the ActionMode fallback bar stays suppressed (because the JS side still *believes*
     * it's watching and keeps calling `contextmenu.preventDefault()`) while the replacement pill
     * toolbar never shows either (because the actual selectionchange callback wiring underneath is
     * gone) -- net result, no selection menu at all right after a read-aloud session, until the
     * next real chapter change happens to re-trigger [onLocatorChanged] naturally.
     */
    fun reinstallWatcher(navigator: EpubNavigatorFragment) {
        Logger.d("SelectionToolbar: reinstallWatcher() forced (e.g. TTS session just ended)")
        lastInjectedHref = null
        injectWatcherScript(navigator, forceReset = true)
    }

    private fun handleSelectionActive() {
        val myToken = ++selectionRequestToken
        host.selectionScope().launch {
            val snapshot = host.currentSelectionSnapshot()
            if (myToken != selectionRequestToken) {
                Logger.d("SelectionToolbar: handleSelectionActive superseded, dropping snapshot")
                return@launch // superseded by a newer selection
            }
            if (snapshot == null) {
                Logger.d("SelectionToolbar: currentSelectionSnapshot() returned null, no pill shown")
                dismissPillToolbar()
                return@launch
            }
            Logger.d("SelectionToolbar: got snapshot, text='${snapshot.text.take(30)}', rect=${snapshot.rect}")
            showPillToolbar(snapshot)
        }
    }

    private fun handleSelectionCleared() {
        selectionRequestToken++ // invalidate any in-flight fetch from handleSelectionActive
        dismissPillToolbar()
        dismissColorSwatchPopup()
    }

    private var pillPopup: PopupWindow? = null

    private fun showPillToolbar(snapshot: ReaderSelectionSnapshot) {
        val anchor = host.anchorView()
        if (anchor == null) {
            Logger.d("SelectionToolbar: anchorView() is null, cannot show pill")
            return
        }
        dismissColorSwatchPopup() // a fresh/changed selection cancels any open color picker

        val reuse = pillPopup
        val popupView = if (reuse != null && reuse.isShowing) {
            reuse.contentView
        } else {
            dismissPillToolbar()
            val inflated = LayoutInflater.from(context)
                .inflate(R.layout.view_reader_selection_pill_toolbar, null, false)
            val popup = PopupWindow(
                inflated,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                false,
            ).apply {
                isOutsideTouchable = true
                isFocusable = false
                elevation = 24f
                setBackgroundDrawable(ColorDrawable(0))
            }
            pillPopup = popup
            inflated
        }
        // Rebind every time, not just on first creation: the selection (and therefore its text /
        // locatorJson) can keep changing while the pill stays visible, e.g. dragging a handle to
        // extend the selection re-triggers handleSelectionActive() with a fresh snapshot but
        // reuses this same PopupWindow instance below.
        bindPillButtons(popupView, snapshot)

        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val (localX, localY) = anchorLocation(anchor, snapshot.rect, popupView.measuredWidth, popupView.measuredHeight)
        val (x, y) = toScreenLocation(anchor, localX, localY)
        val popup = pillPopup ?: return
        Logger.d(
            "SelectionToolbar: showing pill at screen x=$x y=$y (local was $localX,$localY) " +
                "(measured ${popupView.measuredWidth}x${popupView.measuredHeight}, " +
                "anchor attached=${anchor.isAttachedToWindow})"
        )
        if (popup.isShowing) {
            popup.update(x, y, -1, -1)
        } else {
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        }
    }

    /** Which style the two "A" buttons in row 1 are currently set to -- lives on the
     * [ReaderSelectionToolbar] instance (not per-popup-view) so it survives the pill being
     * dismissed and re-shown for a new selection within the same reading session, matching how a
     * person expects "I picked underline last time" to stick around. Defaults to FILL, same as
     * every highlight created before this style option existed. */
    private var pendingStyle: ReaderHighlightStyle = ReaderHighlightStyle.FILL

    /** Which color dot last got tapped -- purely cosmetic (drives the checkmark badge in row 1,
     * mirroring the reference design's "currently active color" indicator), defaults to YELLOW
     * to match [promptAddNote]'s own default color for Note-only highlights. */
    private var lastUsedColor: ReaderHighlightColor = ReaderHighlightColor.YELLOW

    private fun bindPillButtons(popupView: View, snapshot: ReaderSelectionSnapshot) {
        popupView.alpha = 1f
        tintPillActionIcons(popupView)
        bindColorDot(popupView, R.id.reader_selection_color_red, ReaderHighlightColor.PINK, snapshot)
        bindColorDot(popupView, R.id.reader_selection_color_yellow, ReaderHighlightColor.YELLOW, snapshot)
        bindColorDot(popupView, R.id.reader_selection_color_green, ReaderHighlightColor.GREEN, snapshot)
        bindColorDot(popupView, R.id.reader_selection_color_purple, ReaderHighlightColor.BLUE, snapshot)

        popupView.findViewById<View>(R.id.reader_selection_action_clear).setOnClickListener {
            Logger.d("SelectionToolbar: Clear tapped, dismissing pill")
            dismissPillAndClearSelection()
        }
        popupView.findViewById<View>(R.id.reader_selection_action_note).setOnClickListener {
            promptAddNote(snapshot)
        }
        popupView.findViewById<View>(R.id.reader_selection_action_copy).setOnClickListener {
            copySelectionToClipboard(snapshot)
        }
        popupView.findViewById<View>(R.id.reader_selection_action_dictionary).setOnClickListener {
            host.showDictionaryLookup(snapshot.text)
        }
        popupView.findViewById<View>(R.id.reader_selection_action_translate).setOnClickListener {
            host.showTranslation(snapshot.text)
        }
    }

    private fun tintPillActionIcons(popupView: View) {
        val tint = ContextCompat.getColor(context, R.color.reader_selection_icon_tint)
        listOf(
            R.id.reader_selection_action_clear,
            R.id.reader_selection_action_note,
            R.id.reader_selection_action_copy,
            R.id.reader_selection_action_dictionary,
            R.id.reader_selection_action_translate,
        ).forEach { id ->
            popupView.findViewById<ImageButton>(id)?.apply {
                visibility = View.VISIBLE
                alpha = 1f
                imageTintList = ColorStateList.valueOf(tint)
                bringToFront()
            }
        }
    }

    private fun bindColorDot(
        popupView: View,
        viewId: Int,
        color: ReaderHighlightColor,
        snapshot: ReaderSelectionSnapshot,
    ) {
        val dot = popupView.findViewById<View>(viewId)
        dot.visibility = View.VISIBLE
        dot.alpha = 1f
        ViewCompat.setBackgroundTintList(dot, ColorStateList.valueOf(color.argb))
        dot.setOnClickListener {
            Logger.d(
                "SelectionToolbar: (pill) color dot tapped, color=${color.storageKey}, " +
                    "style=$pendingStyle, text='${snapshot.text.take(30)}'"
            )
            host.addHighlight(snapshot.locatorJson, color, null, snapshot.text.ifBlank { null }, pendingStyle)
            lastUsedColor = color
            dismissPillToolbar()
        }
    }

    private fun copySelectionToClipboard(snapshot: ReaderSelectionSnapshot) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null || snapshot.text.isBlank()) {
            Logger.d("SelectionToolbar: copy failed, clipboard service or text unavailable")
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("highlight", snapshot.text))
        host.showMessage(host.formatString(R.string.reader_selection_copied))
        dismissPillAndClearSelection()
    }

    private fun dismissPillAndClearSelection() {
        dismissPillToolbar()
        // The pill has no ActionMode to call finish() on (that's the whole point), so clearing
        // the selection itself is done the same way the color swatch already does it elsewhere:
        // via host.addHighlight()'s nav.clearSelection() path, or -- for Copy, which doesn't
        // create a highlight -- there's nothing here that force-clears the native WebView
        // selection. That's an acceptable, minor gap vs. the ActionMode-based flow (which used to
        // call mode.finish()): the selection handles just stay up until the person taps elsewhere,
        // same as normal Android text selection outside the reader.
    }

    private fun dismissPillToolbar() {
        pillPopup?.dismiss()
        pillPopup = null
    }

    // --- Fallback: previous ActionMode-based menu (see class kdoc for when this actually runs) -

    /**
     * BUG FIX: the current selection snapshot, refreshed proactively on every
     * `onCreateActionMode`/`onPrepareActionMode` call (i.e. as soon as the system tells us a
     * selection exists or changed) instead of being re-fetched lazily, a second time, right at
     * the moment a menu item is tapped. That lazy re-fetch (the old [withSnapshot]-only design,
     * still kept below as a last-resort fallback) raced the WebView: by the time the async
     * `nav.currentSelection()` call in [withSnapshot] came back, the selection had sometimes
     * already gone stale/null (no exception, no log -- `withSnapshot` just silently returned),
     * which looked exactly like "tapping Đánh dấu does nothing at all" on a real device. Reading
     * eagerly here, right when the bar/menu itself is being (re)built, uses the freshest moment
     * the selection is guaranteed to be alive.
     */
    private var cachedSnapshot: ReaderSelectionSnapshot? = null
    private var suppressFallbackActionModeMenu = false

    private fun refreshCachedSnapshot() {
        host.selectionScope().launch {
            val snapshot = host.currentSelectionSnapshot()
            cachedSnapshot = snapshot
            Logger.d("SelectionToolbar: cachedSnapshot refreshed, present=${snapshot != null}")
        }
    }

    fun buildActionModeCallback(): ActionMode.Callback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            Logger.d("SelectionToolbar: system ActionMode.onCreateActionMode() fired (fallback path)")
            // Some Android/WebView builds ignore MenuItem icons in FloatingToolbar. We used to
            // set empty titles to force an icon-only bar; on those builds that produced the exact
            // "dark empty pill" seen on device. When the system fallback appears, immediately
            // snapshot the selection, draw our own Waka-style popup toolbar, then close the
            // system ActionMode so the user never has to rely on platform menu icon rendering.
            menu ?: return true
            menu.clear()
            suppressFallbackActionModeMenu = true
            showCustomPillFromActionMode(mode, finishSystemMode = true)
            return true
        }

        // BUG FIX: this used to just `return false` ("menu unchanged"), the standard no-op
        // answer -- except Android calls onPrepareActionMode() again every single time the
        // selection itself changes (not just once, unlike onCreateActionMode), and on stock
        // WebView/Chromium builds with OEM "smart selection"/reading-assistant extensions (seen
        // in the field on MIUI, for example) the framework/OEM layer can re-populate the menu
        // with its *own* extra items right here -- e.g. a couple of quick "highlight yellow /
        // highlight green" chips -- alongside or ahead of ours. Because SHOW_AS_ACTION_ALWAYS
        // items lay out left-to-right in a fixed-width bar, those extra OS items can shove some
        // or all six of our own icons off into the overflow "⋮", which from the user's side looks
        // exactly like "the menu only shows two highlight-color options, not the full
        // Readest-style row of icons" (see prompt-nang-cap-selection-toolbar-v2.md's bug report).
        // Rebuilding the menu from scratch here -- clear, then re-add exactly our six items, same
        // as onCreateActionMode -- guarantees our own icons are the *only* things in it and can't
        // be re-crowded-out after the fact by whatever the platform/OEM injects on a later
        // prepare pass.
        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            menu ?: return false
            if (suppressFallbackActionModeMenu) {
                menu.clear()
                return true
            }
            menu.clear()
            populateActionModeMenu(menu, mode)
            refreshCachedSnapshot()
            return true
        }

        // BUG FIX: use cachedSnapshot (refreshed on every onCreateActionMode/onPrepareActionMode,
        // i.e. already known-good) synchronously here instead of firing a brand-new async
        // nav.currentSelection() query via withSnapshot on every tap -- see cachedSnapshot's kdoc
        // for the race this removes. withSnapshot is kept only as a last-resort fallback for the
        // rare case cachedSnapshot hasn't been populated yet (e.g. a click landing before the
        // first refreshCachedSnapshot() coroutine has completed).
        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            val snapshot = cachedSnapshot
            if (snapshot == null) {
                Logger.d("SelectionToolbar: onActionItemClicked with no cachedSnapshot yet, falling back to withSnapshot()")
                when (item?.itemId) {
                    // SECOND BUG FIX: the first attempt (mode?.finish() before
                    // showColorSwatchPopup()) still didn't make the swatch visible -- most likely
                    // because WebView's ActionMode.finish() dispatch swallows exceptions thrown
                    // from inside the callback chain it's driving (this call happens *from inside*
                    // onActionItemClicked, i.e. re-entrantly finishing the very mode that's still
                    // dispatching the click), so a WindowManager.BadTokenException/similar from
                    // showAtLocation() racing the ActionMode teardown could be getting silently
                    // dropped instead of crashing loudly where it'd be visible in logs.
                    // Sidestepping that race entirely: [showHighlightColorDialog] shows the color
                    // picker as a real AlertDialog instead of a PopupWindow. A Dialog is its own
                    // top-level focusable window -- exactly like Ghi chú's AlertDialog, which was
                    // never affected by this bug -- so Android forces the system floating
                    // ActionMode bar to hide on its own the moment the dialog takes focus, no
                    // manual mode?.finish() timing to get right.
                    ID_HIGHLIGHT -> withSnapshot(mode, finishAfter = false) { showHighlightColorDialog(it) }
                    ID_NOTE -> withSnapshot(mode, finishAfter = false) { promptAddNote(it) }
                    ID_SEARCH -> withSnapshot(mode, finishAfter = true) { host.openSearchInBook(it.text) }
                    ID_DICTIONARY -> withSnapshot(mode, finishAfter = false) { host.showDictionaryLookup(it.text) }
                    ID_TRANSLATE -> withSnapshot(mode, finishAfter = false) { host.showTranslation(it.text) }
                    ID_SHARE -> withSnapshot(mode, finishAfter = true) { host.shareSelectedText(it.text) }
                    else -> return false
                }
                return true
            }
            when (item?.itemId) {
                // Same fix as the withSnapshot() branch above -- use the dialog-based color
                // picker, not the PopupWindow-based one, in the ActionMode fallback path.
                ID_HIGHLIGHT -> showHighlightColorDialog(snapshot)
                ID_NOTE -> promptAddNote(snapshot)
                ID_SEARCH -> {
                    host.openSearchInBook(snapshot.text)
                    mode?.finish()
                }
                ID_DICTIONARY -> host.showDictionaryLookup(snapshot.text)
                ID_TRANSLATE -> host.showTranslation(snapshot.text)
                ID_SHARE -> {
                    host.shareSelectedText(snapshot.text)
                    mode?.finish()
                }
                else -> return false
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            suppressFallbackActionModeMenu = false
            dismissColorSwatchPopup()
            cachedSnapshot = null
        }
    }

    private fun showCustomPillFromActionMode(mode: ActionMode?, finishSystemMode: Boolean = false) {
        val token = ++selectionRequestToken
        host.selectionScope().launch {
            val snapshot = host.currentSelectionSnapshot()
            if (token != selectionRequestToken || snapshot == null) return@launch
            cachedSnapshot = snapshot
            showPillToolbar(snapshot)
            if (finishSystemMode) {
                mainHandler.postDelayed({ mode?.finish() }, 80L)
            }
        }
    }

    /** Our full 6-icon Readest-style set (Highlight, Note, Search, Dictionary, Translate, Share),
     * shared by [ActionMode.Callback.onCreateActionMode] and `onPrepareActionMode` above so both
     * always agree on exactly the same menu -- see the bug-fix note on `onPrepareActionMode`. */
    private fun populateActionModeMenu(menu: Menu, mode: ActionMode?) {
        addIconItem(menu, mode, ID_HIGHLIGHT, R.string.reader_selection_highlight, R.drawable.ic_reader_highlight)
        addIconItem(menu, mode, ID_NOTE, R.string.reader_selection_note, R.drawable.ic_selection_note)
        addIconItem(menu, mode, ID_SEARCH, R.string.reader_selection_search, R.drawable.ic_selection_search)
        addIconItem(menu, mode, ID_DICTIONARY, R.string.reader_selection_dictionary, R.drawable.ic_selection_dictionary)
        addIconItem(menu, mode, ID_TRANSLATE, R.string.reader_selection_translate, R.drawable.ic_selection_translate)
        addIconItem(menu, mode, ID_SHARE, R.string.reader_selection_share, R.drawable.ic_selection_share)
    }

    private fun addIconItem(menu: Menu, mode: ActionMode?, id: Int, titleRes: Int, iconRes: Int) {
        val label = host.formatString(titleRes)
        menu.add(Menu.NONE, id, Menu.NONE, "").apply {
            setIcon(iconRes)
            titleCondensed = ""
            val button = ImageButton(context).apply {
                layoutParams = ViewGroup.LayoutParams(dp(46), dp(46))
                background = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
                    .let { typedArray ->
                        try {
                            typedArray.getDrawable(0)
                        } finally {
                            typedArray.recycle()
                        }
                    }
                setPadding(dp(11), dp(11), dp(11), dp(11))
                setImageDrawable(ContextCompat.getDrawable(context, iconRes)?.mutate()?.let { drawable ->
                    DrawableCompat.setTint(drawable, 0xFFFFFFFF.toInt())
                    drawable
                })
                contentDescription = label
                setOnClickListener { handleActionModeCommand(mode, id) }
            }
            MenuItemCompat.setActionView(this, button)
            MenuItemCompat.setContentDescription(this, label)
            MenuItemCompat.setTooltipText(this, label)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
    }

    private fun handleActionModeCommand(mode: ActionMode?, itemId: Int): Boolean {
        val snapshot = cachedSnapshot
        if (snapshot == null) {
            Logger.d("SelectionToolbar: action item tapped with no cachedSnapshot yet, falling back to withSnapshot()")
            when (itemId) {
                ID_HIGHLIGHT -> withSnapshot(mode, finishAfter = false) { showHighlightColorDialog(it) }
                ID_NOTE -> withSnapshot(mode, finishAfter = false) { promptAddNote(it) }
                ID_SEARCH -> withSnapshot(mode, finishAfter = true) { host.openSearchInBook(it.text) }
                ID_DICTIONARY -> withSnapshot(mode, finishAfter = false) { host.showDictionaryLookup(it.text) }
                ID_TRANSLATE -> withSnapshot(mode, finishAfter = false) { host.showTranslation(it.text) }
                ID_SHARE -> withSnapshot(mode, finishAfter = true) { host.shareSelectedText(it.text) }
                else -> return false
            }
            return true
        }
        when (itemId) {
            ID_HIGHLIGHT -> showHighlightColorDialog(snapshot)
            ID_NOTE -> promptAddNote(snapshot)
            ID_SEARCH -> {
                host.openSearchInBook(snapshot.text)
                mode?.finish()
            }
            ID_DICTIONARY -> host.showDictionaryLookup(snapshot.text)
            ID_TRANSLATE -> host.showTranslation(snapshot.text)
            ID_SHARE -> {
                host.shareSelectedText(snapshot.text)
                mode?.finish()
            }
            else -> return false
        }
        return true
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** Runs [block] with the current selection snapshot, if there still is one; optionally
     * finishes the ActionMode afterwards (Search/Share end the selection, Dictionary/Translate
     * deliberately don't, so the selection -- and its native handles -- stay visible while the
     * lookup bottom sheet is open, matching Readest's behaviour of not clearing the selection).
     * Only used by the ActionMode fallback path -- the pill toolbar already has its snapshot on
     * hand from [handleSelectionActive] and calls [showColorSwatchPopup]/[promptAddNote] directly. */
    private fun withSnapshot(mode: ActionMode?, finishAfter: Boolean, block: (ReaderSelectionSnapshot) -> Unit) {
        host.selectionScope().launch {
            val snapshot = host.currentSelectionSnapshot() ?: return@launch
            block(snapshot)
            if (finishAfter) mode?.finish()
        }
    }

    private fun promptAddNote(snapshot: ReaderSelectionSnapshot) {
        dismissPillToolbar()
        val dialogView = LayoutInflater.from(context)
            .inflate(R.layout.dialog_reader_note_editor, null, false)
        val input = dialogView.findViewById<EditText>(R.id.reader_note_input)
        AlertDialog.Builder(context)
            .setTitle(R.string.reader_highlight_add_note)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                host.addHighlight(
                    snapshot.locatorJson,
                    ReaderHighlightColor.YELLOW,
                    input.text?.toString()?.trim().orEmpty().ifBlank { null },
                    snapshot.text.ifBlank { null },
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- Highlight color picker -- dialog variant for the ActionMode fallback path ------------

    /**
     * Same 4-color-dot content as [showColorSwatchPopup] (reuses the identical
     * `view_reader_selection_color_popup.xml` layout), but hosted inside a real [AlertDialog]
     * instead of a `PopupWindow`. Used ONLY from [buildActionModeCallback]'s
     * `onActionItemClicked` -- see the "SECOND BUG FIX" comment there for why: the PopupWindow
     * variant is deliberately non-focusable (so it doesn't steal focus from the live text
     * selection when shown from the Readest-style pill, which has no competing system UI), but
     * that same non-focusability meant it could never draw above the system's own floating
     * `ActionMode` bar, which keeps window focus until it's explicitly finished/hidden. A
     * [Dialog] is always its own focusable top-level window, so this sidesteps that entirely --
     * exactly the same reason [promptAddNote]'s `AlertDialog` was never affected by this bug.
     */
    private fun showHighlightColorDialog(snapshot: ReaderSelectionSnapshot) {
        dismissPillToolbar()
        dismissColorSwatchPopup()

        val dialogView = LayoutInflater.from(context)
            .inflate(R.layout.view_reader_selection_color_popup, null, false)

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(0))

        fun bindDialogColorDot(viewId: Int, color: ReaderHighlightColor) {
            val dot = dialogView.findViewById<View>(viewId)
            ViewCompat.setBackgroundTintList(dot, ColorStateList.valueOf(color.argb))
            dot.setOnClickListener {
                Logger.d("SelectionToolbar: (dialog) color dot tapped, color=${color.storageKey}, text='${snapshot.text.take(30)}'")
                host.addHighlight(snapshot.locatorJson, color, null, snapshot.text.ifBlank { null })
                dialog.dismiss()
            }
        }
        bindDialogColorDot(R.id.reader_selection_color_yellow, ReaderHighlightColor.YELLOW)
        bindDialogColorDot(R.id.reader_selection_color_green, ReaderHighlightColor.GREEN)
        bindDialogColorDot(R.id.reader_selection_color_blue, ReaderHighlightColor.BLUE)
        bindDialogColorDot(R.id.reader_selection_color_pink, ReaderHighlightColor.PINK)
        dialogView.findViewById<ImageButton>(R.id.reader_selection_color_remove).setOnClickListener {
            dialog.dismiss()
        }

        Logger.d("SelectionToolbar: showing color dialog (ActionMode fallback), text='${snapshot.text.take(30)}'")
        dialog.show()
    }

    // --- Highlight color swatch popup (legacy PopupWindow variant) --------------------------
    // BUG FIX / cleanup: the pill toolbar's row 1 now has its own inline color dots (see
    // bindColorDot() near bindPillButtons() above) instead of opening a second floating popup
    // when Highlight is tapped, so the PopupWindow-based showColorSwatchPopup() this used to call
    // is gone. colorPopup/dismissColorSwatchPopup() stay as harmless no-ops (colorPopup is always
    // null now) purely so the handful of defensive dismissColorSwatchPopup() calls elsewhere in
    // this file (onLocatorChanged-adjacent cleanup, ActionMode lifecycle, etc.) don't need to be
    // hunted down and deleted one by one -- deleting this pair outright is a fine follow-up but
    // not required for the redesign itself.
    private var colorPopup: PopupWindow? = null

    private fun dismissColorSwatchPopup() {
        colorPopup?.dismiss()
        colorPopup = null
    }

    /** Anchor-LOCAL coordinates (i.e. relative to [anchor]'s own top-left corner, same space as
     * `Selection.rect` -- callers must run the result through [toScreenLocation] before passing it
     * to [PopupWindow.showAtLocation], which expects absolute screen coordinates) for the popup,
     * clamped so it never draws off the left/right edge of [anchor]. When [rect] is known,
     * the popup opens centered under the selection's *bottom* edge (falling back to *above* the
     * *top* edge if there isn't enough room below); when it's null -- Readium documents
     * `Selection.rect` as nullable -- this falls back to an approximate spot near the top-center
     * of [anchor], which is deliberately conservative rather than guessing a location that could
     * land on top of the ActionMode bar itself. */
    private fun anchorLocation(anchor: View, rect: RectF?, popupWidth: Int, popupHeight: Int): Pair<Int, Int> {
        val anchorWidth = anchor.width.takeIf { it > 0 } ?: anchor.resources.displayMetrics.widthPixels
        val anchorHeight = anchor.height.takeIf { it > 0 } ?: anchor.resources.displayMetrics.heightPixels

        val rawX: Int
        val rawY: Int
        if (rect != null) {
            rawX = (rect.centerX() - popupWidth / 2f).toInt()
            val spaceBelow = anchorHeight - rect.bottom
            rawY = if (spaceBelow >= popupHeight + SWATCH_MARGIN_PX) {
                (rect.bottom + SWATCH_MARGIN_PX).toInt()
            } else {
                (rect.top - popupHeight - SWATCH_MARGIN_PX).toInt()
            }
        } else {
            rawX = (anchorWidth - popupWidth) / 2
            rawY = FALLBACK_TOP_OFFSET_PX
        }

        val clampedX = rawX.coerceIn(SWATCH_MARGIN_PX, (anchorWidth - popupWidth - SWATCH_MARGIN_PX).coerceAtLeast(SWATCH_MARGIN_PX))
        val clampedY = rawY.coerceIn(SWATCH_MARGIN_PX, (anchorHeight - popupHeight - SWATCH_MARGIN_PX).coerceAtLeast(SWATCH_MARGIN_PX))
        return clampedX to clampedY
    }

    /** [PopupWindow.showAtLocation] with [Gravity.NO_GRAVITY] positions the popup using absolute
     * on-screen coordinates -- but [anchorLocation] (and `Selection.rect` before it) works in the
     * coordinate space of [anchor] itself (view-local, i.e. relative to that view's own top-left
     * corner). Those two spaces only coincide when [anchor] happens to start at (0,0) of its
     * window, which is rarely true here (there's a header bar / system status bar above it) --
     * without this conversion the popup silently draws off-screen (usually above/left of the
     * visible area), with no exception and no log, which looked exactly like "nothing happens
     * when I select text" when tested on a real device.
     *
     * BUG FIX: added this conversion step -- [showPillToolbar] / [showColorSwatchPopup] used to
     * pass [anchorLocation]'s view-local (x, y) straight into `showAtLocation` unconverted. */
    private fun toScreenLocation(anchor: View, localX: Int, localY: Int): Pair<Int, Int> {
        val screenPos = IntArray(2)
        anchor.getLocationOnScreen(screenPos)
        return (localX + screenPos[0]) to (localY + screenPos[1])
    }

    companion object {
        private const val ID_HIGHLIGHT = 2001
        private const val ID_NOTE = 2002
        private const val ID_SEARCH = 2003
        private const val ID_DICTIONARY = 2004
        private const val ID_TRANSLATE = 2005
        private const val ID_SHARE = 2006

        private const val SWATCH_MARGIN_PX = 16
        private const val FALLBACK_TOP_OFFSET_PX = 160

        /** Name the JS side calls back into, e.g. `window.CsyncSelectionBridge.onSelectionActive()`.
         * Passed to `EpubNavigatorFragment.Configuration.registerJavascriptInterface(...)` by the
         * host Activity. */
        const val JS_INTERFACE_NAME = "CsyncSelectionBridge"

        /**
         * Injected into every visible EPUB resource (see [onLocatorChanged]). Mirrors exactly the
         * two things Readest's own `useTextSelector.ts`/`Annotator.tsx` do in the reader's WebView
         * (see class kdoc): suppress the native long-press context menu while there's a live
         * selection, and report selection-changed/-cleared, debounced, so the native code can
         * show/reposition/hide [showPillToolbar] itself. Idempotent per-document via the
         * `__csyncSelWatcherInstalled` guard, since a resource's WebView can be re-injected into
         * more than once (e.g. if [onLocatorChanged] fires again before a page unload completes).
         */
        val SELECTION_WATCH_JS = """
            (function() {
              if (window.__csyncSelWatcherInstalled) { return 'already-installed'; }
              window.__csyncSelWatcherInstalled = true;
              document.addEventListener('contextmenu', function(e) {
                var sel = window.getSelection();
                if (sel && String(sel).length > 0) { e.preventDefault(); }
              }, true);
              var csyncSelTimer = null;
              function csyncReportSelection() {
                if (!window.$JS_INTERFACE_NAME) { return; }
                var sel = window.getSelection();
                var text = sel ? String(sel) : '';
                if (text.length > 0) {
                  window.$JS_INTERFACE_NAME.onSelectionActive();
                } else {
                  window.$JS_INTERFACE_NAME.onSelectionCleared();
                }
              }
              document.addEventListener('selectionchange', function() {
                if (csyncSelTimer) { clearTimeout(csyncSelTimer); }
                csyncSelTimer = setTimeout(csyncReportSelection, 120);
              });
              return 'installed';
            })();
        """.trimIndent()
    }
}
