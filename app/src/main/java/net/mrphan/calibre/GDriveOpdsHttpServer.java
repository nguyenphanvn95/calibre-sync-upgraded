package net.mrphan.calibre;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Locale;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.OkHttpClient;
import okhttp3.Response;

/** Server HTTP nội bộ chỉ chạy trên 127.0.0.1, phục vụ 3 việc cho GDriveLibraryWebViewActivity:
 * (1) route "/api/library.json" trả lại đúng nội dung metadata_public.json đã cache trong
 * GDriveLibraryDb (SQLite) dưới dạng JSON cho app.js fetch same-origin; (2) 2 route proxy file
 * "/opds/cover/{bookId}" và "/{bookId}/download[/{format}]" đọc thẳng byte từ Drive qua
 * GDrivePublicFileClient (không buffer cả file vào RAM, có hỗ trợ Range để resume/seek); (3) mọi
 * path còn lại trả về asset tĩnh (index.html/app.js/CSS) từ assets/gdrive_library/ -- 1 bộ code
 * DUY NHẤT dùng chung cho mọi thư viện. KHÔNG còn giả lập OPDS/Atom feed như bản trước (xem
 * lịch sử git nếu cần đối chiếu) -- WebView giờ render trực tiếp UI "Thư viện mini" thay vì
 * OPDSBrowserFragment.
 *
 * Vòng đời: start() cho 1 driveFileId cụ thể → (tải lại metadata_public.json nếu cần) → bind vào
 * cổng ngẫu nhiên còn trống → trả về port. stop() đóng server + DB. Chỉ nên chạy 1 thư viện tại 1
 * thời điểm (đơn giản hóa); nếu cần duyệt nhiều thư viện cùng lúc trong tương lai, tách instance
 * theo libraryId thay vì static singleton như dưới đây. */
public class GDriveOpdsHttpServer {

    private static GDriveOpdsHttpServer instance;

    public static synchronized GDriveOpdsHttpServer getInstance() {
        if (instance == null) instance = new GDriveOpdsHttpServer();
        return instance;
    }

    private final OkHttpClient httpClient = NetworkModule.BASE;
    private NanoHTTPD server;
    private GDriveLibraryDb db;
    private GDrivePublicFileClient fileClient;
    private GDriveCoverDiskCache coverDiskCache;
    private android.content.res.AssetManager assetManager;
    private android.content.Context appContext; // dat trong start(), dung lai o handleLibraryJson()
    private String currentLibraryId;
    private String currentDriveFileId;
    private int port = -1;

    /** Cho bridge (GDriveLibraryJsBridge)/Activity dùng lại đúng 1 kết nối GDriveLibraryDb đang
     * mở của server thay vì tự mở thêm 1 GDriveLibraryDb khác cho cùng file .db -- xem điểm cần
     * Codex tự kiểm chứng trong prompt nâng cấp. Null nếu server chưa start(). */
    public GDriveLibraryDb getDb() { return db; }

    public boolean isRunning() { return server != null; }
    public int getPort() { return port; }
    public String getBaseUrl() { return "http://127.0.0.1:" + port + "/"; }

