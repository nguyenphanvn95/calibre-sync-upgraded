package net.mrphan.calibre.reader.epub

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import net.mrphan.calibre.MetadataDatabaseHelper
import net.mrphan.calibre.R
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/**
 * Bookmark panel opened from [net.mrphan.calibre.reader.chrome.BookmarksAction] (see prompt
 * section 5.2). Lists every "bookmark"-type row of `reader_annotations` for the current book,
 * newest first; tapping a row jumps the navigator there, the trash icon removes it, and the "+"
 * button in the header bookmarks the current reading position.
 *
 * Deliberately EPUB-specific (package `reader.epub`, not `reader.chrome`): matching a bookmark's
 * locator href against `publication.tableOfContents` to show a chapter title only makes sense for
 * a Readium `Locator`, not the page-number bookmarks a future Pdf/Cbz implementation would store
 * in the same table (see MetadataDatabaseHelper#addAnnotation kdoc) -- that would need its own,
 * differently-labelled sheet.
 */
class ReaderBookmarksSheet(
    private val mDB: MetadataDatabaseHelper,
    private val uuid: String,
    private val publication: Publication,
    private val currentLocatorProvider: () -> Locator?,
    private val onJumpTo: (Locator) -> Unit,
    /** Called after a bookmark is added or removed from this sheet, so the host Activity can
     * refresh anything that reflects bookmark state elsewhere (e.g. a future filled/outline
     * bookmark icon in the header -- see BookmarksAction's kdoc in ReaderActions.kt). */
    private val onBookmarksChanged: () -> Unit,
) : BottomSheetDialogFragment() {

    private var listContainer: LinearLayout? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 24)
        }
        val header = inflater.inflate(R.layout.item_reader_annotation_sheet_header, root, false)
        header.findViewById<TextView>(R.id.reader_annotation_sheet_title).setText(R.string.reader_bookmarks_title)
        header.findViewById<ImageButton>(R.id.reader_annotation_sheet_add).setOnClickListener {
            addBookmarkAtCurrentLocation()
        }
        root.addView(header)

        val list = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        listContainer = list
        root.addView(list)
        refreshList()
        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        listContainer = null
    }

    private fun addBookmarkAtCurrentLocation() {
        val locator = currentLocatorProvider() ?: return
        val locatorJson = encodeLocator(locator)
        if (mDB.findBookmarkId(uuid, locatorJson) >= 0) {
            // Already bookmarked exactly here -- nothing to add, just refresh in case the sheet
            // was opened stale.
            refreshList()
            return
        }
        mDB.addAnnotation(uuid, "bookmark", locatorJson, null, null, null)
        onBookmarksChanged()
        refreshList()
    }

    private fun refreshList() {
        val list = listContainer ?: return
        list.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        val cursor = mDB.getAnnotations(uuid, "bookmark")
        val annotations = try {
            ReaderAnnotation.readAll(cursor)
        } finally {
            cursor.close()
        }
        if (annotations.isEmpty()) {
            list.addView(
                ReaderEmptyStateView.inflate(
                    requireContext(),
                    list,
                    R.drawable.ic_empty_bookmark,
                    R.string.reader_bookmarks_empty_title,
                    R.string.reader_bookmarks_empty_body,
                ),
            )
            return
        }
        for (annotation in annotations) {
            val locator = decodeLocator(annotation.locatorJson) ?: continue
            val row = inflater.inflate(R.layout.item_reader_bookmark_entry, list, false)
            row.findViewById<TextView>(R.id.reader_bookmark_entry_label).text = labelFor(locator)
            row.setOnClickListener {
                onJumpTo(locator)
                dismiss()
            }
            row.findViewById<ImageButton>(R.id.reader_bookmark_entry_delete).setOnClickListener {
                mDB.removeAnnotation(annotation.id)
                onBookmarksChanged()
                refreshList()
            }
            list.addView(row)
        }
    }

    /** Chapter title (from the table of contents) if the locator's href matches an entry,
     * otherwise the reading-progress percentage -- same fallback EpubReadingStatsSource uses. */
    private fun labelFor(locator: Locator): String {
        val tocEntry = publication.tableOfContents.firstOrNull { it.href == locator.href }
        val title = tocEntry?.title ?: locator.title
        if (!title.isNullOrBlank()) return title
        val percent = ((locator.locations.totalProgression ?: 0.0) * 100).toInt()
        return getString(R.string.reader_chrome_percent_label, percent)
    }

    companion object {
        const val TAG = "ReaderBookmarksSheet"
    }
}
