package net.mrphan.calibre;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import androidx.preference.PreferenceManager;
import android.view.MenuItem;
import android.view.View;
import android.webkit.HttpAuthHandler;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Embeds the Calibre Content Server's own web UI in a WebView, reusing exactly the host/port/
 * scheme (pref_cs_url, saved by ConnectServerDialogFragment) and username/password
 * (CsCredentialStore, keyed by host+contentServerPort -- same key ConnectServerDialogFragment
 * uses) already configured for OPDS browsing. No separate login step: PHẦN 6-bis / CLOUD_SETUP.md
 * §2.
 * <p>
 * HTTP Basic Auth is handled the standard WebView way -- onReceivedHttpAuthRequest() answers the
 * server's 401 challenge with the saved credentials, and WebView's own HTTP auth cache then
 * carries them across every subsequent navigation within this WebView automatically, so this
 * class only needs to supply them once per (host, realm) challenge.
 */
public class ContentServerBrowserActivity extends AppCompatActivity {

    /** Injected into the Content Server page after every load. Capture-phase click listener so
     * it sees the click before the page's own handler can stopPropagation() it. Only acts on
     * anchors carrying a "download" attribute -- the standard way a page marks "this click means
     * save-as-file", which is exactly what the book-list app's format links use, whether they
     * point at a real server URL (delegate to native, same as setDownloadListener would've done)
     * or a blob: URL (only reachable from inside the page's own JS context, hence fetch() +
     * FileReader here instead of a second native HTTP request). Idempotent guard so re-running it
     * on every onPageFinished (needed because it's a SPA that never reloads the page after first
     * load, but pbPageLoading/onPageFinished can still fire more than once for iframes etc.)
     * doesn't attach the listener twice. */
    private static final String DOWNLOAD_HOOK_JS = "(function(){\n" +
            "  if (window.__csDownloadHookInstalled) return;\n" +
            "  window.__csDownloadHookInstalled = true;\n" +
            "  document.addEventListener('click', function(ev) {\n" +
            "    var el = ev.target;\n" +
            "    while (el && el !== document) {\n" +
            "      if (el.tagName === 'A' && el.hasAttribute('download') && el.href) {\n" +
            "        var href = el.href;\n" +
            "        var filename = el.getAttribute('download') || '';\n" +
            "        if (href.indexOf('blob:') === 0) {\n" +
            "          ev.preventDefault(); ev.stopPropagation();\n" +
            "          fetch(href).then(function(r){ return r.blob(); }).then(function(blob){\n" +
            "            var reader = new FileReader();\n" +
            "            reader.onloadend = function() {\n" +
            "              var b64 = String(reader.result).split(',')[1] || '';\n" +
            "              AndroidCsBridge.saveBlobData(b64, filename);\n" +
            "            };\n" +
            "            reader.readAsDataURL(blob);\n" +
            "          }).catch(function(){ AndroidCsBridge.reportJsError(filename); });\n" +
            "        } else {\n" +
            "          ev.preventDefault(); ev.stopPropagation();\n" +
            "          AndroidCsBridge.saveRemoteUrl(href, filename);\n" +
            "        }\n" +
            "        return;\n" +
            "      }\n" +
            "      el = el.parentElement;\n" +
            "    }\n" +
            "  }, true);\n" +
            "})();";

    private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WebView webView;
    private ProgressBar pbPageLoading;
    private String csHost;
    private int csPort;
    private String csUsername;
    private String csPassword;
    private Set<String> acceptedExtensions;

    private CalibreService mCalibreService;
    private boolean isBoundToService = false;

