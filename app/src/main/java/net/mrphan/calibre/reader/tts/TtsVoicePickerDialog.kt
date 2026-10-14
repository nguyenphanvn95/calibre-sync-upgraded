package net.mrphan.calibre.reader.tts

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mrphan.calibre.Logger
import net.mrphan.calibre.R

/** Cau mau co dinh de preview giong doc -- ngan, tu nhien, dung duoc cho ca giong Bac/Nam. */
private const val PREVIEW_SAMPLE_TEXT = "Xin chào, đây là giọng đọc mẫu."

/**
 * "Tier 3" of the read-aloud UI: the "Select Voice" list, grouped by engine (see
 * [TtsEngineRegistry.buildVoiceGroups]), reached from [TtsPlayerBottomSheet]'s "Giọng đọc" pill.
 */
object TtsVoicePickerDialog {

    private sealed class Row {
        data class Header(val group: TtsVoiceGroup) : Row()
        data class VoiceRow(val voice: TtsVoice) : Row()
    }

    /** @param onVoiceChanged invoked after the user picks a voice, so the caller (the bottom
     * sheet's pill label) can refresh itself. */
    fun show(activity: Activity, controller: TtsController, onVoiceChanged: () -> Unit = {}) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_tts_voice_picker, null, false)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.reader_tts_voice_picker_title)
            .setView(view)
            .setNegativeButton(R.string.reader_chrome_close, null)
            .create()

        val listView = view.findViewById<ListView>(R.id.tts_voice_picker_list)
        val settingsStore = TtsSettingsStore(activity.applicationContext)

        (activity as AppCompatActivity).lifecycleScope.launch {
            val groups = TtsEngineRegistry.buildVoiceGroups(activity)
            val currentEngineId = settingsStore.getSelectedEngineId() ?: TtsEngineRegistry.default().id
            val currentVoiceId = settingsStore.getSelectedVoiceId(currentEngineId)

            val rows = mutableListOf<Row>()
            for (group in groups) {
                rows += Row.Header(group)
                for (voice in group.voices) rows += Row.VoiceRow(voice)
            }
            val adapter = object : BaseAdapter() {
                override fun getCount() = rows.size
                override fun getItem(position: Int) = rows[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getViewTypeCount() = 2
                override fun getItemViewType(position: Int) =
                    if (rows[position] is Row.Header) 0 else 1
                override fun isEnabled(position: Int) = rows[position] is Row.VoiceRow

                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    return when (val row = rows[position]) {
                        is Row.Header -> {
                            val v = convertView ?: LayoutInflater.from(activity)
                                .inflate(R.layout.item_tts_voice_group_header, parent, false)
                            v.findViewById<TextView>(R.id.tts_voice_group_header_text).text =
                                activity.getString(
                                    R.string.reader_tts_voice_group_header_format,
                                    row.group.engineDisplayName,
                                    row.group.voices.size,
                                )
                            v
                        }
                        is Row.VoiceRow -> {
                            val v = convertView ?: LayoutInflater.from(activity)
                                .inflate(R.layout.item_tts_voice, parent, false)
                            v.findViewById<TextView>(R.id.tts_voice_item_name).text = row.voice.displayName
                            val isSelected = row.voice.engineId == currentEngineId && row.voice.id == currentVoiceId
                            v.findViewById<ImageView>(R.id.tts_voice_item_check).visibility =
                                if (isSelected) View.VISIBLE else View.INVISIBLE
                            // MOI: nut nghe thu giong doc -- tao/khoi tao 1 engine TAM THOI rieng
                            // cho tung lan bam (xem playPreview), khong dung engine dang phuc vu
                            // phien doc chinh.
                            v.findViewById<ImageButton>(R.id.tts_voice_item_preview).setOnClickListener {
                                (activity as AppCompatActivity).lifecycleScope.launch {
                                    playPreview(activity, row.voice)
                                }
                            }
                            v
                        }
                    }
                }
            }
            listView.adapter = adapter
            listView.setOnItemClickListener { _, _, position, _ ->
                val row = rows.getOrNull(position) as? Row.VoiceRow ?: return@setOnItemClickListener
                (activity as AppCompatActivity).lifecycleScope.launch {
                    controller.setVoice(row.voice, activity.applicationContext)
                    onVoiceChanged()
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
    }

    /** Phat cau mau qua dung engine ma [voice] thuoc ve -- tao 1 instance MOI, RIENG
     * ([TtsEngineRegistry.newInstance], KHONG dung [TtsEngineRegistry.byId] vi do la instance
     * DUNG CHUNG voi phien doc chinh) cho MOI lan bam preview, va shutdown() ngay sau khi phat
     * xong (trong finally) -- tranh giu nhieu instance TextToSpeech/AudioTrack/MediaPlayer song
     * song neu nguoi dung bam preview nhieu giong lien tuc. Loi (mang/engine chua san sang) chi
     * log, khong hien Toast -- tranh lam phien nguoi dung dang duyet danh sach, ho co the thu
     * lai bang cach bam preview lan nua. */
    private suspend fun playPreview(activity: Activity, voice: TtsVoice) {
        val previewEngine = TtsEngineRegistry.newInstance(voice.engineId) ?: return
        withContext(Dispatchers.IO) {
            try {
                // QUAN TRONG: phai initialize() TRUOC roi moi kiem tra isAvailable -- voi
                // NghiTtsEngine, isAvailable chi phan anh dung trang thai (importedModels khong
                // rong) SAU KHI initialize() da quet xong thu muc model (xem kdoc cua
                // TtsEngineRegistry.buildVoiceGroups). Instance preview nay MOI duoc tao
                // (TtsEngineRegistry.newInstance), nen importedModels luon rong truoc initialize()
                // -- kiem tra isAvailable truoc do se luon false va return som, khien preview
                // NghiTTS khong bao gio phat duoc am thanh.
                previewEngine.initialize(activity.applicationContext)
                if (!previewEngine.isAvailable) return@withContext
                previewEngine.setVoice(voice)
                previewEngine.speak(PREVIEW_SAMPLE_TEXT, utteranceId = "preview-${voice.id}")
            } catch (e: Exception) {
                Logger.e(e)
            } finally {
                try { previewEngine.shutdown() } catch (_: Exception) {}
            }
        }
    }
}
