package net.mrphan.calibre.reader.chrome.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import net.mrphan.calibre.R
import net.mrphan.calibre.reader.chrome.ReaderChromeHost
import net.mrphan.calibre.reader.chrome.ReaderKind

/**
 * Bang "Cai dat doc" hop nhat, thay the cho viec mo nhieu dialog roi rac (font/mau/TTS) tu menu
 * ba gach ngang -- xem prompt-nang-cap-reader-settings-tabbed.md. Cac nut nhanh o footer/menu cu
 * (`FontSizeAction` -> `openFontSizeControls()`, `ColorContrastAction` ->
 * `openColorAndContrastSettings()`) van con nguyen, khong bi thay the: sheet nay chi la mot loi
 * vao THEM, danh cho `PreferencesAction` ("Tinh chinh" trong nhom menu "Hien thi").
 *
 * Thanh tab icon ngang tren cung (clone bo cuc cua Readest, xem [ReaderSettingsTab]), noi dung
 * ben duoi la mot [ReaderSettingsPage] duy nhat duoc doi cho theo tab dang chon. Moi page tu quan
 * ly viec doc/ghi preference cua no, sheet nay chi lo dieu huong UI.
 */
class ReaderSettingsSheet(
    private val host: ReaderChromeHost,
    private val readerKind: ReaderKind,
    private val initialTab: ReaderSettingsTab = ReaderSettingsTab.FONT,
) : BottomSheetDialogFragment() {

    private lateinit var tabRow: LinearLayout
    private lateinit var pageContainer: FrameLayout

    private var currentTab: ReaderSettingsTab = initialTab

    private val availableTabs: List<ReaderSettingsTab> by lazy {
        ReaderSettingsTab.entries.filter { it.isRelevantFor(readerKind) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = inflater.inflate(R.layout.sheet_reader_settings, container, false)
        tabRow = root.findViewById(R.id.reader_settings_tab_row)
        pageContainer = root.findViewById(R.id.reader_settings_page_container)
        buildTabRow()
        showTab(if (availableTabs.contains(initialTab)) initialTab else availableTabs.first())
        return root
    }

    private fun buildTabRow() {
        tabRow.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        for (tab in availableTabs) {
            val tabView = inflater.inflate(R.layout.item_reader_settings_tab, tabRow, false)
            tabView.findViewById<ImageView>(R.id.reader_settings_tab_icon).setImageResource(tab.icon)
            tabView.contentDescription = getString(tab.label)
            tabView.setOnClickListener { showTab(tab) }
            tabView.tag = tab
            tabRow.addView(tabView)
        }
        refreshTabSelection()
    }

    private fun refreshTabSelection() {
        for (i in 0 until tabRow.childCount) {
            val child = tabRow.getChildAt(i)
            child.isSelected = child.tag == currentTab
        }
    }

    private fun showTab(tab: ReaderSettingsTab) {
        currentTab = tab
        refreshTabSelection()
        pageContainer.removeAllViews()
        val context = requireContext()
        val page: ReaderSettingsPage = when (tab) {
            ReaderSettingsTab.FONT -> ReaderSettingsFontPage(host)
            ReaderSettingsTab.LAYOUT -> ReaderSettingsLayoutPage(host)
            ReaderSettingsTab.COLOR -> ReaderSettingsColorPage(host, context)
            ReaderSettingsTab.BEHAVIOR -> ReaderSettingsBehaviorPage(host)
            ReaderSettingsTab.TTS -> ReaderSettingsTtsPage(host, context)
            ReaderSettingsTab.SYNC -> ReaderSettingsSyncPage(host, context)
        }
        pageContainer.addView(page.onCreateView(LayoutInflater.from(context), pageContainer))
    }

    companion object {
        const val TAG = "ReaderSettingsSheet"
    }
}
