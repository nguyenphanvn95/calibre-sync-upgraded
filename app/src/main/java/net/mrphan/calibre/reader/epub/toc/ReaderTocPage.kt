package net.mrphan.calibre.reader.epub.toc

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

/**
 * Một trang nội dung của panel Mục lục -- tương đương `ReaderSettingsPage` nhưng cho panel TOC
 * (xem `ReaderSettingsSheet` làm khuôn mẫu, prompt mục 1.3). [ReaderTocPanel] gọi [onCreateView]
 * một lần mỗi khi chuyển sang tab này, và forward [onQueryChanged]/[onSortRequested] khi người
 * dùng gõ vào ô search / bấm nút sort ở header.
 */
interface ReaderTocPage {
    fun onCreateView(inflater: LayoutInflater, parent: ViewGroup): View

    /** Gọi mỗi lần text trong thanh search-trong-panel đổi (rỗng khi thanh search bị đóng lại). */
    fun onQueryChanged(query: String)

    /** Gọi khi người dùng bấm nút sort ở header. Trang tự quyết định ý nghĩa của "sort" cho mình
     * (xem prompt mục 5) và tự vẽ lại danh sách nếu cần -- ReaderTocPanel không biết logic sort
     * cụ thể. */
    fun onSortRequested()
}
