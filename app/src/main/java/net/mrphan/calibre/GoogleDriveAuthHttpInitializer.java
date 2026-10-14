package net.mrphan.calibre;

import android.content.Context;

import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;

import java.io.IOException;

/**
 * Thay thế GoogleAccountCredential: gắn header "Authorization: Bearer <access_token>" vào mỗi
 * request Drive v3 REST, tự refresh token qua CloudTokenManager nếu cần. KHÔNG phụ thuộc
 * Play Services / SHA-1 / package signature -- chỉ cần refresh_token đã lưu.
 */
final class GoogleDriveAuthHttpInitializer implements HttpRequestInitializer {

    private final Context appContext;

    GoogleDriveAuthHttpInitializer(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @Override
    public void initialize(HttpRequest request) throws IOException {
        String accessToken = CloudTokenManager.getInstance(appContext, CloudRemoteType.GOOGLE_DRIVE)
                .getValidAccessTokenBlocking(appContext);
        request.getHeaders().setAuthorization("Bearer " + accessToken);
    }
}
