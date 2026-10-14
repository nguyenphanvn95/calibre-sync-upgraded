package net.mrphan.calibre;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Mo ta mot provider OAuth 2.0 Authorization Code Flow + PKCE (Google Drive hoac OneDrive), dung
 * de tham so hoa CloudOAuthManager/CloudOAuthActivity thay vi viet rieng mot cap class cho tung
 * provider. Khong luu token o day - chi la cau hinh tinh, giong vai tro cu cua
 * GoogleDriveOAuthConfig nhung dung chung cho ca hai loai remote.
 * <p>
 * Duong dan redirect tren loopback server la mot hang so DUNG CHUNG (REDIRECT_PATH) - chi mot
 * loopback server chay tai mot thoi diem, nen khong xung dot du 2 provider cung dung chung path.
 */
final class OAuthProviderSpec {

    /** Duong dan co dinh tren server loopback noi bo ma ca Google va Microsoft se redirect ve sau
     * khi nguoi dung dang nhap/cap quyen xong - xem OAuthLoopbackServer#serve(). */
    static final String REDIRECT_PATH = "/oauth2redirect";

    final CloudRemoteType type;
    final String clientId;
    /** null cho OneDrive (public client, khong co client_secret) - CHI Google Drive can gia tri
     * nay (loai OAuth Client "Desktop app" cua Google van yeu cau client_secret ngay ca voi PKCE). */
    final String clientSecretOrNull;
    final String authEndpoint;
    final String tokenEndpoint;
    final String scope;
    /** "prompt=consent", "access_type=offline" cho Google; rong cho OneDrive (offline_access da
     * nam trong scope la du de nhan refresh_token). */
    final Map<String, String> extraAuthParams;

    private OAuthProviderSpec(CloudRemoteType type, String clientId, String clientSecretOrNull,
                               String authEndpoint, String tokenEndpoint, String scope,
                               Map<String, String> extraAuthParams) {
        this.type = type;
        this.clientId = clientId;
        this.clientSecretOrNull = clientSecretOrNull;
        this.authEndpoint = authEndpoint;
        this.tokenEndpoint = tokenEndpoint;
        this.scope = scope;
        this.extraAuthParams = extraAuthParams;
    }

    static OAuthProviderSpec forGoogleDrive() {
        Map<String, String> extras = new HashMap<>();
        extras.put("access_type", "offline");
        return new OAuthProviderSpec(
                CloudRemoteType.GOOGLE_DRIVE,
                BuildConfig.GOOGLE_DRIVE_CLIENT_ID,
                BuildConfig.GOOGLE_DRIVE_CLIENT_SECRET,
                "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token",
                "https://www.googleapis.com/auth/drive",
                extras);
    }

    /**
     * Dang ky tren portal.azure.com -> App registrations -> New registration:
     * 1. Supported account types: "Accounts in any organizational directory and personal
     *    Microsoft accounts" (ho tro ca OneDrive ca nhan lan OneDrive for Business).
     * 2. Authentication -> Add a platform -> "Mobile and desktop applications" -> tick
     *    "http://localhost" trong danh sach goi y (khong tu go them cong so - Microsoft bo qua
     *    phan cong khi so khop redirect URI dang loopback).
     * 3. API permissions -> Add a permission -> Microsoft Graph -> Delegated permissions ->
     *    Files.ReadWrite, offline_access, User.Read.
     * 4. Copy "Application (client) ID" thay vao REPLACE_WITH_YOUR_AZURE_APP_CLIENT_ID.
     * Khong can tao client secret - loai platform nay la public client.
     */
    static OAuthProviderSpec forOneDrive() {
        return new OAuthProviderSpec(
                CloudRemoteType.ONEDRIVE,
                "REPLACE_WITH_YOUR_AZURE_APP_CLIENT_ID",
                null,
                "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
                "https://login.microsoftonline.com/common/oauth2/v2.0/token",
                "https://graph.microsoft.com/Files.ReadWrite offline_access https://graph.microsoft.com/User.Read",
                Collections.emptyMap());
    }

