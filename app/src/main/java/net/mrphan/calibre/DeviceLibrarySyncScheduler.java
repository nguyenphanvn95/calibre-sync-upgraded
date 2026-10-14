package net.mrphan.calibre;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

/** Diem vao duy nhat de enqueue DeviceLibrarySyncWorker -- CollectionFragment / dialog picker
 * KHONG duoc tu tao WorkRequest rieng, luon di qua day. Dung 2 unique work name RIENG BIET:
 * UNIQUE_WORK_NAME cho upload/download/full-sync (tranh 2 job chay chong cheo cung luc, gay
 * xung dot ghi manifest.json) va UNIQUE_WORK_NAME_PROGRESS rieng cho progress-only sync (xem
 * javadoc enqueueProgressSync() ve ly do tach rieng, tranh bi xep hang cho sau cac full-sync
 * lon). */
public final class DeviceLibrarySyncScheduler {

    static final String UNIQUE_WORK_NAME = "device_library_cloud_sync";

    /** Hang doi RIENG cho enqueueProgressSync -- xem giai thich chi tiet trong javadoc cua ham
     * do (fix loi "dong bo tien trinh doc bi tre hang chuc phut du dat chu ky vai phut"). */
    private static final String UNIQUE_WORK_NAME_PROGRESS = "device_library_progress_sync";

    private DeviceLibrarySyncScheduler() {}

    static void enqueueUpload(Context context, String remoteId, String[] uuidsOrNullForAll) {
        Data.Builder data = new Data.Builder()
                .putString(DeviceLibrarySyncWorker.KEY_DIRECTION, DeviceLibrarySyncWorker.DIRECTION_UPLOAD)
                .putString(DeviceLibrarySyncWorker.KEY_REMOTE_ID, remoteId);
        if (uuidsOrNullForAll != null) {
            data.putStringArray(DeviceLibrarySyncWorker.KEY_UUIDS, uuidsOrNullForAll);
        }
        int bookCount = uuidsOrNullForAll != null ? uuidsOrNullForAll.length : -1;
        enqueue(context, data.build(), BackgroundJobStore.DIRECTION_UPLOAD, remoteId, bookCount);
    }

    static void enqueueDownload(Context context, String remoteId) {
        Data data = new Data.Builder()
                .putString(DeviceLibrarySyncWorker.KEY_DIRECTION, DeviceLibrarySyncWorker.DIRECTION_DOWNLOAD)
                .putString(DeviceLibrarySyncWorker.KEY_REMOTE_ID, remoteId)
                .build();
        enqueue(context, data, BackgroundJobStore.DIRECTION_DOWNLOAD, remoteId, -1);
    }

