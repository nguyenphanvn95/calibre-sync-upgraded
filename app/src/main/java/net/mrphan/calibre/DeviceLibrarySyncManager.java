package net.mrphan.calibre;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;

import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Logic sync 2 chieu giua rootDirectory local va thu muc cloud remote tuong ung (Google Drive /
 * Dropbox / Box / OneDrive / WebDAV / FTP, qua CloudProvider). KHONG duoc goi tu main thread --
 * chi duoc goi tu DeviceLibrarySyncWorker#doWork() (da chay tren luong nen cua WorkManager). Moi
 * phuong thuc public la 1 "phien sync" hoan chinh: resolve thu muc, tai manifest, so sanh,
 * upload/download, ghi lai manifest.
 * <p>
 * Nang cap theo mo hinh readest's file sync engine (xem prompt nang cap Device Library Sync):
 * cau truc remote moi la {@code <remoteRoot>/books/<uuid>/{book.<ext>,cover.<ext>}} (khoa bat
 * bien theo uuid sach thay vi ten hien thi Author/Title mirror cu -- xem RemoteTreeResolver va
 * muc 3.1 cua prompt nang cap), content-hash de bo qua upload thua (Phase 7), tombstone khi xoa
 * sach (Phase 5) -- KEM XOA VAT LY thu muc books/&lt;uuid&gt;/ tren remote ngay khi tombstone
 * (xem deleteRemoteBookFolder(), thay vi doi nguoi dung tu quet "Check" mo coi), merge-before-write
 * chong lost-update (Phase 6), va bounded concurrency pool thay vong for tuan tu (Phase 4/10).
 */
final class DeviceLibrarySyncManager {

    /** So luong upload/download dong thoi toi da trong 1 phien sync -- giong readest's runPool()
     * (xem Phase 4 cua prompt nang cap). Hang so rieng de de chinh/A-B test sau nay. */
    private static final int SYNC_CONCURRENCY = 4;

    private final Context context;
    private final CloudProvider provider;
    private final SyncProgressListener progressListener;
    private final MetadataDatabaseHelper db;
    private final File rootDir;
    private final String driveFolderId;
    private final RemoteTreeResolver treeResolver;

