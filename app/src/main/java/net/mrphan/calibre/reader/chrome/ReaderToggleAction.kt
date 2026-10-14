package net.mrphan.calibre.reader.chrome

/**
 * Extension of [ReaderAction] for actions that have a checked/selected state, so
 * [ReaderMenuSheet] can draw a switch or a radio-style checkmark next to the row instead of a
 * plain tap-and-dismiss line -- see prompt section 2 ("Mo rong framework chrome"). Ordinary
 * actions (e.g. [ReloadBookAction]) don't implement this and [ReaderMenuSheet] draws them exactly
 * as before. This mirrors the checkbox/segmented-control rows in Readest's ViewMenu (see
 * apps/readest-app/src/app/reader/components/ on readest/readest's GitHub), but built on this
 * project's existing [ReaderAction] plugin architecture instead of a separate React component.
 */
interface ReaderToggleAction : ReaderAction {
    /** Current checked state, read directly from the host (e.g. `host.isScrollModeEnabled()`). */
    fun isChecked(host: ReaderChromeHost): Boolean

    /** true if this row is one choice in a mutually-exclusive group (radio, e.g. Page/Scroll
     * mode), false if it's an independent on/off switch (e.g. Auto scroll). [ReaderMenuSheet] uses
     * this only to decide which icon to draw (checkmark vs switch) -- it does not currently
     * un-check sibling rows itself, since each [ReaderToggleAction] already reports its own
     * [isChecked] independently from the host's single source of truth (e.g.
     * `ReadingModePageAction.isChecked` is simply `!host.isScrollModeEnabled()`), so rebuilding the
     * sheet after any row is tapped (see [ReaderMenuSheet.buildContent]) already keeps the whole
     * group visually consistent without extra bookkeeping here. */
    val isExclusiveChoice: Boolean get() = false
}
