package net.mrphan.calibre.reader.epub.curl

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import net.mrphan.calibre.Logger

/**
 * "Cong trang" that (Play Books / Moon Reader style): custom View tu ve bang Canvas/Path, bam
 * theo vi tri ngon tay (ca X lan Y - xem [touchYFraction]), thay the cho CurlPageTransformer cu
 * (xoay rotationY phang, chi la xap xi - xem kdoc dau file PageTurnTransformers.kt). Chi 1 View
 * (khong phai ViewGroup), duoc EpubReaderActivity gan/go lam sibling cua ViewPager noi bo cua
 * Readium khi pageTurnAnimation doi giua NONE/SLIDE/CURL - xem EpubReaderActivity#syncCurlOverlay.
 *
 * Trang duoc chia thanh nhieu "dong luoi" (rows) chong theo chieu doc, MOI dong tu tinh mot duong
 * gap/ban kinh RIENG (PageCurlMath.rowLocalDragFraction: dong trung ngon tay cuon het muc, cang xa
 * ngon tay theo Y cang cuon it) - xep canh nhau, cac dong nay tao ra duong VIEN CONG, thon dan ve
 * goc trang giong hinh chup Moon Reader (con neu keo gan nhu thang dung thi chi can 1 dong, re nhu
 * ban cu - xem PageCurlMath.rowCountFor), gan voi mot "cuon giay tu 1 goc" that (kieu
 * https://github.com/eschao/android-PageFlip) hon la 1 duong gap thang/cheo don gian cho ca trang.
 * Moi o luoi (dong x cot) gio la 1 HINH CHU NHAT (khong con can Matrix.setPolyToPoly/clipPath nhu
 * ban truoc) ve bang canvas.drawBitmap(src,dst) - vua re hon, vua khong con de lai khe ho AA mong
 * giua cac o (nguyen nhan cua nhung "vet den" chay doc trong ban cu) vi cac hinh chu nhat lien ke
 * duoc "no" chong nhe len nhau 1 chut thay vi khop khit tuyet doi - xem drawCurlBandForRow.
 *
 * Overlay nay KHONG can biet gi ve ReaderChromeOverlay (chrome toolbar an/hien): viec do da duoc
 * xu ly doc lap o EpubReaderActivity#dispatchTouchEvent (goi chromeOverlay.dispatchContentTouch
 * cho MOI su kien truoc khi di xuong cay View), nen tap-de-an/hien-chrome van hoat dong dung du
 * overlay nay co tieu thu (consume) su kien cho cu keo curl hay khong.
 */
class PageCurlOverlayView(context: Context) : View(context) {

    /** Cac callback ve phia host (EpubReaderActivity) - co tinh toi thieu, chi de
     * EpubReaderActivity la noi duy nhat goi API that cua Readium (findInnerViewPager,
     * goForward/goBack). */
    interface Host {
        /** True chi khi pageTurnAnimation == CURL VA dang o paginated mode (khong phai
         * scroll mode). */
        fun isCurlEnabled(): Boolean

        fun captureCurrentBitmap(): Bitmap?

        /** Bitmap trang ke, delta = +1 (trang sau) hoac -1 (trang truoc). Null = khong co
         * trang ke huong do (dau/cuoi sach, hoac chua attach). */
        fun captureNeighbourBitmap(delta: Int): Bitmap?

        /** Ve nen reader hien tai phia duoi bitmap trang/overlay curl. Host dung cung logic voi
         * native reader background de vung status/nav/header/footer va trang ke trong luc lat
         * khong bi roi ve mau den mac dinh cua Readium/ViewPager. */
        fun drawReaderBackground(canvas: Canvas, width: Int, height: Int) = Unit

        /** Curl da chay het toi canh - phai goi API that (goForward()/goBack()) roi moi an
         * overlay, dam bao tien do doc / highlight / TTS dong bo dung nhu lat trang thuong. */
        fun onCurlCommitted(forward: Boolean)
    }

    var host: Host? = null

    private var currentBitmap: Bitmap? = null
    private var nextBitmap: Bitmap? = null
    private var draggingForward = true
    private var dragX = 0f
    private var isDragging = false

