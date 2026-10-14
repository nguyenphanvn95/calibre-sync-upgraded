package net.mrphan.calibre.reader.tts

import android.app.Activity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.Slider
import net.mrphan.calibre.R

/**
 * "Advanced Speed Settings" cho Doc cho nghe -- object voi static `show()`, cung pattern nhu
 * `net.mrphan.calibre.reader.tts.music.BackgroundMusicSettingsDialog` va [TtsPlayerBottomSheet]
 * (khong dung `BottomSheetDialogFragment` de khong phai lam them plumbing FragmentManager, nhat
 * quan voi cach cac bottom sheet khac trong app dang lam).
 *
 * Thay the `TtsPlayerBottomSheet.showSpeedPicker()` cu (AlertDialog liet ke 8 muc toc do co
 * dinh) bang 3 slider lien tuc:
 * - Toc do doc 0.5x-3.0x, buoc 0.05 -- ap dung ngay lap tuc qua [TtsController.setSpeed].
 * - Khoang nghi giua cau (Sentence Pause) 0-600ms va khoang nghi giua doan (Paragraph Pause)
 *   0-2000ms -- luu vao [TtsSettingsStore], KHONG nhan theo toc do doc (xem
 *   [TtsController.pauseBeforeSentence] -- sua dung loi mo ta trong readest#2837 "FR: TTS long
 *   pauses", noi pause bi keo gian ty le theo rate o cac ban Readest cu).
 */
object TtsSpeedSettingsBottomSheet {

    fun show(activity: Activity, controller: TtsController, settingsStore: TtsSettingsStore) {
        val view = LayoutInflater.from(activity)
            .inflate(R.layout.bottomsheet_tts_speed_settings, null, false)
        val dialog = BottomSheetDialog(activity, R.style.ThemeOverlay_App_FullScreenBottomSheet)
        dialog.setContentView(view)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        // FIX (cung pattern voi BackgroundMusicSettingsDialog/TtsPlayerBottomSheet): ep
        // FrameLayout noi bo cua BottomSheetDialog gian rong toi da, tranh cac slider bi do sai
        // chieu rong o lan layout dau tien.
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet,
            )
            bottomSheet?.let {
                it.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                it.requestLayout()
                BottomSheetBehavior.from(it).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                }
            }
        }

        val speedSlider = view.findViewById<Slider>(R.id.tts_speed_slider)
        val speedValue = view.findViewById<TextView>(R.id.tts_speed_value)
        val sentencePauseSlider = view.findViewById<Slider>(R.id.tts_sentence_pause_slider)
        val sentencePauseValue = view.findViewById<TextView>(R.id.tts_sentence_pause_value)
        val paragraphPauseSlider = view.findViewById<Slider>(R.id.tts_paragraph_pause_slider)
        val paragraphPauseValue = view.findViewById<TextView>(R.id.tts_paragraph_pause_value)

        // Toc do hien tai doc tu controller.state (chinh xac hon settingsStore trong truong hop
        // hiem gap engine dang phat co rate khac voi gia tri da luu, vd ngay sau khi doi engine)
        // -- cung nguon ma TtsPlayerBottomSheet.bind() dang dung de hien speedValue tren pill.
        speedSlider.value = controller.state.value.speechRate.coerceIn(
            speedSlider.valueFrom, speedSlider.valueTo,
        )
        speedValue.text = activity.getString(R.string.reader_tts_speed_precise_value_format, speedSlider.value)

        sentencePauseSlider.value = settingsStore.getSentencePauseMs().toFloat()
            .coerceIn(sentencePauseSlider.valueFrom, sentencePauseSlider.valueTo)
        sentencePauseValue.text = activity.getString(
            R.string.reader_tts_pause_ms_format, sentencePauseSlider.value.toInt(),
        )

        paragraphPauseSlider.value = settingsStore.getParagraphPauseMs().toFloat()
            .coerceIn(paragraphPauseSlider.valueFrom, paragraphPauseSlider.valueTo)
        paragraphPauseValue.text = activity.getString(
            R.string.reader_tts_pause_ms_format, paragraphPauseSlider.value.toInt(),
        )

        speedSlider.addOnChangeListener { _, value, fromUser ->
            speedValue.text = activity.getString(R.string.reader_tts_speed_precise_value_format, value)
            if (fromUser) controller.setSpeed(value)
        }
        sentencePauseSlider.addOnChangeListener { _, value, fromUser ->
            sentencePauseValue.text = activity.getString(R.string.reader_tts_pause_ms_format, value.toInt())
            if (fromUser) settingsStore.saveSentencePauseMs(value.toInt())
        }
        paragraphPauseSlider.addOnChangeListener { _, value, fromUser ->
            paragraphPauseValue.text = activity.getString(R.string.reader_tts_pause_ms_format, value.toInt())
            if (fromUser) settingsStore.saveParagraphPauseMs(value.toInt())
        }

        view.findViewById<ImageButton>(R.id.tts_speed_settings_back).setOnClickListener { dialog.dismiss() }

        dialog.show()
    }
}
