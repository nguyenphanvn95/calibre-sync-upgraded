package net.mrphan.calibre.reader.epub.toc

import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication

/** Một ảnh bitmap tìm thấy trong tài nguyên (`resources`) của EPUB -- xem [EpubImageScanner]. */
data class EpubImageEntry(val link: Link, val index: Int, val displayName: String)

/**
 * Duyệt `publication.resources` (KHÔNG phải `readingOrder` -- ảnh không phải trang HTML, xem
 * `ChapterHtmlBuilder.kt` dòng dùng `publication.resources.firstOrNull { ... }` để lấy ảnh nhúng
 * trong chương, cùng thuộc tính) và lọc theo `MediaType.isBitmap`, để backing tab "Ảnh trong
 * sách" (yêu cầu 2.4 của prompt-nang-cap-muc-luc-panel-truot.md).
 */
object EpubImageScanner {
    fun scan(publication: Publication): List<EpubImageEntry> =
        publication.resources
            .filter { it.mediaType?.isBitmap == true }
            .mapIndexed { index, link ->
                val name = link.href.toString().substringAfterLast('/')
                EpubImageEntry(link, index, name)
            }
}
