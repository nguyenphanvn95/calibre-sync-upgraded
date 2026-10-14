package net.mrphan.calibre;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/** Cache local (1 file .db riêng cho mỗi thư viện GDrive, tên "gdrive_lib_<id>.db") build từ nội
 * dung metadata_public.json — đối xứng với GDriveLibraryCache (sqflite) bên bản Flutter. Bảng
 * "books" là nguồn dữ liệu cho GDriveOpdsHttpServer khi build Atom feed; "library_meta" lưu
 * generated_at/source_file_id/book_count để GDriveOpdsHttpServer#needsResync() quyết định có
 * cần tải lại metadata_public.json hay không mỗi khi mở thư viện. */
class GDriveLibraryDb extends SQLiteOpenHelper {

    // BUG FIX: was 1. metadata_public.json's "pubdate"/"timestamp" fields were never even read
    // into this cache (only "last_modified" was), so the Book Detail preview (GDriveBookEntryBuilder)
    // had no way to show a publication date, and downloading a book (GDriveLibraryJsBridge)
    // had no way to carry it into the Device Library either -- see both classes' Javadoc. This
    // is just a rebuildable local cache of metadata_public.json (see class Javadoc), so bumping
    // the version and letting onUpgrade() drop+recreate the table is safe: the next library open
    // re-syncs from the source file automatically (see needsResync()/rebuildFromJson()).
    private static final int DB_VERSION = 2;

    static String dbFileName(String libraryId) {
        return "gdrive_lib_" + libraryId.replaceAll("[^a-zA-Z0-9_-]", "_") + ".db";
    }

    static void deleteDbFile(Context context, String libraryId) {
        context.getApplicationContext().deleteDatabase(dbFileName(libraryId));
    }

