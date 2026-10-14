package net.mrphan.calibre;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.List;

/**
 * Moi tick cua chuoi auto-sync toan bo thu vien (xem AppLibraryAutoSyncScheduler va muc 0.1 cua
 * prompt nang cap auto-sync ve ly do dung OneTimeWorkRequest tu len lich lai thay vi
 * PeriodicWorkRequest). doWork() KHONG tu thuc hien upload/download -- chi enqueue
 * DeviceLibrarySyncScheduler#enqueueFullSync() cho tung remote da chon (tai su dung nguyen logic
 * full-sync 2 chieu da co, tranh trung lap code), roi tu len lich tick ke tiep truoc khi ket
 * thuc, BAT KE tick nay co remote nao de dong bo hay khong (vi nguoi dung co the them remote
 * moi giua chung, chuoi khong duoc phep chet).
 */
public class AppLibraryAutoSyncWorker extends Worker {

    public AppLibraryAutoSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        int intervalMinutes = AppLibraryAutoSyncPrefs.getIntervalMinutes(context);

        // Nguoi dung da tat tinh nang giua chung (hoac tu 1 tick cu con sot lai truoc khi tat) --
        // dung chuoi tai day, KHONG tu len lich tick ke tiep. Lan bat lai se di qua
        // AppLibraryAutoSyncScheduler#applySettingsChanged() de khoi dong chuoi moi.
        if (intervalMinutes <= 0) {
            return Result.success();
        }

        List<CloudRemote> remotes = AppLibraryAutoSyncPrefs.resolveSelectedRemotes(context);
        for (CloudRemote remote : remotes) {
            // enqueueFullSync tu APPEND vao unique work "device_library_cloud_sync" -- an toan
            // goi nhieu remote lien tiep, cac job se noi duoi nhau thay vi chay chong cheo (xem
            // javadoc DeviceLibrarySyncScheduler).
            DeviceLibrarySyncScheduler.enqueueFullSync(context, remote.getId());
        }

        AppLibraryAutoSyncScheduler.scheduleNextTick(context, intervalMinutes);
        return Result.success();
    }
}
