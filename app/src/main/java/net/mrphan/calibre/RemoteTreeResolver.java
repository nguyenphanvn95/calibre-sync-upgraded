package net.mrphan.calibre;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolve/tao thu muc va tim file theo ten tren bat ky CloudProvider nao (Drive, Dropbox, Box,
 * OneDrive, WebDAV, FTP deu dung chung logic nay qua interface CloudProvider). Thay the
 * GoogleDriveFolderResolver + logic resolveMirroredFolder/findOrCreateChildFolder cu trong
 * DeviceLibrarySyncManager.
 * <p>
 * Diem khac biet quan trong so voi ban cu: khi listFiles() tra ve NHIEU muc cung ten (do 2
 * thiet bi race nhau tao gan nhu dong thoi lan dau sync -- xem muc 2.1 cua prompt nang cap),
 * luon CHON ID/refId NHO NHAT theo thu tu tu dien lam ban chinh thuc thay vi lay phan tu dau
 * tien listFiles() tra ve (thu tu khong dam bao on dinh giua cac lan goi). Nho vay ca 2 thiet
 * bi, o lan sync KE TIEP, deu hoi tu ve cung 1 thu muc/file ma khong can khoa phan tan --
 * chuyen the tu findChild()/resolveOrCreateFolder() cua GoogleDriveProvider.ts (readest).
 * <p>
 * idCache song trong suot 1 phien DeviceLibrarySyncManager (khong ben vung qua cac lan sync)
 * -- danh doi hop ly: giam manh so round-trip trong 1 phien, nhung khong co nguy co du lieu cu
 * tu phien truoc lam sai lech phien sau.
 */
final class RemoteTreeResolver {

    private final CloudProvider provider;
    /** ConcurrentHashMap (khong phai HashMap thuong) -- vi RemoteTreeResolver dung CHUNG 1
     * instance cho ca phien sync, va Phase 4 (BoundedTaskPool) goi resolveOrCreateFolder()/
     * writeFile() tu toi da SYNC_CONCURRENCY luong CUNG LUC tren cung instance nay. HashMap
     * thuong bi mutate dong thoi tu nhieu luong co the corrupt cau truc noi bo (vong lap vo han
     * khi resize) -- day la loi tiem an nghiem trong da duoc phat hien va sua khi tich hop Phase
     * 3 (resolver) voi Phase 4 (concurrency pool), khong co trong ban nhap goc cua tung phase
     * rieng le. */
    private final Map<String, String> idCache = new ConcurrentHashMap<>();

    RemoteTreeResolver(CloudProvider provider) {
        this.provider = provider;
    }

    /** Tim con truc tiep ten "name" trong thu muc "parentRefId". Neu co nhieu ket qua trung
     * ten, tra ve refId nho nhat theo thu tu tu dien (deterministic winner). Null neu khong
     * tim thay.
     * <p>
     * FIX: so khop THU MUC (directoryOnly=true) gio TRIM khoang trang thua + KHONG phan biet
     * hoa/thuong, thay vi String#equals tuyet doi truoc day. Ly do: ten thu muc goc tren remote
     * duoc ghep tu 2 nguon DOC LAP khong bao gio thuc su dam bao khop ky tu-doi-ky tu -- ben
     * Calibre desktop la 1 o nhap text nguoi dung tu go ("Cloud Sync Folder Name" trong Settings
     * cua plugin), ben Android la ten that cua thu muc luu tru cuc bo tren may (rootDir.getName()).
     * Chi can lech 1 khoang trang thua o dau/cuoi hoac 1 chu hoa/thuong (vd "eBooks" vs "ebooks "),
     * String#equals cu se coi la 2 thu muc HOAN TOAN KHAC NHAU -- Android se tao rieng 1 thu muc
     * goc MOI, RONG, va se khong bao gio thay duoc calibre_sync_manifest.json ma may/plugin kia da
     * ghi, du sach thuc su da upload thanh cong len Drive (dung "thu muc goc" theo y nguoi dung,
     * chi khac nhau ve chuoi ky tu). Day la nguyen nhan hop ly nhat cho trieu chung "sach da upload
     * xong, manifest co entry con song, nhung dien thoai van khong thay sach". Voi FILE (book.<ext>,
     * cover.jpg, calibre_sync_manifest.json, calibre_sync_progress.json) van giu String#equals tuyet
     * doi nhu cu -- day la cac ten CO DINH ca 2 phia deu tu sinh ra giong het nhau (khong phai do
     * nguoi dung go tay), khong can noi long. */
    private String findChild(String parentRefId, String name, boolean directoryOnly) throws IOException {
        List<String> matches = new ArrayList<>();
        for (CloudFile f : provider.listFiles(parentRefId)) {
            boolean nameMatches = directoryOnly
                    ? namesMatchLoosely(name, f.getName())
                    : name.equals(f.getName());
            if (nameMatches && (!directoryOnly || f.isDirectory())) {
                matches.add(f.getRefId());
            }
        }
        if (matches.isEmpty()) return null;
        Collections.sort(matches);
        return matches.get(0);
    }

