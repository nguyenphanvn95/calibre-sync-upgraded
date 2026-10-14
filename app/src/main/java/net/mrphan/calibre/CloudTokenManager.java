package net.mrphan.calibre;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.EnumMap;
import java.util.Map;

/**
 * Ban tong quat hoa cua GoogleDriveTokenManager - mot instance rieng cho MOI CloudRemoteType (moi
 * loai co file EncryptedSharedPreferences RIENG, vd "calibre_oauth_encrypted_GOOGLE_DRIVE" va
 * "calibre_oauth_encrypted_ONEDRIVE"), de dang xuat mot provider khong dung vo tinh xoa token cua
 * provider con lai. Logic luu/refresh/xoa token giu nguyen 100% so ban cu (GoogleDriveTokenManager),
 * cung pattern voi CsCredentialStore.java (file rieng, tach khoi
 * PreferenceManager.getDefaultSharedPreferences()).
 */
final class CloudTokenManager {

    private static final String PREFS_FILE_PREFIX = "calibre_oauth_encrypted_";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";
    private static final String KEY_EXPIRES_AT = "expires_at_millis";

    /** Tru hao 60s truoc khi het han that, de tranh truong hop access_token het han giua luc dang
     * gui request API. */
    private static final long EXPIRY_SAFETY_MARGIN_MILLIS = 60_000L;

    private static final Map<CloudRemoteType, CloudTokenManager> instances = new EnumMap<>(CloudRemoteType.class);

    private final CloudRemoteType type;
    private final SharedPreferences prefs;

    static synchronized CloudTokenManager getInstance(Context context, CloudRemoteType type) {
        CloudTokenManager existing = instances.get(type);
        if (existing != null) return existing;
        CloudTokenManager created = new CloudTokenManager(context.getApplicationContext(), type);
        instances.put(type, created);
        return created;
    }

    private CloudTokenManager(Context context, CloudRemoteType type) {
        this.type = type;
        SharedPreferences p;
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            p = EncryptedSharedPreferences.create(
                    context,
                    PREFS_FILE_PREFIX + type.name(),
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (GeneralSecurityException | IOException e) {
            // Cung chien luoc fail-safe voi CsCredentialStore: khong roi ve luu plaintext, chi
            // don gian la khong luu duoc token (nguoi dung phai dang nhap lai moi lan mo app).
            Logger.e(e);
            p = null;
        }
        this.prefs = p;
    }

    synchronized void saveTokens(String accessToken, String refreshToken, long expiresAtMillis) {
        if (prefs == null) return;
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_ACCESS_TOKEN, accessToken);
        // refresh_token: chi duoc tra ve o lan cap quyen DAU TIEN (hoac khi
        // access_type=offline + prompt=consent) - neu response khong co refresh_token moi (vd
        // dang refresh access_token), giu nguyen refresh_token cu da luu.
        if (refreshToken != null && !refreshToken.isEmpty()) {
            editor.putString(KEY_REFRESH_TOKEN, refreshToken);
        }
        editor.putLong(KEY_EXPIRES_AT, expiresAtMillis);
        editor.apply();
    }

    synchronized String getRefreshToken() {
        return prefs == null ? null : prefs.getString(KEY_REFRESH_TOKEN, null);
    }

    /** Fast, local-only check (khong goi mang) dung cho ConnectionFragment#refreshProviderCards. */
    boolean isSignedIn() {
        return getRefreshToken() != null;
    }

    /** @return access_token con han dung, tu refresh qua mang neu can. PHAI goi tu thread nen
     * (day la blocking call).
     * @throws IOException neu chua tung dang nhap, hoac refresh_token da bi thu hoi. */
    synchronized String getValidAccessTokenBlocking(Context context) throws IOException {
        if (prefs == null) {
            throw new IOException("Token storage unavailable for " + type);
        }
        String refreshToken = getRefreshToken();
        if (refreshToken == null) {
            throw new IOException("Not signed in to " + type);
        }
        long expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0);
        String accessToken = prefs.getString(KEY_ACCESS_TOKEN, null);
        boolean stillValid = accessToken != null
                && System.currentTimeMillis() < expiresAt - EXPIRY_SAFETY_MARGIN_MILLIS;
        if (stillValid) {
            return accessToken;
        }
        // Het han (hoac chua tung co access_token cache) - refresh ngay bang refresh_token.
        CloudOAuthManager manager = new CloudOAuthManager(OAuthProviderSpec.forType(type));
        CloudOAuthManager.TokenResult result = manager.refreshAccessTokenBlocking(context, refreshToken);
        saveTokens(result.accessToken, result.refreshToken, result.expiresAtMillis);
        return result.accessToken;
    }

    /** Goi khi bam "Dang xuat" - KHONG goi Google Sign-Out/Microsoft sign-out (khong con Play
     * Services / MSAL) - chi xoa du lieu local. Dung chung cho ca Google Drive va OneDrive, khac
     * nhau o `type` duoc truyen vao getInstance(). */
    synchronized void clear() {
        if (prefs == null) return;
        prefs.edit()
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_REFRESH_TOKEN)
                .remove(KEY_EXPIRES_AT)
                .apply();
    }
}
