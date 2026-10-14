package net.mrphan.calibre.reader.chrome.settings

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import net.mrphan.calibre.R
import net.mrphan.calibre.reader.chrome.ReaderChromeHost

/**
 * Tab "Sync" -- mục cài đặt duy nhất theo yêu cầu gốc: chu kỳ tự động đồng bộ
 * (mặc định 3 phút, 0 = tắt). Đọc/ghi qua ReaderChromeHost.autoSyncIntervalMinutes()/
 * setAutoSyncIntervalMinutes() (EpubReaderActivity implement bằng
 * ReaderSyncSettingsStore -- xem prompt-nang-cap-reader-sync.md mục 2 và 5).
 */
internal class ReaderSettingsSyncPage(
    private val host: ReaderChromeHost,
    private val context: Context,
) : ReaderSettingsPage {

    private val intervalOptionsMinutes = intArrayOf(0, 1, 3, 5, 10, 15)

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup): View {
        val root = inflater.inflate(R.layout.page_reader_settings_sync, parent, false)
        val card = root.findViewById<LinearLayout>(R.id.reader_settings_sync_card)

        if (!host.isProgressSyncAvailable()) {
            ReaderSettingsRowBinder.addGroupHeader(
                card, inflater, R.string.reader_sync_no_cloud_connected_hint,
            )
            return root
        }

        fun labelFor(minutes: Int) = if (minutes == 0) {
            context.getString(R.string.reader_sync_interval_off)
        } else {
            context.getString(R.string.reader_sync_interval_minutes, minutes)
        }

        lateinit var valueLabel: TextView
        val row = ReaderSettingsRowBinder.addLinkRow(
            container = card,
            inflater = inflater,
            titleRes = R.string.reader_settings_sync_interval,
            value = labelFor(host.autoSyncIntervalMinutes()),
        ) {
            val labels = intervalOptionsMinutes.map { labelFor(it) }.toTypedArray()
            val current = intervalOptionsMinutes.indexOf(host.autoSyncIntervalMinutes()).coerceAtLeast(0)
            AlertDialog.Builder(context)
                .setTitle(R.string.reader_settings_sync_interval)
                .setSingleChoiceItems(labels, current) { dialog, which ->
                    host.setAutoSyncIntervalMinutes(intervalOptionsMinutes[which])
                    valueLabel.text = labelFor(intervalOptionsMinutes[which])
                    dialog.dismiss()
                }
                .show()
        }
        valueLabel = row.findViewById(R.id.reader_settings_row_value)

        return root
    }
}
