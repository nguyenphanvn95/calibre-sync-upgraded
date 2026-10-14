package net.mrphan.calibre.reader.tts

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.mrphan.calibre.Logger
import net.mrphan.calibre.reader.epub.TtsSentenceHighlighter
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/**
 * Gom toan bo logic "noi" mot [TtsPlaybackService] dang active voi UI cua mot man hinh doc:
 * mini player, tap hop trang thai cho to sang cau dang doc trong noi dung sach. Tach ra tu
 * EpubReaderActivity (xem prompt-nang-cap-reader-tts-2026-07.md muc 5) de giam kich thuoc
 * Activity va co the unit-test logic key-dedup/gating (lastHighlightedSentenceKey,
 * highlightOn/autoFollowOn) doc lap voi Android framework.
 *
 * KHONG so huu TtsController/TtsPlaybackService (Activity/Service van giu vai tro do) -- class
 * nay chi la "day noi" giua state va UI, tuong tu vai tro TtsSentenceHighlighter da co nhung o
 * muc cao hon (goi ca highlighter lan mini player).
 */
class TtsSessionCoordinator(
    private val lifecycleOwner: LifecycleOwner,
    // MOI: nhan lambda thay vi gia tri TtsSettingsStore truc tiep -- ttsSettingsStore ben phia
    // EpubReaderActivity la 1 `lateinit var` duoc gan bat dong bo (sau khi mo xong publication),
    // co the CHUA san sang tai thoi diem TtsSessionCoordinator duoc khoi tao (`by lazy`, kich
    // hoat ngay lan dau bind() duoc goi tu onServiceConnected -- co the xay ra truoc khi
    // publication mo xong). Doi lai truy cap den luc thuc su can (trong applyHighlightIfNeeded,
    // SAU khi da kiem tra sessionActive) giu dung tinh an toan cua code Activity goc, noi
    // ttsSettingsStore chi thuc su duoc doc khi 1 phien doc THAT SU dang active.
    private val getTtsSettingsStore: () -> TtsSettingsStore,
    private val miniPlayerBinder: (TtsPlaybackState) -> Unit,
    private val getNavigatorFragment: () -> EpubNavigatorFragment?,
    private val getPublication: () -> Publication?,
    private val autoFollow: (Locator) -> Unit,
    // BUG FIX (menu chon van ban bien mat sau khi tat doc to): goi dung 1 lan moi khi phien TTS
    // chuyen tu active -> inactive, bat ke nguoi dung dung tu dau (mini player, full player, hay
    // thong bao) -- xem ReaderSelectionToolbar.reinstallWatcher() kdoc de biet ly do can goi lai
    // watcher script o day: applyDecorations() lien tuc trong suot phien doc co the lam hong
    // trang thai watcher da cai truoc do ma khong doi href, nen guard href-only cua
    // onLocatorChanged khong tu phat hien duoc.
    private val onSessionEnded: () -> Unit = {},
) {
    private var miniPlayerCollectJob: Job? = null
    private var highlightCollectJob: Job? = null
    private var lastHighlightedSentenceKey: Pair<String, Int>? = null

    /** Goi 1 lan moi khi TtsPlaybackService (re)connect -- huy job cu truoc khi tao job moi,
     * an toan goi lai nhieu lan (giu dung hanh vi cu cua attachMiniPlayerAndFullPlayer). */
    fun bind(controller: TtsController) {
        miniPlayerCollectJob?.cancel()
        miniPlayerCollectJob = lifecycleOwner.lifecycleScope.launch {
            controller.state.collect { state -> miniPlayerBinder(state) }
        }
        highlightCollectJob?.cancel()
        highlightCollectJob = lifecycleOwner.lifecycleScope.launch {
            controller.state.collect { state -> applyHighlightIfNeeded(state) }
        }
    }

    fun unbind() {
        miniPlayerCollectJob?.cancel()
        highlightCollectJob?.cancel()
    }

    /** Goi khi nguoi dung tat cong tac "To sang cau dang doc" trong Settings, hoac dong han
     * "Doc cho nghe" -- xoa ngay thay vi doi state doi tu nhien (co the mat vai giay). */
    fun clearHighlightNow() {
        lastHighlightedSentenceKey = null
        val nav = getNavigatorFragment() ?: return
        lifecycleOwner.lifecycleScope.launch { TtsSentenceHighlighter.clear(nav) }
    }

    /** Duoc goi moi lan TtsPlaybackState doi (nhieu truong khac ngoai sentenceIndex cung kich
     * hoat collect nay, vd tick tien trinh) -- [lastHighlightedSentenceKey] tranh goi
     * applyDecorations()/go() lap lai vo ich cho CUNG 1 cau.
     *
     * Ngoai to sang (xem TtsSentenceHighlighter), ham nay con tu dong cuon/lat trang toi cau
     * dang doc qua [autoFollow] khi nguoi dung bat co [TtsSettingsStore.getAutoFollowCurrentSentence]
     * -- dung lai chinh Locator vua tinh cho decoration. **Chua build/run-verified**: chua co
     * cach kiem tra tren thiet bi that trong sandbox nay xem viec goi autoFollow lien tuc moi
     * khi doi cau co gay giat/nhay lien tuc kho chiu hay khong -- can kiem tra thuc te va can
     * nhac them debounce/chi goi khi cau moi thuc su nam ngoai trang dang hien thi. */
    private suspend fun applyHighlightIfNeeded(state: TtsPlaybackState) {
        val nav = getNavigatorFragment() ?: return
        val pub = getPublication() ?: return

        val sessionActive = state.isPlaying || state.sentenceCount > 0
        if (!sessionActive) {
            // Phien doc da ket thuc/chua bat dau -- xoa moi highlight con sot lai va reset key de
            // lan bat lai tu dau se ap dung ngay cau dau tien.
            if (lastHighlightedSentenceKey != null) {
                TtsSentenceHighlighter.clear(nav)
                lastHighlightedSentenceKey = null
                // BUG FIX: chi goi 1 lan dung luc chuyen active -> inactive (guard boi dieu kien
                // lastHighlightedSentenceKey != null o tren, khong phai moi lan collect chay khi
                // da inactive san roi) -- xem kdoc onSessionEnded/reinstallWatcher().
                onSessionEnded()
            }
            return
        }

        val key = state.chapterHref to state.sentenceIndex
        if (key == lastHighlightedSentenceKey) return // cung 1 cau, khong lam lai
        lastHighlightedSentenceKey = key

        val highlightOn = getTtsSettingsStore().getHighlightCurrentSentence()
        val autoFollowOn = getTtsSettingsStore().getAutoFollowCurrentSentence()
        if (!highlightOn && !autoFollowOn) {
            TtsSentenceHighlighter.clear(nav)
            return
        }

        val locator = TtsSentenceHighlighter.buildLocator(pub, state)
        if (locator == null) {
            Logger.d("TtsSessionCoordinator: buildLocator() returned null, skipping")
            return
        }
        if (highlightOn) {
            TtsSentenceHighlighter.apply(nav, locator)
        } else {
            TtsSentenceHighlighter.clear(nav)
        }
        if (autoFollowOn) autoFollow(locator)
    }
}
