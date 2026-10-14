package net.mrphan.calibre;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Doc/ghi cau hinh "Dong bo tu dong" toan bo thu vien (Settings > Ket noi > Dong bo tu dong).
 * Interval va danh sach remote da chon nam trong default SharedPreferences (key
 * "pref_auto_sync_interval" / "pref_auto_sync_remotes", vi day la Preference chuan can UI tu
 * cap nhat summary) -- lop nay chi la 1 lop tien ich gop cac gia tri lai thanh 1 API duy nhat cho
 * AppLibraryAutoSyncScheduler/Worker doc, cong them so phut tuy chinh luu rieng.
 */
final class AppLibraryAutoSyncPrefs {

    private static final String KEY_INTERVAL = "pref_auto_sync_interval";
    private static final String KEY_REMOTES = "pref_auto_sync_remotes";
    private static final String KEY_CUSTOM_MINUTES = "pref_auto_sync_interval_custom_minutes";

    private AppLibraryAutoSyncPrefs() {}

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    /** @return so phut giua 2 lan dong bo, hoac 0 neu tinh nang dang TAT. */
    static int getIntervalMinutes(Context context) {
        String raw = prefs(context).getString(KEY_INTERVAL, "0");
        if (raw == null) return 0;
        if ("custom".equals(raw)) {
            return prefs(context).getInt(KEY_CUSTOM_MINUTES, 20);
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static void setCustomIntervalMinutes(Context context, int minutes) {
        prefs(context).edit().putInt(KEY_CUSTOM_MINUTES, Math.max(1, minutes)).apply();
    }

    static int getCustomIntervalMinutes(Context context) {
        return prefs(context).getInt(KEY_CUSTOM_MINUTES, 20);
    }

    static boolean isEnabled(Context context) {
        return getIntervalMinutes(context) > 0;
    }

    /** Id cac CloudRemote duoc chon de auto-sync. Neu chua từng đặt (key chưa tồn tại -- phân
     * biệt với "đã đặt rồi nhưng user bỏ chọn hết", set rỗng), mặc định TRẢ VỀ id của toàn bộ
     * remote hỗ trợ sync hiện có, đúng yêu cầu "mặc định là tất cả các server đã đăng nhập". */
    static Set<String> getSelectedRemoteIds(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.contains(KEY_REMOTES)) {
            Set<String> all = new HashSet<>();
            for (CloudRemote r : CloudRemoteStore.getAll(context)) {
                if (r.getType().supportsDeviceLibrarySync()) all.add(r.getId());
            }
            return all;
        }
        Set<String> saved = p.getStringSet(KEY_REMOTES, new HashSet<>());
        return saved != null ? new HashSet<>(saved) : new HashSet<>();
    }

    /** Danh sach CloudRemote thuc su se duoc dong bo: giao giua "da chon" va "hien con ton tai +
     * con ho tro sync" -- loai bo remote da bi dang xuat/xoa ma prefs con luu id cu. */
    static List<CloudRemote> resolveSelectedRemotes(Context context) {
        Set<String> selectedIds = getSelectedRemoteIds(context);
        List<CloudRemote> result = new java.util.ArrayList<>();
        for (CloudRemote r : CloudRemoteStore.getAll(context)) {
            if (r.getType().supportsDeviceLibrarySync() && selectedIds.contains(r.getId())) {
                result.add(r);
            }
        }
        return result;
    }
}
