package net.mrphan.calibre;

import android.database.Cursor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tien trinh doc/ghi chu (bookmark, highlight, reading time, comments) cua toan bo thu vien,
 * dong goi thanh calibre_sync_progress.json -- tach rieng khoi SyncManifest vi thay doi thuong
 * xuyen hon nhieu (moi lan doc), trong khi manifest chi doi khi them/xoa sach. Xem muc 2 va 3.4
 * cua prompt cho cau truc JSON va nguyen tac merge day du.
 * <p>
 * KHONG duoc goi tu main thread.
 */
final class SyncProgress {

    private static final String FILE_NAME = "calibre_sync_progress.json";
    private static final int VERSION = 1;

    static final class AnnotationEntry {
        final String type;
        final String locatorJson;
        final String color;
        final String note;
        final String excerpt;
        final long createdAt;
        final long updatedAt;
        /** BUG FIX (DATABASE_VERSION 15): true if this annotation was soft-deleted (local
         * `deleted_at != NULL`) -- see MetadataDatabaseHelper#removeAnnotation()'s kdoc. Carried
         * through the same "highest updatedAt wins" merge {@link #key} already uses (a deletion
         * bumps updatedAt just like any other edit), so a genuinely newer delete correctly beats
         * an older un-deleted copy from another device, and vice versa. Old
         * calibre_sync_progress.json files written before this fix simply don't have this field
         * (defaults to false via JSONObject#optBoolean), which is the correct/safe interpretation
         * -- nothing in those files was ever a tombstone. */
        final boolean deleted;

        AnnotationEntry(String type, String locatorJson, String color, String note, String excerpt,
                        long createdAt, long updatedAt, boolean deleted) {
            this.type = type;
            this.locatorJson = locatorJson;
            this.color = color;
            this.note = note;
            this.excerpt = excerpt;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.deleted = deleted;
        }

        /** Khoa tu nhien de merge -- xem muc 3.4: (uuid da la key ngoai cua ProgressEntry,
         * (locatorJson, type) la du de phan biet cac annotation con lai cua cung 1 sach). */
        String key() {
            return type + "|" + locatorJson;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("locatorJson", locatorJson);
            o.put("color", color);
            o.put("note", note);
            o.put("excerpt", excerpt);
            o.put("createdAt", createdAt);
            o.put("updatedAt", updatedAt);
            o.put("deleted", deleted);
            return o;
        }

        static AnnotationEntry fromJson(JSONObject o) {
            return new AnnotationEntry(
                    o.optString("type", null),
                    o.optString("locatorJson", null),
                    o.isNull("color") ? null : o.optString("color", null),
                    o.isNull("note") ? null : o.optString("note", null),
                    o.isNull("excerpt") ? null : o.optString("excerpt", null),
                    o.optLong("createdAt", 0),
                    o.optLong("updatedAt", 0),
                    o.optBoolean("deleted", false));
        }
    }

    static final class ProgressEntry {
        String lastLocatorJson;
        long lastLocatorUpdatedAt;
        String comments;
        final Map<String, Long> readingTimeMs = new HashMap<>();
        /** key = AnnotationEntry#key(). */
        final Map<String, AnnotationEntry> annotations = new HashMap<>();
    }

    private final Map<String, ProgressEntry> books;
    private String selfRefId;

    private SyncProgress(Map<String, ProgressEntry> books, String selfRefId) {
        this.books = books;
        this.selfRefId = selfRefId;
    }

    private ProgressEntry entryFor(String uuid) {
        ProgressEntry e = books.get(uuid);
        if (e == null) {
            e = new ProgressEntry();
            books.put(uuid, e);
        }
        return e;
    }

    static SyncProgress downloadOrEmpty(CloudProvider provider, String driveFolderId) throws IOException {
        for (CloudFile f : provider.listFiles(driveFolderId)) {
            if (!f.isDirectory() && FILE_NAME.equals(f.getName())) {
                File tmp = File.createTempFile("sync_progress", ".json");
                try {
                    provider.downloadFile(f, tmp);
                    return parse(readFile(tmp), f.getRefId());
                } catch (JSONException e) {
                    Logger.e(e);
                    return new SyncProgress(new HashMap<>(), f.getRefId());
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            }
        }
        return new SyncProgress(new HashMap<>(), null);
    }

    private static SyncProgress parse(String json, String selfRefId) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONObject booksJson = root.optJSONObject("books");
        Map<String, ProgressEntry> map = new HashMap<>();
        if (booksJson != null) {
            java.util.Iterator<String> keys = booksJson.keys();
            while (keys.hasNext()) {
                String uuid = keys.next();
                JSONObject bookJson = booksJson.getJSONObject(uuid);
                ProgressEntry entry = new ProgressEntry();
                entry.lastLocatorJson = bookJson.isNull("lastLocator") ? null : bookJson.optString("lastLocator", null);
                entry.lastLocatorUpdatedAt = bookJson.optLong("lastLocatorUpdatedAt", 0);
                entry.comments = bookJson.isNull("comments") ? null : bookJson.optString("comments", null);
                JSONObject rt = bookJson.optJSONObject("readingTimeMs");
                if (rt != null) {
                    java.util.Iterator<String> rtKeys = rt.keys();
                    while (rtKeys.hasNext()) {
                        String unitKey = rtKeys.next();
                        entry.readingTimeMs.put(unitKey, rt.optLong(unitKey, 0));
                    }
                }
                JSONArray annotations = bookJson.optJSONArray("annotations");
                if (annotations != null) {
                    for (int i = 0; i < annotations.length(); i++) {
                        AnnotationEntry a = AnnotationEntry.fromJson(annotations.getJSONObject(i));
                        entry.annotations.put(a.key(), a);
                    }
                }
                map.put(uuid, entry);
            }
        }
        return new SyncProgress(map, selfRefId);
    }

