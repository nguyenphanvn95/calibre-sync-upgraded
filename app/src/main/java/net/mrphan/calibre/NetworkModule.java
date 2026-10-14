package net.mrphan.calibre;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * OkHttpClient dung chung cho toan app. Truoc day BoxCloudProvider, OneDriveCloudProvider,
 * DropboxCloudProvider, GDriveOpdsHttpServer moi noi tu "new OkHttpClient()" rieng -- moi
 * instance tu quan ly connection pool + dispatcher thread pool rieng, ton bo nho va khong tai su
 * dung duoc ket noi TCP/TLS da mo giua cac noi goi (xem upgrade_1.md muc 2.1).
 * <p>
 * Noi nao can tuy chinh timeout/interceptor rieng thi dung {@link #BASE}.newBuilder()...build() --
 * client con lai van CHIA SE connection pool + dispatcher voi client goc, chi khac cau hinh.
 */
final class NetworkModule {

    static final OkHttpClient BASE = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    private NetworkModule() {}
}
