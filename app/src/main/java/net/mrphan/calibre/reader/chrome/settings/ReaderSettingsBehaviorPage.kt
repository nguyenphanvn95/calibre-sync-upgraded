package net.mrphan.calibre.reader.chrome.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import net.mrphan.calibre.EpubReaderActivity
import net.mrphan.calibre.R
import net.mrphan.calibre.reader.chrome.ReaderChromeHost

/**
 * "Thao tác" tab. 2 control độc lập với Readium/EpubPreferences, chỉ là field/flag riêng của
 * EpubReaderActivity: "Giữ màn hình sáng khi đọc" (Window flag) và "Xoay màn hình"
 * (Activity.requestedOrientation) -- xem [EpubReaderActivity.setKeepScreenOnEnabled] và
 * [EpubReaderActivity.setScreenOrientationMode]'s kdoc. Not on [ReaderChromeHost] yet since
 * PdfReaderActivity/CbzReaderActivity are outside this prompt's file scope (see
 * [ReaderSettingsPage]'s kdoc for why a direct cast is used instead of widening the interface);
 * a follow-up wiring PDF/CBZ into this sheet ([ReaderSettingsTab.BEHAVIOR] already reports
 * `isRelevantFor` = true for every [net.mrphan.calibre.reader.chrome.ReaderKind]) should move
 * this onto the interface once those two Activities also implement it.
 *
 * Hiệu ứng lật trang (page-turn animation): KHÔNG đi qua `EpubPreferences`/
 * `EpubNavigatorFragment.Configuration` (Readium Kotlin Toolkit 3.1.2 không có field kiểu này,
 * vẫn xác nhận lại trước khi sửa nếu bản Readium thay đổi) -- thay vào đó cắm thẳng
 * `ViewPager.setPageTransformer` vào `ViewPager` (v1, KHÔNG phải ViewPager2 - xem kdoc
 * `findInnerViewPager` trong EpubReaderActivity.kt) nội bộ mà `EpubNavigatorFragment` dùng để
 * chuyển trang, xem [net.mrphan.calibre.reader.epub.PageTurnTransformers.kt] và
 * `EpubReaderActivity.applyPageTurnAnimation()`/`findInnerViewPager()`. Prompt gốc:
 * prompt-nang-cap-hieu-ung-lat-trang.md.
 */
