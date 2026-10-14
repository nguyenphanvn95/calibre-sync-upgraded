package net.mrphan.calibre;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * Manages book cover images cached to disk under {@code <rootDirectory>/covers/}, mirroring the
 * directory structure of each book's {@code lpath} (only the extension changes, to {@code .jpg})
 * -- see section 2.1 of the Device Library cover-scan upgrade this class was written for.
 * <p>
 * Kept separate from {@link BookCrawler} for the same single-responsibility reason
 * {@link BookMetadataSniffer} already is: BookCrawler orchestrates the scan, BookMetadataSniffer
 * knows how to read a book file, and this class knows how to manage the on-disk cover cache.
 */
final class CoverManager {

    static final String COVERS_DIR_NAME = "covers";

    private CoverManager() {}

    /** @return the path, RELATIVE to rootDirectory, of the cover file for this lpath, e.g.
     * lpath "Truyen/ABC/tap-1.epub" -> "covers/Truyen/ABC/tap-1.jpg". Does not check whether the
     * file actually exists. No sanitization is applied -- lpath is already a valid filesystem
     * path (it's literally the book's own path), so reusing it verbatim (besides the extension)
     * guarantees no collision between books in different folders, including ones with Vietnamese
     * (or any other Unicode) characters, exactly like lpath itself already handles fine. */
    static String relativeCoverPathFor(String lpath) {
        String withoutExt = lpath.contains(".")
                ? lpath.substring(0, lpath.lastIndexOf('.')) : lpath;
        return COVERS_DIR_NAME + "/" + withoutExt + ".jpg";
    }

    /** Writes coverBytes (any image format BitmapFactory can decode -- JPEG/PNG/GIF) to disk as
     * a normalized JPEG at the position mirroring lpath, creating parent directories as needed.
     * @return true if the cover was written successfully. Deliberately does NOT touch the
     * database -- callers (BookCrawler) are responsible for calling
     * MetadataDatabaseHelper#updateCoverPath only once this returns true, so a failed write never
     * leaves cover_path pointing at a file that doesn't exist (see edge case #4 of the cover-scan
     * upgrade: a book whose cover_path is still NULL is retried on the next scan). */
    static boolean saveCover(File rootDirectory, String lpath, byte[] coverBytes) {
        if (coverBytes == null || coverBytes.length == 0) return false;
        try {
            Bitmap bitmap = BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.length);
            if (bitmap == null) return false;
            String relPath = relativeCoverPathFor(lpath);
            File outFile = new File(rootDirectory, relPath);
            File parent = outFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
                return false;
            }
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos);
            }
            bitmap.recycle();
            return true;
        } catch (Exception e) {
            Logger.e(e);
            return false;
        }
    }

    /** Deletes the cover file for this lpath if it exists (used when deleting a book). Never
     * throws if the file is already missing -- best-effort cleanup that must never block the
     * main book-delete flow. */
    static void deleteCoverFor(File rootDirectory, String lpath) {
        if (lpath == null) return;
        File coverFile = new File(rootDirectory, relativeCoverPathFor(lpath));
        if (coverFile.exists()) coverFile.delete();
    }

    /** Recursively walks {@code <rootDirectory>/covers/}, deleting any cover image whose
     * rootDirectory-relative path is not present in knownCoverPaths (the current set of
     * cover_path values still referenced in SQLite, from
     * MetadataDatabaseHelper#getAllCoverPaths) -- this is the "orphan cover cleanup" step (see
     * requirement #3 of the cover-scan upgrade). Comparing against the DB's own cover_path
     * values, instead of trying to infer orphan status from which book files still exist, avoids
     * assuming a strict 1:1 filename mapping that might not hold if this logic ever changes.
     * @return the number of orphan cover files deleted. */
    static int cleanupOrphanCovers(File rootDirectory, Set<String> knownCoverPaths) {
        File coversDir = new File(rootDirectory, COVERS_DIR_NAME);
        if (!coversDir.exists()) return 0;
        int[] deleted = {0};
        cleanupOrphanCoversRecursive(rootDirectory, coversDir, knownCoverPaths, deleted);
        return deleted[0];
    }

    private static void cleanupOrphanCoversRecursive(File rootDirectory, File dir,
            Set<String> knownCoverPaths, int[] deletedCounter) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                cleanupOrphanCoversRecursive(rootDirectory, f, knownCoverPaths, deletedCounter);
                // Clean up now-empty subdirectories left behind -- nice to have, not required.
                String[] remaining = f.list();
                if (remaining != null && remaining.length == 0) f.delete();
            } else {
                String relPath = f.getAbsolutePath().substring(
                        rootDirectory.getAbsolutePath().length() + 1).replace(File.separatorChar, '/');
                if (!knownCoverPaths.contains(relPath)) {
                    if (f.delete()) deletedCounter[0]++;
                }
            }
        }
    }

    /** Decodes a cover file to a Bitmap downsampled to roughly reqWidth x reqHeight, instead of
     * the file's full resolution -- avoids allocating a huge Bitmap (and stuttering the
     * RecyclerView/GridView while scrolling) just to display it in a small thumbnail slot. Uses
     * the standard two-pass BitmapFactory.Options.inJustDecodeBounds technique. Returns null on
     * any decode failure, e.g. a partially-written or corrupt cover file. */
    static Bitmap decodeSampledBitmapFromFile(File file, int reqWidth, int reqHeight) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (Exception e) {
            Logger.e(e);
            return null;
        }
    }

    /** Bien the cua decodeSampledBitmapFromFile() cho du lieu da co san trong bo nho (byte[])
     * thay vi file tren dia -- dung cho BookDetailActivity/DeviceBookDetailActivity khi cover chi
     * co duoi dang BLOB (thumb tu OPDS feed hoac SQLite), khong co file rieng tren dia de doc
     * (xem upgrade_1.md muc 3.1). Cung logic downsample 2-pass inJustDecodeBounds nhu ham tren. */
    static Bitmap decodeSampledBitmapFromBytes(byte[] data, int reqWidth, int reqHeight) {
        try {
            if (data == null || data.length == 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (Exception e) {
            Logger.e(e);
            return null;
        }
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }
}
