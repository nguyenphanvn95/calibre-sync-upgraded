package net.mrphan.calibre;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;

/**
 * Reads a single book's full JSON entry straight out of rootDirectory/metadata.calibre, by
 * uuid -- used by DeviceBookDetailActivity to show every field Calibre desktop (or this app's
 * own BookCrawler#writeMetadataFile) put in that file, not just the reduced subset
 * MetadataDatabaseHelper#insertJSON keeps in SQLite (see Book.COLUMNS: uuid, lpath, author,
 * title, thumbnail, cover_path only).
 * <p>
 * Read-only and one-shot: does not cache anything, since the detail screen only ever needs to
 * look this up once when it's opened. Re-reads the whole file every call, same tradeoff
 * BookCrawler#parseExistingMetadataFile already makes -- metadata.calibre for a typical personal
 * library is a few hundred KB to a few MB at most, cheap enough to parse on demand.
 */
final class DeviceBookMetadataReader {

    private static final String METADATA_FILE_NAME = "metadata.calibre";

    private DeviceBookMetadataReader() {}

    /** @return the raw JSONObject for uuid as currently written in
     * rootDirectory/metadata.calibre, or null if the file doesn't exist, isn't valid JSON, or
     * has no entry matching this uuid. */
    static JSONObject findByUuid(File rootDirectory, String uuid) {
        if (rootDirectory == null || uuid == null) return null;
        File metadataFile = new File(rootDirectory, METADATA_FILE_NAME);
        if (!metadataFile.exists()) return null;
        try {
            JSONArray allBooks = new JSONArray(readFile(metadataFile));
            for (int i = 0; i < allBooks.length(); i++) {
                JSONObject book = allBooks.optJSONObject(i);
                if (book != null && uuid.equals(book.optString("uuid", null))) {
                    return book;
                }
            }
        } catch (JSONException e) {
            Logger.e(e);
        }
        return null;
    }

    private static String readFile(File f) {
        StringBuilder data = new StringBuilder();
        byte[] buf = new byte[8192];
        int c;
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(f))) {
            while ((c = in.read(buf, 0, buf.length)) > 0) {
                data.append(new String(buf, 0, c, "utf-8"));
            }
        } catch (Exception e) {
            Logger.e(e);
        }
        return data.toString();
    }
}
