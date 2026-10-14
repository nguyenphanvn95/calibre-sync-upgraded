package net.mrphan.calibre;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tai truoc (prefetch) anh bia cho 1 GDriveOpdsLibrary vao GDriveCoverDiskCache, theo lo toi da
 * BATCH_SIZE anh chay song song -- doi xung voi _DriveRequestGate(4) ben gdrive_public_file_
 * service.dart, nhung o day la hang doi tai theo lo o tang ung dung (khong phai gioi han so
 * request Drive dang mo, cai do GDrivePublicFileClient da tu gioi han 4 roi) -- muc dich la de
 * luc OPDSLoader.getThumb() goi tuan tu cho tung entry trong feed, phan lon request do da la
 * cache-hit cuc bo (doc file, khong co network), thay vi phai doi 500+ round-trip mang noi tiep
 * nhau -- do la nguyen nhan chinh khien man hinh duyet sach bi trong/treo voi thu vien lon (xem
 * ghi chu trong GDriveOpdsHttpServer). */
class GDriveCoverPrefetcher {

    /** 5 anh moi lo, theo dung yeu cau -- lo ke tiep chi bat dau khi lo hien tai xong, tranh doi
     * hang tram request cung luc vao GDrivePublicFileClient (ban than client do cung tu gioi han
     * 4 request Drive *dang mo* cung luc qua Semaphore rieng -- 2 lop gioi han nay khong xung
     * dot, chi lop o day kiem soat "dua viec vao hang doi" con lop kia kiem soat "dang goi mang
     * that"). */
    static final int BATCH_SIZE = 5;

    interface ProgressListener {
        /** done/total tang dan sau moi anh (thanh cong hoac that bai deu tinh la "done") -- dung
         * de cap nhat text "Dang tai bia sach x/y" tren UI. Goi tu thread nen, caller tu post len
         * main thread neu can. */
        void onProgress(int done, int total);
    }

    private final GDrivePublicFileClient fileClient;
    private final GDriveCoverDiskCache diskCache;

    GDriveCoverPrefetcher(GDrivePublicFileClient fileClient, GDriveCoverDiskCache diskCache) {
        this.fileClient = fileClient;
        this.diskCache = diskCache;
    }

    /** Chay dong bo (BLOCKING) tren thread goi -- caller (GDriveOpdsHttpServer#start, von da chay
     * tren background executor cua GDriveOpdsLibraryBrowserActivity) chiu trach nhiem khong goi
     * ham nay tu main thread. Bo qua (khong throw) tung anh tai loi -- 1 anh loi khong duoc lam
     * hong ca viec mo thu vien, cac sach con lai van phai duyet duoc; OPDSLoader van se tu thu
     * tai lai (cache-miss) cho dung anh do khi parse feed neu prefetch bo sot. */
    void run(java.util.List<String> coverFileIds, ProgressListener listener) {
        // LinkedHashSet: loai trung (nhieu sach co the share 1 file bia) nhung giu thu tu tuong
        // doi de prefetch theo dung thu tu sach xuat hien trong feed truoc.
        Set<String> pending = new LinkedHashSet<>();
        for (String id : coverFileIds) {
            if (id != null && !id.isEmpty() && !diskCache.has(id)) pending.add(id);
        }
        int total = pending.size();
        if (total == 0) {
            if (listener != null) listener.onProgress(0, 0);
            return;
        }

        Queue<String> queue = new ArrayDeque<>(pending);
        ExecutorService pool = Executors.newFixedThreadPool(BATCH_SIZE);
        java.util.concurrent.atomic.AtomicInteger doneCount = new java.util.concurrent.atomic.AtomicInteger(0);

        try {
            while (!queue.isEmpty()) {
                int batchSize = Math.min(BATCH_SIZE, queue.size());
                CountDownLatch latch = new CountDownLatch(batchSize);
                for (int i = 0; i < batchSize; i++) {
                    String fileId = queue.poll();
                    pool.execute(() -> {
                        try {
                            fetchOne(fileId);
                        } finally {
                            int done = doneCount.incrementAndGet();
                            if (listener != null) listener.onProgress(done, total);
                            latch.countDown();
                        }
                    });
                }
                latch.await();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdown();
        }
    }

    private void fetchOne(String coverFileId) {
        try {
            okhttp3.Response response = fileClient.openStream(coverFileId, null);
            byte[] bytes = response.body() != null ? response.body().bytes() : null;
            response.close();
            if (bytes != null && bytes.length > 0) {
                diskCache.put(coverFileId, bytes);
            }
        } catch (Exception e) {
            // Loi tung anh (403/404/network/timeout...) khong nen lam gian doan ca lo -- log roi
            // bo qua, OPDSLoader se cache-miss va tu tai lai dung anh nay khi thuc su can.
            Logger.d("GDriveCoverPrefetcher: bo qua anh " + coverFileId + " (" + e.getMessage() + ")");
        }
    }
}
