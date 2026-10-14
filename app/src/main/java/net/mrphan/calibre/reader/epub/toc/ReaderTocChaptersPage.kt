package net.mrphan.calibre.reader.epub.toc

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import net.mrphan.calibre.R
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication

/**
 * Tab "Chương" (yêu cầu 2.2 của prompt-nang-cap-muc-luc-panel-truot.md): danh sách phẳng toàn bộ
 * `publication.tableOfContents` (kể cả sub-chương lồng nhau, "flatten" giữ nguyên độ thụt lề theo
 * cấp -- xem [flatten]), lọc theo ô search ngay trong panel. Thay thế hoàn toàn `AlertDialog` cũ
 * của `EpubReaderActivity#openTableOfContents()`.
 *
 * [onJumpTo] nhận thẳng [Link] (không phải `Locator`) và forward tới
 * `navigatorFragment?.go(link, animated = true)` -- y hệt cách AlertDialog cũ đã làm, tránh phải tự
 * dựng `Locator` thủ công từ một TOC `Link` (mediaType của các mục TOC không đảm bảo luôn có).
 */
class ReaderTocChaptersPage(
    private val context: Context,
    private val publication: Publication,
    private val onJumpTo: (Link) -> Unit,
) : ReaderTocPage {

    private data class FlatEntry(val link: Link, val title: String, val depth: Int)

    private val allEntries: List<FlatEntry> = flatten(publication.tableOfContents, depth = 0)
    private lateinit var list: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup): View {
        val scroll = ScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        render(allEntries)
        return scroll
    }

    override fun onQueryChanged(query: String) {
        val trimmed = query.trim()
        render(
            if (trimmed.isEmpty()) allEntries
            else allEntries.filter { it.title.contains(trimmed, ignoreCase = true) },
        )
    }

    // "Sort" không áp dụng cho tab Chương: thứ tự đọc (spine order) là thứ tự duy nhất có ý nghĩa
    // -- xem prompt mục 5 (bảng hành vi nút sort theo tab). Không làm gì cả; ReaderTocPanel còn ẩn
    // hẳn nút sort khi ở tab này.
    override fun onSortRequested() = Unit

    private fun render(entries: List<FlatEntry>) {
        list.removeAllViews()
        val inflater = LayoutInflater.from(context)
        if (entries.isEmpty()) {
            val empty = TextView(context).apply {
                text = context.getString(R.string.reader_toc_chapters_empty)
                setPadding(48, 32, 48, 32)
                alpha = 0.6f
            }
            list.addView(empty)
            return
        }
        for (entry in entries) {
            val row = inflater.inflate(R.layout.item_reader_toc_chapter_entry, list, false)
            row.findViewById<TextView>(R.id.reader_toc_chapter_entry_label).apply {
                text = entry.title
                setPadding(paddingLeft + entry.depth * 32, paddingTop, paddingRight, paddingBottom)
            }
            row.setOnClickListener { onJumpTo(entry.link) }
            list.addView(row)
        }
    }

    companion object {
        private fun flatten(links: List<Link>, depth: Int): List<FlatEntry> =
            links.flatMap { link ->
                listOf(FlatEntry(link, link.title ?: link.href.toString(), depth)) +
                    flatten(link.children, depth + 1)
            }
    }
}
