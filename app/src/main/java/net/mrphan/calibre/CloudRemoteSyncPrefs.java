package net.mrphan.calibre;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Ghi/đọc thời điểm hoàn tất lần "Đồng bộ toàn bộ thư viện" (2 chiều, xem
 * DeviceLibrarySyncScheduler#enqueueFullSync) gần nhất cho từng CloudRemote, dùng riêng 1 file
 * SharedPreferences ("cloud_remote_sync_prefs") thay vì nhét vào CloudRemote/CloudRemoteStore --
 * tránh phải đổi schema JSON + viết migration cho field mới chỉ vì 1 con số hiển thị UI.
 * <p>
 * CHỈ ghi khi DIRECTION_FULL_SYNC hoàn tất thành công -- KHÔNG ghi khi upload/download riêng lẻ
 * (DIRECTION_UPLOAD/DIRECTION_DOWNLOAD từ CollectionFragment) để "lần đồng bộ cuối" hiển thị
 * trong dialog này chỉ phản ánh đúng hành động người dùng bấm trong dialog đó.
 */
final class CloudRemoteSyncPrefs {

    private static final String PREFS_NAME = "cloud_remote_sync_prefs";
    private static final String KEY_PREFIX_LAST_SYNC = "last_full_sync_";

    private CloudRemoteSyncPrefs() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** @return millis epoch của lần full-sync gần nhất, hoặc 0 nếu chưa từng đồng bộ. */
    static long getLastSyncMillis(Context context, String remoteId) {
        if (remoteId == null) return 0L;
        return prefs(context).getLong(KEY_PREFIX_LAST_SYNC + remoteId, 0L);
    }

    static void setLastSyncNow(Context context, String remoteId) {
        if (remoteId == null) return;
        prefs(context).edit().putLong(KEY_PREFIX_LAST_SYNC + remoteId, System.currentTimeMillis()).apply();
    }

    /** Gọi khi 1 CloudRemote bị xoá/đăng xuất để không lưu rác vĩnh viễn cho 1 remote id không
     * còn tồn tại. */
    static void clear(Context context, String remoteId) {
        if (remoteId == null) return;
        prefs(context).edit().remove(KEY_PREFIX_LAST_SYNC + remoteId).apply();
    }
}
