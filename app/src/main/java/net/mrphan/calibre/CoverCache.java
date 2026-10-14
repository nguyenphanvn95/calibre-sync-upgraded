package net.mrphan.calibre;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.util.LruCache;

/**
 * Tang cache anh bia DUY NHAT trong bo nho, dung chung cho MOI nguon anh bia trong app (local
 * library scan, BLOB SQLite usbms, GDrive OPDS, Content Server OPDS thong thuong, man hinh chi
 * tiet sach, hieu ung mo/dong sach cua reader...).
 * <p>
 * TRUOC DAY (xem upgrade_3.md "3 he thong cache cover anh rieng biet"): moi nguon anh tu quan ly
 * 1 LruCache rieng cua no -- {@code CoverBitmapCache} (Bitmap da decode, dung boi
 * BookCursorAdapter/BookRecyclerAdapter/BookDetailActivity/DeviceBookDetailActivity/
 * BookOpenCloseDoorEffect) va {@code GDriveCoverDiskCache} (LruCache<String,byte[]> RIENG cho
 * bytes tho JPEG cua GDrive, ben canh lop cache dia cua chinh no). 2 LruCache doc lap nay MOI CAI
 * tu tinh ngan sach bo nho rieng (theo {@code Runtime.getRuntime().maxMemory() / N}) -- tong dung
 * luong RAM thuc te app danh cho cache anh bia vi vay VUOT QUA muc dinh, ma khong co 1 cho duy
 * nhat de biet/gioi han TONG dung luong dang dung cho anh bia. Gop lai day du:
 * <ul>
 *   <li>1 LruCache CHUNG duy nhat, kich thuoc tinh theo {@link ActivityManager#getMemoryClass()}
 *   (khuyen nghi chuan cua Android, on dinh hon giua cac thiet bi so voi doc thang
 *   Runtime.maxMemory() -- xem developer.android.com/topic/performance/graphics/cache-bitmap),
 *   thay vi moi lop tu doc Runtime.maxMemory() rieng.</li>
 *   <li>Phan biet NGUON du lieu bang TIEN TO cua key (Bitmap giu nguyen key cu tung nguon dang
 *   dung, bytes tho GDrive dung tien to {@code "raw:"} -- xem {@link #rawBytesKey}), khong con
 *   phan biet bang class/instance LruCache rieng nua -- dung 1
 *   entry co the la {@code Bitmap} (da decode, san sang de setImageBitmap) hoac {@code byte[]}
 *   (bytes tho JPEG chua decode, dung de GDriveOpdsHttpServer phuc vu HTTP response ma khong can
 *   decode). sizeOf() tinh dung dung luong thuc te cho ca 2 loai.</li>
 * </ul>
 * Loi ich: 1 Activity#onTrimMemory/evictAll() duy nhat don sach TOAN BO cache anh bia (khong con
 * canh bao "quen mat 1 trong 2 cache khi xu ly memory pressure"); 1 ngan sach RAM duy nhat nen
 * anh "nong" (moi xem gan day) tu MOI nguon deu co co hoi song sot nhu nhau thay vi moi nguon co
 * 1 ngan sach nho rieng de nhau chen (vd luot nhieu thu vien GDrive lien tuc khong con lam "day"
 * rieng phan cache byte tho ma khong anh huong toi phan Bitmap da decode cua local library, vi
 * gio ca 2 cung tranh chap chung 1 ngan sach theo LRU thuc su -- dung y nghia "least recently
 * used" tren toan bo anh bia dang duoc xem, khong chi trong tung nguon rieng le).
 */
public final class CoverCache {

    private static final Object INIT_LOCK = new Object();
    private static volatile LruCache<String, Object> CACHE;

    /** Fallback khi khong lay duoc ActivityManager (rat hiem, vd context bi mock trong test) --
     * dung dung gia tri Android mac dinh cho thiet bi "small heap" thay vi crash. */
    private static final int FALLBACK_MEMORY_CLASS_MB = 64;

    private CoverCache() {}

