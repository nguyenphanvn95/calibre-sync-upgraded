package net.mrphan.calibre;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dialog "Thông tin thư viện cloud" cho 4 loại remote hỗ trợ Device Library Sync (Google Drive,
 * OneDrive, Box, Dropbox -- xem CloudRemoteType#supportsDeviceLibrarySync). Thay thế hành vi cũ
 * của ConnectionFragment#onOAuthCloudCardClick khi remote ĐÃ đăng nhập: trước đây bấm vào card
 * mở thẳng CloudBrowserActivity, giờ mở dialog này trước -- vẫn giữ đường vào
 * CloudBrowserActivity qua nút "Duyệt file" bên dưới để KHÔNG mất tính năng cũ.
 * <p>
 * Nút "Đồng bộ" enqueue DeviceLibrarySyncWorker.DIRECTION_FULL_SYNC (upload TOÀN BỘ sách cục bộ
 * đang đổi/mới hơn, rồi tải về mọi sách đang thiếu trên máy) qua
 * DeviceLibrarySyncScheduler#enqueueFullSync, và tự observe WorkInfo của CHÍNH job đó.
 */
public class CloudRemoteInfoBottomSheetFragment extends BottomSheetDialogFragment {

    private static final String ARG_REMOTE_ID = "remote_id";
    private static final String TAG = "CloudRemoteInfoSheet";

    /** Triển khai bởi Fragment cha (ConnectionFragment), KHÔNG phải Activity -- dialog này luôn
     * được mở qua getChildFragmentManager() của ConnectionFragment nên phải lấy listener từ
     * getParentFragment(), khác với GDriveLibraryInfoBottomSheetFragment (mở từ 1 Activity nên
     * cast được thẳng trong onAttach(Context)). */
    public interface OnCloudRemoteChangedListener {
        void onCloudRemoteChanged();
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView tvRemoteTitle;
    private TextView tvLastSync;
    private Button btnSync;
    private ProgressBar pbSyncing;
    private TextView tvBookCount, tvAuthorCount, tvTagCount;
    private Button btnBrowseFiles;
    private Button btnDisconnect;
    private Button btnCheckOrphans;

    /** true trong luc dang "Check" hoac dang xoa muc mo coi -- dung chung voi isSyncing (bien
     * ngam trong setSyncing/pbSyncing/btnSync) de khoa lan nhau, tranh 2 thao tac cung dung
     * CloudProvider tren cung 1 instance (vd FTP khong thread-safe, xem javadoc CloudProvider). */
    private boolean isChecking;

    @Nullable
    private UUID pendingSyncWorkId;

    public static CloudRemoteInfoBottomSheetFragment newInstance(String remoteId) {
        CloudRemoteInfoBottomSheetFragment f = new CloudRemoteInfoBottomSheetFragment();
        Bundle args = new Bundle();
        args.putString(ARG_REMOTE_ID, remoteId);
        f.setArguments(args);
        return f;
    }

    private String remoteId() {
        return getArguments() != null ? getArguments().getString(ARG_REMOTE_ID) : null;
    }

    @Nullable
    private CloudRemote findRemote() {
        if (getContext() == null) return null;
        String id = remoteId();
        if (id == null) return null;
        for (CloudRemote r : CloudRemoteStore.getAll(getContext())) {
            if (r.getId().equals(id)) return r;
        }
        return null;
    }

    @Nullable
    private OnCloudRemoteChangedListener listener() {
        Fragment parent = getParentFragment();
        return (parent instanceof OnCloudRemoteChangedListener) ? (OnCloudRemoteChangedListener) parent : null;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.bottomsheet_cloud_remote_info, container, false);
        tvRemoteTitle = v.findViewById(R.id.tvRemoteTitle);
        tvLastSync = v.findViewById(R.id.tvLastSync);
        btnSync = v.findViewById(R.id.btnSync);
        pbSyncing = v.findViewById(R.id.pbSyncing);
        tvBookCount = v.findViewById(R.id.tvBookCount);
        tvAuthorCount = v.findViewById(R.id.tvAuthorCount);
        tvTagCount = v.findViewById(R.id.tvTagCount);
        btnBrowseFiles = v.findViewById(R.id.btnBrowseFiles);
        btnDisconnect = v.findViewById(R.id.btnDisconnect);
        btnCheckOrphans = v.findViewById(R.id.btnCheckOrphans);

        CloudRemote remote = findRemote();
        if (remote == null) {
            // Remote vừa bị xoá/đăng xuất ở nơi khác giữa lúc dialog đang mở -- không còn gì để
            // hiện, đóng ngay thay vì crash NPE bên dưới.
            dismissAllowingStateLoss();
            return v;
        }
        tvRemoteTitle.setText(remote.getDisplayName());

        btnSync.setOnClickListener(view -> onSyncClicked());
        btnBrowseFiles.setOnClickListener(view -> onBrowseFilesClicked());
        btnDisconnect.setOnClickListener(view -> onDisconnectClicked());
        // Chỉ 4 loại remote hỗ trợ Device Library Sync mới có cấu trúc books/<uuid>/ +
        // calibre_sync_manifest.json để đối chiếu -- xem mục 6 của prompt nâng cấp.
        btnCheckOrphans.setVisibility(
                remote.getType().supportsDeviceLibrarySync() ? View.VISIBLE : View.GONE);
        btnCheckOrphans.setOnClickListener(view -> onCheckOrphansClicked());

        updateLastSyncLabel();
        loadLocalSummary();
        return v;
    }

    private void updateLastSyncLabel() {
        if (tvLastSync == null || getContext() == null) return;
        long millis = CloudRemoteSyncPrefs.getLastSyncMillis(getContext(), remoteId());
        if (millis <= 0) {
            tvLastSync.setText(R.string.cloud_remote_info_never_synced);
            return;
        }
        String formatted = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT,
                Locale.getDefault()).format(new Date(millis));
        tvLastSync.setText(getString(R.string.cloud_remote_info_last_sync, formatted));
    }

    private void loadLocalSummary() {
        if (getContext() == null) return;
        Context appContext = getContext().getApplicationContext();
        executor.execute(() -> {
            MetadataDatabaseHelper db = new MetadataDatabaseHelper(appContext);
            try {
                MetadataDatabaseHelper.LocalLibrarySummary summary = db.getLocalLibrarySummary();
                mainHandler.post(() -> bindSummary(summary));
            } catch (Exception e) {
                // Trước đây không có catch: nếu query lỗi, task chết âm thầm, mainHandler.post
                // không bao giờ chạy -> 3 ô Sách/Tác giả/Thẻ đứng yên rỗng mãi mãi không rõ lý do.
                Log.e(TAG, "Lỗi khi tải thống kê thư viện cục bộ (Sách/Tác giả/Thẻ)", e);
                mainHandler.post(this::bindSummaryError);
            } finally {
                db.close();
            }
        });
    }

    private void bindSummary(MetadataDatabaseHelper.LocalLibrarySummary s) {
        if (tvBookCount == null) return; // View đã bị huỷ (dialog đóng trước khi query xong)
        tvBookCount.setText(String.valueOf(s.bookCount));
        tvAuthorCount.setText(String.valueOf(s.authorCount));
        tvTagCount.setText(String.valueOf(s.tagCount));
    }

    private void bindSummaryError() {
        if (tvBookCount == null) return; // View đã bị huỷ (dialog đóng trước khi query xong)
        tvBookCount.setText("-");
        tvAuthorCount.setText("-");
        tvTagCount.setText("-");
    }

    private void onSyncClicked() {
        if (getContext() == null) return;
        String id = remoteId();
        if (id == null) return;
        setSyncing(true);
        UUID workId = DeviceLibrarySyncScheduler.enqueueFullSync(getContext(), id);
        pendingSyncWorkId = workId;
        WorkManager.getInstance(getContext().getApplicationContext())
                .getWorkInfoByIdLiveData(workId)
                .observe(getViewLifecycleOwner(), this::onSyncWorkInfoChanged);
    }

    private void onSyncWorkInfoChanged(@Nullable WorkInfo info) {
        if (info == null || pendingSyncWorkId == null || !pendingSyncWorkId.equals(info.getId())) return;
        if (!info.getState().isFinished()) return; // RUNNING/ENQUEUED/BLOCKED: vẫn đang chờ.
        pendingSyncWorkId = null;
        setSyncing(false);
        if (getContext() == null) return;
        if (info.getState() == WorkInfo.State.SUCCEEDED) {
            updateLastSyncLabel();
            loadLocalSummary();
            Toast.makeText(getContext(), R.string.cloud_remote_info_sync_success, Toast.LENGTH_SHORT).show();
            OnCloudRemoteChangedListener l = listener();
            if (l != null) l.onCloudRemoteChanged();
        } else {
            Toast.makeText(getContext(), R.string.cloud_remote_info_sync_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void setSyncing(boolean syncing) {
        if (pbSyncing == null || btnSync == null) return;
        pbSyncing.setVisibility(syncing ? View.VISIBLE : View.GONE);
        btnSync.setVisibility(syncing ? View.GONE : View.VISIBLE);
        // Khoá Disconnect trong lúc đang có job chạy: clear() token giữa chừng sẽ làm job đang
        // chạy (đã có CloudProvider riêng, không đọc lại token) thất bại với lỗi mơ hồ thay vì
        // lỗi AUTH_FAILED rõ ràng.
        if (btnBrowseFiles != null) btnBrowseFiles.setEnabled(!syncing);
        if (btnDisconnect != null) btnDisconnect.setEnabled(!syncing);
        // Khoá luôn nút "Check" trong lúc đồng bộ -- tránh 2 thao tác cùng đụng CloudProvider
        // cùng lúc trên cùng 1 instance (xem javadoc CloudProvider).
        if (btnCheckOrphans != null) btnCheckOrphans.setEnabled(!syncing);
    }

    /** Bật/tắt trạng thái bận của thao tác "Check" -- tái dùng pbSyncing (2 thao tác không bao
     * giờ chạy đồng thời, vì chính hàm này khoá btnSync trong lúc đang check, và setSyncing()
     * khoá btnCheckOrphans trong lúc đang đồng bộ). */
    private void setChecking(boolean checking) {
        isChecking = checking;
        if (pbSyncing == null || btnCheckOrphans == null) return;
        pbSyncing.setVisibility(checking ? View.VISIBLE : View.GONE);
        btnCheckOrphans.setEnabled(!checking);
        if (btnSync != null) btnSync.setEnabled(!checking);
        if (btnBrowseFiles != null) btnBrowseFiles.setEnabled(!checking);
        if (btnDisconnect != null) btnDisconnect.setEnabled(!checking);
    }

    private void onCheckOrphansClicked() {
        if (getContext() == null || isChecking) return;
        CloudRemote remote = findRemote();
        if (remote == null) return;
        Context appContext = getContext().getApplicationContext();
        setChecking(true);
        executor.execute(() -> {
            CloudProvider provider = null;
            try {
                provider = CloudProviderFactory.create(appContext, remote, null);
                SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(appContext);
                File rootDir = StorageUtils.getRootDirectory(appContext, settings);
                RemoteTreeResolver treeResolver = new RemoteTreeResolver(provider);
                String driveFolderId = treeResolver.resolveOrCreateFolder(
                        rootDir.getName(), provider.getRoot().getRefId(), rootDir.getName());
                RemoteOrphanScanner.ScanResult result = RemoteOrphanScanner.scan(provider, driveFolderId);
                CloudProvider finalProvider = provider;
                mainHandler.post(() -> {
                    setChecking(false);
                    showOrphanConfirmDialog(finalProvider, driveFolderId, result);
                });
            } catch (IOException e) {
                Logger.e(e);
                mainHandler.post(() -> {
                    setChecking(false);
                    if (getContext() != null) {
                        Toast.makeText(getContext(), R.string.cloud_remote_info_orphan_scan_failed,
                                Toast.LENGTH_LONG).show();
                    }
                });
                if (provider != null) provider.close();
            }
        });
    }

    private void showOrphanConfirmDialog(CloudProvider provider, String driveFolderId,
                                          RemoteOrphanScanner.ScanResult result) {
        if (result.totalOrphanCount() == 0) {
            provider.close();
            if (getContext() != null) {
                Toast.makeText(getContext(), R.string.cloud_remote_info_orphan_none_found,
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (getContext() == null) {
            provider.close();
            return;
        }
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.cloud_remote_info_orphan_found_title)
                .setMessage(getString(R.string.cloud_remote_info_orphan_found_message,
                        result.orphanTopLevelFiles.size(), result.orphanBookFolders.size(),
                        result.duplicateBookEntries.size()))
                .setPositiveButton(R.string.cloud_remote_info_orphan_delete,
                        (d, w) -> deleteOrphans(provider, driveFolderId, result))
                .setNegativeButton(android.R.string.cancel, (d, w) -> provider.close())
                .setOnCancelListener(d -> provider.close())
                .show();
    }

    private void deleteOrphans(CloudProvider provider, String driveFolderId,
                                RemoteOrphanScanner.ScanResult result) {
        if (getContext() == null) {
            provider.close();
            return;
        }
        setChecking(true);
        executor.execute(() -> {
            RemoteOrphanScanner.DeleteSummary summary =
                    RemoteOrphanScanner.deleteOrphans(provider, driveFolderId, result);
            provider.close();
            mainHandler.post(() -> {
                setChecking(false);
                if (getContext() == null) return;
                String message = summary.failed > 0
                        ? getString(R.string.cloud_remote_info_orphan_delete_result_with_failures,
                                summary.succeeded, summary.failed)
                        : getString(R.string.cloud_remote_info_orphan_delete_result, summary.succeeded);
                Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void onBrowseFilesClicked() {
        if (getContext() == null) return;
        String id = remoteId();
        if (id == null) return;
        Intent intent = new Intent(getContext(), CloudBrowserActivity.class);
        intent.putExtra(CloudBrowserActivity.EXTRA_REMOTE_ID, id);
        startActivity(intent);
    }

    private void onDisconnectClicked() {
        if (getContext() == null) return;
        CloudRemote remote = findRemote();
        if (remote == null) return;
        int titleRes, messageRes, confirmRes;
        CloudRemoteType type = remote.getType();
        if (type == CloudRemoteType.GOOGLE_DRIVE) {
            titleRes = R.string.gdrive_disconnect_title;
            messageRes = R.string.gdrive_disconnect_message;
            confirmRes = R.string.gdrive_disconnect_confirm;
        } else if (type == CloudRemoteType.ONEDRIVE) {
            titleRes = R.string.onedrive_disconnect_title;
            messageRes = R.string.onedrive_disconnect_message;
            confirmRes = R.string.onedrive_disconnect_confirm;
        } else if (type == CloudRemoteType.BOX) {
            titleRes = R.string.box_disconnect_title;
            messageRes = R.string.box_disconnect_message;
            confirmRes = R.string.box_disconnect_confirm;
        } else {
            titleRes = R.string.dropbox_disconnect_title;
            messageRes = R.string.dropbox_disconnect_message;
            confirmRes = R.string.dropbox_disconnect_confirm;
        }
        new AlertDialog.Builder(getContext())
                .setTitle(titleRes)
                .setMessage(messageRes)
                .setPositiveButton(confirmRes, (dialog, which) -> {
                    if (getContext() == null) return;
                    CloudTokenManager.getInstance(getContext(), type).clear();
                    if (type == CloudRemoteType.GOOGLE_DRIVE) {
                        new GDriveCoverDiskCache(getContext().getApplicationContext()).clearAll();
                    }
                    CloudRemoteSyncPrefs.clear(getContext(), remote.getId());
                    OnCloudRemoteChangedListener l = listener();
                    if (l != null) l.onCloudRemoteChanged();
                    dismissAllowingStateLoss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        executor.shutdown();
    }
}
