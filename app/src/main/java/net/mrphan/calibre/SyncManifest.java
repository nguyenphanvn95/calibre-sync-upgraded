package net.mrphan.calibre;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * "Nguon su that" ve nhung gi da co tren cloud remote -- tranh phai listFiles de quy toan bo cay
 * thu muc remote moi lan sync (cham, ton quota). Mot file JSON duy nhat o goc thu muc cua thu
 * vien, ten co dinh calibre_sync_manifest.json. Xem muc 2 va 3.3 cua prompt nang cap cho cau
 * truc JSON day du.
 * <p>
 * KHONG duoc goi tu main thread -- moi method o day co the doc/ghi mang qua CloudProvider.
 * <p>
 * Schema v2 (xem Phase 1 cua prompt nang cap): moi Entry co them contentHash/coverHash (bo qua
 * upload thua + kiem tra toan ven, muc 3.4), updatedAt/deletedAt (LWW + tombstone, muc 3.3). Doc
 * duoc manifest v1 hien co (khong co "version" hoac version=1) ma khong crash -- entry cu duoc
 * coi la "chua migrate": contentHash == null, updatedAt == 0, deletedAt == null (xem Phase 1 va
 * muc 5 cua prompt nang cap ve lazy migration).
 */
final class SyncManifest {

    private static final String FILE_NAME = "calibre_sync_manifest.json";
    private static final int VERSION = 2;

    /** Mot entry sach trong manifest v2. So voi v1: them contentHash/coverHash (bo qua upload
     * thua + kiem tra toan ven, xem muc 3.4 cua prompt nang cap), updatedAt/deletedAt (LWW +
     * tombstone, xem muc 3.3), va bo di su phu thuoc vao driveFileId/driveCoverFileId cho entry
     * moi (RemoteTreeResolver tu resolve theo ten co dinh trong books/<uuid>/, xem Phase 7). Cac
     * truong cu (driveFileId, driveCoverFileId, sourceMtime) duoc GIU LAI de doc duoc manifest v1
     * hien co va ho tro migrate lazy (xem DeviceLibrarySyncManager va muc 5 cua prompt nang cap). */
    static final class Entry {
        final String lpath;
        /** null neu sach chua kip trich cover luc upload. */
        final String coverPath;
        /** legacy -- co the null voi entry moi (v2 dung path co dinh books/<uuid>/, khong can
         * nho id/refId giua cac phien sync nua). Van GIU trong entry cu de ho tro cleanup thu
         * cong thu muc Author/Title cu (xem muc 5 cua prompt nang cap). */
        final String driveFileId;
        /** legacy -- xem driveFileId. */
        final String driveCoverFileId;
        final long sizeBytes;
        /** File.lastModified() cua file sach local tai thoi diem upload -- dung lam buoc so
         * sanh nhanh TRUOC khi tinh contentHash (Phase 7): mtime khong doi = chac chan noi dung
         * khong doi, bo qua hoan toan buoc doc+hash. Cung la fallback so sanh doi voi entry
         * legacy chua co contentHash. */
        final long sourceMtime;
        final long uploadedAt;

        // --- v2 ---
        /** SHA-256 hex cua file sach, null neu chua tung tinh (entry legacy v1, xem Phase 1). */
        final String contentHash;
        /** SHA-256 hex cua cover, null neu khong co cover hoac chua tinh. */
        final String coverHash;
        /** Clock LWW cho chinh entry nay -- bump moi lan entry doi that su (upload moi, xoa).
         * Entry v1 doc vao co updatedAt = 0 (cu nhat co the), de moi entry v2 (moi hon) luon
         * thang khi merge (xem mergeRemoteBeforeWrite). */
        final long updatedAt;
        /** null = con song; != null = tombstone (sach da bi xoa chu dich khoi 1 thiet bi), epoch
         * ms luc xoa. Xem Phase 5 va muc 3.3 cua prompt nang cap. */
        final Long deletedAt;

