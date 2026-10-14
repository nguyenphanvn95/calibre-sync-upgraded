package net.mrphan.calibre

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import net.mrphan.calibre.reader.tts.TtsChapterProgressStore
import org.readium.r2.shared.publication.Link

/**
 * Mỗi dòng trong danh sách chương hiển thị số thứ tự, tên chương, nhãn "Tiến độ: NN%" nếu có
 * (ẩn nhãn nếu chưa nghe) và icon tai nghe bên phải -- xem ảnh mẫu trong "Nâng cấp TTS chapter"
 * feature notes.
 */
class TtsChapterAdapter(
    private var items: List<Link>,
    private val onClick: (Link) -> Unit,
) : RecyclerView.Adapter<TtsChapterAdapter.ViewHolder>() {

    private var progressMap: Map<String, TtsChapterProgressStore.ChapterProgress> = emptyMap()
    private var activeChapterHref: String? = null

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val index: TextView = view.findViewById(R.id.tts_chapter_row_index)
        val title: TextView = view.findViewById(R.id.tts_chapter_row_title)
        val progress: TextView = view.findViewById(R.id.tts_chapter_row_progress)
        val icon: ImageView = view.findViewById(R.id.tts_chapter_row_icon)
        val defaultIndexColor: Int = index.currentTextColor
        val defaultTitleColor: Int = title.currentTextColor
        val defaultProgressColor: Int = progress.currentTextColor
        val defaultIconTint = ImageViewCompat.getImageTintList(icon)
    }

    fun submit(newItems: List<Link>, newProgress: Map<String, TtsChapterProgressStore.ChapterProgress>) {
        items = newItems
        progressMap = newProgress
        notifyDataSetChanged()
    }

    fun updateProgress(newProgress: Map<String, TtsChapterProgressStore.ChapterProgress>) {
        progressMap = newProgress
        notifyDataSetChanged()
    }

    fun updateActiveChapter(href: String?) {
        if (activeChapterHref == href) return
        activeChapterHref = href
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tts_chapter_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val link = items[position]
        val isActive = link.href.toString() == activeChapterHref
        val activeColor = ContextCompat.getColor(holder.itemView.context, R.color.color_progress_trend_up)
        holder.index.text = (position + 1).toString()
        holder.title.text = link.title ?: link.href.toString()
        val progress = progressMap[link.href.toString()]
        if (progress != null) {
            holder.progress.visibility = View.VISIBLE
            holder.progress.text = holder.progress.context.getString(
                R.string.tts_chapter_progress_format, progress.percent,
            )
        } else {
            holder.progress.visibility = View.GONE
        }
        if (isActive) {
            holder.index.setTextColor(activeColor)
            holder.title.setTextColor(activeColor)
            holder.progress.setTextColor(activeColor)
            ImageViewCompat.setImageTintList(holder.icon, android.content.res.ColorStateList.valueOf(activeColor))
        } else {
            holder.index.setTextColor(holder.defaultIndexColor)
            holder.title.setTextColor(holder.defaultTitleColor)
            holder.progress.setTextColor(holder.defaultProgressColor)
            ImageViewCompat.setImageTintList(holder.icon, holder.defaultIconTint)
        }
        holder.itemView.setOnClickListener { onClick(link) }
    }

    override fun getItemCount(): Int = items.size
}
