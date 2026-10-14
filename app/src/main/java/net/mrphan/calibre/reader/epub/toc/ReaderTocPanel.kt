package net.mrphan.calibre.reader.epub.toc

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CoroutineScope
import net.mrphan.calibre.MetadataDatabaseHelper
import net.mrphan.calibre.R
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/**
 * Controller cho panel "Mục lục" trượt từ trái, thay thế `AlertDialog` cũ của
 * `EpubReaderActivity#openTableOfContents()` (xem prompt-nang-cap-muc-luc-panel-truot.md). Sở
 * hữu `view_reader_toc_panel.xml` (được `<include>` sẵn, GONE, trong `activity_reader_epub.xml`)
 * và chuyển đổi 3 [ReaderTocPage] con theo tab đang chọn -- lặp lại đúng pattern
 * `ReaderSettingsSheet` (thanh tab + FrameLayout container), chỉ khác là host là một View overlay
 * full-screen tự vẽ scrim + animate `translationX` thay vì `BottomSheetDialogFragment`.
 *
 * [toggleBookmarksPanel] của `EpubReaderActivity` cũng trỏ vào đây (mở thẳng tab
 * [ReaderTocTab.BOOKMARKS]) thay vì mở `ReaderBookmarksSheet` riêng, để không tồn tại 2 UI
 * bookmark khác nhau song song (prompt mục 1.2).
 */
