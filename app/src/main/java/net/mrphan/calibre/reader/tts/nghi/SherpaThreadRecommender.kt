package net.mrphan.calibre.reader.tts.nghi

/**
 * De xuat so luong CPU sherpa-onnx nen dung, dua tren so core thuc te cua may
 * (Runtime.getRuntime().availableProcessors()) - KHONG doc cau hinh cung, de tu thich
 * ung may yeu/may manh. Quy tac co tinh bao thu (thien ve on dinh hon toc do), vi
 * generate() chay tren Dispatchers.IO cung luc UI thread va cac tac vu khac (dong bo,
 * cover prefetch...) van can CPU - xem NghiTtsEngine.kt.
 */
object SherpaThreadRecommender {

    /** Cac lua chon hien thi trong dialog, co dinh 1..4 luong - khong phoi bay so lon hon
     * cho nguoi dung chon tay vi sherpa-onnx khong huong loi tuyen tinh qua so core thuc,
     * va de lam may yeu bi giat khi doc kem cac tac vu nen khac. */
    val SELECTABLE_THREAD_COUNTS = listOf(1, 2, 3, 4)

    /**
     * @return so luong khuyen nghi, luon nam trong [SELECTABLE_THREAD_COUNTS].
     * - <=2 core thuc -> 1 (de danh core con lai cho UI/he thong)
     * - 3-4 core -> 2
     * - 5-7 core -> 3
     * - >=8 core -> 4
     */
    fun recommendedThreadCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            cores <= 2 -> 1
            cores <= 4 -> 2
            cores <= 7 -> 3
            else -> 4
        }
    }
}
