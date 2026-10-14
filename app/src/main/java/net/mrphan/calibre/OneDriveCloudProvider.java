package net.mrphan.calibre;

import android.content.Context;

import org.json.JSONArray;
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
 * CloudProvider dua tren Microsoft Graph v1.0 REST API, goi truc tiep bang OkHttp (da la
 * dependency san co cua du an, xem app/build.gradle.kts) thay vi mot SDK Graph rieng - cung tinh
 * than voi WebDavCloudProvider/FtpCloudProvider (tu goi giao thuc thap thay vi phu thuoc SDK
 * nang). Moi method la mot request mang dong bo (blocking), giong dung hop dong cua
 * CloudProvider - xem Javadoc cua interface do.
 */
public class OneDriveCloudProvider implements CloudProvider {

    private static final String GRAPH_BASE = "https://graph.microsoft.com/v1.0";
    private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");

    private final Context appContext;
    private final OkHttpClient http;

    OneDriveCloudProvider(Context context) {
        if (!CloudTokenManager.getInstance(context, CloudRemoteType.ONEDRIVE).isSignedIn()) {
            throw new IllegalStateException("Not signed in to OneDrive (or access was revoked)");
        }
        this.appContext = context.getApplicationContext();
        this.http = NetworkModule.BASE.newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    private String bearerToken() throws IOException {
        return CloudTokenManager.getInstance(appContext, CloudRemoteType.ONEDRIVE)
                .getValidAccessTokenBlocking(appContext);
    }

    private Request.Builder authedRequest(String url) throws IOException {
        return new Request.Builder().url(url).header("Authorization", "Bearer " + bearerToken());
    }

    private JSONObject executeJson(Request request) throws IOException {
        try (Response response = http.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Graph API error " + response.code() + ": " + body);
            }
            try {
                return new JSONObject(body);
            } catch (org.json.JSONException e) {
                throw new IOException("Unexpected Graph API response: " + body, e);
            }
        }
    }

    @Override
    public CloudFile getRoot() {
        return new CloudFile("root", "OneDrive", true, 0, 0, false);
    }

    @Override
    public List<CloudFile> listFiles(String refId) throws IOException {
        List<CloudFile> result = new ArrayList<>();
        // "root" la alias dac biet cua chinh provider nay (xem getRoot()), khong phai id that cua
        // Graph - can doi thanh duong dan /me/drive/root/children, khac voi /me/drive/items/{id}/children
        // danh cho moi folder con khac.
        String childrenUrl = "root".equals(refId)
                ? GRAPH_BASE + "/me/drive/root/children?$select=id,name,folder,size,lastModifiedDateTime"
                : GRAPH_BASE + "/me/drive/items/" + refId + "/children?$select=id,name,folder,size,lastModifiedDateTime";
        String nextUrl = childrenUrl;
        while (nextUrl != null) {
            JSONObject page = executeJson(authedRequest(nextUrl).get().build());
            JSONArray values = page.optJSONArray("value");
            if (values != null) {
                for (int i = 0; i < values.length(); i++) {
                    try {
                        JSONObject item = values.getJSONObject(i);
                        boolean isFolder = item.has("folder");
                        result.add(new CloudFile(
                                item.getString("id"),
                                item.getString("name"),
                                isFolder,
                                item.optLong("size", 0),
                                parseIsoTimeToMillis(item.optString("lastModifiedDateTime", null)),
                                false));
                    } catch (org.json.JSONException e) {
                        throw new IOException("Unexpected Graph API response item: " + e.getMessage(), e);
                    }
                }
            }
            nextUrl = page.isNull("@odata.nextLink") ? null : page.optString("@odata.nextLink", null);
        }
        return result;
    }

