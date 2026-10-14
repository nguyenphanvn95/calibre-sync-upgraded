package net.mrphan.calibre;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists the user's configured Cloud Libraries remotes (WebDAV/FTP servers, and the fact
 * that a Google Drive account is linked). Copies CsCredentialStore's EncryptedSharedPreferences
 * pattern exactly -- same MasterKey/AES256_GCM + AES256_SIV/AES256_GCM scheme, same fail-safe
 * "log and treat as empty" behaviour if the keystore is somehow unusable on this device -- kept
 * in its own encrypted file so a WebDAV/FTP password never ends up in plaintext
 * SharedPreferences alongside pref_cs_url and friends.
 *
 * Everything except the password lives in a single JSON array under "remotes_json". Passwords
 * are kept in their own per-remote keys ("cloud_password_"+id) so listing/renaming remotes never
 * needs to touch password material. GOOGLE_DRIVE remotes never have a password here -- OMH
 * manages its own sign-in token internally via Credential Manager/AccountManager, this class
 * never sees or stores it.
 */
public class CloudRemoteStore {

    private static final String PREFS_FILE_NAME = "calibre_cloud_remotes_encrypted";
    private static final String KEY_REMOTES_JSON = "remotes_json";
    private static final String KEY_PASSWORD_PREFIX = "cloud_password_";

    private static SharedPreferences open(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context.getApplicationContext())
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    context.getApplicationContext(),
                    PREFS_FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (GeneralSecurityException | IOException e) {
            // Same fail-safe degradation as CsCredentialStore: don't fall back to plaintext,
            // just don't persist. The Cloud Libraries cards will behave as if nothing was ever
            // added, rather than crashing or leaking secrets.
            Logger.e(e);
            return null;
        }
    }

    public static List<CloudRemote> getAll(Context context) {
        List<CloudRemote> result = new ArrayList<>();
        SharedPreferences prefs = open(context);
        if (prefs == null) return result;
        String json = prefs.getString(KEY_REMOTES_JSON, null);
        if (json == null || json.isEmpty()) return result;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                try {
                    result.add(CloudRemote.fromJson(arr.getJSONObject(i)));
                } catch (JSONException e) {
                    Logger.e(e);
                }
            }
        } catch (JSONException e) {
            Logger.e(e);
        }
        return dedupeGoogleDrive(prefs, result);
    }

    /** Tu doi voi cac ban ghi GOOGLE_DRIVE bi trung lap da ton tai san (vd tu ban build truoc,
     * khi moi lan dang nhap lai tao 1 id ngau nhien moi) -- app chi ho tro 1 tai khoan Google
     * Drive tai 1 thoi diem nen chi giu lai ban ghi GOOGLE_DRIVE cuoi cung (moi nhat) va ghi lai
     * xuong dia ngay, de nguoi dung khong con thay 2 muc "Google Drive" giong het nhau trong
     * dialog chon sync nua ma khong can dang xuat/dang nhap lai. */
    private static List<CloudRemote> dedupeGoogleDrive(SharedPreferences prefs, List<CloudRemote> all) {
        int googleDriveCount = 0;
        for (CloudRemote r : all) {
            if (r.getType() == CloudRemoteType.GOOGLE_DRIVE) googleDriveCount++;
        }
        if (googleDriveCount <= 1) return all;

        List<CloudRemote> deduped = new ArrayList<>();
        CloudRemote lastGoogleDrive = null;
        for (CloudRemote r : all) {
            if (r.getType() == CloudRemoteType.GOOGLE_DRIVE) {
                lastGoogleDrive = r; // giu ban cuoi cung gap duoc
            } else {
                deduped.add(r);
            }
        }
        if (lastGoogleDrive != null) deduped.add(lastGoogleDrive);
        writeAll(prefs, deduped);
        return deduped;
    }

    /** Adds (or replaces, matched by id) a remote and, if passwordOrNull is non-null,
     * (over)writes its password. Pass null for GOOGLE_DRIVE remotes, which have no password. */
    static void save(Context context, CloudRemote remote, String passwordOrNull) {
        SharedPreferences prefs = open(context);
        if (prefs == null) return;
        List<CloudRemote> all = getAll(context);
        List<CloudRemote> updated = new ArrayList<>();
        for (CloudRemote r : all) {
            if (r.getId().equals(remote.getId())) continue;
            // Chi 1 tai khoan Google Drive tai 1 thoi diem -- neu remote moi la GOOGLE_DRIVE,
            // bo qua moi ban ghi GOOGLE_DRIVE cu khac id (phong truong hop caller lo tao id
            // ngau nhien moi thay vi tai su dung id cu), tranh tich luy trung lap.
            if (remote.getType() == CloudRemoteType.GOOGLE_DRIVE && r.getType() == CloudRemoteType.GOOGLE_DRIVE) continue;
            updated.add(r);
        }
        updated.add(remote);
        writeAll(prefs, updated);
        if (passwordOrNull != null) {
            prefs.edit().putString(KEY_PASSWORD_PREFIX + remote.getId(), passwordOrNull).apply();
        }
    }

    static void delete(Context context, String id) {
        SharedPreferences prefs = open(context);
        if (prefs == null) return;
        List<CloudRemote> all = getAll(context);
        List<CloudRemote> updated = new ArrayList<>();
        for (CloudRemote r : all) {
            if (!r.getId().equals(id)) updated.add(r);
        }
        writeAll(prefs, updated);
        prefs.edit().remove(KEY_PASSWORD_PREFIX + id).apply();
    }

    static String getPassword(Context context, String id) {
        SharedPreferences prefs = open(context);
        if (prefs == null) return null;
        return prefs.getString(KEY_PASSWORD_PREFIX + id, null);
    }

    private static void writeAll(SharedPreferences prefs, List<CloudRemote> remotes) {
        JSONArray arr = new JSONArray();
        for (CloudRemote r : remotes) {
            try {
                arr.put(r.toJson());
            } catch (JSONException e) {
                Logger.e(e);
            }
        }
        prefs.edit().putString(KEY_REMOTES_JSON, arr.toString()).apply();
    }
}
