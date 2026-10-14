package net.mrphan.calibre;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.os.LocaleListCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Noi dung mot tab cua man hinh Settings: nap res/xml/preference_*.xml theo {@link SettingsTab},
 * dien summary phien ban app, va xu ly Theme/Language/Book Folder/TTS model dir.
 * <p>
 * Truoc day day la mot nested static class ben trong SettingsActivity (mot Activity rieng).
 * Sau khi chuyen man hinh Settings sang bottom sheet (xem prompt-settings-bottom-sheet.md),
 * fragment nay duoc tach ra file rieng va song trong childFragmentManager cua {@link SettingsSheet}
 * -- moi cho truoc day ep kieu {@code (SettingsActivity) getActivity()} gio ep kieu
 * {@code (SettingsSheet) getParentFragment()} de goi nguoc len sheet xin quyen/mo folder picker.
 */
public class SettingsFragment extends PreferenceFragmentCompat {

    private static final String ARG_TAB_ORDINAL = "arg_tab_ordinal";

    private Preference prefRootDir;
    private Preference prefNghiModelDir;
    private Preference prefNghiScanNow;
    private androidx.preference.ListPreference prefNghiDefaultModel;
    private Preference prefNgheEpubModelDir;
    private Preference prefNgheEpubScanNow;
    private androidx.preference.ListPreference prefNgheEpubDefaultVoice;
    private androidx.preference.ListPreference prefAppLanguage;
    private androidx.preference.ListPreference prefAppTheme;

