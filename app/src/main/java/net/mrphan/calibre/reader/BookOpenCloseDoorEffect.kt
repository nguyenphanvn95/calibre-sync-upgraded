package net.mrphan.calibre.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.preference.PreferenceManager
import net.mrphan.calibre.CoverCache
import java.io.File

/**
 * Hieu ung "keo rem" (curtain slide) khi MO/DONG 1 cuon sach, dung chung 1 setting bat/tat
 * (`pref_book_open_effect`, key giu nguyen tu ban dau chi co hieu ung mo, de khong mat setting da
 * luu cua user cu).
 *
 * MO (xem [playOpenIfEnabled]): anh bia phu kin man hinh xuat hien ngay lap tuc (khong fade-in),
 * giu tinh khoang 300ms, roi tach lam doi va 2 nua TRUOT thang ra 2 ben (nua trai truot sang trai,
 * nua phai truot sang phai, giong 2 tam rem duoc keo mo ra 2 ben) cho den khi ra het khoi man
 * hinh, de lo trang doc that o duoi.
 *
 * DONG (xem [playCloseIfEnabled]): chay NGUOC lai hieu ung mo -- xuat phat tu trang thai dang doc
 * binh thuong, 2 nua anh bia TU 2 MEP MAN HINH truot CHAY VAO GIUA de ghep lai thanh anh bia full
 * man hinh, giu tinh 1 khoang ngan, sau do moi thuc su finish() Activity.
 *
 * Ca 2 chieu deu dung View.translationX (khong rotationY/pivot/cameraDistance kieu "cua doi" 3D),
 * nen chuyen dong phang, muot va nhe, khong can OpenGL/thu vien ngoai.
 *
 * [playOpenIfEnabled] duoc goi tu cuoi onCreate() cua ca 3 reader Activity (Epub/Pdf/Cbz), sau
 * setContentView(). [playCloseIfEnabled] duoc goi tu close() (nut "X" tren chrome overlay / fallback
 * back) cua ca 3 Activity do. Neu setting dang tat hoac khong co anh bia thi ca 2 ham return/goi
 * callback ngay lap tuc, sach van mo/dong binh thuong nhu khi tat hieu ung -- khong duoc gay delay/
 * man hinh trang phat sinh o bat ky chieu nao.
 */
object BookOpenCloseDoorEffect {

    private const val PREF_KEY = "pref_book_open_effect"

    // ---- MO SACH -----------------------------------------------------------------------------

    @JvmStatic
    fun playOpenIfEnabled(activity: Activity, coverFile: File?) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        if (!prefs.getBoolean(PREF_KEY, true)) return
        if (coverFile == null || !coverFile.exists()) return

        val contentRoot = activity.window.decorView
            .findViewById<ViewGroup>(android.R.id.content) ?: return

        // Cover da duoc BookCrawler/CoverManager downsample nho san nen decode dong bo tren main
        // thread la du nhanh -- hieu ung phai hien NGAY frame dau tien, khong co do tre decode/
        // fade-in nao (dung nhu video goc: bia xuat hien tuc thi, khong mo dan).
        val bitmap = decodeCover(activity, coverFile) ?: return

