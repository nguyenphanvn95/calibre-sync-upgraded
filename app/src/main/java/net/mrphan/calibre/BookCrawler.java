package net.mrphan.calibre;

import android.annotation.SuppressLint;
import android.database.Cursor;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Created by sengjea on 1/14/14.
 */
@SuppressLint("NewApi")
public class BookCrawler {
    private MetadataDatabaseHelper mDB;
    private File rootDirectory;
    private JSONObject driveInfo;
    private ArrayList<JSONObject> newBooks;
    private Thread metadata_thread;
    private String[] mExtensions;
    // NOTE: writeMetadataFile() used to guard itself with a private metadataFileLock. It now
    // synchronizes on MetadataFileIo.LOCK instead, the same lock DeviceBookMetadataWriter uses
    // when patching a single entry (Hiệu đính metadata) -- see MetadataFileIo's javadoc for why
    // sharing one lock across both classes matters.

    /** Callback reporting that a scanFolder() run has finished, so the UI (Device Library) can
     * refresh and report how many new books were found. */
    public interface ScanListener {
        void onScanFinished(int newBooksFound);
    }

    private ScanListener mScanListener;

    public void setScanListener(ScanListener listener) {
        mScanListener = listener;
    }

    private Runnable metadataRunnable = new Runnable() {
        public void run() {
            try {
                if (rootDirectory == null) return;
                parseExistingMetadataFile();
                parseExistingDriveInfoFile();
                findNewBooks(rootDirectory);
                fillMissingCovers();      // NEW: requirement #1 -- backfill cover_path for
                                           // books (old or just-found) that still lack one
                clearDeletedBooks();
                cleanupOrphanCoverFiles(); // NEW: requirement #3 -- prune covers/ of images no
                                           // longer referenced by any book, now that
                                           // clearDeletedBooks() has updated the DB
                writeMetadataFile();

            } catch (Throwable e) {
                Logger.e(e);
            } finally {
                if (mScanListener != null) {
                    mScanListener.onScanFinished(numNewBooks());
                }
            }

        }

    };
    public BookCrawler(MetadataDatabaseHelper db, String[] exts) {
        mDB = db;
        mExtensions = exts;
        newBooks = new ArrayList<JSONObject>();
        driveInfo = null;
    }
    public boolean isCrawling() {
        return (metadata_thread != null) && metadata_thread.isAlive();
    }

    public void waitForScan() {
        try {
            if (isCrawling()) metadata_thread.join();
        } catch (InterruptedException e) {
            Logger.e(e);
        }
    }

    public JSONObject getDriveInfo() {
        return (driveInfo != null ? driveInfo : new JSONObject());
    }
    public void setDriveInfo(JSONObject dI) {
        if (isCrawling()) return;
        driveInfo = dI;
        if (rootDirectory != null && dI != null) {
            File f = new File(rootDirectory, "driveinfo.calibre");
            writeToFile(new StringBuilder(driveInfo.toString()), f, false);
        }
    }
    private void parseExistingMetadataFile() throws JSONException {
        JSONArray tmp_metadata;
        File cf = new File(rootDirectory, "metadata.calibre");
        if (cf.exists()) {
            tmp_metadata = new JSONArray(readFromFile(cf).toString());

            for (int i = 0; i < tmp_metadata.length(); i++) {
                // One malformed entry (missing/invalid field, bad thumbnail, etc.) used to
                // throw out of this loop and, since parseExistingMetadataFile() itself has no
                // catch and its only caller (metadataRunnable) wraps the *entire* scan in one
                // catch(Throwable), abort findNewBooks()/fillMissingCovers()/writeMetadataFile()
                // for every other book too. Isolate each entry so a single bad row just gets
                // skipped instead of silently killing the whole crawl.
                try {
                    JSONObject tmp_json = tmp_metadata.getJSONObject(i);
                    if ((new File(rootDirectory, tmp_json.getString("lpath"))).exists()) {
                        mDB.insertJSON(tmp_json);
                    }
                } catch (JSONException e) {
                    Logger.d("parseExistingMetadataFile: skipping malformed entry at index " + i + ": " + e);
                }
            }
        }
    }
    private void parseExistingDriveInfoFile() {
        File cf = new File(rootDirectory, "driveinfo.calibre");
        if (cf.exists()) {
            String di = readFromFile(cf).toString();
            try {
                driveInfo = new JSONObject(di);
            } catch (JSONException e) {
                Logger.d("JSONException while reading dI: " + di);
            }
        }
    }
    private void clearDeletedBooks() {
        Cursor cs;
        cs = mDB.getNotNull(new String[]{"_id", "lpath"}, "lpath");
        cs.moveToFirst();
        while (!cs.isAfterLast()) {
            String lpath = cs.getString(1);
            if (!new File(rootDirectory, lpath).exists()) {
                CoverManager.deleteCoverFor(rootDirectory, lpath); // NEW: drop its cached cover too
                mDB.deleteBook(cs.getInt(0));
                Logger.d("Deleted " + lpath);
            }
            cs.moveToNext();
        }
        cs.close();
    }

