package net.mrphan.calibre.reader.epub

import android.view.View
import androidx.viewpager2.widget.ViewPager2

/**
 * Hieu ung lat trang cho che do doc phan trang (paginated), kieu Readest mobile ("slide" va
 * "page-curl" - xem prompt-nang-cap-hieu-ung-lat-trang.md muc 0.1). Cam vao ViewPager noi
 * bo cua EpubNavigatorFragment (Readium Kotlin Toolkit 3.1.2 khong expose field nay qua API
 * public, VA ban than no la ViewPager "v1" (androidx.viewpager.widget.ViewPager) chu KHONG
 * PHAI ViewPager2 - xem findInnerViewPager trong EpubReaderActivity.kt), ap dung cho ca vuot
 * tay lan goForward()/goBackward() (deu set currentItem tren cung 1 ViewPager).
 *
 * LUU Y: [CurlPageTransformer] o duoi la ban xap xi truc quan cu bang rotationY + cameraDistance
 * + lop phu bong, KHONG phai mo phong vat ly giay cong hinh tru dong theo luc keo ngon tay. Ban
 * curl "that" (cylindrical, bam theo ngon tay, co mat sau + bong do chay theo nep gap) da duoc
 * lam trong package net.mrphan.calibre.reader.epub.curl (PageCurlOverlayView) va la co che THAT
 * SU dang chay cho PageTurnAnimation.CURL - xem EpubReaderActivity#syncCurlOverlay. Class
 * [CurlPageTransformer] duoi day khong con duoc EpubReaderActivity tham chieu toi nua, giu lai
 * trong file chi de tham khao lich su, KHONG sua tiep no.
 */
enum class PageTurnAnimation {
    NONE, SLIDE, CURL;

    companion object {
        fun fromPrefName(name: String?): PageTurnAnimation =
            entries.firstOrNull { it.name == name } ?: NONE
    }
}

/** "Truot" -- trang di ra dich cham hon trang di vao (parallax nhe) kem bong do mo dan o
 * canh trang moi, giong cam giac "slide" cua Readest hon la dich chuyen 1:1 mac dinh cua
 * ViewPager2 (mac dinh trong "phang", it chieu sau). */
class SlidePageTransformer : androidx.viewpager.widget.ViewPager.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        page.translationX = 0f
        page.translationY = 0f
        when {
            position < -1f || position > 1f -> {
                page.alpha = 0f
            }
            position <= 0f -> {
                // Trang sap roi man hinh (ben trai): dich cham hon (x0.7) de tao cam giac
                // trang moi "de" len, kem toi nhe dan nhu bi trang moi phu bong len.
                page.alpha = 1f
                page.translationX = page.width * -position * 0.3f
                page.scaleX = 1f
                page.scaleY = 1f
                page.elevation = 0f
            }
            else -> {
                // Trang moi di vao (tu ben phai): truot binh thuong + bong do tang dan khi
                // con cach xa vi tri cuoi, giam dan ve 0 khi da vao han.
                page.alpha = 1f
                page.translationX = 0f
                page.elevation = (1f - position) * 8f
            }
        }
    }
}

/** "Cong trang" -- xoay trang quanh truc doc gan mep trong (noi "gay sach" ao), kem lop phu
 * bong toi mo phong nep gap khi trang dang xoay qua goc lon. Xem class kdoc o dau file cho
 * gioi han cua cach tiep can nay. */
class CurlPageTransformer : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        page.cameraDistance = 20000f * page.resources.displayMetrics.density
        page.pivotY = page.height / 2f
        when {
            position < -1f || position > 1f -> {
                page.alpha = 0f
                page.rotationY = 0f
            }
            position <= 0f -> {
                // Trang cu "gap" ra sau quanh mep phai (giong lat sang trang ke), giam dan do
                // trong suot khi goc xoay lon de gia lap bong do vao nep gap.
                page.pivotX = page.width.toFloat()
                page.alpha = 1f
                page.rotationY = 90f * -position
            }
            else -> {
                page.pivotX = 0f
                page.alpha = 1f
                page.rotationY = -90f * position
            }
        }
    }
}
