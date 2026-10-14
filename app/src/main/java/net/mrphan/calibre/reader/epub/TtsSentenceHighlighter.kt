package net.mrphan.calibre.reader.epub

import net.mrphan.calibre.Logger
import net.mrphan.calibre.reader.tts.TtsPlaybackState
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/**
 * To sang cau dang duoc TTS doc ngay trong noi dung sach ("man hinh doc" -- EpubNavigatorFragment
 * hien thi chu that cua sach), dung lai dung co che Decoration/Locator da co san cho highlight/
 * note cua nguoi dung (xem EpubReaderActivity#loadAndApplyHighlights) -- nhom decoration RIENG
 * ([DECORATION_GROUP]) de khong bao gio tron lan hay ghi de highlight that su cua nguoi dung.
 *
 * Cach tinh Locator theo dung cong thuc "progression gan dung + text" da chay duoc trong
 * EpubSearchEngine (dung cho dieu huong nav.go(locator) o tinh nang Tim kiem trong sach) -- xem
 * kdoc cua EpubSearchEngine. `nav.go(locator)` da co tien le chay duoc qua tinh nang Tim kiem,
 * nhung `nav.applyDecorations()` (to sang) voi CUNG loai Locator (chi co progression + text,
 * khong co cssSelector/DOM Range that) CHUA co tien le trong repo nay truoc file nay -- ve mat
 * ly thuyet ca hai deu di qua chung 1 API dinh vi bang text cua Readium, nhung dieu nay
 * **chua duoc kiem tra tren thiet bi/emulator that** (sandbox tao ra file nay khong co Android
 * SDK). Da them `Locator.Text.before`/`after` (doan van ban quanh cau, xem
 * TtsPlaybackState.currentSentenceContextBefore/After va TtsController.contextBefore/After) de
 * Readium khop text chinh xac hon khi cau bi trung lap o nhieu cho trong chuong -- van CAN
 * kiem tra lai tren thiet bi that, xem log tag "TtsHighlight" trong [apply].
 */
object TtsSentenceHighlighter {
    private const val DECORATION_GROUP = "tts_current_sentence"
    private const val DECORATION_ID = "tts-current-sentence"
    // Mau khac han voi cac mau highlight nguoi dung tu chon (vang/xanh la/xanh duong/hong) de
    // khong bi nham la mot highlight da luu -- xanh duong nhat, trong suot ~45%. Viet duoi dang
    // hang so Long roi .toInt() vi gia tri ARGB co bit alpha cao (0x73...) vuot qua Int duong --
    // cung 1 kieu du lieu ma ReaderHighlightColor.argb dang dung (xem ReaderAnnotation.kt).
    private const val HIGHLIGHT_TINT = 0x734FC3F7L

    /** Tra ve null neu thieu du lieu can thiet (chua co offset vi dang idle, khong tim thay Link
     * khop href trong readingOrder, thieu mediaType, ...) -- caller (EpubReaderActivity) phai bo
     * qua highlight lan do thay vi crash. */
    fun buildLocator(publication: Publication, state: TtsPlaybackState): Locator? {
        if (state.currentSentenceCharOffset < 0 || state.chapterTotalTextLength <= 0) return null
        val link: Link = publication.readingOrder.firstOrNull {
            it.href.toString() == state.chapterHref
        } ?: return null
        val mediaType = link.mediaType ?: return null
        val start = state.currentSentenceCharOffset
        val end = (start + state.currentSentenceCharLength).coerceAtMost(state.chapterTotalTextLength)
        if (start >= end) return null
        val progression = (start.toDouble() / state.chapterTotalTextLength).coerceIn(0.0, 1.0)
        return Locator(
            href = link.url(),
            mediaType = mediaType,
            title = link.title,
            locations = Locator.Locations(progression = progression),
            text = Locator.Text(
                highlight = state.currentSentenceText,
                // MOI: cung cap ngu canh de Readium khop dung vi tri khi cau bi trung lap o nhieu
                // cho trong chuong -- rong (khong truyen) neu dang o dau/cuoi chuong, Locator.Text
                // chap nhan before/after == null.
                before = state.currentSentenceContextBefore.takeIf { it.isNotEmpty() },
                after = state.currentSentenceContextAfter.takeIf { it.isNotEmpty() },
            ),
        )
    }

    suspend fun apply(nav: EpubNavigatorFragment, locator: Locator) {
        val decoration = Decoration(
            id = DECORATION_ID,
            locator = locator,
            style = Decoration.Style.Highlight(tint = HIGHLIGHT_TINT.toInt()),
        )
        try {
            nav.applyDecorations(listOf(decoration), DECORATION_GROUP)
            // MOI: log xac nhan de kiem tra tren thiet bi that bang logcat filter "TtsHighlight"
            // -- XOA dong nay sau khi da xac nhan on dinh qua vai lan doc thu thuc te.
            Logger.d(
                "TtsHighlight: applyDecorations OK, href=${locator.href} progression=" +
                    "${locator.locations.progression} text='${locator.text.highlight?.take(20)}'",
            )
        } catch (e: Exception) {
            Logger.d("TtsHighlight: applyDecorations() threw, skipping this sentence")
            Logger.e(e)
        }
    }

    suspend fun clear(nav: EpubNavigatorFragment) {
        try {
            nav.applyDecorations(emptyList(), DECORATION_GROUP)
        } catch (e: Exception) {
            Logger.e(e)
        }
    }
}
