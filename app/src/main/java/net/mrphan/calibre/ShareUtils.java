package net.mrphan.calibre;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Shared "share a book file" logic, extracted from CollectionFragment#shareBooks so both the
 * Device Library list's swipe-to-share action and DeviceBookDetailActivity's new Share button
 * go through the exact same Intent.ACTION_SEND/ACTION_SEND_MULTIPLE + FileProvider code path,
 * instead of two copies drifting apart over time. See CollectionFragment#shareBooks for the
 * original call site this was pulled out of.
 */
public final class ShareUtils {

    private ShareUtils() {
    }

    /** Result of a shareFiles() call, so a caller that wants to (like CollectionFragment, for
     * its "N/M books could not be shared" Toast) can tell a partial failure apart from a full
     * success without ShareUtils itself deciding what message to show. */
    public static final class Result {
        public final int requested;
        public final int shared;

        Result(int requested, int shared) {
            this.requested = requested;
            this.shared = shared;
        }

        public boolean isFullSuccess() {
            return shared > 0 && shared == requested;
        }

        public boolean isPartialFailure() {
            return shared > 0 && shared < requested;
        }
    }

    /** Shares one or more book files via Intent.ACTION_SEND/ACTION_SEND_MULTIPLE, using
     * FileProvider to obtain a content Uri for each. Skips (without crashing) any file that
     * doesn't exist or that FileProvider refuses (e.g. it falls outside the paths declared in
     * res/xml/file_paths.xml); shows an error Toast only if NO file could be shared at all. The
     * caller decides what, if anything, to show for a partial failure via the returned Result. */
    public static Result shareFiles(Activity activity, List<File> files) {
        if (activity == null || files == null || files.isEmpty()) {
            return new Result(files == null ? 0 : files.size(), 0);
        }
        String authority = activity.getPackageName() + ".fileprovider";

        ArrayList<Uri> uris = new ArrayList<>();
        String singleMimeType = null;
        for (File fd : files) {
            if (fd == null || !fd.exists()) continue;
            try {
                Uri uri = FileProvider.getUriForFile(activity, authority, fd);
                uris.add(uri);
                String mime = getMimeType(fd.getName());
                singleMimeType = (singleMimeType == null || singleMimeType.equals(mime))
                        ? mime : "*/*";
            } catch (IllegalArgumentException e) {
                Logger.e(e);
            }
        }

        if (uris.isEmpty()) {
            Toast.makeText(activity, R.string.dialog_share_error, Toast.LENGTH_SHORT).show();
            return new Result(files.size(), 0);
        }

        Intent shareIntent;
        if (uris.size() == 1) {
            shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            shareIntent.setType(singleMimeType != null ? singleMimeType : "*/*");
        } else {
            shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
            shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            shareIntent.setType(singleMimeType != null ? singleMimeType : "*/*");
        }
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(Intent.createChooser(shareIntent,
                activity.getString(R.string.dialog_share_chooser_title)));
        return new Result(files.size(), uris.size());
    }

    public static Result shareFile(Activity activity, File file) {
        if (file == null) return new Result(0, 0);
        return shareFiles(activity, Collections.singletonList(file));
    }

    private static String getMimeType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "*/*";
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime != null ? mime : "*/*";
    }
}
