package net.mrphan.calibre;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Bat moi crash (uncaught exception) TRUOC KHI he thong giet process, ghi full stack trace ra
 * 1 file trong bo nho rieng cua app (khong can quyen storage gi ca, luon ghi duoc du chua cau
 * hinh Root Folder/CalibreService -- xem Logger.set() trong CalibreService, cai do CHI hoat dong
 * khi Root Folder da duoc chon, nen se KHONG bat duoc crash xay ra truoc/ngoai luong do, vi du
 * ngay khi vao 1 man hinh moi bi loi).
 * <p>
 * Cach xem log sau khi app crash:
 * 1. Mo lai app (khong can gi them, file van con tren may).
 * 2. Lay file ve may tinh bang adb (khong can root):
 *    adb pull /sdcard/Android/data/net.mrphan.calibre/files/crash_logs/ .
 *    (thay "net.mrphan.calibre" bang dung applicationId neu ban doi package name)
 * 3. Hoac doc truc tiep tu trong app: file nam o
 *    context.getExternalFilesDir(null) + "/crash_logs/crash_<timestamp>.txt"
 * <p>
 * Cai dat 1 lan duy nhat trong CalibreSyncApplication.onCreate() -- xem install() ben duoi.
 */
public final class CrashLogHandler implements Thread.UncaughtExceptionHandler {

    private static final String CRASH_DIR_NAME = "crash_logs";

    private final Context appContext;
    private final Thread.UncaughtExceptionHandler previousHandler;

    private CrashLogHandler(Context appContext, Thread.UncaughtExceptionHandler previousHandler) {
        this.appContext = appContext;
        this.previousHandler = previousHandler;
    }

    /** Goi 1 lan trong Application.onCreate(), cang som cang tot. */
    public static void install(Context context) {
        Thread.UncaughtExceptionHandler existing = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(
                new CrashLogHandler(context.getApplicationContext(), existing));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            writeCrashLog(thread, throwable);
        } catch (Throwable loggingFailure) {
            // Tuyet doi khong de viec ghi log lam crash-handler tu no crash them lan nua --
            // chi log ra logcat (khong ghi file) roi tiep tuc xu ly binh thuong ben duoi.
            android.util.Log.e("CSync", "Failed to write crash log", loggingFailure);
        }
        // Log ra logcat ngay lap tuc qua Logger co san (Logger.e da tu ghi ca vao debugFile
        // NEU Logger.set() da duoc goi truoc do, vd sau khi Root Folder da duoc chon).
        Logger.e(throwable);

        // QUAN TRONG: van phai goi lai handler mac dinh (hoac System.exit) sau khi ghi log xong,
        // neu khong process se bi "treo" o trang thai loi thay vi crash/dong binh thuong nhu
        // Android mong doi -- nguoi dung se thay app dung im re thay vi bao "App has stopped".
        if (previousHandler != null) {
            previousHandler.uncaughtException(thread, throwable);
        } else {
            System.exit(2);
        }
    }

    private void writeCrashLog(Thread thread, Throwable throwable) {
        File dir = new File(appContext.getExternalFilesDir(null), CRASH_DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            android.util.Log.e("CSync", "Could not create crash log directory: " + dir);
            return;
        }

        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        File logFile = new File(dir, "crash_" + timestamp + ".txt");

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("=== CRASH REPORT ===");
        pw.println("Time: " + timestamp);
        pw.println("Thread: " + thread.getName());
        pw.println("App version: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
        pw.println("Device: " + Build.MANUFACTURER + " " + Build.MODEL
                + " (Android " + Build.VERSION.RELEASE + ", API " + Build.VERSION.SDK_INT + ")");
        pw.println();
        pw.println("--- Stack trace ---");
        throwable.printStackTrace(pw);

        // Neu day la mot loi long nhau (cause chain), Throwable#printStackTrace o tren da tu in
        // het "Caused by:" ben trong roi, khong can tu duyet them.
        pw.flush();

        try (FileWriter fw = new FileWriter(logFile, false)) {
            fw.write(sw.toString());
        } catch (IOException e) {
            android.util.Log.e("CSync", "Could not write crash log file: " + logFile, e);
        }
    }
}
