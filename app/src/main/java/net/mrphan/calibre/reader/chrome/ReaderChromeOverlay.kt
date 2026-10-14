package net.mrphan.calibre.reader.chrome

import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import net.mrphan.calibre.R

/**
 * Shared "chrome" overlay for all three reader Activities: a thin header bar (top) + thin footer
 * bar (bottom) that fade in over the page on a middle-of-screen tap and fade back out on the next
 * tap, plus a hamburger-menu bottom sheet ([ReaderMenuSheet]) for everything that doesn't fit in
 * the header/footer -- modeled on the Readest app's HeaderBar/NavigationBar/ViewMenu layout (see
 * prompt-nang-cap-reader-toolbar.md). This replaces the previous full-screen semi-transparent
 * button-grid design; the *gesture* that shows/hides the chrome (dispatchContentTouch/
 * EDGE_ZONE_FRACTION/show()/hide()/toggle()) is unchanged from that earlier design.
 *
 * One instance is created per reader Activity, bound to the `<include layout=
 * "@layout/view_reader_chrome_overlay" />` root inflated into that Activity's own layout (see
 * `activity_reader_epub.xml` / `_pdf.xml` / `_cbz.xml`) plus that Activity's [ReaderChromeHost]
 * implementation. No Activity-specific logic lives here -- everything reader-specific goes through
 * [ReaderChromeHost] / [ReaderAction] instead, which is what lets all three Activities share this
 * one class.
 */
