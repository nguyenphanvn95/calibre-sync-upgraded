package net.mrphan.calibre.reader.chrome

/**
 * Where a given [ReaderAction] renders in the new thin header + thin footer + hamburger-menu
 * chrome (replacing the old full-screen button grid), modeled on Readest's HeaderBar/NavigationBar/
 * ViewMenu layout. See the mapping table in `prompt-nang-cap-reader-toolbar.md` section 2 for which
 * action belongs to which slot -- don't guess, follow that table.
 */
enum class ReaderActionSlot {
    /** Left-hand icon group in the thin header bar. */
    HEADER_START,

    /** Right-hand icon group in the thin header bar (before the close button). */
    HEADER_END,

    /** One of the (up to) 5 evenly-spaced icons in the thin footer bar. Unlike [HEADER_START]/
     * [HEADER_END]/[MENU], footer entries don't necessarily call [ReaderAction.invoke] directly --
     * see [ReaderChromeOverlay]'s footer-building code for the "Tiến độ" icon's special case,
     * which opens a slide-up panel instead. */
    FOOTER,

    /** A row inside the hamburger-menu bottom sheet ([ReaderMenuSheet]), grouped by the `null`
     * separators already present in [ReaderActionRegistry.layout]. Default slot for any
     * [ReaderAction] that doesn't explicitly set one, so adding a new action without touching this
     * file still lands it somewhere visible. */
    MENU,
}