    GDriveLibraryDb(Context context, String libraryId) {
        super(context.getApplicationContext(), dbFileName(libraryId), null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE books (" +
                "book_id INTEGER PRIMARY KEY," +
                "title TEXT," +
                "sort TEXT," +
                "author_sort TEXT," +
                "authors TEXT," +
                "series TEXT," +
                "series_index REAL," +
                "publisher TEXT," +
                "tags_json TEXT," +
                "rating INTEGER," +
                "languages_json TEXT," +
                "comments TEXT," +
                "isbn TEXT," +
                "pubdate TEXT," +
                "timestamp TEXT," +
                "uuid TEXT," +
                "has_cover INTEGER," +
                "cover_file_id TEXT," +
                "formats_json TEXT," +
                "last_modified TEXT" +
                ")");
        db.execSQL("CREATE TABLE library_meta (key TEXT PRIMARY KEY, value TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS books");
        db.execSQL("DROP TABLE IF EXISTS library_meta");
        onCreate(db);
    }

    /** Xóa sạch và build lại toàn bộ bảng "books" từ nội dung raw của metadata_public.json.
     * Gọi trong background thread (I/O + parse JSON có thể tốn thời gian với thư viện lớn). */
    void rebuildFromJson(String rawJson, String sourceFileId) throws JSONException {
        JSONObject decoded = new JSONObject(rawJson);
        JSONObject books = decoded.optJSONObject("books");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("books", null, null);
            if (books != null) {
                java.util.Iterator<String> keys = books.keys();
                while (keys.hasNext()) {
                    String bookIdKey = keys.next();
                    JSONObject book = books.optJSONObject(bookIdKey);
                    if (book == null) continue;
                    insertBook(db, bookIdKey, book);
                }
            }
            putMeta(db, "generated_at", decoded.optString("generated_at", ""));
            putMeta(db, "source_file_id", sourceFileId);
            putMeta(db, "library_path", decoded.optString("library_path", ""));
            putMeta(db, "book_count", String.valueOf(books != null ? books.length() : 0));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void insertBook(SQLiteDatabase db, String bookIdKey, JSONObject book) throws JSONException {
        int bookId = book.has("book_id") ? book.optInt("book_id") : Integer.parseInt(bookIdKey);
        String title = book.optString("title", "Unknown Title");
        String sort = book.optString("sort", "");

        JSONObject cover = book.optJSONObject("cover");
        String coverFileId = cover != null ? cover.optString("file_id", null) : null;

        JSONObject formatsIn = book.optJSONObject("formats");
        JSONObject formatsOut = new JSONObject();
        if (formatsIn != null) {
            java.util.Iterator<String> fk = formatsIn.keys();
            while (fk.hasNext()) {
                String code = fk.next();
                formatsOut.put(code.toUpperCase(Locale.US), formatsIn.get(code));
            }
        }

        ContentValues cv = new ContentValues();
        cv.put("book_id", bookId);
        cv.put("title", title);
        cv.put("sort", sort.isEmpty() ? title : sort);
        cv.put("author_sort", book.optString("author_sort", ""));
        cv.put("authors", book.optString("authors", "Unknown"));
        cv.put("series", book.isNull("series") ? null : book.optString("series", null));
        cv.put("series_index", book.isNull("series_index") ? null : book.optDouble("series_index"));
        cv.put("publisher", book.optString("publisher", ""));
        cv.put("tags_json", (book.optJSONArray("tags") != null ? book.optJSONArray("tags") : new JSONArray()).toString());
        cv.put("rating", book.isNull("rating") ? null : book.optInt("rating"));
        cv.put("languages_json", (book.optJSONArray("languages") != null ? book.optJSONArray("languages") : new JSONArray()).toString());
        cv.put("comments", book.optString("comments", ""));
        cv.put("isbn", book.optString("isbn", ""));
        cv.put("pubdate", book.isNull("pubdate") ? null : book.optString("pubdate", null));
        // "timestamp" is Calibre's own field name for "date added to library" -- some exporters
        // may instead call it "date_added", so fall back to that if "timestamp" isn't present.
        String dateAdded = !book.isNull("timestamp") ? book.optString("timestamp", null) : null;
        if (dateAdded == null && !book.isNull("date_added")) dateAdded = book.optString("date_added", null);
        cv.put("timestamp", dateAdded);
        cv.put("uuid", book.isNull("uuid") ? null : book.optString("uuid", null));
        cv.put("has_cover", book.optBoolean("has_cover", false) ? 1 : 0);
        cv.put("cover_file_id", coverFileId);
        cv.put("formats_json", formatsOut.toString());
        cv.put("last_modified", book.isNull("last_modified") ? null : book.optString("last_modified", null));

        db.insertWithOnConflict("books", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private void putMeta(SQLiteDatabase db, String key, String value) {
        ContentValues cv = new ContentValues();
        cv.put("key", key);
        cv.put("value", value);
        db.insertWithOnConflict("library_meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    String getMeta(String key) {
        Cursor c = getReadableDatabase().query("library_meta", new String[]{"value"}, "key=?",
                new String[]{key}, null, null, null);
        try {
            if (c.moveToFirst()) return c.getString(0);
            return null;
        } finally {
            c.close();
        }
    }

    /** True nếu chưa từng sync, hoặc source_file_id đã lưu khác với file đang cấu hình (ví dụ
     * người dùng Sửa thư viện để trỏ sang 1 metadata_public.json khác). Đồng bộ lại theo yêu cầu
     * (nút "Làm mới") luôn forceRefresh=true bất kể hàm này trả gì. */
    boolean needsResync(String sourceFileId) {
        String stored = getMeta("source_file_id");
        return stored == null || !stored.equals(sourceFileId);
    }

    /** "Chữ ký" hiện tại của nội dung đã cache -- dùng để nút "Làm mới" so sánh với nội dung mới
     * tải về TRƯỚC khi quyết định có cần xóa/ghi lại cache hay không (xem
     * AddGDriveOpdsLibraryDialogFragment/GDriveOpdsLibraryListBottomSheet). Ưu tiên
     * "generated_at" (Calibre ghi mỗi lần xuất metadata_public.json); nếu file cũ không có field
     * này thì rơi về "count:<book_count>" -- vẫn phát hiện được thay đổi số sách dù không hoàn
     * hảo bằng generated_at (2 lần xuất có cùng số sách nhưng nội dung khác sẽ không bị phát
     * hiện). */
    String getRefreshSignature() {
        String generatedAt = getMeta("generated_at");
        if (generatedAt != null && !generatedAt.isEmpty()) return generatedAt;
        return "count:" + getMeta("book_count");
    }

    /** Chữ ký tương ứng của 1 nội dung metadata_public.json THÔ vừa tải về (chưa rebuild vào
     * DB) -- dùng cùng quy ước với getRefreshSignature() để so sánh trước khi rebuild. */
    static String refreshSignatureOf(String rawJson) {
        try {
            JSONObject decoded = new JSONObject(rawJson);
            String generatedAt = decoded.optString("generated_at", "");
            if (!generatedAt.isEmpty()) return generatedAt;
            JSONObject books = decoded.optJSONObject("books");
            return "count:" + (books != null ? books.length() : 0);
        } catch (JSONException e) {
            // Khong doc duoc -- coi nhu luon khac de rebuildFromJson() chay va tu bao loi ro
            // rang hon la lang lang giu cache cu.
            return "parse-error:" + System.nanoTime();
        }
    }

    int getBookCount() {
        String s = getMeta("book_count");
        try {
            return s != null ? Integer.parseInt(s) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Trả về danh sách sách thô (JSONObject) để GDriveOpdsHttpServer build Atom feed — tránh
     * tạo thêm 1 lớp model Java riêng (GDriveBookModel) không thật cần thiết ở bản Java, vì XML
     * builder trong GDriveOpdsHttpServer đọc cursor trực tiếp là đủ.
     * BUG FIX: thêm tham số tag để hỗ trợ lọc theo thể loại (trước đây chỉ lọc được theo search). */
    Cursor queryBooksCursor(String search, String tag, int offset, int limit, String sortBy) {
        String orderBy;
        switch (sortBy == null ? "title" : sortBy) {
            case "authors":
                orderBy = "author_sort ASC";
                break;
            case "date":
                orderBy = "last_modified DESC";
                break;
            default:
                orderBy = "sort ASC";
        }
        java.util.List<String> where = new java.util.ArrayList<>();
        java.util.List<String> args = new java.util.ArrayList<>();
        if (search != null && !search.trim().isEmpty()) {
            where.add("(title LIKE ? OR authors LIKE ?)");
            String like = "%" + search.trim() + "%";
            args.add(like);
            args.add(like);
        }
        if (tag != null && !tag.trim().isEmpty()) {
            // tags_json la 1 mang JSON dang ["Fiction","Sci-Fi"] -- LIKE voi dau nhay kep bao
            // quanh tranh khop nham 1 tag la substring cua tag khac (vd "Fiction" vs "SciFiction").
            where.add("tags_json LIKE ?");
            args.add("%\"" + tag.trim() + "\"%");
        }
        String selection = where.isEmpty() ? null : String.join(" AND ", where);
        String[] selectionArgs = where.isEmpty() ? null : args.toArray(new String[0]);
        return getReadableDatabase().query("books",
                new String[]{"book_id", "title", "authors", "series", "series_index", "tags_json",
                        "comments", "has_cover", "cover_file_id", "formats_json", "last_modified"},
                selection, selectionArgs, null, null, orderBy,
                offset + "," + limit);
    }

    Cursor getBookById(int bookId) {
        return getReadableDatabase().query("books",
                new String[]{"book_id", "title", "authors", "cover_file_id", "formats_json"},
                "book_id=?", new String[]{String.valueOf(bookId)}, null, null, null);
    }

    /** Toan bo cot cua 1 sach -- dung cho Phan 3 (tai vao Device Library, can day du
     * tags/series/rating/publisher/comments...) va cho openBookDetail() (dung BookDetailActivity
     * de xem truoc, xem GDriveLibraryJsBridge). Caller phai dong Cursor. */
    Cursor getBookFullById(int bookId) {
        return getReadableDatabase().query("books",
                new String[]{"book_id", "title", "sort", "author_sort", "authors", "series",
                        "series_index", "publisher", "tags_json", "rating", "languages_json",
                        "comments", "isbn", "pubdate", "timestamp", "uuid", "has_cover",
                        "cover_file_id", "formats_json", "last_modified"},
                "book_id=?", new String[]{String.valueOf(bookId)}, null, null, null);
    }

    /** Xây lại đúng cấu trúc JSON mà GDriveOpdsHttpServer#handleLibraryJson() trả cho WebView
     * (route "/api/library.json") -- về bản chất là "trả lại metadata_public.json đã cache" từ
     * bảng "books", cộng thêm cover_url/download_url trỏ tới 2 route proxy cục bộ đã có sẵn, để
     * app.js không cần biết gì về Google Drive/file_id trực tiếp nữa (xem phần 2 của prompt nâng
     * cấp). Gọi trên background thread (I/O SQLite + build JSONObject có thể tốn thời gian với
     * thư viện lớn). */
    // Chu ky moi: buildLibraryJson(Set<String> deviceUuids) -- caller (GDriveOpdsHttpServer) tu doc
    // Device Library DB (MetadataDatabaseHelper) roi truyen vao, GDriveLibraryDb KHONG tu mo them 1
    // SQLiteOpenHelper khac (giu dung nguyen tac "1 class, 1 DB" da co).
    JSONObject buildLibraryJson(java.util.Set<String> deviceUuids) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("generated_at", getMeta("generated_at"));
        root.put("library_path", getMeta("library_path"));
        JSONObject books = new JSONObject();
        Cursor c = getReadableDatabase().query("books", null, null, null, null, null, "sort ASC");
        try {
            while (c.moveToNext()) {
                int bookId = c.getInt(c.getColumnIndexOrThrow("book_id"));
                books.put(String.valueOf(bookId), bookRowToJson(c, bookId, deviceUuids));
            }
        } finally {
            c.close();
        }
        root.put("books", books);
        return root;
    }

    private JSONObject bookRowToJson(Cursor c, int bookId, java.util.Set<String> deviceUuids) throws JSONException {
        JSONObject book = new JSONObject();
        book.put("book_id", bookId);
        book.put("title", c.getString(c.getColumnIndexOrThrow("title")));
        book.put("sort", c.getString(c.getColumnIndexOrThrow("sort")));
        book.put("author_sort", c.getString(c.getColumnIndexOrThrow("author_sort")));
        book.put("authors", c.getString(c.getColumnIndexOrThrow("authors")));
        book.put("series", nullableString(c, "series"));
        if (!c.isNull(c.getColumnIndexOrThrow("series_index"))) {
            book.put("series_index", c.getDouble(c.getColumnIndexOrThrow("series_index")));
        }
        book.put("publisher", c.getString(c.getColumnIndexOrThrow("publisher")));
        book.put("tags", new JSONArray(c.getString(c.getColumnIndexOrThrow("tags_json"))));
        if (!c.isNull(c.getColumnIndexOrThrow("rating"))) {
            book.put("rating", c.getInt(c.getColumnIndexOrThrow("rating")));
        }
        book.put("languages", new JSONArray(c.getString(c.getColumnIndexOrThrow("languages_json"))));
        book.put("comments", c.getString(c.getColumnIndexOrThrow("comments")));
        book.put("isbn", c.getString(c.getColumnIndexOrThrow("isbn")));
        book.put("pubdate", nullableString(c, "pubdate"));
        book.put("timestamp", nullableString(c, "timestamp"));
        String uuid = nullableString(c, "uuid");
        book.put("uuid", uuid);
        book.put("in_device_library", uuid != null && deviceUuids.contains(uuid));
        boolean hasCover = c.getInt(c.getColumnIndexOrThrow("has_cover")) == 1;
        book.put("has_cover", hasCover);
        if (hasCover) {
            book.put("cover_url", "/opds/cover/" + bookId);
        }
        JSONObject formatsOut = new JSONObject();
        JSONObject formatsIn = new JSONObject(c.getString(c.getColumnIndexOrThrow("formats_json")));
        java.util.Iterator<String> keys = formatsIn.keys();
        while (keys.hasNext()) {
            String code = keys.next();
            JSONObject fmt = new JSONObject(formatsIn.getJSONObject(code).toString());
            fmt.put("download_url", "/" + bookId + "/download/" + code.toLowerCase(Locale.US));
            formatsOut.put(code, fmt);
        }
        book.put("formats", formatsOut);
        book.put("last_modified", nullableString(c, "last_modified"));
        return book;
    }

    private String nullableString(Cursor c, String column) {
        int idx = c.getColumnIndexOrThrow(column);
        return c.isNull(idx) ? null : c.getString(idx);
    }

    /** Toan bo cover_file_id KHONG NULL trong thu vien, dung cho GDriveCoverPrefetcher -- tra ve
     * List (khong phai Set) de giu nguyen thu tu "sort ASC" (uu tien prefetch dung thu tu sach se
     * hien thi truoc trong feed mac dinh). */
    java.util.List<String> getAllCoverFileIds() {
        java.util.List<String> result = new java.util.ArrayList<>();
        Cursor c = getReadableDatabase().query("books", new String[]{"cover_file_id"},
                "cover_file_id IS NOT NULL", null, null, null, "sort ASC");
        try {
            while (c.moveToNext()) {
                String id = c.getString(0);
                if (id != null && !id.isEmpty()) result.add(id);
            }
        } finally {
            c.close();
        }
        return result;
    }

    /** Tong so sach khop [search]/[tag] (khong ap offset/limit). Hien khong con noi nao goi ham
     * nay (feed Atom/OPDS cu da bi go bo, xem GDriveOpdsHttpServer) -- giu lai phong khi can loc
     * theo tag/tu khoa cho route "/api/library.json" trong tuong lai. */
    int countBooks(String search, String tag) {
        java.util.List<String> where = new java.util.ArrayList<>();
        java.util.List<String> args = new java.util.ArrayList<>();
        if (search != null && !search.trim().isEmpty()) {
            where.add("(title LIKE ? OR authors LIKE ?)");
            String like = "%" + search.trim() + "%";
            args.add(like);
            args.add(like);
        }
        if (tag != null && !tag.trim().isEmpty()) {
            where.add("tags_json LIKE ?");
            args.add("%\"" + tag.trim() + "\"%");
        }
        String selection = where.isEmpty() ? null : String.join(" AND ", where);
        String[] selectionArgs = where.isEmpty() ? null : args.toArray(new String[0]);
        Cursor c = getReadableDatabase().query("books", new String[]{"COUNT(*)"}, selection, selectionArgs, null, null, null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /** Danh sach the (tag) khong trung lap trong toan bo thu vien, kem so luong sach cho moi the,
     * dung de build trang dieu huong "The loai". Duyet toan bo bang books o phia Java (khong dung
     * SQL json_each()) vi json1 extension cua SQLite khong chac chan co san tren moi thiet bi
     * Android. Sap xep khong phan biet hoa/thuong bang TreeMap. */
    java.util.List<TagCount> getAllTags() {
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Cursor c = getReadableDatabase().query("books", new String[]{"tags_json"}, null, null, null, null, null);
        try {
            while (c.moveToNext()) {
                String json = c.getString(0);
                if (json == null) continue;
                try {
                    JSONArray tags = new JSONArray(json);
                    for (int i = 0; i < tags.length(); i++) {
                        String tag = tags.optString(i, null);
                        if (tag == null || tag.trim().isEmpty()) continue;
                        String key = tag.trim();
                        counts.merge(key, 1, Integer::sum);
                    }
                } catch (JSONException ignored) {
                }
            }
        } finally {
            c.close();
        }
        java.util.List<TagCount> result = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
            result.add(new TagCount(e.getKey(), e.getValue()));
        }
        return result;
    }

    /** Ket qua getAllTags(): 1 the (tag) kem so luong sach co the do. Hien khong con noi nao goi
     * ham nay (trang dieu huong "The loai" cua feed Atom/OPDS cu da bi go bo, xem
     * GDriveOpdsHttpServer) -- giu lai phong khi can hien danh sach the trong "/api/library.json"
     * hoac sidebar trong tuong lai. */
    static class TagCount {
        final String tag;
        final int count;
        TagCount(String tag, int count) {
            this.tag = tag;
            this.count = count;
        }
    }

    /** Tong hop so lieu hien thi trong dialog "Library details" (Phan 2 cua prompt nang cap):
     * Books/Authors/Tags/Publishers/Series, cong voi generated_at/library_path de dialog co the
     * hien them neu can. Goi tren background thread (nhieu truy van SQLite). */
    static class LibrarySummary {
        final int bookCount;
        final int authorCount;
        final int tagCount;
        final int publisherCount;
        final int seriesCount;
        final String generatedAt;
        final String libraryPath;

        LibrarySummary(int bookCount, int authorCount, int tagCount, int publisherCount,
                       int seriesCount, String generatedAt, String libraryPath) {
            this.bookCount = bookCount;
            this.authorCount = authorCount;
            this.tagCount = tagCount;
            this.publisherCount = publisherCount;
            this.seriesCount = seriesCount;
            this.generatedAt = generatedAt;
            this.libraryPath = libraryPath;
        }
    }

    LibrarySummary getSummary() {
        return new LibrarySummary(
                getBookCount(),
                getAuthorCount(),
                getAllTags().size(),
                getDistinctNonEmptyCount("publisher"),
                getDistinctNonEmptyCount("series"),
                getMeta("generated_at"),
                getMeta("library_path"));
    }

    /** Dem so tac gia PHAN BIET trong toan bo thu vien. Calibre noi nhieu tac gia cua 1 sach
     * bang " & " trong field "authors" (vi du "Nguyen Nhat Anh & Ta Huy Long") -- tach theo dau
     * "&" roi trim() tung ten truoc khi dua vao TreeSet khong phan biet hoa/thuong, tranh dem
     * trung "Author A" va "author a" la 2 nguoi khac nhau. */
    int getAuthorCount() {
        java.util.Set<String> distinct = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Cursor c = getReadableDatabase().query("books", new String[]{"authors"}, null, null, null, null, null);
        try {
            while (c.moveToNext()) {
                String raw = c.getString(0);
                if (raw == null || raw.trim().isEmpty()) continue;
                for (String name : raw.split("\\s*&\\s*")) {
                    String trimmed = name.trim();
                    if (!trimmed.isEmpty()) distinct.add(trimmed);
                }
            }
            return distinct.size();
        } finally {
            c.close();
        }
    }

    /** Dem so gia tri PHAN BIET, khong rong, cua 1 cot don gia tri (khong phai JSON array) --
     * dung cho "publisher" va "series". column CHI duoc goi voi hang so noi bo co san ("publisher",
     * "series"), khong nhan input tu nguoi dung, nen an toan khi noi chuoi truc tiep vao selection. */
    private int getDistinctNonEmptyCount(String column) {
        Cursor c = getReadableDatabase().query(true, "books", new String[]{column},
                column + " IS NOT NULL AND TRIM(" + column + ") <> ''",
                null, null, null, null, null);
        try {
            return c.getCount();
        } finally {
            c.close();
        }
    }
}
