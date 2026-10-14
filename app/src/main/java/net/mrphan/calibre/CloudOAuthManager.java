package net.mrphan.calibre;

import android.content.Context;
import android.net.Uri;

import net.openid.appauth.AuthorizationException;
import net.openid.appauth.AuthorizationRequest;
import net.openid.appauth.AuthorizationService;
import net.openid.appauth.AuthorizationServiceConfiguration;
import net.openid.appauth.GrantTypeValues;
import net.openid.appauth.ResponseTypeValues;
import net.openid.appauth.TokenRequest;
import net.openid.appauth.TokenResponse;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Ban tong quat hoa cua GoogleDriveOAuthManager - nhan OAuthProviderSpec thay vi hard-code hang
 * so Google, dung chung cho ca Google Drive va OneDrive. Logic AppAuth (PKCE, doi code lay token,
 * refresh token) giu nguyen 100% so ban cu, chi tham so hoa endpoint/clientId/clientSecret/scope.
 * <p>
 * CHI dung AppAuth cho phan build URL (buildAuthorizationRequest) + goi HTTP toi token endpoint
 * (performTokenRequest) - KHONG dung AuthorizationService#performAuthorizationRequest(request,
 * successIntent, cancelIntent), vi ham do can mot Activity co intent-filter khai bao trong
 * manifest de bat redirect, trong khi app nay bat redirect qua HTTP server loopback tu viet
 * (OAuthLoopbackServer) de tuong thich ca 2 loai public/confidential client - xem
 * OAuthProviderSpec.
 */
final class CloudOAuthManager {

    private final OAuthProviderSpec spec;
    private final AuthorizationServiceConfiguration serviceConfig;

    CloudOAuthManager(OAuthProviderSpec spec) {
        this.spec = spec;
        this.serviceConfig = new AuthorizationServiceConfiguration(
                Uri.parse(spec.authEndpoint), Uri.parse(spec.tokenEndpoint));
    }

    static final class TokenResult {
        final String accessToken;
        final String refreshToken;
        final long expiresAtMillis;

        TokenResult(String accessToken, String refreshToken, long expiresAtMillis) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    interface ExchangeCallback {
        void onSuccess(TokenResult result);

        void onError(String errorMessage);
    }

    /** Authorization URL de mo trong Chrome Custom Tabs (xem CloudOAuthActivity); bao gom PKCE
     * (AuthorizationRequest.Builder tu sinh code_verifier/code_challenge mac dinh). */
    AuthorizationRequest buildAuthorizationRequest(Uri redirectUri) {
        AuthorizationRequest.Builder builder = new AuthorizationRequest.Builder(
                serviceConfig, spec.clientId, ResponseTypeValues.CODE, redirectUri)
                .setScope(spec.scope);
        if (!spec.extraAuthParams.isEmpty()) {
            builder.setAdditionalParameters(spec.extraAuthParams);
        }
        if (spec.type == CloudRemoteType.GOOGLE_DRIVE) {
            // Google can "prompt=consent" de dam bao luon nhan refresh_token - "prompt" la tham
            // so duoc AppAuth ho tro truc tiep qua setPrompt() (dua vao
            // setAdditionalParameters() se nem IllegalArgumentException ngay khi build()).
            // Microsoft khong can tham so nay - offline_access trong scope la du.
            builder.setPrompt("consent");
        }
        return builder.build();
    }

    /** Goi tren main thread (CloudOAuthActivity) ngay sau khi bat duoc "code" tu redirect URL -
     * AuthorizationService#performTokenRequest tu lam viec bat dong bo tren thread nen cua chinh
     * no, khong can boc them executor o day. */
    void exchangeAuthorizationCode(Context context, String authorizationCode,
                                    AuthorizationRequest originalRequest,
                                    ExchangeCallback callback) {
        AuthorizationService service = new AuthorizationService(context.getApplicationContext());
        TokenRequest.Builder tokenRequestBuilder = new TokenRequest.Builder(serviceConfig, spec.clientId)
                .setGrantType(GrantTypeValues.AUTHORIZATION_CODE)
                .setAuthorizationCode(authorizationCode)
                // Phai khop Y HET redirect_uri da dung o buoc xin quyen (bat buoc theo chuan
                // OAuth) - originalRequest da luu san URI loopback (co cong) tu luc build.
                .setRedirectUri(originalRequest.redirectUri)
                .setCodeVerifier(originalRequest.codeVerifier);
        if (spec.clientSecretOrNull != null) {
            tokenRequestBuilder.setAdditionalParameters(
                    Collections.singletonMap("client_secret", spec.clientSecretOrNull));
        }
        service.performTokenRequest(tokenRequestBuilder.build(), (TokenResponse response, AuthorizationException ex) -> {
            service.dispose();
            if (ex != null || response == null) {
                callback.onError(ex != null ? ex.errorDescription : "Unknown token exchange error");
                return;
            }
            callback.onSuccess(new TokenResult(
                    response.accessToken,
                    response.refreshToken,
                    response.accessTokenExpirationTime != null
                            ? response.accessTokenExpirationTime
                            : System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1)));
        });
    }

    /** Blocking - chi goi tu thread nen (xem GoogleDriveAuthHttpInitializer/
     * OneDriveAuthHttpInitializer, hoac gian tiep qua CloudTokenManager#getValidAccessTokenBlocking). */
    TokenResult refreshAccessTokenBlocking(Context context, String refreshToken) throws java.io.IOException {
        AuthorizationService service = new AuthorizationService(context.getApplicationContext());
        TokenRequest.Builder tokenRequestBuilder = new TokenRequest.Builder(serviceConfig, spec.clientId)
                .setGrantType(GrantTypeValues.REFRESH_TOKEN)
                .setRefreshToken(refreshToken);
        if (spec.clientSecretOrNull != null) {
            tokenRequestBuilder.setAdditionalParameters(
                    Collections.singletonMap("client_secret", spec.clientSecretOrNull));
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<TokenResponse> responseRef = new AtomicReference<>();
        AtomicReference<AuthorizationException> errorRef = new AtomicReference<>();
        service.performTokenRequest(tokenRequestBuilder.build(), (response, ex) -> {
            responseRef.set(response);
            errorRef.set(ex);
            latch.countDown();
        });
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new java.io.IOException("Timed out refreshing " + spec.type + " access token");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException("Interrupted while refreshing " + spec.type + " access token", e);
        } finally {
            service.dispose();
        }
        if (errorRef.get() != null || responseRef.get() == null) {
            throw new java.io.IOException("Refresh token exchange failed: "
                    + (errorRef.get() != null ? errorRef.get().errorDescription : "empty response")
                    + " - refresh_token may have been revoked, user must sign in again");
        }
        TokenResponse response = responseRef.get();
        return new TokenResult(
                response.accessToken,
                // Ca Google lan Microsoft thuong KHONG tra refresh_token moi khi refresh
                // access_token - giu refresh_token cu (CloudTokenManager#saveTokens da xu ly
                // truong hop null).
                response.refreshToken,
                response.accessTokenExpirationTime != null
                        ? response.accessTokenExpirationTime
                        : System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1));
    }
}