        Entry(String lpath, String coverPath, String driveFileId, String driveCoverFileId,
              long sizeBytes, long sourceMtime, long uploadedAt,
              String contentHash, String coverHash, long updatedAt, Long deletedAt) {
            this.lpath = lpath;
            this.coverPath = coverPath;
            this.driveFileId = driveFileId;
            this.driveCoverFileId = driveCoverFileId;
            this.sizeBytes = sizeBytes;
            this.sourceMtime = sourceMtime;
            this.uploadedAt = uploadedAt;
            this.contentHash = contentHash;
            this.coverHash = coverHash;
            this.updatedAt = updatedAt;
            this.deletedAt = deletedAt;
        }

        boolean isTombstone() {
            return deletedAt != null;
        }

        /** true neu entry nay chua tung duoc migrate sang cau truc remote moi (books/<uuid>/) --
         * dieu kien la "chua co contentHash" (Phase 1: chi entry v2 that su duoc upload/cap nhat
         * lai qua uploadOneBook() moi co contentHash). Xem muc 5 cua prompt nang cap. */
        boolean isLegacyNotMigrated() {
            return contentHash == null && !isTombstone();
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("lpath", lpath);
            if (coverPath != null) o.put("coverPath", coverPath);
            if (driveFileId != null) o.put("driveFileId", driveFileId);
            if (driveCoverFileId != null) o.put("driveCoverFileId", driveCoverFileId);
            o.put("sizeBytes", sizeBytes);
            o.put("sourceMtime", sourceMtime);
            o.put("uploadedAt", uploadedAt);
            if (contentHash != null) o.put("contentHash", contentHash);
            if (coverHash != null) o.put("coverHash", coverHash);
            o.put("updatedAt", updatedAt);
            if (deletedAt != null) o.put("deletedAt", deletedAt.longValue());
            return o;
        }

        static Entry fromJson(JSONObject o) {
            return new Entry(
                    o.optString("lpath", null),
                    o.isNull("coverPath") ? null : o.optString("coverPath", null),
                    o.isNull("driveFileId") ? null : o.optString("driveFileId", null),
                    o.isNull("driveCoverFileId") ? null : o.optString("driveCoverFileId", null),
                    o.optLong("sizeBytes", 0),
                    o.optLong("sourceMtime", 0),
                    o.optLong("uploadedAt", 0),
                    o.isNull("contentHash") ? null : o.optString("contentHash", null),
                    o.isNull("coverHash") ? null : o.optString("coverHash", null),
                    // entry v1 khong co updatedAt: coi nhu da cu nhat (0) de moi entry v2 thang LWW
                    o.optLong("updatedAt", 0),
                    o.isNull("deletedAt") ? null : o.optLong("deletedAt"));
        }
    }

    private final Map<String, Entry> books;
    /** refId cua chinh file manifest.json tren remote, null neu day la instance rong (chua tung
     * upload) -- dung de goi updateFileContent thay vi uploadFile khi ghi lai, tranh tao file
     * calibre_sync_manifest.json trung ten. */
    private String selfRefId;

    private SyncManifest(Map<String, Entry> books, String selfRefId) {
        this.books = books;
        this.selfRefId = selfRefId;
    }

    int size() {
        return books.size();
    }

    Entry get(String uuid) {
        return books.get(uuid);
    }

    void put(String uuid, Entry entry) {
        books.put(uuid, entry);
    }

    Collection<Entry> allEntries() {
        return books.values();
    }

    java.util.Set<Map.Entry<String, Entry>> entrySet() {
        return books.entrySet();
    }

