package net.mrphan.calibre.reader.chrome

import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mrphan.calibre.FileHashUtils
import net.mrphan.calibre.R
import java.io.File

/**
 * "Sync Info" bottom sheet -- doi chieu Readest's Tools > Sync info (xem
 * prompt-nang-cap-reader-sync.md muc 0.1/7). Book Fingerprint duoc tinh bang
 * sha256 NOI DUNG FILE sach dang mo (dung lai FileHashUtils.sha256Hex da co
 * san cho device-library-sync) -- gan voi tinh than "content-based identity"
 * cua Readest hon la dung UUID ngau nhien lam "fingerprint" gia. Identifiers
 * hien thi UUID that su ma DeviceLibrarySync/SyncProgress dung lam khoa dong
 * bo trong app nay (tuong duong "waka-..." id cua Readest -- 1 chuoi dinh danh
 * dong bo, khong phai ISBN).
 */
class BookSyncInfoSheet : BottomSheetDialogFragment() {

    private lateinit var bookFilePath: String
    private lateinit var uuid: String
    private lateinit var titleArg: String
    private lateinit var authorArg: String
    private var lastSyncedAtMillis: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = requireArguments()
        bookFilePath = args.getString(ARG_BOOK_FILE_PATH)!!
        uuid = args.getString(ARG_UUID)!!
        titleArg = args.getString(ARG_TITLE)!!
        authorArg = args.getString(ARG_AUTHOR)!!
        lastSyncedAtMillis = args.getLong(ARG_LAST_SYNCED, -1L).let { if (it < 0) null else it }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = inflater.inflate(R.layout.sheet_book_sync_info, container, false)
        root.findViewById<View>(R.id.sync_info_back_button).setOnClickListener { dismiss() }

        bindField(root, R.id.sync_info_field_fingerprint, R.string.sync_info_label_fingerprint,
            getString(R.string.reader_sync_computing))
        bindField(root, R.id.sync_info_field_title, R.string.sync_info_label_title, titleArg)
        bindField(root, R.id.sync_info_field_author, R.string.sync_info_label_author, authorArg)
        bindField(root, R.id.sync_info_field_identifiers, R.string.sync_info_label_identifiers, uuid)
        bindField(root, R.id.sync_info_field_last_synced, R.string.sync_info_label_last_synced,
            formatLastSynced())

        // Hash file co the mat vai chuc ms voi sach lon -- tinh ngoai main
        // thread roi cap nhat rieng o TextView fingerprint, khong chan mo sheet.
        viewLifecycleOwner.lifecycleScope.launch {
            val hash = withContext(Dispatchers.IO) {
                runCatching { FileHashUtils.sha256Hex(File(bookFilePath)).take(32) }.getOrDefault("—")
            }
            root.findViewById<View>(R.id.sync_info_field_fingerprint)
                .findViewById<TextView>(R.id.sync_info_field_value).text = hash
        }
        return root
    }

    private fun bindField(root: View, containerId: Int, @androidx.annotation.StringRes labelRes: Int, value: String) {
        val fieldRoot = root.findViewById<View>(containerId)
        fieldRoot.findViewById<TextView>(R.id.sync_info_field_label).setText(labelRes)
        fieldRoot.findViewById<TextView>(R.id.sync_info_field_value).text = value
    }

    private fun formatLastSynced(): String {
        val millis = lastSyncedAtMillis ?: return getString(R.string.reader_sync_never)
        return DateFormat.format("M/d/yyyy, h:mm:ss a", millis).toString()
    }

    companion object {
        const val TAG = "BookSyncInfoSheet"
        private const val ARG_BOOK_FILE_PATH = "book_file_path"
        private const val ARG_UUID = "uuid"
        private const val ARG_TITLE = "title"
        private const val ARG_AUTHOR = "author"
        private const val ARG_LAST_SYNCED = "last_synced"

        fun newInstance(
            uuid: String,
            title: String,
            author: String,
            bookFilePath: String,
            lastSyncedAtMillis: Long?,
        ) = BookSyncInfoSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_UUID, uuid)
                putString(ARG_TITLE, title)
                putString(ARG_AUTHOR, author)
                putString(ARG_BOOK_FILE_PATH, bookFilePath)
                putLong(ARG_LAST_SYNCED, lastSyncedAtMillis ?: -1L)
            }
        }
    }
}
