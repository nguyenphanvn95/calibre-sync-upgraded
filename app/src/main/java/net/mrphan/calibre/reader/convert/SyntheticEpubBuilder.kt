package net.mrphan.calibre.reader.convert

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Dựng 1 file **EPUB 2 hợp lệ tối giản** (mimetype + META-INF/container.xml + OPF + NCX +
 * XHTML chương + ảnh) mà Readium's `DefaultPublicationParser` chắc chắn nhận diện được -- dùng
 * EPUB2/NCX thay vì EPUB3/nav vì cấu trúc đơn giản hơn, ít điểm có thể sai hơn, và Readium hỗ trợ
 * tốt cả 2.
 *
 * Điểm dễ sai nhất khi tự build file zip EPUB bằng tay: entry `mimetype` PHẢI là entry ĐẦU
 * TIÊN trong file zip, PHẢI dùng phương thức `STORED` (không nén), và PHẢI set đúng `crc`/`size`
 * thủ công (bắt buộc với `STORED` trong `java.util.zip.ZipOutputStream`, khác với `DEFLATED` là tự
 * tính). Thiếu 1 trong 3 điều kiện này khiến nhiều epub reader (kể cả 1 số version Readium) coi file
 * là zip hỏng hoặc không nhận ra là EPUB.
 */
object SyntheticEpubBuilder {

    /** Ghi [book] thành 1 file .epub hợp lệ tại [outputFile]. Ghi đè nếu đã tồn tại (dùng cho
     * cache -- xem EbookFormatConverter). Ném [EbookConversionException.OutputWriteFailed] cho
     * MỌI lỗi I/O, để tầng gọi phân biệt được với lỗi parse (đã xảy ra trước đó, không liên quan
     * tới việc GHI file). */
    fun build(book: ParsedBook, outputFile: File) {
        try {
            outputFile.parentFile?.mkdirs()
            ZipOutputStream(outputFile.outputStream()).use { zip ->
                writeMimetypeStored(zip)
                writeEntry(zip, "META-INF/container.xml", CONTAINER_XML)
                writeEntry(zip, "OEBPS/content.opf", buildOpf(book))
                writeEntry(zip, "OEBPS/toc.ncx", buildNcx(book))
                book.chapterHtmlBlocks.forEachIndexed { index, html ->
                    writeEntry(zip, "OEBPS/chapter_${"%04d".format(index)}.xhtml", wrapXhtml(html))
                }
                book.images.forEachIndexed { index, imgBytes ->
                    writeBinaryEntry(zip, "OEBPS/images/$index.jpg", imgBytes)
                }
            }
        } catch (e: EbookConversionException) {
            throw e
        } catch (e: Exception) {
            outputFile.delete() // khong de lai 1 file .epub nua vo/hong trong cache
            throw EbookConversionException.OutputWriteFailed(e)
        }
    }

    private fun writeMimetypeStored(zip: ZipOutputStream) {
        val content = "application/epub+zip".toByteArray(Charsets.US_ASCII)
        val entry = ZipEntry("mimetype").apply {
            method = ZipEntry.STORED
            size = content.size.toLong()
            compressedSize = content.size.toLong()
            crc = CRC32().apply { update(content) }.value
        }
        zip.putNextEntry(entry)
        zip.write(content)
        zip.closeEntry()
    }

    private fun writeEntry(zip: ZipOutputStream, path: String, content: String) =
        writeBinaryEntry(zip, path, content.toByteArray(Charsets.UTF_8))

    private fun writeBinaryEntry(zip: ZipOutputStream, path: String, content: ByteArray) {
        zip.putNextEntry(ZipEntry(path)) // DEFLATED mac dinh, ZipOutputStream tu tinh crc/size
        zip.write(content)
        zip.closeEntry()
    }

    private fun wrapXhtml(bodyHtml: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE html>
        <html xmlns="http://www.w3.org/1999/xhtml">
        <head><meta charset="utf-8"/><title></title></head>
        <body>$bodyHtml</body>
        </html>
    """.trimIndent()

    private fun buildOpf(book: ParsedBook): String {
        val manifestItems = StringBuilder()
        val spineItems = StringBuilder()
        book.chapterHtmlBlocks.indices.forEach { i ->
            val id = "chap${"%04d".format(i)}"
            val file = "chapter_${"%04d".format(i)}.xhtml"
            manifestItems.append("<item id=\"$id\" href=\"$file\" media-type=\"application/xhtml+xml\"/>\n")
            spineItems.append("<itemref idref=\"$id\"/>\n")
        }
        book.images.indices.forEach { i ->
            manifestItems.append("<item id=\"img$i\" href=\"images/$i.jpg\" media-type=\"image/jpeg\"/>\n")
        }
        val coverMeta = book.coverImageIndex?.let { "<meta name=\"cover\" content=\"img$it\"/>" } ?: ""
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>${escapeXml(book.title)}</dc:title>
                <dc:language>vi</dc:language>
                <dc:identifier id="bookid">urn:uuid:converted-${book.title.hashCode()}</dc:identifier>
                $coverMeta
              </metadata>
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                $manifestItems
              </manifest>
              <spine toc="ncx">
                $spineItems
              </spine>
            </package>
        """.trimIndent()
    }

    private fun buildNcx(book: ParsedBook): String {
        val navPoints = StringBuilder()
        book.chapterHtmlBlocks.indices.forEach { i ->
            navPoints.append(
                """<navPoint id="navpoint-$i" playOrder="${i + 1}">
                     <navLabel><text>${escapeXml("Chương ${i + 1}")}</text></navLabel>
                     <content src="chapter_${"%04d".format(i)}.xhtml"/>
                   </navPoint>"""
            )
        }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <head>
                <meta name="dtb:uid" content="urn:uuid:converted-${book.title.hashCode()}"/>
              </head>
              <docTitle><text>${escapeXml(book.title)}</text></docTitle>
              <navMap>$navPoints</navMap>
            </ncx>
        """.trimIndent()
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // Chu y: khong dung "--" (2 dau gach ngang lien tiep) ben trong bat ky comment XML/HTML nao
    // duoc sinh o day hoac trong cac file .xml khac cua project -- XML spec cam "--" xuat hien
    // giua "<!--" va "-->". Cac chuoi template o tren KHONG chua comment XML nao ca nen an toan.
    private val CONTAINER_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
    """.trimIndent()
}
