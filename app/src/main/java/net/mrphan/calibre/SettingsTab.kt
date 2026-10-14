package net.mrphan.calibre

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.annotation.XmlRes

/**
 * Mot subtab trong thanh icon ngang cua man hinh Settings (SettingsActivity), clone kieu bo cuc
 * cua thanh tab "Cai dat doc" (xem ReaderSettingsTab/ReaderSettingsSheet trong
 * reader/chrome/settings/): moi "khoi" truoc day la mot <PreferenceCategory> gom nhieu muc trong
 * cung 1 danh sach cuon dai (res/xml/preference.xml), gio moi khoi la MOT tab icon rieng + MOT
 * file xml preference rieng (res/xml/preference_*.xml), noi dung ben duoi chi hien thi dung khoi
 * dang duoc chon -- xem SettingsActivity.showTab()/SettingsActivity.SettingsFragment.
 *
 * android:key cua tung Preference ben trong khong doi so voi ban truoc khi tach file, nen moi
 * findPreference(key)/dependency trong SettingsActivity.java van hoat dong y nguyen.
 *
 * [label] duoc tai su dung tu chinh cac string category da co san truoc do
 * (settings_category_*_title / settings_lookup_category_title) thay vi tao string moi, vi noi
 * dung y nghia giong het nhau (chi doi tu "tieu de nhom trong danh sach" thanh "nhan/contentDescription
 * cua tab icon").
 */
enum class SettingsTab(
    @DrawableRes val icon: Int,
    @StringRes val label: Int,
    @XmlRes val prefsRes: Int,
) {
    APPEARANCE(
        R.drawable.ic_settings_tab_appearance,
        R.string.settings_category_appearance_title,
        R.xml.preference_appearance,
    ),
    CONNECTION(
        R.drawable.ic_settings_tab_connection,
        R.string.settings_category_connection_title,
        R.xml.preference_connection,
    ),
    STORAGE(
        R.drawable.ic_settings_tab_storage,
        R.string.settings_category_storage_title,
        R.xml.preference_storage,
    ),
    TTS(
        // Tai su dung icon loa cua reader (cung y nghia "doc to") thay vi tao ban ve trung lap.
        R.drawable.ic_reader_read_aloud,
        R.string.settings_category_tts_title,
        R.xml.preference_tts,
    ),
    LOOKUP(
        // Tai su dung icon globe/translate co san (cung y nghia "tu dien & dich").
        R.drawable.ic_selection_translate,
        R.string.settings_lookup_category_title,
        R.xml.preference_lookup,
    ),
    SYSTEM(
        R.drawable.ic_settings_tab_system,
        R.string.settings_category_system_title,
        R.xml.preference_system,
    ),
}
