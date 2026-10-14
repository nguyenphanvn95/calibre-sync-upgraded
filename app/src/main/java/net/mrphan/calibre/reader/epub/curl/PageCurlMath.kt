package net.mrphan.calibre.reader.epub.curl

import kotlin.math.abs
import kotlin.math.max

/**
 * Toan hoc thuan cho hieu ung cuon giay hinh tru (cylindrical curl), mo phong kieu Moon
 * Reader/iBooks/android-PageFlip. Gom 2 tang:
 *  1) [compute] - hinh hoc CUA MOT DAI NGANG (mot "dong luoi"): duong gap, ban kinh, diem ngoai
 *     cung. Doc lap voi Y (khong phu thuoc chieu cao dai).
 *  2) [rowCountFor]/[rowLocalDragFraction] - dieu phoi NHIEU dong nhu vay chong len nhau theo
 *     chieu doc, moi dong tu goi [compute] voi mot dragX RIENG (nho dan khi cang xa ngon tay theo
 *     Y - xem kdoc [rowLocalDragFraction]). Day la thu thay the cho ban truoc chi co DUY NHAT 1
 *     duong gap thang/cheo cho ca trang: xep nhieu dong canh nhau, moi dong "cuon" khac nhau mot
 *     chut, tao ra duong VIEN CONG/thon dan ve goc trang (giong anh chup Moon Reader nguoi dung
 *     gui) thay vi 1 doan thang cheo don gian - va vi cang xa ngon tay cang cuon it, no cung dong
 *     thoi mo phong dung hon cam giac "nam mot goc giay that ma keo" (xem
 *     https://github.com/eschao/android-PageFlip).
 *
 * Khong phu thuoc Android View/Bitmap - de co the unit-test bang JVM test thuong, khong can
 * instrumentation. Xem PageCurlOverlayView cho phan ve Canvas dua tren ket qua cua class nay.
 */
object PageCurlMath {

    /** Ban kinh cuon nho nhat/lon nhat, tinh theo % chieu rong trang. Ca hai dau cu dong (vua
     * bat dau keo, hoac sap lat xong) deu can ban kinh nho hon giua chung - xem [compute]. */
    private const val MIN_RADIUS_FRACTION = 0.035f
    private const val MAX_RADIUS_FRACTION = 0.15f

    /** Do nghieng toi da cua duong gap (tinh theo % chieu rong trang) khi ngon tay o sat mep
     * tren/duoi man hinh. 0 = luon thang dung (hieu ung cu), cang lon cang "cheo" ro theo kieu
     * corner-drag that. Van giu de [compute] tu no van dung duoc doc lap (vd goi truc tiep voi 1
     * dong duy nhat khi keo gan nhu thang dung - xem [rowCountFor]). */
    private const val MAX_TILT_FRACTION = 0.14f

    /** So dong luoi toi da khi cu keo cheo het muc (xem [tiltStrengthOf] = 1). Ban cu dung 12
     * dong nen canh cuon nhin bi be thanh tung khuc lon tren man hinh cao. Tang len 36 de duong
     * cong mem hon, van giu du nhe vi moi dong chi ve cac strip chu nhat don gian. */
    private const val MAX_MESH_ROWS = 36

    /** Muc cuon con lai (ti le so voi dong dang o duoi ngon tay) tai dong XA ngon tay NHAT khi
     * keo cheo toi da - xem [rowLocalDragFraction]. Khong ve ve 0 de duong vien khong bi "cat cut"
     * qua dot ngot o goc trang xa, van co mot chut cuon lien tuc. */
    private const val ROW_FALLOFF_FLOOR = 0.32f

