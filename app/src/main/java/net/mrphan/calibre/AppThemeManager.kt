package net.mrphan.calibre

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager

/**
 * Đọc lựa chọn "Giao diện" (pref_app_theme: Theo hệ thống/Sáng/Tối) đã lưu và áp dụng qua
 * AppCompatDelegate.setDefaultNightMode(). Chỉ điều khiển theme khung ứng dụng (chrome, dialog,
 * Settings, Thư viện, khung + menu Cài đặt của Reader) -- KHÔNG đụng tới ReaderThemeMode/
 * ReaderColorTheme (theme nội dung sách, xem reader/chrome/ReaderThemeMode.kt).
 *
 * Việc GHI giá trị mới do chính ListPreference "pref_app_theme" tự lo (persistent mặc định của
 * Preference), object này chỉ có trách nhiệm ĐỌC lại giá trị đã lưu lúc khởi động app --
 * xem CalibreSyncApplication.onCreate().
 */
object AppThemeManager {
    const val PREF_KEY = "pref_app_theme"

    fun applyPersistedTheme(context: Context) {
        AppCompatDelegate.setDefaultNightMode(readSavedMode(context))
    }

    private fun readSavedMode(context: Context): Int {
        val saved = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_KEY, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM.toString())
        return saved?.toIntOrNull() ?: AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }
}
