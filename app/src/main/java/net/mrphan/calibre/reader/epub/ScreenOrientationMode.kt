package net.mrphan.calibre.reader.epub

import android.content.pm.ActivityInfo

/**
 * 3 lua chon cho hang "Xoay man hinh" trong tab "Thao tac" cua Reader Settings (xem
 * ReaderSettingsBehaviorPage.kt), dat ngay duoi hang cong tac "Giu man hinh sang khi doc".
 * Mirror dung cau truc cua PageTurnAnimation (PageTurnTransformers.kt): enum + fromPrefName,
 * de luu/doc qua SharedPreferences bang ten hang so (name), giong cach
 * EpubReaderActivity.PREF_READER_PAGE_TURN_ANIMATION dang lam.
 */
enum class ScreenOrientationMode {
    SYSTEM, PORTRAIT, LANDSCAPE;

    /** Gia tri truyen thang cho Activity.setRequestedOrientation(). SYSTEM dung UNSPECIFIED de
     * tra quyen quyet dinh huong man hinh lai cho he dieu hanh (tuc la ung xu y het truoc khi co
     * tinh nang nay), khong dung SENSOR vi SENSOR se bo qua ca cai dat "Khoa xoay man hinh" cua
     * chinh he thong, sai voi y nghia "Theo he thong" ma nguoi dung mong doi. */
    fun toActivityInfoOrientation(): Int = when (this) {
        SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    companion object {
        fun fromPrefName(name: String?): ScreenOrientationMode =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}
