package net.mrphan.calibre;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/** Một thư viện Calibre trên Google Drive mà người dùng đã lưu (qua ID/link metadata_public.json
 * đã share công khai). Khác với CloudRemote(GOOGLE_DRIVE) hiện có — remote đó là 1 tài khoản
 * Google đã OAuth để duyệt file thô; còn đây là 1 thư viện Calibre cụ thể, KHÔNG cần đăng nhập,
 * duyệt qua local OPDS server giả lập (xem GDriveOpdsHttpServer). */
public class GDriveOpdsLibrary {
    private final String id;
    private String displayName;
    /** File ID Drive đã trích xuất sạch (KHÔNG phải link thô người dùng gõ). */
    private String driveFileId;
    private long addedAtMillis;
    /** Cache nhẹ để hiện subtitle mà không cần mở DB — cập nhật lại mỗi lần đồng bộ thành công
     * trong GDriveOpdsHttpServer#start(). */
    private int cachedBookCount = -1;
    private long lastSyncedAtMillis = 0;

    public GDriveOpdsLibrary(String id, String displayName, String driveFileId) {
        this.id = id;
        this.displayName = displayName;
        this.driveFileId = driveFileId;
    }

    public static GDriveOpdsLibrary newWithRandomId(String displayName, String driveFileId) {
        GDriveOpdsLibrary lib = new GDriveOpdsLibrary(UUID.randomUUID().toString(), displayName, driveFileId);
        lib.addedAtMillis = System.currentTimeMillis();
        return lib;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getDriveFileId() { return driveFileId; }
    public void setDriveFileId(String driveFileId) { this.driveFileId = driveFileId; }
    public int getCachedBookCount() { return cachedBookCount; }
    public void setCachedBookCount(int cachedBookCount) { this.cachedBookCount = cachedBookCount; }
    public long getLastSyncedAtMillis() { return lastSyncedAtMillis; }
    public void setLastSyncedAtMillis(long lastSyncedAtMillis) { this.lastSyncedAtMillis = lastSyncedAtMillis; }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("displayName", displayName);
        o.put("driveFileId", driveFileId);
        o.put("addedAtMillis", addedAtMillis);
        o.put("cachedBookCount", cachedBookCount);
        o.put("lastSyncedAtMillis", lastSyncedAtMillis);
        return o;
    }

    public static GDriveOpdsLibrary fromJson(JSONObject o) throws JSONException {
        GDriveOpdsLibrary lib = new GDriveOpdsLibrary(o.getString("id"), o.getString("displayName"), o.getString("driveFileId"));
        lib.addedAtMillis = o.optLong("addedAtMillis", 0);
        lib.cachedBookCount = o.optInt("cachedBookCount", -1);
        lib.lastSyncedAtMillis = o.optLong("lastSyncedAtMillis", 0);
        return lib;
    }
}
