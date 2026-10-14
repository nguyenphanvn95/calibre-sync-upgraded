package net.mrphan.calibre.reader.epub.toc

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mrphan.calibre.Logger
import net.mrphan.calibre.R
import net.mrphan.calibre.ZoomableImageView
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.getOrElse
import java.io.File
import java.io.FileOutputStream

/**
 * "Image Tool": xem toan man hinh + zoom (pinch va nut +/-) + tai xuong + chia se + chuyen
 * anh ke tiep/truoc do, duyet qua TOAN BO anh trong sach (khong chi anh trong 1 chuong). Thay the
 * ReaderTocImageViewerDialog cu (chi xem 1 anh, khong next/prev, khong share, khong nut zoom).
 *
 * Mo tu 2 noi: ReaderTocImagesPage (tab "Anh trong sach" trong Muc luc) va
 * ReaderImageTapWatcher (cham truc tiep vao anh dang doc trong noi dung chuong).
 */
class ReaderImageToolDialog(
    private val publication: Publication,
    private val entries: List<EpubImageEntry>,
    private val startIndex: Int,
) : DialogFragment() {

    override fun getTheme() = R.style.Theme_Calibre_FullScreenDialog

    private lateinit var pager: ViewPager2
    private lateinit var counter: TextView
    private lateinit var adapter: PageAdapter
    // Cache bytes anh da tai, key la index trong `entries` -- tranh tai lai khi vuot qua lai anh cu.
    private val bytesCache = HashMap<Int, ByteArray>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = inflater.inflate(R.layout.dialog_reader_image_tool, container, false)
        pager = view.findViewById(R.id.image_tool_pager)
        counter = view.findViewById(R.id.image_tool_counter)
        val btnClose = view.findViewById<ImageButton>(R.id.image_tool_close)
        val btnDownload = view.findViewById<ImageButton>(R.id.image_tool_download)
        val btnShare = view.findViewById<ImageButton>(R.id.image_tool_share)
        val btnZoomIn = view.findViewById<ImageButton>(R.id.image_tool_zoom_in)
        val btnZoomOut = view.findViewById<ImageButton>(R.id.image_tool_zoom_out)
        val btnPrev = view.findViewById<ImageButton>(R.id.image_tool_prev)
        val btnNext = view.findViewById<ImageButton>(R.id.image_tool_next)

        adapter = PageAdapter()
        pager.adapter = adapter
        pager.setCurrentItem(startIndex.coerceIn(0, entries.size - 1), false)
        updateCounter(pager.currentItem)
        updateNavButtonsEnabled(pager.currentItem, btnPrev, btnNext)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateCounter(position)
                updateNavButtonsEnabled(position, btnPrev, btnNext)
            }
        })

        btnClose.setOnClickListener { dismiss() }
        btnPrev.setOnClickListener {
            if (pager.currentItem > 0) pager.setCurrentItem(pager.currentItem - 1, true)
        }
        btnNext.setOnClickListener {
            if (pager.currentItem < entries.size - 1) pager.setCurrentItem(pager.currentItem + 1, true)
        }
        btnZoomIn.setOnClickListener { currentPageImageView()?.zoomIn() }
        btnZoomOut.setOnClickListener { currentPageImageView()?.zoomOut() }
        btnDownload.setOnClickListener { downloadCurrent() }
        btnShare.setOnClickListener { shareCurrent() }
        return view
    }

    private fun updateCounter(position: Int) {
        counter.text = getString(R.string.reader_image_tool_counter, position + 1, entries.size)
    }

    private fun updateNavButtonsEnabled(position: Int, btnPrev: ImageButton, btnNext: ImageButton) {
        btnPrev.isEnabled = position > 0
        btnPrev.alpha = if (btnPrev.isEnabled) 1f else 0.3f
        btnNext.isEnabled = position < entries.size - 1
        btnNext.alpha = if (btnNext.isEnabled) 1f else 0.3f
    }

    /** Tim ZoomableImageView cua trang dang hien thi trong ViewPager2 -- ViewPager2 dung
     * RecyclerView ben trong, nen phai lay qua findViewHolderForAdapterPosition thay vi
     * pager.getChildAt(). */
    private fun currentPageImageView(): ZoomableImageView? {
        val recycler = pager.getChildAt(0) as? RecyclerView ?: return null
        val holder = recycler.findViewHolderForAdapterPosition(pager.currentItem) ?: return null
        return holder.itemView.findViewById(R.id.image_tool_page_image)
    }

    private fun downloadCurrent() {
        val index = pager.currentItem
        val entry = entries[index]
        lifecycleScope.launch {
            val bytes = bytesCache[index] ?: loadBytes(entry) ?: run {
                Logger.d("ImageTool: download failed, no bytes for index=$index")
                return@launch
            }
            ReaderImageSaver.saveToDownloads(requireContext(), bytes, entry.displayName)
        }
    }

    private fun shareCurrent() {
        val index = pager.currentItem
        val entry = entries[index]
        lifecycleScope.launch {
            val bytes = bytesCache[index] ?: loadBytes(entry) ?: run {
                Logger.d("ImageTool: share failed, no bytes for index=$index")
                return@launch
            }
            val context = context ?: return@launch
            try {
                // Ghi tam vao cacheDir/images/ roi lay content:// Uri qua FileProvider -- giong
                // dung pattern ShareUtils.java, nhung file_paths.xml can them <cache-path> (xem
                // ReaderImageToolDialog.shareCurrent()), vi ShareUtils truoc gio chi share file da
                // nam san tren storage, chua bao gio share file tam trong cacheDir.
                val dir = File(context.cacheDir, "images").apply { mkdirs() }
                val safeName = entry.displayName.ifBlank { "image.jpg" }
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                val file = File(dir, safeName)
                withContext(Dispatchers.IO) {
                    FileOutputStream(file).use { it.write(bytes) }
                }
                val authority = context.packageName + ".fileprovider"
                val uri = FileProvider.getUriForFile(context, authority, file)
                val mime = when {
                    safeName.endsWith(".png", true) -> "image/png"
                    safeName.endsWith(".gif", true) -> "image/gif"
                    safeName.endsWith(".webp", true) -> "image/webp"
                    else -> "image/jpeg"
                }
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.dialog_share_chooser_title)))
            } catch (e: Exception) {
                Logger.e(e)
            }
        }
    }

    private suspend fun loadBytes(entry: EpubImageEntry): ByteArray? {
        val bytes = withContext(Dispatchers.IO) {
            publication.get(entry.link)?.read()?.getOrElse { ByteArray(0) }
        }
        if (bytes == null || bytes.isEmpty()) return null
        bytesCache[entry.index] = bytes
        return bytes
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val imageView: ZoomableImageView = view.findViewById(R.id.image_tool_page_image)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_image_tool_page, parent, false)
            return VH(view)
        }

        override fun getItemCount() = entries.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = entries[position]
            holder.imageView.setImageDrawable(null)
            holder.imageView.resetZoom()
            lifecycleScope.launch {
                val bytes = bytesCache[position] ?: loadBytes(entry) ?: return@launch
                val bmp = withContext(Dispatchers.IO) {
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
                if (bmp != null && holder.bindingAdapterPosition == position) {
                    holder.imageView.setImageBitmap(bmp)
                    holder.imageView.resetZoom()
                }
            }
        }
    }

    companion object {
        const val TAG = "ReaderImageToolDialog"
    }
}
