package net.mrphan.calibre;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Quet thu muc goc thu vien tren cloud remote (driveFolderId cua DeviceLibrarySyncManager) va doi
 * chieu voi calibre_sync_manifest.json de tim cac muc "mo coi": file rac o cap goc (vd cac file
 * sync_progress_out*.json sinh ra do bug cu trong SyncProgress.uploadTo(), da duoc sua -- xem
 * javadoc SyncProgress) va cac thu muc books/&lt;uuid&gt;/ khong con duoc manifest theo doi (uuid
 * khong ton tai, hoac ton tai nhung entry la tombstone). Dung cho nut "Check" trong
 * CloudRemoteInfoBottomSheetFragment.
 * <p>
 * Ngoai orphan "dung nghia", con phat hien them mot loai rac khac: cac entry manifest CON SONG
 * (khong tombstone) nhung TRUNG lpath voi nhau -- xay ra khi local SQLite cua mot thiet bi mat dau
 * vet 1 sach da tung sync (xoa du lieu ung dung, cai lai app...) trong khi file van con nguyen
 * tren dia; BookCrawler#findNewBooks() khi do cap 1 UUID MOI cho file "tuong nhu moi" nay va
 * uploadOneBook() day len thanh 1 thu muc books/&lt;uuid&gt;/ khac, du entry uuid CU van con song
 * trong manifest (xem DeviceLibrarySyncManager#tombstoneDuplicateLpathEntries -- fix chan tai
 * dien tu ban ghi moi tro ve sau, nhung khong tu dong don du lieu trung lap da phat sinh TRUOC do).
 * Loai rac nay KHONG bi orphanBookFolders o tren bat duoc vi ca 2 (hoac nhieu hon) entry deu la
 * "con song hop le" theo dinh nghia rieng le -- chi lo ra khi doi chieu CHEO giua cac entry voi
 * nhau qua lpath. Xem duplicateBookEntries/deleteDuplicates() ben duoi.
 * <p>
 * KHONG duoc goi tu main thread -- moi method o day co the doc/ghi mang qua CloudProvider, giong
 * moi class con lai lam viec voi CloudProvider (xem SyncManifest, SyncProgress).
 */
final class RemoteOrphanScanner {

    private static final String MANIFEST_FILE_NAME = "calibre_sync_manifest.json";
    private static final String PROGRESS_FILE_NAME = "calibre_sync_progress.json";
    private static final String BOOKS_FOLDER_NAME = "books";

    private RemoteOrphanScanner() {
    }

    static final class DuplicateEntry {
        final String uuid;
        final String lpath;
        final CloudFile folder;

        DuplicateEntry(String uuid, String lpath, CloudFile folder) {
            this.uuid = uuid;
            this.lpath = lpath;
            this.folder = folder;
        }
    }

    static final class ScanResult {
        /** Rac cap goc, vd sync_progress_out*.json con lai tu bug cu. */
        final List<CloudFile> orphanTopLevelFiles;
        /** books/<uuid>/ khong con trong manifest (khong ton tai hoac la tombstone). */
        final List<CloudFile> orphanBookFolders;
        /** Entry manifest con song nhung trung lpath voi 1 entry khac moi hon cua CUNG sach --
         * xem javadoc lop ngoai. Entry duoc GIU LAI (moi nhat moi nhom) khong xuat hien o day. */
        final List<DuplicateEntry> duplicateBookEntries;
        /** Tong sizeBytes cua orphanTopLevelFiles -- thu muc orphan chua tinh duoc kich thuoc
         * that su vi khong de quy listFiles ben trong tung books/<uuid>/ (xem javadoc scan()). */
        final long estimatedBytes;

        ScanResult(List<CloudFile> orphanTopLevelFiles, List<CloudFile> orphanBookFolders,
                   List<DuplicateEntry> duplicateBookEntries, long estimatedBytes) {
            this.orphanTopLevelFiles = orphanTopLevelFiles;
            this.orphanBookFolders = orphanBookFolders;
            this.duplicateBookEntries = duplicateBookEntries;
            this.estimatedBytes = estimatedBytes;
        }

        int totalOrphanCount() {
            return orphanTopLevelFiles.size() + orphanBookFolders.size()
                    + duplicateBookEntries.size();
        }
    }

    static final class DeleteSummary {
        final int succeeded;
        final int failed;

        DeleteSummary(int succeeded, int failed) {
            this.succeeded = succeeded;
            this.failed = failed;
        }
    }

