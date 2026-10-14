package net.mrphan.calibre.reader.tts.music

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import net.mrphan.calibre.R

/**
 * Danh sách file nhạc nền hiển thị trong [BackgroundMusicSettingsDialog] -- xem ảnh 2: mỗi dòng
 * là tên file (2 dòng, ellipsize) + nút thùng rác đỏ để xoá. Danh sách được giữ cục bộ trong
 * adapter (khởi tạo từ [BackgroundMusicSettingsStore.getTracks]) và đồng bộ ngược lại store qua
 * [onRemove], thay vì đọc lại store mỗi lần bind -- đơn giản hơn cho một danh sách thường chỉ vài
 * file.
 */
class BackgroundMusicTrackAdapter(
    private val tracks: MutableList<BackgroundMusicTrack>,
    private val onRemove: (BackgroundMusicTrack) -> Unit,
) : RecyclerView.Adapter<BackgroundMusicTrackAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.bg_music_track_name)
        val remove: ImageButton = view.findViewById(R.id.bg_music_track_remove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_background_music_track, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val track = tracks[position]
        holder.name.text = track.displayName
        holder.remove.setOnClickListener {
            val index = tracks.indexOf(track)
            if (index == -1) return@setOnClickListener
            tracks.removeAt(index)
            notifyItemRemoved(index)
            onRemove(track)
        }
    }

    override fun getItemCount(): Int = tracks.size

    /** Gọi sau khi thêm 1 file mới qua Storage Access Framework, để danh sách cập nhật ngay mà
     * không cần re-tạo cả adapter. */
    fun addTrack(track: BackgroundMusicTrack) {
        tracks.add(track)
        notifyItemInserted(tracks.size - 1)
    }
}
