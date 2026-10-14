package net.mrphan.calibre.reader.epub.toc

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import net.mrphan.calibre.MetadataDatabaseHelper
import net.mrphan.calibre.R
import net.mrphan.calibre.reader.epub.ReaderAnnotation
import net.mrphan.calibre.reader.epub.ReaderEmptyStateView
import net.mrphan.calibre.reader.epub.decodeLocator
import net.mrphan.calibre.reader.epub.encodeLocator
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/**
 * Tab "Đánh dấu trang" (yêu cầu 2.3 của prompt-nang-cap-muc-luc-panel-truot.md). Logic liệt
 * kê/thêm/xoá bookmark được chuyển nguyên vẹn từ `ReaderBookmarksSheet`
 * (`mDB.getAnnotations`/`addAnnotation`/`removeAnnotation`, `ReaderAnnotation`,
 * `encodeLocator`/`decodeLocator`) -- không viết lại từ đầu, chỉ đổi khung chứa UI (không còn tự
 * vẽ header riêng) + thêm filter theo query từ ô search của panel + nút sort đảo chiều mới/cũ.
 */
class ReaderTocBookmarksPage(
    private val context: Context,
    private val scope: CoroutineScope,
    private val mDB: MetadataDatabaseHelper,
    private val uuid: String,
    private val publication: Publication,
    private val currentLocatorProvider: () -> Locator?,
    private val onJumpTo: (Locator) -> Unit,
    private val onBookmarksChanged: () -> Unit,
    private val footerActionSlot: ViewGroup,
) : ReaderTocPage {

    private lateinit var list: LinearLayout
    private var allAnnotations: List<ReaderAnnotation> = emptyList()
    private var query: String = ""
    private var newestFirst = true

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup): View {
        val scroll = ScrollView(context)
        list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)

        // Nút "+" tạo dấu trang -- đặt vào slot dưới footer của panel (yêu cầu 2.3 "có nút tạo
        // dấu trang ở dưới"), thay cho header riêng mà ReaderBookmarksSheet cũ tự vẽ.
        footerActionSlot.removeAllViews()
        val addButton = inflater.inflate(
            R.layout.item_reader_toc_bookmark_add_button,
            footerActionSlot,
            false,
        )
        addButton.setOnClickListener { addBookmarkAtCurrentLocation() }
        footerActionSlot.addView(addButton)

        refreshList()
        return scroll
    }

    override fun onQueryChanged(newQuery: String) {
        query = newQuery
        renderFiltered()
    }

    // "Sort" cho tab Bookmark = đảo chiều mới nhất/cũ nhất (theo created_at) -- xem prompt mục 5.
    override fun onSortRequested() {
        newestFirst = !newestFirst
        renderFiltered()
    }

    private fun addBookmarkAtCurrentLocation() {
        val locator = currentLocatorProvider() ?: return
        val locatorJson = encodeLocator(locator)
        if (mDB.findBookmarkId(uuid, locatorJson) < 0) {
            mDB.addAnnotation(uuid, "bookmark", locatorJson, null, null, null)
            onBookmarksChanged()
        }
        refreshList()
    }

    private fun refreshList() {
        val cursor = mDB.getAnnotations(uuid, "bookmark")
        allAnnotations = try {
            ReaderAnnotation.readAll(cursor)
        } finally {
            cursor.close()
        }
        renderFiltered()
    }

    private fun renderFiltered() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val inflater = LayoutInflater.from(context)
        var items = allAnnotations.sortedBy { it.createdAt }
        if (newestFirst) items = items.asReversed()
        val decoded = items.mapNotNull { ann -> decodeLocator(ann.locatorJson)?.let { ann to it } }
        val filtered = if (query.isBlank()) {
            decoded
        } else {
            decoded.filter { labelFor(it.second).contains(query, ignoreCase = true) }
        }
        if (filtered.isEmpty()) {
            list.addView(
                ReaderEmptyStateView.inflate(
                    context,
                    list,
                    R.drawable.ic_empty_bookmark,
                    R.string.reader_bookmarks_empty_title,
                    R.string.reader_bookmarks_empty_body,
                ),
            )
            return
        }
        for ((annotation, locator) in filtered) {
            val row = inflater.inflate(R.layout.item_reader_bookmark_entry, list, false)
            row.findViewById<TextView>(R.id.reader_bookmark_entry_label).text = labelFor(locator)
            row.setOnClickListener { onJumpTo(locator) }
            row.findViewById<ImageButton>(R.id.reader_bookmark_entry_delete).setOnClickListener {
                mDB.removeAnnotation(annotation.id)
                onBookmarksChanged()
                refreshList()
            }
            list.addView(row)
        }
    }

    /** Chapter title (từ table of contents) nếu href của locator khớp một mục, nếu không thì
     * phần trăm tiến độ đọc -- cùng cách fallback mà `EpubReadingStatsSource` đã dùng. */
    private fun labelFor(locator: Locator): String {
        val tocEntry = publication.tableOfContents.firstOrNull { it.href == locator.href }
        val title = tocEntry?.title ?: locator.title
        if (!title.isNullOrBlank()) return title
        val percent = ((locator.locations.totalProgression ?: 0.0) * 100).toInt()
        return context.getString(R.string.reader_chrome_percent_label, percent)
    }
}
