package net.mrphan.calibre

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentManager
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Thay the SettingsActivity (mot Activity rieng): cung mot khung tab-row + page-container nhu
 * ReaderSettingsSheet trong reader doc ("Cai dat doc"), nhung hien thi duoi dang bottom sheet de
 * len tren man hinh hien tai thay vi mo cua so/Activity moi. Xem
 * prompt-settings-bottom-sheet.md.
 *
 * Noi dung tung tab van la mot SettingsFragment (PreferenceFragmentCompat) nap tu
 * res/xml/preference_*.xml theo SettingsTab, y het truoc day -- chi khac cho song
 * (childFragmentManager cua sheet nay thay vi supportFragmentManager cua Activity), va cach
 * SettingsFragment goi nguoc len de xin quyen/mo folder picker (qua parentFragment thay vi
 * ep kieu Activity).
 *
 * [autoOpenFolderPicker]: tuong duong EXTRA_AUTO_OPEN_FOLDER_PICKER cu -- khi true, sheet tu mo
 * thang vao tab STORAGE va tu "bam" vao "Book Folder" ngay khi mo, dung boi
 * CollectionFragment#openBookFolderPicker() cho nut o empty-state.
 */
class SettingsSheet @JvmOverloads constructor(
    private val autoOpenFolderPicker: Boolean = false,
) : BottomSheetDialogFragment(), PreferenceFragmentCompat.OnPreferenceStartScreenCallback {

    private lateinit var tabRow: LinearLayout
    private var currentTab: SettingsTab = if (autoOpenFolderPicker) SettingsTab.STORAGE else SettingsTab.APPEARANCE

    // Thay the 4 request code + onActivityResult()/onRequestPermissionsResult() cu cua
    // SettingsActivity: cac launcher nay PHAI dang ky truoc khi Fragment vao trang thai STARTED,
    // nen luon o onCreate() -- dung chung mau voi AddCloudRemoteDialogFragment.loginLauncher da
    // co san trong repo.
    private lateinit var allFilesAccessLauncher: ActivityResultLauncher<Intent>
    private lateinit var openDocumentTreeLauncher: ActivityResultLauncher<Uri?>
    private lateinit var nghiModelDirLauncher: ActivityResultLauncher<Uri?>
    private lateinit var ngheEpubModelDirLauncher: ActivityResultLauncher<Uri?>
    private lateinit var legacyStoragePermissionLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        allFilesAccessLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            // Khong doc resultCode: he thong khong tra ve ket qua dang tin cay duoc cho man hinh
            // "All files access" nay -- kiem tra lai truc tiep giong SettingsActivity cu.
            if (StorageUtils.hasBroadStorageAccess()) launchFolderPickerInternal()
        }
        openDocumentTreeLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { treeUri -> if (treeUri != null) onRootDirPicked(treeUri) }
        nghiModelDirLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { treeUri -> if (treeUri != null) onNghiModelDirPicked(treeUri) }
        ngheEpubModelDirLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { treeUri -> if (treeUri != null) onNgheEpubModelDirPicked(treeUri) }
        legacyStoragePermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants -> if (grants.values.all { it }) launchFolderPickerInternal() }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = inflater.inflate(R.layout.sheet_settings, container, false)
        tabRow = root.findViewById(R.id.settings_sheet_tab_row)
        buildTabRow()
        showTab(currentTab)
        return root
    }

    override fun onStart() {
        super.onStart()
        // Mo full-height ngay lap tuc thay vi "half-expanded" mac dinh: PreferenceFragmentCompat
        // la mot danh sach cuon dai, khac voi cac ReaderSettingsPage ngan gon wrap_content trong
        // reader -- neu khong ep STATE_EXPANDED, danh sach preference bi bo hep, phai keo len moi
        // thay het. Cung pattern voi TtsSpeedSettingsBottomSheet/BackgroundMusicSettingsDialog.
        (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
        if (autoOpenFolderPicker) {
            view?.post {
                // BUG FIX (crash 2026-07-26): runnable nay chay bat dong bo tren message queue,
                // nen giua luc post() va luc no thuc su chay, sheet co the da bi dismiss() --
                // vd. ngay sau khi nguoi dung chon xong thu muc thi onRootDirPicked() goi
                // dismiss() truoc khi runnable nay kip chay. Luc do Fragment da "not attached"
                // nhung view?.post van khong bat duoc dieu nay (view co the van con giu tham
                // chieu), nen goi childFragmentManager se nem
                // IllegalStateException("has not been attached yet"). Phai tu kiem tra isAdded.
                if (!isAdded) return@post
                (childFragmentManager.findFragmentById(R.id.settings_sheet_page_container)
                        as? SettingsFragment)?.triggerChooseRootDirFromExternalCall()
            }
        }
    }

    private fun buildTabRow() {
        tabRow.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        for (tab in SettingsTab.values()) {
            // Tai su dung dung layout item cua thanh tab "Cai dat doc" trong reader
            // (item_reader_settings_tab.xml) de giao dien 2 noi giong het nhau.
            val tabView = inflater.inflate(R.layout.item_reader_settings_tab, tabRow, false)
            tabView.findViewById<ImageView>(R.id.reader_settings_tab_icon).setImageResource(tab.icon)
            tabView.contentDescription = getString(tab.label)
            tabView.tag = tab
            tabView.setOnClickListener { showTab(tab) }
            tabRow.addView(tabView)
        }
        refreshTabSelection()
    }

    private fun refreshTabSelection() {
        for (i in 0 until tabRow.childCount) {
            val child = tabRow.getChildAt(i)
            child.isSelected = child.tag == currentTab
        }
    }

    /** Chuyen sang mot tab/khoi Settings khac: dong het back stack (vd. dang o man hinh con
     * "TTS Models") roi thay SettingsFragment bang mot instance moi nap tu res/xml/preference_*.xml
     * cua tab do -- xem SettingsFragment.newInstance(). */
    private fun showTab(tab: SettingsTab) {
        currentTab = tab
        refreshTabSelection()
        val fm = childFragmentManager
        fm.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        fm.beginTransaction()
            .replace(R.id.settings_sheet_page_container, SettingsFragment.newInstance(tab))
            .commit()
    }

    override fun onPreferenceStartScreen(
        caller: PreferenceFragmentCompat,
        pref: PreferenceScreen,
    ): Boolean {
        // "caller" luon la SettingsFragment cua tab dang hien thi (vd. TTS, noi "TTS Models"
        // song) -- truyen lai chinh tab do de fragment con nap dung file preference_*.xml chua
        // pref.getKey().
        val fragment = SettingsFragment.newInstanceForNestedScreen((caller as SettingsFragment).tab, pref.key)
        childFragmentManager.beginTransaction()
            .replace(R.id.settings_sheet_page_container, fragment)
            .addToBackStack(pref.key)
            .commit()
        return true
    }

    // ---- Ha tang duoc SettingsFragment goi nguoc vao qua parentFragment (thay cho ep kieu
    // (SettingsActivity) getActivity() truoc day) ----

    /** MANAGE_EXTERNAL_STORAGE (API 30+) can only be granted through this dedicated system
     * Settings screen, never a normal runtime permission dialog. */
    fun requestAllFilesAccess() {
        Toast.makeText(requireContext(), R.string.toast_need_all_files_access, Toast.LENGTH_LONG).show()
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:" + requireContext().packageName),
        )
        allFilesAccessLauncher.launch(intent)
    }

    fun requestLegacyStoragePermission() {
        legacyStoragePermissionLauncher.launch(
            arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE, android.Manifest.permission.READ_EXTERNAL_STORAGE)
        )
    }

    fun launchFolderPickerInternal() {
        try {
            openDocumentTreeLauncher.launch(null)
        } catch (e: Exception) {
            Logger.e(e)
        }
    }

    /** Chỉ cần quyền đọc (không cần ghi) vì app chỉ copy model VÀO bộ nhớ riêng, không bao giờ
     * ghi ngược lại thư mục người dùng chọn. */
    fun launchNghiModelDirPicker() {
        try {
            nghiModelDirLauncher.launch(null)
        } catch (e: Exception) {
            Logger.e(e)
        }
    }

    fun launchNgheEpubModelDirPicker() {
        try {
            ngheEpubModelDirLauncher.launch(null)
        } catch (e: Exception) {
            Logger.e(e)
        }
    }

    private fun onRootDirPicked(treeUri: Uri) {
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: Exception) {
            Logger.e(e)
        }
        val resolved = StorageUtils.resolveTreeUriToFile(requireContext(), treeUri)
        if (resolved == null) {
            Toast.makeText(requireContext(), R.string.toast_folder_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
            .putString(StorageUtils.PREF_ROOT_DIR_PATH, resolved.absolutePath)
            .apply()
        (childFragmentManager.findFragmentById(R.id.settings_sheet_page_container) as? SettingsFragment)
            ?.updateRootDirSummary(resolved.absolutePath)
        // BUG FIX: previously the sheet just sat here at full height showing the updated
        // summary, so after picking a folder from the Device Library empty-state button the
        // user appeared "stuck" on the folder-picker screen instead of landing back on their
        // book list (CollectionFragment WAS already refreshing underneath via mRootDirListener,
        // it just wasn't visible behind this fullscreen sheet). Only auto-dismiss when this
        // sheet was auto-opened for that exact purpose -- if the user manually opened
        // Settings > Storage themselves to change the folder, leave the sheet open so they can
        // keep adjusting other settings.
        if (autoOpenFolderPicker) {
            dismiss()
        }
    }

    private fun onNghiModelDirPicked(treeUri: Uri) {
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: Exception) {
            Logger.e(e)
        }
        val store = net.mrphan.calibre.reader.tts.nghi.NghiModelStore(requireContext())
        store.saveModelDirUri(treeUri.toString())
        (childFragmentManager.findFragmentById(R.id.settings_sheet_page_container) as? SettingsFragment)
            ?.updateNghiModelDirSummary(treeUri.toString())
    }

    private fun onNgheEpubModelDirPicked(treeUri: Uri) {
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: Exception) {
            Logger.e(e)
        }
        val store = net.mrphan.calibre.reader.tts.nghe.NgheEpubModelStore(requireContext())
        store.saveModelDirUri(treeUri.toString())
        (childFragmentManager.findFragmentById(R.id.settings_sheet_page_container) as? SettingsFragment)
            ?.updateNgheEpubModelDirSummary(treeUri.toString())
    }

    companion object {
        const val TAG = "SettingsSheet"
    }
}
