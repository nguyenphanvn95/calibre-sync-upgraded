package net.mrphan.calibre.reader.epub

/**
 * Quy doi href tuong doi (vi du src="../images/cover.jpg" trong mot the <img>) ve href tuyet doi
 * so voi goc EPUB, dung chung boi ChapterHtmlBuilder (in PDF) va ReaderImageTapWatcher (Image
 * Tool). Tach ra tu ChapterHtmlBuilder.kt de khong bi hai ban logic lech nhau.
 */
object HrefUtils {
    fun resolveRelativeHref(baseHref: String, relative: String): String {
        if (relative.startsWith("/")) return relative
        val baseDir = baseHref.substringBeforeLast('/', "")
        return if (baseDir.isEmpty()) relative else normalizePath("$baseDir/$relative")
    }

    /** Giai quyet "../" va "./" trong duong dan tuong doi. */
    fun normalizePath(path: String): String {
        val segments = path.split("/").toMutableList()
        var i = 0
        while (i < segments.size) {
            when (segments[i]) {
                "." -> segments.removeAt(i)
                ".." -> {
                    if (i > 0) {
                        segments.removeAt(i)
                        segments.removeAt(i - 1)
                        i--
                    } else {
                        segments.removeAt(i)
                    }
                }
                else -> i++
            }
        }
        return segments.joinToString("/")
    }
}
