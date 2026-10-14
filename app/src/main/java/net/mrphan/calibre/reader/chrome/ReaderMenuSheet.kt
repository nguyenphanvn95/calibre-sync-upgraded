package net.mrphan.calibre.reader.chrome

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import net.mrphan.calibre.R

/**
 * Menu 3 gach ngang cua reader: danh sach doc, nhom bang duong ke ngang, tuong duong ViewMenu cua
 * Readest (xem prompt-nang-cap-reader-toolbar.md muc 1 va 2). Noi dung lay tu
 * ReaderActionRegistry.menuGroupsFor(host.readerKind) -- khong hardcode danh sach o day, de them
 * mot action moi chi can sua ReaderActions.kt + ReaderActionRegistry.kt.
 *
 * Rows whose action is a [ReaderToggleAction] (Page/Scroll mode, Auto scroll, Fullscreen, Light/
 * Dark) don't dismiss the sheet on tap -- they call [ReaderToggleAction.invoke], then rebuild the
 * whole sheet content in place via [buildContent] so every row's checkmark reflects the new state
 * immediately (mirrors how [ReaderChromeOverlay.refreshActions] rebuilds the header/footer after
 * something changes). Plain [ReaderAction] rows keep the original dismiss-then-invoke behaviour.
 */
class ReaderMenuSheet(private val host: ReaderChromeHost) : BottomSheetDialogFragment() {

    private var contentRoot: LinearLayout? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 24, 0, 24)
        }
        contentRoot = root
        buildContent(root)
        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        contentRoot = null
    }

    private fun buildContent(root: LinearLayout) {
        val inflater = LayoutInflater.from(root.context)
        root.removeAllViews()
        val groups = ReaderActionRegistry.menuGroupsFor(host.readerKind)
        for ((index, group) in groups.withIndex()) {
            if (index > 0) {
                inflater.inflate(R.layout.item_reader_menu_divider, root, true)
            }
            for (action in group) {
                val isToggle = action is ReaderToggleAction
                val layoutRes = if (isToggle) {
                    R.layout.item_reader_menu_entry_toggle
                } else {
                    R.layout.item_reader_menu_entry
                }
                val row = inflater.inflate(layoutRes, root, false)
                row.findViewById<ImageView>(R.id.reader_menu_entry_icon).setImageResource(action.icon)
                row.findViewById<TextView>(R.id.reader_menu_entry_label).setText(action.label)
                if (isToggle) {
                    val checkView = row.findViewById<ImageView>(R.id.reader_menu_entry_check)
                    val toggleAction = action as ReaderToggleAction
                    checkView.visibility = if (toggleAction.isChecked(host)) View.VISIBLE else View.GONE
                }
                row.setOnClickListener {
                    action.invoke(host)
                    if (isToggle) {
                        // Keep the sheet open and refresh every row's checkmark instead of
                        // dismissing -- see class kdoc.
                        contentRoot?.let { buildContent(it) }
                    } else {
                        dismiss()
                    }
                }
                row.setOnLongClickListener {
                    val handled = action.onLongPress(host)
                    if (handled && isToggle) {
                        contentRoot?.let { buildContent(it) }
                    }
                    handled
                }
                root.addView(row)
            }
            // Chen hang Sync NGAY SAU nhom dau tien ("Che do doc", index 0) -- dung
            // vi tri Readest dat Sync info: giua nhom Scrolled/Auto Scroll/
            // Paragraph Mode va nhom Dark Mode/Share Book (xem anh minh hoa trong
            // prompt-nang-cap-reader-sync.md). Khong dua vao ReaderActionRegistry
            // vi hang nay co 2 vung bam rieng (than hang + nut info), khac model
            // 1-action-1-invoke() cua ReaderAction.
            if (index == 0 && host.isProgressSyncAvailable()) {
                inflater.inflate(R.layout.item_reader_menu_divider, root, true)
                buildSyncRow(inflater, root)
            }
        }
    }

    private fun buildSyncRow(inflater: LayoutInflater, root: LinearLayout) {
        val row = inflater.inflate(R.layout.item_reader_menu_entry_sync, root, false)
        val icon = row.findViewById<ImageView>(R.id.reader_menu_sync_icon)
        val spinner = row.findViewById<android.widget.ProgressBar>(R.id.reader_menu_sync_progress)
        val label = row.findViewById<TextView>(R.id.reader_menu_sync_label)
        val infoButton = row.findViewById<ImageView>(R.id.reader_menu_sync_info)

        fun refreshLabel() {
            val syncing = host.isProgressSyncInProgress()
            icon.visibility = if (syncing) View.GONE else View.VISIBLE
            spinner.visibility = if (syncing) View.VISIBLE else View.GONE
            val last = host.lastProgressSyncedAt()
            label.text = when {
                syncing -> host.formatString(R.string.reader_sync_syncing)
                last == null -> host.formatString(R.string.reader_sync_never)
                else -> host.formatString(R.string.reader_sync_synced_ago, formatAgo(last))
            }
        }
        refreshLabel()

        row.setOnClickListener {
            if (host.isProgressSyncInProgress()) return@setOnClickListener
            val started = host.syncProgressNow { success ->
                refreshLabel()
                if (!success) host.showMessage(host.formatString(R.string.reader_sync_failed))
            }
            if (started) refreshLabel() // hien spinner ngay, khong doi callback
        }
        infoButton.setOnClickListener {
            dismiss()
            host.openBookSyncInfo()
        }
        root.addView(row)
    }

    /** Do dai thoi gian ("X phút", "X giờ"...) luon co dinh tieng Viet, dung cho
     * [R.string.reader_sync_synced_ago]. BUG DA SUA: truoc day dung
     * android.text.format.DateUtils.getRelativeTimeSpanString() -- ham nay tra ve chuoi da
     * kem san hau to theo LOCALE HE THONG cua may, khong phai locale cua app. May de tieng
     * Anh se ra "10 minutes ago", ghep voi "trước" cua reader_sync_synced_ago thanh
     * "Đã đồng bộ 10 minutes ago trước" (dung 2 hau to thoi gian, sai ngon ngu). Ham duoi
     * day CHI tra ve con so + don vi tieng Viet, khong tu them "trước"/"ago". */
    private fun formatAgo(sinceMillis: Long): String {
        val diffMs = (System.currentTimeMillis() - sinceMillis).coerceAtLeast(0L)
        val minutes = diffMs / 60_000L
        val hours = diffMs / 3_600_000L
        val days = diffMs / 86_400_000L
        return when {
            minutes < 1 -> host.formatString(R.string.reader_sync_ago_just_now)
            hours < 1 -> host.formatString(R.string.reader_sync_ago_minutes, minutes.toInt())
            days < 1 -> host.formatString(R.string.reader_sync_ago_hours, hours.toInt())
            else -> host.formatString(R.string.reader_sync_ago_days, days.toInt())
        }
    }

    companion object {
        const val TAG = "ReaderMenuSheet"
    }
}

