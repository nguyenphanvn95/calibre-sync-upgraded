package net.mrphan.calibre;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * Chay upload/download that su, off main thread (Worker#doWork chay tren luong nen cua
 * WorkManager). setForegroundAsync o dau doWork() de khong bi he thong giet job khi app o
 * background lau (xem yeu cau "chay duoi nen doc lap van doc sach khac duoc") -- notification
 * chi la yeu cau ky thuat de duy tri foreground service, KHONG chan bat ky man hinh nao khac
 * cua app, nguoi dung van mo BookDetail/Reader binh thuong trong luc worker chay.
 */
public class DeviceLibrarySyncWorker extends Worker {

    static final String KEY_DIRECTION = "direction";
    static final String DIRECTION_UPLOAD = "upload";
    static final String DIRECTION_DOWNLOAD = "download";
    /** Dong bo nhe: chi calibre_sync_progress.json, khong dung file sach/manifest -- dung cho
     * nut "Sync" trong Reader va auto-sync theo chu ky (xem prompt-nang-cap-reader-sync.md). */
    static final String DIRECTION_PROGRESS_ONLY = "progress_only";
    /** Đồng bộ 2 chiều TOÀN BỘ thư viện trong 1 phiên làm việc: upload hết sách cục bộ
     * (uploadBooks(null)) rồi tải về mọi sách đang thiếu trên máy (downloadMissingBooks()) --
     * dùng cho nút "Đồng bộ" trong dialog "Thông tin thư viện cloud" của ConnectionFragment.
     * GIỚI HẠN ĐÃ BIẾT (xem mục 3.2 của prompt): sách tồn tại CẢ 2 phía nhưng bản trên cloud
     * mới hơn sẽ KHÔNG được tải lại đè lên bản cục bộ -- downloadMissingBooks() chỉ tải sách
     * đang THIẾU ở local. */
    static final String DIRECTION_FULL_SYNC = "full_sync";
    static final String KEY_REMOTE_ID = "remote_id";
    /** Neu vang mat: sync TOAN BO sach dang co lpath (dung cho "Sync all" trong tuong lai). */
    static final String KEY_UUIDS = "uuids";

    /** Publish qua setProgressAsync -- doc lai boi BackgroundJobsBottomSheet qua
     * WorkInfo#getProgress().getInt(KEY_DONE/KEY_TOTAL, 0). */
    static final String KEY_DONE = "done";
    static final String KEY_TOTAL = "total";

    private static final String CHANNEL_ID = "device_library_cloud_sync";
    private static final int NOTIFICATION_ID = 5301;

    /** Doc trong onManagerProgress() de biet dung chuoi "uploading" hay "downloading" -- set 1
     * lan dau doWork(), truoc khi manager co the goi callback. LUU Y: voi DIRECTION_FULL_SYNC,
     * gia tri nay bi GHI DE lai giua 2 pha (upload roi download trong CUNG 1 lan chay doWork())
     * de progress text hien dung cho tung pha, thay vi bao "downloading" xuyen suot ca pha
     * upload -- xem nhanh DIRECTION_FULL_SYNC trong doWork(). */
    private volatile String currentDirection;

