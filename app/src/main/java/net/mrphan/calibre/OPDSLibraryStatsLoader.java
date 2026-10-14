package net.mrphan.calibre;

import android.os.Handler;
import android.os.Looper;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * One-shot, best-effort fetch of a library's total book count, used only to fill in the
 * subtitle under each library's name on the "choose a library" screen (see
 * OPDSBrowserFragment#fetchLibraryCounts and item_library_card.xml).
 *
 * Calibre's OPDS feeds report this as an &lt;opensearch:totalResults&gt; element that appears
 * near the top of the feed, before any &lt;entry&gt;. This stops parsing as soon as it finds
 * that element -- or as soon as it hits the first &lt;entry&gt;, meaning this particular feed
 * never reports one -- so it never has to parse actual book entries, covers, etc. just to read
 * a single number.
 *
 * Runs on its own short-lived background thread per library, separate from the shared
 * OPDSLoader used for normal page navigation: that class only tracks one in-flight parse at a
 * time and is already busy owning the "current page" back-stack, whereas the root feed's
 * handful of libraries need to be counted independently of (and without disturbing) whatever
 * page the fragment is actually navigating.
 */
class OPDSLibraryStatsLoader {

    interface Callback {
        /** Called on the main thread. count is one of the OPDSLibraryBlock.COUNT_* sentinels,
         * or a value &gt;= 0 for a real count. */
        void onCount(int count);
    }

    private static final int TIMEOUT_MS = 8000;

    static void fetchCount(final URL url, final String authorizationHeader, final Callback callback) {
        final Handler mainThread = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int result = fetchCountSync(url, authorizationHeader);
                mainThread.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onCount(result);
                    }
                });
            }
        }, "OPDSLibraryStatsLoader").start();
    }

    private static int fetchCountSync(URL url, String authorizationHeader) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            if (authorizationHeader != null) {
                connection.setRequestProperty("Authorization", authorizationHeader);
            }
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return OPDSLibraryBlock.COUNT_UNKNOWN;
            }

            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XmlPullParser xpp = factory.newPullParser();
            xpp.setInput(connection.getInputStream(), null);

            int eventType = xpp.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    String name = xpp.getName();
                    if ("totalResults".equals(name)) {
                        try {
                            return Integer.parseInt(xpp.nextText().trim());
                        } catch (Exception e) {
                            return OPDSLibraryBlock.COUNT_UNKNOWN;
                        }
                    } else if ("entry".equals(name)) {
                        // Reached the first book entry without seeing totalResults -- this feed
                        // doesn't report one.
                        return OPDSLibraryBlock.COUNT_UNKNOWN;
                    }
                }
                eventType = xpp.next();
            }
        } catch (Exception e) {
            Logger.e(e);
        } finally {
            if (connection != null) connection.disconnect();
        }
        return OPDSLibraryBlock.COUNT_UNKNOWN;
    }
}
