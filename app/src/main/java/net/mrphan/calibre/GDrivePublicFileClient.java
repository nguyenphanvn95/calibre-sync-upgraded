package net.mrphan.calibre;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Gọi Google Drive v3 REST API công khai (KHÔNG OAuth) bằng 1 API key đã bị restrict, cho các file đã
 * share "Anyone with the link". Đối xứng 1-1 với GDrivePublicFileService bên bản Flutter
 * (calibre-web-companion) — xem file đó để so khớp hành vi nếu cần chỉnh sửa.
 */
public class GDrivePublicFileClient {

    private static final String DRIVE_FILES_BASE = "https://www.googleapis.com/drive/v3/files";
    private static final Pattern FILE_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{10,}$");

    /** Cho phép tối đa 4 request Drive chạy song song (cover/download hàng loạt khi cuộn lưới
     * sách) — phần dư xếp hàng thay vì bắn hết cùng lúc rồi bị 403 quota. */
    private final Semaphore gate = new Semaphore(4);
    private final OkHttpClient client;

    public GDrivePublicFileClient(OkHttpClient client) {
        this.client = client;
    }

    /** Nhận vào ID thô hoặc link chia sẻ dạng .../file/d/<ID>/view hoặc .../open?id=<ID>,
     * trả về ID sạch, hoặc null nếu không nhận diện được. */
    public static String extractFileId(String input) {
        if (input == null) return null;
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return null;

        if (FILE_ID_PATTERN.matcher(trimmed).matches() && !trimmed.contains("/")) {
            return trimmed;
        }

        try {
            HttpUrl url = HttpUrl.parse(trimmed);
            if (url == null) return null;
            java.util.List<String> segments = url.pathSegments();
            int dIndex = segments.indexOf("d");
            if (dIndex != -1 && dIndex + 1 < segments.size()) {
                return segments.get(dIndex + 1);
            }
            String idParam = url.queryParameter("id");
            if (idParam != null && FILE_ID_PATTERN.matcher(idParam).matches()) {
                return idParam;
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private HttpUrl directDownloadUrl(String fileId) {
        return HttpUrl.parse(DRIVE_FILES_BASE + "/" + fileId).newBuilder()
                .addQueryParameter("alt", "media")
                .addQueryParameter("key", BuildConfig.GDRIVE_PUBLIC_API_KEY)
                .build();
    }

    private HttpUrl metadataUrl(String fileId) {
        return HttpUrl.parse(DRIVE_FILES_BASE + "/" + fileId).newBuilder()
                .addQueryParameter("fields", "id,name,mimeType,size")
                .addQueryParameter("key", BuildConfig.GDRIVE_PUBLIC_API_KEY)
                .build();
    }

    /** Kiểm tra nhanh 1 file ID có tồn tại/công khai không (dùng cho debounce validate khi
     * người dùng gõ ID/link vào dialog thêm thư viện) — KHÔNG tải nội dung file. */
    public org.json.JSONObject fetchMetadata(String fileId) throws GDriveFileException {
        Request request = new Request.Builder().url(metadataUrl(fileId)).get().build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throwForStatus(response.code(), fileId, body);
            }
            return new org.json.JSONObject(body);
        } catch (IOException e) {
            throw new GDriveFileException(GDriveFileErrorType.NETWORK,
                    "Không kết nối được tới Google Drive: " + e.getMessage());
        } catch (org.json.JSONException e) {
            throw new GDriveFileException(GDriveFileErrorType.UNKNOWN, "Phản hồi không hợp lệ từ Google Drive");
        }
    }

    /** Tải toàn bộ nội dung text của metadata_public.json theo file ID. */
    public String downloadJson(String fileId) throws GDriveFileException {
        Request request = new Request.Builder().url(directDownloadUrl(fileId)).get().build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body != null ? body.string() : "";
            if (!response.isSuccessful()) {
                throwForStatus(response.code(), fileId, text);
            }
            return text;
        } catch (IOException e) {
            throw new GDriveFileException(GDriveFileErrorType.NETWORK,
                    "Không kết nối được tới Google Drive: " + e.getMessage());
        }
    }

    /** Mở stream tải trực tiếp 1 file (cover hoặc ebook) để GDriveOpdsHttpServer proxy thẳng ra
     * response HTTP mà không buffer cả file vào RAM. Có retry 1 lần khi Drive trả 403 do
     * rate-limit/quota (không retry khi 403 do file thực sự không public). */
    public Response openStream(String fileId, String rangeHeaderOrNull) throws GDriveFileException {
        try {
            gate.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GDriveFileException(GDriveFileErrorType.UNKNOWN, "Interrupted");
        }
        try {
            return openStreamAttempt(fileId, rangeHeaderOrNull, true);
        } finally {
            gate.release();
        }
    }

    private Response openStreamAttempt(String fileId, String rangeHeaderOrNull, boolean allowRetry)
            throws GDriveFileException {
        Request.Builder builder = new Request.Builder().url(directDownloadUrl(fileId)).get();
        if (rangeHeaderOrNull != null) builder.addHeader("Range", rangeHeaderOrNull);
        Request request = builder.build();

        Response response;
        try {
            response = client.newCall(request).execute();
        } catch (IOException e) {
            throw new GDriveFileException(GDriveFileErrorType.NETWORK,
                    "Không kết nối được tới Google Drive: " + e.getMessage());
        }

        if (response.code() == 403) {
            String body;
            try {
                body = response.body() != null ? response.body().string() : "";
            } catch (IOException e) {
                body = "";
            }
            response.close();
            boolean rateLimited = body.toLowerCase().contains("quota") || body.toLowerCase().contains("rate");
            if (rateLimited && allowRetry) {
                Logger.d("GDrive rate-limited " + fileId + ", retrying once after backoff...");
                try {
                    Thread.sleep(800);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return openStreamAttempt(fileId, rangeHeaderOrNull, false);
            }
            throw new GDriveFileException(
                    rateLimited ? GDriveFileErrorType.QUOTA_EXCEEDED : GDriveFileErrorType.NOT_PUBLIC,
                    rateLimited ? "Google Drive API đã vượt quota, thử lại sau." :
                            "File này chưa được chia sẻ công khai. Hãy đặt chế độ chia sẻ \"Anyone with the link\" cho file (và thư mục cha) trên Google Drive.");
        }
        if (!response.isSuccessful()) {
            String body;
            try {
                body = response.body() != null ? response.body().string() : "";
            } catch (IOException e) {
                body = "";
            }
            response.close();
            throwForStatus(response.code(), fileId, body);
        }
        return response;
    }

    private void throwForStatus(int statusCode, String fileId, String body) throws GDriveFileException {
        switch (statusCode) {
            case 404:
                throw new GDriveFileException(GDriveFileErrorType.NOT_FOUND,
                        "Không tìm thấy file trên Google Drive (id: " + fileId + "). Kiểm tra lại ID/link chia sẻ.");
            case 403:
                boolean quota = body.toLowerCase().contains("quota") || body.toLowerCase().contains("rate");
                throw new GDriveFileException(
                        quota ? GDriveFileErrorType.QUOTA_EXCEEDED : GDriveFileErrorType.NOT_PUBLIC,
                        quota ? "Google Drive API đã vượt quota, thử lại sau."
                                : "File này chưa được chia sẻ công khai (\"Anyone with the link\").");
            default:
                throw new GDriveFileException(GDriveFileErrorType.UNKNOWN,
                        "Google Drive trả về HTTP " + statusCode + " cho file " + fileId + ".");
        }
    }
}
