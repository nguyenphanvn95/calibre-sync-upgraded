package net.mrphan.calibre;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client for the send2ereader relay service (see daniel-j/send2ereader on GitHub). Protocol:
 * the e-reader's own browser visits the configured instance, which generates a short pairing
 * key and shows it on screen while polling GET /status/{key}; this app then POSTs the book
 * file (or a remote URL) to POST /upload along with that same key, and the server relays it to
 * the waiting e-reader browser. There is no "push straight to a specific device" API -- the
 * pairing key is the only handshake, entered by the user after reading it off their e-reader's
 * screen. See BookDetailActivity/DeviceBookDetailActivity for the two call sites (Calibre
 * Library tab and Device Library tab).
 * <p>
 * NOTE: the exact field names used below (key/file) were verified against a real send2ereader
 * instance per the "Yêu cầu bàn giao" checklist before this class shipped -- if you stand up a
 * different fork/version and it rejects requests, re-check with `curl -F key=XXXX -F
 * file=@test.epub https://<instance>/upload` before changing this class.
 */
public final class Send2EreaderClient {

    public static final String PREF_BASE_URL = "pref_send2ereader_base_url";
    /** Public instance mentioned in send2ereader's own README as the default hosted option --
     * used only as a starting suggestion, users are encouraged to self-host their own (see
     * CLOUD_SETUP.md's tone for other self-hosted integrations in this app). */
    public static final String DEFAULT_BASE_URL = "https://send.djazz.se";

    public interface Callback {
        void onSuccess();
        void onFailure(String message);
    }

    private Send2EreaderClient() {}

    // Rieng khong dung AsyncTask (da bi loai bo khoi Android moi, va moi AsyncTask trong toan
    // he thong von dung chung 1 thread pool tuan tu theo mac dinh -- 1 upload cham co the lam
    // nghen cac AsyncTask khac). Dung 1 ExecutorService rieng cho class nay, cung tinh than voi
    // CoverDecodeExecutor. Khac voi CoverDecodeExecutor (bind 1 row list, mat ket qua thi khong
    // sao), upload o day KHONG duoc phep bi bo qua giua chung chi vi hang doi day -- dung
    // newCachedThreadPool (khong gioi han) thay vi ThreadPoolExecutor gioi han + reject-oldest,
    // vi so luong upload dong thoi trong thuc te rat nho (nguoi dung bam "Gui toi e-reader" tung
    // cuon mot) nen khong can bounded pool o day.
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "send2ereader-upload");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public static String getBaseUrl(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String saved = prefs.getString(PREF_BASE_URL, null);
        if (TextUtils.isEmpty(saved)) return DEFAULT_BASE_URL;
        // Strip any trailing slash so "{base}/upload" never ends up with a double slash.
        return saved.endsWith("/") ? saved.substring(0, saved.length() - 1) : saved;
    }

    /** Uploads a local file to the given pairing key. Runs on a background thread (this class's
     * own {@link #EXECUTOR}, not the shared AsyncTask pool); callback fires on the main thread.
     * Fire-and-forget by design, same as the AsyncTask version it replaces: callers
     * (BookDetailActivity/DeviceBookDetailActivity) pass applicationContext and don't hold a
     * reference to cancel this, so there is no lifecycle to leak -- if the Activity is gone by
     * the time the callback runs, the Callback implementation (a Toast, typically) just becomes
     * a no-op/silently-swallowed call, exactly as it already did with AsyncTask.onPostExecute().
     */
    public static void uploadFile(Context context, String pairingKey, File file,
                                   String displayFilename, String mimeType, Callback callback) {
        Context appContext = context.getApplicationContext();
        String resolvedMimeType = TextUtils.isEmpty(mimeType) ? "application/octet-stream" : mimeType;
        EXECUTOR.execute(() -> {
            String errorMessage = doUpload(appContext, pairingKey, file, displayFilename, resolvedMimeType);
            if (callback == null) return;
            MAIN_HANDLER.post(() -> {
                if (errorMessage == null) {
                    callback.onSuccess();
                } else {
                    callback.onFailure(errorMessage);
                }
            });
        });
    }

    /** Runs on {@link #EXECUTOR}'s background thread. Returns null on success (HTTP 2xx), or an
     * error message otherwise -- same contract AsyncTask's doInBackground() return value had. */
    private static String doUpload(Context appContext, String pairingKey, File file,
                                    String displayFilename, String mimeType) {
        if (file == null || !file.exists()) {
            return "local file missing";
        }
        String boundary = "----calibresync-" + UUID.randomUUID();
        HttpURLConnection connection = null;
        try {
            URL url = new URL(getBaseUrl(appContext) + "/upload");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setChunkedStreamingMode(8192);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            try (OutputStream out = connection.getOutputStream()) {
                writeFormField(out, boundary, "key", pairingKey);
                writeFileField(out, boundary, "file", displayFilename, mimeType, file);
                out.write(("--" + boundary + "--\r\n").getBytes("UTF-8"));
            }

            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) {
                return null; // success, no error message
            }
            return "HTTP " + code;
        } catch (Exception e) {
            Logger.e(e);
            return e.getMessage() != null ? e.getMessage() : "Unknown error";
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void writeFormField(OutputStream out, String boundary, String name, String value) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n");
        sb.append(value).append("\r\n");
        out.write(sb.toString().getBytes("UTF-8"));
    }

    private static void writeFileField(OutputStream out, String boundary, String fieldName,
                                        String filename, String mimeType, File file) throws IOException {
        StringBuilder header = new StringBuilder();
        header.append("--").append(boundary).append("\r\n");
        header.append("Content-Disposition: form-data; name=\"").append(fieldName)
                .append("\"; filename=\"").append(sanitizeForHeader(filename)).append("\"\r\n");
        header.append("Content-Type: ").append(mimeType).append("\r\n\r\n");
        out.write(header.toString().getBytes("UTF-8"));

        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
        }
        out.write("\r\n".getBytes("UTF-8"));
    }

    /** Header values can't contain raw quotes/newlines -- strip anything that would break the
     * Content-Disposition line instead of trusting a book title as-is. */
    private static String sanitizeForHeader(String name) {
        if (name == null) return "book";
        return name.replaceAll("[\\r\\n\"]", "_");
    }
}
