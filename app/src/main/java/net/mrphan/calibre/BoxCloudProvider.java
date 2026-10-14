package net.mrphan.calibre;

import android.content.Context;

import org.json.JSONArray;
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
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * CloudProvider dua tren Box Content API v2.0, goi truc tiep bang OkHttp -- cung tinh than voi
 * OneDriveCloudProvider/GoogleDriveCloudProvider. Box can 2 domain khac nhau: api.box.com cho
 * moi thao tac metadata/download, upload.box.com rieng cho 2 API upload (file moi + version
 * moi). Root folder la id that "0", KHONG phai alias tu che nhu "root" cua OneDrive.
 */
public class BoxCloudProvider implements CloudProvider {

    private static final String API_BASE = "https://api.box.com/2.0";
    private static final String UPLOAD_BASE = "https://upload.box.com/api/2.0";
    private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");
    private static final MediaType JSON_TYPE = MediaType.parse("application/json");

    private final Context appContext;
    private final OkHttpClient http;
    /** Rieng cho download: KHONG tu theo redirect, vi buoc 2 (dl.boxcloud.com) khong duoc mang
     * theo header Authorization -- xem javadoc downloadFile(). */
    private final OkHttpClient httpNoRedirect;

    BoxCloudProvider(Context context) {
        if (!CloudTokenManager.getInstance(context, CloudRemoteType.BOX).isSignedIn()) {
            throw new IllegalStateException("Not signed in to Box (or access was revoked)");
        }
        this.appContext = context.getApplicationContext();
        this.http = NetworkModule.BASE.newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build();
        this.httpNoRedirect = http.newBuilder().followRedirects(false).build();
    }

    private String bearerToken() throws IOException {
        return CloudTokenManager.getInstance(appContext, CloudRemoteType.BOX)
                .getValidAccessTokenBlocking(appContext);
    }

    private Request.Builder authedRequest(String url) throws IOException {
        return new Request.Builder().url(url).header("Authorization", "Bearer " + bearerToken());
    }

