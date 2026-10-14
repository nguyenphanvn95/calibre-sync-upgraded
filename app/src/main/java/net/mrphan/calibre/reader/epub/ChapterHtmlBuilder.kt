package net.mrphan.calibre.reader.epub

import android.util.Base64
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.getOrElse

/**
 * Dung 1 chuoi HTML "tu chua" (khong tham chieu file ngoai) cho DUNG 1 resource trong reading
 * order cua EPUB, de dua cho ChapterPdfPrinter render trong 1 WebView offscreen -- Readium's
 * internal resource server chi phuc vu EpubNavigatorFragment, khong co API public de mot WebView
 * khac dung lai, nen phai tu doc bytes va nhung anh truc tiep.
 *
 * Dung lai dung pattern "raw bytes qua Publication#get(Link), khong dung ContentService (con
 * 'experimental')" da co san trong EpubTextExtractor.kt -- cung mot ly do "khong co moi truong
 * build o day de verify API do" da duoc ghi ro tai do. Xu ly anh tuong doi bang regex thay vi
 * them thu vien parse HTML day du (jsoup...), giu dung tinh than "khong bia them dependency"
 * cua EpubTextExtractor.
 */
class ChapterHtmlBuilder(private val publication: Publication) {

    suspend fun buildSelfContainedHtml(chapterLink: Link): String {
        val rawBytes = publication.get(chapterLink)?.read()?.getOrElse { ByteArray(0) } ?: ByteArray(0)
        val rawHtml = String(rawBytes, Charsets.UTF_8)
        val withInlinedImages = inlineImages(rawHtml, chapterLink)
        return wrapWithPrintStyles(withInlinedImages)
    }

    private suspend fun inlineImages(html: String, chapterLink: Link): String {
        var result = html
        for (match in IMG_SRC_REGEX.findAll(html)) {
            val originalSrc = match.groupValues[1]
            if (originalSrc.startsWith("data:")) continue
            val resolvedHref = HrefUtils.resolveRelativeHref(chapterLink.href.toString(), originalSrc)
            val dataUri = loadImageAsDataUri(resolvedHref) ?: continue
            result = result.replace("src=\"$originalSrc\"", "src=\"$dataUri\"")
                .replace("src='$originalSrc'", "src='$dataUri'")
        }
        return result
    }

    private suspend fun loadImageAsDataUri(href: String): String? {
        // CHU Y: publication.readingOrder chi chua cac resource dang doc (spine); anh minh hoa
        // thuong nam trong "resources"/manifest chu khong phai spine. Neu Publication 3.1.2
        // khong co thuoc tinh `.resources` nhu ben duoi, Codex tim ham/thuoc tinh tuong duong
        // (vi du publication.manifest.resources, hoac mot ham `linkWithHref`/`get` nhan href
        // truc tiep khong can Link) -- day la cho DUY NHAT trong file nay co rui ro sai chu ky API.
        val imageLink = publication.readingOrder.firstOrNull { it.href.toString() == href }
            ?: publication.resources.firstOrNull { it.href.toString() == href }
            ?: return null
        val bytes = publication.get(imageLink)?.read()?.getOrElse { null } ?: return null
        val mime = imageLink.mediaType?.toString() ?: guessMimeFromExtension(href)
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:$mime;base64,$encoded"
    }

    private fun guessMimeFromExtension(href: String): String = when {
        href.endsWith(".png", true) -> "image/png"
        href.endsWith(".jpg", true) || href.endsWith(".jpeg", true) -> "image/jpeg"
        href.endsWith(".gif", true) -> "image/gif"
        href.endsWith(".svg", true) -> "image/svg+xml"
        else -> "image/*"
    }

    /** Stylesheet in an rieng, KHONG dung mau nen/chu cua reader (sepia/dark se ton muc khi in
     * ra giay that) -- luon den chu tren nen trang. */
    private fun wrapWithPrintStyles(documentHtml: String): String {
        val headHtml = HEAD_REGEX.find(documentHtml)?.groupValues?.getOrNull(1).orEmpty()
        val bodyHtml = BODY_REGEX.find(documentHtml)?.groupValues?.getOrNull(1) ?: documentHtml
        return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        $headHtml
        <style>
          @page { margin: 16mm; }
          html, body { min-height: 100%; }
          body {
            font-family: serif;
            font-size: 14pt;
            line-height: 1.5;
            color: #000000 !important;
            background: #ffffff !important;
            margin: 0;
          }
          img, svg { max-width: 100%; height: auto; }
          * { box-sizing: border-box; }
        </style>
        </head>
        <body>
        $bodyHtml
        </body>
        </html>
    """.trimIndent()
    }

    companion object {
        private val IMG_SRC_REGEX = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val HEAD_REGEX = Regex("""<head[^>]*>([\s\S]*?)</head>""", RegexOption.IGNORE_CASE)
        private val BODY_REGEX = Regex("""<body[^>]*>([\s\S]*?)</body>""", RegexOption.IGNORE_CASE)
    }
}
