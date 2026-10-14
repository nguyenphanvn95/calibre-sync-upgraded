package net.mrphan.calibre.reader.chrome

/**
 * The single source of truth for what appears on the reader chrome (thin header bar + thin footer
 * bar + hamburger-menu bottom sheet, replacing the old full-screen button grid -- see
 * `prompt-nang-cap-reader-toolbar.md`) and in what order -- modeled on calibre desktop's
 * `viewer/toolbars.py` (`all_actions()` + `DEFAULT_ACTIONS`), Readest's HeaderBar/NavigationBar/
 * ViewMenu layout for the *position* of things.
 *
 * [layout] is still the flat list where `null` marks a group boundary (used only by
 * [menuGroupsFor] now, to decide where the `<hr>` dividers go inside the bottom sheet). Where each
 * [ReaderAction] actually renders -- header start/end, footer, or a menu group -- is decided by
 * its own [ReaderAction.slot], not by its position in this list:
 *   - Header (start): Bookmarks, Highlights (Epub only)
 *   - Header (end): Reading stats
 *   - Footer: Table of contents (Epub only), Màu nền/Tương phản (Epub only), Goto location
 *     ("Tiến độ", opens [ReaderProgressPanel] which reuses Back/Forward), Font size (Epub only),
 *     Read aloud
 *   - Menu, group "Chế độ đọc" (Epub only): Page mode, Scroll mode, Auto scroll
 *   - Menu, group "Sách": Reload, Edit book (stub)
 *   - Menu, group "Hiển thị": Preferences, Profiles (stub)
 *   - Menu, group "Giao diện": Fullscreen/immersive, Light/Dark theme toggle
 *   - Menu, group "Ứng dụng": Print, Inspector (stub), Reset interface (stub)
 *
 * The header's hamburger button and close (X) button are NOT [ReaderAction]s -- they're wired up
 * directly in [ReaderChromeOverlay] ([ReaderMenuSheet] / [ReaderChromeHost.close]) since "open the
 * menu" isn't itself a toolbar action with reader-specific behaviour.
 *
 * NOTE: "Open book", "Inspector" and "Reset interface" were removed from the toolbar (not just
 * hidden behind isRelevantFor()) per spec items 1-2 -- they don't apply to a single-book in-app
 * reader the way they do in calibre desktop's multi-document viewer ("Open book" duplicated the
 * app's own book list; "Inspector"/"Reset interface" have no in-app implementation and never will
 * on this platform; "Print" now exports the current chapter to PDF via the system Print dialog,
 * see [PrintAction]/[ReaderChromeHost.printCurrentChapterAsPdf]). [OpenBookAction]/[PrintAction]/
 * [InspectorAction]/[ResetInterfaceAction] and their [ReaderChromeHost] capabilities are left in
 * place (unused, or -- for Print/Inspector/Reset interface -- moved into the menu ☰ instead of
 * being fully removed, see the mapping table) so a future revision can reinstate/relocate them
 * without rebuilding the plumbing from scratch. [QuitAction] is likewise kept as an unused object
 * (see 4.6 of the spec): the header's close (X) button now calls [ReaderChromeHost.close]
 * directly instead of going through [ReaderAction.invoke].
 *
 * To add a new toolbar button: write a class/object implementing [ReaderAction] (or reuse
 * [PlaceholderReaderAction] for a stub), set its `slot`, then add it here. Nothing else in this
 * package, nor any of the three reader Activities, needs to change -- see [ReaderChromeHost]'s
 * kdoc for the corresponding capability contract if the action needs new reader-specific
 * behaviour.
 */