    /** Vi tri Y cua ngon tay tren trang, quy ve [0,1] - xem kdoc PageCurlMath.compute. Cap nhat
     * lien tuc trong ACTION_MOVE de duong gap "bam" theo ngon tay ca chieu doc, khong chi chieu
     * ngang nhu ban truoc. 0.5f (giua) khi curl chay tu dong (bam nut, khong co ngon tay that). */
    private var touchYFraction = 0.5f

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var velocityTracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f

    private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** "Mat sau" trang - khong co art rieng cho mat sau giay, nen xap xi re bang cach lay mieng
     * bitmap mat truoc, lat guong + giam saturation + toi di mot chut. Day la ky thuat chap nhan
     * duoc, giong cach cac lib page-curl ma nguon mo hay lam khi khong co texture rieng cho mat
     * sau. */
    private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix().apply {
                setSaturation(0.25f)
                // Toi nhe mat sau (giay khong tu phat sang, chi phan chieu anh sang yeu hon mat
                // truoc in chu) bang cach nhan them 1 ma tran scale do sang.
                val darken = ColorMatrix(
                    floatArrayOf(
                        0.82f, 0f, 0f, 0f, 0f,
                        0f, 0.82f, 0f, 0f, 0f,
                        0f, 0f, 0.82f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                )
                postConcat(darken)
            },
        )
    }

    /** Bong do dang mang (khong phai chu nhat phang mau den nhu ban truoc) phu len tung dai cuon
     * - dam nhat theo goc cuon de mo phong anh sang bi khuat dan khi be mat cong ra xa nguon sang.
     * Mau/alpha cua shader duoc cap nhat lai moi lan ve (setShader), Paint duoc tai su dung de do
     * khong cap phat lai object Paint. */
    private val stripShadingPaint = Paint()

    /** Bong do mem do dai cuon "do bong" xuong trang duoi no (khac voi bong tren tung dai, day la
     * bong tren chinh trang ke tiep, ngay canh duong ngoai cung cua cuon giay) - tao cam giac
     * trang dang nhac len khoi mat giay ben duoi thay vi dinh chat vao no. */
    private val castShadowPaint = Paint()

    /** Vet sang mo (highlight) doc theo dinh cua o cuon, noi be mat giay cong nhat va bat sang
     * nhieu nhat - chi tiet nho nhung quan trong de mat nguoi cam nhan duoc "do tron" 3 chieu cua
     * cuon giay thay vi mot dai phang doi mau. */
    private val highlightPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }

    /** Vet toi mong ngay tai duong gap, ve de len canh cua vung phang - noi lien thi giac giua
     * phan phang va dai cuon (khong co no, duong gap trong "cat cut" qua dot ngot). */
    private val creaseShadowPaint = Paint()

    private var flipAnimator: ValueAnimator? = null

    /** Duong vien cua vung con phang cua trang hien tai - MOT path duy nhat noi curlOuterX cua
     * TUNG DONG luoi lai voi nhau (xem [drawFlatRegion]), tai su dung moi lan ve de khong cap phat
     * lai object Path. */
    private val flatRegionPath = Path()

    /** Rect nguon/dich tai su dung cho tung o luoi (dong x cot) trong [drawCurlBandForRow] - moi
     * o gio la 1 hinh chu nhat don gian (khong con tu giac nghieng nhu ban truoc, vi do nghieng
     * gio la do CHENH LECH giua cac dong chu khong phai trong noi bo 1 dong), nen drawBitmap(src,
     * dst) la du, khong can Matrix/clipPath nhu ban truoc. */
    private val reusableSrcRect = Rect()
    private val reusableDstRect = RectF()
    private var curlMeshVerts = FloatArray(0)
    private var curlMeshColors = IntArray(0)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (host?.isCurlEnabled() != true) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                isDragging = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val dx = event.x - downX
                val dy = event.y - downY
                if (!isDragging) {
                    if (abs(dx) < touchSlop || abs(dx) < abs(dy)) {
                        return true
                    }
                    // Huong cua cu keo dau tien vuot touch slop quyet dinh ca gesture.
                    isDragging = true
                    draggingForward = dx < 0f
                    currentBitmap = host?.captureCurrentBitmap()
                    nextBitmap = host?.captureNeighbourBitmap(if (draggingForward) 1 else -1)
                    if (nextBitmap == null) {
                        Logger.d("PageCurlOverlayView: bo qua gesture - khong lay duoc bitmap trang ke (forward=$draggingForward). Xem log PageBitmapCapture o tren de biet ly do cu the.")
                        isDragging = false // dau/cuoi sach, khong co trang de cuon toi
                        return true
                    }
                }
                dragX = (if (draggingForward) -dx else dx).coerceIn(0f, width.toFloat())
                if (height > 0) {
                    touchYFraction = (event.y / height).coerceIn(0f, 1f)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) {
                    releaseVelocityTracker()
                    return true
                }
                val shouldCommit = velocityTracker?.let { tracker ->
                    tracker.computeCurrentVelocity(1000)
                    val vx = abs(tracker.xVelocity)
                    val pastThreshold = dragX > width * FLIP_THRESHOLD_FRACTION
                    val isFling = vx > ViewConfiguration.get(context).scaledMinimumFlingVelocity
                    pastThreshold || isFling
                } ?: (dragX > width * FLIP_THRESHOLD_FRACTION)
                animateTo(commit = shouldCommit)
                releaseVelocityTracker()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun releaseVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    /** Dua overlay ve trang thai "sach" NGAY LAP TUC (khong hoat hinh) - huy flipAnimator dang
     * chay (neu co), xoa currentBitmap/nextBitmap, dua dragX/touchYFraction ve mac dinh, roi
     * invalidate. PHAI goi ham nay moi khi trang duoi overlay (pager THAT cua Readium) doi VI
     * MOT LY DO KHAC voi chinh cu keo/commit cua overlay nay - vi du TTS autoFollow nhay thang
     * toi 1 locator (xem `autoFollow` trong EpubReaderActivity), nguoi dung bam vao muc luc/ket
     * qua tim kiem/highlight, hoac tinh nang doc tu dong cuon trang (auto-scroll) - tat ca deu
     * goi thang navigatorFragment.go()/goForward()/goBackward(), BO QUA overlay nay hoan toan.
     *
     * Neu khong reset: currentBitmap/nextBitmap van la anh chup CU tu truoc do (vi du dang dang
     * do mot cu keo/animation), trong khi pager THAT o duoi da lang le nhay sang 1 vi tri hoan
     * toan khac (vd TTS da tien vai trang/chuong) - ket qua la overlay tiep tuc ve DE LEN noi
     * dung moi, tao ra hien tuong "chong chap 2 trang" (vd tieu de chuong lon, dam de len doan
     * van thuong) nhu trong anh chup man hinh nguoi dung gui khi dang bat TTS.
     *
     * An toan de goi ngay ca khi trang doi la KET QUA cua chinh overlay nay tu commit (xem
     * animateTo): luc do overlay da tu dua minh ve trang thai sach TRUOC KHI goi
     * host.onCurlCommitted() lam pager that su doi trang, nen resetImmediately() goi ngay sau do
     * (vd tu 1 page-change-listener chung, xem EpubReaderActivity#syncCurlOverlay) chi la lam
     * lai 1 viec da xong, khong gay tac dung phu. */
    fun resetImmediately() {
        flipAnimator?.cancel()
        flipAnimator = null
        isDragging = false
        currentBitmap = null
        nextBitmap = null
        dragX = 0f
        touchYFraction = 0.5f
        releaseVelocityTracker()
        invalidate()
    }

    /** Chay curl "tu dong" cho goForward()/goBack() bam nut - cung 1 duong ve nhu drag tay bi
     * tha qua nguong, chi khac la khong co ngon tay dieu khien dragX/touchYFraction (dung giua
     * trang = 0.5f, duong gap thang dung nhu lat trang thuong). */
    fun animateAutomatic(forward: Boolean) {
        val host = host
        if (host?.isCurlEnabled() != true) {
            host?.onCurlCommitted(forward)
            return
        }
        draggingForward = forward
        currentBitmap = host.captureCurrentBitmap()
        nextBitmap = host.captureNeighbourBitmap(if (forward) 1 else -1)
        if (nextBitmap == null) {
            Logger.d("PageCurlOverlayView: animateAutomatic bo qua - khong lay duoc bitmap trang ke (forward=$forward), chuyen trang binh thuong khong hieu ung.")
            host.onCurlCommitted(forward)
            return
        }
        dragX = 0f
        touchYFraction = 0.5f
        isDragging = true
        animateTo(commit = true)
    }

    private fun animateTo(commit: Boolean) {
        val target = if (commit) width.toFloat() else 0f
        flipAnimator?.cancel()
        flipAnimator = ValueAnimator.ofFloat(dragX, target).apply {
            duration = FLIP_ANIM_DURATION_MS
            addUpdateListener {
                dragX = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    isDragging = false
                    if (commit) host?.onCurlCommitted(draggingForward)
                    currentBitmap = null
                    nextBitmap = null
                    dragX = 0f
                    touchYFraction = 0.5f
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val current = currentBitmap ?: return
        val next = nextBitmap ?: return
        if (dragX <= 0f) return // phang hoan toan, khong co gi de ve de len trang that ben duoi

        host?.drawReaderBackground(canvas, width, height)

        // 1) Trang ke lo ra day tien, toan bo canvas - day la nhung gi con lai sau khi trang hien
        // tai da cuon het khoi vi tri do (vung ben ngoai curlOuterX se lo trang nay ra).
        canvas.drawBitmap(next, 0f, 0f, normalPaint)

        // Chia trang thanh nhieu "dong luoi", moi dong tu tinh 1 dragX rieng (nho dan khi cang xa
        // ngon tay theo Y) - xem PageCurlMath.rowLocalDragFraction va kdoc dau file. Khi keo gan
        // nhu thang dung se chi co 1 dong (re nhu ban cu).
        val rows = buildRowBands()

        // 2) Bong mem do dai cuon do xuong trang ke, ngay sat duong ngoai cung cua cuon (truoc
        // khi ve dai cuon de bong nam DUOI no, giong bong that do vat the cao hon do xuong).
        rows.forEach { drawCastShadowForRow(canvas, it) }

        // 3) Mat trang hien tai + dai cuon, ve bang mot mesh lien tuc. Cach nay hoc theo
        // android-pagecurl-master: chia be mat thanh luoi/scan-line roi warp texture lien tuc,
        // khong ve tung mieng chu nhat roi ghep lai nhu ban cu (nguyen nhan mep bi be khuc).
        drawContinuousCurlMesh(canvas, current)

        // 4) Vet toi mong ngay tai duong gap cua tung dong, de noi lien thi giac voi dai cuon.
        rows.forEach { drawCreaseShadowForRow(canvas, it) }
    }

    /** Mot "dong" cua luoi cuon: khoang Y [y0,y1] tren man hinh, cung hinh hoc rieng (duong gap,
     * ban kinh...) tinh boi PageCurlMath.compute voi dragX da duoc [PageCurlMath.rowLocalDragFraction]
     * dieu chinh cho rieng dong nay. */
    private class RowBand(val y0: Float, val y1: Float, val geometry: PageCurlMath.CurlGeometry)

    private fun buildRowBands(): List<RowBand> {
        val h = height.toFloat()
        if (h <= 0f) return emptyList()
        val rowCount = PageCurlMath.rowCountFor(touchYFraction)
        val w = width.toFloat()
        return (0 until rowCount).map { i ->
            val y0 = h * i / rowCount
            val y1 = h * (i + 1) / rowCount
            val rowYFraction = ((y0 + y1) / 2f / h).coerceIn(0f, 1f)
            val localFraction = PageCurlMath.rowLocalDragFraction(rowYFraction, touchYFraction)
            val geometry = PageCurlMath.compute(
                dragX = dragX * localFraction,
                // Khong nghieng THEM trong noi bo 1 dong - do cong/nghieng tong the da den tu su
                // khac nhau GIUA cac dong (localFraction), xem kdoc buildRowBands/onDraw.
                touchYFraction = 0.5f,
                pageWidth = w,
                pageHeight = (y1 - y0).coerceAtLeast(1f),
            )
            RowBand(y0, y1, geometry)
        }
    }

    /**
     * Ve trang hien tai bang drawBitmapMesh thay cho viec cat thanh nhieu strip rieng. Y tuong
     * lay tu android-pagecurl-master: cac diem scan-line cua trang duoc bien doi theo nửa hinh
     * tru, texture van la mot be mat lien tuc nen khong con seam ngang/doc ro rang. Phan ben trai
     * curlOuterX giu phang; phan ben phai duoc nen vao dai cong outer->fold theo tung hang Y.
     */
    private fun drawContinuousCurlMesh(canvas: Canvas, page: Bitmap) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        ensureCurlMeshBuffers()
        var offset = 0
        var colorOffset = 0
        for (row in 0..CURL_MESH_ROWS) {
            val yFraction = row / CURL_MESH_ROWS.toFloat()
            val srcY = page.height * yFraction
            val localDrag = dragX * PageCurlMath.rowLocalDragFraction(yFraction, touchYFraction)
            val geometry = PageCurlMath.compute(
                dragX = localDrag,
                touchYFraction = 0.5f,
                pageWidth = w,
                pageHeight = h,
            )
            val outerX = geometry.curlOuterTopX.coerceIn(0f, w)
            val foldX = geometry.foldTopX.coerceIn(outerX, w)
            val curlWidth = (foldX - outerX).coerceAtLeast(1f)
            val curledSourceSpan = (w - outerX).coerceAtLeast(1f)

            for (col in 0..CURL_MESH_COLUMNS) {
                val xFraction = col / CURL_MESH_COLUMNS.toFloat()
                val viewX = w * xFraction
                val viewY = h * yFraction
                val beyondOuter = viewX > outerX
                val destX: Float
                val shade: Float
                if (!beyondOuter) {
                    destX = viewX
                    shade = 1f
                } else {
                    val t = ((viewX - outerX) / curledSourceSpan).coerceIn(0f, 1f)
                    val eased = t * t * (3f - 2f * t)
                    val cylinder = sin(eased * Math.PI / 2.0).toFloat()
                    destX = outerX + curlWidth * cylinder
                    val light = sin(eased * Math.PI).toFloat().coerceIn(0f, 1f)
                    shade = (0.54f + 0.46f * light).coerceIn(0.46f, 1f)
                }
                curlMeshVerts[offset++] = destX
                curlMeshVerts[offset++] = srcY.coerceIn(0f, page.height.toFloat()).let { viewY }
                val channel = (shade * 255).toInt().coerceIn(0, 255)
                curlMeshColors[colorOffset++] = Color.argb(255, channel, channel, channel)
            }
        }
        canvas.drawBitmapMesh(
            page,
            CURL_MESH_COLUMNS,
            CURL_MESH_ROWS,
            curlMeshVerts,
            0,
            curlMeshColors,
            0,
            normalPaint,
        )
    }

    private fun ensureCurlMeshBuffers() {
        val vertexCount = (CURL_MESH_COLUMNS + 1) * (CURL_MESH_ROWS + 1)
        val vertSize = vertexCount * 2
        if (curlMeshVerts.size != vertSize) curlMeshVerts = FloatArray(vertSize)
        if (curlMeshColors.size != vertexCount) curlMeshColors = IntArray(vertexCount)
    }

    private fun drawCastShadowForRow(canvas: Canvas, band: RowBand) {
        val geometry = band.geometry
        val spread = geometry.radius * CAST_SHADOW_SPREAD_FACTOR
        if (spread <= 0.5f) return
        val x1 = geometry.curlOuterTopX
        val x0 = (x1 - spread).coerceAtLeast(0f)
        if (x0 >= x1) return
        castShadowPaint.shader = LinearGradient(
            x0, 0f, x1, 0f,
            Color.TRANSPARENT, Color.argb(CAST_SHADOW_MAX_ALPHA, 0, 0, 0),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(x0, band.y0 - rowOverlapPx, x1, band.y1 + rowOverlapPx, castShadowPaint)
    }

    private fun drawCreaseShadowForRow(canvas: Canvas, band: RowBand) {
        val creaseWidth = CREASE_SHADOW_WIDTH_DP * resources.displayMetrics.density
        if (creaseWidth <= 0.5f) return
        val x1 = band.geometry.curlOuterTopX
        val x0 = (x1 - creaseWidth).coerceAtLeast(0f)
        if (x0 >= x1) return
        creaseShadowPaint.shader = LinearGradient(
            x0, 0f, x1, 0f,
            Color.TRANSPARENT, Color.argb(CREASE_SHADOW_MAX_ALPHA, 0, 0, 0),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(x0, band.y0 - rowOverlapPx, x1, band.y1 + rowOverlapPx, creaseShadowPaint)
    }

    /** Vung con phang hoan toan cua trang hien tai: MOT path duy nhat di qua curlOuterTopX cua
     * TUNG dong luoi (xem PageCurlMath.CurlGeometry.curlOuterTopX) - vi cac dong co the "cuon"
     * khac nhau (xem buildRowBands), duong vien ben phai cua vung nay la 1 duong gap khuc nhieu
     * doan, cang nhieu dong (PageCurlMath.rowCountFor) cang gan mot duong CONG that su, thay vi 1
     * doan thang cheo don gian noi dinh/day trang nhu ban truoc. */
    private fun drawFlatRegion(canvas: Canvas, page: Bitmap, rows: List<RowBand>) {
        if (rows.isEmpty()) return
        flatRegionPath.reset()
        flatRegionPath.moveTo(0f, 0f)
        val firstX = rows.first().geometry.curlOuterTopX
        flatRegionPath.lineTo(firstX, 0f)
        rows.forEachIndexed { index, band ->
            val previousX = if (index == 0) firstX else rows[index - 1].geometry.curlOuterTopX
            val currentX = band.geometry.curlOuterTopX
            val midY = (band.y0 + band.y1) / 2f
            // Noi cac moc Y bang cubic co tiep tuyen doc. Neu dung lineTo, 12-36 dong luoi van
            // co the lo mep gap khuc; cubic lam mep phan trang phang mem nhu 1 duong cong lien tuc.
            flatRegionPath.cubicTo(previousX, midY, currentX, midY, currentX, band.y1)
        }
        flatRegionPath.lineTo(0f, height.toFloat())
        flatRegionPath.close()

        canvas.save()
        canvas.clipPath(flatRegionPath)
        canvas.drawBitmap(page, 0f, 0f, normalPaint)
        canvas.restore()
    }

    /** Ve dai cuon cua MOT dong luoi, chia thanh nhieu o cot doc theo huong cuon - xap xi hinh
     * tru bang nhieu mat phang nho, cach cac lib page-curl ma nguon mo hay dung de tranh phai viet
     * OpenGL rieng. Khac ban truoc: vi do nghieng/cong gio o cap DONG (xem buildRowBands) chu
     * khong con trong noi bo 1 dong nua, moi o (dong x cot) BAY GIO la 1 HINH CHU NHAT don gian -
     * chi can canvas.drawBitmap(srcRect, dstRect), khong can Matrix.setPolyToPoly + clipPath nhu
     * ban truoc. Dieu nay vua re hon, vua tranh duoc khe ho AA mong giua cac o clipPath lien ke
     * (nguyen nhan chinh cua nhung "vet den" tho trong ban cu - xem anh chup man hinh nguoi dung
     * gui): 2 hinh chu nhat lien ke duoc "no" ra CHONG len nhau [cellOverlapPx] thay vi khop
     * khit tuyet doi, nen sai so lam tron/AA khong con co the de lo nen phia sau ra nua. */
    private fun drawCurlBandForRow(canvas: Canvas, page: Bitmap, band: RowBand) {
        val geometry = band.geometry
        val bandWidth = geometry.foldTopX - geometry.curlOuterTopX
        if (bandWidth <= 0f) return
        val srcStart = geometry.foldX
        // QUAN TRONG: khong duoc dung "width - srcStart" (toan bo phan trang con lai, co the toi
        // 80-90% chieu rong) lam srcSpan roi nen het vao bandWidth (chi ~radius, toi da 15% chieu
        // rong) - do la nguyen nhan chinh gay "vo/rach" chu khi keo xa (xem anh chup man hinh loi:
        // chu bi nghien vun thanh nhieu soc doc chong cheo). Ve mat vat ly, dai cuon chi dai dien
        // cho phan giay THAT SU dang cuon quanh hinh tru tai vi tri nay - do dai cung xap xi
        // radius * PI (nua chu vi, ung voi curlAngle chay tu 0 den PI qua CURL_STRIP_COUNT cot).
        // Gioi han srcSpan theo do dai cung nay (khong vuot qua phan trang con lai) giu ti le
        // nen/gian hop ly, giong cach eschao/android-PageFlip va Readest lay mau texture cho be
        // mat cong, thay vi nhoi toan bo noi dung con lai vao 1 dai hep.
        val arcSpan = (geometry.radius * Math.PI).toFloat()
        val srcSpan = arcSpan.coerceIn(1f, (width - srcStart).coerceAtLeast(1f))
        val yOverlap = rowOverlapPx
        val srcTop = (band.y0 - yOverlap).toInt().coerceIn(0, page.height)
        val srcBottom = ceil(band.y1 + yOverlap).toInt().coerceIn(srcTop, page.height)
        if (srcBottom <= srcTop) return

        for (i in 0 until CURL_STRIP_COUNT) {
            val f0 = i / CURL_STRIP_COUNT.toFloat()
            val f1 = (i + 1) / CURL_STRIP_COUNT.toFloat()
            val srcX0 = (srcStart + srcSpan * f0).coerceIn(0f, width.toFloat())
            val srcX1 = (srcStart + srcSpan * f1).coerceIn(0f, width.toFloat())
            if (srcX1 - srcX0 < 0.4f) continue

            val dstX0 = geometry.foldTopX - bandWidth * f1
            val dstX1 = geometry.foldTopX - bandWidth * f0

            val angle = (Math.PI * (f0 + f1) / 2.0).toFloat()
            val isBackFacing = angle > (Math.PI / 2).toFloat()

            reusableSrcRect.set(srcX0.toInt(), srcTop, ceil(srcX1).toInt().coerceAtMost(page.width), srcBottom)
            reusableDstRect.set(
                min(dstX0, dstX1) - cellOverlapPx,
                band.y0 - yOverlap,
                max(dstX0, dstX1) + cellOverlapPx,
                band.y1 + yOverlap,
            )
            if (reusableSrcRect.width() <= 0 || reusableDstRect.width() <= 0f) continue

            canvas.save()
            if (isBackFacing) {
                // Lat guong quanh truc doc giua cua o, roi ve bang backPaint - mo phong mat sau
                // giay khi be mat da xoay qua 90 do, khong con quay ra nguoi doc.
                canvas.scale(-1f, 1f, reusableDstRect.centerX(), reusableDstRect.centerY())
            }
            canvas.drawBitmap(page, reusableSrcRect, reusableDstRect, if (isBackFacing) backPaint else normalPaint)
            canvas.restore()

            // Bong do dang mang gradient (thay vi chu nhat phang mau den nhu ban truoc): dam
            // nhat gan duong gap (angle gan 0), nhat dan khi angle tien ve PI/2 (be mat vuong goc
            // voi nguoi nhin, it bi khuat sang nhat), roi lai dam nhe o mat sau (anh sang khong
            // xuyen qua giay). Dung shader theo huong vuong goc voi truc cuon (X) de mo phong anh
            // sang chieu tu 1 phia thay vi mot khoi mau dong deu.
            val shadowAlpha = (sin(angle.toDouble()) * MAX_SHADOW_ALPHA).toInt().coerceIn(0, 255)
            if (shadowAlpha > 0) {
                stripShadingPaint.shader = LinearGradient(
                    dstX0, 0f, dstX1, 0f,
                    Color.argb(shadowAlpha / 3, 0, 0, 0),
                    Color.argb(shadowAlpha, 0, 0, 0),
                    Shader.TileMode.CLAMP,
                )
                canvas.drawRect(reusableDstRect, stripShadingPaint)
            }

            // Vet sang (highlight) - dinh diem o vung be mat cong nhat, ngay truoc khi chuyen
            // sang mat sau (angle ~ 0.55*PI): mo phong anh sang bat vao phan tron nhat cua cuon
            // giay, chi tiet quan trong nhat de nguoi xem cam nhan duoc "do tron" 3 chieu.
            val highlightBell = highlightBellCurve(angle)
            if (highlightBell > 0.01f && !isBackFacing) {
                val highlightAlpha = (highlightBell * MAX_HIGHLIGHT_ALPHA).toInt().coerceIn(0, 255)
                highlightPaint.color = Color.argb(highlightAlpha, 255, 255, 255)
                canvas.drawRect(reusableDstRect, highlightPaint)
            }
        }
    }

    /** No (dilate) nhe cac o luoi ra theo X, tinh bang px thuc te (dua tren mat do man hinh) -
     * xem kdoc [drawCurlBandForRow]. */
    private val cellOverlapPx: Float
        get() = CELL_OVERLAP_DP * resources.displayMetrics.density

    /** No nhe cac dong luoi theo Y de nuot khe noi ngang giua hai dong lien ke. Khac voi viec
     * tang so dong, overlap nay xu ly phan AA/lam tron pixel nen mep cuon khong con bi cat thanh
     * tung mieng hinh chu nhat ro nhu anh chup. */
    private val rowOverlapPx: Float
        get() = ROW_OVERLAP_DP * resources.displayMetrics.density

    /** Duong cong hinh chuong, dinh o [HIGHLIGHT_PEAK_ANGLE_FRACTION] * PI - dung mu (pow) de
     * vet sang hep va ro rang thay vi lan toa mo nhat suot ca dai cuon. */
    private fun highlightBellCurve(angle: Float): Float {
        val peak = (Math.PI * HIGHLIGHT_PEAK_ANGLE_FRACTION).toFloat()
        val halfWidth = (Math.PI / 2.4).toFloat()
        val normalized = ((angle - peak) / halfWidth).coerceIn(-1f, 1f)
        val bell = 1f - normalized * normalized
        return max(0f, min(1f, bell.pow(2)))
    }

    companion object {
        private const val FLIP_THRESHOLD_FRACTION = 0.35f
        private const val FLIP_ANIM_DURATION_MS = 250L

        /** So cot moi DONG luoi. Tong so o ve moi khung hinh = so nay * PageCurlMath.rowCountFor
         * (toi da 36) - tang nhe so cot so voi ban cu de mat cuon bot lo canh dung, nhung van
         * tranh day chi phi ve len qua cao tren thiet bi tam trung. */
        private const val CURL_STRIP_COUNT = 28
        private const val CURL_MESH_COLUMNS = 72
        private const val CURL_MESH_ROWS = 64
        private const val MAX_SHADOW_ALPHA = 100
        private const val MAX_HIGHLIGHT_ALPHA = 90
        private const val HIGHLIGHT_PEAK_ANGLE_FRACTION = 0.42
        private const val CAST_SHADOW_SPREAD_FACTOR = 1.6f
        private const val CAST_SHADOW_MAX_ALPHA = 70
        private const val CREASE_SHADOW_WIDTH_DP = 10f
        private const val CREASE_SHADOW_MAX_ALPHA = 55

        /** Do "no" (dilate) cua tung o luoi theo X, dp - xem kdoc [drawCurlBandForRow]. Du lon de
         * nuot sai so lam tron/AA (thuong duoi 1px) tren moi mat do man hinh, du nho de khong lam
         * lem duong vien ngoai cung cua ca dai cuon. */
        private const val CELL_OVERLAP_DP = 0.75f
        private const val ROW_OVERLAP_DP = 1.25f
    }
}
