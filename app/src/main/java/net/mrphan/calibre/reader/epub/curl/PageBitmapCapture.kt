package net.mrphan.calibre.reader.epub.curl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import net.mrphan.calibre.Logger
import org.readium.r2.navigator.epub.EpubNavigatorFragment

/**
 * Snapshot 1-lan-cho-moi-gesture cua 1 trang (View goc cua fragment con Readium, chua WebView
 * ben trong) ra Bitmap. Chi goi luc bat dau mot gesture keo/animate, KHONG duoc goi lai moi
 * ACTION_MOVE - xem PageCurlOverlayView.
 *
 * QUAN TRONG: ban truoc cua file nay gia dinh ViewPager noi bo cua EpubNavigatorFragment la
 * ViewPager2 (dua tren RecyclerView), nen doc trang qua
 * `(pager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(position)`. Da xac
 * minh bang cach doc source Readium Kotlin Toolkit 3.1.2 rang dieu do SAI: pager thuc te la
 * ViewPager "v1" (androidx.viewpager.widget.ViewPager, xem findInnerViewPager trong
 * EpubReaderActivity.kt) dua tren PagerAdapter/Fragment, KHONG co RecyclerView/ViewHolder ben
 * trong - nen cach doc cu LUON tra ve null, khien nextBitmap luon null va hieu ung curl khong
 * bao gio chay duoc.
 *
 * Cach doc dung: adapter noi bo cua Readium (R2PagerAdapter, `internal class` nen app khong
 * tham chieu ten class duoc) la mot FragmentPagerAdapter chuan gan moi trang nhu 1
 * childFragment cua EpubNavigatorFragment, voi tag dang "f<vi tri>" (xem
 * R2FragmentPagerAdapter.instantiateItem() trong readium-navigator: tag = getItemId(position),
 * getItemId() mac dinh tra ve chinh position). Do do co the tim dung Fragment cua 1 vi tri qua
 * `nav.childFragmentManager.findFragmentByTag("f$position")` (API cong khai cua
 * androidx.fragment.app.Fragment/FragmentManager), roi lay `.view` cua no ra ve Bitmap. Day la
 * chi tiet cai dat noi bo cua Readium, khong duoc bao dam on dinh giua cac phien ban - neu sau
 * nay nang cap Readium ma hieu ung curl lai "im lang khong chay" (khong crash, chi
 * captureCurrentBitmap()/captureNeighbourBitmap() luon null), day la noi dau tien can kiem tra
 * lai (vd Readium doi tien to tag, hoac doi hang sang FragmentStateAdapter/ViewPager2 that).
 */
object PageBitmapCapture {

