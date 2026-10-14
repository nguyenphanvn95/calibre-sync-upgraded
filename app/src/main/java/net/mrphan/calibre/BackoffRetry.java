package net.mrphan.calibre;

import java.io.IOException;

/** Retry voi backoff mu, uu tien Retry-After cua server khi co (429/5xx) — chuyen the tu
 * withBackoff() cua GoogleDriveProvider.ts. Toi da 4 lan thu lai, giong readest.
 * <p>
 * CHi ap dung cho CloudSyncException co code RATE_LIMIT/NETWORK (xem CloudSyncException) --
 * IOException thuong (provider chua nang cap nem loi co ngu nghia) di qua khong bi bat, giu
 * nguyen hanh vi cu (khong retry o tang nay, WorkManager van co the Result.retry() rieng o
 * DeviceLibrarySyncWorker). */
final class BackoffRetry {

    private static final int MAX_RETRIES = 4;
    private static final long BASE_DELAY_MS = 500;

    private BackoffRetry() {}

    interface Op<T> {
        T call() throws IOException;
    }

    static <T> T run(Op<T> op) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                return op.call();
            } catch (CloudSyncException e) {
                boolean transient_ = e.code == CloudSyncException.Code.RATE_LIMIT
                        || e.code == CloudSyncException.Code.NETWORK;
                if (!transient_ || attempt >= MAX_RETRIES) throw e;
                long delayMs = e.retryAfterSeconds != null
                        ? e.retryAfterSeconds * 1000L
                        : BASE_DELAY_MS * (1L << attempt);
                sleep(delayMs);
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