    /** Requirement #1: for every book in the DB that has an lpath but no cover_path yet --
     * either just added by findNewBooks() (which doesn't set cover_path even when it did manage
     * to save one right away, see below) or one that predates this feature entirely -- re-sniff
     * the file for a cover and cache it to disk + record it in the DB. Runs on metadata_thread
     * (this whole Runnable already does), never on the main/UI thread. */
    private void fillMissingCovers() {
        Cursor cs = mDB.getBooksMissingCover();
        int scanned = 0, found = 0;
        try {
            while (cs.moveToNext()) {
                String uuid = cs.getString(0);
                String lpath = cs.getString(1);
                File bookFile = new File(rootDirectory, lpath);
                if (!bookFile.exists()) continue;
                try {
                    BookMetadataSniffer.Result sniffed = BookMetadataSniffer.sniff(bookFile);
                    if (sniffed.coverBytes != null
                            && CoverManager.saveCover(rootDirectory, lpath, sniffed.coverBytes)) {
                        mDB.updateCoverPath(uuid, CoverManager.relativeCoverPathFor(lpath));
                        found++;
                    }
                } catch (Exception e) {
                    Logger.e(e);
                }
                scanned++;
                // First-run backfill on a large existing library can touch thousands of files;
                // this stays on the background thread for as long as it takes (no cap), but log
                // progress periodically so it's possible to tell it's still working via logcat.
                if (scanned % 100 == 0) {
                    Logger.d("fillMissingCovers progress: " + scanned + " scanned, " + found + " covers saved so far");
                }
            }
        } finally {
            cs.close();
        }
        if (scanned > 0) {
            Logger.d("fillMissingCovers finished: " + scanned + " scanned, " + found + " covers saved");
        }
    }

    /** Requirement #3: remove any cached cover file that no book in the DB references anymore
     * (its source book was deleted outside the app, renamed, or its lpath otherwise changed).
     * Runs AFTER clearDeletedBooks() so the set of cover_path values read from the DB already
     * reflects books removed earlier in this same scan. */
    private void cleanupOrphanCoverFiles() {
        java.util.Set<String> knownCoverPaths = mDB.getAllCoverPaths();
        int removed = CoverManager.cleanupOrphanCovers(rootDirectory, knownCoverPaths);
        if (removed > 0) Logger.d("Cleaned up " + removed + " orphan cover file(s)");
    }

    /** Overwrites metadata.calibre from the current SQLite data -- not an append, the same way
     * Calibre desktop re-syncs its entire device cache on every change instead of patching parts
     * of it. */
    public void writeMetadataFile() {
        if (rootDirectory == null) return;
        synchronized (MetadataFileIo.LOCK) {
            JSONArray all = mDB.exportAllBooksAsJSON();
            File cf = new File(rootDirectory, "metadata.calibre");
            writeToFile(new StringBuilder(all.toString()), cf, false);
        }
    }

