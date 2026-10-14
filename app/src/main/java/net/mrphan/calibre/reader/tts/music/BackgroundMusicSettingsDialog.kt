package net.mrphan.calibre.reader.tts.music

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import net.mrphan.calibre.R
import net.mrphan.calibre.reader.music.YouTubeAudioResolver

/**
 * Cài đặt nhạc nền cho "Đọc cho nghe" -- object với static `show()`, cùng pattern như
 * [net.mrphan.calibre.reader.tts.TtsPlayerBottomSheet]. Việc chọn file dùng Storage Access
 * Framework ([Intent.ACTION_OPEN_DOCUMENT] qua [ActivityResultContracts.OpenDocument]) thay vì
 * xin quyền đọc bộ nhớ toàn cục, vì app đã hạn chế xin thêm quyền runtime mới nếu SAF đủ dùng.
 */
object BackgroundMusicSettingsDialog {

    fun show(activity: Activity, musicController: BackgroundMusicController) {
        val store = BackgroundMusicSettingsStore(activity.applicationContext)
        val view = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_tts_background_music, null, false)
        val dialog = BottomSheetDialog(activity, R.style.ThemeOverlay_App_FullScreenBottomSheet)
        dialog.setContentView(view)
        dialog.behavior.peekHeight = activity.resources.displayMetrics.heightPixels
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        // FIX: ép FrameLayout nội bộ của BottomSheetDialog giãn MATCH_PARENT ngay khi hiển thị,
        // nếu không RecyclerView 0dp bên trong sẽ bị đo cao 0px ở lần layout đầu tiên.
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

        val enabledSwitch = view.findViewById<SwitchCompat>(R.id.bg_music_enabled_switch)
        val volumeSeekBar = view.findViewById<SeekBar>(R.id.bg_music_volume_seekbar)
        val volumeLabel = view.findViewById<TextView>(R.id.bg_music_volume_label)
        val listHeader = view.findViewById<TextView>(R.id.bg_music_list_header)
        val recyclerView = view.findViewById<RecyclerView>(R.id.bg_music_list)
        val emptyState = view.findViewById<TextView>(R.id.bg_music_empty_state)

        fun refreshHeader(count: Int) {
            listHeader.text = activity.getString(
                R.string.reader_tts_background_music_list_header, count,
            )
            emptyState.visibility = if (count == 0) android.view.View.VISIBLE else android.view.View.GONE
            recyclerView.visibility = if (count == 0) android.view.View.GONE else android.view.View.VISIBLE
        }

        val tracks = store.getTracks().toMutableList()
        val adapter = BackgroundMusicTrackAdapter(
            tracks = tracks,
            onRemove = { track ->
                store.removeTrack(track.uri)
                refreshHeader(tracks.size)
            },
        )
        recyclerView.layoutManager = LinearLayoutManager(activity)
        recyclerView.adapter = adapter

        enabledSwitch.isChecked = store.isEnabled()
        enabledSwitch.setOnCheckedChangeListener { _, isChecked -> store.setEnabled(isChecked) }

        volumeSeekBar.progress = store.getVolumePercent()
        volumeLabel.text = activity.getString(
            R.string.reader_tts_background_music_volume_format, store.getVolumePercent(),
        )
        volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                volumeLabel.text = activity.getString(
                    R.string.reader_tts_background_music_volume_format, progress,
                )
                if (fromUser) {
                    store.setVolumePercent(progress)
                    musicController.applyVolumeNow()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        refreshHeader(tracks.size)

        view.findViewById<MaterialButton>(R.id.bg_music_add_file_button).setOnClickListener {
            (activity as? AppCompatActivity)?.let { host ->
                pickAudioFile(host, store, adapter) { refreshHeader(tracks.size) }
            }
        }
        view.findViewById<MaterialButton>(R.id.bg_music_add_youtube_button).setOnClickListener {
            showYouTubeLinkDialog(activity, store, adapter) { refreshHeader(tracks.size) }
        }

        view.findViewById<ImageButton>(R.id.bg_music_back).setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    /** Dùng ACTION_OPEN_DOCUMENT (không phải GET_CONTENT) để có thể gọi
     * contentResolver.takePersistableUriPermission(...) và giữ quyền đọc file qua các lần mở app
     * sau -- GET_CONTENT không đảm bảo quyền tồn tại lâu dài. */
    private fun pickAudioFile(
        activity: AppCompatActivity,
        store: BackgroundMusicSettingsStore,
        adapter: BackgroundMusicTrackAdapter,
        onAdded: () -> Unit,
    ) {
        lateinit var launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>
        launcher = activity.activityResultRegistry.register(
            "bg_music_pick_${System.currentTimeMillis()}",
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) return@register
            activity.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            val name = queryDisplayName(activity, uri) ?: uri.lastPathSegment.orEmpty()
            val track = BackgroundMusicTrack(uri, name)
            store.addTrack(track)
            adapter.addTrack(track)
            onAdded()
        }
        launcher.launch(arrayOf("audio/*"))
    }

    private fun queryDisplayName(activity: Activity, uri: android.net.Uri): String? {
        activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) return cursor.getString(nameIndex)
        }
        return null
    }

    private fun showYouTubeLinkDialog(
        activity: Activity,
        store: BackgroundMusicSettingsStore,
        adapter: BackgroundMusicTrackAdapter,
        onAdded: () -> Unit,
    ) {
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.reader_tts_background_music_youtube_hint)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.reader_tts_background_music_youtube_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val url = input.text?.toString()?.trim().orEmpty()
                if (!YouTubeAudioResolver.isYouTubeUrl(url)) {
                    android.widget.Toast.makeText(
                        activity,
                        R.string.reader_tts_background_music_youtube_invalid,
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    return@setPositiveButton
                }
                val track = BackgroundMusicTrack(
                    uri = Uri.parse(url),
                    displayName = url,
                )
                store.addTrack(track)
                adapter.addTrack(track)
                onAdded()
            }
            .show()
    }
}