    /** Goi truoc lan dung dau tien, an toan de goi lai nhieu lan tu nhieu Activity/Adapter khac
     * nhau (idempotent -- chi lan goi dau tien thuc su khoi tao). Khong bat buoc phai co 1
     * Application#onCreate() rieng chi de goi ham nay: du an nay chua co custom Application class
     * (xem AndroidManifest.xml), nen moi noi dung CoverCache (BookCursorAdapter,
     * BookRecyclerAdapter, BookDetailActivity, DeviceBookDetailActivity, BookOpenCloseDoorEffect,
     * GDriveCoverDiskCache) deu tu goi init() truoc, voi Context san co ngay tai cho. */
    public static void init(Context context) {
        if (CACHE != null) return;
        synchronized (INIT_LOCK) {
            if (CACHE != null) return;
            int memoryClassMb = FALLBACK_MEMORY_CLASS_MB;
            try {
                ActivityManager am = (ActivityManager)
                        context.getApplicationContext().getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) memoryClassMb = am.getMemoryClass();
            } catch (Exception ignored) {
                // Giu fallback o tren -- khoi tao cache van phai thanh cong du getMemoryClass()
                // that bai vi ly do gi do, khong de app crash chi vi tinh sai kich thuoc cache.
            }
            // 1/8 memoryClass, giong ty le CoverBitmapCache cu tung dung voi maxMemory() (khuyen
            // nghi chuan cua Android cho cache bitmap trong bo nho).
            int cacheSizeKb = (memoryClassMb * 1024) / 8;
            CACHE = new LruCache<String, Object>(cacheSizeKb) {
                @Override
                protected int sizeOf(String key, Object value) {
                    if (value instanceof Bitmap) {
                        return ((Bitmap) value).getByteCount() / 1024;
                    }
                    if (value instanceof byte[]) {
                        return ((byte[]) value).length / 1024;
                    }
                    return 0;
                }
            };
        }
    }

    /** Truy cap cache truoc khi init() da chay (ly thuyet khong nen xay ra vi moi caller deu tu
     * init() truoc, nhung phong ho: tao voi fallback size ngay tai day thay vi NPE). */
    private static LruCache<String, Object> cache() {
        LruCache<String, Object> c = CACHE;
        if (c == null) {
            throw new IllegalStateException(
                    "CoverCache.init(context) chua duoc goi -- goi truoc khi dung get/put.");
        }
        return c;
    }

    // ---- Bitmap da decode (thay the CoverBitmapCache cu) -----------------------------------
    // Dung boi: BookCursorAdapter/BookRecyclerAdapter (list local library), BookDetailActivity/
    // DeviceBookDetailActivity (man hinh chi tiet), BookOpenCloseDoorEffect (hieu ung mo/dong
    // sach cua reader). Key giu NGUYEN quy uoc cu (xem tung noi goi): "file:<duong dan>",
    // "blob:<uuid>", "detail-blob:<uuid>", hoac duong dan tuyet doi file cover cho hieu ung reader.

    public static Bitmap getBitmap(String key) {
        if (key == null) return null;
        Object v = cache().get(key);
        return v instanceof Bitmap ? (Bitmap) v : null;
    }

    public static void putBitmap(String key, Bitmap bitmap) {
        if (key == null || bitmap == null) return;
        cache().put(key, bitmap);
    }

    // ---- Bytes tho (thay the LruCache<String,byte[]> rieng cu trong GDriveCoverDiskCache) --
    // Dung boi: GDriveCoverDiskCache, phuc vu bytes JPEG tho cho GDriveOpdsHttpServer (KHONG
    // decode Bitmap o tang nay -- xem javadoc GDriveCoverDiskCache). Tien to "raw:" dam bao
    // KHONG BAO GIO trung key voi Bitmap da decode cua chinh cover_file_id do (2 dang du lieu
    // khac nhau cho cung 1 anh, ca 2 co the cung ton tai trong cache mot luc, vd 1 anh GDrive vua
    // duoc OPDS server phuc vu bytes tho VA vua duoc GDriveOpdsLibraryWebViewActivity hien thi
    // Bitmap decode).

    private static String rawBytesKey(String coverFileId) {
        return "raw:" + coverFileId;
    }

    public static byte[] getRawBytes(String coverFileId) {
        if (coverFileId == null) return null;
        Object v = cache().get(rawBytesKey(coverFileId));
        return v instanceof byte[] ? (byte[]) v : null;
    }

    public static void putRawBytes(String coverFileId, byte[] bytes) {
        if (coverFileId == null || bytes == null) return;
        cache().put(rawBytesKey(coverFileId), bytes);
    }

    /** Xoa 1 entry bytes-tho don le -- dung khi GDriveCoverDiskCache.clearAll() xoa cache dia
     * cua rieng no va can dong bo xoa luon phan bo nho tuong ung, MA KHONG duoc evictAll() toan
     * bo CoverCache (se xoa nham ca Bitmap dang cache cua local library/man hinh chi tiet, hoan
     * toan khong lien quan gi toi "xoa cache GDrive"). */
    public static void removeRawBytes(String coverFileId) {
        if (coverFileId == null) return;
        LruCache<String, Object> c = CACHE;
        if (c != null) c.remove(rawBytesKey(coverFileId));
    }

    /** Goi khi memory pressure cao (vd Activity#onTrimMemory) -- don sach cho MOI nguon anh bia
     * cung luc (truoc day phai goi rieng CoverBitmapCache.evictAll() VA tu lam trong voi
     * GDriveCoverDiskCache, de sot 1 trong 2 la loi thuong gap). */
    public static void evictAll() {
        LruCache<String, Object> c = CACHE;
        if (c != null) c.evictAll();
    }

    public static void trimToSize(int maxSizeKb) {
        LruCache<String, Object> c = CACHE;
        if (c != null) c.trimToSize(maxSizeKb);
    }
}