    /**
     * Dang ky tai https://cloud.app.box.com/developers/console -> Create New App ->
     * "Custom App" -> Authentication method: "User Authentication (OAuth 2.0)".
     * 1. Tab Configuration -> OAuth 2.0 Redirect URI: them dung "http://localhost/oauth2redirect"
     *    (giong het Google Drive/OneDrive, dung chung REDIRECT_PATH o tren).
     * 2. Application Scopes: tick "Read and write all files and folders stored in Box"
     *    (root_readwrite). KHONG can bat "Manage users"/webhook, khong can submit review vi app
     *    chi dung cho tai khoan ca nhan cua nguoi dung (giong mo hinh Google Drive/OneDrive o day).
     * 3. Copy Client ID + Client Secret vao 2 hang so ben duoi -- khac OneDrive, Box BAT BUOC
     *    client_secret ngay ca voi luong Authorization Code (khong co che do public-client-only
     *    nhu OneDrive), nen OAuthProviderSpec nay set clientSecretOrNull khac null, cung nhanh xu
     *    ly voi Google Drive trong CloudOAuthManager (da co san, khong can sua).
     */
    static OAuthProviderSpec forBox() {
        return new OAuthProviderSpec(
                CloudRemoteType.BOX,
                "REPLACE_WITH_YOUR_BOX_CLIENT_ID",
                "REPLACE_WITH_YOUR_BOX_CLIENT_SECRET",
                "https://account.box.com/api/oauth2/authorize",
                "https://api.box.com/oauth2/token",
                "root_readwrite",
                Collections.emptyMap());
    }

    /**
     * Dang ky tren https://www.dropbox.com/developers/apps -> Create app -> chon "Scoped access"
     * va "Full Dropbox" (hoac "App folder" neu muon gioi han pham vi). 1. Tab Settings -> OAuth 2
     * -> muc "Redirect URIs": them dung "http://localhost/oauth2redirect" (giong het Google
     * Drive/OneDrive/Box, dung chung REDIRECT_PATH o tren). 2. Tab Permissions -> tick
     * "files.metadata.write", "files.metadata.read", "files.content.write",
     * "files.content.read" roi bam Submit. 3. Copy "App key" vao hang so ben duoi thay cho
     * REPLACE_WITH_YOUR_DROPBOX_APP_KEY - KHONG can "App secret": Dropbox ho tro PKCE cho public
     * client (giong OneDrive), nen clientSecretOrNull de null la du, khac voi Box.
     * <p>
     * extraAuthParams co "token_access_type=offline" - BAT BUOC voi Dropbox de nhan duoc
     * refresh_token; neu thieu tham so nay Dropbox chi tra access_token song ngan han (~4 gio) ma
     * khong co refresh_token, khien CloudTokenManager#getValidAccessTokenBlocking khong the tu
     * lam moi duoc sau khi het han.
     */
    static OAuthProviderSpec forDropbox() {
        Map<String, String> extras = new HashMap<>();
        extras.put("token_access_type", "offline");
        return new OAuthProviderSpec(
                CloudRemoteType.DROPBOX,
                "txjev524hdzy123",
                null,
                "https://www.dropbox.com/oauth2/authorize",
                "https://api.dropboxapi.com/oauth2/token",
                "files.metadata.write files.metadata.read files.content.write files.content.read account_info.read",
                extras);
    }

    static OAuthProviderSpec forType(CloudRemoteType type) {
        switch (type) {
            case GOOGLE_DRIVE:
                return forGoogleDrive();
            case ONEDRIVE:
                return forOneDrive();
            case BOX:
                return forBox();
            case DROPBOX:
                return forDropbox();
            default:
                throw new IllegalArgumentException("Khong phai provider OAuth: " + type);
        }
    }
}
