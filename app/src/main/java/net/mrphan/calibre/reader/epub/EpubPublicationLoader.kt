package net.mrphan.calibre.reader.epub

import android.content.Context
import net.mrphan.calibre.OpenBookActivity
import net.mrphan.calibre.reader.convert.EbookFormatConverter
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.protection.ContentProtection
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

/**
 * Bọc lại chuỗi AssetRetriever -> DefaultPublicationParser -> PublicationOpener mà
 * EpubReaderActivity#setUpNavigatorFragmentFactory đang làm inline, để TtsChapterListActivity có
 * thể mở Publication (lấy tableOfContents/readingOrder + EpubTextExtractor) mà không cần dùng
 * EpubNavigatorFragment/giao diện đọc chữ đầy đủ. EpubReaderActivity nên đổi sang gọi hàm này thay
 * vì lặp lại code, tránh 2 nơi cùng mở publication theo 2 cách khác nhau rồi lệch dần theo thời
 * gian.
 */
object EpubPublicationLoader {

    /**
     * [uuid] mới thêm: bắt buộc phải có để EbookFormatConverter cache đúng theo sách -- callers
     * hiện tại (EpubReaderActivity, TtsChapterListActivity) đều đã có sẵn `uuid` trong tay, chỉ
     * cần truyền thêm vào lời gọi. Nếu định dạng file KHÔNG nằm trong
     * EbookFormatConverter.SUPPORTED_EXTENSIONS và cũng không phải "epub", hàm coi như file đã là
     * epub (giữ nguyên hành vi cũ) -- Readium tự trả lỗi qua đường null quen thuộc nếu sai.
     *
     * Mọi exception từ EbookFormatConverter.convert() (các subtype của EbookConversionException)
     * được CHỦ Ý không bắt ở đây -- để lan ra ngoài cho EpubReaderActivity/TtsChapterListActivity
     * tự bắt và hiện đúng thông báo cụ thể cho từng loại lỗi (xem 2 file đó ở mục dưới). Đây là
     * khác biệt duy nhất so với hành vi "trả về null" cũ của hàm này khi Readium tự thất bại.
     */
    suspend fun open(context: Context, bookFile: File, uuid: String): Publication? {
        val extension = OpenBookActivity.getExtension(bookFile.name)
        val effectiveFile = if (extension in EbookFormatConverter.SUPPORTED_EXTENSIONS) {
            EbookFormatConverter.convert(context, bookFile, uuid, extension)
        } else {
            bookFile
        }

        val assetRetriever = AssetRetriever(
            contentResolver = context.contentResolver,
            httpClient = DefaultHttpClient(),
        )
        val publicationParser = DefaultPublicationParser(
            context = context,
            httpClient = DefaultHttpClient(),
            assetRetriever = assetRetriever,
            pdfFactory = null,
        )
        val publicationOpener = PublicationOpener(
            publicationParser = publicationParser,
            contentProtections = emptyList<ContentProtection>(),
        )
        val asset = assetRetriever.retrieve(effectiveFile).getOrElse { return null }
        return publicationOpener.open(asset, allowUserInteraction = false).getOrElse { return null }
    }
}
