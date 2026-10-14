package net.mrphan.calibre;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One "library" extracted from a Calibre OPDS Content Server root feed.
 *
 * Only the library that's *currently active* on the Content Server has its actual filters
 * ("By Newest", "By Title", "By Rating", "By Language", "By Publisher", "By Author", "By
 * Tags"...) present in the root feed; every other configured library shows up there as nothing
 * more than a "Library: &lt;name&gt;" switch link, with no filters of its own -- the server only
 * generates those once you actually follow that link, which flips the server's active library
 * and returns a *new* top-level catalog, itself shaped exactly like the root feed (that
 * library's own filters, followed by the same list of "Library: X" switch links to every
 * configured library). So a library other than the first is only "resolved" -- i.e. actually
 * knows its own real filters and a real "By Newest" href -- after that extra round trip; see
 * {@link #isResolved()} / {@link #resolveFrom}.
 *
 * This class groups {@link OPDSPage#getCatalogLinks()} / {@link OPDSPage#getCatalogLinkAt(int)}
 * of a root (or post-switch) page into these per-library groups, WITHOUT flattening them into a
 * single raw list the way the tab used to render the root feed.
 *
 * Deliberately uses parallel ordered lists rather than a title-keyed Map for each library's own
 * filters, for the exact same reason already documented on OPDSPage.cat_titles/cat_hrefs:
 * filter titles ("By Author", "By Tags"...) repeat verbatim across libraries, so a title-keyed
 * lookup is not safe even *within* one already-split-out library block if a server happened to
 * repeat a filter title for some other reason -- position-based lookup (getFilterHrefAt) keeps
 * every href intact regardless.
 */
public class OPDSLibraryBlock {
    /** Display name, e.g. "Calibre Library". Never null. */
    public final String name;
    private List<String> filterTitles;
    private List<String> filterHrefs;
    /** Href to fetch to enter this library. While {@link #isResolved()} is false this is really
     * the library's own "Library: X" switch link (the only href the root feed gave us for it),
     * and fetching it returns that library's own top-level catalog rather than a book listing --
     * see {@link #resolveFrom}, which is what turns this into a real "By Newest" href. Never
     * null as long as this library has at least one filter or its own "Library: X" href to fall
     * back on. */
    public String defaultHref;
    /** The page this block's filterHrefs/defaultHref were parsed from, and therefore the base
     * every one of those hrefs must be resolved against (they may be root-relative, or relative
     * to that specific page). Starts as the root feed's own URL; replaced by the post-switch
     * page's URL once {@link #resolveFrom} runs. */
    private URL resolveBaseUrl;
    /** True once filterTitles/filterHrefs/defaultHref reflect this library's own real catalog
     * (either because it was the server's already-active library when the root feed was parsed,
     * or because {@link #resolveFrom} has since run). False means defaultHref is still just the
     * "Library: X" switch link and filterTitles/filterHrefs are empty placeholders. */
    private boolean resolved;

    /** bookCount hasn't been fetched yet (see OPDSLibraryStatsLoader). Card shows a loading
     * placeholder in this state. */
    public static final int COUNT_LOADING = -2;
    /** Fetch finished but no usable count was found (server didn't report one, request failed,
     * unauthorized...). Card omits the subtitle entirely in this state rather than showing an
     * error inline on the "choose a library" screen. */
    public static final int COUNT_UNKNOWN = -1;
    /** Total book count for this library, fetched best-effort and cached here for the lifetime
     * of this block (i.e. for as long as OPDSBrowserFragment#mLibraries holds it) so returning
     * to the "choose a library" screen never re-fetches it. Deliberately mutable/non-final --
     * unlike the rest of this otherwise-immutable class -- since it's populated asynchronously
     * after this block already exists and is already sitting in the GridView's adapter; see
     * OPDSBrowserFragment#fetchLibraryCounts. */
    public int bookCount = COUNT_LOADING;

    public OPDSLibraryBlock(String name, List<String> filterTitles, List<String> filterHrefs,
                             String defaultHref, URL resolveBaseUrl, boolean resolved) {
        this.name = name;
        this.filterTitles = filterTitles;
        this.filterHrefs = filterHrefs;
        this.defaultHref = defaultHref;
        this.resolveBaseUrl = resolveBaseUrl;
        this.resolved = resolved;
    }

    /** False if this block's defaultHref is still just its "Library: X" switch link rather than
     * a real "By Newest" href -- i.e. it needs one more fetch (see resolveFrom) before it can
     * actually be entered and show its own books. Always true for whichever library was already
     * active when the root feed was parsed. */
    public boolean isResolved() {
        return resolved;
    }

    /** Base URL every filterHref / defaultHref on this block must be resolved against. */
    public URL getResolveBaseUrl() {
        return resolveBaseUrl;
    }

    /** Fills in this library's *real* filters and default ("By Newest") href from the catalog
     * page returned by following its "Library: X" switch link -- that page is shaped exactly
     * like the root feed (this library's own filters, then "Library: X" switch links to every
     * configured library), so the same leading-block extraction as {@link #extractFrom} applies:
     * everything up to the first "Library:" match is this library's own filter set. A no-op if
     * the page had no leading filters at all (defaultHref/resolved are left untouched so the
     * caller can detect the failure). */
    public void resolveFrom(OPDSPage page) {
        List<String> titles = page.getCatalogLinks();
        List<String> newFilterTitles = new ArrayList<String>();
        List<String> newFilterHrefs = new ArrayList<String>();
        for (int i = 0; i < titles.size(); i++) {
            String title = titles.get(i);
            if (matchLibraryTitle(title) != null) break;
            newFilterTitles.add(title);
            newFilterHrefs.add(page.getCatalogLinkAt(i));
        }
        if (newFilterHrefs.isEmpty()) return;
        this.filterTitles = newFilterTitles;
        this.filterHrefs = newFilterHrefs;
        this.defaultHref = newFilterHrefs.get(0);
        this.resolveBaseUrl = page.peekURL();
        this.resolved = true;
    }

    /** Ordered display titles for this library's filters (e.g. "By Newest", "By Author"...),
     * built dynamically from whatever the server's XML actually contained -- never hard-coded,
     * since different Calibre versions/servers can offer a different set or different labels. */
    public List<String> getFilterTitles() {
        return filterTitles;
    }

    /** Resolves a filter's href by its position in getFilterTitles(), not by title -- see the
     * class comment on why titles alone aren't a safe key. */
    public String getFilterHrefAt(int position) {
        if (position >= 0 && position < filterHrefs.size()) return filterHrefs.get(position);
        return null;
    }

    @Override
    public String toString() {
        // Lets this class be dropped straight into an ArrayAdapter<OPDSLibraryBlock> for the
        // "choose a library" screen without a custom adapter.
        return name;
    }

    private static final Pattern LIBRARY_TITLE_PATTERN =
            Pattern.compile("^\\s*Library:\\s*(.+?)\\s*$", Pattern.CASE_INSENSITIVE);

    /** @return the library name if title matches Calibre's "Library: &lt;name&gt;" switch-link
     * convention (case-insensitive on the "Library:" part only -- the name itself keeps whatever
     * casing the server sent), or null if this title is an ordinary filter ("By ..."). */
    private static String matchLibraryTitle(String title) {
        if (title == null) return null;
        Matcher m = LIBRARY_TITLE_PATTERN.matcher(title);
        return m.matches() ? m.group(1) : null;
    }

    /**
     * Splits a root feed's catalog links (in the exact order Calibre returned them) into one
     * {@link OPDSLibraryBlock} per library. Pure/no Android dependency so it's easy to unit
     * test independently of the Fragment/Loader.
     *
     * @param rootPage the parsed root feed page.
     * @param singleLibraryFallbackName name to use for the edge case of a server that has only
     *                                  one library and therefore never emits a "Library: X"
     *                                  entry at all (so no name can be read from the feed
     *                                  itself) -- e.g. the configured server's host, or a
     *                                  generic string resource. Must not be null.
     */
    public static List<OPDSLibraryBlock> extractFrom(OPDSPage rootPage, String singleLibraryFallbackName) {
        List<OPDSLibraryBlock> libraries = new ArrayList<OPDSLibraryBlock>();
        List<String> titles = rootPage.getCatalogLinks();
        List<String> pendingTitles = new ArrayList<String>();
        List<String> pendingHrefs = new ArrayList<String>();
        URL rootUrl = rootPage.peekURL();
        for (int i = 0; i < titles.size(); i++) {
            String title = titles.get(i);
            String href = rootPage.getCatalogLinkAt(i);
            String libraryName = matchLibraryTitle(title);
            if (libraryName != null) {
                // A non-empty pendingHrefs means this "Library: X" entry directly follows the
                // set of filters that belong to it -- true only for the server's currently
                // active library (necessarily the first one encountered, since the root feed
                // has exactly one real filter set). Every later "Library: X" entry has nothing
                // preceding it (pendingHrefs already cleared), so it starts out unresolved: its
                // "defaultHref" is really just this switch link itself, to be followed once (see
                // resolveFrom) before this library can actually be entered.
                boolean resolved = !pendingHrefs.isEmpty();
                String defaultHref = resolved ? pendingHrefs.get(0) : href;
                libraries.add(new OPDSLibraryBlock(libraryName,
                        new ArrayList<String>(pendingTitles), new ArrayList<String>(pendingHrefs),
                        defaultHref, rootUrl, resolved));
                pendingTitles.clear();
                pendingHrefs.clear();
            } else {
                pendingTitles.add(title);
                pendingHrefs.add(href);
            }
        }
        // Edge case: a server with a single library never emits a "Library: X" entry at all, so
        // everything parsed ends up still sitting in pendingTitles/pendingHrefs with no library
        // ever having been closed off above.
        if (libraries.isEmpty() && !pendingTitles.isEmpty()) {
            String name = (singleLibraryFallbackName != null && !singleLibraryFallbackName.isEmpty())
                    ? singleLibraryFallbackName : "Calibre Library";
            libraries.add(new OPDSLibraryBlock(name, pendingTitles, pendingHrefs,
                    pendingHrefs.get(0), rootUrl, true));
        }
        // Further edge case: a feed with NO catalog/filter links at all -- neither "Library: X"
        // switch links nor "By ..." filters, just a flat list of book <entry> elements. This is
        // exactly the shape of the local server GDriveOpdsHttpServer builds (see PHẦN 2/5 of the
        // GDrive-on-OPDS upgrade prompt): with zero catalog links, pendingTitles/pendingHrefs
        // above are also empty, so the branch just above never fires either. Treat this as
        // exactly one resolved "virtual" library whose defaultHref is the root feed's own URL,
        // so OPDSBrowserFragment skips straight to the book grid instead of showing an empty
        // "Select Library" screen.
        if (libraries.isEmpty() && pendingTitles.isEmpty() && rootUrl != null) {
            String name = (singleLibraryFallbackName != null && !singleLibraryFallbackName.isEmpty())
                    ? singleLibraryFallbackName : "Calibre Library";
            libraries.add(new OPDSLibraryBlock(name, new ArrayList<String>(), new ArrayList<String>(),
                    rootUrl.toString(), rootUrl, true));
        }
        return libraries;
    }
}