class ReaderChromeOverlay(
    includedRoot: View,
    private val host: ReaderChromeHost,
) {
    private val overlayRoot: ViewGroup = includedRoot.findViewById(R.id.reader_chrome_overlay_root)
    private val headerBar: View = includedRoot.findViewById(R.id.reader_header_bar)
    private val headerTitle: TextView = includedRoot.findViewById(R.id.reader_header_title)
    private val headerStartGroup: LinearLayout = includedRoot.findViewById(R.id.reader_header_start_group)
    private val headerEndGroup: LinearLayout = includedRoot.findViewById(R.id.reader_header_end_group)
    private val menuButton: ImageButton = includedRoot.findViewById(R.id.reader_header_menu_button)
    private val closeButton: ImageButton = includedRoot.findViewById(R.id.reader_header_close_button)
    private val footerBar: LinearLayout = includedRoot.findViewById(R.id.reader_footer_bar)
    private val bottomStack: View = includedRoot.findViewById(R.id.reader_chrome_bottom_stack)
    // Chi ton tai trong activity_reader_epub.xml (xem view_tts_mini_player.xml) -- PDF/CBZ chua ho
    // tro TTS nen findViewById tra ve null o do, moi thao tac ben duoi phai tu kiem tra null.
    private val ttsMiniPlayerRoot: View? = includedRoot.findViewById(R.id.tts_mini_player_root)
    private val musicMiniPlayerRoot: View? = includedRoot.findViewById(R.id.reader_music_player_root)
    private val progressPanelRoot: View = includedRoot.findViewById(R.id.reader_progress_panel_root)
    private val progressSeekBar: ReaderProgressSeekBar = includedRoot.findViewById(R.id.reader_progress_seekbar)
    private val progressJumpStartButton: ImageButton =
        includedRoot.findViewById(R.id.reader_progress_jump_start_button)
    private val progressJumpEndButton: ImageButton =
        includedRoot.findViewById(R.id.reader_progress_jump_end_button)
    private val progressBackButton: ImageButton = includedRoot.findViewById(R.id.reader_progress_back_button)
    private val progressForwardButton: ImageButton = includedRoot.findViewById(R.id.reader_progress_forward_button)
    private val progressPrevChapterButton: ImageButton =
        includedRoot.findViewById(R.id.reader_progress_prev_chapter_button)
    private val progressNextChapterButton: ImageButton =
        includedRoot.findViewById(R.id.reader_progress_next_chapter_button)
    private val progressChapterLabel: TextView =
        includedRoot.findViewById(R.id.reader_progress_chapter_label)

    /** ReaderAction -> footer icon View đã inflate, để [refreshFooterActiveStates] tô lại màu
     * nhấn (xanh, kiểu Readest) khi trạng thái "đang hoạt" của nút đó đổi -- xem
     * [isFooterActionActive]. Xây lại mỗi lần [buildFooterIcons] chạy. */
    private val footerIconViewsByAction = mutableMapOf<ReaderAction, ImageView>()

    private var lastKnownContentWidth = 0
    private var shown = false
    private var progressPanelShown = false
    private val ttsMiniPlayerBaseBottomMarginPx: Int =
        (ttsMiniPlayerRoot?.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
    private val ttsMiniPlayerChromeGapPx: Int =
        (8f * includedRoot.resources.displayMetrics.density).toInt()
    private val musicMiniPlayerBaseBottomMarginPx: Int =
        (musicMiniPlayerRoot?.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
    private val musicMiniPlayerChromeGapPx: Int =
        (8f * includedRoot.resources.displayMetrics.density).toInt()

    /** Detects a confirmed single tap. Middle 50% width zone toggles the chrome. The two 25% edge
     * strips, previously unclaimed ("reserved for whatever page-turn gesture the content view
     * already has" -- in practice nothing claimed them, since Readium's ViewPager2 only reacts to
     * flicks, not taps), now optionally turn the page when
     * [ReaderChromeHost.isEdgeTapPageTurnEnabled] is true (see
     * prompt-nang-cap-phim-am-luong-vung-cham-lat-trang.md). `onDown` must return true or
     * GestureDetector never reports `onSingleTapConfirmed` at all. */
    private val gestureDetector = GestureDetector(
        includedRoot.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val width = lastKnownContentWidth
                if (width <= 0) return false
                val leftEdge = width * EDGE_ZONE_FRACTION
                val rightEdge = width * (1f - EDGE_ZONE_FRACTION)
                if (e.x in leftEdge..rightEdge) {
                    toggle()
                    return true
                }
                // Cham vao 1 trong 2 vung mep (25% moi ben). Chi lat trang neu nguoi dung da bat
                // trong tab "Thao tac" (xem prompt-nang-cap-phim-am-luong-vung-cham-lat-trang.md) --
                // mac dinh tra false o day (isEdgeTapPageTurnEnabled() == false) nen hanh vi cu
                // (khong lam gi khi cham mep) khong doi cho nguoi dung chua bat tinh nang moi.
                if (host.isEdgeTapPageTurnEnabled()) {
                    val invert = host.isEdgeTapPageTurnInverted()
                    val tappedLeftZone = e.x < leftEdge
                    // Mac dinh: mep trai = lui trang, mep phai = loi toi; dao lai khi invert = true.
                    val turnsForward = if (tappedLeftZone) invert else !invert
                    if (turnsForward) host.goForward() else host.goBack()
                    return true
                }
                return false
            }
        }
    )

    init {
        headerTitle.text = host.bookTitle
        buildHeaderIcons()
        buildFooterIcons()
        applyChromeTheme()
        wireProgressPanel()
        applyChromeInsets()
        // Tapping the transparent backdrop itself (i.e. anywhere that isn't the header, the
        // footer, or the progress panel) hides the chrome again -- this is the "chạm ra ngoài
        // toolbar" case from the spec, distinct from tapping the content view's middle zone
        // (handled by dispatchContentTouch/gestureDetector below).
        overlayRoot.setOnClickListener { hide() }
        // Per spec section 4.6: the header's close (X) button now IS the old QuitAction's
        // behaviour (finish the reader Activity outright), not just "hide the chrome" -- that's
        // now purely the tap-gesture's job. QuitAction itself is left in ReaderActions.kt, unused,
        // per the registry's kdoc.
        closeButton.setOnClickListener { host.close() }
        menuButton.setOnClickListener {
            ReaderMenuSheet(host).show(host.fragmentManager(), ReaderMenuSheet.TAG)
        }
        overlayRoot.visibility = View.GONE
        overlayRoot.alpha = 0f
        // Khi chrome hien, footer/progress stack phai nam o day man hinh va mini player noi len
        // PHIA TREN stack do (giong Waka). Cu giu mini player dock day se che toolbar; day nguoc
        // bottomStack len lai lam footer khong con dung vi tri. Listener nay chi cap nhat bottom
        // margin cua cac mini player theo chieu cao bottomStack hien tai.
        if (ttsMiniPlayerRoot != null || musicMiniPlayerRoot != null) {
            includedRoot.viewTreeObserver.addOnGlobalLayoutListener {
                positionMiniPlayersAroundChrome()
            }
        }
    }

    private fun positionMiniPlayersAroundChrome() {
        positionMiniPlayerAroundChrome(
            player = ttsMiniPlayerRoot,
            baseBottomMarginPx = ttsMiniPlayerBaseBottomMarginPx,
            chromeGapPx = ttsMiniPlayerChromeGapPx,
        )
        positionMiniPlayerAroundChrome(
            player = musicMiniPlayerRoot,
            baseBottomMarginPx = musicMiniPlayerBaseBottomMarginPx,
            chromeGapPx = musicMiniPlayerChromeGapPx,
        )
    }

    private fun positionMiniPlayerAroundChrome(
        player: View?,
        baseBottomMarginPx: Int,
        chromeGapPx: Int,
    ) {
        player ?: return
        val lp = player.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val chromeOffsetPx = if (shown) bottomStack.height + chromeGapPx else 0
        val targetBottomMargin = baseBottomMarginPx + chromeOffsetPx
        if (lp.bottomMargin == targetBottomMargin) return
        lp.bottomMargin = targetBottomMargin
        player.layoutParams = lp
    }

    private fun buildHeaderIcons() {
        headerStartGroup.removeAllViews()
        headerEndGroup.removeAllViews()
        val inflater = LayoutInflater.from(headerStartGroup.context)
        for (action in ReaderActionRegistry.headerStartActionsFor(host.readerKind)) {
            headerStartGroup.addView(inflateHeaderIcon(inflater, headerStartGroup, action))
        }
        for (action in ReaderActionRegistry.headerEndActionsFor(host.readerKind)) {
            headerEndGroup.addView(inflateHeaderIcon(inflater, headerEndGroup, action))
        }
    }

    private fun inflateHeaderIcon(
        inflater: LayoutInflater,
        parent: ViewGroup,
        action: ReaderAction,
    ): View {
        val button = inflater.inflate(R.layout.item_reader_header_icon, parent, false) as ImageButton
        button.setImageResource(action.icon)
        button.contentDescription = host.formatString(action.label)
        button.setOnClickListener { action.invoke(host) }
        return button
    }

    private fun buildFooterIcons() {
        footerBar.removeAllViews()
        footerIconViewsByAction.clear()
        val inflater = LayoutInflater.from(footerBar.context)
        for (action in ReaderActionRegistry.footerActionsFor(host.readerKind)) {
            val item = inflater.inflate(R.layout.item_reader_footer_icon, footerBar, false)
            val label = host.formatString(action.label)
            val iconView = item.findViewById<ImageView>(R.id.reader_footer_item_icon).apply {
                setImageResource(action.icon)
                contentDescription = label
            }
            footerIconViewsByAction[action] = iconView
            // Text label itself stays hidden (view_item_reader_footer_icon.xml sets
            // visibility="gone") so the footer bar is icon-only, Readest-style/gọn hơn -- still
            // set here (rather than removed) so TalkBack/accessibility services that read a
            // GONE-but-populated TextView's text as part of the row still get it, and so a future
            // "show labels" toggle can just flip that one XML attribute back.
            item.findViewById<TextView>(R.id.reader_footer_item_label).text = label
            item.contentDescription = label
            item.setOnClickListener {
                // GotoLocationAction stands in for the whole Back/SeekBar/Forward group in the
                // footer -- see ReaderActionSlot.FOOTER kdoc and spec section 4.4/4.6. Tapping it
                // opens/closes the slide-up ReaderProgressPanel instead of calling invoke()
                // directly; every other footer action is invoked normally.
                if (action === GotoLocationAction) {
                    toggleProgressPanel()
                } else {
                    action.invoke(host)
                }
                // Vai nut co the doi trang thai "dang hoat" ngay sau khi bam (vd Doc to nghe bat
                // len) -- to lai mau ngay thay vi doi lan show() ke tiep.
                refreshFooterActiveStates()
            }
            footerBar.addView(item)
        }
        refreshFooterActiveStates()
    }

    private fun applyChromeTheme() {
        val colorRes = when (host.currentThemeMode()) {
            ReaderThemeMode.LIGHT -> R.color.reader_chrome_bar_light
            ReaderThemeMode.DARK -> R.color.reader_chrome_bar_dark
        }
        val barColor = androidx.core.content.ContextCompat.getColor(headerBar.context, colorRes)
        headerBar.setBackgroundColor(barColor)
        footerBar.setBackgroundColor(barColor)
    }

    /** Nut nao trong footer dang o trang thai "dang hoat" -- Readest to xanh cac nut nay (vd nut
     * "Tiến độ" trong khi panel tien do dang mo, xem anh chup man hinh dinh kem prompt). Mo rong
     * o day (them 1 nhanh `when`) neu sau nay co them trang thai can to mau tuong tu. */
    private fun isFooterActionActive(action: ReaderAction): Boolean = when (action) {
        GotoLocationAction -> progressPanelShown
        ReadAloudAction -> host.isReadAloudActive()
        else -> false
    }

    /** To lai mau (xanh khi dang hoat, trang binh thuong khi khong) cho tung icon footer da
     * inflate -- goi sau moi lan trang thai co the doi: buildFooterIcons() (lan dau), tap 1 nut
     * footer bat ky, va toggleProgressPanel()/show(). */
    private fun refreshFooterActiveStates() {
        for ((action, iconView) in footerIconViewsByAction) {
            applyFooterIconTint(iconView, active = isFooterActionActive(action))
        }
    }

    private fun applyFooterIconTint(iconView: ImageView, active: Boolean) {
        val context = iconView.context
        if (active) {
            iconView.setColorFilter(androidx.core.content.ContextCompat.getColor(context, R.color.reader_accent_blue))
            iconView.background = androidx.core.content.ContextCompat.getDrawable(
                context,
                R.drawable.bg_reader_footer_icon_active,
            )
        } else {
            iconView.clearColorFilter()
            iconView.background = null
        }
    }

    /**
     * Keeps the header/footer bars clear of the status bar and navigation/gesture bar, Readest-
     * style, instead of drawing edge-to-edge underneath them (which is what made the header
     * collide visually with the device's own status bar icons/clock -- the two rows fighting for
     * the same strip at the top of the screen). The header's original XML padding/minHeight is
     * kept intact and the status bar height is added on top of it; same idea for the footer with
     * the bottom/navigation bar inset. Re-applying this listener is safe: we always add the inset
     * to the *original* padding captured once here, never to whatever padding is currently set, so
     * repeated insets callbacks (rotation, keyboard, etc.) never keep stacking extra space.
     */
    private fun applyChromeInsets() {
        val headerBasePaddingTop = headerBar.paddingTop
        val footerBasePaddingBottom = footerBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(overlayRoot) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            headerBar.updatePadding(top = headerBasePaddingTop + systemBars.top)
            footerBar.updatePadding(bottom = footerBasePaddingBottom + systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(overlayRoot)
    }

    /** Wires the Back/SeekBar/Forward panel that slides up directly above the footer when the
     * "Tiến độ" footer icon is tapped -- see spec section 4.4. [BackAction]/[ForwardAction] are
     * reused as plain Kotlin objects here (they're never rendered anywhere on their own; see
     * their kdoc in ReaderActions.kt), so the 3-action-old-back/forward/goto behaviour is
     * preserved without duplicating any navigation logic. */
    private fun wireProgressPanel() {
        // Hang dieu huong day du kieu Readest: Ve dau sach - Chuong truoc - Lui vi tri -
        // Toi vi tri - Chuong sau - Ve cuoi sach.
        progressJumpStartButton.setOnClickListener { jumpToFractionAndRefresh(0f) }
        progressJumpEndButton.setOnClickListener { jumpToFractionAndRefresh(1f) }
        progressBackButton.setOnClickListener { BackAction.invoke(host) }
        progressForwardButton.setOnClickListener { ForwardAction.invoke(host) }
        progressPrevChapterButton.setOnClickListener {
            if (!host.goToPreviousChapter()) {
                host.showMessage(host.formatString(R.string.reader_progress_no_prev_chapter))
            } else {
                refreshProgressPanel()
            }
        }
        progressNextChapterButton.setOnClickListener {
            if (!host.goToNextChapter()) {
                host.showMessage(host.formatString(R.string.reader_progress_no_next_chapter))
            } else {
                refreshProgressPanel()
            }
        }
        // Khong con can tu cap nhat nhan % o day nua -- ReaderProgressSeekBar tu ve chu "NN%"
        // ngay tren pill va tu invalidate() moi khi progress doi (ke ca khi nguoi dung dang keo),
        // xem class do.
        progressSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = Unit

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            // Nang cap: goi thang jumpToProgressFraction() thay vi GotoLocationAction.invoke()
            // (cai cu luon bao "khong ho tro" vi openGoToLocation() = false - xem muc 1.2).
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val fraction = seekBar.progress / 100f
                if (!host.jumpToProgressFraction(fraction)) {
                    host.showMessage(
                        host.formatString(
                            R.string.reader_chrome_action_unsupported,
                            host.formatString(R.string.reader_action_goto),
                        )
                    )
                } else {
                    refreshProgressPanel()
                }
            }
        })
    }

    /** Dung chung cho 2 nut "Ve dau sach"/"Ve cuoi sach" -- cung 1 duong jumpToProgressFraction()
     * ma keo-tha SeekBar da dung, chi khac fraction co dinh 0f/1f. */
    private fun jumpToFractionAndRefresh(fraction: Float) {
        if (!host.jumpToProgressFraction(fraction)) {
            host.showMessage(
                host.formatString(
                    R.string.reader_chrome_action_unsupported,
                    host.formatString(R.string.reader_action_goto),
                )
            )
        } else {
            refreshProgressPanel()
        }
    }

    private fun refreshProgressPanel() {
        val fraction = host.currentProgressFraction()
        progressSeekBar.progress = (fraction * 100).toInt().coerceIn(0, 100)
        progressSeekBar.tickFractions = host.chapterTickFractions()
        val chapterTitle = host.currentChapterTitle()
        progressChapterLabel.text = chapterTitle
        progressChapterLabel.visibility = if (chapterTitle.isBlank()) View.GONE else View.VISIBLE
    }

    private fun toggleProgressPanel() {
        if (progressPanelShown) hideProgressPanel() else showProgressPanel()
    }

    private fun showProgressPanel() {
        if (progressPanelShown) return
        progressPanelShown = true
        refreshProgressPanel()
        progressPanelRoot.visibility = View.VISIBLE
        refreshFooterActiveStates()
    }

    private fun hideProgressPanel() {
        if (!progressPanelShown) return
        progressPanelShown = false
        progressPanelRoot.visibility = View.GONE
        positionMiniPlayersAroundChrome()
        refreshFooterActiveStates()
    }

    /** Rebuilds the header/footer icons -- call after something that could change [ReaderAction]
     * relevance or availability (there is none of that yet, but kept for forward-compatibility
     * with a future "Reset interface" / dynamic registry). */
    fun refreshActions() {
        applyChromeTheme()
        buildHeaderIcons()
        buildFooterIcons()
    }

    fun show() {
        if (shown) return
        shown = true
        applyChromeTheme()
        bottomStack.translationY = 0f
        positionMiniPlayersAroundChrome()
        refreshFooterActiveStates()
        overlayRoot.visibility = View.VISIBLE
        overlayRoot.post { positionMiniPlayersAroundChrome() }
        overlayRoot.animate().cancel()
        overlayRoot.translationY = -SLIDE_DISTANCE_DP
        overlayRoot.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(ANIMATION_DURATION_MS)
            .start()
    }

    fun hide() {
        if (!shown) return
        shown = false
        hideProgressPanel()
        positionMiniPlayersAroundChrome()
        overlayRoot.animate().cancel()
        overlayRoot.animate()
            .alpha(0f)
            .translationY(-SLIDE_DISTANCE_DP)
            .setDuration(ANIMATION_DURATION_MS)
            .withEndAction { overlayRoot.visibility = View.GONE }
            .start()
    }

    fun toggle() {
        if (shown) hide() else show()
    }

    fun isShown(): Boolean = shown

    /**
     * Feeds a touch event from the reader's content view (WebView-hosting fragment container for
     * Epub, ViewPager2 for Pdf/Cbz) into the tap-zone [gestureDetector].
     *
     * IMPLEMENTATION NOTE: this is called from each Activity's own `dispatchTouchEvent()`
     * override rather than a plain `View.setOnTouchListener` on the content view, because a
     * `WebView`/`ViewPager2` largely consumes its own touch stream for scrolling/paging, which
     * makes a listener attached directly to (or above) those views unreliable for ever seeing a
     * clean single-tap. Dispatching from the Activity sees every event unconditionally *before*
     * the content view gets a chance to consume it, and this method never reports the event as
     * consumed -- callers must still forward the event to `super.dispatchTouchEvent(event)`
     * afterwards -- so existing page-turn/scroll/zoom gestures on the content view are completely
     * unaffected; this only ever *observes* taps to decide whether to toggle the chrome.
     *
     * BUG FIX: only feeds the event into [gestureDetector] while the chrome is currently HIDDEN.
     * Previously this ran unconditionally, including while the chrome (header/footer/progress
     * panel) was already shown and visually on top of the content -- since this Activity-level
     * dispatch only ever looks at [MotionEvent.getX] against [lastKnownContentWidth] and never
     * checks Y position or whether the tap actually landed on the content view at all, tapping a
     * footer icon positioned in the middle 50% width zone (e.g. "Tiến độ", the 3rd of 5 evenly
     * spaced footer icons) ALSO satisfied the middle-tap-to-toggle gesture and called [toggle] ->
     * [hide] on the whole overlay in the same gesture that the icon's own click listener used to
     * show the progress panel -- the panel would flash open and immediately close, giving the
     * user no time to drag the seekbar. While the chrome is shown, tapping the transparent
     * backdrop in between the header/footer to dismiss it is already handled by
     * [overlayRoot]'s own `setOnClickListener { hide() }` (see the `init` block above), so this
     * gesture-based toggle is only ever needed to *reveal* the chrome from a fully hidden state,
     * never to hide an already-shown one.
     */
    fun dispatchContentTouch(event: MotionEvent, contentWidth: Int) {
        if (shown) return
        lastKnownContentWidth = contentWidth
        gestureDetector.onTouchEvent(event)
    }

    companion object {
        /** Width fraction claimed by each edge tap zone; spec calls for ~25% / 50% / 25%, kept as
         * a named constant so it's easy to retune. */
        private const val EDGE_ZONE_FRACTION = 0.25f
        private const val ANIMATION_DURATION_MS = 200L
        private const val SLIDE_DISTANCE_DP = 40f
    }
}
