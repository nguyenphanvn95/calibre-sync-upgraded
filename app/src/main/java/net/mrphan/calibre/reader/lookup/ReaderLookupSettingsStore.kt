package net.mrphan.calibre.reader.lookup

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Doc cac tuy chon Tu dien/Dich nguoi dung tu cau hinh trong man hinh Settings
 * (`res/xml/preference.xml`, muc "Từ điển và Dịch").
 *
 * **Khac voi [net.mrphan.calibre.reader.tts.TtsSettingsStore]**: TtsSettingsStore dung file
 * SharedPreferences rieng (`tts_settings`) vi khong co man hinh Settings (Preference) nao cho no
 * ca -- toan bo TTS duoc cau hinh ngay trong reader. Nguoc lai, 3 gia tri o day (ngon ngu dich
 * mac dinh, provider dich, DeepL API key) *da* co `ListPreference`/`EditTextPreference` tuong ung
 * trong `preference.xml`, va `PreferenceFragmentCompat` mac dinh doc/ghi qua
 * `PreferenceManager.getDefaultSharedPreferences()`. Neu class nay dung mot file
 * SharedPreferences rieng (nhu ban nhap dau tien cua prompt nay de xuat) thi se co **2 noi doc/ghi
 * 2 file khac nhau cho cung 1 gia tri** -- nguoi dung doi ngon ngu dich trong Settings nhung
 * bottom sheet Dich van dung gia tri cu vi doc tu file khac. Vi vay class nay CHU DINH doc thang
 * tu `getDefaultSharedPreferences()` voi dung cac key trong `preference.xml`, khong tao file
 * rieng -- day la lua chon duy nhat dam bao 1 gia tri = 1 noi luu.
 */
class ReaderLookupSettingsStore(context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    /**
     * Ma ngon ngu dich mac dinh (vi, en, ja, ...). Mac dinh "vi" vi nguoi dung chinh cua app doc
     * sach tieng Anh/Trung va can dich sang tieng Viet. Phai khop `android:defaultValue="vi"` cua
     * `lookup_target_language` trong preference.xml.
     */
    fun getTargetLanguage(): String = prefs.getString(KEY_TARGET_LANG, "vi") ?: "vi"

    /**
     * "google_free" hoac "deepl" -- xem [GoogleTranslateFreeProvider]/[DeepLProvider]. Phai khop
     * `android:defaultValue` cua `lookup_translation_provider` trong preference.xml.
     */
    fun getTranslationProviderId(): String = prefs.getString(KEY_TRANSLATE_PROVIDER, "google_free") ?: "google_free"

    /**
     * Nguoi dung tu nhap key rieng cua ho (mien phi tu deepl.com/pro-api), KHONG bao gio hardcode
     * key mac dinh nao trong app. Null/rong nghia la chua cau hinh -- [LookupBottomSheetFragment]
     * se tu dung [GoogleTranslateFreeProvider] trong truong hop do du nguoi dung co chon "deepl".
     */
    fun getDeepLApiKey(): String? = prefs.getString(KEY_DEEPL_KEY, null)?.takeIf { it.isNotBlank() }

    companion object {
        private const val KEY_TARGET_LANG = "lookup_target_language"
        private const val KEY_TRANSLATE_PROVIDER = "lookup_translation_provider"
        private const val KEY_DEEPL_KEY = "lookup_deepl_api_key"
    }
}
