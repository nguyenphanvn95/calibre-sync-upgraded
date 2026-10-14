package net.mrphan.calibre;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cầu nối JS <-> native cho GDriveLibraryWebViewActivity, đăng ký vào WebView với tên
 * "AndroidBridge" (xem GDriveLibraryWebViewActivity#onCreate). Thay thế 2 hành động mà bản HTML
 * gốc (index.html/app.js "Thư viện mini") vốn làm bằng window.open()/modal:
 *
 * - downloadBook(bookId, format): tải đúng 1 định dạng của 1 sách vào Device Library, với ĐẦY ĐỦ
 *   metadata (tags/series/publisher/rating/comments...) đọc lại từ GDriveLibraryDb -- KHÔNG dùng
 *   BookMetadataSniffer (xem class Javadoc lý do) và KHÔNG gọi CalibreService.rescanBookFolder()
 *   (rescan đầy đủ có thể quét lại đúng file này bằng BookCrawler/Sniffer và ghi đè bằng metadata
 *   nghèo hơn nếu nó chạy trước khi DB kịp có record) -- xem Phần 3 của prompt nâng cấp.
 * - openBookDetail(bookId): dựng 1 OPDSEntry "tổng hợp" từ GDriveLibraryDb rồi mở BookDetailActivity
 *   y hệt cách tab OPDS/Content Server đang mở màn xem trước, thay vì modal HTML.
 *
 * Mọi callback vào JS (onNativeDownloadResult) chạy qua WebView#evaluateJavascript, phải post lên
 * main thread vì bridge method tự nó chạy trên thread của WebView (JavaBridge thread), không phải
 * main thread -- đây là lý do bắt buộc của @JavascriptInterface.
 */
class GDriveLibraryJsBridge {

    private final Activity activity;
    private final GDriveLibraryDb db;
    private final int port;
    private final WebView webView;
    /** id của thư viện GDrive dang mo (GDriveOpdsLibrary#getId()) -- can truyen sang
     * BookDetailActivity de no tu dung lai 1 GDriveLibraryDb khi vuot trai/phai sang sach ke
     * tiep/truoc do (xem EXTRA_GDRIVE_LIBRARY_ID), vi luc do khong con jsBridge/WebView nay nua. */
    private final String libraryId;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    GDriveLibraryJsBridge(Activity activity, GDriveLibraryDb db, int port, WebView webView, String libraryId) {
        this.activity = activity;
        this.db = db;
        this.port = port;
        this.webView = webView;
        this.libraryId = libraryId;
    }

    @JavascriptInterface
    public void downloadBook(int bookId, String format) {
        executor.execute(() -> {
            String errorMessage = null;
            boolean ok = false;
            try {
                ok = doDownloadBookAndReturnFile(bookId, format) != null;
            } catch (Exception e) {
                Logger.e(e);
                errorMessage = e.getMessage();
            }
            notifyDownloadResult(bookId, ok, errorMessage);
        });
    }

    @JavascriptInterface
    public void downloadAndOpenBook(int bookId, String format) {
        executor.execute(() -> {
            try {
                ResolvedBookFile resolved = ensureDownloaded(bookId, format);
                if (resolved == null) { showToast(R.string.cloud_download_failed_generic); return; }
                mainHandler.post(() -> activity.startActivity(new Intent(activity, OpenBookActivity.class)
                        .setAction(OpenBookActivity.OPEN_BOOK)
                        .putExtra(OpenBookActivity.EXTRA_UUID, resolved.uuid)));
            } catch (Exception e) {
                Logger.e(e);
                showToast(R.string.cloud_download_failed_generic);
            }
        });
    }

    @JavascriptInterface
    public void downloadAndShareBook(int bookId, String format) {
        executor.execute(() -> {
            try {
                ResolvedBookFile resolved = ensureDownloaded(bookId, format);
                if (resolved == null) { showToast(R.string.cloud_download_failed_generic); return; }
                mainHandler.post(() -> ShareUtils.shareFiles(activity, java.util.Collections.singletonList(resolved.file)));
            } catch (Exception e) {
                Logger.e(e);
                showToast(R.string.cloud_download_failed_generic);
            }
        });
    }

    private static final class ResolvedBookFile {
        final String uuid;
        final File file;
        ResolvedBookFile(String uuid, File file) { this.uuid = uuid; this.file = file; }
    }

    /** Neu sach (theo uuid) da co san lpath trong Device Library (metadata da tai o phien truoc, vi
     * du qua nut tai nhanh tren card hoac qua BookDetailActivity), tra thang file da co -- KHONG tai
     * lai. Nguoc lai tai moi dung nhu doDownloadBookAndReturnFile() roi tra file vua ghi. */
    private ResolvedBookFile ensureDownloaded(int bookId, String format) throws Exception {
        String uuid = uuidForBook(bookId);
        if (uuid != null) {
            MetadataDatabaseHelper metaDb = new MetadataDatabaseHelper(activity.getApplicationContext());
            try {
                Cursor cs = metaDb.getByUuid(new String[]{"lpath"}, uuid);
                try {
                    if (cs.moveToFirst() && !cs.isNull(0)) {
                        String lpath = cs.getString(0);
                        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(activity.getApplicationContext());
                        File rootDir = StorageUtils.getRootDirectory(activity.getApplicationContext(), settings);
                        File existing = new File(rootDir, lpath);
                        if (existing.exists()) return new ResolvedBookFile(uuid, existing);
                    }
                } finally {
                    cs.close();
                }
            } finally {
                metaDb.close();
            }
        }
        File downloaded = doDownloadBookAndReturnFile(bookId, format);
        if (downloaded == null) return null;
        return new ResolvedBookFile(uuidForBook(bookId), downloaded);
    }

    private String uuidForBook(int bookId) {
        Cursor c = db.getBookFullById(bookId);
        try {
            if (!c.moveToFirst()) return null;
            return c.isNull(c.getColumnIndexOrThrow("uuid")) ? null : c.getString(c.getColumnIndexOrThrow("uuid"));
        } finally {
            c.close();
        }
    }

    private void showToast(int resId) {
        mainHandler.post(() -> android.widget.Toast.makeText(activity, resId, android.widget.Toast.LENGTH_SHORT).show());
    }

    @JavascriptInterface
    public void openBookDetail(int bookId, String pageBookIdsJson) {
        executor.execute(() -> doOpenBookDetail(bookId, pageBookIdsJson));
    }

    // --- downloadBook() ---

    private File doDownloadBookAndReturnFile(int bookId, String formatRequested) throws Exception {
        String code = formatRequested != null ? formatRequested.toUpperCase(Locale.US) : null;
        Cursor c = db.getBookFullById(bookId);
        try {
            if (!c.moveToFirst()) return null;

            JSONObject formats = new JSONObject(c.getString(c.getColumnIndexOrThrow("formats_json")));
            if (code == null || !formats.has(code)) return null;
            JSONObject fmtRef = formats.getJSONObject(code);
            String fileId = fmtRef.getString("file_id");

            SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(activity.getApplicationContext());
            File rootDir = StorageUtils.getRootDirectory(activity.getApplicationContext(), settings);
            if (!rootDir.exists() && !rootDir.mkdirs()) {
                throw new IOException("Cannot create book folder: " + rootDir);
            }

            String title = c.getString(c.getColumnIndexOrThrow("title"));
            String filename = fmtRef.optString("filename", "");
            if (filename.trim().isEmpty()) filename = fallbackFilename(title, code);
            File destFile = FileNameUtils.resolveNonConflictingFile(rootDir, filename);

            GDrivePublicFileClient fileClient = new GDrivePublicFileClient(new okhttp3.OkHttpClient());
            okhttp3.Response response = fileClient.openStream(fileId, null);
            try (InputStream in = response.body() != null ? response.body().byteStream() : null) {
                if (in == null) throw new IOException("Empty response body");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(destFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                }
            } finally {
                response.close();
            }

            // lpath phai la duong dan TUONG DOI so voi rootDir, giong het quy uoc
            // MetadataDatabaseHelper/CalibreService dang dung o moi noi khac.
            String lpath = destFile.getName();

            boolean hasCover = c.getInt(c.getColumnIndexOrThrow("has_cover")) == 1;
            String coverFileId = c.getString(c.getColumnIndexOrThrow("cover_file_id"));
            String coverPath = null;
            if (hasCover && coverFileId != null) {
                try {
                    okhttp3.Response coverResponse = fileClient.openStream(coverFileId, null);
                    byte[] coverBytes = coverResponse.body() != null ? coverResponse.body().bytes() : null;
                    coverResponse.close();
                    if (coverBytes != null && CoverManager.saveCover(rootDir, lpath, coverBytes)) {
                        coverPath = CoverManager.relativeCoverPathFor(lpath);
                    }
                } catch (Exception e) {
                    // Bia tai loi khong nen chan viec ghi sach vao thu vien -- chi bo qua bia.
                    Logger.e(e);
                }
            }

            JSONObject book = buildInsertJsonFor(c, lpath, coverPath);
            // insertJSON() tra ve byte[] thumbnail da ghi -- NHUNG gia tri null cung xay ra o
            // nhanh THANH CONG khi sach khong co bia (khong chi khi that bai), nen khong the dung
            // gia tri tra ve de biet insert co thanh cong hay khong. Thay vao do, tu kiem tra
            // truoc dung 3 dieu kien bat buoc ma insertJSON() doi hoi (uuid, author_sort/
            // authors[0], title) -- xem MetadataDatabaseHelper#insertJSON().
            boolean hasUuid = book.has("uuid") && !book.isNull("uuid") && !book.optString("uuid").isEmpty();
            boolean hasAuthor = (book.has("author_sort") && !book.optString("author_sort").isEmpty())
                    || (book.optJSONArray("authors") != null && book.getJSONArray("authors").length() > 0);
            boolean hasTitle = book.has("title") && !book.optString("title").isEmpty();
            if (!hasUuid || !hasAuthor || !hasTitle) {
                Logger.d("GDriveLibraryJsBridge: skipping insertJSON, missing required field(s) for bookId=" + bookId);
                return null;
            }
            MetadataDatabaseHelper metaDb = new MetadataDatabaseHelper(activity.getApplicationContext());
            try {
                metaDb.insertJSON(book);
            } finally {
                metaDb.close();
            }

            // Chi ghi lai metadata.calibre tu SQLite hien tai (KHONG quet lai dia bang
            // BookCrawler/BookMetadataSniffer) -- xem class Javadoc o tren.
            notifyCalibreServiceMetadataOnly();
            return destFile;
        } finally {
            c.close();
        }
    }

    /** Build đúng schema mà MetadataDatabaseHelper#insertJSON() kỳ vọng (uuid, author_sort/
     * authors[], title, tags[], thumbnail dạng mảng ["jpeg",0,0,base64], lpath) từ 1 row
     * GDriveLibraryDb -- xem MetadataDatabaseHelper#insertJSON() để đối chiếu field/kiểu dữ liệu
     * trước khi sửa.
     * BUG FIX: trước đây publisher/pubdate/series/series_index/rating/languages/identifiers/
     * ngày thêm vào thư viện bị bỏ qua hoàn toàn ở đây với lý do "TABLE_METADATA không có cột
     * tương ứng" -- điều đó đúng ở DATABASE_VERSION cũ, nhưng từ DATABASE_VERSION 13 (đợt nâng
     * cấp metadata Phase 1-4) bảng NÀY đã có đủ các cột đó và insertJSON() đã đọc đúng các key
     * JSON dưới đây (xem insertJSON() để đối chiếu) -- nên giờ đọc lại đầy đủ từ GDriveLibraryDb
     * và truyền vào, thay vì tiếp tục bỏ sót như comment cũ mô tả. */
    private JSONObject buildInsertJsonFor(Cursor c, String lpath, String coverPath) throws JSONException, IOException {
        JSONObject book = new JSONObject();
        String uuid = c.isNull(c.getColumnIndexOrThrow("uuid")) ? null : c.getString(c.getColumnIndexOrThrow("uuid"));
        book.put("uuid", uuid != null ? uuid : java.util.UUID.randomUUID().toString());
        book.put("lpath", lpath);
        String title = c.getString(c.getColumnIndexOrThrow("title"));
        book.put("title", title);
        book.put("title_sort", c.getString(c.getColumnIndexOrThrow("sort")));

        String authorSort = c.getString(c.getColumnIndexOrThrow("author_sort"));
        String authorsRaw = c.getString(c.getColumnIndexOrThrow("authors"));
        if (authorSort != null && !authorSort.isEmpty()) {
            book.put("author_sort", authorSort);
        }
        JSONArray authorsArr = new JSONArray();
        if (authorsRaw != null && !authorsRaw.isEmpty()) {
            // Plugin desktop noi nhieu tac gia bang " & " (library_metadata.py#_authors_string:
            // ' & '.join(metadata.authors or [])), va GDriveLibraryDb#getAuthorCount() trong
            // chinh file Android nay cung dang tach bang "&" (raw.split("\\s*&\\s*")) - truoc day
            // cho nay tach nham bang dau phay, khien nhieu tac gia dinh lien thanh 1 chuoi duy
            // nhat trong metadata luu o Device Library.
            for (String a : authorsRaw.split("\\s*&\\s*")) {
                String trimmed = a.trim();
                if (!trimmed.isEmpty()) authorsArr.put(trimmed);
            }
        }
        book.put("authors", authorsArr);

        JSONArray tags = new JSONArray(c.getString(c.getColumnIndexOrThrow("tags_json")));
        book.put("tags", tags);

        String comments = c.getString(c.getColumnIndexOrThrow("comments"));
        if (comments != null && !comments.isEmpty()) book.put("comments", comments);

        String publisher = c.getString(c.getColumnIndexOrThrow("publisher"));
        if (publisher != null && !publisher.isEmpty()) book.put("publisher", publisher);

        String pubdate = c.isNull(c.getColumnIndexOrThrow("pubdate")) ? null : c.getString(c.getColumnIndexOrThrow("pubdate"));
        if (pubdate != null && !pubdate.isEmpty()) book.put("pubdate", pubdate);

        String dateAdded = c.isNull(c.getColumnIndexOrThrow("timestamp")) ? null : c.getString(c.getColumnIndexOrThrow("timestamp"));
        if (dateAdded != null && !dateAdded.isEmpty()) book.put("timestamp", dateAdded);

        String series = c.isNull(c.getColumnIndexOrThrow("series")) ? null : c.getString(c.getColumnIndexOrThrow("series"));
        if (series != null && !series.isEmpty()) {
            book.put("series", series);
            if (!c.isNull(c.getColumnIndexOrThrow("series_index"))) {
                book.put("series_index", c.getDouble(c.getColumnIndexOrThrow("series_index")));
            }
        }

        // insertJSON() expects Calibre's raw 0-10 scale here (it divides by 2.0 itself to get
        // the app's normal 0-5 scale) -- GDriveLibraryDb stores the SAME raw 0-10 value read
        // straight from metadata_public.json (see GDriveBookEntryBuilder, which instead
        // normalizes it for direct display), so pass it through as-is, do NOT normalize here.
        if (!c.isNull(c.getColumnIndexOrThrow("rating"))) {
            book.put("rating", c.getInt(c.getColumnIndexOrThrow("rating")));
        }

        JSONArray languages = new JSONArray(c.getString(c.getColumnIndexOrThrow("languages_json")));
        if (languages.length() > 0) book.put("languages", languages);

        String isbn = c.getString(c.getColumnIndexOrThrow("isbn"));
        if (isbn != null && !isbn.isEmpty()) {
            JSONObject identifiers = new JSONObject();
            identifiers.put("isbn", isbn);
            book.put("identifiers", identifiers);
        }

        if (coverPath != null) {
            book.put("cover_path", coverPath);
        } else {
            // Khong co bia rieng tren dia -- neu GDriveLibraryDb co bia goc, thu tai lai day du
            // (kich thuoc goc) qua thumbnail base64 de insertJSON() luu vao cot "thumbnail" (BLOB)
            // -- day la cach duy nhat insertJSON() chap nhan anh bia khi khong co cover_path.
            boolean hasCover = c.getInt(c.getColumnIndexOrThrow("has_cover")) == 1;
            String coverFileId = c.getString(c.getColumnIndexOrThrow("cover_file_id"));
            if (hasCover && coverFileId != null) {
                try {
                    GDrivePublicFileClient fileClient = new GDrivePublicFileClient(new okhttp3.OkHttpClient());
                    okhttp3.Response coverResponse = fileClient.openStream(coverFileId, null);
                    byte[] coverBytes = coverResponse.body() != null ? coverResponse.body().bytes() : null;
                    coverResponse.close();
                    if (coverBytes != null) {
                        JSONArray thumb = new JSONArray();
                        thumb.put("jpeg");
                        thumb.put(0);
                        thumb.put(0);
                        thumb.put(android.util.Base64.encodeToString(coverBytes, android.util.Base64.NO_WRAP));
                        book.put("thumbnail", thumb);
                    }
                } catch (Exception e) {
                    Logger.e(e);
                }
            }
        }
        return book;
    }

    private void notifyCalibreServiceMetadataOnly() {
        // Bind tam thoi vao CalibreService chi de goi 1 ham "ghi lai metadata.calibre tu SQLite"
        // -- KHONG phai full rescan (xem class Javadoc). Neu app chua co the goi duoc (service
        // chua chay), bo qua -- metadata.calibre se tu dong duoc ghi dung o lan scan/sync tiep
        // theo, khong anh huong toi viec sach da nam trong SQLite/Device Library UI.
        try {
            android.content.Intent serviceIntent = new android.content.Intent(activity, CalibreService.class);
            activity.getApplicationContext().bindService(serviceIntent, new android.content.ServiceConnection() {
                @Override
                public void onServiceConnected(android.content.ComponentName name, android.os.IBinder binder) {
                    try {
                        CalibreService service = ((CalibreService.CalibreBinder) binder).getService();
                        if (service != null) service.rescanBookFolderMetadataFileOnly();
                    } finally {
                        activity.getApplicationContext().unbindService(this);
                    }
                }

                @Override
                public void onServiceDisconnected(android.content.ComponentName name) { }
            }, android.content.Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            Logger.e(e);
        }
    }

    private void notifyDownloadResult(int bookId, boolean success, String message) {
        mainHandler.post(() -> {
            String js = success
                    ? "window.onNativeDownloadResult && window.onNativeDownloadResult(" + bookId + ", true, null)"
                    : "window.onNativeDownloadResult && window.onNativeDownloadResult(" + bookId + ", false, "
                            + org.json.JSONObject.quote(message != null ? message : "") + ")";
            webView.evaluateJavascript(js, null);
            android.widget.Toast.makeText(activity,
                    success ? R.string.cloud_download_success_generic : R.string.cloud_download_failed_generic,
                    android.widget.Toast.LENGTH_SHORT).show();
        });
    }

    // --- openBookDetail() ---

    /** Dựng 1 OPDSEntry "tổng hợp" (qua GDriveBookEntryBuilder, dùng chung với
     * BookDetailActivity#resolveGdriveAdjacentEntry khi vuốt trái/phải) rồi mở
     * BookDetailActivity y hệt cách BookDetailActivity đang được mở từ tab OPDS/Content Server
     * (EXTRA_ENTRY/EXTRA_PAGE_URL/EXTRA_AUTH_HEADER) -- xem Phần 3 "Dialog xem trước sách" của
     * prompt nâng cấp. href của mỗi format trỏ tới route proxy cục bộ
     * "/{bookId}/download/{format}" (đã hỗ trợ Range), cover trỏ "/opds/cover/{bookId}" --
     * pageUrl truyền vào là baseUrl của chính server này, EXTRA_AUTH_HEADER = null vì server
     * local không yêu cầu auth.
     * @param pageBookIdsJson mảng JSON các bookId đang hiển thị TRÊN CÙNG 1 trang trong WebView
     *        (app.js#openBookDetailNative -- xem javadoc BookDetailActivity#EXTRA_BOOK_LIST_GDRIVE_IDS),
     *        theo đúng thứ tự sort/filter hiện tại, dùng để vuốt trái/phải sang cuốn kế tiếp/
     *        trước đó mà không cần quay lại WebView. null/rỗng nếu gọi từ 1 chỗ không có ngữ
     *        cảnh trang (khi đó vuốt sẽ không làm gì cả, giống DeviceBookDetailActivity). */
    private void doOpenBookDetail(int bookId, String pageBookIdsJson) {
        try {
            OPDSEntry entry = GDriveBookEntryBuilder.build(activity.getApplicationContext(), db, port, bookId);
            if (entry == null) return;
            String baseUrl = "http://127.0.0.1:" + port + "/";
            int[] pageIds = parsePageBookIds(pageBookIdsJson);
            int index = indexOfBookId(pageIds, bookId);

            mainHandler.post(() -> activity.startActivity(new Intent(activity, BookDetailActivity.class)
                    .putExtra(BookDetailActivity.EXTRA_ENTRY, entry)
                    .putExtra(BookDetailActivity.EXTRA_PAGE_URL, baseUrl)
                    .putExtra(BookDetailActivity.EXTRA_AUTH_HEADER, (String) null)
                    .putExtra(BookDetailActivity.EXTRA_BOOK_LIST_GDRIVE_IDS, pageIds)
                    .putExtra(BookDetailActivity.EXTRA_GDRIVE_LIBRARY_ID, libraryId)
                    .putExtra(BookDetailActivity.EXTRA_GDRIVE_PORT, port)
                    .putExtra(BookDetailActivity.EXTRA_BOOK_LIST_INDEX, index)));
        } catch (JSONException e) {
            Logger.e(e);
        }
    }

    /** Parse mảng JSON bookId (vd "[12,7,31]") gửi từ app.js#openBookDetailNative thành int[].
     * Trả về mảng rỗng (không phải null) khi input rỗng/lỗi, để BookDetailActivity chỉ cần kiểm
     * tra length == 0 thay vì null-check riêng. */
    private static int[] parsePageBookIds(String json) {
        if (json == null || json.isEmpty()) return new int[0];
        try {
            JSONArray arr = new JSONArray(json);
            int[] ids = new int[arr.length()];
            for (int i = 0; i < ids.length; i++) ids[i] = arr.optInt(i, -1);
            return ids;
        } catch (JSONException e) {
            Logger.e(e);
            return new int[0];
        }
    }

    private static int indexOfBookId(int[] ids, int bookId) {
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] == bookId) return i;
        }
        return -1;
    }


    private String fallbackFilename(String title, String code) {
        String base = (title == null || title.trim().isEmpty()) ? "book" : title.trim();
        base = base.replaceAll("[\\x00-\\x1f\"*/:<>?\\\\|]", "_");
        String ext = code.toLowerCase(Locale.US);
        return base + "." + ext;
    }

    void shutdown() {
        executor.shutdown();
    }
}
