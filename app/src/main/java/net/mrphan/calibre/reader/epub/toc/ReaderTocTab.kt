package net.mrphan.calibre.reader.epub.toc

import net.mrphan.calibre.R

/**
 * 3 tab của panel Mục lục (xem [ReaderTocPanel] và prompt-nang-cap-muc-luc-panel-truot.md mục 2).
 * Thứ tự enum = thứ tự tab trái sang phải, đúng yêu cầu "Chương, Đánh dấu trang, Ảnh trong sách".
 */
enum class ReaderTocTab(val label: Int) {
    CHAPTERS(R.string.reader_toc_tab_chapters),
    BOOKMARKS(R.string.reader_toc_tab_bookmarks),
    IMAGES(R.string.reader_toc_tab_images),
}