    /** Nhe hon enqueueUpload/enqueueDownload -- chi day calibre_sync_progress.json,
     * dung cho nut "Sync" trong Reader va auto-sync dinh ky.
     *
     * BUG DA SUA: truoc day ham nay APPEND vao CHUNG unique work name voi
     * upload/download/full-sync (UNIQUE_WORK_NAME). Ly do ban dau la tranh 2 job ghi
     * calibre_sync_progress.json/manifest.json dong thoi -- nhung vi APPEND xep job MOI
     * vao CUOI hang doi, neu chuoi "Dong bo tu dong toan bo thu vien" (AppLibraryAutoSyncWorker,
     * xem AppLibraryAutoSyncScheduler) dang co 1 hoac nhieu enqueueFullSync() cho thu vien lon
     * dang chay/cho trong CHINH hang doi do, moi tick auto-sync tien trinh doc (enqueueProgressSync,
     * goi tu EpubReaderActivity moi vai phut theo ReaderSyncSettingsStore) bi xep hang PHIA SAU
     * cac full-sync do va phai doi chung xong het moi den luot -- co the tre hang chuc phut du
     * nguoi dung dat chu ky auto-sync chi vai phut trong Reader. Cach sua: progress-only sync
     * dung 1 unique work name RIENG (UNIQUE_WORK_NAME_PROGRESS), khong bao gio bi chan boi
     * upload/download/full-sync toan bo thu vien -- an toan vi calibre_sync_progress.json va
     * manifest.json la 2 FILE KHAC NHAU tren remote, khong co xung dot ghi de thuc su giua 2
     * hang doi nay (moi hang doi van tu APPEND_OR_REPLACE de tranh 2 job progress-only chong
     * cheo nhau). Tra ve ID cua WorkRequest de caller tu observe dung job nay (khong lan voi
     * job khac dang xep hang chung queue) -- xem EpubReaderActivity.syncProgressNow(). */
    public static java.util.UUID enqueueProgressSync(Context context, String remoteId) {
        Data data = new Data.Builder()
                .putString(DeviceLibrarySyncWorker.KEY_DIRECTION, DeviceLibrarySyncWorker.DIRECTION_PROGRESS_ONLY)
                .putString(DeviceLibrarySyncWorker.KEY_REMOTE_ID, remoteId)
                .build();
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(DeviceLibrarySyncWorker.class)
                .setInputData(data)
                .setConstraints(constraints)
                .build();
        // KHONG goi BackgroundJobStore.record() o day: job nay chay am tham (khong
        // hien trong BackgroundJobsBottomSheet) -- qua nhe/qua thuong xuyen (moi vai
        // phut hoac moi lan bam nut Sync) de dua vao danh sach "tac vu chay nen".
        WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME_PROGRESS, ExistingWorkPolicy.APPEND_OR_REPLACE, request);
        return request.getId();
    }

    /** Đồng bộ 2 chiều TOÀN BỘ thư viện trong 1 lần bấm -- xem DeviceLibrarySyncWorker
     * .DIRECTION_FULL_SYNC. Điểm vào duy nhất cho nút "Đồng bộ" trong dialog "Thông tin thư viện
     * cloud" (CloudRemoteInfoBottomSheetFragment). Trả về UUID của WorkRequest để dialog tự quan
     * sát đúng job này qua WorkManager#getWorkInfoByIdLiveData (giống cách EpubReaderActivity đang
     * làm với enqueueProgressSync) -- KHÔNG dùng getWorkInfosForUniqueWorkLiveData(UNIQUE_WORK_NAME)
     * ở đây vì dialog chỉ quan tâm job CỦA CHÍNH NÓ vừa enqueue. */
    static java.util.UUID enqueueFullSync(Context context, String remoteId) {
        Data data = new Data.Builder()
                .putString(DeviceLibrarySyncWorker.KEY_DIRECTION, DeviceLibrarySyncWorker.DIRECTION_FULL_SYNC)
                .putString(DeviceLibrarySyncWorker.KEY_REMOTE_ID, remoteId)
                .build();
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(DeviceLibrarySyncWorker.class)
                .setInputData(data)
                .setConstraints(constraints)
                .build();
        // Vẫn ghi vào BackgroundJobsBottomSheet (không nhẹ như enqueueProgressSync) -- đây là 1
        // phiên upload+download thật sự, có thể mất vài phút với thư viện lớn.
        BackgroundJobStore.record(context, request.getId(), BackgroundJobStore.DIRECTION_FULL_SYNC,
                resolveRemoteName(context, remoteId), -1);
        WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request);
        return request.getId();
    }

    private static void enqueue(Context context, Data data, String jobDirection, String remoteId, int bookCount) {
        // Khong co constraint nay truoc day: job chay ngay bat ke co internet hay khong, khien
        // CloudOAuthManager#refreshAccessTokenBlocking phai doi het 30s timeout roi fail
        // moi lan may chua co mang thuc su (vd co Wi-Fi/LAN de discover Calibre server nhung
        // khong co duong ra internet). Voi CONNECTED, WorkManager tu giu job cho den khi co mang
        // roi moi chay, va se tu chay lai ngay khi mang quay lai neu doWork() tra ve retry().
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(DeviceLibrarySyncWorker.class)
                .setInputData(data)
                .setConstraints(constraints)
                .build();

        // Ghi metadata (ten remote, so sach, thoi diem tao) TRUOC khi enqueue -- request.getId()
        // da co ngay sau build(), khong can doi job chay xong. Xem BackgroundJobStore.
        BackgroundJobStore.record(context, request.getId(), jobDirection, resolveRemoteName(context, remoteId), bookCount);

        // APPEND (khong REPLACE): neu nguoi dung bam sync them sach khac trong luc job truoc
        // con dang chay, job moi se noi duoi thay vi huy job dang chay do.
        WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request);
    }

    private static String resolveRemoteName(Context context, String remoteId) {
        for (CloudRemote r : CloudRemoteStore.getAll(context)) {
            if (r.getId().equals(remoteId)) return r.getDisplayName();
        }
        return "";
    }
}
