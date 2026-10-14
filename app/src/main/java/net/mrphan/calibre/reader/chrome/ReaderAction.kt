package net.mrphan.calibre.reader.chrome

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import net.mrphan.calibre.R

/**
 * One button on the reader chrome toolbar. This is the "plugin" unit: to add a new toolbar
 * button, implement this interface and add it (plus `null` separators as needed) to
 * [ReaderActionRegistry.layout] -- [ReaderChromeOverlay] never needs to change.
 */
interface ReaderAction {
    /** Stable id, e.g. "back", "edit_book". Not currently used for persistence, but kept stable
     * and unique so a future "customize toolbar" feature (calibre desktop's "Reset interface")
     * has something to key on. */
    val id: String

    @get:DrawableRes
    val icon: Int

    @get:StringRes
    val label: Int

    /** Where this button renders in the header/footer/menu chrome -- see [ReaderActionSlot].
     * Defaults to [ReaderActionSlot.MENU] so an action that doesn't explicitly set one still ends
     * up somewhere visible instead of silently disappearing. This only decides *where* the button
     * renders; it does not replace [isRelevantFor], which still decides *whether* it renders at
     * all for a given [ReaderKind]. */
    val slot: ReaderActionSlot get() = ReaderActionSlot.MENU

    /** Whether this button should be shown at all for [kind]. Actions that don't apply to a
     * format (e.g. "Table of contents" for a CBZ) should return false here so
     * [ReaderChromeOverlay] omits them entirely, rather than showing a button that always fails.
     */
    fun isRelevantFor(kind: ReaderKind): Boolean = true

    /** Called when the button is tapped. Implementations should call the matching
     * [ReaderChromeHost] capability and, if it reports unsupported (returns false), surface that
     * via [ReaderChromeHost.showMessage] -- never fail silently (see spec Definition of Done #3).
     */
    fun invoke(host: ReaderChromeHost)

    /** Optional secondary behaviour on long-press, e.g. [AutoScrollAction] opening a speed
     * picker. Framework-level extension point (like [ReaderToggleAction]) rather than a
     * capability hardcoded into [ReaderMenuSheet] -- most actions have nothing to do here and
     * simply don't override it. @return true if the long-press was handled (row should not also
     * fire its normal tap behaviour), false to let the tap handler run as usual. */
    fun onLongPress(host: ReaderChromeHost): Boolean = false
}

/**
 * Shared implementation for toolbar buttons that don't have a real feature behind them yet
 * ("Đọc cho nghe" / "Hiệu đính" / "Print" at the time of writing -- see README/CHANGELOG). Tapping
 * one just announces that the feature isn't built yet. To turn a placeholder into a real feature
 * later: write a class implementing [ReaderAction] and swap the `val XyzAction = ...` entry in
 * [ReaderActionRegistry.layout] for it -- no other file needs to change.
 */
open class PlaceholderReaderAction(
    override val id: String,
    @DrawableRes override val icon: Int,
    @StringRes override val label: Int,
    override val slot: ReaderActionSlot = ReaderActionSlot.MENU,
) : ReaderAction {
    override fun invoke(host: ReaderChromeHost) {
        val actionName = host.formatString(label)
        host.showMessage(host.formatString(R.string.reader_chrome_coming_soon, actionName))
    }
}
