package net.mrphan.calibre;

import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.List;
import java.util.Map;

/** Ghi thay đổi từ {@link BookMetadataForm} vào ĐÚNG 1 entry (theo uuid) trong
 * rootDirectory/metadata.calibre, giữ nguyên mọi entry khác và mọi field lạ trên chính entry đó
 * mà form không có ô để sửa (ví dụ "last_modified", "timestamp", field tùy biến do Calibre
 * desktop đồng bộ sang). Đây là điểm khác biệt cốt lõi so với
 * {@link BookCrawler#writeMetadataFile()}, vốn REBUILD toàn bộ file chỉ từ tập cột rút gọn
 * trong SQLite (uuid/lpath/title/author/tags/thumbnail/cover_path/comments -- xem
 * MetadataDatabaseHelper#exportAllBooksAsJSON) -- gọi writeMetadataFile() sau khi sửa 1 quyển
 * sách sẽ âm thầm xóa sạch publisher/series/pubdate/rating/identifiers/languages... của TẤT CẢ
 * sách khác trong thư viện. Class này không bao giờ gọi writeMetadataFile(); nó tự đọc-sửa-ghi
 * đúng 1 phần tử của mảng JSON, dùng chung {@link MetadataFileIo#LOCK} với writeMetadataFile()
 * để hai đường ghi không bao giờ chồng lên nhau. */
final class DeviceBookMetadataWriter {

    private DeviceBookMetadataWriter() {}

    /** Patch entry của uuid form.uuid trong metadata.calibre theo nội dung form, rồi đồng bộ lại
     * các cột rút gọn trong SQLite (title/author/comments + thumbnail nếu đổi bìa) để list/detail
     * hiển thị đúng ngay mà không cần rescan toàn bộ thư viện. Ném Exception nếu không ghi được
     * (caller phải báo lỗi rõ ràng cho người dùng). */
    static void save(File rootDirectory, MetadataDatabaseHelper db, BookMetadataForm form) throws Exception {
        synchronized (MetadataFileIo.LOCK) {
            JSONArray all = MetadataFileIo.readAll(rootDirectory);
            JSONObject target = null;
            for (int i = 0; i < all.length(); i++) {
                JSONObject o = all.optJSONObject(i);
                if (o != null && form.uuid.equals(o.optString("uuid", null))) {
                    target = o;
                    break;
                }
            }
            if (target == null) {
                // Sách chưa có entry (ví dụ vẫn đang "_new_book_" chỉ có trong SQLite, chưa qua
                // 1 vòng writeMetadataFile() nào) -- tạo entry mới thay vì bỏ qua, để lần Hiệu
                // đính đầu tiên không bị mất dữ liệu.
                target = new JSONObject();
                target.put("uuid", form.uuid);
                target.put("lpath", form.lpath);
                all.put(target);
            }
            applyFormToJson(target, form);
            MetadataFileIo.writeAll(rootDirectory, all);
        }

        // Đồng bộ lại các cột rút gọn trong SQLite để list/detail hiển thị đúng ngay, không cần
        // rescan toàn bộ thư viện -- và, quan trọng hơn (DATABASE_VERSION 13), để một lần
        // BookCrawler#writeMetadataFile() rebuild sau đó (rebuild TỪ SQLite, xem
        // MetadataDatabaseHelper#exportAllBooksAsJSON) không âm thầm xóa mất đúng những field
        // vừa Hiệu đính ở trên. Xem updateMetadataFields()'s javadoc cho quy ước null/rỗng.
        String primaryAuthor = form.authors.isEmpty() ? "" : form.authors.get(0);
        Double seriesIndex = parseDoubleOrNull(form.seriesIndex);
        Double rating = parseDoubleOrNull(form.rating);
        String languagesCsv = form.languages.isEmpty() ? null : TextUtils.join(",", form.languages);
        String identifiersJson = toIdentifiersJson(form.identifiers);
        db.updateMetadataFields(form.uuid, form.title, primaryAuthor, form.comments, joinTags(form.tags),
                form.publisher, form.pubDate, form.series, seriesIndex, rating,
                languagesCsv, identifiersJson, form.customColumnDefs, form.customColumnValues);
        if (form.newCoverBytes != null) {
            db.updateThumbnail(form.uuid, form.newCoverBytes);
        }
    }