    /** Tai manifest.json tu remote neu da co, hoac tra ve 1 instance rong neu chua tung sync
     * (lan dau) hoac file bi hong/khong parse duoc -- KHONG duoc crash worker vi manifest hong. */
    static SyncManifest downloadOrEmpty(CloudProvider provider, String driveFolderId) throws IOException {
        for (CloudFile f : provider.listFiles(driveFolderId)) {
            if (!f.isDirectory() && FILE_NAME.equals(f.getName())) {
                File tmp = File.createTempFile("sync_manifest", ".json");
                try {
                    provider.downloadFile(f, tmp);
                    String json = readFile(tmp);
                    return parse(json, f.getRefId());
                } catch (JSONException e) {
                    Logger.e(e);
                    return new SyncManifest(new HashMap<>(), f.getRefId());
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            }
        }
        return new SyncManifest(new HashMap<>(), null);
    }

    private static SyncManifest parse(String json, String selfRefId) throws JSONException {
        JSONObject root = new JSONObject(json);
        // "version" vang mat = manifest v1 (truoc khi truong nay ton tai) -- doc binh thuong,
        // moi Entry.fromJson() se tu dien gia tri mac dinh hop ly cho cac truong v2 con thieu
        // (contentHash=null, updatedAt=0, deletedAt=null), coi nhu "can migrate" thay vi loi.
        int version = root.optInt("version", 1);
        JSONObject booksJson = root.optJSONObject("books");
        Map<String, Entry> map = new HashMap<>();
        if (booksJson != null) {
            java.util.Iterator<String> keys = booksJson.keys();
            while (keys.hasNext()) {
                String uuid = keys.next();
                map.put(uuid, Entry.fromJson(booksJson.getJSONObject(uuid)));
            }
        }
        if (version < 2) {
            Logger.d("SyncManifest: doc duoc manifest v" + version + ", se nang len v" + VERSION
                    + " o lan ghi lai ke tiep (cac entry cu duoc coi la chua migrate).");
        }
        return new SyncManifest(map, selfRefId);
    }

    /** Hop nhat "this" (snapshot da chinh sua trong phien sync hien tai) voi ban moi nhat vua tai
     * lai tu remote NGAY TRUOC KHI GHI, theo tung uuid: giu ban co updatedAt lon hon (LWW). Muc
     * dich: neu 1 thiet bi khac vua ghi them sach moi vao khoang thoi gian giua luc phien nay tai
     * manifest luc dau va luc chuan bi ghi lai, sach do KHONG bi mat -- xem van de #4 (muc 2) va
     * muc 3.3 cua prompt nang cap. Goi ngay truoc uploadTo(), truyen vao ban tai lai lan 2. */
    void mergeRemoteBeforeWrite(SyncManifest freshRemote) {
        for (Map.Entry<String, Entry> e : freshRemote.books.entrySet()) {
            String uuid = e.getKey();
            Entry remote = e.getValue();
            Entry local = books.get(uuid);
            if (local == null || remote.updatedAt > local.updatedAt) {
                books.put(uuid, remote);
            }
        }
    }

    /** Ghi manifest len remote -- updateFileContent neu da biet selfRefId (tu lan download truoc
     * do trong cung phien sync nay), uploadFile lan dau tien. Luon ghi "version": VERSION (2)
     * moi, du doc vao la v1 hay v2 -- tu dong nang cap file tren remote len schema moi nhat. */
    void uploadTo(CloudProvider provider, String driveFolderId) throws IOException {
        File tmp = File.createTempFile("sync_manifest_out", ".json");
        try {
            writeFile(tmp, toJson().toString());
            if (selfRefId != null) {
                selfRefId = provider.updateFileContent(selfRefId, tmp);
            } else {
                selfRefId = provider.uploadFile(tmp, driveFolderId, FILE_NAME);
            }
        } catch (JSONException e) {
            throw new IOException(e);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private JSONObject toJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("version", VERSION);
        root.put("updatedAt", System.currentTimeMillis());
        JSONObject booksJson = new JSONObject();
        for (Map.Entry<String, Entry> e : books.entrySet()) {
            booksJson.put(e.getKey(), e.getValue().toJson());
        }
        root.put("books", booksJson);
        return root;
    }

    private static String readFile(File f) throws IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            byte[] buffer = new byte[(int) f.length()];
            int off = 0;
            int read;
            while (off < buffer.length && (read = in.read(buffer, off, buffer.length - off)) != -1) {
                off += read;
            }
            return new String(buffer, "UTF-8");
        } finally {
            in.close();
        }
    }

    private static void writeFile(File f, String content) throws IOException {
        FileWriter writer = new FileWriter(f);
        try {
            writer.write(content);
        } finally {
            writer.close();
        }
    }
}
