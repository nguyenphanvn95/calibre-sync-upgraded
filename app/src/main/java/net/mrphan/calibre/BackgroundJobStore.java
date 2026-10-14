package net.mrphan.calibre;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Luu metadata (loai job, ten remote, so luong sach, thoi diem tao) cho tung WorkManager request
 * cua DeviceLibrarySyncWorker, khop theo WorkRequest#getId() -- WorkInfo tu WorkManager KHONG
 * mang theo cac truong nay (chi co state/progress), nen phai luu rieng o day de
 * BackgroundJobsBottomSheet ghep voi WorkInfo khi hien thi. Dung SharedPreferences thuong
 * (khong ma hoa, khac CloudRemoteStore) vi du lieu khong nhay cam.
 * <p>
 * Chi giu toi da MAX_ENTRIES ban ghi gan nhat (FIFO) de tranh phinh to vo han qua nhieu thang/nam
 * dung app -- WorkManager cung tu don dep WorkInfo cu dinh ky, nen gioi han o day chi la du
 * phong, khong phai co che chinh (BackgroundJobsBottomSheet da tu bo qua meta khong con
 * WorkInfo khop).
 */
final class BackgroundJobStore {

    private static final String PREFS_KEY = "background_jobs_json";
    private static final int MAX_ENTRIES = 30;

    static final String DIRECTION_UPLOAD = "upload";
    static final String DIRECTION_DOWNLOAD = "download";
    /** Job DIRECTION_FULL_SYNC của DeviceLibrarySyncWorker (upload hết + download sách thiếu trong
     * 1 lần chạy) -- giá trị riêng thay vì tái dùng DIRECTION_UPLOAD để BackgroundJobsBottomSheet
     * hiện đúng nhãn "Đồng bộ 2 chiều" thay vì "Đang tải lên" gây hiểu lầm. */
    static final String DIRECTION_FULL_SYNC = "full_sync";

    private BackgroundJobStore() {}

    static final class JobMeta {
        final String id;
        final String direction;
        final String remoteName;
        /** -1 = khong ro / khong ap dung (vd "toan bo thu vien", hoac download chua biet total). */
        final int bookCount;
        final long createdAt;

        JobMeta(String id, String direction, String remoteName, int bookCount, long createdAt) {
            this.id = id;
            this.direction = direction;
            this.remoteName = remoteName;
            this.bookCount = bookCount;
            this.createdAt = createdAt;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("direction", direction);
            o.put("remoteName", remoteName);
            o.put("bookCount", bookCount);
            o.put("createdAt", createdAt);
            return o;
        }

        static JobMeta fromJson(JSONObject o) throws JSONException {
            return new JobMeta(
                    o.getString("id"),
                    o.getString("direction"),
                    o.optString("remoteName", ""),
                    o.optInt("bookCount", -1),
                    o.optLong("createdAt", 0));
        }
    }

    /** Goi ngay sau khi build() xong OneTimeWorkRequest (request.getId() da co that), TRUOC khi
     * enqueueUniqueWork -- xem DeviceLibrarySyncScheduler#enqueue. */
    static void record(Context context, UUID workId, String direction, String remoteName, int bookCount) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        List<JobMeta> all = readAll(prefs);
        all.add(new JobMeta(workId.toString(), direction, remoteName, bookCount, System.currentTimeMillis()));
        while (all.size() > MAX_ENTRIES) {
            all.remove(0); // ban ghi cu nhat (danh sach chua sort, nhung luon append o cuoi nen dau la cu nhat)
        }
        writeAll(prefs, all);
    }

    /** Sap xep tang dan theo createdAt (cu -> moi) -- dung truc tiep cho BackgroundJobsBottomSheet. */
    static List<JobMeta> getAllSortedOldestFirst(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        List<JobMeta> all = readAll(prefs);
        Collections.sort(all, (a, b) -> Long.compare(a.createdAt, b.createdAt));
        return all;
    }

    /** Xoa het lich su TRU nhung job id dang con "song" trong WorkManager (RUNNING/ENQUEUED/
     * BLOCKED) -- dung boi nut "Xoa lich su" trong BackgroundJobsBottomSheet. Truoc do lich su
     * chi cong don (record() gioi han MAX_ENTRIES nhung khong co cach nao xoa bot thu cong), nen
     * nguoi dung khong the tu don dep cac job da xong/that bai neu muon. Khong xoa job dang chay
     * de tranh lam bien mat tien trinh dang hien thi. */
    static void clearFinished(Context context, java.util.Set<String> activeJobIds) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        List<JobMeta> all = readAll(prefs);
        List<JobMeta> kept = new ArrayList<>();
        for (JobMeta m : all) {
            if (activeJobIds.contains(m.id)) kept.add(m);
        }
        writeAll(prefs, kept);
    }

    private static List<JobMeta> readAll(SharedPreferences prefs) {
        List<JobMeta> result = new ArrayList<>();
        String json = prefs.getString(PREFS_KEY, null);
        if (json == null || json.isEmpty()) return result;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                try {
                    result.add(JobMeta.fromJson(arr.getJSONObject(i)));
                } catch (JSONException e) {
                    Logger.e(e);
                }
            }
        } catch (JSONException e) {
            Logger.e(e);
        }
        return result;
    }

    private static void writeAll(SharedPreferences prefs, List<JobMeta> all) {
        JSONArray arr = new JSONArray();
        for (JobMeta m : all) {
            try {
                arr.put(m.toJson());
            } catch (JSONException e) {
                Logger.e(e);
            }
        }
        prefs.edit().putString(PREFS_KEY, arr.toString()).apply();
    }
}
