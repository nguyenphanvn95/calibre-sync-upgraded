package net.mrphan.calibre;

import android.content.Context;

/** Logic dung chung "tai metadata_public.json moi, so sanh chu ky voi ban cache, CHI
 * rebuildFromJson() neu thuc su thay doi" -- tach ra tu
 * GDriveOpdsLibraryListBottomSheet#refreshLibrary() (nut "Lam moi" moi hang trong danh sach thu
 * vien) de GDriveLibraryInfoBottomSheetFragment (nut Sync trong dialog "Library details") dung
 * lai y het, tranh 2 ban logic lech nhau ve sau. PHAI goi tren background thread (network +
 * SQLite I/O). */
class GDriveLibrarySyncHelper {

    static class Result {
        final boolean changed;
        final Exception error;

        private Result(boolean changed, Exception error) {
            this.changed = changed;
            this.error = error;
        }

        static Result unchanged() { return new Result(false, null); }
        static Result updated() { return new Result(true, null); }
        static Result failed(Exception e) { return new Result(false, e); }
    }

    /** db PHAI la ket noi GDriveLibraryDb DANG MO cua dung thu vien nay:
     * - Goi tu GDriveLibraryInfoBottomSheetFragment: dung lai
     *   GDriveOpdsHttpServer.getInstance().getDb() (KHONG tu mo them 1 GDriveLibraryDb khac cho
     *   cung file .db trong khi server dang chay, tranh 2 ket noi ghi/doc cung luc).
     * - Goi tu GDriveOpdsLibraryListBottomSheet: tu mo 1 GDriveLibraryDb tam nhu code hien tai
     *   (khong co server nao dang chay cho hang do). */
    static Result syncIfChanged(Context context, GDriveOpdsLibrary library, GDriveLibraryDb db) {
        try {
            GDrivePublicFileClient client = new GDrivePublicFileClient(new okhttp3.OkHttpClient());
            String raw = client.downloadJson(library.getDriveFileId());
            String newSignature = GDriveLibraryDb.refreshSignatureOf(raw);
            String oldSignature = db.getRefreshSignature();
            boolean unchanged = oldSignature != null && oldSignature.equals(newSignature);
            if (!unchanged) {
                db.rebuildFromJson(raw, library.getDriveFileId());
                library.setCachedBookCount(db.getBookCount());
            }
            // Cap nhat lastSyncedAtMillis du noi dung co doi hay khong, bam Sync la 1 hanh
            // dong "da kiem tra", "Dong bo lan cuoi" trong dialog nen phan anh dung lan KIEM TRA
            // gan nhat, khong chi lan THAY DOI gan nhat.
            library.setLastSyncedAtMillis(System.currentTimeMillis());
            GDriveOpdsLibraryStore.save(context, library);
            return unchanged ? Result.unchanged() : Result.updated();
        } catch (Exception e) {
            Logger.e(e);
            return Result.failed(e);
        }
    }
}