    /** Top-level instance for a tab: nap toan bo res/xml/preference_*.xml cua tab do tu goc. */
    public static SettingsFragment newInstance(SettingsTab tab) {
        SettingsFragment fragment = new SettingsFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_TAB_ORDINAL, tab.ordinal());
        fragment.setArguments(args);
        return fragment;
    }

    /** Instance cho mot nested {@code <PreferenceScreen>} (vd. "TTS Models") ben trong tab:
     * van nap dung file preference_*.xml cua tab do, nhung root la rootKey thay vi toan bo
     * cay -- day la co che chuan cua PreferenceFragmentCompat.OnPreferenceStartScreenCallback,
     * xem SettingsSheet.onPreferenceStartScreen(). */
    public static SettingsFragment newInstanceForNestedScreen(SettingsTab tab, String rootKey) {
        SettingsFragment fragment = new SettingsFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_TAB_ORDINAL, tab.ordinal());
        args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, rootKey);
        fragment.setArguments(args);
        return fragment;
    }

    /** Tab ma fragment nay dang hien thi (toan bo hoac mot nested screen ben trong no) -- dung
     * boi SettingsSheet.onPreferenceStartScreen() de biet nap tiep tu file xml nao. */
    public SettingsTab getTab() {
        Bundle args = getArguments();
        int ordinal = args != null ? args.getInt(ARG_TAB_ORDINAL, 0) : 0;
        SettingsTab[] tabs = SettingsTab.values();
        return (ordinal >= 0 && ordinal < tabs.length) ? tabs[ordinal] : SettingsTab.APPEARANCE;
    }

    @Override
    public void onViewCreated(android.view.View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // An toan goi o day: super.onViewCreated() chi duoc goi SAU onCreateView(), luc nay
        // RecyclerView (mList) da duoc gan nen setDivider()/setDividerHeight() khong con NPE
        // (xem giai thich chi tiet trong onCreatePreferences() ben duoi).
        setDivider(null);
        setDividerHeight(0);
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(getTab().getPrefsRes(), rootKey);

        // Nang cap UI: moi Preference thanh mot the bo goc, moi PreferenceCategory thanh tieu de
        // nam ngoai the (xem prompt-nang-cap-app-settings-card-ui.md muc 7). Viec gop nhieu hang
        // lien tiep thanh MOT the chung (bo goc tren/duoi, vuong o giua) do CardPreferenceGroupAdapter
        // dam nhan qua onCreateAdapter() ben duoi, o day chi can gan layoutResource cho tung
        // Preference/PreferenceCategory.
        applyCardStyle(getPreferenceScreen());
        // KHONG goi setDivider()/setDividerHeight() o day: onCreatePreferences() chay trong
        // onCreate(), truoc khi RecyclerView (mList noi bo cua PreferenceFragmentCompat) duoc
        // tao o onCreateView(). setDivider()/setDividerHeight() cua thu vien goi thang
        // mList.invalidateItemDecorations() ma KHONG kiem tra null -> NPE ngay lap tuc
        // (tung gay crash that, xem crash_2026-07-27_06-46-37.txt). Da chuyen 2 loi goi nay
        // xuong onViewCreated() ben duoi, noi RecyclerView chac chan da ton tai.

        try {
            Preference prefAppVersionName = findPreference("pref_app_version_name");
            if (prefAppVersionName != null) {
                prefAppVersionName.setSummary(
                        requireContext().getPackageManager()
                                .getPackageInfo(requireContext().getPackageName(), 0).versionName);
            }
        } catch (Exception e) {
            Logger.e(e);
        }

        prefAppTheme = findPreference("pref_app_theme");
        if (prefAppTheme != null) {
            prefAppTheme.setOnPreferenceChangeListener((preference, newValue) -> {
                int mode;
                try {
                    mode = Integer.parseInt((String) newValue);
                } catch (NumberFormatException e) {
                    mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                }
                // AppCompatDelegate.setDefaultNightMode() KHÔNG tự recreate() Activity hiện tại (khác
                // với setApplicationLocales() ở khối pref_app_language bên dưới) -- phải tự gọi. Vì
                // sheet nay song ben trong mot Activity host (MainActivity/...), recreate() se dong
                // luon sheet -- day la danh doi chap nhan duoc, xem prompt-settings-bottom-sheet.md
                // muc 5.1.
                AppCompatDelegate.setDefaultNightMode(mode);
                Activity activity = getActivity();
                if (activity != null) activity.recreate();
                return true; // để ListPreference tự lưu giá trị mới vào SharedPreferences
            });
        }

        androidx.preference.ListPreference prefGridDisplayStyle = findPreference("pref_grid_display_style");
        androidx.preference.CheckBoxPreference prefGridShowTitleProgress = findPreference("pref_grid_show_title_progress");
        if (prefGridDisplayStyle != null && prefGridShowTitleProgress != null) {
            // Ap dung trang thai khoa/gia tri ep buoc ngay khi mo man hinh Settings, dua tren gia
            // tri style da luu.
            applyGridShowTitleProgressLockState(prefGridShowTitleProgress, prefGridDisplayStyle.getValue());
            prefGridDisplayStyle.setOnPreferenceChangeListener((preference, newValue) -> {
                applyGridShowTitleProgressLockState(prefGridShowTitleProgress, (String) newValue);
                return true; // de ListPreference tu luu gia tri moi vao SharedPreferences
            });
        }

        prefAppLanguage = findPreference("pref_app_language");
        if (prefAppLanguage != null) {
            syncLanguagePreferenceWithCurrentLocale(prefAppLanguage);
            prefAppLanguage.setOnPreferenceChangeListener((preference, newValue) -> {
                String tag = (String) newValue;
                AppCompatDelegate.setApplicationLocales(
                        tag.isEmpty()
                                ? LocaleListCompat.getEmptyLocaleList()
                                : LocaleListCompat.forLanguageTags(tag));
                // AppCompatDelegate.setApplicationLocales() tự recreate() mọi AppCompatActivity
                // đang sống (bao gồm Activity host của sheet này) để áp dụng ngôn ngữ mới ngay lập
                // tức -- không cần tự gọi recreate() hay tự cập nhật summary ở đây. Sheet cũng sẽ
                // tự đóng theo (xem prompt-settings-bottom-sheet.md mục 5.1).
                return true;
            });
        }

        prefRootDir = findPreference("pref_root_dir_path");
        if (prefRootDir != null) {
            SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(requireContext());
            String saved = settings.getString(StorageUtils.PREF_ROOT_DIR_PATH, null);
            if (saved != null && !saved.isEmpty()) {
                updateRootDirSummary(saved);
            }
            prefRootDir.setOnPreferenceClickListener(preference -> {
                onChooseRootDirClicked();
                return true;
            });
        }

        prefNghiModelDir = findPreference("pref_nghitts_model_dir");
        prefNghiScanNow = findPreference("pref_nghitts_scan_now");
        prefNghiDefaultModel = findPreference("pref_nghitts_default_model");
        prefNgheEpubModelDir = findPreference("pref_ngheepubtts_model_dir");
        prefNgheEpubScanNow = findPreference("pref_ngheepubtts_scan_now");
        prefNgheEpubDefaultVoice = findPreference("pref_ngheepubtts_default_voice");

        net.mrphan.calibre.reader.tts.nghi.NghiModelStore nghiStore =
                new net.mrphan.calibre.reader.tts.nghi.NghiModelStore(requireContext());
        net.mrphan.calibre.reader.tts.nghe.NgheEpubModelStore ngheEpubStore =
                new net.mrphan.calibre.reader.tts.nghe.NgheEpubModelStore(requireContext());

        String savedModelDirUri = nghiStore.getModelDirUri();
        if (prefNghiModelDir != null) {
            updateNghiModelDirSummary(savedModelDirUri);
            prefNghiModelDir.setOnPreferenceClickListener(preference -> {
                SettingsSheet sheet = (SettingsSheet) getParentFragment();
                if (sheet != null) sheet.launchNghiModelDirPicker();
                return true;
            });
        }

        refreshNghiDefaultModelList();
        refreshNgheEpubDefaultVoiceList();

        if (prefNghiScanNow != null) {
            prefNghiScanNow.setOnPreferenceClickListener(preference -> {
                onScanNghiModelsClicked();
                return true;
            });
        }
        String savedNgheEpubModelDirUri = ngheEpubStore.getModelDirUri();
        if (prefNgheEpubModelDir != null) {
            updateNgheEpubModelDirSummary(savedNgheEpubModelDirUri);
            prefNgheEpubModelDir.setOnPreferenceClickListener(preference -> {
                SettingsSheet sheet = (SettingsSheet) getParentFragment();
                if (sheet != null) sheet.launchNgheEpubModelDirPicker();
                return true;
            });
        }
        if (prefNgheEpubScanNow != null) {
            prefNgheEpubScanNow.setOnPreferenceClickListener(preference -> {
                onScanNgheEpubModelsClicked();
                return true;
            });
        }

        if (prefNghiDefaultModel != null) {
            prefNghiDefaultModel.setOnPreferenceChangeListener((preference, newValue) -> {
                nghiStore.saveDefaultModelId((String) newValue);
                return true;
            });
        }
        if (prefNgheEpubDefaultVoice != null) {
            prefNgheEpubDefaultVoice.setOnPreferenceChangeListener((preference, newValue) -> {
                ngheEpubStore.saveDefaultVoiceId((String) newValue);
                return true;
            });
        }

        setupAutoSyncPreferences();
    }

    /** Gan layout the bo goc cho toan bo Preference trong cay, de quy vao ca cac PreferenceGroup
     * long nhau (vi du nested screen "TTS Models"). Khong dong den key/listener/gia tri da luu. */
    private void applyCardStyle(PreferenceGroup group) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference pref = group.getPreference(i);
            if (pref instanceof PreferenceCategory) {
                pref.setLayoutResource(R.layout.layout_app_settings_category_header);
            } else {
                pref.setLayoutResource(R.layout.layout_app_settings_card_preference);
            }
            if (pref instanceof PreferenceGroup) {
                applyCardStyle((PreferenceGroup) pref);
            }
        }
    }

    /** Thay the PreferenceGroupAdapter mac dinh bang CardPreferenceGroupAdapter de gop cac
     * Preference lien tiep (khong phai PreferenceCategory) thanh MOT the bo goc dung chung, giong
     * ảnh Readest tham khảo (xem CardPreferenceGroupAdapter.kt va prompt-nang-cap-app-settings-
     * card-ui.md muc 7.4). Khong anh huong nested screen ("TTS Models"): moi PreferenceScreen con
     * tu tao mot Fragment/Adapter rieng khi mo, cung se di qua override nay. */
    @Override
    public RecyclerView.Adapter onCreateAdapter(PreferenceScreen preferenceScreen) {
        return new CardPreferenceGroupAdapter(preferenceScreen);
    }

    public void updateRootDirSummary(String path) {
        if (prefRootDir != null) prefRootDir.setSummary(path);
    }

    /**
     * Khoa checkbox "pref_grid_show_title_progress" khi style Grid đang chọn không cho phép
     * người dùng tự bật/tắt riêng lẻ, giữ đồng bộ với logic ép buộc lúc render trong
     * BookRecyclerAdapter#onBindViewHolder / BookCursorAdapter (2 style này cùng khoá, chỉ khác
     * hướng ép buộc):
     * <ul>
     *   <li>Bookshelf: giá sách gỗ chỉ hiện bìa, LUÔN ẩn tựa/tiến trình.</li>
     *   <li>Simple 2: bìa full-bleed + tựa overlay + badge định dạng, LUÔN hiện tựa/tiến trình
     *       (đối lập với Bookshelf).</li>
     * </ul>
     * Cả 2 style đều disable checkbox; setChecked() ở đây vừa cập nhật UI vừa tự lưu lại giá trị
     * true/false tương ứng vào SharedPreferences (hành vi mặc định của CheckBoxPreference), nên
     * BookCursorAdapter/BookRecyclerAdapter đọc lại giá trị pref cũng khớp với những gì hiện trên
     * màn hình, dù bản thân 2 adapter đó đã tự ép buộc riêng, không phụ thuộc hoàn toàn vào giá
     * trị đã lưu này.
     */
    private void applyGridShowTitleProgressLockState(androidx.preference.CheckBoxPreference pref, String styleValue) {
        boolean isBookshelf = "bookshelf".equals(styleValue);
        boolean isSimple2 = "simple2".equals(styleValue);
        pref.setEnabled(!isBookshelf && !isSimple2);
        if (isSimple2) {
            pref.setChecked(true);
        } else if (isBookshelf) {
            pref.setChecked(false);
        }
    }

    /**
     * Đồng bộ trạng thái hiển thị của "Language" với ngôn ngữ app đang thực sự áp dụng, để khi
     * người dùng mở lại Settings, dòng đang chọn và summary luôn khớp với ngôn ngữ hiện tại --
     * kể cả lần đầu tiên khi chưa ai từng bấm chọn gì (lúc đó getApplicationLocales() trả về
     * danh sách rỗng, toLanguageTags() trả về "", khớp đúng với <item></item> rỗng ở vị trí
     * "System default" trong pref_app_language_values).
     */
    private void syncLanguagePreferenceWithCurrentLocale(androidx.preference.ListPreference pref) {
        String currentTag = AppCompatDelegate.getApplicationLocales().toLanguageTags();
        pref.setValue(currentTag);
        CharSequence entry = pref.getEntry();
        pref.setSummary(entry != null ? entry : pref.getEntries()[0]);
    }

    /** Lets SettingsSheet (autoOpenFolderPicker) simulate a tap on "Book Folder" as soon as this
     * screen opens, reusing the exact same permission/SAF flow as a manual tap. */
    public void triggerChooseRootDirFromExternalCall() {
        onChooseRootDirClicked();
    }

    private void onChooseRootDirClicked() {
        SettingsSheet sheet = (SettingsSheet) getParentFragment();
        if (sheet == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                sheet.requestAllFilesAccess();
                return;
            }
        } else {
            if (ActivityCompat.checkSelfPermission(requireContext(),
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                sheet.requestLegacyStoragePermission();
                return;
            }
        }
        sheet.launchFolderPickerInternal();
    }

    public void updateNghiModelDirSummary(String uriString) {
        if (prefNghiModelDir == null) return;
        if (uriString == null || uriString.isEmpty()) {
            prefNghiModelDir.setSummary(R.string.pref_nghitts_model_dir_summary_default);
        } else {
            prefNghiModelDir.setSummary(android.net.Uri.parse(uriString).getPath());
        }
    }

    public void refreshNghiDefaultModelList() {
        if (prefNghiDefaultModel == null) return;
        java.util.List<net.mrphan.calibre.reader.tts.nghi.NghiImportedModel> models =
                net.mrphan.calibre.reader.tts.nghi.NghiModelImporter.INSTANCE
                        .loadImportedModels(requireContext());
        if (models.isEmpty()) {
            prefNghiDefaultModel.setEnabled(false);
            prefNghiDefaultModel.setSummary(R.string.pref_nghitts_default_model_summary_empty);
            return;
        }
        prefNghiDefaultModel.setEnabled(true);
        CharSequence[] entries = new CharSequence[models.size()];
        CharSequence[] values = new CharSequence[models.size()];
        for (int i = 0; i < models.size(); i++) {
            entries[i] = models.get(i).getDisplayName();
            values[i] = models.get(i).getId();
        }
        prefNghiDefaultModel.setEntries(entries);
        prefNghiDefaultModel.setEntryValues(values);
        net.mrphan.calibre.reader.tts.nghi.NghiModelStore store =
                new net.mrphan.calibre.reader.tts.nghi.NghiModelStore(requireContext());
        String current = store.getDefaultModelId();
        if (current != null) prefNghiDefaultModel.setValue(current);
        prefNghiDefaultModel.setSummary(prefNghiDefaultModel.getEntry());
    }

    public void updateNgheEpubModelDirSummary(String uriString) {
        if (prefNgheEpubModelDir == null) return;
        if (uriString == null || uriString.isEmpty()) {
            prefNgheEpubModelDir.setSummary(R.string.pref_ngheepubtts_model_dir_summary_default);
        } else {
            prefNgheEpubModelDir.setSummary(android.net.Uri.parse(uriString).getPath());
        }
    }

    public void refreshNgheEpubDefaultVoiceList() {
        if (prefNgheEpubDefaultVoice == null) return;
        java.util.List<net.mrphan.calibre.reader.tts.nghe.NgheEpubVoiceRef> voices =
                net.mrphan.calibre.reader.tts.nghe.NgheEpubModelImporter.INSTANCE
                        .allVoiceRefs(requireContext());
        if (voices.isEmpty()) {
            prefNgheEpubDefaultVoice.setEnabled(false);
            prefNgheEpubDefaultVoice.setSummary(R.string.pref_ngheepubtts_default_voice_summary_empty);
            return;
        }
        prefNgheEpubDefaultVoice.setEnabled(true);
        CharSequence[] entries = new CharSequence[voices.size()];
        CharSequence[] values = new CharSequence[voices.size()];
        for (int i = 0; i < voices.size(); i++) {
            entries[i] = voices.get(i).getDisplayName();
            values[i] = voices.get(i).getVoiceId();
        }
        prefNgheEpubDefaultVoice.setEntries(entries);
        prefNgheEpubDefaultVoice.setEntryValues(values);
        net.mrphan.calibre.reader.tts.nghe.NgheEpubModelStore store =
                new net.mrphan.calibre.reader.tts.nghe.NgheEpubModelStore(requireContext());
        String current = store.getDefaultVoiceId();
        if (current != null) prefNgheEpubDefaultVoice.setValue(current);
        prefNgheEpubDefaultVoice.setSummary(prefNgheEpubDefaultVoice.getEntry());
    }

    /** Noi 2 Preference "pref_auto_sync_interval" / "pref_auto_sync_remotes" (Settings > Ket noi >
     * Dong bo tu dong) voi AppLibraryAutoSyncScheduler -- moi thay doi deu goi
     * applySettingsChanged() ngay lap tuc de ap dung chu ky/danh sach remote moi, khong doi app
     * khoi dong lai. Chi ap dung cho SettingsTab.CONNECTION vi 2 pref nay chi ton tai trong
     * preference_connection.xml -- findPreference() tra ve null o cac tab khac nen guard bang if. */
    private void setupAutoSyncPreferences() {
        androidx.preference.ListPreference prefInterval = findPreference("pref_auto_sync_interval");
        androidx.preference.MultiSelectListPreference prefRemotes = findPreference("pref_auto_sync_remotes");
        // FIX (bam khong co tac dung gi): dong "So phut tuy chinh" truoc day chi la 1 Preference
        // hien thi tinh, khong co OnPreferenceClickListener nao ca -- dialog nhap so phut
        // (showCustomIntervalDialog() ben duoi) truoc day CHI duoc mo tu su kien chon muc
        // "Tuy chinh..." trong dropdown "Chu ky tu dong dong bo" lan dau; sau khi da chon "custom"
        // roi, bam lai vao dong nay de SUA so phut thi khong lam gi ca. Gan click listener de mo
        // lai dung dialog do bat ky luc nao.
        androidx.preference.Preference prefCustomDisplay = findPreference("pref_auto_sync_interval_custom_display");
        if (prefInterval == null || prefRemotes == null) return;

        if (prefCustomDisplay != null) {
            prefCustomDisplay.setOnPreferenceClickListener(preference -> {
                showCustomIntervalDialog(prefInterval);
                return true;
            });
        }

        // Nap dong danh sach remote ho tro sync tu CloudRemoteStore -- xem javadoc entries/entryValues
        // trong preference_connection.xml muc 1.1.
        java.util.List<CloudRemote> syncableRemotes = new java.util.ArrayList<>();
        for (CloudRemote r : CloudRemoteStore.getAll(requireContext())) {
            if (r.getType().supportsDeviceLibrarySync()) syncableRemotes.add(r);
        }
        CharSequence[] entries = new CharSequence[syncableRemotes.size()];
        CharSequence[] entryValues = new CharSequence[syncableRemotes.size()];
        for (int i = 0; i < syncableRemotes.size(); i++) {
            entries[i] = syncableRemotes.get(i).getDisplayName();
            entryValues[i] = syncableRemotes.get(i).getId();
        }
        prefRemotes.setEntries(entries);
        prefRemotes.setEntryValues(entryValues);
        prefRemotes.setValues(AppLibraryAutoSyncPrefs.getSelectedRemoteIds(requireContext()));
        updateAutoSyncRemotesSummary(prefRemotes, syncableRemotes.size());
        prefRemotes.setEnabled(!syncableRemotes.isEmpty());
        if (syncableRemotes.isEmpty()) {
            prefRemotes.setSummary(R.string.pref_auto_sync_remotes_summary_none);
        }

        updateAutoSyncIntervalSummary(prefInterval);

        prefInterval.setOnPreferenceChangeListener((preference, newValue) -> {
            String value = (String) newValue;
            if ("custom".equals(value)) {
                showCustomIntervalDialog(prefInterval);
                // Tra ve false: KHONG luu "custom" ngay -- doi dialog xac nhan so phut hop le roi
                // showCustomIntervalDialog() tu goi setValue("custom") + applySettingsChanged().
                return false;
            }
            prefInterval.setValue(value);
            updateAutoSyncIntervalSummary(prefInterval);
            AppLibraryAutoSyncScheduler.applySettingsChanged(requireContext());
            return false; // da tu setValue() o tren, tra ve false de tranh ListPreference luu 2 lan
        });

        prefRemotes.setOnPreferenceChangeListener((preference, newValue) -> {
            @SuppressWarnings("unchecked")
            java.util.Set<String> selected = (java.util.Set<String>) newValue;
            updateAutoSyncRemotesSummary(prefRemotes, syncableRemotes.size());
            AppLibraryAutoSyncScheduler.applySettingsChanged(requireContext());
            return true; // de MultiSelectListPreference tu luu gia tri moi vao SharedPreferences
        });
    }

    private void updateAutoSyncIntervalSummary(androidx.preference.ListPreference prefInterval) {
        String value = prefInterval.getValue();
        boolean isCustom = "custom".equals(value);
        // FIX (dong "So phut tuy chinh" luon hien du dang chon chu ky nao): truoc day
        // pref_auto_sync_interval_custom_display chi dua vao android:dependency trong XML de
        // an/hien -- nhung dependency cua Preference framework chi hoat dong voi TwoStatePreference
        // (checkbox/switch) lam "nguon", khong ap dung cho ListPreference nhu pref_auto_sync_interval
        // nen dong nay luon hien bat ke gia tri dang chon. Tu tay an/hien o day (goi lai moi khi
        // ham nay chay -- tuc la moi lan gia tri/chu ky doi, xem cac noi goi ben duoi) thay the
        // hoan toan cho dependency khong hoat dong trong XML.
        androidx.preference.Preference prefCustomDisplay = findPreference("pref_auto_sync_interval_custom_display");
        if (prefCustomDisplay != null) prefCustomDisplay.setVisible(isCustom);

        if (isCustom) {
            int customMinutes = AppLibraryAutoSyncPrefs.getCustomIntervalMinutes(requireContext());
            prefInterval.setSummary(getString(R.string.pref_auto_sync_interval_custom_title)
                    + ": " + customMinutes + " " + getString(R.string.pref_auto_sync_interval_10min).replaceAll("[0-9]", "").trim());
        } else {
            prefInterval.setSummary(prefInterval.getEntry());
        }
    }

    private void updateAutoSyncRemotesSummary(androidx.preference.MultiSelectListPreference prefRemotes, int totalCount) {
        if (totalCount == 0) return;
        int selectedCount = AppLibraryAutoSyncPrefs.getSelectedRemoteIds(requireContext()).size();
        if (selectedCount >= totalCount) {
            prefRemotes.setSummary(getString(R.string.pref_auto_sync_remotes_summary_all, totalCount));
        } else {
            prefRemotes.setSummary(getString(R.string.pref_auto_sync_remotes_summary_partial, selectedCount, totalCount));
        }
    }

    /** Dialog nhap so phut tuy chinh khi chon "Tuy chinh..." trong ListPreference chu ky. Dung
     * AlertDialog + EditText thuong (khong dung DialogPreference rieng) vi chi can validate 1 so
     * nguyen duong, khong can luu tru phuc tap gi them. */
    private void showCustomIntervalDialog(androidx.preference.ListPreference prefInterval) {
        android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint(R.string.pref_auto_sync_interval_custom_dialog_hint);
        input.setText(String.valueOf(AppLibraryAutoSyncPrefs.getCustomIntervalMinutes(requireContext())));
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.pref_auto_sync_interval_custom_dialog_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    int minutes;
                    try {
                        minutes = Integer.parseInt(input.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        minutes = 0;
                    }
                    if (minutes <= 0) {
                        Toast.makeText(requireContext(), R.string.pref_auto_sync_interval_custom_invalid, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    AppLibraryAutoSyncPrefs.setCustomIntervalMinutes(requireContext(), minutes);
                    prefInterval.setValue("custom");
                    updateAutoSyncIntervalSummary(prefInterval);
                    AppLibraryAutoSyncScheduler.applySettingsChanged(requireContext());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void onScanNghiModelsClicked() {
        net.mrphan.calibre.reader.tts.nghi.NghiModelStore store =
                new net.mrphan.calibre.reader.tts.nghi.NghiModelStore(requireContext());
        String uriString = store.getModelDirUri();
        if (uriString == null) {
            Toast.makeText(requireContext(), R.string.toast_nghitts_choose_folder_first, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(requireContext(), R.string.pref_nghitts_scan_now_title, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                java.util.List<net.mrphan.calibre.reader.tts.nghi.NghiImportedModel> models =
                        net.mrphan.calibre.reader.tts.nghi.NghiModelImporter.INSTANCE
                                .scanAndImport(requireContext().getApplicationContext(), android.net.Uri.parse(uriString));
                requireActivity().runOnUiThread(() -> {
                    Toast.makeText(requireContext(),
                            getString(R.string.toast_nghitts_scan_result, models.size()),
                            Toast.LENGTH_LONG).show();
                    refreshNghiDefaultModelList();
                });
            } catch (Exception e) {
                Logger.e(e);
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), R.string.toast_nghitts_scan_error, Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void onScanNgheEpubModelsClicked() {
        net.mrphan.calibre.reader.tts.nghe.NgheEpubModelStore store =
                new net.mrphan.calibre.reader.tts.nghe.NgheEpubModelStore(requireContext());
        String uriString = store.getModelDirUri();
        if (uriString == null) {
            Toast.makeText(requireContext(), R.string.toast_ngheepubtts_choose_folder_first, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(requireContext(), R.string.pref_ngheepubtts_scan_now_title, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                java.util.List<net.mrphan.calibre.reader.tts.nghe.NgheEpubImportedModel> models =
                        net.mrphan.calibre.reader.tts.nghe.NgheEpubModelImporter.INSTANCE
                                .scanAndImport(requireContext().getApplicationContext(), android.net.Uri.parse(uriString));
                requireActivity().runOnUiThread(() -> {
                    int voices = net.mrphan.calibre.reader.tts.nghe.NgheEpubModelImporter.INSTANCE
                            .allVoiceRefs(requireContext()).size();
                    Toast.makeText(requireContext(),
                            getString(R.string.toast_ngheepubtts_scan_result, models.size(), voices),
                            Toast.LENGTH_LONG).show();
                    refreshNgheEpubDefaultVoiceList();
                });
            } catch (Exception e) {
                Logger.e(e);
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), R.string.toast_ngheepubtts_scan_error, Toast.LENGTH_LONG).show());
            }
        }).start();
    }

}