    /**
     * Quet thu muc goc thu vien (driveFolderId), doi chieu voi manifest hien co tren remote (tai
     * lai bang SyncManifest.downloadOrEmpty, KHONG dung ban cache trong bo nho vi co the cu -- de
     * tranh xoa nham sach vua duoc thiet bi khac upload). Khong xoa gi ca trong ham nay -- chi tra
     * ve danh sach de UI hoi xac nhan truoc.
     */
    static ScanResult scan(CloudProvider provider, String driveFolderId) throws IOException {
        // 1. Nguon su that: cac uuid con song (khong tombstone) trong manifest moi nhat tren remote.
        SyncManifest manifest = SyncManifest.downloadOrEmpty(provider, driveFolderId);
        Set<String> aliveUuids = new HashSet<>();
        // uuid con song -> Entry, gom rieng de buoc 4 (phat hien trung lpath) khong phai doc lai
        // manifest.entrySet() lan nua.
        Map<String, SyncManifest.Entry> aliveEntries = new HashMap<>();
        for (java.util.Map.Entry<String, SyncManifest.Entry> e : manifest.entrySet()) {
            if (!e.getValue().isTombstone()) {
                aliveUuids.add(e.getKey());
                aliveEntries.put(e.getKey(), e.getValue());
            }
        }

        // 2. Liet ke cap goc: file roi khong phai manifest/progress -> orphanTopLevelFiles; tim
        // thu muc "books" de quet rieng o buoc 3.
        List<CloudFile> orphanTopLevelFiles = new ArrayList<>();
        String booksFolderRefId = null;
        for (CloudFile f : provider.listFiles(driveFolderId)) {
            if (f.isDirectory()) {
                if (BOOKS_FOLDER_NAME.equals(f.getName())) {
                    booksFolderRefId = f.getRefId();
                }
                // Thu muc la la nao khac ngoai "books" (khong mong doi xay ra trong luong hien
                // tai) -- pham vi ban dau khong xu ly, de an toan khong xoa nham.
                continue;
            }
            if (MANIFEST_FILE_NAME.equals(f.getName()) || PROGRESS_FILE_NAME.equals(f.getName())) {
                continue;
            }
            orphanTopLevelFiles.add(f);
        }

        // 3. Trong books/: thu muc con ten uuid khong con song trong manifest -> orphanBookFolders.
        // Ghi lai ca map uuid -> CloudFile cho MOI thu muc con song (kha ca dung 1 lan quet
        // listFiles(), khong goi lai lan 2) de buoc 4 gan folder vat ly cho tung duplicate entry.
        // Khong de quy sau vao ben trong tung thu muc uuid hop le (khong kiem tra book.*/cover.*
        // le te) -- pham vi ban dau chi xu ly dung loai rac da quan sat duoc.
        List<CloudFile> orphanBookFolders = new ArrayList<>();
        Map<String, CloudFile> aliveFoldersByUuid = new HashMap<>();
        if (booksFolderRefId != null) {
            for (CloudFile bookFolder : provider.listFiles(booksFolderRefId)) {
                if (!bookFolder.isDirectory()) continue;
                if (aliveUuids.contains(bookFolder.getName())) {
                    aliveFoldersByUuid.put(bookFolder.getName(), bookFolder);
                } else {
                    orphanBookFolders.add(bookFolder);
                }
            }
        }

        // 4. Trong cac uuid CON SONG: gom nhom theo lpath -- nhieu uuid tro ve CUNG 1 file vat ly
        // tren thiet bi (xem javadoc lop ngoai) la rac trung lap, du khong phai orphan theo dinh
        // nghia buoc 3. Trong moi nhom, giu lai entry moi nhat (updatedAt lon nhat, roi toi
        // uploadedAt neu bang nhau, roi toi uuid de co ket qua on dinh khi thuc su bang nhau het) --
        // cac uuid con lai trong nhom la duplicateBookEntries.
        Map<String, List<String>> uuidsByLpath = new HashMap<>();
        for (Map.Entry<String, SyncManifest.Entry> e : aliveEntries.entrySet()) {
            String lpath = e.getValue().lpath;
            if (lpath == null) continue;
            List<String> uuids = uuidsByLpath.get(lpath);
            if (uuids == null) {
                uuids = new ArrayList<>();
                uuidsByLpath.put(lpath, uuids);
            }
            uuids.add(e.getKey());
        }
        List<DuplicateEntry> duplicateBookEntries = new ArrayList<>();
        for (Map.Entry<String, List<String>> group : uuidsByLpath.entrySet()) {
            List<String> uuids = group.getValue();
            if (uuids.size() < 2) continue;
            String lpath = group.getKey();
            String keepUuid = uuids.get(0);
            for (String candidate : uuids) {
                if (isNewerDuplicate(aliveEntries.get(candidate), aliveEntries.get(keepUuid), candidate, keepUuid)) {
                    keepUuid = candidate;
                }
            }
            for (String uuid : uuids) {
                if (uuid.equals(keepUuid)) continue;
                duplicateBookEntries.add(new DuplicateEntry(uuid, lpath, aliveFoldersByUuid.get(uuid)));
            }
        }

        long estimatedBytes = 0;
        for (CloudFile f : orphanTopLevelFiles) {
            estimatedBytes += f.getSizeBytes();
        }

        return new ScanResult(orphanTopLevelFiles, orphanBookFolders, duplicateBookEntries,
                estimatedBytes);
    }

