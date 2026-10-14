package net.mrphan.calibre;

import java.io.IOException;

/** Loi dong bo co ma ngu nghia, chuyen the tu FileSyncError (provider.ts, readest) — cho phep
 * DeviceLibrarySyncWorker/DeviceLibrarySyncManager phan nhanh xu ly (AUTH_FAILED khong nen
 * retry vo han, NETWORK/RATE_LIMIT nen backoff-va-retry) thay vi coi moi IOException nhu nhau
 * (van de #10, muc 2 cua prompt nang cap). Cac provider hien tai (Box/OneDrive/WebDAV/FTP) van
 * co the tiep tuc nem IOException thuong -- BackoffRetry/DeviceLibrarySyncWorker coi loai do
 * nhu truoc day (khong phan loai duoc thi retry mu theo co che WorkManager cu), CHi Dropbox va
 * Google Drive duoc nang cap nem CloudSyncException co ngu nghia trong ban nay (xem Phase 8 cua
 * prompt nang cap, muc "Ghi chu cho reviewer" ve uu tien correctness). */
public class CloudSyncException extends IOException {
    public enum Code { AUTH_FAILED, NOT_FOUND, RATE_LIMIT, NETWORK, CONFLICT, UNKNOWN }

    public final Code code;
    public final Integer httpStatus;
    /** So giay server yeu cau doi truoc khi thu lai, tu header Retry-After — null neu server
     * khong tra ve hoac loi khong phai do rate-limit. */
    public final Integer retryAfterSeconds;

    public CloudSyncException(String message, Code code, Integer httpStatus, Integer retryAfterSeconds) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
