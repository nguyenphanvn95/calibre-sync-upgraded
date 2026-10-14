package net.mrphan.calibre;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * CloudProvider dua tren Dropbox API v2, goi truc tiep bang OkHttp - cung tinh than voi
 * BoxCloudProvider/OneDriveCloudProvider. Khac voi Box/OneDrive/Google Drive (dung id do server
 * cap), Dropbox dung DUONG DAN (path) lam dinh danh chinh cho moi thao tac (list_folder,
 * download, upload, create_folder, get_metadata), nen refId cua CloudFile o day chinh la
 * path_lower cua Dropbox - giong tinh than "full path la refId" cua WebDavCloudProvider hon la
 * cua BoxCloudProvider. Duong dan goc (root) la CHUOI RONG "" (KHONG phai "/"), day la quy uoc
 * rieng cua Dropbox API can nho khi ghep path con: "" + "/ten" = "/ten" la dung, con "/ten con"
 * lay tu 1 path khac rong thi ghep binh thuong "path/ten".
 * <p>
 * Dropbox tach request lam 2 loai voi 2 domain khac nhau: cac RPC don gian (list_folder,
 * create_folder_v2, get_metadata, get_current_account) goi toi api.dropboxapi.com voi tham so
 * trong JSON body; cac request lien quan noi dung file (download/upload) goi toi
 * content.dropboxapi.com voi tham so trong header "Dropbox-API-Arg" (JSON, nhung PHAI ma hoa
 * ky tu ngoai ASCII thanh \\uXXXX vi day la mot HTTP header - xem asciiSafeHeaderValue()).
 */
public class DropboxCloudProvider implements CloudProvider {

    private static final String API_BASE = "https://api.dropboxapi.com/2";
    private static final String CONTENT_BASE = "https://content.dropboxapi.com/2";
    private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");
    private static final MediaType JSON_TYPE = MediaType.parse("application/json");

    private final Context appContext;
    private final OkHttpClient http;

    DropboxCloudProvider(Context context) {
        if (!CloudTokenManager.getInstance(context, CloudRemoteType.DROPBOX).isSignedIn()) {
            throw new IllegalStateException("Not signed in to Dropbox (or access was revoked)");
        }
        this.appContext = context.getApplicationContext();
        this.http = NetworkModule.BASE.newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    private String bearerToken() throws IOException {
        return CloudTokenManager.getInstance(appContext, CloudRemoteType.DROPBOX)
                .getValidAccessTokenBlocking(appContext);
    }

    private Request.Builder authedRequest(String url) throws IOException {
        return new Request.Builder().url(url).header("Authorization", "Bearer " + bearerToken());
    }

    /** Goi mot RPC don gian cua Dropbox (path api.dropboxapi.com), tham so truyen qua JSON body -
     * dung cho list_folder/list_folder/continue, create_folder_v2, get_metadata,
     * get_current_account. */
    private JSONObject executeRpc(String path, JSONObject params) throws IOException {
        RequestBody body = RequestBody.create(
                params != null ? params.toString() : "null", JSON_TYPE);
        Request request = authedRequest(API_BASE + path).post(body).build();
        try (Response response = http.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw rateLimitAwareException(response, responseBody);
            }
            try {
                return responseBody.isEmpty() ? new JSONObject() : new JSONObject(responseBody);
            } catch (JSONException e) {
                throw new IOException("Unexpected Dropbox API response: " + responseBody, e);
            }
        }
    }

    /** Bao loi rieng cho RPC cua Dropbox, giu lai HTTP status va body goc (JSON error_summary) de
     * cac ham goi (uploadFile/createFolder) phan biet duoc loi "path/conflict" (coi nhu da co san,
     * xem javadoc CloudProvider#createFolder) voi cac loi that su khac. Ke thua CloudSyncException
     * (Phase 8 cua prompt nang cap) de BackoffRetry co the phan biet loi tam thoi (RATE_LIMIT/
     * NETWORK, nen retry) voi loi vinh vien (AUTH_FAILED/NOT_FOUND, khong nen retry vo han). */
    private static final class DropboxApiException extends CloudSyncException {
        final int httpCode;
        final String body;

