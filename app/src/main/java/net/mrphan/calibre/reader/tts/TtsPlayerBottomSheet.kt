package net.mrphan.calibre.reader.tts

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.coroutines.launch
import net.mrphan.calibre.EpubReaderActivity
import net.mrphan.calibre.R
import net.mrphan.calibre.TtsChapterListActivity
import net.mrphan.calibre.reader.tts.dictionary.TtsDictionaryManagerActivity
import net.mrphan.calibre.reader.tts.music.BackgroundMusicSettingsDialog
import net.mrphan.calibre.reader.tts.nghi.SherpaTtsSettingsDialog
import java.io.File

/**
 * Full "Now playing" style player cho "Đọc cho nghe", opened by tapping [TtsMiniPlayerView].
 * Renders cover art, chapter title, the sentence currently being read, a continuous progress
 * bar, and utility pills (sleep timer, speed, voice, background music).
 *
 * Takes [TtsPlaybackService] (not just [TtsController]) because the background-music pill needs
 * `service.musicController`, and the notification/cover art also live on the Service now -- see
 * [EpubReaderActivity]'s service-binding kdoc for why ownership moved there.
 */
object TtsPlayerBottomSheet {

    fun show(
        activity: Activity,
        ttsService: TtsPlaybackService,
        isAudioSession: Boolean = false,
        onBackInAudioSession: (() -> Unit)? = null,
    ): BottomSheetDialog {
        val controller = ttsService.ttsController
        val view = android.view.LayoutInflater.from(activity)
            .inflate(R.layout.dialog_tts_player, null, false)
        val dialog = BottomSheetDialog(activity, R.style.ThemeOverlay_App_FullScreenBottomSheet)
        dialog.setContentView(view)
        dialog.behavior.peekHeight = activity.resources.displayMetrics.heightPixels
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        // FIX (phòng ngừa, cùng pattern với BackgroundMusicSettingsDialog): ép FrameLayout nội
        // bộ của BottomSheetDialog giãn MATCH_PARENT ngay khi hiển thị, tránh trường hợp view con
        // dùng chiều cao 0dp bị đo cao 0px ở lần layout đầu tiên.
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet,
            )
            bottomSheet?.let {
                it.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                it.requestLayout()
                BottomSheetBehavior.from(it).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                }
            }
        }

        val coverImage = view.findViewById<ImageView>(R.id.tts_player_cover)
        val titleText = view.findViewById<TextView>(R.id.tts_player_title)
        val chapterTitleText = view.findViewById<TextView>(R.id.tts_player_chapter_title)
        val sentenceText = view.findViewById<TextView>(R.id.tts_player_sentence_text)
        val progressBar = view.findViewById<SeekBar>(R.id.tts_player_progress_bar)
        val progressText = view.findViewById<TextView>(R.id.tts_player_progress)
        val voiceNameLabel = view.findViewById<TextView>(R.id.tts_player_voice_name_label)
        val playPauseButton = view.findViewById<ImageButton>(R.id.tts_player_play_pause)
        val prevButton = view.findViewById<ImageButton>(R.id.tts_player_prev)
        val nextButton = view.findViewById<ImageButton>(R.id.tts_player_next)
        val speedValue = view.findViewById<TextView>(R.id.tts_player_speed_value)
        val voiceValue = view.findViewById<TextView>(R.id.tts_player_voice_value)
        val sleepTimerValue = view.findViewById<TextView>(R.id.tts_player_sleep_timer_value)
        val loadingProgress = view.findViewById<android.widget.ProgressBar>(R.id.tts_player_loading_progress)

        titleText.text = (activity as? EpubReaderActivity)?.bookTitle ?: ttsService.nowPlayingBookTitle

        // Bia sach: fullPlayerCoverBitmap/nowPlayingCover duoc nap BAT DONG BO o EpubReaderActivity
        // (Publication.cover() cua Readium can thoi gian decode). Trong luong "Nghe sach" tu cold
        // start, dialog nay duoc mo NGAY khi service vua bind (xem startAudioSessionIfNeeded()),
        // tuc la THUONG XUYEN truoc khi cover tai xong -- neu chi doc cover MOT LAN o day, ImageView
        // se ket qua "ket dinh" o placeholder vinh vien du cover sau do co san. Fix: thu ap lai cover
        // moi lan bind() chay (bind() da duoc goi lien tuc theo state reactive cua controller, xem
        // ghi chu duoi day), cho toi khi ap thanh cong thi dung lai (khong setImageBitmap thua).
        var coverApplied = false
        fun tryApplyCover() {
            if (coverApplied) return
            val cover = (activity as? EpubReaderActivity)?.fullPlayerCoverBitmap ?: ttsService.nowPlayingCover
            if (cover != null) {
                coverImage.setImageBitmap(cover)
                coverApplied = true
            }
            // else: layout's android:src already points at ic_reader_cover_placeholder, so the
            // ImageView is never left blank while waiting.
        }
        tryApplyCover()

        var userIsDragging = false

        fun bind(state: TtsPlaybackState) {
            tryApplyCover()
            chapterTitleText.text = state.chapterTitle
            sentenceText.text = state.currentSentenceText
            if (!userIsDragging) progressBar.progress = (state.progressFraction * 1000).toInt()
            progressText.text = if (state.sentenceCount > 0) {
                activity.getString(
                    R.string.reader_tts_sentence_progress,
                    state.sentenceIndex + 1,
                    state.sentenceCount,
                )
            } else {
                ""
            }
            playPauseButton.setImageResource(
                if (state.isPlaying) R.drawable.ic_tts_mini_pause else R.drawable.ic_tts_mini_play
            )
            speedValue.text = activity.getString(R.string.reader_tts_speed_value_format, state.speechRate)
            sleepTimerValue.text = state.sleepTimerMinutesRemaining?.let {
                activity.getString(R.string.reader_tts_sleep_timer_active_format, it)
            } ?: activity.getString(R.string.reader_tts_sleep_timer_off)
            // Chuong that su da san sang (co it nhat 1 cau): tat overlay "dang chuan bi" va mo
            // lai cac nut dieu khien phu thuoc du lieu chuong, xem khoi khoi tao ben duoi khi
            // isAudioSession == true.
            if (state.sentenceCount > 0 && loadingProgress.visibility != View.GONE) {
                loadingProgress.visibility = View.GONE
                progressBar.isEnabled = true
                playPauseButton.isEnabled = true
                prevButton.isEnabled = true
                nextButton.isEnabled = true
            }
        }

        fun refreshVoiceLabel() {
            val scope = (activity as AppCompatActivity).lifecycleScope
            scope.launch {
                val groups = TtsEngineRegistry.buildVoiceGroups(activity)
                val settingsStore = TtsSettingsStore(activity.applicationContext)
                val currentEngineId = settingsStore.getSelectedEngineId() ?: TtsEngineRegistry.default().id
                val selectedVoiceId = settingsStore.getSelectedVoiceId(currentEngineId)
                val selectedVoice = groups
                    .firstOrNull { it.engineId == currentEngineId }
                    ?.voices
                    ?.firstOrNull { it.id == selectedVoiceId }
                val label = selectedVoice?.displayName
                    ?: TtsEngineRegistry.byId(currentEngineId)?.displayName.orEmpty()
                voiceValue.text = label
                voiceNameLabel.text = label
            }
        }

        view.findViewById<ImageButton>(R.id.tts_player_back).setOnClickListener { dialog.dismiss() }

        // Yeu cau 1 (prompt-codex-nangcap-now-playing.md): bam ten chuong -> mo danh sach chuong
        // audio cua dung cuon dang phat. Dich den la 1 Activity toan man hinh khac han (khong
        // phai 1 dialog con de len tren nhu cac pill khac), nen dismiss() bottom sheet goc truoc
        // de tranh 2 lop UI chong nhau khi nguoi dung bam back quay lai.
        chapterTitleText.setOnClickListener {
            val bookPath = ttsService.nowPlayingBookPath ?: return@setOnClickListener
            val uuid = ttsService.nowPlayingUuid ?: return@setOnClickListener
            val title = (activity as? EpubReaderActivity)?.bookTitle ?: ttsService.nowPlayingBookTitle
            dialog.dismiss()
            activity.startActivity(
                TtsChapterListActivity.buildIntent(activity, File(bookPath), uuid, title),
            )
        }

        // Yeu cau 2: bam cau dang doc -> mo trang doc thuong (KHONG phai audio-session moi) va
        // ep to sang + cuon toi dung cau do 1 lan, bat ke 2 setting to sang/tu dong cuon dang
        // bat/tat -- xem EpubReaderActivity.focusCurrentTtsSentence().
        sentenceText.setOnClickListener {
            val bookPath = ttsService.nowPlayingBookPath ?: return@setOnClickListener
            val uuid = ttsService.nowPlayingUuid ?: return@setOnClickListener
            val title = (activity as? EpubReaderActivity)?.bookTitle ?: ttsService.nowPlayingBookTitle
            val readerActivity = activity as? EpubReaderActivity
            if (readerActivity != null && readerActivity.currentBookUuid == uuid) {
                // Toi uu (truong hop bien trong prompt): da dung san dung trong reader cua dung
                // cuon sach nay, ke ca dang o che do audio-session -- khong can mo them 1
                // Activity moi, chi can dismiss dialog roi ep to sang ngay tren Activity co san.
                // PHAI xoa onDismissListener TRUOC khi dismiss: neu dang o audio-session,
                // startAudioSessionIfNeeded() da gan onBackInAudioSession = { finish() } cho MOI
                // duong dong dialog -- neu khong xoa, dismiss() o day se vo tinh finish() luon
                // Activity ma ta dinh giu lai.
                dialog.setOnDismissListener(null)
                dialog.dismiss()
                readerActivity.focusCurrentTtsSentence()
            } else {
                dialog.dismiss()
                activity.startActivity(
                    EpubReaderActivity.buildReopenIntent(activity, uuid, bookPath, title)
                        .putExtra(EpubReaderActivity.EXTRA_FOCUS_CURRENT_TTS_SENTENCE, true),
                )
            }
        }

        if (isAudioSession) {
            // Yeu cau 5 cua tinh nang "Nghe sach": khi phien nghe duoc mo tu TtsChapterListActivity,
            // bat ky cach nao thoat khoi man hinh dang phat (nut back trong dialog, vuot xuong de
            // dong bottom sheet, nut back he thong) deu phai dua nguoi dung ve dung danh sach
            // chuong -- dung setOnDismissListener (goi o MOI duong thoat dialog) thay vi gan rieng
            // le vao tung nut, de khong sot truong hop nao.
            dialog.setOnDismissListener { onBackInAudioSession?.invoke() }

            // Dialog duoc mo NGAY khi service da bind, truoc khi engine TTS khoi tao/trich xuat
            // text/bat dau chuong xong (xem prompt-fix-tts-now-playing-mo-truoc-audio-sau.md muc
            // 5): hien overlay "dang chuan bi" va tam khoa cac nut phu thuoc du lieu chuong de
            // nguoi dung khong bam vao state rong/cu trong luc cho; bind() o tren se tu tat overlay
            // va mo lai cac nut nay ngay khi chuong that (co sentenceCount > 0) san sang.
            loadingProgress.visibility = View.VISIBLE
            progressBar.isEnabled = false
            playPauseButton.isEnabled = false
            prevButton.isEnabled = false
            nextButton.isEnabled = false
        }
        progressBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                // Khong lam gi khi khong phai nguoi dung keo -- tranh vong lap voi bind() o tren
                // tu cap nhat lai progressBar.progress moi khi state doi.
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                userIsDragging = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                userIsDragging = false
                val state = controller.state.value
                if (state.sentenceCount > 0) {
                    val targetIndex = ((seekBar.progress / 1000f) * state.sentenceCount)
                        .toInt().coerceIn(0, state.sentenceCount - 1)
                    controller.seekToSentence(targetIndex)
                }
            }
        })
        prevButton.setOnClickListener {
            controller.skipToPreviousSentence()
        }
        nextButton.setOnClickListener {
            controller.skipToNextSentence()
        }
        playPauseButton.setOnClickListener {
            if (controller.state.value.isPlaying) controller.pause() else controller.resume()
        }
        view.findViewById<LinearLayout>(R.id.tts_player_speed_pill).setOnClickListener {
            TtsSpeedSettingsBottomSheet.show(activity, controller, TtsSettingsStore(activity.applicationContext))
        }
        view.findViewById<LinearLayout>(R.id.tts_player_sleep_timer_pill).setOnClickListener {
            showSleepTimerPicker(activity, controller)
        }
        view.findViewById<LinearLayout>(R.id.tts_player_voice_pill).setOnClickListener {
            TtsVoicePickerDialog.show(activity, controller) { refreshVoiceLabel() }
        }
        view.findViewById<LinearLayout>(R.id.tts_player_music_pill).setOnClickListener {
            BackgroundMusicSettingsDialog.show(activity, ttsService.musicController)
        }
        view.findViewById<ImageButton>(R.id.tts_player_music_toggle).setOnClickListener {
            BackgroundMusicSettingsDialog.show(activity, ttsService.musicController)
        }
        view.findViewById<ImageButton>(R.id.tts_player_bookmark).setOnClickListener {
            SherpaTtsSettingsDialog.show(activity)
        }
        view.findViewById<ImageButton>(R.id.tts_player_dictionary).setOnClickListener {
            activity.startActivity(
                android.content.Intent(activity, TtsDictionaryManagerActivity::class.java),
            )
        }

        val scope = (activity as AppCompatActivity).lifecycleScope
        scope.launch {
            controller.state.collect { state -> if (dialog.isShowing) bind(state) }
        }

        bind(controller.state.value)
        refreshVoiceLabel()
        dialog.show()
        return dialog
    }

    private fun showSleepTimerPicker(activity: Activity, controller: TtsController) {
        val options = activity.resources.getStringArray(R.array.reader_tts_sleep_timer_options)
        val minutes = intArrayOf(0, 10, 20, 30, 45, 60)
        AlertDialog.Builder(activity)
            .setTitle(R.string.reader_tts_sleep_timer_label)
            .setItems(options) { _, which ->
                if (minutes[which] == 0) controller.cancelSleepTimer() else controller.startSleepTimer(minutes[which])
            }
            .show()
    }
}