    /**
     * Doc toan bo tien trinh local (moi sach co lpath) va merge vao {@code cloudProgress} theo
     * nguyen tac muc 3.4 cua prompt: last_locator lay ban co updatedAt lon hon; comments chi ghi
     * de neu local rong; annotations merge theo khoa (locatorJson, type), giu ban moi hon; reading
     * time lay max tung unit_key (khong cong don). Tra ve chinh {@code cloudProgress} (da duoc
     * merge tai cho) de goi tiep applyRemoteNewerIntoLocal/uploadTo.
     */
    static SyncProgress mergeLocalInto(MetadataDatabaseHelper db, SyncProgress cloudProgress) {
        Cursor cs = db.getAllProgressForSync();
        try {
            while (cs.moveToNext()) {
                String uuid = cs.getString(0);
                String localLocator = cs.isNull(1) ? null : cs.getString(1);
                long localLocatorUpdatedAt = cs.isNull(2) ? 0 : cs.getLong(2);
                String localComments = cs.isNull(3) ? null : cs.getString(3);

                ProgressEntry entry = cloudProgress.entryFor(uuid);

                // last_locator: ban co updatedAt lon hon thang; bang nhau thi giu local (khong
                // ghi gi, tuc la khong doi entry -- entry dang la ban cloud, chi ghi de neu local
                // thuc su moi hon).
                if (localLocator != null && localLocatorUpdatedAt > entry.lastLocatorUpdatedAt) {
                    entry.lastLocatorJson = localLocator;
                    entry.lastLocatorUpdatedAt = localLocatorUpdatedAt;
                }

                // comments: chi ghi de local-vao-merged khi merged (cloud) dang rong va local co
                // gia tri -- neu khong thi giu nguyen ban cloud (se duoc ap lai vao local o
                // applyRemoteNewerIntoLocal, dung dung nguyen tac "khong co timestamp rieng").
                if ((entry.comments == null || entry.comments.isEmpty())
                        && localComments != null && !localComments.isEmpty()) {
                    entry.comments = localComments;
                }

                // annotations
                Cursor annCs = db.getAnnotationsForSync(uuid);
                try {
                    int idxType = annCs.getColumnIndexOrThrow("type");
                    int idxLocator = annCs.getColumnIndexOrThrow("locator_json");
                    int idxColor = annCs.getColumnIndexOrThrow("color");
                    int idxNote = annCs.getColumnIndexOrThrow("note");
                    int idxExcerpt = annCs.getColumnIndexOrThrow("excerpt");
                    int idxCreated = annCs.getColumnIndexOrThrow("created_at");
                    int idxUpdated = annCs.getColumnIndexOrThrow("updated_at");
                    // BUG FIX (DATABASE_VERSION 15): deleted_at -- see getAnnotationsForSync()'s
                    // kdoc, it deliberately includes soft-deleted rows too, so tombstones make it
                    // into the merged/uploaded JSON instead of silently disappearing from it (which
                    // would look, from the cloud's point of view, identical to "this device just
                    // doesn't have this annotation" and get re-added right back on the next sync).
                    int idxDeleted = annCs.getColumnIndexOrThrow("deleted_at");
                    while (annCs.moveToNext()) {
                        AnnotationEntry local = new AnnotationEntry(
                                annCs.getString(idxType),
                                annCs.getString(idxLocator),
                                annCs.isNull(idxColor) ? null : annCs.getString(idxColor),
                                annCs.isNull(idxNote) ? null : annCs.getString(idxNote),
                                annCs.isNull(idxExcerpt) ? null : annCs.getString(idxExcerpt),
                                annCs.getLong(idxCreated),
                                annCs.getLong(idxUpdated),
                                !annCs.isNull(idxDeleted));
                        AnnotationEntry existing = entry.annotations.get(local.key());
                        if (existing == null || local.updatedAt > existing.updatedAt) {
                            entry.annotations.put(local.key(), local);
                        }
                    }
                } finally {
                    annCs.close();
                }
            }
        } finally {
            cs.close();
        }

        // reading time: max(local, remote) tung unit_key, khong cong don.
        Map<String, Map<String, Long>> allLocalReadingTime = db.getAllReadingTimeForSync();
        for (Map.Entry<String, Map<String, Long>> perBook : allLocalReadingTime.entrySet()) {
            ProgressEntry entry = cloudProgress.entryFor(perBook.getKey());
            for (Map.Entry<String, Long> perUnit : perBook.getValue().entrySet()) {
                Long remoteVal = entry.readingTimeMs.get(perUnit.getKey());
                long merged = Math.max(remoteVal != null ? remoteVal : 0, perUnit.getValue());
                entry.readingTimeMs.put(perUnit.getKey(), merged);
            }
        }

        return cloudProgress;
    }