    public DeviceLibrarySyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            setForegroundAsync(buildForegroundInfo(getApplicationContext().getString(R.string.sync_notification_preparing)));
        } catch (Exception e) {
            Logger.e(e); // Khong chan sync neu foreground khong len duoc (vd bi OS tu choi hiem gap).
        }

        String remoteId = getInputData().getString(KEY_REMOTE_ID);
        String direction = getInputData().getString(KEY_DIRECTION);
        currentDirection = direction;
        CloudRemote remote = findRemote(remoteId);
        if (remote == null) return Result.failure();

        CloudProvider provider;
        try {
            provider = CloudProviderFactory.create(getApplicationContext(), remote, null);
        } catch (IllegalStateException e) {
            // Token het han/thu hoi (GoogleDriveCloudProvider/OneDriveCloudProvider's constructor
            // checks isSignedIn()) -- khong retry vo han cho loi dang nhap, bao that bai ro rang
            // thay vi loop mai.
            Logger.e(e);
            return Result.failure();
        }

        DeviceLibrarySyncManager manager;
        try {
            manager = new DeviceLibrarySyncManager(getApplicationContext(), provider,
                    this::onManagerProgress);
        } catch (Exception e) {
            Logger.e(e);
            provider.close();
            return Result.retry();
        }

        try {
            if (DIRECTION_UPLOAD.equals(direction)) {
                String[] uuids = getInputData().getStringArray(KEY_UUIDS);
                manager.uploadBooks(uuids); // null = tat ca
            } else if (DIRECTION_PROGRESS_ONLY.equals(direction)) {
                manager.syncProgressOnly();
            } else if (DIRECTION_FULL_SYNC.equals(direction)) {
                // Upload TRƯỚC, download SAU -- nếu 1 sách vừa được thêm cục bộ VÀ vừa bị xoá trên
                // cloud bởi thiết bị khác giữa 2 lần sync, upload trước đảm bảo sách cục bộ ít nhất
                // cũng được ghi lên manifest (thành entry mới) trước khi đọc lại manifest cho bước
                // download.
                currentDirection = DIRECTION_UPLOAD;
                manager.uploadBooks(null);
                currentDirection = DIRECTION_DOWNLOAD;
                manager.downloadMissingBooks();
                CloudRemoteSyncPrefs.setLastSyncNow(getApplicationContext(), remoteId);
            } else {
                manager.downloadMissingBooks();
            }
            return Result.success();
        } catch (Exception e) {
            Logger.e(e);
            // Phase 8 (prompt nang cap): Dropbox/Google Drive gio nem CloudSyncException co ma
            // ngu nghia -- AUTH_FAILED/NOT_FOUND/CONFLICT la loi vinh vien, khong nen retry vo
            // han (WorkManager se backoff-va-thu-lai theo setBackoffCriteria() cua Scheduler,
            // ton pin/du lieu vo ich neu cu retry mai mot loi 401 se khong bao gio tu het).
            // RATE_LIMIT/NETWORK moi nen retry. Cac provider chua nang cap (Box/OneDrive/WebDAV/
            // FTP) van nem IOException thuong -- giu hanh vi cu (coi la loi mang thoang qua,
            // retry) vi khong the phan biet duoc loai loi.
            if (e instanceof CloudSyncException) {
                CloudSyncException cse = (CloudSyncException) e;
                boolean permanent = cse.code == CloudSyncException.Code.AUTH_FAILED
                        || cse.code == CloudSyncException.Code.NOT_FOUND;
                return permanent ? Result.failure() : Result.retry();
            }
            return (e instanceof java.io.IOException) ? Result.retry() : Result.failure();
        } finally {
            manager.close();
            provider.close();
        }
    }

    /** SyncProgressListener callback -- cap nhat ca notification (text nguoi doc duoc) lan
     * setProgressAsync (du lieu tho de BackgroundJobsBottomSheet doc realtime). */
    private void onManagerProgress(int done, int total) {
        boolean isUpload = DIRECTION_UPLOAD.equals(currentDirection);
        String text = getApplicationContext().getString(
                isUpload ? R.string.sync_notification_uploading : R.string.sync_notification_downloading,
                done, total);
        try {
            setForegroundAsync(buildForegroundInfo(text));
        } catch (Exception e) {
            Logger.e(e);
        }
        setProgressAsync(new Data.Builder()
                .putInt(KEY_DONE, done)
                .putInt(KEY_TOTAL, total)
                .build());
    }

    private CloudRemote findRemote(String remoteId) {
        for (CloudRemote r : CloudRemoteStore.getAll(getApplicationContext())) {
            if (r.getId().equals(remoteId)) return r;
        }
        return null;
    }

    private ForegroundInfo buildForegroundInfo(String contentText) {
        NotificationManager nm = (NotificationManager) getApplicationContext()
                .getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getApplicationContext().getString(R.string.sync_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }
        Notification notification = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setContentTitle(getApplicationContext().getString(R.string.sync_notification_title))
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_menu_cloud_upload)
                .setOngoing(true)
                .build();
        // Bat buoc phai truyen foreground service type tu API 29 (Q) tro len, giong pattern da
        // dung trong CalibreService/OAuthLoopbackService. Neu khong, setForegroundAsync() se
        // throw MissingForegroundServiceTypeException ngay dong dau doWork() tren API 34+
        // (targetSdk 35), khien job crash truoc khi DeviceLibrarySyncManager kip resolve/tao
        // folder tren Drive -- do la ly do vua crash vua khong thay thu muc/sach nao duoc tao.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new ForegroundInfo(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            return new ForegroundInfo(NOTIFICATION_ID, notification);
        }
    }
}