    DeviceLibrarySyncManager(Context context, CloudProvider provider,
                              SyncProgressListener progressListener) throws IOException {
        this.context = context.getApplicationContext();
        this.provider = provider;
        this.progressListener = progressListener;
        this.db = new MetadataDatabaseHelper(this.context);
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this.context);
        this.rootDir = StorageUtils.getRootDirectory(this.context, settings);
        this.treeResolver = new RemoteTreeResolver(provider);
        // Thay GoogleDriveFolderResolver.resolveOrCreateLibraryFolder() cu -- cung logic
        // "nguoi thang xac dinh" (xem muc 2.1, 3.2 cua prompt nang cap) duoc ap dung dong nhat
        // cho ca thu muc thu vien goc lan moi thu muc con ben duoi qua RemoteTreeResolver.
        this.driveFolderId = treeResolver.resolveOrCreateFolder(
                rootDir.getName(), provider.getRoot().getRefId(), rootDir.getName());
    }

    /** Dong bo NHE: chi tien trinh doc/annotation/reading-time
     * (calibre_sync_progress.json), KHONG dung file sach hay SyncManifest -- dung
     * cho nut "Sync" trong Reader va auto-sync theo chu ky (xem
     * prompt-nang-cap-reader-sync.md). Tach y het doan progress cu trong
     * uploadBooks() de khong lap code -- uploadBooks() ben duoi goi thang ham
     * nay thay vi lap lai 3 dong do. */
    void syncProgressOnly() throws IOException {
        SyncProgress progress = SyncProgress.downloadOrEmpty(provider, driveFolderId);
        SyncProgress merged = SyncProgress.mergeLocalInto(db, progress);
        merged.applyRemoteNewerIntoLocal(db);
        merged.uploadTo(provider, driveFolderId);
    }

    /** @param uuidsOrNull null = tat ca sach dang co lpath trong DB. */
    void uploadBooks(String[] uuidsOrNull) throws IOException {
        SyncManifest manifest = SyncManifest.downloadOrEmpty(provider, driveFolderId);

        // Ghi tombstone cho sach da bi xoa chu dich khoi thiet bi nay (lpath = NULL nhung uuid
        // van con trong DB -- xem MetadataDatabaseHelper#getAllUuidsWithNullLpathArray). Chi
        // tombstone entry ma manifest remote DA TUNG biet toi (existing != null) -- tranh ghi
        // tombstone "khong dau khong duoi" cho uuid la rac cuc bo, chua bao gio len cloud. Lam
        // TRUOC vong upload de sach vua xoa khong vo tinh duoc coi la "can upload lai" o duoi
        // (xem Phase 5 cua prompt nang cap).
        for (String uuid : db.getAllUuidsWithNullLpathArray()) {
            SyncManifest.Entry existing = manifest.get(uuid);
            if (existing != null && !existing.isTombstone()) {
                manifest.put(uuid, tombstoneOf(existing));
                // NANG CAP: truoc day chi tombstone manifest va cho nguoi dung tu bam "Check"
                // (RemoteOrphanScanner) de don thu muc books/<uuid>/ con lai tren remote -- ket
                // qua la nguoi dung xoa sach tren thiet bi van thay thu muc "mo coi" nam lai
                // tren Google Drive cho den khi ho chu dong quet don. Gio xoa vat ly luon thu
                // muc do NGAY KHI dong bo phat hien sach da bi xoa cuc bo, thay vi doi buoc quet
                // rieng. Best-effort: loi (mang chap chon, thu muc da bi xoa tay tu truoc...)
                // khong duoc lam hong ca phien sync -- manifest da tombstone o tren van la nguon
                // su that quan trong nhat (chan cac thiet bi khac tai lai sach nay); neu buoc
                // xoa vat ly o day that bai, RemoteOrphanScanner ("Check") van con la luoi an
                // toan de don sau (xem javadoc lop do).
                deleteRemoteBookFolder(uuid);
            }
        }

        String[] uuids = (uuidsOrNull != null) ? uuidsOrNull : db.getAllUuidsWithLpathArray();
        List<String> uuidList = new ArrayList<>(uuids.length);
        for (String u : uuids) uuidList.add(u);

        AtomicInteger doneCounter = new AtomicInteger(0);
        int total = uuidList.size();
        BoundedTaskPool.run(uuidList, SYNC_CONCURRENCY, uuid -> {
            uploadOneBook(uuid, manifest);
            progressListener.onProgress(doneCounter.incrementAndGet(), total);
        }, (uuid, e) -> {
            // Mot sach loi (vi du file bi xoa giua chung, hoac loi mang thoang qua) khong duoc
            // lam chet ca phien sync -- log va tiep tuc sach tiep theo (xem muc 9.2/Phase 4).
            Logger.e(e);
            progressListener.onProgress(doneCounter.incrementAndGet(), total);
        });

        // Progress/comments/annotations/reading-time cua TOAN BO sach (khong chi sach vua chon)
        // luon duoc merge + ghi lai -- file nay nho, re hon nhieu so voi phai theo dung tap con.
        syncProgressOnly();

        // Tai lai remote LAN CUOI (file manifest nho, chi phi thap) va hop nhat truoc khi ghi --
        // chan lost-update khi co thiet bi khac ghi xen giua luc phien nay bat dau va ket thuc
        // (xem Phase 6 cua prompt nang cap). Neu buoc tai lai nay that bai (mang chap chon),
        // chap nhan fallback ve ghi de nhu hanh vi cu thay vi lam fail ca phien sync -- uu tien
        // "sync xong con hon khong" (xem muc 7 "Ghi chu cho reviewer" cua prompt nang cap).
        try {
            SyncManifest freshRemote = SyncManifest.downloadOrEmpty(provider, driveFolderId);
            manifest.mergeRemoteBeforeWrite(freshRemote);
        } catch (IOException e) {
            Logger.e(e);
        }
        manifest.uploadTo(provider, driveFolderId);
    }

    /** Tombstone moi entry con song (chua tombstone) trong manifest co cung lpath nhung khac
     * uuid voi sach vua duoc gan uuid moi -- xem ghi chu FIX o uploadOneBook() o tren. Goi TRUOC
     * khi ghi entry moi cua chinh uuid do vao manifest, de neu upload gian doan giua chung, lan
     * sync sau van tu phat hien lai duoc (existing == null) va thu lai. */
    private static void tombstoneDuplicateLpathEntries(SyncManifest manifest, String lpath,
                                                         String newUuid) {
        for (Map.Entry<String, SyncManifest.Entry> e : manifest.entrySet()) {
            String otherUuid = e.getKey();
            SyncManifest.Entry other = e.getValue();
            if (otherUuid.equals(newUuid)) continue;
            if (other.isTombstone()) continue;
            if (!lpath.equals(other.lpath)) continue;
            Logger.d("Tombstoning stale duplicate manifest entry " + otherUuid
                    + " (same lpath as new uuid " + newUuid + "): " + lpath);
            manifest.put(otherUuid, tombstoneOf(other));
        }
    }

    /** Xoa vat ly thu muc books/&lt;uuid&gt;/ (va toan bo file ben trong -- book.* / cover.*) tren
     * remote, ngay khi phat hien sach da bi xoa cuc bo tren thiet bi nay (xem vong lap tombstone
     * o uploadBooks() o tren). Dung findExistingFolder() (KHONG resolveOrCreateFolder()) o ca 2
     * cap "books" va "<uuid>" -- neu 1 trong 2 chua tung ton tai (sach chua tung upload len
     * remote truoc khi bi xoa, hoac thu muc da bi xoa tay tu truoc), khong co gi de xoa va return
     * ngay, tuyet doi khong duoc VO TINH TAO MOI 1 thu muc rong chi vi kiem tra. Loi mang/provider
     * duoc nuot va log -- xem ghi chu "best-effort" o noi goi. */
    private void deleteRemoteBookFolder(String uuid) {
        try {
            String booksRootId = treeResolver.findExistingFolder(driveFolderId, "books");
            if (booksRootId == null) return;
            String bookFolderId = treeResolver.findExistingFolder(booksRootId, uuid);
            if (bookFolderId == null) return;
            provider.deleteFile(new CloudFile(bookFolderId, uuid, true, 0, 0, false));
            treeResolver.invalidate("/books/" + uuid);
        } catch (IOException e) {
            Logger.e(e);
        }
    }

    private static SyncManifest.Entry tombstoneOf(SyncManifest.Entry prev) {
        long now = System.currentTimeMillis();
        return new SyncManifest.Entry(prev.lpath, prev.coverPath, prev.driveFileId,
                prev.driveCoverFileId, prev.sizeBytes, prev.sourceMtime, prev.uploadedAt,
                prev.contentHash, prev.coverHash, now, now);
    }

    private void uploadOneBook(String uuid, SyncManifest manifest) throws IOException {
        Cursor cs = db.getByUuid(new String[]{"lpath", "cover_path"}, uuid);
        try {
            if (!cs.moveToFirst()) return;
            String lpath = cs.getString(0);
            String coverPath = cs.isNull(1) ? null : cs.getString(1);
            if (lpath == null) return;

            File bookFile = new File(rootDir, lpath);
            if (!bookFile.exists()) return;

            SyncManifest.Entry existing = manifest.get(uuid);

            // FIX (dong bo "server khong khop thu vien thiet bi"): neu day la lan DAU TIEN uuid
            // nay duoc upload (existing == null), co the day khong phai sach that su moi, ma la
            // mot sach CU da tung sync truoc do nhung local SQLite da mat dau vet cua no (vd xoa
            // du lieu ung dung, cai lai app, doi thiet bi...) trong khi file van con nguyen tren
            // dia -- BookCrawler#findNewBooks() khi do coi file la "moi" va cap random UUID KHAC
            // cho no (xem BookCrawler.java). Ket qua: 1 file vat ly duy nhat (1 lpath) lai co
            // NHIEU entry con song (khong tombstone) trong manifest voi UUID khac nhau, tao ra
            // nhieu thu muc books/<uuid>/ "ma" tren remote -- KHONG phai orphan (RemoteOrphanScanner
            // khong bat duoc vi tat ca deu la entry con song hop le), nhung trung lap noi dung.
            // Vi lpath la duong dan tuong doi THAT SU tren dia (khong phai ten hien thi), 2 entry
            // khac uuid nhung cung lpath chac chan tro ve CUNG 1 file -- an toan de tombstone cac
            // entry cu hon, giu lai entry moi nay lam ban ghi song duy nhat cho file do. Nho vay
            // remote hoi tu ve dung so luong sach dang thuc su co tren thiet bi thay vi phinh to
            // dan theo thoi gian moi lan local DB bi mat dau vet.
            if (existing == null) {
                tombstoneDuplicateLpathEntries(manifest, lpath, uuid);
            }

            // Buoc 1 (re): so sanh nhanh bang mtime truoc -- neu mtime KHONG doi so voi lan
            // upload truoc, chac chan noi dung khong doi (gia dinh he thong file khong "giat
            // lui" mtime), bo qua hoan toan buoc doc+hash file (dat, doi voi audiobook hang tram
            // MB). Chi khi mtime da doi moi can tinh hash de xac nhan noi dung THAT SU doi
            // (Phase 7 -- van de #2, muc 2 cua prompt nang cap).
            boolean mtimeChanged = existing == null || existing.sourceMtime < bookFile.lastModified();
            String newHash = existing != null ? existing.contentHash : null;
            boolean needsUpload;
            if (!mtimeChanged) {
                needsUpload = false;
            } else {
                newHash = FileHashUtils.sha256Hex(bookFile);
                needsUpload = existing == null
                        || existing.contentHash == null
                        || !existing.contentHash.equals(newHash);
            }

            String newCoverHash = existing != null ? existing.coverHash : null;
            boolean coverChanged = false;
            File coverFile = coverPath != null ? new File(rootDir, coverPath) : null;
            if (coverFile != null && coverFile.exists()) {
                // Cover danh gia rieng, KHONG con an theo needsUpload cua sach nua (sua van de
                // #11 trong bang muc 2 cua prompt nang cap) -- chi tai lai khi hash cover thuc
                // su doi hoac chua tung co.
                //
                // LUU Y (sai khac co chu dich voi snippet Phase 7 cua prompt): ban goc dung dieu
                // kien "existing.driveCoverFileId == null" de phat hien "cover chua tung upload",
                // nhung Entry moi (v2) luon de driveFileId/driveCoverFileId = null vinh vien (xem
                // ghi chu cuoi Phase 7 cua prompt), nen dieu kien do se LUON dung sau khi sach da
                // migrate -- gay upload lai cover MOI LAN sync, dung nguoc lai muc dich sua loi
                // #11. Dung "existing.coverHash == null" thay the: dung chinh xac dieu kien "chua
                // tung tinh/luu hash cover truoc do" ma khong phu thuoc truong legacy da bi bo.
                String coverHashNow = FileHashUtils.sha256Hex(coverFile);
                coverChanged = existing == null
                        || existing.coverHash == null
                        || !coverHashNow.equals(existing.coverHash);
                newCoverHash = coverHashNow;
            }

            if (needsUpload || coverChanged) {
                String bookFolderId = BackoffRetry.run(
                        () -> treeResolver.resolveFolderChain(driveFolderId, "books", uuid));
                if (needsUpload) {
                    File finalBookFile = bookFile;
                    String bookRemoteName = "book" + extensionOf(lpath);
                    BackoffRetry.run(() -> {
                        treeResolver.writeFile(bookFolderId, bookRemoteName, finalBookFile);
                        return null;
                    });
                }
                if (coverChanged) {
                    File finalCoverFile = coverFile;
                    String coverRemoteName = "cover" + extensionOf(coverPath);
                    BackoffRetry.run(() -> {
                        treeResolver.writeFile(bookFolderId, coverRemoteName, finalCoverFile);
                        return null;
                    });
                }
            }

            long now = System.currentTimeMillis();
            // driveFileId/driveCoverFileId de null vinh vien tu nay -- RemoteTreeResolver#writeFile
            // tu resolve theo ten co dinh (book.<ext>/cover.<ext>) trong books/<uuid>/ moi lan,
            // khong can nho id giua cac phien sync nua (don gian hoa dang ke so voi thiet ke cu,
            // va loai bo han nhom loi "driveFileId cu tro toi file da bi xoa tay" -- xem cuoi
            // Phase 7 cua prompt nang cap).
            manifest.put(uuid, new SyncManifest.Entry(lpath, coverPath, null, null,
                    bookFile.length(), bookFile.lastModified(), now,
                    newHash, newCoverHash, now, null));
        } finally {
            cs.close();
        }
    }

    private static String extensionOf(String path) {
        int idx = path.lastIndexOf('.');
        return idx < 0 ? "" : path.substring(idx);
    }

    /** Tai ve moi sach co trong manifest cloud nhung chua co file local (theo lpath), roi merge
     * tien trinh doc/annotation/reading-time (chieu nguoc lai cua uploadBooks). Bo qua moi entry
     * la tombstone (sach da bi xoa chu dich o mot thiet bi khac -- xem Phase 5 cua prompt nang
     * cap). Ket thuc bang viec KHONG tu goi rescan -- CollectionFragment#observeSyncWork lam
     * viec do sau khi WorkInfo bao SUCCEEDED. */
    void downloadMissingBooks() throws IOException {
        SyncManifest manifest = SyncManifest.downloadOrEmpty(provider, driveFolderId);
        SyncProgress progress = SyncProgress.downloadOrEmpty(provider, driveFolderId);

        // NANG CAP (fix "xoa qua plugin Calibre desktop khong dong bo xuong dien thoai"): ap dung
        // moi tombstone den TU NGUON KHAC (thiet bi Android khac, hoac -- pho bien nhat -- man
        // hinh "Quan ly sach tren Device Library" cua plugin desktop, xem
        // device_sync/manage_ops.py#delete_books ben phia plugin) vao file da co san local, TRUOC
        // khi loc danh sach can tai o duoi -- xem applyRemoteTombstones() va javadoc cua no de
        // biet vi sao buoc nay TUNG BI THIEU hoan toan (khong lien quan gi toi kiem tra lpath luc
        // upload).
        applyRemoteTombstones(manifest);

        List<Map.Entry<String, SyncManifest.Entry>> toProcess = new ArrayList<>();
        for (Map.Entry<String, SyncManifest.Entry> e : manifest.entrySet()) {
            // sach da bi xoa chu dich o mot thiet bi khac -- khong tai lai (xem Phase 5).
            if (!e.getValue().isTombstone()) toProcess.add(e);
        }

        AtomicInteger doneCounter = new AtomicInteger(0);
        int total = toProcess.size();
        BoundedTaskPool.run(toProcess, SYNC_CONCURRENCY, e -> {
            downloadOneBook(e.getKey(), e.getValue());
            progressListener.onProgress(doneCounter.incrementAndGet(), total);
        }, (e, ex) -> {
            // 404 (file bi xoa tay tren remote) hay loi mang thoang qua cho 1 sach khong duoc lam
            // chet ca job -- log va tiep tuc sach tiep theo (xem muc 9.2/Phase 4).
            Logger.e(ex);
            progressListener.onProgress(doneCounter.incrementAndGet(), total);
        });

        progress.applyRemoteNewerIntoLocal(db);
    }

    /** Tai 1 sach (+ cover neu co) con thieu local. Ho tro CA HAI cau truc remote: entry legacy
     * (v1, chua migrate) con driveFileId/driveCoverFileId tro thang toi file trong cay
     * Author/Title mirror cu; entry moi (da qua uploadOneBook() it nhat 1 lan) khong con
     * driveFileId nen phai resolve qua books/<uuid>/ + tien to ten file co dinh "book."/"cover."
     * (xem muc 5 -- lazy migration -- cua prompt nang cap). */
    private void downloadOneBook(String uuid, SyncManifest.Entry entry) throws IOException {
        File localFile = new File(rootDir, entry.lpath);
        boolean needBook = !localFile.exists();
        File localCover = entry.coverPath != null ? new File(rootDir, entry.coverPath) : null;
        boolean needCover = localCover != null && !localCover.exists();
        if (!needBook && !needCover) return;

        if (needBook && entry.driveFileId != null) {
            downloadMirrored(entry.driveFileId, localFile);
            needBook = false;
        }
        if (needCover && entry.driveCoverFileId != null) {
            downloadMirrored(entry.driveCoverFileId, localCover);
            needCover = false;
        }
        if (!needBook && !needCover) return;

        // Phan con lai (hoac toan bo, voi entry da migrate) nam trong cau truc moi
        // books/<uuid>/ -- ten file co dinh "book.<ext>"/"cover.<ext>" nhung phan mo rong khong
        // luu rieng trong manifest, nen list thu muc 1 lan roi khop theo tien to thay vi doan ten
        // day du.
        String bookFolderId = treeResolver.resolveFolderChain(driveFolderId, "books", uuid);
        for (CloudFile f : provider.listFiles(bookFolderId)) {
            if (needBook && f.getName().startsWith("book.")) {
                downloadMirrored(f.getRefId(), localFile);
                needBook = false;
            } else if (needCover && localCover != null && f.getName().startsWith("cover.")) {
                downloadMirrored(f.getRefId(), localCover);
                needCover = false;
            }
            if (!needBook && !needCover) break;
        }
    }

    /** Xoa file sach + cover local (+ dong bo SQLite) cho MOI entry manifest da bi tombstone --
     * khong phan biet tombstone do TU DAU: co the do chinh thiet bi nay tao ra o uploadBooks() o
     * tren (truong hop nay deleteLocalBookIfPresent() se no-op vi lpath da null tu truoc), nhung
     * quan trong nhat la truong hop tombstone den TU NGUON KHAC -- 1 thiet bi Android khac, hoac
     * (thuong gap nhat trong thuc te) man hinh "Quan ly sach tren Device Library" cua plugin
     * Calibre desktop (xem device_sync/manage_ops.py#delete_books ben phia plugin: ham do CHI ghi
     * `deletedAt` len manifest tren Drive, khong bao gio dong cham gi toi may Android ca).
     * <p>
     * TRUOC ban fix nay, hai noi DUY NHAT dung toi isTombstone() trong file nay la: (1) vong lap
     * tombstone trong uploadBooks(), CHI TAO tombstone khi CHINH thiet bi nay xoa sach cuc bo; va
     * (2) vong loc trong downloadMissingBooks(), CHI dung tombstone de BO QUA khong tai lai sach
     * da xoa. Khong co buoc nao thuc su ap dung 1 tombstone (du no den tu dau) bang cach xoa file
     * DA CO SAN local -- nen sach xoa qua plugin desktop van nam nguyen tren dien thoai mai mai,
     * du manifest da dung dan bao "deletedAt". Day la 1 khoang trong tinh nang rieng, KHONG lien
     * quan gi toi co che kiem tra lpath khi upload (tombstoneDuplicateLpathEntries()/dong null-
     * lpath o uploadBooks() -- ca hai chi xu ly chieu nguoc lai, thiet-bi-nay-xoa-thi-bao-len-may-
     * chu, chu khong xu ly chieu nguoc). */
    private void applyRemoteTombstones(SyncManifest manifest) {
        for (Map.Entry<String, SyncManifest.Entry> e : manifest.entrySet()) {
            if (!e.getValue().isTombstone()) continue;
            deleteLocalBookIfPresent(e.getKey());
        }
    }

    /** Xoa file sach + file cover tren dia va don dep dong SQLite (qua db.deleteBook(), giong het
     * CollectionFragment#deleteBookFileAndDb()) cho 1 uuid -- CHI KHI sach do van con ton tai cuc
     * bo (lpath khong null). Neu local da khong co file (chua tung tai ve, hoac chinh thiet bi
     * nay da tu xoa tu truoc) thi khong lam gi -- an toan de goi lai nhieu lan / cho moi tombstone
     * bat ke nguon goc. */
    private void deleteLocalBookIfPresent(String uuid) {
        Cursor cs = db.getByUuid(new String[]{"_id", "lpath"}, uuid);
        try {
            if (!cs.moveToFirst()) return;
            int id = cs.getInt(0);
            String lpath = cs.isNull(1) ? null : cs.getString(1);
            if (lpath == null) return;

            CoverManager.deleteCoverFor(rootDir, lpath);
            new File(rootDir, lpath).delete();
            db.deleteBook(id);
        } finally {
            cs.close();
        }
    }

    /** Giai phong ket noi SQLite rieng ma constructor da mo -- goi tu finally cua
     * DeviceLibrarySyncWorker#doWork(), khong dung chung MetadataDatabaseHelper voi UI thread. */
    void close() {
        db.close();
    }

    private void downloadMirrored(String refId, File destFile) throws IOException {
        File parent = destFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
            throw new IOException("Cannot create parent dir: " + parent);
        }
        CloudFile placeholder = new CloudFile(refId, destFile.getName(), false, 0, 0, false);
        BackoffRetry.run(() -> {
            provider.downloadFile(placeholder, destFile);
            return null;
        });
    }
}
