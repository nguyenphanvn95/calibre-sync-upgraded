package net.mrphan.calibre.reader.tts.nghi

import android.app.Activity
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import net.mrphan.calibre.R
import net.mrphan.calibre.reader.tts.TtsSettingsStore

/**
 * Dialog "Sherpa TTS" - cho chon so luong CPU (numThreads) ma NghiTtsEngine dung khi tong
 * hop giong, xem NghiTtsEngine#loadCurrentModelIntoSherpa. Mo tu 2 noi:
 * 1) nut ngoi sao tren TtsPlayerBottomSheet (dialog_tts_player.xml, R.id.tts_player_bookmark);
 * 2) hang "Sherpa TTS" trong tab Doc to (ReaderSettingsTtsPage).
 * Cung pattern `object` + `fun show(activity)` nhu TtsVoicePickerDialog/BackgroundMusicSettingsDialog.
 *
 * @param onThreadCountChanged goi lai sau khi nguoi dung doi lua chon, de caller co co hoi
 * reload engine dang chay (neu co phien doc song) ngay lap tuc thay vi doi lan load ke tiep.
 */
object SherpaTtsSettingsDialog {

    fun show(activity: Activity, onThreadCountChanged: (() -> Unit)? = null) {
        val store = TtsSettingsStore(activity.applicationContext)
        val recommended = SherpaThreadRecommender.recommendedThreadCount()
        var selected = store.getSherpaThreadCount() ?: recommended

        val view = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_sherpa_tts_settings, null, false)
        val container = view.findViewById<LinearLayout>(
            R.id.sherpa_settings_thread_options_container,
        )

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.reader_tts_sherpa_settings_title)
            .setView(view)
            .create()

        val optionRows = mutableListOf<android.view.View>()

        fun refreshSelectedState() {
            optionRows.forEachIndexed { index, row ->
                val threads = SherpaThreadRecommender.SELECTABLE_THREAD_COUNTS[index]
                row.isActivated = threads == selected
                row.findViewById<ImageView>(R.id.sherpa_thread_option_check).visibility =
                    if (threads == selected) android.view.View.VISIBLE else android.view.View.INVISIBLE
            }
        }

        for (threads in SherpaThreadRecommender.SELECTABLE_THREAD_COUNTS) {
            val row = LayoutInflater.from(activity)
                .inflate(R.layout.item_sherpa_thread_option, container, false)

            val title = activity.getString(R.string.reader_tts_sherpa_thread_option_format, threads) +
                if (threads == recommended) {
                    activity.getString(R.string.reader_tts_sherpa_thread_recommended_suffix)
                } else {
                    ""
                }
            row.findViewById<TextView>(R.id.sherpa_thread_option_title).text = title

            val descRes = when (threads) {
                1 -> R.string.reader_tts_sherpa_thread_option_1_desc
                2 -> R.string.reader_tts_sherpa_thread_option_2_desc
                3 -> R.string.reader_tts_sherpa_thread_option_3_desc
                else -> R.string.reader_tts_sherpa_thread_option_4_desc
            }
            row.findViewById<TextView>(R.id.sherpa_thread_option_desc).setText(descRes)

            row.findViewById<ImageView>(R.id.sherpa_thread_option_icon).setImageResource(
                if (threads == 1) R.drawable.ic_sherpa_thread_leaf else R.drawable.ic_sherpa_thread_bolt,
            )

            row.setOnClickListener {
                if (selected != threads) {
                    selected = threads
                    store.saveSherpaThreadCount(threads)
                    refreshSelectedState()
                    onThreadCountChanged?.invoke()
                }
            }

            container.addView(row)
            optionRows += row
        }
        refreshSelectedState()

        view.findViewById<TextView>(R.id.sherpa_settings_ack_button).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
}
