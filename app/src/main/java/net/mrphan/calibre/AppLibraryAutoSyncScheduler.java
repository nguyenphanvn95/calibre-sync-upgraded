package net.mrphan.calibre;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * Diem vao duy nhat cho chuoi "Dong bo tu dong toan bo thu vien" (Settings > Ket noi > Dong bo
 * tu dong). Dung 1 unique work name RIENG voi DeviceLibrarySyncScheduler.UNIQUE_WORK_NAME (chuoi
 * nay chi lam nhiem vu "danh thuc" dinh ky va tu len lich lai, con viec upload/download that su
 * van di qua unique work name cu -- xem AppLibraryAutoSyncWorker).
 */
final class AppLibraryAutoSyncScheduler {

    private static final String UNIQUE_WORK_NAME = "app_wide_library_auto_sync_chain";

    private AppLibraryAutoSyncScheduler() {}

    /** Goi khi: (1) app khoi dong (CalibreSyncApplication#onCreate), va (2) nguoi dung doi bat
     * kỳ pref nao trong khoi "Dong bo tu dong" (bat/tat, doi chu ky, doi danh sach remote) --
     * xem SettingsFragment#setupAutoSyncPreferences. Luon HUY chuoi cu truoc khi quyet dinh co
     * bat lai hay khong, tranh 2 chuoi chay song song neu goi lien tiep nhieu lan. */
    static void applySettingsChanged(Context context) {
        int intervalMinutes = AppLibraryAutoSyncPrefs.getIntervalMinutes(context);
        if (intervalMinutes <= 0) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME);
            return;
        }
        scheduleNextTick(context, intervalMinutes);
    }

    /** Enqueue 1 lan chay AppLibraryAutoSyncWorker sau {@code delayMinutes}, THAY THE
     * (REPLACE) tick dang cho neu co -- goi boi applySettingsChanged() (tick dau tien sau khi
     * bat/doi cau hinh) va boi chinh AppLibraryAutoSyncWorker (tick ke tiep sau khi 1 tick chay
     * xong), nen phai dung REPLACE chu khong phai APPEND_OR_REPLACE nhu
     * DeviceLibrarySyncScheduler -- moi lan doi cau hinh phai huy tick cu dang cho de ap dung
     * chu ky moi ngay, khong de tick cu (theo chu ky cu) chay xong roi moi doi. */
    static void scheduleNextTick(Context context, int delayMinutes) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AppLibraryAutoSyncWorker.class)
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request);
    }
}
