package net.mrphan.calibre;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Central list of the "info rows" a book detail screen (BookDetailActivity for the Calibre
 * Library / OPDS tab, DeviceBookDetailActivity for the Device Library tab) can show, plus the
 * persisted on/off state for each -- backed by the same SharedPreferences SettingsActivity
 * already uses (PreferenceManager.getDefaultSharedPreferences), so toggling a column in
 * Settings takes effect on both tabs without any extra plumbing.
 */
public final class BookInfoColumns {

    public static final String KEY_UPDATED = "updated";
    public static final String KEY_PUBLISHED = "published";
    public static final String KEY_LANGUAGE = "language";
    public static final String KEY_PUBLISHER = "publisher";
    public static final String KEY_SERIES = "series";
    public static final String KEY_RATING = "rating";
    public static final String KEY_TAGS = "tags";
    public static final String KEY_IDENTIFIERS = "identifiers";
    public static final String KEY_DESCRIPTION = "description";
    /** Device Library tab only (DATABASE_VERSION 13) -- Custom Column values aren't available
     * for OPDS/Calibre Library entries (BookDetailActivity), so this key is a no-op there, same
     * as any other toggle would be if its tab has nothing to show for it. */
    public static final String KEY_CUSTOM_COLUMNS = "custom_columns";

    /** Preference key storing the set of *visible* columns (MultiSelectListPreference-style),
     * mirrors how pref_filetypes already works in res/xml/preference.xml. */
    public static final String PREF_KEY = "pref_book_info_columns";

    /** All columns visible by default -- an empty/never-set preference must behave as "show
     * everything", matching the app's current (pre-settings) behaviour so nobody's book detail
     * screen silently loses rows on first update. */
    public static final Set<String> ALL_KEYS = new HashSet<>(Arrays.asList(
            KEY_UPDATED, KEY_PUBLISHED, KEY_LANGUAGE, KEY_PUBLISHER, KEY_SERIES,
            KEY_RATING, KEY_TAGS, KEY_IDENTIFIERS, KEY_DESCRIPTION, KEY_CUSTOM_COLUMNS
    ));

    private BookInfoColumns() {}

    public static boolean isVisible(Context context, String key) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        Set<String> visible = prefs.getStringSet(PREF_KEY, ALL_KEYS);
        return visible.contains(key);
    }
}
