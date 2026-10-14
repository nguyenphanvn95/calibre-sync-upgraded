package net.mrphan.calibre;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.content.ContextCompat;

import net.openid.appauth.AuthorizationException;
import net.openid.appauth.AuthorizationRequest;

import java.io.IOException;
import java.util.Map;

/**
 * Ban tong quat hoa cua GoogleDriveOAuthActivity - dung chung cho Google Drive, OneDrive, Box,
 * va Dropbox, nhan CloudRemoteType qua Intent extra roi tu chon OAuthProviderSpec/CloudOAuthManager
 * tuong ung. Toan bo logic Chrome Custom Tabs + loopback server (OAuthLoopbackService) giu nguyen 100%
 * so ban cu Google Drive - xem javadoc cu cua GoogleDriveOAuthActivity (da xoa) neu can doi
 * chieu: Google chan cung moi WebView nhung cho OAuth (disallowed_useragent), nen dung Chrome
 * Custom Tabs (dung engine/cookie jar Chrome that); vi khong con WebView de "doc trom" URL
 * redirect, app bat redirect qua mot HTTP server noi bo chay tren loopback, song ben trong mot
 * foreground Service rieng (OAuthLoopbackService) de tranh bi he thong kill giua luc Custom Tabs
 * dang mo.
 * <p>
 * Tra ket qua ve caller (AddCloudRemoteDialogFragment) qua setResult(): RESULT_OK +
 * EXTRA_ACCOUNT_LABEL neu dang nhap + doi token thanh cong; RESULT_CANCELED neu nguoi dung dong
 * Custom Tabs ma khong hoan tat; RESULT_OAUTH_ERROR + EXTRA_ERROR_MESSAGE neu co loi mang/loi doi
 * token.
 */
public class CloudOAuthActivity extends AppCompatActivity {

    static final String EXTRA_REMOTE_TYPE = "remote_type";
    static final String EXTRA_ACCOUNT_LABEL = "account_label";
    static final String EXTRA_ERROR_MESSAGE = "error_message";
    static final int RESULT_OAUTH_ERROR = RESULT_FIRST_USER;

    private CloudRemoteType remoteType;
    private OAuthProviderSpec spec;
    private CloudOAuthManager oauthManager;

