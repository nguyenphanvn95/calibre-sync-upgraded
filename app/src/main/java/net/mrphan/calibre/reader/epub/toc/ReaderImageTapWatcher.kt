package net.mrphan.calibre.reader.epub.toc

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.mrphan.calibre.Logger
import net.mrphan.calibre.reader.epub.HrefUtils
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Publication

/** Everything ReaderImageTapWatcher needs from the host Activity, keep tach biet nhu
 * ReaderSelectionActionHost de file nay khong phai biet ve EpubReaderActivity truc tiep. */
interface ReaderImageTapHost {
    fun imageTapScope(): CoroutineScope

    /** Mo Image Tool, bat dau tai entries[startIndex]. entries la TOAN BO anh trong sach, theo
     * dung thu tu EpubImageScanner.scan(). */
    fun openImageTool(entries: List<EpubImageEntry>, startIndex: Int)
}

/**
 * Bat su kien nguoi dung cham vao mot <img> ben trong noi dung chuong EPUB dang hien thi, va mo
 * Image Tool (ReaderImageToolDialog) dung vi tri anh do trong toan bo sach.
 *
 * Cung mot co che voi ReaderSelectionToolbar (xem file do de biet ly do): Readium phuc vu moi
 * resource bang MOT WebView rieng ma app khong so huu truc tiep, nen cach duy nhat de "nghe" su
 * kien DOM ben trong la tiem JavaScript qua EpubNavigatorFragment.Configuration
 * .registerJavascriptInterface(...), roi doc lai qua @JavascriptInterface. Script phai duoc
 * tiem lai moi khi resource hien tai doi (onLocatorChanged), giong het
 * ReaderSelectionToolbar.onLocatorChanged.
 */
class ReaderImageTapWatcher(
    private val host: ReaderImageTapHost,
    private val publication: Publication,
) {

    /** JS goi lai vao day khi mot <img> duoc tap. `srcAttr` la gia tri getAttribute('src') tho
     * (chua resolve), `chapterHref` la href (khong co "#fragment") cua resource dang hien thi
     * luc script duoc tiem -- dung de resolve duong dan tuong doi. */
    val jsBridge: Any = object {
        @JavascriptInterface
        fun onImageTapped(srcAttr: String, chapterHref: String) {
            Logger.d("ImageTapWatcher: onImageTapped src='$srcAttr' chapterHref='$chapterHref'")
            mainHandler.post { handleImageTapped(srcAttr, chapterHref) }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastInjectedHref: String? = null

    /** Goi 1 lan ngay sau khi navigator fragment ton tai, va moi lan locatorHref (href cua
     * resource dang hien thi, da strip "#fragment") thay doi -- giong het
     * ReaderSelectionToolbar.onLocatorChanged, vi moi resource la 1 WebView rieng trong Readium. */
    fun onLocatorChanged(navigator: EpubNavigatorFragment, locatorHref: String) {
        if (locatorHref == lastInjectedHref) return
        lastInjectedHref = locatorHref
        injectWatcherScript(navigator, locatorHref)
    }

    private fun injectWatcherScript(navigator: EpubNavigatorFragment, locatorHref: String) {
        host.imageTapScope().launch {
            try {
                val script = buildWatcherScript(locatorHref)
                val result = navigator.evaluateJavascript(script)
                Logger.d("ImageTapWatcher: evaluateJavascript() returned '$result'")
            } catch (e: Exception) {
                Logger.d("ImageTapWatcher: evaluateJavascript() threw, watcher NOT installed")
                Logger.e(e)
            }
        }
    }

    private fun handleImageTapped(srcAttr: String, chapterHref: String) {
        if (srcAttr.isBlank() || srcAttr.startsWith("data:")) return // anh data-uri: khong co Link tra ve, bo qua
        val resolvedHref = HrefUtils.resolveRelativeHref(chapterHref, srcAttr)
        val allImages = EpubImageScanner.scan(publication)
        // So khop theo phan duoi cua href (bo qua dau "/" dan dau neu co), vi Link.href tu
        // publication.resources co the co/khong co "/" dan dau tuy tung publisher.
        val index = allImages.indexOfFirst {
            val a = it.link.href.toString().removePrefix("/")
            val b = resolvedHref.removePrefix("/")
            a == b || a.endsWith(b) || b.endsWith(a)
        }
        if (index < 0) {
            Logger.d("ImageTapWatcher: no matching Link for resolvedHref='$resolvedHref'")
            return
        }
        host.openImageTool(allImages, index)
    }

    private fun buildWatcherScript(chapterHref: String): String {
        // chapterHref duoc nhung thang vao script duoi dang chuoi JS -- escape dau nhay don de
        // tranh gay cu phap neu href co ky tu dac biet (hiem nhung van phong thu).
        val escapedHref = chapterHref.replace("\\", "\\\\").replace("'", "\\'")
        return """
            (function() {
              if (window.__csyncImgWatcherInstalled) { return 'already-installed'; }
              window.__csyncImgWatcherInstalled = true;
              document.addEventListener('click', function(e) {
                var el = e.target;
                while (el && el.tagName !== 'IMG') { el = el.parentElement; }
                if (!el) { return; }
                var src = el.getAttribute('src') || '';
                if (!src) { return; }
                if (window.$JS_INTERFACE_NAME) {
                  window.$JS_INTERFACE_NAME.onImageTapped(src, '$escapedHref');
                }
              }, true);
              return 'installed';
            })();
        """.trimIndent()
    }

    companion object {
        /** Ten JS goi lai, vi du window.CsyncImageBridge.onImageTapped(...). Truyen cho
         * EpubNavigatorFragment.Configuration.registerJavascriptInterface(...) o EpubReaderActivity. */
        const val JS_INTERFACE_NAME = "CsyncImageBridge"
    }
}