    data class CurlGeometry(
        /** Vi tri duong gap tren truc X o mep TREN trang (y=0). */
        val foldTopX: Float,
        /** Vi tri duong gap tren truc X o mep DUOI trang (y=pageHeight). */
        val foldBottomX: Float,
        /** Trung binh foldTopX/foldBottomX - giu de tuong thich nguoc voi cac phep tinh/test coi
         * duong gap la 1 diem duy nhat tren truc X. */
        val foldX: Float,
        /** Ban kinh hinh tru, don vi px - dung chung cho ca chieu cao trang (khong bien doi theo
         * Y) de giu hinh tru deu, chi truc cua no nghieng theo Y. */
        val radius: Float,
        /** Goc da cuon qua, 0 = phang hoan toan, PI = da lat het (phang tro lai o phia ben
         * kia). Dung de tinh do dam bong do va xac dinh khi nao dang thay "mat sau" trang. */
        val curlAngle: Float,
        /** Diem ngoai cung ma phan cuon con che duoc trang hien tai o mep TREN (foldTopX - radius,
         * kep >= 0). Ben ngoai diem nay, trang ke tiep da lo ra hoan toan. */
        val curlOuterTopX: Float,
        /** Tuong tu curlOuterTopX nhung cho mep DUOI. */
        val curlOuterBottomX: Float,
    )

    /**
     * @param dragX khoang cach da keo tu mep dang lat, px, luon >= 0 (da lay tri tuyet doi va
     *   quy ve chieu chuan boi caller).
     * @param touchYFraction vi tri Y cua ngon tay tren trang, quy ve [0,1] (0 = sat mep tren, 1 =
     *   sat mep duoi, 0.5 = giua). Dung de nghieng duong gap - keo tu gan mep tren se cuon manh o
     *   tren va cham o duoi, va nguoc lai. Truyen 0.5f neu khong biet vi tri Y that (vd curl tu
     *   dong khi bam nut, khong phai vuot ngon tay).
     * @param pageWidth chieu rong vung noi dung trang, px.
     * @param pageHeight chieu cao vung noi dung trang, px. Giu tham so nay (khong dung truc tiep
     *   trong ham - hinh hoc 1 dai ngang khong phu thuoc Y) de tuong thich nguoc voi cac test/cho
     *   goi cu, va de danh cho nguoi goi (PageCurlOverlayView) truyen chieu cao CUA TUNG DONG khi
     *   dung [compute] nhu 1 dong luoi con - xem kdoc dau file.
     */
    fun compute(
        dragX: Float,
        touchYFraction: Float = 0.5f,
        pageWidth: Float,
        @Suppress("UNUSED_PARAMETER") pageHeight: Float,
    ): CurlGeometry {
        val clampedDrag = dragX.coerceIn(0f, pageWidth)
        val foldX = (pageWidth - clampedDrag).coerceIn(0f, pageWidth)

        val progress = if (pageWidth <= 0f) 0f else (clampedDrag / pageWidth).coerceIn(0f, 1f)
        // Ban kinh nho o 2 dau (progress = 0 hoac 1), lon nhat o giua gesture (progress = 0.5)
        // - day la thu cho cam giac "cuon nho o mep, phinh to giua chung" giong Moon Reader,
        // thay vi mot ong tron kich thuoc co dinh suot ca cu dong.
        val bulge = (progress * (1f - progress) * 4f).coerceIn(0f, 1f)
        val minRadius = pageWidth * MIN_RADIUS_FRACTION
        val maxRadius = pageWidth * MAX_RADIUS_FRACTION
        val radius = minRadius + (maxRadius - minRadius) * bulge

        val curlAngle = (progress * Math.PI).toFloat()

        // Nghieng duong gap theo Y cua ngon tay. t trong [-1, 1]: -1 = sat mep tren, +1 = sat
        // mep duoi. Nhan them "bulge" de do nghieng tang dan mem mai theo cu keo (giong bulge cua
        // ban kinh) thay vi bat/tat dot ngot, va it lo lieu khi vua cham/vua tha ngon tay.
        val t = ((touchYFraction - 0.5f) * 2f).coerceIn(-1f, 1f)
        val tiltMag = pageWidth * MAX_TILT_FRACTION * bulge
        val foldTopX = (foldX + t * tiltMag).coerceIn(0f, pageWidth)
        val foldBottomX = (foldX - t * tiltMag).coerceIn(0f, pageWidth)

        val curlOuterTopX = (foldTopX - radius).coerceAtLeast(0f)
        val curlOuterBottomX = (foldBottomX - radius).coerceAtLeast(0f)

        return CurlGeometry(
            foldTopX = foldTopX,
            foldBottomX = foldBottomX,
            foldX = (foldTopX + foldBottomX) / 2f,
            radius = radius,
            curlAngle = curlAngle,
            curlOuterTopX = curlOuterTopX,
            curlOuterBottomX = curlOuterBottomX,
        )
    }

