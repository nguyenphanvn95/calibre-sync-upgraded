package net.mrphan.calibre;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small file-naming helpers shared by CloudBrowserActivity (Cloud Libraries downloads) and
 * ContentServerBrowserActivity (Content Server WebView downloads), so both download paths
 * avoid clobbering an existing file with the same name and both display file sizes the same
 * way.
 */
public class FileNameUtils {

    private static final Pattern FILENAME_STAR_PATTERN =
            Pattern.compile("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILENAME_PATTERN =
            Pattern.compile("filename\\s*=\\s*\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);

    /** @return a file in dir with the requested name, or -- if that name is already taken --
     * the same name with " (1)", " (2)", etc. inserted before the extension, whichever comes
     * first that doesn't already exist. */
    public static File resolveNonConflictingFile(File dir, String name) {
        File candidate = new File(dir, name);
        if (!candidate.exists()) return candidate;

        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int i = 1;
        File next;
        do {
            next = new File(dir, base + " (" + i + ")" + ext);
            i++;
        } while (next.exists());
        return next;
    }

    /** Best-effort filename extraction from a Content-Disposition header (RFC 6266), including
     * the filename*=UTF-8''... extended form. Falls back to the last path segment of the URL,
     * and finally to a timestamped placeholder if nothing usable is found. */
    public static String resolveFileNameFromResponse(String contentDisposition, String url, String fallbackExt) {
        if (contentDisposition != null) {
            Matcher star = FILENAME_STAR_PATTERN.matcher(contentDisposition);
            if (star.find()) {
                String encoding = star.group(1);
                String encoded = star.group(2);
                try {
                    String charset = (encoding == null || encoding.isEmpty()) ? "UTF-8" : encoding;
                    String decoded = URLDecoder.decode(encoded, charset);
                    if (!decoded.isEmpty()) return sanitize(decoded);
                } catch (UnsupportedEncodingException | IllegalArgumentException ignored) {
                    // fall through to the plain filename= form / URL fallback below
                }
            }
            Matcher plain = FILENAME_PATTERN.matcher(contentDisposition);
            if (plain.find()) {
                String name = plain.group(1).trim();
                if (!name.isEmpty()) return sanitize(name);
            }
        }
        if (url != null) {
            String path = url;
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            int slash = path.lastIndexOf('/');
            if (slash >= 0 && slash < path.length() - 1) {
                String segment = path.substring(slash + 1);
                try {
                    segment = URLDecoder.decode(segment, "UTF-8");
                } catch (UnsupportedEncodingException | IllegalArgumentException ignored) {
                }
                if (!segment.isEmpty()) return sanitize(segment);
            }
        }
        return "download_" + System.currentTimeMillis() + (fallbackExt != null ? fallbackExt : ".epub");
    }

    private static String sanitize(String name) {
        // Strip path separators a hostile/broken header could sneak in -- never let a
        // Content-Disposition value escape the target download directory.
        return name.replace("/", "_").replace("\\", "_");
    }

    /** Human-readable file size, e.g. "1.2 MB", "340 KB". */
    public static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String unit = "KMGTPE".charAt(exp - 1) + "B";
        return String.format(Locale.getDefault(), "%.1f %s", bytes / Math.pow(1024, exp), unit);
    }
}
