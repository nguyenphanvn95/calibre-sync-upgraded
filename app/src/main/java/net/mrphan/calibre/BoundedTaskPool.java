package net.mrphan.calibre;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Chay danh sach "items" voi toi da "concurrency" tac vu dong thoi -- chuyen the tu runPool()
 * cua engine.ts (readest). Thay vi vong for tuan tu (ban cu cua uploadBooks/
 * downloadMissingBooks), cho phep nhieu request mang chay song song, an do do tre round-trip
 * cua tung sach — quan trong tren mang di dong do tre cao.
 * <p>
 * Loi cua 1 item KHONG lam dung ca pool: duoc bat va dua vao "onError" (giong cach
 * DeviceLibrarySyncManager hien tai log-va-tiep-tuc cho tung sach), giu dung hanh vi "1 sach
 * loi khong lam chet ca phien sync" da co.
 */
final class BoundedTaskPool {

    private BoundedTaskPool() {}

    interface Worker<T> {
        void run(T item) throws Exception;
    }

    static <T> void run(List<T> items, int concurrency, Worker<T> worker,
                         java.util.function.BiConsumer<T, Exception> onError) {
        if (items.isEmpty()) return;
        int poolSize = Math.max(1, Math.min(concurrency, items.size()));
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        try {
            List<Future<?>> futures = new ArrayList<>(items.size());
            for (T item : items) {
                futures.add(executor.submit(() -> {
                    try {
                        worker.run(item);
                    } catch (Exception e) {
                        onError.accept(item, e);
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException e) {
                    // Worker da tu bat exception va goi onError o tren -- nhanh nay ly thuyet
                    // khong xay ra, nhung log phong khi co loi khong luong truoc duoc (vd
                    // OutOfMemoryError khong phai Exception).
                    Logger.e(e);
                }
            }
        } finally {
            executor.shutdown();
        }
    }
}
