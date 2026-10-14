package net.mrphan.calibre;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * ExecutorService rieng cho viec decode bitmap cover ngoai main thread, dung cho ca
 * BookCursorAdapter va BookRecyclerAdapter (2 kien truc adapter khac nhau, xem upgrade_1.md muc
 * 2.3 -- chua hop nhat adapter nen it nhat dung chung 1 executor decode).
 * <p>
 * KHONG dung BoundedTaskPool o day: BoundedTaskPool.run() la blocking (Future.get() cho toi khi
 * ca batch xong), thiet ke cho sync hang loat, khong hop voi nhu cau "enqueue 1 task, khong cho,
 * nhan callback tren main thread khi xong" cua viec bind 1 row.
 * <p>
 * Thread pool co gioi han (khong dung newCachedThreadPool khong gioi han) de tranh tao qua nhieu
 * thread khi user scroll rat nhanh qua hang tram row lien tuc -- moi core CPU 1 thread la du cho
 * tac vu decode JPEG/PNG (I/O + CPU-bound nhe).
 */
final class CoverDecodeExecutor {

    private static final int POOL_SIZE = Math.max(2,
            Runtime.getRuntime().availableProcessors());

    // Hang doi gioi han + reject-oldest: neu user scroll qua nhanh, cac task decode cho row DA
    // BI RECYCLE khong con y nghia gi nua -- huy bot thay vi de hang doi phinh to vo han.
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
            POOL_SIZE, POOL_SIZE,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(64),
            r -> {
                Thread t = new Thread(r, "cover-decode");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            },
            (r, executor) -> {
                // Hang doi day: bo task cu nhat (co the la row da scroll qua roi), enqueue lai task
                // moi -- uu tien row dang hien tai tren man hinh hon row vua bi scroll qua.
                java.util.concurrent.BlockingQueue<Runnable> q = executor.getQueue();
                q.poll();
                executor.execute(r);
            });

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private CoverDecodeExecutor() {}

    interface Callback {
        void onDecoded(android.graphics.Bitmap bitmap);
    }

    /** Enqueue 1 task decode. Khong throw, khong block caller. */
    static void decodeAsync(java.util.concurrent.Callable<android.graphics.Bitmap> decodeTask,
                             Callback callback) {
        EXECUTOR.execute(() -> {
            android.graphics.Bitmap result;
            try {
                result = decodeTask.call();
            } catch (Exception e) {
                Logger.e(e);
                result = null;
            }
            android.graphics.Bitmap finalResult = result;
            MAIN_HANDLER.post(() -> callback.onDecoded(finalResult));
        });
    }
}