    /**
     * @param nav EpubNavigatorFragment dang gan cho ReaderActivity (nav.childFragmentManager la
     *   noi Readium gan cac fragment-trang con vao).
     * @param position vi tri adapter can chup (pager.currentItem, hoac +/-1 cho trang ke).
     * @return null neu trang chua duoc attach/render - caller phai coi day la "khong co trang
     *   ke huong nay", KHONG duoc retry hay ep tao fragment moi bang reflection.
     */
    fun capture(
        nav: EpubNavigatorFragment,
        position: Int,
        drawBackground: ((Canvas, Int, Int) -> Unit)? = null,
    ): Bitmap? {
        if (position < 0) {
            Logger.d("PageBitmapCapture: position=$position < 0, dau sach, khong co trang de chup")
            return null
        }
        val fragment = nav.childFragmentManager.findFragmentByTag("f$position") ?: run {
            Logger.d("PageBitmapCapture: khong tim thay fragment-trang cho position=$position (tag=\"f$position\") - chua duoc Readium instantiate (offscreenPageLimit chua kip preload), hoac dau/cuoi sach")
            return null
        }
        val pageView: View = fragment.view ?: run {
            Logger.d("PageBitmapCapture: fragment position=$position chua co view (chua onCreateView)")
            return null
        }
        if (pageView.width <= 0 || pageView.height <= 0) {
            Logger.d("PageBitmapCapture: position=$position co view nhung chua duoc do kich thuoc (w=${pageView.width}, h=${pageView.height})")
            return null
        }

        return try {
            val bitmap = Bitmap.createBitmap(pageView.width, pageView.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawBackground?.invoke(canvas, pageView.width, pageView.height)
            pageView.draw(canvas)
            bitmap
        } catch (e: Exception) {
            // Neu day la nguyen nhan lien tuc (bitmap trang/den tren mot so thiet bi), day la
            // luc them fallback PixelCopy.request() (API 26+) - xem kdoc file nay o repo prompt
            // goc, muc 4. Chua can lam truoc cho phuc tap them.
            Logger.d("PageBitmapCapture: draw(canvas) that bai - ${e.message}")
            null
        }
    }

    /**
     * Chup viewport ke tiep/truoc do ngay trong cung WebView dang hien thi.
     *
     * Readium dung ViewPager o tang spine/resource, con moi "trang" trong che do paginated thuc
     * chat la viewport ngang cua WebView hien tai. Vi vay neu chup `pager.currentItem + 1` thi
     * overlay se lay nham resource/chuong sau, khong phai trang ke tiep ma nguoi doc se thay khi
     * goForward(). Ham nay scroll WebView tam thoi mot man hinh, ve bitmap, roi tra scroll ve
     * dung vi tri cu trong cung mot frame touch event.
     */
    fun captureShiftedViewport(
        nav: EpubNavigatorFragment,
        position: Int,
        delta: Int,
        drawBackground: ((Canvas, Int, Int) -> Unit)? = null,
    ): Bitmap? {
        if (position < 0) {
            Logger.d("PageBitmapCapture: shifted position=$position < 0, dau sach, khong co viewport de chup")
            return null
        }
        val fragment = nav.childFragmentManager.findFragmentByTag("f$position") ?: run {
            Logger.d("PageBitmapCapture: shifted khong tim thay fragment hien tai position=$position")
            return null
        }
        val pageView: View = fragment.view ?: run {
            Logger.d("PageBitmapCapture: shifted fragment position=$position chua co view")
            return null
        }
        if (pageView.width <= 0 || pageView.height <= 0) {
            Logger.d("PageBitmapCapture: shifted view chua do kich thuoc (w=${pageView.width}, h=${pageView.height})")
            return null
        }

        val webView = findWebView(pageView) ?: run {
            Logger.d("PageBitmapCapture: shifted khong tim thay WebView trong fragment position=$position")
            return null
        }
        val oldX = webView.scrollX
        val oldY = webView.scrollY
        val pageStep = (webView.width.takeIf { it > 0 } ?: pageView.width).coerceAtLeast(1)
        val targetX = oldX + delta * pageStep
        if (!webView.canScrollHorizontally(delta)) {
            Logger.d("PageBitmapCapture: shifted WebView khong scroll duoc theo delta=$delta tai scrollX=$oldX")
            return null
        }

        return try {
            webView.scrollTo(targetX, oldY)
            if (webView.scrollX == oldX) {
                Logger.d("PageBitmapCapture: shifted scrollTo khong doi scrollX (old=$oldX, target=$targetX)")
                return null
            }
            val bitmap = Bitmap.createBitmap(pageView.width, pageView.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawBackground?.invoke(canvas, pageView.width, pageView.height)
            pageView.draw(canvas)
            bitmap
        } catch (e: Exception) {
            Logger.d("PageBitmapCapture: shifted draw(canvas) that bai - ${e.message}")
            null
        } finally {
            webView.scrollTo(oldX, oldY)
        }
    }

    private fun findWebView(view: View?): WebView? {
        return when (view) {
            null -> null
            is WebView -> view
            is ViewGroup -> {
                for (i in 0 until view.childCount) {
                    findWebView(view.getChildAt(i))?.let { return it }
                }
                null
            }
            else -> null
        }
    }
}
