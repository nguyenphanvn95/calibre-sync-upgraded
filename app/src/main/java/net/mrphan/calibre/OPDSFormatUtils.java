package net.mrphan.calibre;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;

import java.net.URL;
import java.util.List;
import java.util.Locale;

/**
 * Format-chooser dialog and format-label helpers shared between BookDetailActivity (Calibre
 * Library tab) and anything else that needs to let the user pick which format of a
 * multi-format book to act on. Moved here from OPDSBrowserFragment so BookDetailActivity
 * doesn't have to duplicate the same logic -- see the "Book Detail" feature notes.
 */
public final class OPDSFormatUtils {

    /** Callback invoked once the user has picked a format (or immediately, for a single-format
     * book that never showed the dialog at all). */
    public interface OnFormatChosenListener {
        void onChosen(String uuid, URL resolvedUrl);
    }

    private OPDSFormatUtils() {}

    /** Shown when a book is available in more than one format (e.g. both EPUB and PDF, or an
     * ebook plus an MP3/M4B audiobook) -- lets the user pick which one to act on instead of
     * always using whichever format the OPDS feed happened to list last. */
    public static void showFormatChooserDialog(Context context, List<OPDSEntry.Format> formats,
                                                URL pageUrl, String uuid,
                                                OnFormatChosenListener listener) {
        final String[] hrefs = new String[formats.size()];
        String[] labels = new String[formats.size()];
        for (int i = 0; i < formats.size(); i++) {
            OPDSEntry.Format f = formats.get(i);
            hrefs[i] = f.href;
            labels[i] = formatLabel(f);
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.dialog_title_select_format)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            listener.onChosen(uuid, new URL(pageUrl, hrefs[which]));
                        } catch (Exception e) {
                            Logger.e(e);
                        }
                    }
                })
                .show();
    }

    /** A short, human-readable label for a format choice, e.g. "EPUB" or "MP3". Calibre's
     * content server acquisition links are of the form ".../get/&lt;FMT&gt;/...", where FMT is
     * already the plain format/extension name -- that's the most reliable source (it's exactly
     * what AsyncDownloader will save the file as), with the link's mime "type" attribute as a
     * fallback for any acquisition link that doesn't follow that convention. */
    public static String formatLabel(OPDSEntry.Format f) {
        String fromHref = guessFormatFromHref(f.href);
        if (fromHref != null) return fromHref;
        String fromMime = guessFormatFromMime(f.mimeType);
        if (fromMime != null) return fromMime;
        return f.mimeType != null ? f.mimeType : "?";
    }

    /** True if one of the entry's formats matches the given label (e.g. "EPUB"), case
     * insensitive -- used to gate features that only work for one specific format, such as the
     * "Listen (TTS)" action button which only applies to EPUB (plain text), not PDF/MOBI/CBZ. */
    public static boolean hasFormat(List<OPDSEntry.Format> formats, String label) {
        if (formats == null) return false;
        for (OPDSEntry.Format f : formats) {
            if (label.equalsIgnoreCase(formatLabel(f))) return true;
        }
        return false;
    }

    private static String guessFormatFromHref(String href) {
        if (href == null) return null;
        String fmt = extractSegmentAfter(href, "/get/");
        if (fmt == null) fmt = extractSegmentAfter(href, "/download/");
        return fmt;
    }

    /** Pulls the path segment right after {@code marker} (e.g. "mp3" out of ".../download/mp3"
     * or ".../download/mp3?x=1"), stopping at the next '/', '?' or '#' so trailing path segments
     * or query strings don't leak into the result. Used for both Calibre content server links
     * (".../get/&lt;FMT&gt;/...") and the GDrive library's local proxy links
     * (".../&lt;bookId&gt;/download/&lt;fmt&gt;", see GDriveLibraryJsBridge#doOpenBookDetail). */
    private static String extractSegmentAfter(String href, String marker) {
        int idx = href.indexOf(marker);
        if (idx < 0) return null;
        String rest = href.substring(idx + marker.length());
        int end = rest.length();
        for (String stop : new String[]{"/", "?", "#"}) {
            int at = rest.indexOf(stop);
            if (at >= 0 && at < end) end = at;
        }
        String fmt = rest.substring(0, end);
        return fmt.isEmpty() ? null : fmt.toUpperCase(Locale.ROOT);
    }

    private static String guessFormatFromMime(String mime) {
        if (mime == null) return null;
        String m = mime.toLowerCase(Locale.ROOT);
        if (m.contains("epub")) return "EPUB";
        if (m.contains("pdf")) return "PDF";
        if (m.contains("mobipocket")) return "MOBI";
        if (m.contains("amazon")) return "AZW";
        if (m.contains("fictionbook")) return "FB2";
        if (m.contains("rtf")) return "RTF";
        if (m.contains("msword") || m.contains("wordprocessingml")) return "DOC";
        if (m.contains("plain")) return "TXT";
        if (m.contains("html")) return "HTML";
        if (m.equals("application/x-cbz") || m.contains("comicbook+zip")) return "CBZ";
        if (m.equals("application/x-cbr") || m.contains("comicbook+rar")) return "CBR";
        if (m.contains("mpeg") && m.startsWith("audio")) return "MP3";
        if (m.contains("audio/mp4") || m.contains("audio/x-m4b")) return "M4B";
        if (m.contains("ogg")) return "OGG";
        if (m.contains("flac")) return "FLAC";
        if (m.startsWith("audio/wav") || m.contains("x-wav")) return "WAV";
        return null;
    }

    /** Human-readable file size, e.g. "3.2 MB" -- shared by BookDetailActivity (Calibre Library
     * tab, format-info rows) and DeviceBookDetailActivity (Device Library tab, file-info card)
     * so both screens format sizes the same way. */
    public static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String unit = "KMGTPE".charAt(exp - 1) + "B";
        return String.format(Locale.getDefault(), "%.1f %s", bytes / Math.pow(1024, exp), unit);
    }

    /** Both the OPDS Atom "updated" timestamp and Calibre's own metadata.calibre "pubdate" /
     * "last_modified" fields are full ISO-8601 timestamps (e.g. "2023-06-02T00:00:00+00:00");
     * this shows just the "yyyy-MM-dd" portion when the raw value looks like that shape,
     * otherwise it's shown as-is rather than hiding a field the source did provide. */
    public static String formatIsoDatePrefix(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        int tIndex = raw.indexOf('T');
        return tIndex > 0 ? raw.substring(0, tIndex) : raw;
    }

    /** Parses a full ISO-8601 timestamp (e.g. "2020-05-01T00:00:00+00:00", the shape both the
     * OPDS Atom "updated"/"published" fields and Calibre's metadata.calibre "pubdate"/
     * "timestamp" fields use) into epoch milliseconds, or null if the string doesn't parse --
     * callers should treat null as "unknown" rather than falling back to 0/"now". Used by
     * CustomColumnSync (datatype "datetime") and MetadataDatabaseHelper#insertJSON (date_added
     * from the "timestamp" field) to get a sortable numeric value alongside the raw ISO string. */
    public static Long parseIsoToEpochMillisOrNull(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            return java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }

    /** Formats a rating/series-index style number without a trailing ".0" for whole values
     * (e.g. 4 instead of "4.0", but "4.5" stays as-is) -- shared by BookDetailActivity (Calibre
     * Library tab) and DeviceBookDetailActivity (Device Library tab) so both screens format
     * these the same way instead of each keeping its own copy. */
    public static String formatNumber(double value) {
        if (value == Math.floor(value)) return String.valueOf((long) value);
        return String.format(Locale.getDefault(), "%.1f", value);
    }
}