    @Override
    public void downloadFile(CloudFile file, File localDestFile) throws IOException {
        // Lay downloadUrl co chu ky truoc, KHONG goi thang /content - endpoint do tra ve 302 va
        // OkHttp se mang theo header Authorization sang host CDN khac, gay loi 400 tren nhieu CDN
        // cua Microsoft. downloadUrl khong can header Authorization.
        JSONObject meta = executeJson(authedRequest(
                GRAPH_BASE + "/me/drive/items/" + file.getRefId() + "?$select=@microsoft.graph.downloadUrl")
                .get().build());
        String downloadUrl;
        try {
            downloadUrl = meta.getString("@microsoft.graph.downloadUrl");
        } catch (org.json.JSONException e) {
            throw new IOException("Unexpected Graph API response (no downloadUrl): " + meta, e);
        }
        Request request = new Request.Builder().url(downloadUrl).get().build();
        try (Response response = http.newCall(request).execute()) {
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
        // Simple upload - danh cho file <4MB theo khuyen nghi cua Microsoft (gioi han cung la
        // 250MB). File lon hon can "upload session" (POST .../createUploadSession + PUT tung
        // chunk) - CHUA lam trong ban nay, xem TODO ghi trong javadoc updateFileContent.
        // remoteFileName co the khac localFile.getName() (xem javadoc CloudProvider#uploadFile
        // 3-tham-so).
        String encodedName = java.net.URLEncoder.encode(remoteFileName, "UTF-8").replace("+", "%20");
        String url = "root".equals(destFolderRefId)
                ? GRAPH_BASE + "/me/drive/root:/" + encodedName + ":/content"
                : GRAPH_BASE + "/me/drive/items/" + destFolderRefId + ":/" + encodedName + ":/content";
        RequestBody body = RequestBody.create(localFile, mediaTypeOrOctetStream(localFile));
        Request request = authedRequest(url).put(body).build();
        JSONObject created = executeJson(request);
        try {
            return created.getString("id");
        } catch (org.json.JSONException e) {
            throw new IOException("Unexpected Graph API response (no id): " + created, e);
        }
    }

    @Override
    public String createFolder(String parentRefId, String name) throws IOException {
        String url = "root".equals(parentRefId)
                ? GRAPH_BASE + "/me/drive/root/children"
                : GRAPH_BASE + "/me/drive/items/" + parentRefId + "/children";
        JSONObject payload = new JSONObject();
        try {
            payload.put("name", name);
            payload.put("folder", new JSONObject());
            payload.put("@microsoft.graph.conflictBehavior", "rename");
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
        RequestBody body = RequestBody.create(payload.toString(), MediaType.parse("application/json"));
        Request request = authedRequest(url).post(body).build();
        JSONObject created = executeJson(request);
        try {
            return created.getString("id");
        } catch (org.json.JSONException e) {
            throw new IOException("Unexpected Graph API response (no id): " + created, e);
        }
    }

    /** TODO: file lon (>4MB, gan hoac vuot 250MB) hien dung simple PUT - Microsoft khuyen nghi
     * dung "upload session" (resumable, chia chunk) cho file lon de tranh timeout/loi mang giua
     * chung phai upload lai tu dau. Da biet gioi han nay, chua cai trong ban nay - da so
     * sach epub/pdf ca nhan duoi 250MB nen simple upload van dung duoc, chi cham/kem tin cay hon
     * voi file rat lon. */
    @Override
    public String updateFileContent(String existingRefId, File localFile) throws IOException {
        String url = GRAPH_BASE + "/me/drive/items/" + existingRefId + "/content";
        RequestBody body = RequestBody.create(localFile, mediaTypeOrOctetStream(localFile));
        Request request = authedRequest(url).put(body).build();
        try (Response response = http.newCall(request).execute()) {
            if (response.code() == 404) {
                // File bi xoa tay tren OneDrive giua 2 lan sync - tao lai o root, giong hanh vi
                // fallback cua GoogleDriveCloudProvider#updateFileContent.
                return uploadFile(localFile, "root");
            }
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Graph API error " + response.code() + ": " + responseBody);
            }
            try {
                return new JSONObject(responseBody).getString("id");
            } catch (org.json.JSONException e) {
                return existingRefId;
            }
        }
    }

    @Override
    public void deleteFile(CloudFile file) throws IOException {
        // Xoa 1 folder cung de quy tu dong phia Graph, khong can tu lap listFiles() roi xoa tung
        // muc con.
        String url = GRAPH_BASE + "/me/drive/items/" + file.getRefId();
        Request request = authedRequest(url).delete().build();
        try (Response response = http.newCall(request).execute()) {
            if (response.code() == 404) {
                // Da bi xoa thu cong tu truoc -- coi la thanh cong (xem javadoc
                // CloudProvider#deleteFile).
                return;
            }
            if (!response.isSuccessful()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                throw new IOException("Graph API error " + response.code() + ": " + responseBody);
            }
        }
    }

    @Override
    public boolean testConnection() throws IOException {
        executeJson(authedRequest(GRAPH_BASE + "/me/drive?$select=id").get().build());
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