    /** Khởi động (hoặc tái sử dụng nếu đã chạy đúng thư viện này) server cho 1 GDriveOpdsLibrary
     * đã lưu. Gọi từ background thread — có network I/O (tải metadata_public.json nếu cần) +
     * SQLite. */
    public synchronized int start(Context context, GDriveOpdsLibrary library, boolean forceRefresh)
            throws GDriveFileException, JSONException, IOException {

        if (isRunning() && library.getId().equals(currentLibraryId) && !forceRefresh) {
            return port;
        }

        fileClient = new GDrivePublicFileClient(httpClient);
        db = new GDriveLibraryDb(context, library.getId());
        if (coverDiskCache == null) coverDiskCache = new GDriveCoverDiskCache(context);
        assetManager = context.getApplicationContext().getAssets();
        this.appContext = context.getApplicationContext();

        boolean needsResync = forceRefresh || db.needsResync(library.getDriveFileId());
        if (needsResync) {
            String raw = fileClient.downloadJson(library.getDriveFileId());
            db.rebuildFromJson(raw, library.getDriveFileId());
        }

        // KHONG con chan (block) o day cho toi khi tai xong toan bo anh bia nhu truoc -- server
        // tra ve ngay sau khi bind xong. OPDSBrowserFragment/OPDSLoader gio se tu hien tung
        // sach ngay khi anh bia cua sach do tai xong (xem OPDSLoader#onBookAdded), thay vi doi
        // ca man hinh "Dang tai bia sach x/y" truoc khi thay bat ky sach nao. Van khoi dong 1
        // lan warm-up NEN (khong chan UI) de trang sau ("More") va lan mo lai thu vien nay
        // duoc nhanh hon nho GDriveCoverDiskCache da co san.
        warmUpRemainingCoversInBackground();

        currentLibraryId = library.getId();
        currentDriveFileId = library.getDriveFileId();

        stopServerOnly();
        port = findFreePort();
        server = new NanoHTTPD("127.0.0.1", port) {
            @Override
            public Response serve(IHTTPSession session) {
                return route(session);
            }
        };
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

        library.setCachedBookCount(db.getBookCount());
        library.setLastSyncedAtMillis(System.currentTimeMillis());
        GDriveOpdsLibraryStore.save(context, library);

        return port;
    }

    /** Am tham tai truoc (khong chan UI, khong callback tien trinh) toan bo anh bia con thieu
     * cua thu vien vao GDriveCoverDiskCache, chay tren 1 thread rieng ngay sau khi server da
     * bind xong. Muc dich CHI la lam am cache cho lan sau (mo lai thu vien, hoac bam "More" qua
     * trang tiep theo) -- KHONG con la dieu kien de mo duoc man hinh duyet sach nhu truoc, vi
     * OPDSLoader#onBookAdded da lo viec hien tung sach ngay khi anh bia cua no san sang. Neu
     * server bi stop() truoc khi thread nay xong, cac request Drive con lai don gian la lang
     * phi (khong crash gi) -- fileClient/coverDiskCache van con song vi la field cua instance
     * nay (singleton), khong bi GC trong luc thread dang chay. */
    private void warmUpRemainingCoversInBackground() {
        java.util.List<String> coverFileIds = db.getAllCoverFileIds();
        if (coverFileIds.isEmpty()) return;
        final GDrivePublicFileClient clientRef = fileClient;
        final GDriveCoverDiskCache cacheRef = coverDiskCache;
        new Thread(() -> {
            try {
                new GDriveCoverPrefetcher(clientRef, cacheRef).run(coverFileIds, null);
            } catch (Exception e) {
                Logger.e(e);
            }
        }, "GDriveCoverWarmup").start();
    }

    public synchronized void refresh(Context context, GDriveOpdsLibrary library)
            throws GDriveFileException, JSONException, IOException {
        start(context, library, true);
    }

    public synchronized void stop() {
        stopServerOnly();
        if (db != null) db.close();
        db = null;
        currentLibraryId = null;
        currentDriveFileId = null;
    }

    private void stopServerOnly() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    // --- Routing ---

