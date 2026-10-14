package net.mrphan.calibre;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/** Lưu danh sách thư viện Calibre-on-GDrive đã thêm. KHÔNG cần EncryptedSharedPreferences như
 * CloudRemoteStore (WebDAV/FTP có password) — driveFileId của 1 file đã public "Anyone with the
 * link" không phải bí mật, nên dùng SharedPreferences thường là đủ, giống mức bảo mật của chính
 * cơ chế "Anyone with the link" trên Google Drive. */
class GDriveOpdsLibraryStore {

    private static final String PREFS_FILE_NAME = "calibre_gdrive_opds_libraries";
    private static final String KEY_LIBRARIES_JSON = "libraries_json";

    private static SharedPreferences open(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE);
    }

    static List<GDriveOpdsLibrary> getAll(Context context) {
        List<GDriveOpdsLibrary> result = new ArrayList<>();
        String json = open(context).getString(KEY_LIBRARIES_JSON, null);
        if (json == null || json.isEmpty()) return result;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                try {
                    result.add(GDriveOpdsLibrary.fromJson(arr.getJSONObject(i)));
                } catch (JSONException e) {
                    Logger.e(e);
                }
            }
        } catch (JSONException e) {
            Logger.e(e);
        }
        return result;
    }

    static GDriveOpdsLibrary getById(Context context, String id) {
        for (GDriveOpdsLibrary lib : getAll(context)) {
            if (lib.getId().equals(id)) return lib;
        }
        return null;
    }

    static void save(Context context, GDriveOpdsLibrary library) {
        List<GDriveOpdsLibrary> all = getAll(context);
        List<GDriveOpdsLibrary> updated = new ArrayList<>();
        for (GDriveOpdsLibrary lib : all) {
            if (!lib.getId().equals(library.getId())) updated.add(lib);
        }
        updated.add(library);
        writeAll(context, updated);
    }

    static void delete(Context context, String id) {
        List<GDriveOpdsLibrary> all = getAll(context);
        List<GDriveOpdsLibrary> updated = new ArrayList<>();
        for (GDriveOpdsLibrary lib : all) {
            if (!lib.getId().equals(id)) updated.add(lib);
        }
        writeAll(context, updated);
        // Xóa luôn cache SQLite + file DB tương ứng, tránh rác lại trên máy.
        GDriveLibraryDb.deleteDbFile(context, id);
    }

    private static void writeAll(Context context, List<GDriveOpdsLibrary> libraries) {
        JSONArray arr = new JSONArray();
        for (GDriveOpdsLibrary lib : libraries) {
            try {
                arr.put(lib.toJson());
            } catch (JSONException e) {
                Logger.e(e);
            }
        }
        open(context).edit().putString(KEY_LIBRARIES_JSON, arr.toString()).apply();
    }
}
