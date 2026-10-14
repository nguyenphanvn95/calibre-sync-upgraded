package net.mrphan.calibre;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

/** Cache dia cho bytes anh bia tai tu Google Drive, key theo cover_file_id (Drive file id cua
 * anh bia -- KHONG phai book_id, vi 1 file bia vat ly tren Drive la bat bien, con book_id la khoa
 * cuc bo cua thu vien). Tuong duong CachedNetworkImage + CustomCacheManager ben ban Flutter
 * (book_cover_widget.dart), nhung viet tuong minh vi du an Java nay khong co Glide/Coil san
 * (xem build.gradle.kts). Dung CHUNG cho moi GDriveOpdsLibrary (khong tach theo libraryId) vi
 * cung 1 file_id tren Drive luon la cung 1 noi dung anh, ke ca khi xuat hien o 2 thu vien khac
 * nhau -- tranh tai trung.
 * <p>
 * Luu y ve tang cache bo nho (xem upgrade_1.md muc 3.2, sau nay hop nhat theo phan hoi "3 he
 * thong cache cover anh rieng biet"): lop nay phuc vu BYTE THO (JPEG) qua GDriveOpdsHttpServer
 * (local HTTP server, NanoHTTPD) chu KHONG decode Bitmap o day -- Bitmap duoc decode o tang xa
 * hon (BookCursorAdapter/BookDetailActivity khi hien thi). Tang dem bo nho cho bytes tho o day
 * TRUOC DAY la 1 LruCache<String, byte[]> RIENG cua class nay; nay da chuyen sang dung chung
 * {@link CoverCache#getRawBytes}/{@link CoverCache#putRawBytes} -- CUNG 1 ngan sach RAM voi
 * Bitmap da decode cua moi nguon khac trong app, chi phan biet bang tien to key ("raw:"), khong
 * con la 1 LruCache doc lap tu tinh ngan sach rieng nua. Class nay gio chi con chiu trach nhiem
 * cho phan CACHE DIA (file .cover duoi thu muc cacheDir) -- phan RAM da giao het cho CoverCache. */
class GDriveCoverDiskCache {

    private static final String DIR_NAME = "gdrive_covers";
    /** Gioi han tong dung luong cache -- du cho vai nghin bia sach nen JPEG co trung binh.
     * Khi vuot nguong, xoa bot file cu nhat theo lastModified() (LRU don gian, khong can cau
     * truc du lieu phuc tap vi viec don dep chi chay khong thuong xuyen). */
    private static final long MAX_CACHE_BYTES = 150L * 1024 * 1024; // 150 MB

    private final File dir;

    GDriveCoverDiskCache(Context context) {
        // Khoi tao CoverCache (idempotent) truoc khi dung -- day thuong la noi DAU TIEN trong
        // luong GDrive OPDS co Context san sang de tinh ngan sach RAM theo getMemoryClass().
        CoverCache.init(context);
        dir = new File(context.getApplicationContext().getCacheDir(), DIR_NAME);
        if (!dir.exists()) dir.mkdirs();
    }

    private File fileFor(String coverFileId) {
        // Drive file id chi gom [a-zA-Z0-9_-] (xem GDrivePublicFileClient.FILE_ID_PATTERN) nen an
        // toan de dung thang lam ten file, khong can hash/sanitize them.
        return new File(dir, coverFileId + ".cover");
    }

    boolean has(String coverFileId) {
        return coverFileId != null && fileFor(coverFileId).isFile();
    }

    /** @return bytes da cache, hoac null neu chua co / loi doc. Khong throw -- cache miss luon
     * duoc coi la chuyen binh thuong, caller tu tai lai tu Drive khi null. */
    byte[] get(String coverFileId) {
        if (coverFileId == null) return null;
        // Kiem tra cache bo nho TRUOC khi dong toi dia -- doc file la I/O, con doc LruCache chi
        // la truy cap bo nho (xem upgrade_1.md muc 3.2). Cache bo nho gio la CoverCache dung
        // chung (xem javadoc class), khong con la MEMORY_CACHE rieng cua class nay nua.
        byte[] cached = CoverCache.getRawBytes(coverFileId);
        if (cached != null) return cached;

        File f = fileFor(coverFileId);
        if (!f.isFile()) return null;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) != -1) off += n;
            // Cap nhat lastModified de LRU danh gia dung muc do "gan day duoc dung", khong chi
            // "gan day duoc ghi".
            f.setLastModified(System.currentTimeMillis());
            CoverCache.putRawBytes(coverFileId, buf);
            return buf;
        } catch (IOException e) {
            return null;
        }
    }

    /** Ghi bytes anh vao cache. An toan de goi tu nhieu thread/coroutine cung luc (ghi file tam
     * roi rename, tranh 1 reader doc phai file dang ghi do). */
    void put(String coverFileId, byte[] bytes) {
        if (coverFileId == null || bytes == null || bytes.length == 0) return;
        File target = fileFor(coverFileId);
        File tmp = new File(dir, coverFileId + ".tmp-" + Thread.currentThread().getId());
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
        } catch (IOException e) {
            Logger.e(e);
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
        }
        // Da co bytes san trong tay -- dua thang vao cache bo nho, khong can doc lai dia lan sau.
        CoverCache.putRawBytes(coverFileId, bytes);
        maybeEvict();
    }

    private void maybeEvict() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= MAX_CACHE_BYTES) return;

        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File f : files) {
            if (total <= MAX_CACHE_BYTES) break;
            long len = f.length();
            if (f.delete()) total -= len;
        }
    }

    /** Xoa toan bo cache -- goi tu Settings neu sau nay co nut "Xoa cache anh bia GDrive"
     * (KHONG bat buoc phai them UI nay trong task hien tai, chi de san API). Chi xoa cac entry
     * "raw:<cover_file_id>" cua CHINH cache nay khoi CoverCache dung chung -- KHONG goi
     * CoverCache.evictAll(), vi cache dung chung con dang giu Bitmap cua nhung nguon khac (local
     * library, man hinh chi tiet...) hoan toan khong lien quan toi thao tac "xoa cache GDrive". */
    void clearAll() {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (name.endsWith(".cover")) {
                CoverCache.removeRawBytes(name.substring(0, name.length() - ".cover".length()));
            }
            f.delete();
        }
    }
}