    private NanoHTTPD.Response route(NanoHTTPD.IHTTPSession session) {
        String uri = session.getUri();

        try {
            // Route so lieu dung chung cho moi thu vien (Phan 2.3): tra lai dung "metadata_public.json
            // da cache" tu GDriveLibraryDb, khong con la gia lap OPDS feed nua.
            if (uri.equals("/api/library.json")) {
                return handleLibraryJson();
            }
            if (uri.startsWith("/opds/cover/")) {
                return handleCover(uri.substring("/opds/cover/".length()));
            }
            // Giu lai 2 dang path cu (/opds/download/{bookId}/{format}, /{bookId}/download) de
            // tuong thich nguoc, cong them dang moi /{bookId}/download/{format} ma app.js (asset
            // moi) dung -- ca 3 deu tro ve dung handleDownload() nhu nhau.
            if (uri.startsWith("/opds/download/")) {
                String rest = uri.substring("/opds/download/".length());
                String[] parts = rest.split("/", 2);
                if (parts.length == 2) return handleDownload(parts[0], parts[1], session);
            }
            if (uri.matches("^/\\d+/download$")) {
                String bookId = uri.substring(1, uri.length() - "/download".length());
                return handleDownload(bookId, null, session);
            }
            if (uri.matches("^/\\d+/download/[A-Za-z0-9]+$")) {
                String rest = uri.substring(1); // "<bookId>/download/<format>"
                String[] parts = rest.split("/download/", 2);
                if (parts.length == 2) return handleDownload(parts[0], parts[1], session);
            }
            // Moi path con lai (bao gom "/") tra ve asset tinh cua trang render moi (index.html/
            // app.js/CSS) -- xem handleStaticAsset().
            return handleStaticAsset(uri);
        } catch (Exception e) {
            Logger.e(e);
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    "text/plain", "Internal error: " + e.getMessage());
        }
    }

    private NanoHTTPD.Response handleLibraryJson() {
        try {
            java.util.Set<String> deviceUuids = java.util.Collections.emptySet();
            if (appContext != null) {
                MetadataDatabaseHelper metaDb = new MetadataDatabaseHelper(appContext);
                try {
                    deviceUuids = metaDb.getAllUuidsWithLpath();
                } finally {
                    metaDb.close();
                }
            }
            JSONObject json = db.buildLibraryJson(deviceUuids);
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK,
                    "application/json; charset=utf-8", json.toString());
        } catch (JSONException e) {
            Logger.e(e);
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    "text/plain", "Bad cached library data: " + e.getMessage());
        }
    }

    /** Serve index.html/app.js/CSS từ assets/gdrive_library/ -- 1 bộ code DUY NHẤT dùng chung
     * cho mọi thư viện (không sinh riêng 1 bản HTML cho mỗi thư viện). "/" và mọi path không
     * khớp route nào ở trên đều rơi vào đây; path rỗng hoặc "/" trả về index.html. */
    private NanoHTTPD.Response handleStaticAsset(String uri) {
        String assetPath = (uri == null || uri.isEmpty() || uri.equals("/")) ? "index.html" : uri.substring(1);
        // Chan path traversal ("../") don gian -- khong co ly do hop le nao WebView cua chinh app
        // can thoat khoi thu muc asset nay.
        if (assetPath.contains("..")) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN, "text/plain", "Forbidden");
        }
        try (java.io.InputStream in = assetManager.open("gdrive_library/" + assetPath)) {
            byte[] bytes = readAll(in);
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK,
                    mimeForAsset(assetPath), new java.io.ByteArrayInputStream(bytes), bytes.length);
        } catch (IOException e) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Asset not found: " + assetPath);
        }
    }

    private byte[] readAll(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    private String mimeForAsset(String path) {
        String lower = path.toLowerCase(Locale.US);
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    private NanoHTTPD.Response handleCover(String bookIdStr) {
        int bookId = parseIntOr(bookIdStr, -1);
        if (bookId < 0) return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Invalid book id");

        android.database.Cursor c = db.getBookById(bookId);
        try {
            if (!c.moveToFirst()) return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Book not found");
            String coverFileId = c.getString(c.getColumnIndexOrThrow("cover_file_id"));
            if (coverFileId == null) return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "No cover");

            // Cache-hit: tra thang tu dia, khong co network, tuc thoi.
            byte[] cached = coverDiskCache.get(coverFileId);
            if (cached != null) {
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "image/jpeg",
                        new java.io.ByteArrayInputStream(cached), cached.length);
            }

            // Cache-miss (hiem gap neu warmUpRemainingCoversInBackground() da kip chay xong,
            // hoac neu day dung la sach dau tien duoc OPDSLoader hoi truoc khi warm-up kip toi
            // luot no): tai truc tiep tu Drive nhu code cu, NHUNG ghi luon vao cache truoc khi
            // tra response de lan sau (ke ca cuon lai dung sach nay trong cung phien) la
            // cache-hit.
            return proxyDriveFileAndCache(coverFileId, "image/jpeg");
        } finally {
            c.close();
        }
    }

    private NanoHTTPD.Response handleDownload(String bookIdStr, String formatOrNull, NanoHTTPD.IHTTPSession session) {
        int bookId = parseIntOr(bookIdStr, -1);
        if (bookId < 0) return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Invalid book id");

        android.database.Cursor c = db.getBookById(bookId);
        try {
            if (!c.moveToFirst()) return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Book not found");
            try {
                JSONObject formats = new JSONObject(c.getString(c.getColumnIndexOrThrow("formats_json")));
                String code = formatOrNull != null ? formatOrNull.toUpperCase(Locale.US) : pickPrimaryFormat(formats);
                if (code == null || !formats.has(code)) {
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Format not available");
                }
                JSONObject ref = formats.getJSONObject(code);
                String fileId = ref.getString("file_id");
                // BUG FIX: metadata_public.json không phải lúc nào cũng có sẵn "filename" cho mỗi
                // format (tùy phiên bản tool xuất thư viện) -- trước đây nếu thiếu, filename ở đây
                // là null, proxyDriveFile() sẽ KHÔNG gửi header Content-Disposition, và
                // AsyncDownloader.parseContentDispositionFilename(null) trả về null -> tên file cục
                // bộ mặc định là "tmp" (KHÔNG có phần mở rộng). Hệ quả: OpenBookActivity không nhận
                // diện được epub/pdf/cbz từ tên "tmp" nên không mở được sách vừa tải (và nhiều sách
                // thiếu filename khác nhau sẽ đè lên cùng 1 file "tmp"). Luôn đảm bảo có filename
                // hợp lệ kèm đúng phần mở rộng của format để nút Tải/Đọc luôn hoạt động, kể cả khi
                // JSON nguồn thiếu trường này.
                String filename = ref.optString("filename", "");
                if (filename.trim().isEmpty()) {
                    String title = c.getString(c.getColumnIndexOrThrow("title"));
                    filename = fallbackFilename(title, code);
                }
                String range = session.getHeaders().get("range");
                return proxyDriveFile(fileId, mimeForFormat(code), filename, range);
            } catch (JSONException e) {
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", "Bad formats data");
            }
        } finally {
            c.close();
        }
    }

    private NanoHTTPD.Response proxyDriveFile(String fileId, String defaultContentType, String filename, String range) {
        try {
            Response upstream = fileClient.openStream(fileId, range);
            String contentType = upstream.header("content-type", defaultContentType);
            java.io.InputStream body = upstream.body() != null ? upstream.body().byteStream() : null;
            long length = upstream.body() != null ? upstream.body().contentLength() : -1;

            // Drive tra 206 + Content-Range khi request co header Range hop le (xem
            // GDrivePublicFileClient#openStream) -- forward dung status/header do de
            // BookDetailActivity/OpenBookActivity (vd audiobook lon can seek) hoat dong dung,
            // thay vi luon bao 200 OK nhu truoc du than dang la 1 phan file.
            boolean partial = range != null && upstream.code() == 206;
            NanoHTTPD.Response.IStatus status = partial
                    ? NanoHTTPD.Response.Status.PARTIAL_CONTENT : NanoHTTPD.Response.Status.OK;

            NanoHTTPD.Response response;
            if (length >= 0) {
                response = NanoHTTPD.newFixedLengthResponse(status, contentType, body, length);
            } else {
                response = NanoHTTPD.newChunkedResponse(status, contentType, body);
            }
            response.addHeader("Accept-Ranges", "bytes");
            if (partial) {
                String contentRange = upstream.header("content-range");
                if (contentRange != null) response.addHeader("Content-Range", contentRange);
            }
            if (filename != null && !filename.isEmpty()) {
                response.addHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            }
            return response;
        } catch (GDriveFileException e) {
            Logger.e(e);
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", e.getMessage());
        }
    }

    /** Bien the cua proxyDriveFile() CHI dung cho cover -- doc het bytes vao RAM (anh bia nho,
     * khong can streaming nhu ebook) de vua tra response vua ghi vao GDriveCoverDiskCache cung
     * luc. KHONG dung ham nay cho handleDownload() (ebook co the rat lon, phai giu streaming nhu
     * cu). */
    private NanoHTTPD.Response proxyDriveFileAndCache(String fileId, String defaultContentType) {
        try {
            Response upstream = fileClient.openStream(fileId, null);
            byte[] bytes = upstream.body() != null ? upstream.body().bytes() : null;
            String contentType = upstream.header("content-type", defaultContentType);
            upstream.close();
            if (bytes == null || bytes.length == 0) {
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Empty cover");
            }
            coverDiskCache.put(fileId, bytes);
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, contentType,
                    new java.io.ByteArrayInputStream(bytes), bytes.length);
        } catch (GDriveFileException e) {
            Logger.e(e);
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", e.getMessage());
        } catch (IOException e) {
            Logger.e(e);
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", e.getMessage());
        }
    }

    private static int parseIntOr(String s, int fallback) {
        try {
            return s != null ? Integer.parseInt(s) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static final String[] EBOOK_PRIORITY = {"EPUB", "AZW3", "MOBI", "PDF", "FB2", "TXT"};

    private String pickPrimaryFormat(JSONObject formats) {
        for (String code : EBOOK_PRIORITY) {
            if (formats.has(code)) return code;
        }
        java.util.Iterator<String> keys = formats.keys();
        return keys.hasNext() ? keys.next() : null;
    }

    private static final Map<String, String> MIME_TYPES = Map.ofEntries(
            Map.entry("EPUB", "application/epub+zip"),
            Map.entry("PDF", "application/pdf"),
            Map.entry("MOBI", "application/x-mobipocket-ebook"),
            Map.entry("AZW3", "application/vnd.amazon.mobi8-ebook"),
            Map.entry("AZW", "application/vnd.amazon.ebook"),
            Map.entry("FB2", "application/fb2"),
            Map.entry("CBZ", "application/vnd.comicbook+zip"),
            Map.entry("CBR", "application/vnd.comicbook-rar"),
            Map.entry("TXT", "text/plain"),
            Map.entry("RTF", "application/rtf"),
            Map.entry("DOC", "application/msword"),
            Map.entry("HTML", "text/html"),
            // Audiobook formats -- see GDriveLibraryJsBridge#mimeForFormatPublic for why these
            // matter beyond just the Content-Type header (format-chooser dialog label fallback).
            Map.entry("MP3", "audio/mpeg"),
            Map.entry("M4B", "audio/x-m4b"),
            Map.entry("M4A", "audio/mp4"),
            Map.entry("OGG", "audio/ogg"),
            Map.entry("FLAC", "audio/flac"),
            Map.entry("WAV", "audio/wav")
    );

    private String mimeForFormat(String code) {
        return MIME_TYPES.getOrDefault(code, "application/octet-stream");
    }

    private static final Map<String, String> FILE_EXTENSIONS = Map.of(
            "EPUB", "epub",
            "PDF", "pdf",
            "MOBI", "mobi",
            "AZW3", "azw3",
            "FB2", "fb2",
            "CBZ", "cbz",
            "CBR", "cbr",
            "TXT", "txt"
    );

    /** Tên file dự phòng khi metadata_public.json không có sẵn "filename" cho format này --
     * dùng tiêu đề sách (đã lược bỏ ký tự không hợp lệ trên hệ thống file, giống
     * AsyncDownloader#sanitizeFilename) + đúng phần mở rộng của format, để tên file cục bộ luôn
     * có phần mở rộng hợp lệ (thay vì rơi vào "tmp" không có phần mở rộng). */
    private String fallbackFilename(String title, String code) {
        String base = (title == null || title.trim().isEmpty()) ? "book" : title.trim();
        base = base.replaceAll("[\\x00-\\x1f\"*/:<>?\\\\|]", "_");
        String ext = FILE_EXTENSIONS.getOrDefault(code, code.toLowerCase(Locale.US));
        return base + "." + ext;
    }

}