internal class ReaderSettingsBehaviorPage(private val host: ReaderChromeHost) : ReaderSettingsPage {

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup): View {
        val root = inflater.inflate(R.layout.page_reader_settings_behavior, parent, false)
        val card = root.findViewById<LinearLayout>(R.id.reader_settings_behavior_card)
        val activity = host as EpubReaderActivity

        ReaderSettingsRowBinder.addSwitchRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_settings_keep_screen_on,
            initialValue = activity.keepScreenOnEnabled,
        ) { checked ->
            activity.setKeepScreenOnEnabled(checked)
        }

        val orientationLabels = arrayOf(
            activity.getString(R.string.reader_screen_orientation_system),
            activity.getString(R.string.reader_screen_orientation_portrait),
            activity.getString(R.string.reader_screen_orientation_landscape),
        )
        val orientationValues = listOf(
            net.mrphan.calibre.reader.epub.ScreenOrientationMode.SYSTEM,
            net.mrphan.calibre.reader.epub.ScreenOrientationMode.PORTRAIT,
            net.mrphan.calibre.reader.epub.ScreenOrientationMode.LANDSCAPE,
        )
        fun currentOrientationLabel(): String {
            val index = orientationValues.indexOf(activity.currentScreenOrientationMode).coerceAtLeast(0)
            return orientationLabels[index]
        }
        lateinit var orientationRowValueLabel: android.widget.TextView
        val orientationRow = ReaderSettingsRowBinder.addLinkRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_settings_screen_orientation_label,
            value = currentOrientationLabel(),
        ) {
            androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle(R.string.reader_settings_screen_orientation_label)
                .setItems(orientationLabels) { _, which ->
                    activity.setScreenOrientationMode(orientationValues[which])
                    // Cap nhat lai nhan hien thi tren hang -- cach lam giong het
                    // pageTurnRowValueLabel ben duoi trong cung file nay.
                    orientationRowValueLabel.text = currentOrientationLabel()
                }
                .show()
        }
        orientationRowValueLabel = orientationRow.findViewById(R.id.reader_settings_row_value)

        val pageTurnLabels = arrayOf(
            activity.getString(R.string.reader_page_turn_animation_none),
            activity.getString(R.string.reader_page_turn_animation_slide),
            activity.getString(R.string.reader_page_turn_animation_curl),
        )
        val pageTurnValues = listOf(
            net.mrphan.calibre.reader.epub.PageTurnAnimation.NONE,
            net.mrphan.calibre.reader.epub.PageTurnAnimation.SLIDE,
            net.mrphan.calibre.reader.epub.PageTurnAnimation.CURL,
        )
        fun currentPageTurnLabel(): String {
            val index = pageTurnValues.indexOf(activity.getPageTurnAnimation()).coerceAtLeast(0)
            return pageTurnLabels[index]
        }
        lateinit var pageTurnRowValueLabel: android.widget.TextView
        val pageTurnRow = ReaderSettingsRowBinder.addLinkRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_page_turn_animation_label,
            value = currentPageTurnLabel(),
        ) {
            androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle(R.string.reader_page_turn_animation_label)
                .setItems(pageTurnLabels) { _, which ->
                    activity.setPageTurnAnimation(pageTurnValues[which])
                    // Cap nhat lai nhan hien thi tren hang -- xem cach lam tuong tu
                    // refreshVoiceLabel trong TtsPlayerBottomSheet.kt.
                    pageTurnRowValueLabel.text = currentPageTurnLabel()
                }
                .show()
        }
        pageTurnRowValueLabel = pageTurnRow.findViewById(R.id.reader_settings_row_value)
        // Hieu ung lat trang chi co y nghia o che do phan trang (paginated) -- xem prompt muc 1;
        // an hang nay khi dang o scroll mode. Page nay duoc tao lai moi lan tab duoc mo (xem
        // ReaderSettingsSheet.showTab()), nen doc lai isScrollModeEnabled() ngay trong
        // onCreateView la du de "refresh" khi nguoi dung quay lai tab nay tu tab khac, khong can
        // them 1 co che callback rieng.
        pageTurnRow.visibility = if (activity.isScrollModeEnabled()) View.GONE else View.VISIBLE

        // --- Phim am luong lat trang -------------------------------------------------------
        ReaderSettingsRowBinder.addGroupHeader(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_settings_group_volume_keys,
        )
        lateinit var volumeKeyInvertRow: View
        val volumeKeyEnabledRow = ReaderSettingsRowBinder.addSwitchRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_settings_volume_key_page_turn,
            initialValue = activity.volumeKeyPageTurnEnabled,
        ) { checked ->
            activity.setVolumeKeyPageTurnEnabled(checked)
            setDescendantsEnabled(volumeKeyInvertRow, checked)
        }
        volumeKeyInvertRow = ReaderSettingsRowBinder.addSwitchRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_settings_volume_key_invert,
            initialValue = activity.volumeKeyPageTurnInverted,
        ) { checked ->
            activity.setVolumeKeyPageTurnInverted(checked)
        }
        setDescendantsEnabled(volumeKeyInvertRow, activity.volumeKeyPageTurnEnabled)

        // --- Vung cham lat trang -------------------------------------------------------------
        ReaderSettingsRowBinder.addGroupHeader(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_settings_group_edge_tap,
        )
        lateinit var edgeTapInvertRow: View
        val edgeTapEnabledRow = ReaderSettingsRowBinder.addSwitchRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_settings_edge_tap_page_turn,
            initialValue = activity.edgeTapPageTurnEnabled,
        ) { checked ->
            activity.setEdgeTapPageTurnEnabled(checked)
            setDescendantsEnabled(edgeTapInvertRow, checked)
        }
        edgeTapInvertRow = ReaderSettingsRowBinder.addSwitchRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_settings_edge_tap_invert,
            initialValue = activity.edgeTapPageTurnInverted,
        ) { checked ->
            activity.setEdgeTapPageTurnInverted(checked)
        }
        setDescendantsEnabled(edgeTapInvertRow, activity.edgeTapPageTurnEnabled)

        return root
    }

    /** `View.isEnabled = false` tren 1 container khong tu dong lam "mo" cac view con ben trong o
     * 1 LinearLayout thuong -- phai ap dung cho tung view con thi Switch ben trong moi thuc su
     * ngung phan hoi cham, giong het setDescendantsEnabled trong ReaderSettingsLayoutPage.kt. */
    private fun setDescendantsEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                setDescendantsEnabled(view.getChildAt(i), enabled)
            }
        }
    }
}