object ReaderActionRegistry {
    val layout: List<ReaderAction?> = listOf(
        // Kept only so ReaderProgressPanel can call these two by name like ordinary objects --
        // explicitly excluded from menuGroupsFor() below, never rendered from this segment.
        BackAction, ForwardAction, null,
        // Header, left-to-right: Bookmarks -> Highlights (Epub only) -> Search (Epub only).
        BookmarksAction, HighlightsAction, SearchAction, null,
        // Header, right-hand group (before the hamburger/close buttons, which aren't actions).
        ReaderBackgroundMusicAction, null,
        // Footer, left-to-right: Mục lục -> Màu nền/Tương phản -> Tiến độ -> Cỡ chữ -> Đọc to.
        TableOfContentsAction, ColorContrastAction, GotoLocationAction, FontSizeAction, ReadAloudAction, null,
        // Menu ☰, group "Chế độ đọc" (Epub only -- Readest's ViewMenu Paginated/Scrolled/Auto
        // Scroll radio group, see prompt section 3.4). Placed above "Sách" so it's the first
        // thing seen in the menu, closest in spirit to how prominently Readest surfaces it.
        ReadingModePageAction, ReadingModeScrollAction, AutoScrollAction, ReadingStatsAction, null,
        // Menu ☰, group "Sách".
        ReloadBookAction, EditBookAction, null,
        // Menu ☰, group "Hiển thị".
        PreferencesAction, ProfilesAction, null,
        // Menu ☰, group "Giao diện" -- quick Light/Dark switch (prompt section 6.2). Grouped with
        // FullscreenAction (both are now ReaderToggleActions with a checkmark) rather than a
        // separate one-item group, since they're both "hiển thị nhanh" style toggles.
        FullscreenAction, ThemeToggleAction, null,
        // Menu ☰, group "Ứng dụng" -- ResetInterfaceAction is its own trailing segment so it gets
        // a divider directly above it, per spec section 2's "dòng cuối, có <hr> phía trên".
        InspectorAction, null,
        ResetInterfaceAction,
    )

    /** Left-hand icon group of the thin header bar, for [kind]. */
    fun headerStartActionsFor(kind: ReaderKind): List<ReaderAction> =
        layout.filterNotNull().filter { it.slot == ReaderActionSlot.HEADER_START && it.isRelevantFor(kind) }

    /** Right-hand icon group of the thin header bar (before the close button), for [kind]. */
    fun headerEndActionsFor(kind: ReaderKind): List<ReaderAction> =
        layout.filterNotNull().filter { it.slot == ReaderActionSlot.HEADER_END && it.isRelevantFor(kind) }

    /** Up to 5 evenly-spaced icons in the thin footer bar, for [kind]. Deliberately returns only
     * the actions that are actually relevant -- [ReaderChromeOverlay] must build exactly this many
     * items (not 5 slots with some hidden), so `layout_weight` spreads them evenly instead of
     * leaving an ugly gap when e.g. PDF/CBZ only have 2. */
    fun footerActionsFor(kind: ReaderKind): List<ReaderAction> =
        layout.filterNotNull().filter { it.slot == ReaderActionSlot.FOOTER && it.isRelevantFor(kind) }

    /** [layout] grouped by its `null` separators as before, but restricted to [ReaderActionSlot.
     * MENU] entries relevant to [kind] -- used by [ReaderMenuSheet] to draw one `<hr>` divider
     * between each returned group. A group that becomes empty after filtering is dropped so the
     * sheet never renders a blank/divider-only group. Note [BackAction]/[ForwardAction] keep the
     * default MENU slot but are never grouped here (menu display would duplicate what
     * [ReaderProgressPanel]'s Back/Forward buttons already do) -- see their kdoc in
     * ReaderActions.kt. */
    fun menuGroupsFor(kind: ReaderKind): List<List<ReaderAction>> {
        val groups = mutableListOf<List<ReaderAction>>()
        var current = mutableListOf<ReaderAction>()
        for (entry in layout) {
            if (entry == null) {
                if (current.isNotEmpty()) groups.add(current)
                current = mutableListOf()
            } else if (entry.slot == ReaderActionSlot.MENU &&
                entry !== BackAction && entry !== ForwardAction &&
                entry.isRelevantFor(kind)
            ) {
                current.add(entry)
            }
        }
        if (current.isNotEmpty()) groups.add(current)
        return groups
    }
}