    /** Ghi lai vao SQLite local moi gia tri "thang" sau merge -- goi SAU mergeLocalInto khi
     * upload (de nhan progress cua sach khac may nay cap nhat), hoac truc tiep sau
     * downloadOrEmpty khi download (chieu nguoc lai, khong can mergeLocalInto truoc vi
     * downloadMissingBooks chi ap dung cho sach vua duoc tai ve lan dau). */
    void applyRemoteNewerIntoLocal(MetadataDatabaseHelper db) {
        for (Map.Entry<String, ProgressEntry> e : books.entrySet()) {
            String uuid = e.getKey();
            ProgressEntry entry = e.getValue();
            if (entry.lastLocatorJson != null && entry.lastLocatorUpdatedAt > 0) {
                db.saveLastLocator(uuid, entry.lastLocatorJson, entry.lastLocatorUpdatedAt);
            }
            if (entry.comments != null && !entry.comments.isEmpty()) {
                db.saveCommentsIfEmpty(uuid, entry.comments);
            }
            for (Map.Entry<String, Long> rt : entry.readingTimeMs.entrySet()) {
                db.setReadingTimeMs(uuid, rt.getKey(), rt.getValue());
            }
            for (AnnotationEntry a : entry.annotations.values()) {
                if (a.deleted) {
                    // BUG FIX (DATABASE_VERSION 15): this is a tombstone (deleted on some
                    // device -- could be this one or another). If THIS device still has a live
                    // copy, propagate the deletion to it instead of leaving it dangling (e.g. the
                    // user deleted a bookmark on their phone; this is their tablet catching up).
                    // If there's no live local copy, there's nothing to do -- in particular, this
                    // is what stops a tombstone from ever being "added" via addAnnotation(): the
                    // old code path here never distinguished deleted from live entries at all, so
                    // every annotation.entry from the merged JSON -- deleted or not -- fell
                    // through to the exact same add/update logic below, which is how a
                    // just-deleted bookmark came back from the cloud on the very next sync.
                    long existingId = db.findAnnotationId(uuid, a.type, a.locatorJson);
                    if (existingId >= 0) {
                        db.applyRemoteDeletion(uuid, a.type, a.locatorJson, a.updatedAt);
                    }
                    continue;
                }
                // BUG FIX (DATABASE_VERSION 15): findAnnotationId() now checks ALL types (was
                // findBookmarkId(), bookmark-only, with every other type hardcoded to -1 below --
                // see that method's kdoc). Without this, every highlight/note synced down got
                // re-inserted via addAnnotation() on every single sync, duplicating it once per
                // sync cycle.
                long existingId = db.findAnnotationId(uuid, a.type, a.locatorJson);
                if (existingId < 0) {
                    db.addAnnotation(uuid, a.type, a.locatorJson, a.color, a.note, a.excerpt);
                } else if (a.note != null) {
                    db.updateAnnotationNote(existingId, a.note);
                }
            }
        }
    }

    void uploadTo(CloudProvider provider, String driveFolderId) throws IOException {
        File tmp = File.createTempFile("sync_progress_out", ".json");
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
        JSONObject booksJson = new JSONObject();
        for (Map.Entry<String, ProgressEntry> e : books.entrySet()) {
            ProgressEntry entry = e.getValue();
            JSONObject bookJson = new JSONObject();
            if (entry.lastLocatorJson != null) bookJson.put("lastLocator", entry.lastLocatorJson);
            bookJson.put("lastLocatorUpdatedAt", entry.lastLocatorUpdatedAt);
            if (entry.comments != null) bookJson.put("comments", entry.comments);
            JSONObject rt = new JSONObject();
            for (Map.Entry<String, Long> u : entry.readingTimeMs.entrySet()) rt.put(u.getKey(), u.getValue());
            bookJson.put("readingTimeMs", rt);
            JSONArray annotations = new JSONArray();
            for (AnnotationEntry a : entry.annotations.values()) annotations.put(a.toJson());
            bookJson.put("annotations", annotations);
            booksJson.put(e.getKey(), bookJson);
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