    // --- Floating "now playing" TTS panel -- keeps a background "Đọc cho nghe" session visible
    // while the user browses the Content Server here, mirroring Readest's floating audio bar.
    // See net.mrphan.calibre.reader.tts.GlobalTtsFloatingPanel's kdoc.
    private net.mrphan.calibre.reader.tts.GlobalTtsFloatingPanel mTtsFloatingPanel;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mCalibreService = ((CalibreService.CalibreBinder) binder).getService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mCalibreService = null;
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.Theme_Calibre_CloudBrowser);
        setContentView(R.layout.activity_content_server_browser);
        mTtsFloatingPanel = new net.mrphan.calibre.reader.tts.GlobalTtsFloatingPanel(this);

        String savedCsUrl = PreferenceManager
                .getDefaultSharedPreferences(this).getString("pref_cs_url", null);
        URL rootUrl = resolveRootUrl(savedCsUrl);
        if (rootUrl == null) {
            Toast.makeText(this, R.string.content_server_not_connected_hint, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        csHost = rootUrl.getHost();
        csPort = rootUrl.getPort();

        CsCredentialStore credStore = new CsCredentialStore(getApplicationContext());
        csUsername = credStore.getUsername(csHost, csPort);
        csPassword = credStore.getPassword(csHost, csPort);

        acceptedExtensions = new HashSet<>(java.util.Arrays.asList(
                CalibreService.getAcceptedExtensionsArray(getApplicationContext())));

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.cloud_card_content_server);
        }
        toolbar.setNavigationOnClickListener(v -> onBackPressedCompat());

        pbPageLoading = findViewById(R.id.pbPageLoading);
        webView = findViewById(R.id.webView);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
                if (csUsername != null) {
                    handler.proceed(csUsername, csPassword != null ? csPassword : "");
                } else {
                    super.onReceivedHttpAuthRequest(view, handler, host, realm);
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // Self-signed certs are common for a home Content Server -- same trust decision
                // OPDSLoader's HttpsURLConnection path already makes for this app's OPDS
                // browsing (see README "Self-signed certificates"), so the WebView browser isn't
                // any more permissive than the rest of the app.
                handler.proceed();
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pbPageLoading.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pbPageLoading.setVisibility(View.GONE);
                view.evaluateJavascript(DOWNLOAD_HOOK_JS, null);
            }
        });
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) ->
                startDownload(url, contentDisposition));
        // Bug fix: the Content Server's own book-list web app (unlike a plain static page) does
        // NOT trigger a real navigation/HTTP download for its "Download" button -- it's a
        // single-page app that fetches the book as a Blob (via fetch()/XHR) and clicks a
        // throwaway <a download> pointing at a blob: URL entirely in JS, so it can stay on the
        // same page. WebView's setDownloadListener() above only fires for a genuine
        // network-level download (Content-Disposition/unrenderable mimetype on a real HTTP
        // response) and NEVER sees blob: URLs at all -- hence "Download" silently does nothing
        // here even though OPDS (plain HTTP GET, this app's own AsyncDownloader) and Google
        // Drive (native SDK, no WebView involved) both work fine. DOWNLOAD_HOOK_JS below
        // intercepts that click in-page and hands the bytes back to native code instead.
        webView.addJavascriptInterface(new ContentServerDownloadBridge(), "AndroidCsBridge");

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onBackPressedCompat();
            }
        });

        webView.loadUrl(rootUrl.toString());

        Intent serviceIntent = new Intent(this, CalibreService.class);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        isBoundToService = true;
    }

    /** @return scheme://host:port/ derived from the saved pref_cs_url ("scheme://host:port/opds")
     * without depending on a live ConnectionInfo (which may not exist -- see
     * ConnectionInfo#getContentServerRootURL Javadoc), or null if nothing's been saved/it's
     * unparseable. */
    private URL resolveRootUrl(String savedCsUrl) {
        if (savedCsUrl == null || savedCsUrl.isEmpty()) return null;
        try {
            URL opdsUrl = new URL(savedCsUrl);
            return new URL(opdsUrl.getProtocol(), opdsUrl.getHost(), opdsUrl.getPort(), "/");
        } catch (MalformedURLException e) {
            Logger.e(e);
            return null;
        }
    }

    private void onBackPressedCompat() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            finish();
        }
    }

    private void startDownload(String url, String contentDisposition) {
        startDownload(url, contentDisposition, null);
    }

    /** @param filenameHint if non-null/non-empty, an already-known filename (e.g. from a JS
     * &lt;a download="..."&gt; attribute) that takes priority over guessing from the
     * Content-Disposition header or the URL's last path segment -- the Content Server's
     * "/get/FORMAT/book_id/library_id" download URLs don't have a usable filename in the URL
     * itself (the last path segment is the library id, not a filename), so without this hint the
     * old header-only fallback is the only thing standing between a proper "Title.epub" and a
     * useless extension-less file the device library scan would then silently ignore. */
    private void startDownload(String url, String contentDisposition, String filenameHint) {
        Toast.makeText(this, R.string.cloud_downloading, Toast.LENGTH_SHORT).show();
        downloadExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                if (csUsername != null) {
                    String auth = "Basic " + android.util.Base64.encodeToString(
                            (csUsername + ":" + (csPassword != null ? csPassword : "")).getBytes("UTF-8"),
                            android.util.Base64.NO_WRAP);
                    connection.setRequestProperty("Authorization", auth);
                }
                connection.connect();
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IOException("HTTP " + connection.getResponseCode());
                }
                String fileName;
                if (filenameHint != null && !filenameHint.trim().isEmpty()) {
                    fileName = FileNameUtils.resolveFileNameFromResponse(
                            "attachment; filename=\"" + filenameHint.trim() + "\"", url, null);
                } else {
                    String headerDisposition = connection.getHeaderField("Content-Disposition");
                    fileName = FileNameUtils.resolveFileNameFromResponse(
                            headerDisposition != null ? headerDisposition : contentDisposition, url, null);
                }

                File destDir = StorageUtils.getRootDirectory(getApplicationContext(),
                        PreferenceManager.getDefaultSharedPreferences(getApplicationContext()));
                if (!destDir.exists() && !destDir.mkdirs()) {
                    throw new IOException("Cannot create book folder: " + destDir);
                }
                File destFile = FileNameUtils.resolveNonConflictingFile(destDir, fileName);

                try (InputStream in = connection.getInputStream();
                     FileOutputStream out = new FileOutputStream(destFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
                onDownloadSaved(destFile);
            } catch (Exception e) {
                Logger.e(e);
                mainHandler.post(() -> Toast.makeText(this, R.string.cloud_download_failed_generic, Toast.LENGTH_LONG).show());
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    /** Writes already-in-memory bytes (a Blob the JS hook fetched and base64-encoded) straight to
     * the book folder -- the blob: URL that produced them only exists inside the WebView's page
     * context, so unlike startDownload() above there's no second HTTP request to make; this is
     * the whole reason DOWNLOAD_HOOK_JS reads the Blob itself instead of just handing native code
     * a URL. Runs on downloadExecutor (called from ContentServerDownloadBridge, already off the
     * JavaBridge thread). */
    private void saveDownloadedBytes(byte[] data, String suggestedFilename) {
        try {
            String fileName = (suggestedFilename == null || suggestedFilename.trim().isEmpty())
                    ? FileNameUtils.resolveFileNameFromResponse(null, null, ".epub")
                    : FileNameUtils.resolveFileNameFromResponse(
                            "attachment; filename=\"" + suggestedFilename.trim() + "\"", null, null);

            File destDir = StorageUtils.getRootDirectory(getApplicationContext(),
                    PreferenceManager.getDefaultSharedPreferences(getApplicationContext()));
            if (!destDir.exists() && !destDir.mkdirs()) {
                throw new IOException("Cannot create book folder: " + destDir);
            }
            File destFile = FileNameUtils.resolveNonConflictingFile(destDir, fileName);
            try (FileOutputStream out = new FileOutputStream(destFile)) {
                out.write(data);
            }
            onDownloadSaved(destFile);
        } catch (Exception e) {
            Logger.e(e);
            mainHandler.post(() -> Toast.makeText(this, R.string.cloud_download_failed_generic, Toast.LENGTH_LONG).show());
        }
    }

    private void onDownloadSaved(File destFile) {
        mainHandler.post(() -> {
            Toast.makeText(this, getString(R.string.cloud_download_success, destFile.getName()), Toast.LENGTH_SHORT).show();
            if (mCalibreService != null) {
                mCalibreService.rescanBookFolder(newBooksFound -> { });
            }
        });
    }

    /** JS <-> native bridge for DOWNLOAD_HOOK_JS, registered as "AndroidCsBridge". Methods here
     * run on the WebView's JavaBridge thread (not the UI thread), so all work is handed off to
     * downloadExecutor immediately, same convention as GDriveLibraryJsBridge. */
    private class ContentServerDownloadBridge {
        @JavascriptInterface
        public void saveBlobData(String base64Data, String suggestedFilename) {
            downloadExecutor.execute(() -> {
                try {
                    byte[] data = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT);
                    saveDownloadedBytes(data, suggestedFilename);
                } catch (Exception e) {
                    Logger.e(e);
                    mainHandler.post(() -> Toast.makeText(ContentServerBrowserActivity.this,
                            R.string.cloud_download_failed_generic, Toast.LENGTH_LONG).show());
                }
            });
        }

        @JavascriptInterface
        public void saveRemoteUrl(String url, String suggestedFilename) {
            mainHandler.post(() -> startDownload(url, null, suggestedFilename));
        }

        @JavascriptInterface
        public void reportJsError(String filename) {
            mainHandler.post(() -> Toast.makeText(ContentServerBrowserActivity.this,
                    R.string.cloud_download_failed_generic, Toast.LENGTH_LONG).show());
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressedCompat();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mTtsFloatingPanel != null) mTtsFloatingPanel.attach();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mTtsFloatingPanel != null) mTtsFloatingPanel.detach();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBoundToService) {
            unbindService(serviceConnection);
            isBoundToService = false;
        }
        downloadExecutor.shutdown();
        if (webView != null) webView.destroy();
    }
}