        DropboxApiException(int httpCode, String body) {
            this(httpCode, body, null);
        }

        DropboxApiException(int httpCode, String body, Integer retryAfterSeconds) {
            super("Dropbox API error " + httpCode + ": " + body, mapCode(httpCode), httpCode, retryAfterSeconds);
            this.httpCode = httpCode;
            this.body = body;
        }

        boolean isConflict() {
            return httpCode == 409 && body.contains("conflict");
        }

        private static Code mapCode(int httpCode) {
            if (httpCode == 401) return Code.AUTH_FAILED;
            if (httpCode == 404) return Code.NOT_FOUND;
            if (httpCode == 409) return Code.CONFLICT;
            if (httpCode == 429 || httpCode >= 500) return Code.RATE_LIMIT;
            return Code.UNKNOWN;
        }
    }

    @Override
    public CloudFile getRoot() {
        return new CloudFile("", "Dropbox", true, 0, 0, false);
    }

    @Override
    public List<CloudFile> listFiles(String refId) throws IOException {
        List<CloudFile> result = new ArrayList<>();
        try {
            JSONObject params = new JSONObject().put("path", refId).put("recursive", false);
            JSONObject page = executeRpc("/files/list_folder", params);
            appendEntries(page, result);
            while (page.optBoolean("has_more", false)) {
                JSONObject cursorParams = new JSONObject().put("cursor", page.getString("cursor"));
                page = executeRpc("/files/list_folder/continue", cursorParams);
                appendEntries(page, result);
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return result;
    }

    private void appendEntries(JSONObject page, List<CloudFile> out) throws IOException {
        org.json.JSONArray entries = page.optJSONArray("entries");
        if (entries == null) return;
        try {
            for (int i = 0; i < entries.length(); i++) {
                JSONObject item = entries.getJSONObject(i);
                boolean isFolder = "folder".equals(item.optString(".tag"));
                out.add(new CloudFile(
                        item.getString("path_lower"),
                        item.getString("name"),
                        isFolder,
                        item.optLong("size", 0),
                        parseIsoTimeToMillis(item.optString("server_modified", null)),
                        false));
            }
        } catch (JSONException e) {
            throw new IOException("Unexpected Dropbox API response item: " + e.getMessage(), e);
        }
    }

    @Override
    public void downloadFile(CloudFile file, File localDestFile) throws IOException {
        String argHeader;
        try {
            argHeader = asciiSafeHeaderValue(new JSONObject().put("path", file.getRefId()));
        } catch (JSONException e) {
            throw new IOException(e);
        }
        Request request = authedRequest(CONTENT_BASE + "/files/download")
                .header("Dropbox-API-Arg", argHeader)
                .post(RequestBody.create(new byte[0], null))
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                String body = response.body() != null ? response.body().string() : "";
                throw new IOException("Dropbox download failed, HTTP " + response.code() + ": " + body);
            }
            // Ghi THANG tu network stream ra dia thay vi nap het vao RAM qua body.bytes() (ban
            // cu) — tranh OOM voi file lon nhu audiobook M4B hoac PDF quet (van de #8, muc 2 cua
            // prompt nang cap).
            try (ResponseBody body = response.body();
                 java.io.InputStream in = body.byteStream();
                 FileOutputStream out = new FileOutputStream(localDestFile)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
        }
    }

    @Override
    public String uploadFile(File localFile, String destFolderRefId, String remoteFileName) throws IOException {
        // remoteFileName co the khac localFile.getName() (xem javadoc CloudProvider#uploadFile
        // 3-tham-so).
        String destPath = joinPath(destFolderRefId, remoteFileName);
        try {
            return uploadContent(destPath, localFile, "add");
        } catch (DropboxApiException e) {
            if (e.isConflict()) {
                // Trung ten voi file da co trong thu muc dich (chua duoc manifest theo doi) --
                // lay lai metadata cua duong dan dich va coi nhu da co san, cung tinh than
                // idempotent voi cach BoxCloudProvider#uploadFile xu ly HTTP 409 cua Box.
                return metadataPathLowerOrThrow(destPath, e);
            }
            throw e;
        }
    }

    @Override
    public String createFolder(String parentRefId, String name) throws IOException {
        String path = joinPath(parentRefId, name);
        try {
            JSONObject params = new JSONObject().put("path", path).put("autorename", false);
            JSONObject created = executeRpc("/files/create_folder_v2", params);
            return created.getJSONObject("metadata").getString("path_lower");
        } catch (DropboxApiException e) {
            if (e.isConflict()) {
                // Thu muc trung ten da ton tai - phai tra ve path_lower cua thu muc do de idempotent,
                // vi RemoteTreeResolver goi lai createFolder moi lan sync de "dam bao thu
                // muc ton tai", khong phai chi lan dau (giong BoxCloudProvider#createFolder).
                return metadataPathLowerOrThrow(path, e);
            }
            throw e;
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** TODO: file lon (>150MB, gioi han cua "simple upload" ma ham nay dung) hien chua duoc ho
     * tro - Dropbox khuyen nghi "upload session" (POST .../upload_session/start roi
     * .../upload_session/append_v2 tung chunk, ket thuc bang .../upload_session/finish) cho file
     * lon hon. Chua cai trong ban nay, cung ly do voi TODO tren OneDriveCloudProvider/
     * BoxCloudProvider: da so sach epub/pdf ca nhan duoi 150MB nen van dung duoc. */
    @Override
    public String updateFileContent(String existingRefId, File localFile) throws IOException {
        try {
            return uploadContent(existingRefId, localFile, "overwrite");
        } catch (DropboxApiException e) {
            if (e.httpCode == 409) {
                // File bi xoa tay tren Dropbox giua 2 lan sync - tao lai dung path cu (existingRefId
                // da la path_lower day du, vd "/books/<uuid>/book.epub") thay vi chi ten file local,
                // de giu dung cau truc thu muc co dinh cua RemoteTreeResolver thay vi roi xuong root.
                int slash = existingRefId.lastIndexOf('/');
                String parent = slash <= 0 ? "" : existingRefId.substring(0, slash);
                String name = slash < 0 ? existingRefId : existingRefId.substring(slash + 1);
                return uploadFile(localFile, parent, name);
            }
            throw e;
        }
    }

    private static final long CHUNKED_UPLOAD_THRESHOLD = 150L * 1024 * 1024;
    private static final int CHUNK_SIZE = 8 * 1024 * 1024; // 8MB/chunk, theo khuyen nghi Dropbox

    /** Goi chung cho ca uploadFile (mode "add") lan updateFileContent (mode "overwrite") - hai
     * ham nay giong het nhau ngoai tru gia tri "mode" va cach xu ly loi 409 khac nhau o cap goi.
     * File > 150MB (gioi han "simple upload" cua Dropbox) di qua duong upload session chunked
     * (xem uploadContentChunked) thay vi that bai thang (van de #9, muc 2 cua prompt nang cap). */
    private String uploadContent(String destPath, File localFile, String mode) throws IOException {
        if (localFile.length() > CHUNKED_UPLOAD_THRESHOLD) {
            return uploadContentChunked(destPath, localFile, mode);
        }
        try {
            String argHeader = asciiSafeHeaderValue(new JSONObject()
                    .put("path", destPath)
                    .put("mode", mode)
                    .put("autorename", false)
                    .put("mute", false));
            RequestBody body = RequestBody.create(localFile, mediaTypeOrOctetStream(localFile));
            Request request = authedRequest(CONTENT_BASE + "/files/upload")
                    .header("Dropbox-API-Arg", argHeader)
                    .post(body)
                    .build();
            try (Response response = http.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw rateLimitAwareException(response, responseBody);
                }
                return new JSONObject(responseBody).getString("path_lower");
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** Doc header Retry-After khi Dropbox tra ve 429 (rate limit) va boc vao CloudSyncException
     * co retryAfterSeconds -- de BackoffRetry (Phase 8) doi dung khoang thoi gian server yeu cau
     * thay vi backoff mu (van de #10, muc 2 cua prompt nang cap). Cac loi khac van la
     * DropboxApiException nhu cu (khong doi hanh vi hien co cho conflict/404). */
    private DropboxApiException rateLimitAwareException(Response response, String body) {
        if (response.code() == 429) {
            String retryAfterHeader = response.header("Retry-After");
            Integer retryAfterSeconds = null;
            if (retryAfterHeader != null) {
                try {
                    retryAfterSeconds = Integer.parseInt(retryAfterHeader.trim());
                } catch (NumberFormatException ignored) {
                    // Header khong parse duoc so -- bo qua, BackoffRetry se dung backoff mu.
                }
            }
            return new DropboxApiException(response.code(), body, retryAfterSeconds);
        }
        return new DropboxApiException(response.code(), body);
    }

    /** Upload file lon qua Dropbox upload session: start (chunk dau) -> append_v2 (cac chunk
     * giua) -> finish (chunk cuoi + metadata dich, mode/autorename giong simple upload). Xem
     * TODO da co san trong code goc — day la phan trien khai (van de #9, muc 2 cua prompt nang
     * cap). */
    private String uploadContentChunked(String destPath, File localFile, String mode) throws IOException {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(localFile, "r")) {
            long total = raf.length();
            byte[] chunk = new byte[CHUNK_SIZE];

            int firstLen = raf.read(chunk);
            if (firstLen <= 0) {
                // File rong (0 byte) -- ban than khong the > CHUNKED_UPLOAD_THRESHOLD nen nhanh
                // nay ly thuyet khong xay ra, nhung phong thu de tranh chia cho 0/vong lap sai.
                firstLen = 0;
            }
            String sessionId = startUploadSession(chunk, firstLen);
            long offset = firstLen;

            while (offset < total) {
                int len = raf.read(chunk);
                if (len <= 0) break;
                boolean isLast = offset + len >= total;
                if (isLast) {
                    return finishUploadSession(sessionId, offset, chunk, len, destPath, mode);
                }
                appendUploadSession(sessionId, offset, chunk, len);
                offset += len;
            }
            // total == firstLen (file vua dung 1 chunk): finish ngay voi 0 byte them.
            return finishUploadSession(sessionId, offset, new byte[0], 0, destPath, mode);
        }
    }

    private String startUploadSession(byte[] data, int len) throws IOException {
        Request request = authedRequest(CONTENT_BASE + "/files/upload_session/start")
                .header("Dropbox-API-Arg", "{}")
                .post(RequestBody.create(OCTET_STREAM, data, 0, len))
                .build();
        try (Response response = http.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) throw rateLimitAwareException(response, body);
            try {
                return new JSONObject(body).getString("session_id");
            } catch (JSONException e) {
                throw new IOException(e);
            }
        }
    }

    private void appendUploadSession(String sessionId, long offset, byte[] data, int len) throws IOException {
        try {
            String arg = asciiSafeHeaderValue(new JSONObject()
                    .put("cursor", new JSONObject().put("session_id", sessionId).put("offset", offset)));
            Request request = authedRequest(CONTENT_BASE + "/files/upload_session/append_v2")
                    .header("Dropbox-API-Arg", arg)
                    .post(RequestBody.create(OCTET_STREAM, data, 0, len))
                    .build();
            try (Response response = http.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String body = response.body() != null ? response.body().string() : "";
                    throw rateLimitAwareException(response, body);
                }
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private String finishUploadSession(String sessionId, long offset, byte[] data, int len,
                                        String destPath, String mode) throws IOException {
        try {
            String arg = asciiSafeHeaderValue(new JSONObject()
                    .put("cursor", new JSONObject().put("session_id", sessionId).put("offset", offset))
                    .put("commit", new JSONObject()
                            .put("path", destPath).put("mode", mode).put("autorename", false)));
            Request request = authedRequest(CONTENT_BASE + "/files/upload_session/finish")
                    .header("Dropbox-API-Arg", arg)
                    .post(RequestBody.create(OCTET_STREAM, data, 0, len))
                    .build();
            try (Response response = http.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) throw rateLimitAwareException(response, body);
                return new JSONObject(body).getString("path_lower");
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** Sau khi nhan loi 409 "conflict" tu upload/create_folder, goi get_metadata de lay path_lower
     * that su cua doi tuong dang xung dot, thay vi nem loi - can thiet vi ban than JSON error cua
     * Dropbox cho 409 conflict KHONG kem san id/path_lower cua doi tuong cu (khac voi Box, tra
     * san context_info.conflicts[0].id ngay trong body loi 409). */
    private String metadataPathLowerOrThrow(String path, DropboxApiException original) throws IOException {
        try {
            JSONObject params = new JSONObject().put("path", path);
            JSONObject meta = executeRpc("/files/get_metadata", params);
            return meta.getString("path_lower");
        } catch (JSONException e) {
            throw original;
        } catch (IOException e) {
            throw original;
        }
    }

    @Override
    public void deleteFile(CloudFile file) throws IOException {
        // files/delete_v2 xoa de quy ca file lan folder qua CUNG 1 endpoint, khong can phan
        // biet nhu Box.
        try {
            JSONObject params = new JSONObject().put("path", file.getRefId());
            executeRpc("/files/delete_v2", params);
        } catch (DropboxApiException e) {
            if (e.httpCode == 409) {
                // path/lookup/not_found -- da bi xoa thu cong tu truoc, coi la thanh cong (xem
                // javadoc CloudProvider#deleteFile).
                return;
            }
            throw e;
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    @Override
    public boolean testConnection() throws IOException {
        executeRpc("/users/get_current_account", null);
        return true;
    }

    @Override
    public void close() {
        // OkHttpClient khong giu socket thuong truc can giai phong thu cong o day.
    }

    /** Noi path cha voi ten con theo quy uoc path cua Dropbox: root la "" (KHONG phai "/"), nen
     * "" + "/ten" = "/ten"; moi path khac deu da bat dau bang "/" san nen chi can noi them
     * "/ten". */
    private static String joinPath(String parentRefId, String name) {
        return (parentRefId == null || parentRefId.isEmpty() ? "" : parentRefId) + "/" + name;
    }

    /** Header HTTP ve nguyen tac chi an toan voi ky tu ASCII - Dropbox yeu cau moi ky tu ngoai
     * ASCII (vd ten sach tieng Viet co dau) va ky tu 0x7F trong "Dropbox-API-Arg" phai duoc thoat
     * thanh \\uXXXX (xem tai lieu Dropbox "HTTP header format"), khac voi JSON body binh thuong
     * (RequestBody) noi UTF-8 tho la du. */
    private static String asciiSafeHeaderValue(JSONObject json) {
        String raw = json.toString();
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= 0x20 && c <= 0x7E && c != 0x7F) {
                sb.append(c);
            } else {
                sb.append(String.format("\\u%04x", (int) c));
            }
        }
        return sb.toString();
    }

    private static MediaType mediaTypeOrOctetStream(File file) {
        String guessed = URLConnection.guessContentTypeFromName(file.getName());
        MediaType type = guessed != null ? MediaType.parse(guessed) : null;
        return type != null ? type : OCTET_STREAM;
    }

    private static long parseIsoTimeToMillis(String iso8601OrNull) {
        if (iso8601OrNull == null) return 0;
        try {
            return java.time.Instant.parse(iso8601OrNull).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }
}
