package net.mrphan.calibre;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;

import java.io.File;

/**
 * Centralizes where books live on disk.
 * <p>
 * Historically this app stored books inside the app-specific external files directory
 * (Android/data/net.mrphan.calibre/files/eBooks), which needs no runtime permission but is
 * not user-browsable and is wiped on uninstall (see CHANGELOG_MIGRATION.md / README "Known
 * issues"). This class implements the SAF-picker-plus-MANAGE_EXTERNAL_STORAGE approach: the
 * user browses to any folder with the system's ACTION_OPEN_DOCUMENT_TREE picker (see
 * SettingsActivity), we resolve the returned tree Uri down to a plain java.io.File path once,
 * and store *that* absolute path in preferences. From then on every existing java.io.File-based
 * call site (CalibreService, BookCrawler, OpenBookActivity, CollectionFragment) keeps working
 * completely unchanged -- the only difference from before is what path setDirectories() computes,
 * and that reading/writing it now relies on the "All files access" (MANAGE_EXTERNAL_STORAGE)
 * permission instead of scoped-storage-exempt app-private storage.
 */
public class StorageUtils {

    public static final String PREF_ROOT_DIR_PATH = "pref_root_dir_path";

    /** Settings > Giao dien: "Show title and progress in grid view" -- one CheckBoxPreference
     * shared by both tabs (see preference_appearance.xml), only affects Grid view mode. Kept
     * here as a single source rather than re-typing the literal in CollectionFragment/
     * BookRecyclerAdapter/OPDSBrowserFragment/BookCursorAdapter, mirroring PREF_ROOT_DIR_PATH
     * above. */
    public static final String PREF_GRID_SHOW_TITLE_PROGRESS = "pref_grid_show_title_progress";
    public static final String PREF_GRID_DISPLAY_STYLE = "pref_grid_display_style";

    /**
     * @return the user's configured book folder, or the default (eBooks/ next to Android/ on
     * the device's primary internal storage -- a sibling of the Android/data folder, not inside
     * it) if none has been chosen yet.
     */
    public static File getRootDirectory(Context context, SharedPreferences settings) {
        String saved = settings.getString(PREF_ROOT_DIR_PATH, null);
        if (saved != null && !saved.isEmpty()) {
            return new File(saved);
        }
        return getDefaultRootDirectory();
    }

    public static File getDefaultRootDirectory() {
        return new File(Environment.getExternalStorageDirectory(), "eBooks");
    }

    /** @return true if the app currently has the access it needs to read/write an arbitrary
     * folder in shared storage (i.e. outside its own app-specific directory). Always true below
     * API 30, where the classic WRITE_EXTERNAL_STORAGE runtime permission is used instead. */
    public static boolean hasBroadStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return true;
    }

    /**
     * Resolves a tree Uri returned by ACTION_OPEN_DOCUMENT_TREE down to a plain java.io.File,
     * so the rest of the app can keep using ordinary File I/O. Works for the primary internal
     * storage volume and for any other mounted volume (SD card, USB OTG) the device exposes via
     * StorageManager. Returns null if the volume can't be resolved (e.g. a cloud "documents
     * provider" with no real filesystem backing).
     */
    public static File resolveTreeUriToFile(Context context, Uri treeUri) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            int colon = docId.indexOf(':');
            if (colon < 0) return null;
            String volumeId = docId.substring(0, colon);
            String relativePath = docId.substring(colon + 1);
            File volumeRoot = getVolumeRootFile(context, volumeId);
            if (volumeRoot == null) return null;
            return relativePath.isEmpty() ? volumeRoot : new File(volumeRoot, relativePath);
        } catch (Exception e) {
            Logger.e(e);
            return null;
        }
    }

    private static File getVolumeRootFile(Context context, String volumeId) {
        if ("primary".equals(volumeId)) {
            return Environment.getExternalStorageDirectory();
        }
        // Non-primary volumes (SD card, USB OTG): there's no public API mapping a
        // DocumentsContract volume id straight to a filesystem path, so match it against the
        // UUID of each mounted StorageVolume instead. StorageVolume#getDirectory() is only
        // public from API 30, and #getPath() is a hidden method not present in the compileSdk
        // stubs at all, so instead the root is derived from getExternalFilesDirs(), which is
        // public API back to minSdk 24: each entry is
        // <volumeRoot>/Android/data/<package>/files, so stripping that known suffix recovers
        // the root, and StorageManager#getStorageVolume(File) (also API 24+) identifies which
        // volume each entry belongs to.
        StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        if (sm == null) return null;
        File[] filesDirs = context.getExternalFilesDirs(null);
        if (filesDirs == null) return null;
        String suffix = File.separator + "Android" + File.separator + "data" + File.separator
                + context.getPackageName() + File.separator + "files";
        for (File filesDir : filesDirs) {
            if (filesDir == null) continue;
            StorageVolume vol = sm.getStorageVolume(filesDir);
            if (vol == null) continue;
            String uuid = vol.getUuid();
            if (uuid != null && uuid.equalsIgnoreCase(volumeId)) {
                String path = filesDir.getAbsolutePath();
                if (path.endsWith(suffix)) {
                    return new File(path.substring(0, path.length() - suffix.length()));
                }
            }
        }
        return null;
    }

    /** Readiness state of the book folder, used by CollectionFragment to decide whether to show
     * the empty-state (invite to pick a folder / grant permission) or the real book list. */
    public enum RootDirState {
        NOT_CONFIGURED,   // No folder ever chosen (no PREF_ROOT_DIR_PATH) AND no broad storage
                          // access either -- treated as a first-time app open.
        NO_PERMISSION,    // A folder is chosen (or the default is in use) but access permission
                          // is missing.
        DIR_MISSING,      // Permission is present but the saved folder no longer exists/can't be
                          // read (deleted, renamed, SD card removed...).
        READY             // Permission is present and the folder exists and is readable/writable.
    }

    public static RootDirState getRootDirState(Context context, SharedPreferences settings) {
        if (!hasBroadStorageAccess()) {
            return RootDirState.NO_PERMISSION;
        }
        String saved = settings.getString(PREF_ROOT_DIR_PATH, null);
        File dir = getRootDirectory(context, settings);
        if (saved == null || saved.isEmpty()) {
            // No folder ever chosen via SAF -- still treated as "not configured" even though the
            // default folder (eBooks/) may already exist, so the user is proactively invited to
            // confirm/choose a folder instead of silently falling back to the default.
            return dir.exists() ? RootDirState.READY : RootDirState.NOT_CONFIGURED;
        }
        if (!dir.exists() || !dir.canRead()) {
            return RootDirState.DIR_MISSING;
        }
        return RootDirState.READY;
    }
}