        contentRoot.post {
            runOpenEffect(activity, contentRoot, bitmap)
        }
    }

    private fun runOpenEffect(activity: Activity, contentRoot: ViewGroup, cover: Bitmap) {
        val w = contentRoot.width
        val h = contentRoot.height
        if (w <= 0 || h <= 0) return // layout chua xong, bo qua an toan thay vi crash/chia 0

        val leftDoor = buildHalfImageView(activity, cover, w, h, isLeftHalf = true)
        val rightDoor = buildHalfImageView(activity, cover, w, h, isLeftHalf = false)
        val overlay = buildOverlay(activity, leftDoor, rightDoor)
        contentRoot.addView(overlay)

        // KHONG doc leftDoor.width/rightDoor.width o day: view vua duoc addView() nen chua trai
        // qua 1 lan layout pass, View.getWidth() luc nay luon la 0 -> halfW=0 -> translationX luon
        // = 0 -> 2 nua khong he truot di dau ca. Da biet truoc be rong noi dung (w) tu dau ham nen
        // dung thang gia tri do, khong phu thuoc layout pass nao.
        val halfW = (w / 2).toFloat()

        val holdMs = 300L
        val openMs = 500L
        val curtainInterpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = openMs
            startDelay = holdMs
            interpolator = curtainInterpolator
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                // Truot het chinh no ra khoi man hinh ve dung huong (trai -> am, phai -> duong),
                // di het 1 khoang bang chinh be rong cua no la vua bien mat hoan toan o canh man
                // hinh tuong ung -- khong can fade them, "ra khoi khung hinh" la du sach.
                leftDoor.translationX = -halfW * t
                rightDoor.translationX = halfW * t
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    contentRoot.removeView(overlay)
                }
            })
        }

        // Huy animator neu Activity bi finish/destroy giua chung (xoay man hinh, bam back, TTS mo
        // activity khac...) de khong leak/crash khi update 1 View da bi go.
        observeDestroyToCancel(activity, animator)

        animator.start()
    }

    // ---- DONG SACH -----------------------------------------------------------------------------

    /**
     * Phat hieu ung dong sach roi goi [onFinished] (thuong la `activity::finish`) SAU KHI hieu ung
     * chay xong. Neu setting dang tat, khong co anh bia, hoac layout chua san sang, goi [onFinished]
     * NGAY LAP TUC (dong bo) de khong lam cham viec dong sach trong nhung truong hop do -- day la
     * ly do [onFinished] la 1 [Runnable] thay vi kieu Kotlin function type: ca 3 reader Activity goi
     * ham nay deu can dung tu ca Kotlin (EpubReaderActivity) lan Java (Pdf/CbzReaderActivity), va
     * Runnable la kieu duy nhat SAM-convert tu nhien o ca 2 phia.
     */
    @JvmStatic
    fun playCloseIfEnabled(activity: Activity, coverFile: File?, onFinished: Runnable) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        if (!prefs.getBoolean(PREF_KEY, true)) {
            onFinished.run()
            return
        }
        if (coverFile == null || !coverFile.exists()) {
            onFinished.run()
            return
        }

        val contentRoot = activity.window.decorView
            .findViewById<ViewGroup>(android.R.id.content)
        if (contentRoot == null) {
            onFinished.run()
            return
        }

        val bitmap = decodeCover(activity, coverFile)
        if (bitmap == null) {
            onFinished.run()
            return
        }

        // Khac voi hieu ung mo (contentRoot.post{} vi Activity vua setContentView(), co the chua
        // layout xong), luc dong sach nguoi dung dang xem trang doc that su nen contentRoot chac
        // chan da co kich thuoc -- khong can doi them 1 vong post. Van kiem tra w/h <= 0 phong khi
        // Activity o trang thai bat thuong (vd bi thu hoi memory/dang xoay man hinh) de khong bao
        // gio treo man hinh dong sach.
        val w = contentRoot.width
        val h = contentRoot.height
        if (w <= 0 || h <= 0) {
            onFinished.run()
            return
        }

        runCloseEffect(activity, contentRoot, bitmap, w, h, onFinished)
    }

    private fun runCloseEffect(
        activity: Activity,
        contentRoot: ViewGroup,
        cover: Bitmap,
        w: Int,
        h: Int,
        onFinished: Runnable,
    ) {
        val halfW = (w / 2).toFloat()

        // Dat vi tri BAT DAU da lech han ra ngoai 2 mep man hinh (translationX = +-halfW) NGAY
        // sau khi dung view, truoc khi them vao cay view -- translationX la transform luc render,
        // khong phu thuoc layout pass nen dat som the nay khong gay giat/nhap nhay frame dau.
        val leftDoor = buildHalfImageView(activity, cover, w, h, isLeftHalf = true).apply {
            translationX = -halfW
        }
        val rightDoor = buildHalfImageView(activity, cover, w, h, isLeftHalf = false).apply {
            translationX = halfW
        }
        val overlay = buildOverlay(activity, leftDoor, rightDoor)
        contentRoot.addView(overlay)

        val closeMs = 500L
        val holdAfterMs = 200L // giu tinh sau khi 2 nua ghep lai, de mat kip nhan ra sach da "dong"
        val curtainInterpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)

        // Bao ve khoi goi onFinished 2 lan (co the xay ra vi ca nhanh "chay xong binh thuong" lan
        // nhanh "bi huy giua chung" deu co the dan toi goi finishOnce()).
        var finishedAlready = false
        fun finishOnce() {
            if (finishedAlready) return
            finishedAlready = true
            onFinished.run()
        }

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = closeMs
            interpolator = curtainInterpolator
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                // Nguoc voi hieu ung mo: t=0 dang o ngoai mep (+-halfW), t=1 ve dung giua (0) --
                // ghep lai thanh 1 anh bia full man hinh lien mach.
                leftDoor.translationX = -halfW * (1f - t)
                rightDoor.translationX = halfW * (1f - t)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    contentRoot.postDelayed({
                        contentRoot.removeView(overlay)
                        finishOnce()
                    }, holdAfterMs)
                }

                override fun onAnimationCancel(animation: Animator) {
                    // Bi huy giua chung (vd Activity dang destroy) -- KHONG duoc de finish() bi
                    // "quen" goi, neu khong app se ket kep man hinh dong o trang thai do dang giua
                    // chung mai mai. onAnimationEnd() cung se duoc framework goi ngay sau
                    // onAnimationCancel() nhung finishOnce() da co guard nen an toan du bi goi 2 lan.
                    finishOnce()
                }
            })
        }

        observeDestroyToCancel(activity, animator)

        animator.start()
    }

    // ---- Dung chung ---------------------------------------------------------------------------

    private fun decodeCover(context: Context, coverFile: File): Bitmap? {
        CoverCache.init(context)
        val cacheKey = coverFile.absolutePath
        return CoverCache.getBitmap(cacheKey)
            ?: BitmapFactory.decodeFile(cacheKey)?.also { CoverCache.putBitmap(cacheKey, it) }
    }

    private fun buildOverlay(
        context: Context, leftDoor: ImageView, rightDoor: ImageView
    ): FrameLayout = FrameLayout(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(Color.BLACK) // nen giu khi 2 nua chua ghep/da truot ra xa, tranh ho
        addView(leftDoor)
        addView(rightDoor)
        // Nuot het touch trong luc hieu ung chay, khong cho cham xuyen qua trang doc ben duoi khi
        // dang animate (ca 2 chieu mo/dong).
        isClickable = true
        isFocusable = true
    }

    /** Huy animator neu Activity bi finish/destroy giua chung, de khong leak/crash khi update 1
     * View da bi go khoi cay view. */
    private fun observeDestroyToCancel(activity: Activity, animator: ValueAnimator) {
        if (activity is LifecycleOwner) {
            activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    animator.cancel()
                }
            })
        }
    }

    private fun buildHalfImageView(
        context: Context, cover: Bitmap, screenW: Int, screenH: Int, isLeftHalf: Boolean
    ): ImageView {
        // Center-crop cover de phu kin screenW x screenH (giong cach ImageView scaleType=
        // "centerCrop" tinh toan), sau do cat lay dung nua trai/phai cua vung da crop do -- dam
        // bao khi ghep 2 nua lai dung bang anh center-crop full man hinh, khong lech/ho duong noi
        // o giua.
        val halfW = screenW / 2
        val imageView = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(halfW, screenH).also { lp ->
                lp.gravity = Gravity.TOP or if (isLeftHalf) Gravity.LEFT else Gravity.RIGHT
            }
            scaleType = ImageView.ScaleType.MATRIX
        }
        val matrix = computeCenterCropHalfMatrix(cover, screenW, screenH, isLeftHalf)
        imageView.setImageBitmap(cover)
        imageView.imageMatrix = matrix
        return imageView
    }

    /**
     * Dung android.graphics.Matrix theo dung logic ImageView voi scaleType="centerCrop" tu tinh
     * toan noi bo (xem ImageView.configureBounds() cua AOSP): scale theo ti le
     * max(screenW/coverW, screenH/coverH), dich tam anh da scale trung tam man hinh full-bleed,
     * roi voi isLeftHalf=true dich them theo truc X de vung hien thi trong ImageView (rong halfW,
     * neo Gravity.LEFT) bat dau dung tai x=0 cua vung da crop; isLeftHalf=false dich de vung hien
     * thi (neo Gravity.RIGHT) ket thuc dung tai canh phai cua vung da crop. Vi ImageView cua moi
     * nua duoc dat rong halfW va neo trai/phai bang FrameLayout.LayoutParams.gravity, chi can 1
     * Matrix ap dung cho toan bo bitmap goc (khong crop bitmap thuc su) la du.
     */
    private fun computeCenterCropHalfMatrix(
        cover: Bitmap, screenW: Int, screenH: Int, isLeftHalf: Boolean
    ): Matrix {
        val coverW = cover.width.toFloat()
        val coverH = cover.height.toFloat()
        val scale = maxOf(screenW / coverW, screenH / coverH)
        val scaledW = coverW * scale
        val scaledH = coverH * scale

        // Toa do (trong he quy chieu full-bleed screenW x screenH) cua goc tren-trai anh da
        // scale, sau khi can giua ca chieu ngang lan chieu doc.
        val dxFullBleed = (screenW - scaledW) / 2f
        val dyFullBleed = (screenH - scaledH) / 2f

        val halfW = screenW / 2f
        // ImageView cua nua trai duoc neo Gravity.LEFT (goc tren-trai cua no trung x=0 cua man
        // hinh full-bleed) nen khong can dich them; ImageView cua nua phai duoc neo Gravity.RIGHT
        // (goc tren-trai cua no trung x=halfW cua man hinh full-bleed, vi no chiem nua ben phai)
        // nen phai tru them halfW de Matrix (ve trong he quy chieu rieng cua ImageView do) khop
        // dung voi vi tri da tinh trong he quy chieu full-bleed chung.
        val dx = if (isLeftHalf) dxFullBleed else dxFullBleed - halfW

        val matrix = Matrix()
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dyFullBleed)
        return matrix
    }
}