    private AuthorizationRequest authorizationRequest;
    private OAuthLoopbackService loopbackService;
    /** true giua luc bindService() duoc goi thanh cong va luc unbindService() duoc goi. */
    private boolean serviceBound = false;
    /** true sau khi da goi launchUrl() mo Custom Tabs. */
    private boolean customTabsLaunched = false;
    /** true khi luong da ket thuc (thanh cong / loi / huy). */
    private boolean flowFinished = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            loopbackService = ((OAuthLoopbackService.LocalBinder) binder).getService();
            onLoopbackServiceReady();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            loopbackService = null;
            if (!flowFinished) {
                finishWithError(getString(serverStartFailedStringRes()));
            }
        }
    };

    static Intent createIntent(Context context, CloudRemoteType type) {
        Intent intent = new Intent(context, CloudOAuthActivity.class);
        intent.putExtra(EXTRA_REMOTE_TYPE, type.name());
        return intent;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.Theme_Calibre_CloudBrowser);
        setContentView(R.layout.activity_cloud_oauth);

        String typeName = getIntent().getStringExtra(EXTRA_REMOTE_TYPE);
        remoteType = typeName != null ? CloudRemoteType.valueOf(typeName) : CloudRemoteType.GOOGLE_DRIVE;
        spec = OAuthProviderSpec.forType(remoteType);
        oauthManager = new CloudOAuthManager(spec);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(stringForType(
                    R.string.gdrive_oauth_title, R.string.onedrive_oauth_title, R.string.box_oauth_title,
                    R.string.dropbox_oauth_title));
        }
        toolbar.setNavigationOnClickListener(v -> finishCancelled());

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finishCancelled();
            }
        });

        Intent serviceIntent = new Intent(this, OAuthLoopbackService.class);
        serviceIntent.putExtra(OAuthLoopbackService.EXTRA_REDIRECT_PATH, OAuthProviderSpec.REDIRECT_PATH);
        serviceIntent.putExtra(OAuthLoopbackService.EXTRA_PROVIDER_DISPLAY_NAME, getString(
                stringForType(R.string.cloud_type_google_drive, R.string.cloud_card_onedrive,
                        R.string.cloud_card_box, R.string.cloud_card_dropbox)));
        // startForegroundService() truoc de Service song doc lap voi vong doi bind, roi
        // bindService() de lay IBinder + doc cong dang lang nghe.
        ContextCompat.startForegroundService(this, serviceIntent);
        serviceBound = bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        if (!serviceBound) {
            Logger.e(new IOException("bindService(OAuthLoopbackService) returned false"));
            Toast.makeText(this, serverStartFailedStringRes(), Toast.LENGTH_LONG).show();
            finishWithError(getString(serverStartFailedStringRes()));
        }
    }

    /** Helper dung chung cho moi noi can chon 1 trong 4 string resource theo remoteType, tranh
     * lap lai ternary/if-else 3 nhanh cu khi them nhanh thu 4 (Dropbox). */
    private int stringForType(int googleRes, int onedriveRes, int boxRes, int dropboxRes) {
        if (remoteType == CloudRemoteType.GOOGLE_DRIVE) return googleRes;
        if (remoteType == CloudRemoteType.ONEDRIVE) return onedriveRes;
        if (remoteType == CloudRemoteType.BOX) return boxRes;
        return dropboxRes;
    }

    private int serverStartFailedStringRes() {
        return stringForType(R.string.gdrive_oauth_server_start_failed,
                R.string.onedrive_oauth_server_start_failed, R.string.box_oauth_server_start_failed,
                R.string.dropbox_oauth_server_start_failed);
    }

    private int missingCodeStringRes() {
        return stringForType(R.string.gdrive_oauth_missing_code,
                R.string.onedrive_oauth_missing_code, R.string.box_oauth_missing_code,
                R.string.dropbox_oauth_missing_code);
    }

    /** Goi tu ServiceConnection#onServiceConnected() ngay khi co IBinder cua OAuthLoopbackService
     * - server NanoHTTPD ben trong Service duoc khoi dong ngay khi nhan duoc Intent trong
     * onStartCommand() (xem OAuthLoopbackService), nen tai day chi can doc cong, dang ky
     * listener, roi mo Custom Tabs. */
    private void onLoopbackServiceReady() {
        if (flowFinished || loopbackService == null) return;

        IOException startupError = loopbackService.getStartupError();
        Integer port = loopbackService.getListeningPort();
        if (startupError != null || port == null) {
            Logger.e(startupError != null ? startupError
                    : new IOException("OAuthLoopbackService started without a listening port"));
            Toast.makeText(this, serverStartFailedStringRes(), Toast.LENGTH_LONG).show();
            finishWithError(getString(serverStartFailedStringRes()));
            return;
        }

        loopbackService.setRedirectListener(this::onRedirectReceivedOnServerThread);

        if (customTabsLaunched) {
            return;
        }

        Uri redirectUri = Uri.parse("http://127.0.0.1:" + port + OAuthProviderSpec.REDIRECT_PATH);
        authorizationRequest = oauthManager.buildAuthorizationRequest(redirectUri);

        new CustomTabsIntent.Builder().build().launchUrl(this, authorizationRequest.toUri());
        customTabsLaunched = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (customTabsLaunched && !flowFinished) {
            finishCancelled();
        }
    }

    private void onRedirectReceivedOnServerThread(Map<String, String> queryParams) {
        runOnUiThread(() -> handleRedirect(queryParams));
    }

    private void handleRedirect(Map<String, String> queryParams) {
        if (flowFinished) return;
        flowFinished = true;
        shutdownLoopbackService();

        Intent bringToFront = new Intent(this, CloudOAuthActivity.class);
        bringToFront.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(bringToFront);

        String error = queryParams.get("error");
        if (error != null) {
            finishCancelledAlreadyFlagged();
            return;
        }
        String code = queryParams.get("code");
        if (code == null) {
            finishWithErrorAlreadyFlagged(getString(missingCodeStringRes()));
            return;
        }

        oauthManager.exchangeAuthorizationCode(this, code, authorizationRequest,
                new CloudOAuthManager.ExchangeCallback() {
                    @Override
                    public void onSuccess(CloudOAuthManager.TokenResult result) {
                        CloudTokenManager.getInstance(getApplicationContext(), remoteType)
                                .saveTokens(result.accessToken, result.refreshToken, result.expiresAtMillis);
                        Intent data = new Intent();
                        data.putExtra(EXTRA_ACCOUNT_LABEL, getString(stringForType(
                                R.string.cloud_type_google_drive, R.string.cloud_card_onedrive,
                                R.string.cloud_card_box, R.string.cloud_card_dropbox)));
                        setResult(RESULT_OK, data);
                        finish();
                    }

                    @Override
                    public void onError(String errorMessage) {
                        finishWithErrorAlreadyFlagged(errorMessage);
                    }
                });
    }

    private void finishCancelled() {
        flowFinished = true;
        shutdownLoopbackService();
        finishCancelledAlreadyFlagged();
    }

    private void finishCancelledAlreadyFlagged() {
        setResult(RESULT_CANCELED);
        finish();
    }

    private void finishWithError(String message) {
        flowFinished = true;
        shutdownLoopbackService();
        finishWithErrorAlreadyFlagged(message);
    }

    private void finishWithErrorAlreadyFlagged(String message) {
        Logger.e(new AuthorizationException(0, 0, message, null, null, null));
        Intent data = new Intent();
        data.putExtra(EXTRA_ERROR_MESSAGE, message);
        setResult(RESULT_OAUTH_ERROR, data);
        finish();
    }

    private void shutdownLoopbackService() {
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        if (loopbackService != null) {
            loopbackService.shutdown();
            loopbackService = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        shutdownLoopbackService();
    }
}
