package net.mrphan.calibre;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Browses one CloudRemote (Google Drive account, or a WebDAV/FTP server) and lets the user
 * download books straight into the app's configured book folder (StorageUtils.getRootDirectory),
 * after which the library is rescanned (CalibreService#rescanBookFolder) so the book shows up in
 * Device Library right away -- the same mechanism the Content Server WebView browser uses, see
 * ContentServerBrowserActivity.
 * <p>
 * Uses a single-thread ExecutorService for every CloudProvider call: CloudProvider's contract is
 * "blocking, caller runs off main thread", and FtpCloudProvider's FTPClient specifically is not
 * thread-safe, so calls onto a given provider instance are never issued concurrently.
 */
public class CloudBrowserActivity extends AppCompatActivity {

    public static final String EXTRA_REMOTE_ID = "remote_id";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private RecyclerView rvFiles;
    private CloudFileAdapter adapter;
    private TextView tvBreadcrumb;
    private View pbLoading;
    private TextView tvEmpty;

    private CloudRemote remote;
    private CloudProvider provider;
    /** Stack of (refId, displayName) folders navigated into, root not included. Used both to
     * implement the back button (pop one level instead of finishing the activity) and to render
     * the breadcrumb. */
    private final Deque<CloudFile> folderStack = new ArrayDeque<>();
    /** Extensions accepted by Settings > Accepted Filetypes, reused verbatim (PHẦN 2 / PHẦN 6:
     * see CalibreService.getAcceptedExtensionsArray(Context)) so a downloaded file's extension
     * is judged by the exact same rule as local library scanning. */
    private Set<String> acceptedExtensions;

    private CalibreService mCalibreService;
    private boolean isBoundToService = false;
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
        setContentView(R.layout.activity_cloud_browser);

        String remoteId = getIntent().getStringExtra(EXTRA_REMOTE_ID);
        List<CloudRemote> all = CloudRemoteStore.getAll(this);
        for (CloudRemote r : all) {
            if (r.getId().equals(remoteId)) remote = r;
        }
        if (remote == null) {
            Toast.makeText(this, R.string.cloud_remote_not_found, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        acceptedExtensions = new HashSet<>(java.util.Arrays.asList(
                CalibreService.getAcceptedExtensionsArray(getApplicationContext())));

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(remote.getDisplayName());
        }
        toolbar.setNavigationOnClickListener(v -> onBackPressedCompat());

        tvBreadcrumb = findViewById(R.id.tvBreadcrumb);
        pbLoading = findViewById(R.id.pbLoading);
        tvEmpty = findViewById(R.id.tvEmpty);
        rvFiles = findViewById(R.id.rvCloudFiles);
        rvFiles.setLayoutManager(new LinearLayoutManager(this));

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onBackPressedCompat();
            }
        });

        String password = CloudRemoteStore.getPassword(this, remote.getId());
        executor.execute(() -> {
            try {
                provider = CloudProviderFactory.create(getApplicationContext(), remote, password);
                CloudFile root = provider.getRoot();
                loadFolder(root, true);
            } catch (Exception e) {
                Logger.e(e);
                mainHandler.post(() -> showLoadError(e));
            }
        });

        Intent serviceIntent = new Intent(this, CalibreService.class);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        isBoundToService = true;
    }

    private void onBackPressedCompat() {
        if (!folderStack.isEmpty()) {
            folderStack.pop();
            CloudFile target = folderStack.isEmpty() ? provider.getRoot() : folderStack.peek();
            loadFolder(target, false);
        } else {
            finish();
        }
    }

    /** @param isRoot true only for the very first load, so the root folder is never pushed onto
     * folderStack (folderStack only ever holds folders navigated INTO past the root). */
    private void loadFolder(CloudFile folder, boolean isRoot) {
        mainHandler.post(() -> {
            pbLoading.setVisibility(View.VISIBLE);
            tvEmpty.setVisibility(View.GONE);
            updateBreadcrumb(folder);
        });
        executor.execute(() -> {
            try {
                List<CloudFile> children = provider.listFiles(folder.getRefId());
                // Folders first, then files, both alphabetical -- easier to scan visually than
                // provider-defined order.
                children.sort((a, b) -> {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                });
                mainHandler.post(() -> {
                    pbLoading.setVisibility(View.GONE);
                    adapter = new CloudFileAdapter(children, this::onFileClick);
                    rvFiles.setAdapter(adapter);
                    tvEmpty.setVisibility(children.isEmpty() ? View.VISIBLE : View.GONE);
                });
            } catch (IOException e) {
                Logger.e(e);
                mainHandler.post(() -> showLoadError(e));
            }
        });
    }

    private void updateBreadcrumb(CloudFile currentFolder) {
        StringBuilder sb = new StringBuilder(remote.getDisplayName());
        List<CloudFile> ordered = new ArrayList<>(folderStack);
        java.util.Collections.reverse(ordered);
        for (CloudFile f : ordered) {
            sb.append(" / ").append(f.getName());
        }
        tvBreadcrumb.setText(sb.toString());
    }

    private void onFileClick(CloudFile file) {
        if (file.isDirectory()) {
            folderStack.push(file);
            loadFolder(file, false);
            return;
        }
        if (file.isGoogleWorkspaceDoc()) {
            Toast.makeText(this, R.string.cloud_workspace_doc_unsupported, Toast.LENGTH_LONG).show();
            return;
        }
        if (!isAcceptedExtension(file.getName())) {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.cloud_unsupported_extension)
                    .setPositiveButton(R.string.cloud_download_anyway, (d, w) -> downloadFile(file))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        downloadFile(file);
    }

    private boolean isAcceptedExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        String ext = name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return acceptedExtensions.contains(ext);
    }

    private void downloadFile(CloudFile file) {
        adapter.setDownloading(file.getRefId(), -1);
        executor.execute(() -> {
            try {
                File destDir = StorageUtils.getRootDirectory(getApplicationContext(),
                        PreferenceManager.getDefaultSharedPreferences(getApplicationContext()));
                if (!destDir.exists() && !destDir.mkdirs()) {
                    throw new IOException("Cannot create book folder: " + destDir);
                }
                File destFile = FileNameUtils.resolveNonConflictingFile(destDir, file.getName());
                provider.downloadFile(file, destFile);
                mainHandler.post(() -> onDownloadSuccess(file, destFile));
            } catch (Exception e) {
                Logger.e(e);
                mainHandler.post(() -> onDownloadFailure(file, e));
            }
        });
    }

    private void onDownloadSuccess(CloudFile file, File destFile) {
        if (adapter != null) adapter.clearDownloading();
        Toast.makeText(this, getString(R.string.cloud_download_success, file.getName()), Toast.LENGTH_SHORT).show();
        if (mCalibreService != null) {
            mCalibreService.rescanBookFolder(newBooksFound -> {
                // No UI here beyond the toast above -- CollectionFragment picks up the new book
                // the next time it's shown, same as after a normal wireless-device sync.
            });
        }
    }

    private void onDownloadFailure(CloudFile file, Exception e) {
        if (adapter != null) adapter.clearDownloading();
        Toast.makeText(this, getString(R.string.cloud_download_failed, file.getName()), Toast.LENGTH_LONG).show();
    }

    private void showLoadError(Exception e) {
        pbLoading.setVisibility(View.GONE);
        tvEmpty.setVisibility(View.VISIBLE);
        tvEmpty.setText(getString(R.string.cloud_load_folder_failed,
                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
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
    protected void onDestroy() {
        super.onDestroy();
        if (isBoundToService) {
            unbindService(serviceConnection);
            isBoundToService = false;
        }
        executor.execute(() -> {
            if (provider != null) provider.close();
        });
        executor.shutdown();
    }
}
