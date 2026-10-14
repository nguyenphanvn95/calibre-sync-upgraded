package net.mrphan.calibre;

import android.content.Context;
import android.content.Intent;

import java.io.File;

/**
 * Not an Activity itself -- a small router that builds the right Intent for one of the three
 * concrete in-app readers (EpubReaderActivity, PdfReaderActivity, CbzReaderActivity) based on
 * file extension -- EpubReaderActivity also transparently handles MOBI/AZW/AZW3/TXT through an
 * internal converter, see package reader.convert. Kept as a single place so OpenBookActivity
 * doesn't need a switch statement of its own; see OpenBookActivity#openBook() for the fallback to
 * an external app for every format this router doesn't recognize.
 *
 * All three reader activities run in-process, reading the book file directly via java.io.File
 * from the app's own external-files directory (see MetadataDatabaseHelper / CalibreService
 * "Known issues" storage note) -- no FileProvider content:// Uri is needed here, unlike
 * OpenBookActivity's external-app fallback, because the reader Activities are part of this same
 * app/process and already have direct file access to their own external-files directory.
 */
public class ReaderActivity {

    /** Absolute path (java.io.File#getAbsolutePath()) of the book file to open. */
    public static final String EXTRA_BOOK_PATH = "net.mrphan.calibre.reader.EXTRA_BOOK_PATH";
    /** Calibre book UUID -- used as the key for saving/restoring the last-read locator via
     * MetadataDatabaseHelper#saveLastLocator()/getLastLocator(). */
    public static final String EXTRA_UUID = "net.mrphan.calibre.reader.EXTRA_UUID";
    /** Book title, shown in the reader chrome overlay's top bar (see
     * net.mrphan.calibre.reader.chrome.ReaderChromeHost#getBookTitle()). Optional -- callers
     * that don't have a title handy will just see an empty title bar rather than a crash. */
    public static final String EXTRA_TITLE = "net.mrphan.calibre.reader.EXTRA_TITLE";
    /** Absolute path to the cover image file (.jpg) on disk, used by
     * net.mrphan.calibre.reader.BookOpenCloseDoorEffect -- optional, may not exist (cover not
     * scanned yet). Only set by the real "open a book from the library" call site
     * (OpenBookActivity#openBook()) so the door-opening effect plays exactly once, not on every
     * resume/continue-reading/external-file entry point. Each reader Activity also caches this
     * into its own field at onCreate() so the door-closing effect can still use it later even
     * after reloadBook() clears this extra. */
    public static final String EXTRA_COVER_PATH = "net.mrphan.calibre.reader.EXTRA_COVER_PATH";

    public static Intent buildIntent(Context context, File bookFile, String uuid, String title, String lowerCaseExtension) {
        return buildIntent(context, bookFile, uuid, title, lowerCaseExtension, null);
    }

    public static Intent buildIntent(Context context, File bookFile, String uuid, String title, String lowerCaseExtension, File coverFile) {
        Class<?> target;
        switch (lowerCaseExtension) {
            case "epub":
            case "mobi":
            case "azw":
            case "azw3":
            case "txt":
                // MOBI/AZW/AZW3/TXT deu duoc EpubReaderActivity mo -- no tu goi
                // EbookFormatConverter (qua EpubPublicationLoader#open) de convert sang EPUB tam
                // truoc khi dua vao Readium, xem reader/convert/EbookFormatConverter.kt. Router nay
                // KHONG can biet gi ve viec convert, chi can tro dung Activity.
                target = EpubReaderActivity.class;
                break;
            case "pdf":
                target = PdfReaderActivity.class;
                break;
            case "cbz":
                target = CbzReaderActivity.class;
                break;
            default:
                throw new IllegalArgumentException("Unsupported in-app reader format: " + lowerCaseExtension);
        }
        Intent intent = new Intent(context, target)
                .putExtra(EXTRA_BOOK_PATH, bookFile.getAbsolutePath())
                .putExtra(EXTRA_UUID, uuid)
                .putExtra(EXTRA_TITLE, title);
        if (coverFile != null) {
            intent.putExtra(EXTRA_COVER_PATH, coverFile.getAbsolutePath());
        }
        return intent;
    }
}
