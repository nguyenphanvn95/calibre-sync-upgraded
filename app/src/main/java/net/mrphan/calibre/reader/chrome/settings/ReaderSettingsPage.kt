package net.mrphan.calibre.reader.chrome.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

/**
 * Moi tab cua [ReaderSettingsSheet] la mot [ReaderSettingsPage] doc lap, tu quan ly viec doc/ghi
 * preference cua no bang cach goi thang cac ham co san tren `ReaderChromeHost` (khi co tren
 * interface, vi du [net.mrphan.calibre.reader.chrome.ReaderChromeHost.setColorTheme]) hoac -- cho
 * cac kha nang moi chua co tren interface do (font/layout/behavior/tts, xem kdoc cua tung Page) --
 * bang cach ep kieu `host` ve `EpubReaderActivity` (an toan vi [ReaderSettingsSheet] hien chi duoc
 * mo tu do, xem [ReaderSettingsTab]). Khong duoc tu y ghi thang vao SharedPreferences rieng de
 * tranh trung lap voi logic da co.
 */
interface ReaderSettingsPage {
    fun onCreateView(inflater: LayoutInflater, parent: ViewGroup): View
}
