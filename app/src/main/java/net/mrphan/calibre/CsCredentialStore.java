package net.mrphan.calibre;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * Stores the Calibre Content Server ("OPDS") Basic Auth username/password using
 * androidx.security's EncryptedSharedPreferences, deliberately kept in its own file and
 * separate from PreferenceManager.getDefaultSharedPreferences() (which stores everything else,
 * unencrypted, as before -- see README "OPDS Basic Auth").
 *
 * Credentials are keyed by "host:cs_port" so switching between Content Servers (e.g. a laptop
 * at home vs. a desktop at the office) doesn't overwrite each other's saved login.
 */
class CsCredentialStore {

    private static final String PREFS_FILE_NAME = "calibre_cs_credentials_encrypted";
    private static final String KEY_USERNAME_PREFIX = "cs_username_";
    private static final String KEY_PASSWORD_PREFIX = "cs_password_";

    private final SharedPreferences prefs;

    CsCredentialStore(Context context) {
        SharedPreferences p;
        try {
            MasterKey masterKey = new MasterKey.Builder(context.getApplicationContext())
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            p = EncryptedSharedPreferences.create(
                    context.getApplicationContext(),
                    PREFS_FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (GeneralSecurityException | IOException e) {
            // Should not happen on any real device/API level this app supports (API 24+), but
            // if the keystore is somehow unusable, fail safe: don't persist credentials in
            // plaintext as a fallback, just don't persist them at all. The user will need to
            // re-enter them each time the app restarts, which is a safe degradation rather
            // than a silent plaintext leak.
            Logger.e(e);
            p = null;
        }
        prefs = p;
    }

    private static String hostKey(String host, int csPort) {
        return host + ":" + csPort;
    }

    void save(String host, int csPort, String username, String password) {
        if (prefs == null) return;
        SharedPreferences.Editor editor = prefs.edit();
        String key = hostKey(host, csPort);
        if (username == null || username.isEmpty()) {
            editor.remove(KEY_USERNAME_PREFIX + key);
            editor.remove(KEY_PASSWORD_PREFIX + key);
        } else {
            editor.putString(KEY_USERNAME_PREFIX + key, username);
            editor.putString(KEY_PASSWORD_PREFIX + key, password != null ? password : "");
        }
        editor.apply();
    }

    String getUsername(String host, int csPort) {
        if (prefs == null) return null;
        return prefs.getString(KEY_USERNAME_PREFIX + hostKey(host, csPort), null);
    }

    String getPassword(String host, int csPort) {
        if (prefs == null) return null;
        return prefs.getString(KEY_PASSWORD_PREFIX + hostKey(host, csPort), null);
    }

    void clear(String host, int csPort) {
        save(host, csPort, null, null);
    }
}