    /** So sanh ten thu muc "long tay" -- trim khoang trang thua 2 dau + so khop khong phan biet
     * hoa/thuong. Dung rieng cho thu muc (xem javadoc findChild() o tren); an toan voi ten tieng
     * Viet co dau vi String#equalsIgnoreCase da so sanh theo tung ky tu Unicode, khong chi ASCII. */
    private static boolean namesMatchLoosely(String a, String b) {
        if (a == null || b == null) return false;
        return a.trim().equalsIgnoreCase(b.trim());
    }

    /** Resolve-hoac-tao 1 thu muc con "name" trong "parentRefId", cache theo "cacheKey" (thuong
     * la duong dan logic day du, vd "books/3f2e-uuid"). Sau khi tao, TRUY VAN LAI (findChild)
     * de chon nguoi thang xac dinh -- neu 1 thiet bi khac vua tao trung ten trong luc ham nay
     * dang chay, ca 2 phien deu se hoi tu ve cung 1 id thay vi giu id "cua rieng minh". */
    String resolveOrCreateFolder(String cacheKey, String parentRefId, String name) throws IOException {
        String cached = idCache.get(cacheKey);
        if (cached != null) return cached;

        String found = findChild(parentRefId, name, true);
        if (found == null) {
            provider.createFolder(parentRefId, name);
            // Doc lai thay vi tin refId ma createFolder() vua tra ve -- de tu chua lanh khi co
            // race (xem javadoc class). CloudProvider.createFolder() da idempotent voi loi 409
            // (Box/Dropbox) nen goi lai o day khong tao du/tao trung them lan nua.
            found = findChild(parentRefId, name, true);
        }
        if (found == null) {
            // Van khong tim thay sau khi tao (vd provider bao thanh cong nhung listFiles() chua
            // kip nhat quan - eventual consistency o mot so backend WebDAV/FTP hiem gap) -- KHONG
            // duoc goi idCache.put(cacheKey, null): khac voi HashMap, ConcurrentHashMap nem
            // NullPointerException voi gia tri null (day la khac biet quan trong so voi HashMap
            // ban goc, phat sinh tu viec doi sang ConcurrentHashMap de an toan da luong o Phase
            // 4). Khong cache ket qua that bai nay -- lan resolve tiep theo (item khac trong cung
            // pool, hoac phien sync sau) se thu lai tu dau thay vi ket "khong tim thay" mai mai.
            throw new IOException("Could not resolve or create folder '" + name + "' under " + parentRefId);
        }
        idCache.put(cacheKey, found);
        return found;
    }

    /** Resolve toan bo chuoi thu muc con theo danh sach ten, tao thieu neu can. Vd segments =
     * ["books", "3f2e-uuid"] se tao/tim lan luot 2 cap thu muc con duoi root. */
    String resolveFolderChain(String rootRefId, String... segments) throws IOException {
        String parentId = rootRefId;
        StringBuilder key = new StringBuilder();
        for (String segment : segments) {
            key.append('/').append(segment);
            parentId = resolveOrCreateFolder(key.toString(), parentId, segment);
        }
        return parentId;
    }

    /** Upload-hoac-ghi-de file "localFile" voi ten CO DINH "fileName" trong thu muc
     * "folderRefId" (ten co dinh nay co the khac ten file tam local -- vd "book.epub" thay vi
     * ten file tam Android sinh ra). Neu da co file cung ten (kiem qua findChild, khong phai
     * qua id luu trong manifest -- de tu chua lanh giong resolveOrCreateFolder), ghi de bang
     * updateFileContent giu nguyen refId; neu chua co, tao moi voi ten "fileName". Tra ve refId
     * cuoi cung — caller co the luu lai de tham khao nhung KHONG bat buoc phai luu ben vung, vi
     * lan sync sau van tu resolve dung qua ten. */
    String writeFile(String folderRefId, String fileName, java.io.File localFile) throws IOException {
        String existing = findChild(folderRefId, fileName, false);
        if (existing != null) {
            return provider.updateFileContent(existing, localFile);
        }
        return provider.uploadFile(localFile, folderRefId, fileName);
    }

    /** Tim thu muc con "name" trong "parentRefId" NEU DA TON TAI, KHONG tao moi neu chua co --
     * dung cho cac thao tac chi-doc/xoa (vd DeviceLibrarySyncManager xoa thu muc books/&lt;uuid&gt;/
     * khi sach bi xoa cuc bo tren thiet bi, xem deleteRemoteBookFolder()) ma khong duoc phep vo
     * tinh tao ra 1 thu muc rong moi chi vi kiem tra su ton tai. Tra ve null neu khong tim thay.
     * Khong dong bo voi idCache -- idCache chi phuc vu resolveOrCreateFolder() (ket qua "chac
     * chan ton tai, co the tao"), con 1 lookup chi-doc nhu the nay khong nen "ket dinh" mot
     * cacheKey ma cac lan resolveOrCreateFolder() sau co the ky vong khac. */
    String findExistingFolder(String parentRefId, String name) throws IOException {
        return findChild(parentRefId, name, true);
    }

    void invalidate(String cacheKey) {
        idCache.remove(cacheKey);
    }
}
