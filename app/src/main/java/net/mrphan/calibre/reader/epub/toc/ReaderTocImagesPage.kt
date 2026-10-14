package net.mrphan.calibre.reader.epub.toc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mrphan.calibre.R
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.getOrElse

/**
 * Tab "Ảnh trong sách" (yêu cầu 2.4 của prompt-nang-cap-muc-luc-panel-truot.md): lưới thumbnail
 * (3 cột) toàn bộ ảnh bitmap trong EPUB (xem [EpubImageScanner]). Tap một ảnh mở
 * [ReaderImageToolDialog] toàn màn hình, có zoom (pinch + nút bấm), tải xuống, chia sẻ và
 * chuyển ảnh tiếp theo/trước đó qua toàn bộ ảnh trong sách.
 */
class ReaderTocImagesPage(
    private val context: Context,
    private val scope: CoroutineScope,
    private val publication: Publication,
) : ReaderTocPage {

    private val allEntries = EpubImageScanner.scan(publication)
    private var filtered = allEntries
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ImageAdapter
    private val bitmapCache = LruCache<Int, Bitmap>(60)
    private var ascending = true

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup): View {
        if (allEntries.isEmpty()) {
            return TextView(context).apply {
                text = context.getString(R.string.reader_toc_images_empty)
                gravity = Gravity.CENTER
                alpha = 0.6f
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        }
        recycler = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 3)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        adapter = ImageAdapter()
        recycler.adapter = adapter
        return recycler
    }

    override fun onQueryChanged(query: String) {
        // Không có "tiêu đề" cho ảnh ngoài tên file -- lọc theo displayName chứa query.
        filtered = if (query.isBlank()) {
            allEntries
        } else {
            allEntries.filter { it.displayName.contains(query, ignoreCase = true) }
        }
        applySortOrder()
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    // "Sort" cho tab Ảnh = đảo "theo thứ tự xuất hiện trong sách" <-> ngược lại -- xem prompt mục 5.
    override fun onSortRequested() {
        ascending = !ascending
        applySortOrder()
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    private fun applySortOrder() {
        filtered = if (ascending) filtered.sortedBy { it.index } else filtered.sortedByDescending { it.index }
    }

    private inner class ImageAdapter : RecyclerView.Adapter<ImageAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val imageView: ImageView = view.findViewById(R.id.reader_toc_image_thumb)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(context)
                .inflate(R.layout.item_reader_toc_image_thumb, parent, false)
            return VH(view)
        }

        override fun getItemCount() = filtered.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = filtered[position]
            val cached = bitmapCache.get(entry.index)
            holder.imageView.setImageBitmap(cached)
            holder.itemView.setOnClickListener {
                val activity = context as? FragmentActivity ?: return@setOnClickListener
                // `filtered` co the dang sap xep nguoc/loc theo query -- Image Tool phai duyet
                // dung theo TOAN BO anh trong sach (allEntries), khong phai danh sach da
                // loc/sap xep tren man hinh nay, nen tim lai vi tri cua entry trong allEntries
                // thay vi dung `position` cua adapter nay.
                val realIndex = allEntries.indexOfFirst { it.index == entry.index }.coerceAtLeast(0)
                ReaderImageToolDialog(publication, allEntries, realIndex)
                    .show(activity.supportFragmentManager, ReaderImageToolDialog.TAG)
            }
            if (cached == null) {
                scope.launch {
                    val bmp = withContext(Dispatchers.IO) { decodeThumbnail(entry) }
                    if (bmp != null) {
                        bitmapCache.put(entry.index, bmp)
                        // Re-bind chỉ nếu holder vẫn đang hiện đúng item này (tránh ảnh nhảy lung
                        // tung khi RecyclerView đã tái sử dụng view cho item khác lúc bitmap decode
                        // xong).
                        if (holder.bindingAdapterPosition == position) {
                            holder.imageView.setImageBitmap(bmp)
                        }
                    }
                }
            }
        }

        private suspend fun decodeThumbnail(entry: EpubImageEntry): Bitmap? {
            val bytes = publication.get(entry.link)?.read()?.getOrElse { ByteArray(0) } ?: return null
            if (bytes.isEmpty()) return null
            // Thumbnail nhỏ cho lưới 3 cột -- downsample để không decode ảnh gốc độ phân giải cao
            // trên mỗi ô, xem checklist QA của prompt (mục 9) về hiệu năng.
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
    }
}