    /** He so [0,1] the hien do "cheo" cua cu keo: 0 khi ngon tay dung giua canh trang
     * (touchYFraction = 0.5, duong gap thang dung nhu lat trang thuong), 1 khi ngon tay o sat mep
     * tren/duoi man hinh (giong nam han vao 1 GOC trang that de keo). Dung de quyet dinh can bao
     * nhieu dong luoi ([rowCountFor]) va cac dong xa ngon tay "tut" cuon nhanh hay cham
     * ([rowLocalDragFraction]). */
    fun tiltStrengthOf(touchYFraction: Float): Float =
        (abs(touchYFraction - 0.5f) * 2f).coerceIn(0f, 1f)

    /** So dong luoi nen dung cho 1 khung hinh, tuy theo do cheo cua cu keo ([tiltStrengthOf]).
     * Khi keo gan nhu thang dung (tiltStrength ~ 0, vd bam nut lat trang hoac keo dung giua canh)
     * chi can 1 dong - RE nhat, tuong duong hoan toan ban truoc (1 duong gap duy nhat cho ca
     * trang). Cang keo cheo (gan goc trang) cang can nhieu dong hon de duong vien cuon cong mem
     * thay vi gap khuc tho, toi da [MAX_MESH_ROWS]. */
    fun rowCountFor(touchYFraction: Float): Int {
        val tilt = tiltStrengthOf(touchYFraction)
        if (tilt < 0.02f) return 1
        return (1 + tilt * (MAX_MESH_ROWS - 1)).toInt().coerceIn(1, MAX_MESH_ROWS)
    }

    /**
     * He so [0,1] de nhan voi dragX chung, ra dragX RIENG cho 1 "dong luoi" o vi tri doc
     * [rowYFraction] (0 = sat mep tren trang, 1 = sat mep duoi) - day la phan mo phong "cuon tu 1
     * GOC trang" giong that/giong Moon Reader: dong TRUNG voi ngon tay (rowYFraction gan
     * [touchYFraction]) cuon dung bang dragX goc (he so ~1.0), cang xa ngon tay theo chieu doc thi
     * dong do cang cuon IT hon (he so nho dan ve [ROW_FALLOFF_FLOOR]) - xep nhieu dong nhu vay
     * canh nhau (xem PageCurlOverlayView) se tao ra 1 duong vien CONG/thon dan ve phia goc trang xa
     * ngon tay nhat, thay vi 1 doan thang cheo don gian noi 2 diem tren/duoi nhu ban truoc.
     *
     * Khi ngon tay o dung giua canh (touchYFraction = 0.5, [tiltStrengthOf] = 0), ham nay tra ve 1
     * cho MOI dong - tuc la KHONG doi hanh vi/hinh dang so voi truoc (duong gap thang dung deu).
     */
    fun rowLocalDragFraction(rowYFraction: Float, touchYFraction: Float): Float {
        val tilt = tiltStrengthOf(touchYFraction)
        if (tilt <= 0f) return 1f
        // Doc: sat theo tiltStrength - keo cang cheo, day cang xa ngon tay cang "hut" mau.
        val floor = 1f - (1f - ROW_FALLOFF_FLOOR) * tilt
        // maxDist = khoang cach (theo Y, [0,1]) tu ngon tay toi mep TRANG XA NHAT - diem se dat
        // dung "floor" o cuoi duong ease ben duoi.
        val maxDist = max(touchYFraction, 1f - touchYFraction).coerceAtLeast(0.001f)
        val dist = (abs(rowYFraction - touchYFraction) / maxDist).coerceIn(0f, 1f)
        // smoothstep: duong vien cong-mem-mai thay vi noi thang tuyen tinh gap khuc giua cac dong.
        val eased = dist * dist * (3f - 2f * dist)
        return (1f - (1f - floor) * eased).coerceIn(floor, 1f)
    }
}