    private JSONObject executeJson(OkHttpClient client, Request request) throws IOException {
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Box API error " + response.code() + ": " + body);
            }
            try {
                return new JSONObject(body);
            } catch (JSONException e) {
                throw new IOException("Unexpected Box API response: " + body, e);
            }
        }
    }

    @Override
    public CloudFile getRoot() {
        return new CloudFile("0", "Box", true, 0, 0, false);
    }

    @Override
    public List<CloudFile> listFiles(String refId) throws IOException {
        List<CloudFile> result = new ArrayList<>();
        int offset = 0;
        final int limit = 1000;
        while (true) {
            String url = API_BASE + "/folders/" + refId + "/items"
                    + "?fields=id,name,type,size,modified_at&limit=" + limit + "&offset=" + offset;
            JSONObject page = executeJson(http, authedRequest(url).get().build());
            JSONArray entries = page.optJSONArray("entries");
            if (entries == null || entries.length() == 0) break;
            try {
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject item = entries.getJSONObject(i);
                    boolean isFolder = "folder".equals(item.optString("type"));
                    result.add(new CloudFile(
                            item.getString("id"),
                            item.getString("name"),
                            isFolder,
                            item.optLong("size", 0),
                            parseIsoTimeToMillis(item.optString("modified_at", null)),
                            false));
                }
            } catch (JSONException e) {
                throw new IOException("Unexpected Box API response: " + page, e);
            }
            offset += entries.length();
            int totalCount = page.optInt("total_count", offset);
            if (offset >= totalCount) break;
        }
        return result;
    }

    /** Box tra ve 302 toi mot URL da ky san tren dl.boxcloud.com o buoc dau -- KHONG duoc mang
     * header Authorization sang buoc 2 (cung ly do voi OneDriveCloudProvider#downloadFile: nhieu
     * CDN tra loi 400 neu thay header Authorization la); dung httpNoRedirect de tu bat Location
     * roi GET lai KHONG kem header. */
    @Override
    public void downloadFile(CloudFile file, File localDestFile) throws IOException {
        Request firstRequest = authedRequest(API_BASE + "/files/" + file.getRefId() + "/content")
                .get().build();
        String downloadUrl;
        try (Response first = httpNoRedirect.newCall(firstRequest).execute()) {
            if (first.code() != 302 && first.code() != 301) {
                throw new IOException("Unexpected Box download response, HTTP " + first.code());
            }
            downloadUrl = first.header("Location");
            if (downloadUrl == null) {
                throw new IOException("Box download response missing Location header");
            }
        }
        Request second = new Request.Builder().url(downloadUrl).get().build();
        try (Response response = http.newCall(second).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Download failed, HTTP " + response.code());
            }
            try (FileOutputStream out = new FileOutputStream(localDestFile);
                 ResponseBody body = response.body()) {
                out.write(body.bytes());
            }
        }
    }

    @Override
    public String uploadFile(File localFile, String destFolderRefId, String remoteFileName) throws IOException {
        JSONObject attributes = new JSONObject();
        try {
            // "attributes.name" la ten thuc su duoc Box dung -- ten trong addFormDataPart ben
            // duoi chi la ten field cua multipart, Box khong doc no lam ten file (xem javadoc
            // CloudProvider#uploadFile 3-tham-so).
            attributes.put("name", remoteFileName);
            attributes.put("parent", new JSONObject().put("id", destFolderRefId));
        } catch (JSONException e) {
            throw new IOException(e);
        }
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("attributes", attributes.toString())
                .addFormDataPart("file", remoteFileName,
                        RequestBody.create(localFile, mediaTypeOrOctetStream(localFile)))
                .build();
        Request request = authedRequest(UPLOAD_BASE + "/files/content").post(body).build();
        try (Response response = http.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (response.code() == 409) {
                // Trung ten voi file da co trong thu muc dich (chua duoc manifest theo doi) --
                // lay id file dang xung dot tu context_info.conflicts[0].id thay vi nem loi, de
                // DeviceLibrarySyncManager coi nhu da co san va tiep tuc, giong tinh than
                // idempotent cua updateFileContent#404 o OneDriveCloudProvider.
                try {
                    JSONObject conflict = new JSONObject(responseBody);
                    JSONArray conflicts = conflict.getJSONObject("context_info").getJSONArray("conflicts");
                    if (conflicts.length() > 0) {
                        return conflicts.getJSONObject(0).getString("id");
                    }
                } catch (JSONException ignored) {
                    // roi xuong nem IOException ben duoi neu khong parse duoc
                }
            }
            if (!response.isSuccessful()) {
                throw new IOException("Box API error " + response.code() + ": " + responseBody);
            }
            JSONObject created = new JSONObject(responseBody);
            return created.getJSONArray("entries").getJSONObject(0).getString("id");
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    @Override
    public String createFolder(String parentRefId, String name) throws IOException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("name", name);
            payload.put("parent", new JSONObject().put("id", parentRefId));
        } catch (JSONException e) {
            throw new IOException(e);
        }
        RequestBody body = RequestBody.create(payload.toString(), JSON_TYPE);
        Request request = authedRequest(API_BASE + "/folders").post(body).build();
        try (Response response = http.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (response.code() == 409) {
                // Thu muc trung ten da ton tai -- Box KHONG tu doi ten nhu Google Drive/OneDrive,
                // ma tra loi 409 kem id thu muc cu trong context_info.conflicts[0].id. Phai tra
                // ve id do de idempotent, vi RemoteTreeResolver goi lai createFolder moi
                // lan sync de "dam bao thu muc ton tai", khong phai chi lan dau.
                JSONObject conflict = new JSONObject(responseBody);
                JSONArray conflicts = conflict.getJSONObject("context_info").getJSONArray("conflicts");
                if (conflicts.length() > 0) {
                    return conflicts.getJSONObject(0).getString("id");
                }
            }
            if (!response.isSuccessful()) {
                throw new IOException("Box API error " + response.code() + ": " + responseBody);
            }
            return new JSONObject(responseBody).getString("id");
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** TODO: file lon (>50MB, khuyen nghi cua Box cho "simple upload") hien van dung API don
     * gian nay -- Box khuyen nghi "Chunked Upload" (POST .../upload_sessions roi PUT tung phan)
     * cho file lon. Chua cai trong ban nay, cung ly do voi TODO tren OneDriveCloudProvider: da so
     * sach epub/pdf ca nhan duoi 50MB nen van dung duoc. */
    @Override
    public String updateFileContent(String existingRefId, File localFile) throws IOException {
        JSONObject attributes = new JSONObject();
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("attributes", attributes.toString())
                .addFormDataPart("file", localFile.getName(),
                        RequestBody.create(localFile, mediaTypeOrOctetStream(localFile)))
                .build();
        Request request = authedRequest(UPLOAD_BASE + "/files/" + existingRefId + "/content")
                .post(body).build();
        try (Response response = http.newCall(request).execute()) {
            if (response.code() == 404) {
                // File bi xoa tay tren Box giua 2 lan sync -- tao lai o root, giong hanh vi
                // fallback cua GoogleDriveCloudProvider/OneDriveCloudProvider#updateFileContent.
                return uploadFile(localFile, "0");
            }
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Box API error " + response.code() + ": " + responseBody);
            }
            return new JSONObject(responseBody).getJSONArray("entries").getJSONObject(0).getString("id");
        } catch (JSONException e) {
            return existingRefId;
        }
    }

    @Override
    public void deleteFile(CloudFile file) throws IOException {
        // Box dung 2 endpoint khac nhau cho file va thu muc; thu muc can them
        // recursive=true de xoa de quy noi dung ben trong (khong nhu Drive/OneDrive tu dong de
        // quy), xem muc 3 cua prompt nang cap.
        String url = file.isDirectory()
                ? API_BASE + "/folders/" + file.getRefId() + "?recursive=true"
                : API_BASE + "/files/" + file.getRefId();
        Request request = authedRequest(url).delete().build();
        try (Response response = http.newCall(request).execute()) {
            if (response.code() == 404) {
                // Da bi xoa thu cong tu truoc -- coi la thanh cong (xem javadoc
                // CloudProvider#deleteFile).
                return;
            }
            if (!response.isSuccessful()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                throw new IOException("Box API error " + response.code() + ": " + responseBody);
            }
        }
    }

    @Override
    public boolean testConnection() throws IOException {
        executeJson(http, authedRequest(API_BASE + "/users/me?fields=id").get().build());
        return true;
    }

    @Override
    public void close() {
        // OkHttpClient khong giu socket thuong truc can giai phong thu cong o day.
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