    private void findNewBooks(File dir) {
        JSONObject new_book;
        if (!dir.toString().startsWith(rootDirectory.toString())) return;
        // Never descend into <rootDirectory>/covers/ -- it holds cached cover images (see
        // CoverManager), not books. mExtensions is very unlikely to ever contain ".jpg", but
        // excluding this folder by name explicitly guarantees it, even if the user later adds
        // ".jpg" to the accepted-extensions list for loose-image comic/manga folders.
        if (dir.equals(new File(rootDirectory, CoverManager.COVERS_DIR_NAME))) return;

        for (File f : dir.listFiles()) {
            if (f.isDirectory()) {
                findNewBooks(f);
            } else if (fileHasAcceptedExtension(f)) {
                String filename = f.toString().substring(rootDirectory.toString().length() + 1);
                Cursor cs = mDB.getByPath(new String[]{"uuid"}, filename);
                if (!cs.moveToFirst()) {
                    try {
                        BookMetadataSniffer.Result sniffed = BookMetadataSniffer.sniff(f);
                        String uuid = java.util.UUID.randomUUID().toString();

                        // Save the cover right away if the sniffer found one, so a newly-added
                        // book already has a cover in this same scan pass instead of waiting for
                        // the next one's fillMissingCovers() to pick it up.
                        String coverPath = null;
                        if (sniffed.coverBytes != null
                                && CoverManager.saveCover(rootDirectory, filename, sniffed.coverBytes)) {
                            coverPath = CoverManager.relativeCoverPathFor(filename);
                        }

                        new_book = new JSONObject()
                                .put("_new_book_", true)
                                .put("uuid", uuid)
                                .put("lpath", filename)
                                .put("title", sniffed.title)
                                .put("authors", new JSONArray().put(sniffed.author));
                        Logger.d("New Book Found: " + f.toString());
                        newBooks.add(new_book);

                        // Write straight into SQLite so CollectionFragment shows this book
                        // without waiting for a Calibre desktop connection -- this is the main
                        // behavior change from before (which only kept it in newBooks, waiting
                        // to report it to Calibre desktop).
                        mDB.insertMetadata(uuid, filename, sniffed.author, sniffed.title, null, null, coverPath);

                        // BookMetadataSniffer above only reads dc:title/dc:creator(first)/cover --
                        // by design, to keep the scan fast (see its class comment). For EPUB we
                        // already have a full OPF reader (EpubMetadataOpfReader, otherwise only
                        // used by the "Get metadata from ebook" editor action) that can fill in
                        // everything BookMetadataSniffer left out -- publisher/pubdate/series/
                        // tags/languages/identifiers/comments/co-authors/custom columns -- from
                        // the SAME content.opf. Without this, books added here (which is every
                        // new-book path: Device Library Sync download, manual "+" import, and
                        // files copied in by hand) end up with only a bare title+author+cover
                        // even though the source EPUB itself carries full Calibre metadata.
                        if (filename.toLowerCase(java.util.Locale.ROOT).endsWith(".epub")) {
                            enrichNewEpubMetadata(uuid, f, sniffed);
                        }
                    } catch (JSONException e) {
                        Logger.d("Could not add Book: " + f.toString());
                    }
                }
                cs.close();
            }
        }

    }