class ReaderTocPanel(
    root: View, // = findViewById<FrameLayout>(R.id.reader_toc_panel_root)
    private val context: Context,
    private val scope: CoroutineScope, // lifecycleScope của EpubReaderActivity
    private val mDB: MetadataDatabaseHelper,
    private val uuid: String,
    private val publicationProvider: () -> Publication?,
    private val currentLocatorProvider: () -> Locator?,
    /** Nhảy tới một vị trí đã biết (bookmark) -- dùng `Locator`. */
    private val onJumpTo: (Locator) -> Unit,
    /** Nhảy tới một mục trong Mục lục -- dùng `Link` thẳng (giống hệt cách `AlertDialog` cũ đã
     * gọi `navigatorFragment?.go(link, animated = true)`, tránh phải tự dựng `Locator` thủ công
     * từ một TOC Link không đảm bảo luôn có `mediaType`). */
    private val onJumpToChapterLink: (Link) -> Unit,
    private val onBookmarksChanged: () -> Unit,
) {
    private val root: View = root
    private val scrim: View = root.findViewById(R.id.reader_toc_panel_scrim)
    private val panelContainer: View = root.findViewById(R.id.reader_toc_panel_container)
    private val tabLayout: TabLayout = root.findViewById(R.id.reader_toc_tab_layout)
    private val pageContainer: ViewGroup = root.findViewById(R.id.reader_toc_page_container)
    private val btnBack: View = root.findViewById(R.id.reader_toc_btn_back)
    private val btnSort: ImageButton = root.findViewById(R.id.reader_toc_btn_sort)
    private val footerChapterLabel: TextView = root.findViewById(R.id.reader_toc_footer_chapter_label)
    private val footerActionSlot: ViewGroup = root.findViewById(R.id.reader_toc_footer_action_slot)
    private val btnSearch: View = root.findViewById(R.id.reader_toc_btn_search)
    private val searchBar: View = root.findViewById(R.id.reader_toc_search_bar)
    private val footerRow: View = root.findViewById(R.id.reader_toc_footer)
    private val searchField: EditText = root.findViewById(R.id.reader_toc_search_field)
    private val btnSearchClose: View = root.findViewById(R.id.reader_toc_btn_search_close)

    private var shown = false
    private var currentPage: ReaderTocPage? = null
    private var currentTab = ReaderTocTab.CHAPTERS

    private val availableTabs = ReaderTocTab.entries.toList() // cả 3 tab luôn hiện cho EPUB

    init {
        // Chiều rộng panel: min(360dp, 85% chiều ngang màn hình) -- không set cứng trong XML vì
        // % màn hình không tính trực tiếp bằng layout_width XML một cách đáng tin cậy trên mọi
        // kích thước màn hình.
        panelContainer.post { applyPanelWidth() }

        buildTabs()
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showTab(availableTabs[tab.position])
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        btnBack.setOnClickListener { hide() }
        scrim.setOnClickListener { hide() }
        btnSort.setOnClickListener { currentPage?.onSortRequested() }
        btnSearch.setOnClickListener { toggleSearchBar(true) }
        btnSearchClose.setOnClickListener { toggleSearchBar(false) }
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                currentPage?.onQueryChanged(s?.toString().orEmpty())
            }
        })
    }

    private fun applyPanelWidth() {
        val dm = context.resources.displayMetrics
        val eightyFivePercent = (dm.widthPixels * 0.85f).toInt()
        val maxWidthPx = (360 * dm.density).toInt()
        panelContainer.layoutParams = panelContainer.layoutParams.apply {
            width = minOf(eightyFivePercent, maxWidthPx)
        }
        panelContainer.requestLayout()
    }

    private fun buildTabs() {
        tabLayout.removeAllTabs()
        for (tab in availableTabs) {
            tabLayout.addTab(tabLayout.newTab().setText(tab.label))
        }
    }

    private fun toggleSearchBar(visible: Boolean) {
        searchBar.setVisible(visible)
        footerRow.setVisible(!visible)
        if (visible) {
            searchField.setText("")
            searchField.requestFocus()
        } else {
            searchField.setText("")
            currentPage?.onQueryChanged("")
        }
    }

    /** Gọi mỗi lần panel được mở hoặc điều hướng thay đổi (jump chương), để dòng "Chương hiện
     * tại" ở footer luôn đúng. */
    fun refreshCurrentChapterLabel(title: String) {
        footerChapterLabel.text = title
        footerChapterLabel.setVisible(title.isNotBlank())
    }

    private fun showTab(tab: ReaderTocTab) {
        currentTab = tab
        pageContainer.removeAllViews()
        footerActionSlot.removeAllViews()
        toggleSearchBar(false)
        // Nút sort không có ý nghĩa ở tab Chương (spine order là thứ tự duy nhất hợp lý) -- ẩn
        // hẳn thay vì để nó vô tri, xem prompt mục 5.
        btnSort.setVisible(tab != ReaderTocTab.CHAPTERS)
        val publication = publicationProvider() ?: return
        val page: ReaderTocPage = when (tab) {
            ReaderTocTab.CHAPTERS -> ReaderTocChaptersPage(
                context = context,
                publication = publication,
                onJumpTo = { link -> onJumpToChapterLink(link); hide() },
            )
            ReaderTocTab.BOOKMARKS -> ReaderTocBookmarksPage(
                context = context,
                scope = scope,
                mDB = mDB,
                uuid = uuid,
                publication = publication,
                currentLocatorProvider = currentLocatorProvider,
                onJumpTo = { locator -> onJumpTo(locator); hide() },
                onBookmarksChanged = onBookmarksChanged,
                footerActionSlot = footerActionSlot,
            )
            ReaderTocTab.IMAGES -> ReaderTocImagesPage(
                context = context,
                scope = scope,
                publication = publication,
            )
        }
        currentPage = page
        pageContainer.addView(page.onCreateView(LayoutInflater.from(context), pageContainer))
    }

    fun show(initialTab: ReaderTocTab = ReaderTocTab.CHAPTERS) {
        val index = availableTabs.indexOf(initialTab).coerceAtLeast(0)
        val alreadyOnTab = currentTab == initialTab && root.visibility == View.VISIBLE
        tabLayout.getTabAt(index)?.select()
        if (!alreadyOnTab) showTab(initialTab) // đề phòng listener không bắn khi tab đã ở vị trí này
        if (shown) return
        shown = true
        root.setVisible(true)
        val startWidth = if (panelContainer.width > 0) {
            panelContainer.width
        } else {
            context.resources.displayMetrics.widthPixels
        }
        panelContainer.translationX = -startWidth.toFloat()
        scrim.alpha = 0f
        panelContainer.animate().translationX(0f).setDuration(220).start()
        scrim.animate().alpha(1f).setDuration(220).start()
    }

    fun hide() {
        if (!shown) return
        shown = false
        panelContainer.animate()
            .translationX(-panelContainer.width.toFloat())
            .setDuration(200)
            .start()
        scrim.animate().alpha(0f).setDuration(200)
            .withEndAction { root.setVisible(false) }
            .start()
    }

    private fun View.setVisible(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** True nếu panel đang mở -- dùng để chặn back-press của `EpubReaderActivity` đóng panel
     * thay vì thoát Activity, giống cách `ReaderChromeOverlay`/`ReaderMenuSheet` đã làm cho các
     * overlay khác của mình. */
    fun isShown(): Boolean = shown
}