    /** true neu "candidate" nen thay "currentKeep" lam ban duoc GIU LAI trong 1 nhom trung lpath --
     * uu tien updatedAt lon hon, roi uploadedAt lon hon, roi cuoi cung so sanh chuoi uuid de ket
     * qua on dinh (deterministic) ngay ca khi 2 entry giong het nhau ve thoi gian. */
    private static boolean isNewerDuplicate(SyncManifest.Entry candidate, SyncManifest.Entry currentKeep,
                                             String candidateUuid, String currentKeepUuid) {
        if (candidate.updatedAt != currentKeep.updatedAt) {
            return candidate.updatedAt > currentKeep.updatedAt;
        }
        if (candidate.uploadedAt != currentKeep.uploadedAt) {
            return candidate.uploadedAt > currentKeep.uploadedAt;
        }
        return candidateUuid.compareTo(currentKeepUuid) > 0;
    }

    /**
     * Xoa that su cac muc trong result. Goi tuan tu (KHONG song song) vi mot so CloudProvider (vd
     * FTP) khong thread-safe va de dam bao neu loi giua chung co the bao chinh xac da xoa duoc
     * bao nhieu. Bo qua loi tren 1 muc va tiep tuc voi cac muc con lai, gom loi lai de bao cho
     * nguoi dung, khong duoc dung toan bo qua trinh vi 1 muc loi.
     * <p>
     * @param driveFolderId can cho duplicateBookEntries: khac voi 2 loai orphan con lai (chi can
     * xoa file/thu muc vat ly), don duplicate con phai TOMBSTONE dung entry trong
     * calibre_sync_manifest.json roi ghi lai len remote -- neu khong, entry uuid trung do van
     * "con song" theo manifest va downloadMissingBooks() o thiet bi khac se lai tai no ve, coi
     * nhu chua tung don gi (xem javadoc lop ngoai).
     */
    static DeleteSummary deleteOrphans(CloudProvider provider, String driveFolderId, ScanResult result) {
        int succeeded = 0;
        int failed = 0;
        for (CloudFile f : result.orphanTopLevelFiles) {
            if (deleteOne(provider, f)) succeeded++; else failed++;
        }
        for (CloudFile f : result.orphanBookFolders) {
            if (deleteOne(provider, f)) succeeded++; else failed++;
        }
        if (!result.duplicateBookEntries.isEmpty()) {
            int[] dupResult = deleteDuplicates(provider, driveFolderId, result.duplicateBookEntries);
            succeeded += dupResult[0];
            failed += dupResult[1];
        }
        return new DeleteSummary(succeeded, failed);
    }

    /** Xoa vat ly thu muc books/<uuid>/ (neu con) cua tung duplicate roi tombstone dung entry do
     * trong manifest, sau cung ghi manifest da sua lai len remote 1 LAN DUY NHAT (khong phai moi
     * duplicate 1 lan upload -- manifest nho nhung khong can ghi lai lien tuc). Tai manifest MOI
     * NHAT ngay truoc khi sua (khong dung ScanResult cu, co the da lech neu nguoi dung cho lau
     * truoc khi bam Xoa) de tranh ghi de len thay doi cua thiet bi khac trong luc cho. Tra ve
     * {succeeded, failed} thay vi DeleteSummary de dung noi bo trong deleteOrphans(). */
    private static int[] deleteDuplicates(CloudProvider provider, String driveFolderId,
                                           List<DuplicateEntry> duplicates) {
        int succeeded = 0;
        int failed = 0;
        SyncManifest manifest;
        try {
            manifest = SyncManifest.downloadOrEmpty(provider, driveFolderId);
        } catch (IOException e) {
            Logger.e(e);
            return new int[]{0, duplicates.size()};
        }
        boolean manifestChanged = false;
        for (DuplicateEntry dup : duplicates) {
            boolean folderOk = dup.folder == null || deleteOne(provider, dup.folder);
            SyncManifest.Entry current = manifest.get(dup.uuid);
            if (current != null && !current.isTombstone()) {
                manifest.put(dup.uuid, tombstoneOf(current));
                manifestChanged = true;
            }
            if (folderOk) succeeded++; else failed++;
        }
        if (manifestChanged) {
            try {
                manifest.uploadTo(provider, driveFolderId);
            } catch (IOException e) {
                // Thu muc vat ly co the da xoa thanh cong o tren, nhung manifest chua ghi lai duoc
                // -- coi ca nhom nay la that bai (chua that su "xoa xong": lan Check sau se lai
                // thay entry con song, dan toi thu tombstone lai, an toan/idempotent).
                Logger.e(e);
                return new int[]{0, duplicates.size()};
            }
        }
        return new int[]{succeeded, failed};
    }

    private static SyncManifest.Entry tombstoneOf(SyncManifest.Entry prev) {
        long now = System.currentTimeMillis();
        return new SyncManifest.Entry(prev.lpath, prev.coverPath, prev.driveFileId,
                prev.driveCoverFileId, prev.sizeBytes, prev.sourceMtime, prev.uploadedAt,
                prev.contentHash, prev.coverHash, now, now);
    }

    private static boolean deleteOne(CloudProvider provider, CloudFile f) {
        try {
            provider.deleteFile(f);
            return true;
        } catch (IOException e) {
            Logger.e(e);
            return false;
        }
    }
}