    /** Lấp đầy các field OPF chuẩn mà BookMetadataSniffer (dùng trong findNewBooks() để quét
     * nhanh) không đọc: publisher/pubdate/series/series_index/tags/languages/identifiers/
     * comments/đồng-tác-giả/custom columns. Chỉ gọi cho EPUB (định dạng duy nhất có content.opf
     * chuẩn -- xem EpubMetadataOpfReader's class comment). Không bao giờ ném exception ra ngoài
     * findNewBooks(): 1 file OPF hỏng/không đọc được không được làm hỏng cả lượt quét, sách vẫn
     * giữ nguyên title/author/cover đã insertMetadata() ở trên như trước khi có method này.
     * @param sniffed kết quả BookMetadataSniffer đã có -- dùng làm fallback title/author nếu
     *                EpubMetadataOpfReader không đọc được field tương ứng (ví dụ .opf thiếu
     *                dc:title, dù hiếm). */
    private void enrichNewEpubMetadata(String uuid, File epubFile, BookMetadataSniffer.Result sniffed) {
        try {
            EpubMetadataOpfReader.Result opf = EpubMetadataOpfReader.read(epubFile);
            if (opf == null) return;

            String title = !isEmpty(opf.title)
                    ? BookMetadataSniffer.stripTrailingHashSuffix(opf.title) : sniffed.title;
            String author = !opf.authors.isEmpty()
                    ? android.text.TextUtils.join(" & ", opf.authors)
                    : sniffed.author;

            String tagsCsv = opf.tags.isEmpty() ? null
                    : android.text.TextUtils.join(", ", opf.tags);

            Double seriesIndex = null;
            if (!isEmpty(opf.seriesIndex)) {
                try {
                    seriesIndex = Double.parseDouble(opf.seriesIndex.trim());
                } catch (NumberFormatException ignored) {
                    // .opf hiếm khi ghi series_index không phải số -- bỏ qua field này, không
                    // fail cả lượt enrich vì 1 field lẻ.
                }
            }

            String languagesCsv = opf.languages.isEmpty() ? null
                    : android.text.TextUtils.join(",", opf.languages);

            String identifiersJson = null;
            if (!opf.identifiers.isEmpty()) {
                identifiersJson = new JSONObject(opf.identifiers).toString();
            }

            mDB.updateMetadataFields(uuid, title, author, opf.comments, tagsCsv,
                    opf.publisher, opf.pubDate, opf.series, seriesIndex, /* rating= */ null,
                    languagesCsv, identifiersJson,
                    (List<CustomColumnSync.ColumnDef>) opf.customColumnDefs,
                    (Map<String, String>) opf.customColumnValues);
        } catch (Exception e) {
            // Không để 1 sách có .opf lỗi làm hỏng cả findNewBooks() -- sách vẫn có sẵn
            // title/author/cover từ insertMetadata() ở nhánh gọi hàm này.
            Logger.e(e);
        }
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    private boolean fileHasAcceptedExtension(File f) {
        for (String e : mExtensions) {
            if (f.toString().endsWith(e)) return true;
        }
        return false;
    }

    public boolean scanFolder(File f) {
        if (isCrawling()) {
            Logger.d("Already Running Crawler");
            return false;
        }
        rootDirectory = f;
        newBooks.clear();
        metadata_thread = new Thread(metadataRunnable);
        metadata_thread.start();
        return true;
    }
    private void writeToFile(StringBuilder s, File f, boolean append) {
        BufferedOutputStream fw;
        try {
            fw = new BufferedOutputStream(new FileOutputStream(f, append));
            fw.write(s.toString().getBytes());
            fw.flush();
            fw.close();
        } catch (Throwable e) {
            //logAndDie(e);
        }
    }
    private static StringBuilder readFromFile(File f) {
        StringBuilder data = new StringBuilder();
        byte[] fileBuf = new byte[8192];
        int c;
        BufferedInputStream fr;
        try {
            fr = new BufferedInputStream(new FileInputStream(f));
            while ((c = fr.read(fileBuf, 0, fileBuf.length)) > 0) {
                data.append(new String(fileBuf, 0, c, "utf-8"));
            }
            fr.close();
        } catch (Throwable e) {
            Logger.e(e);
        }
        return data;
    }

    public int numNewBooks() {
        return ((!isCrawling() && newBooks != null) ? newBooks.size() : 0);
    }

    public ArrayList<JSONObject> getNewBooks() {
        return (!isCrawling() ? newBooks : null);
    }

    public void setAcceptedExtensions(String[] exts) {
        if (isCrawling()) {
            Logger.d("Not Changing Accepted Exts while Crawling");
            return;
        }
        mExtensions = exts;
    }
}
