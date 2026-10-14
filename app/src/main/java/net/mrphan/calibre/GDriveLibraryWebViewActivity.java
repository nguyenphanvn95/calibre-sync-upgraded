package net.mrphan.calibre;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Mở 1 GDriveOpdsLibrary đã lưu: khởi động GDriveOpdsHttpServer (tải/đồng bộ metadata_public.json
 * nếu cần), rồi nhúng 1 WebView trỏ vào URL local đó ("/") -- server tự trả về asset tĩnh
 * (index.html/app.js, xem GDriveOpdsHttpServer#handleStaticAsset) render UI "Thư viện mini" thay
 * vì giả lập OPDS feed như GDriveOpdsLibraryBrowserActivity (bản cũ) từng làm. Mô phỏng sát cấu
 * trúc ContentServerBrowserActivity, nhưng dùng 1 JavascriptInterface (AndroidBridge) thay cho
 * WebView.setDownloadListener, vì tải sách vào Device Library ở đây cần đủ metadata tra lại từ
 * GDriveLibraryDb theo bookId (xem GDriveLibraryJsBridge), không chỉ byte thô + tên file như
 * Content Server. */
public class GDriveLibraryWebViewActivity extends AppCompatActivity
        implements GDriveLibraryInfoBottomSheetFragment.OnLibraryRefreshedListener {

    public static final String EXTRA_LIBRARY_ID = "library_id";
    public static final String EXTRA_FORCE_REFRESH = "force_refresh";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private String libraryId;
    private ProgressBar pbSyncing;
    private TextView tvSyncingStatus;
    private View webViewGroup;
    private ProgressBar pbPageLoading;
    private WebView webView;
    private GDriveLibraryJsBridge jsBridge;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gdrive_library_webview);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> onBackPressedCompat());

        pbSyncing = findViewById(R.id.pbSyncing);
        tvSyncingStatus = findViewById(R.id.tvSyncingStatus);
        webViewGroup = findViewById(R.id.webViewGroup);
        pbPageLoading = findViewById(R.id.pbPageLoading);
        webView = findViewById(R.id.webView);

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onBackPressedCompat();
            }
        });

        libraryId = getIntent().getStringExtra(EXTRA_LIBRARY_ID);
        boolean forceRefresh = getIntent().getBooleanExtra(EXTRA_FORCE_REFRESH, false);
        GDriveOpdsLibrary library = libraryId != null ? GDriveOpdsLibraryStore.getById(this, libraryId) : null;

        if (library == null) {
            Toast.makeText(this, R.string.gdrive_opds_library_not_found, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (getSupportActionBar() != null) getSupportActionBar().setTitle(library.getDisplayName());
        startServerAndOpen(library, forceRefresh);
    }

    private void onBackPressedCompat() {
        if (webView != null && webViewGroup.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
        } else {
            finish();
        }
    }

    private void startServerAndOpen(GDriveOpdsLibrary library, boolean forceRefresh) {
        setSyncingUiVisible(true);
        executor.execute(() -> {
            try {
                int port = GDriveOpdsHttpServer.getInstance().start(getApplicationContext(), library, forceRefresh);
                mainHandler.post(() -> {
                    if (isFinishing()) return;
                    setSyncingUiVisible(false);
                    setupWebView(port);
                });
            } catch (Exception e) {
                Logger.e(e);
                String message = errorMessageFor(e);
                mainHandler.post(() -> {
                    setSyncingUiVisible(false);
                    Toast.makeText(GDriveLibraryWebViewActivity.this, message, Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        });
    }

    private void setupWebView(int port) {
        GDriveLibraryDb db = GDriveOpdsHttpServer.getInstance().getDb();
        if (db == null) {
            Toast.makeText(this, R.string.gdrive_opds_sync_failed_generic, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pbPageLoading.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pbPageLoading.setVisibility(View.GONE);
            }
        });

        jsBridge = new GDriveLibraryJsBridge(this, db, port, webView, libraryId);
        webView.addJavascriptInterface(jsBridge, "AndroidBridge");

        webView.loadUrl("http://127.0.0.1:" + port + "/");
    }

    private String errorMessageFor(Exception e) {
        if (e instanceof GDriveFileException) {
            return e.getMessage();
        }
        return getString(R.string.gdrive_opds_sync_failed_generic);
    }

    private void setSyncingUiVisible(boolean visible) {
        pbSyncing.setVisibility(visible ? View.VISIBLE : View.GONE);
        tvSyncingStatus.setVisibility(visible ? View.VISIBLE : View.GONE);
        webViewGroup.setVisibility(visible ? View.GONE : View.VISIBLE);
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.menu_gdrive_library_webview, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == R.id.action_library_info) {
            GDriveLibraryInfoBottomSheetFragment.newInstance(libraryId)
                    .show(getSupportFragmentManager(), "gdrive_library_info");
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onLibraryDataRefreshed() {
        // Nap lai du lieu trong WebView (khong reload ca trang) sau khi Sync phat hien thay doi.
        if (webView != null) webView.evaluateJavascript("loadLibrary();", null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        if (jsBridge != null) jsBridge.shutdown();
        if (webView != null) webView.destroy();
        // Dừng server khi rời màn duyệt sách để giải phóng cổng + đóng DB — mở lại thư viện lần
        // sau sẽ start() lại nhanh (cache SQLite vẫn còn, needsResync() trả false nếu chưa đổi
        // driveFileId, chỉ mất thời gian bind lại cổng).
        GDriveOpdsHttpServer.getInstance().stop();
    }
}