    private static Double parseDoubleOrNull(String s) {
        if (TextUtils.isEmpty(s)) return null;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String toIdentifiersJson(Map<String, String> identifiers) {
        if (identifiers == null || identifiers.isEmpty()) return null;
        JSONObject ids = new JSONObject();
        try {
            for (Map.Entry<String, String> e : identifiers.entrySet()) {
                if (e.getKey() != null && !e.getKey().trim().isEmpty()) {
                    ids.put(e.getKey().trim(), e.getValue());
                }
            }
        } catch (Exception ignored) {
            // JSONObject.put() only throws for a null key, already guarded above.
        }
        return ids.length() > 0 ? ids.toString() : null;
    }

    private static void applyFormToJson(JSONObject target, BookMetadataForm form) throws Exception {
        putOrRemove(target, "title", form.title);
        putOrRemove(target, "title_sort", form.titleSort);
        target.put("authors", toJsonArray(form.authors));
        putOrRemove(target, "author_sort", form.authorSort);
        putOrRemove(target, "publisher", form.publisher);
        putOrRemove(target, "pubdate", form.pubDate);
        if (!form.languages.isEmpty()) {
            target.put("languages", toJsonArray(form.languages));
        } else {
            target.remove("languages");
        }
        putOrRemove(target, "series", form.series);
        if (!TextUtils.isEmpty(form.seriesIndex)) {
            try {
                target.put("series_index", Double.parseDouble(form.seriesIndex.trim()));
            } catch (NumberFormatException ignored) {
                target.remove("series_index");
            }
        } else {
            target.remove("series_index");
        }
        if (!TextUtils.isEmpty(form.rating)) {
            try {
                target.put("rating", Double.parseDouble(form.rating.trim()));
            } catch (NumberFormatException ignored) {
                target.remove("rating");
            }
        } else {
            target.remove("rating");
        }
        target.put("tags", toJsonArray(form.tags));
        if (!form.identifiers.isEmpty()) {
            JSONObject ids = new JSONObject();
            for (Map.Entry<String, String> e : form.identifiers.entrySet()) {
                if (e.getKey() != null && !e.getKey().trim().isEmpty()) {
                    ids.put(e.getKey().trim(), e.getValue());
                }
            }
            target.put("identifiers", ids);
        } else {
            target.remove("identifiers");
        }
        putOrRemove(target, "comments", form.comments);

        if (form.customColumnDefs != null && !form.customColumnDefs.isEmpty()
                && form.customColumnValues != null && !form.customColumnValues.isEmpty()) {
            CustomColumnSync.applyValuesIntoWireJson(target, form.customColumnDefs, form.customColumnValues);
        }

        if (form.newCoverBytes != null) {
            JSONArray thumb = new JSONArray();
            thumb.put("jpeg");
            thumb.put(0);
            thumb.put(0);
            thumb.put(Base64.encodeToString(form.newCoverBytes, Base64.NO_WRAP));
            target.put("thumbnail", thumb);
        }
        // form.newCoverBytes == null -> KHÔNG đụng vào field "thumbnail" cũ, giữ nguyên bìa hiện có.
    }

    private static void putOrRemove(JSONObject o, String key, String value) throws Exception {
        if (value == null || value.trim().isEmpty()) {
            o.remove(key);
        } else {
            o.put(key, value.trim());
        }
    }

    private static JSONArray toJsonArray(List<String> list) {
        JSONArray arr = new JSONArray();
        for (String s : list) {
            if (s != null && !s.trim().isEmpty()) arr.put(s.trim());
        }
        return arr;
    }

    private static String joinTags(List<String> tags) {
        return TextUtils.join(", ", tags);
    }
}
