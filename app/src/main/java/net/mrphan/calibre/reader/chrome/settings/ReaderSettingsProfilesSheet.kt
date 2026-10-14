package net.mrphan.calibre.reader.chrome.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import net.mrphan.calibre.EpubReaderActivity
import net.mrphan.calibre.R

/**
 * "Cấu hình tạo sẵn" -- danh sach preset dung san ([ReaderSettingsProfilePresets.ALL]) + preset
 * nguoi dung tu luu ([ReaderSettingsProfileStore]). Cham vao 1 dong = ap dung ngay
 * ([EpubReaderActivity.applySettingsProfile]) roi dong sheet. Chi mo tu EpubReaderActivity, giong
 * het ReaderSettingsSheet (xem ReaderSettingsPage's kdoc) vi tat ca field can doc/ghi deu la
 * internal var tren activity do, khong co tren ReaderChromeHost.
 */
class ReaderSettingsProfilesSheet(
    private val activity: EpubReaderActivity,
) : BottomSheetDialogFragment() {

    private val store by lazy { ReaderSettingsProfileStore(requireContext()) }
    private lateinit var listContainer: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = inflater.inflate(R.layout.sheet_reader_settings_profiles, container, false)
        listContainer = root.findViewById(R.id.reader_settings_profiles_list)
        root.findViewById<View>(R.id.reader_settings_profiles_save_current)
            .setOnClickListener { promptSaveCurrentAsProfile() }
        rebuildList(inflater)
        return root
    }

    private fun rebuildList(inflater: LayoutInflater) {
        listContainer.removeAllViews()

        ReaderSettingsRowBinder.addGroupHeader(listContainer, inflater, R.string.reader_settings_profiles_group_builtin)
        for (profile in ReaderSettingsProfilePresets.ALL) {
            addProfileRow(inflater, profile)
        }

        val customProfiles = store.loadCustomProfiles()
        if (customProfiles.isNotEmpty()) {
            ReaderSettingsRowBinder.addGroupHeader(listContainer, inflater, R.string.reader_settings_profiles_group_custom)
            for (profile in customProfiles) {
                addProfileRow(inflater, profile, deletable = true)
            }
        }
    }

    private fun addProfileRow(inflater: LayoutInflater, profile: ReaderSettingsProfile, deletable: Boolean = false) {
        val row = inflater.inflate(R.layout.item_reader_settings_profile, listContainer, false)
        row.findViewById<TextView>(R.id.reader_settings_profile_name).text = profile.name
        row.setOnClickListener {
            activity.applySettingsProfile(profile)
            activity.showMessage(getString(R.string.reader_settings_profiles_applied, profile.name))
            dismiss()
        }
        val overflow = row.findViewById<View>(R.id.reader_settings_profile_overflow)
        overflow.visibility = if (deletable) View.VISIBLE else View.GONE
        if (deletable) {
            overflow.setOnClickListener { confirmDeleteProfile(profile) }
        }
        listContainer.addView(row)
    }

    private fun promptSaveCurrentAsProfile() {
        val input = EditText(requireContext())
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reader_settings_profiles_save_current_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    store.saveNewProfile(name, activity.captureCurrentSettingsAsProfile())
                    rebuildList(LayoutInflater.from(requireContext()))
                }
            }
            .setNegativeButton(R.string.reader_chrome_close, null)
            .show()
    }

    private fun confirmDeleteProfile(profile: ReaderSettingsProfile) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reader_settings_profiles_delete_confirm_title)
            .setMessage(profile.name)
            .setPositiveButton(R.string.reader_settings_profiles_delete_confirm_action) { _, _ ->
                store.deleteProfile(profile.id)
                rebuildList(LayoutInflater.from(requireContext()))
            }
            .setNegativeButton(R.string.reader_chrome_close, null)
            .show()
    }

    companion object {
        const val TAG = "ReaderSettingsProfilesSheet"
    }
}
