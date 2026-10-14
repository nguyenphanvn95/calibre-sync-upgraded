package net.mrphan.calibre;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

/**
 * User: sengjea
 * Date: 23/12/13
 * Time: 18:23
 */
public class OPDSPage {
    private MatrixCursor books_cursor;
    private Stack<URL> object_urls;
    private Map<String,String> nav_urls;
    /** sortField/ascending books_cursor was last built with (see getBooksSorted below), so a
     * repeat call with the SAME params (e.g. OPDSBrowserFragment#loadCurrentPage() re-running
     * on every onStart(), including a plain return from previewing a book -- see that method's
     * "alreadyLiveRendered" comment) can hand back the exact same MatrixCursor instance instead
     * of rebuilding one. That instance identity is what alreadyLiveRendered actually compares,
     * so skipping the pointless rebuild is what lets the GridView keep its adapter (and thus
     * its scroll position) across such a re-run, instead of resetting to the top every time. */
    private boolean books_cursor_sorted = false;
    private int sorted_field = -1;
    private boolean sorted_ascending = true;
    // Catalog (sub-navigation) links, in feed order. Was a Map<String,String> keyed by the
    // entry's display title, but Calibre's multi-library Content Server root feed lists the
    // *same* category titles ("By Tac gia", "By Tua sach", ...) once per library, one after
    // another, each followed by that library's own "Library: X" switch link. With a
    // title-keyed map, every later library's identically-titled category silently overwrote
    // the href of the earlier one, so tapping e.g. "By Tac gia" under "Library: Waka.vn"
    // could actually navigate into a *different* library's category page (whichever was
    // parsed last) -- which is why browsing into a specific library never reliably showed
    // that library's own books. Parallel ordered lists (with position-based lookup, see
    // getCatalogLinkAt) allow duplicate titles and keep each one's own href intact.
    private List<String> cat_titles;
    private List<String> cat_hrefs;
    /** uuid -> full list of acquisition formats, populated only for books offered in more than
     * one format (see addBook). Used by OPDSBrowserFragment to show a "Select format to read"
     * dialog instead of silently downloading whichever format happened to be listed last. */
    private Map<String, List<OPDSEntry.Format>> formatsByUuid = new HashMap<String, List<OPDSEntry.Format>>();
    /** uuid -> the full OPDSEntry parsed for this page (summary/updated/language/publisher/
     * formats/thumb...), so BookDetailActivity has everything it needs to render without
     * re-fetching the feed. The books_cursor above only keeps the handful of columns
     * BookCursorAdapter needs for the list/grid row and stays untouched. */
    private Map<String, OPDSEntry> entriesByUuid = new HashMap<String, OPDSEntry>();
    /** Every book entry added to this page, in feed order -- parallel to books_cursor's rows,
     * but keeping the full OPDSEntry (not just the handful of columns books_cursor stores) so
     * getBooksSorted() below can re-order by rating/published/updated/series without needing a
     * uuid (entriesByUuid skips entries with no uuid; this doesn't). */
    private List<OPDSEntry> entries_in_order = new ArrayList<OPDSEntry>();

    public OPDSPage(URL url) {
        object_urls = new Stack<URL>();
        object_urls.push(url);
        cat_titles = new ArrayList<String>();
        cat_hrefs = new ArrayList<String>();
        nav_urls = new HashMap<String,String>();
        books_cursor = new MatrixCursor(Book.COLUMNS);
    }
    public MatrixCursor getBooks() {
        return books_cursor;
    }
    /** Ordered display titles for the catalog list; may contain duplicate strings (see class
     * comment above) -- use getCatalogLinkAt(position), not a title lookup, to resolve a click. */
    public List<String> getCatalogLinks() {
        return cat_titles;
    }
    public void addCatalogLink(String k, String u) {
        cat_titles.add(k);
        cat_hrefs.add(u);
    }
    public void clearCatalogLinks() {
        cat_titles.clear();
        cat_hrefs.clear();
    }
    /** Resolves a catalog entry's href by its position in the list (as returned by
     * getCatalogLinks()), not by title -- titles are not guaranteed unique. */
    public String getCatalogLinkAt(int position) {
        if (position >= 0 && position < cat_hrefs.size())
            return cat_hrefs.get(position);
        return null;
    }
    public Map<String,String> getNavigationalLinks() {
        return nav_urls;
    }
    public void addNavigationalLink(String k, String u) {
        nav_urls.put(k, u);
    }
    public void clearNavigationalLinks() {
        nav_urls.clear();
    }
    public void addBook(OPDSEntry e) {
        books_cursor.addRow(new Object[] { e.id,
                e.uuid, e.href, e.author,
                e.title, e.thumb, null /* cover_path: no local file cache for remote books */ });
        entries_in_order.add(e);
        // A new row just landed straight in books_cursor (feed order), bypassing
        // getBooksSorted() below -- e.g. the "more" pagination button appending another page's
        // worth of entries to this same OPDSPage. Whatever sort was cached no longer reflects
        // the full row set, so the next getBooksSorted() call must rebuild even if
        // sortField/ascending happen to match the last call.
        books_cursor_sorted = false;
        if (e.uuid != null && e.formats != null && e.formats.size() > 1) {
            formatsByUuid.put(e.uuid, e.formats);
        }
        if (e.uuid != null) {
            entriesByUuid.put(e.uuid, e);
        }
    }
    /** @return every acquisition format offered for this uuid, or null if it only has one
     * (in which case the caller should just use the book's single href as before). */
    public List<OPDSEntry.Format> getFormatsForUuid(String uuid) {
        return formatsByUuid.get(uuid);
    }
    /** @return the full OPDSEntry parsed for this uuid on this page (used by
     * BookDetailActivity), or null if this page no longer holds it (e.g. the user has since
     * navigated to a different page). */
    public OPDSEntry getEntryForUuid(String uuid) {
        return entriesByUuid.get(uuid);
    }
    /** Tom tat/mo ta sach de hien thi rut gon o che do List (Calibre Library tab), lay tu
     * OPDSEntry.summary da parse san tu OPDS feed. Null neu feed khong co summary cho entry nay. */
    public String getSummaryForUuid(String uuid) {
        OPDSEntry e = entriesByUuid.get(uuid);
        return (e != null) ? e.summary : null;
    }
    public URL addURL(URL url) {
        return object_urls.push(url);
    }
    public boolean removeURLs() {
        return object_urls.empty();
    }
    public URL peekURL() {
        return object_urls.peek();
    }

    public boolean hasCatalogLinks() {
        return (!cat_titles.isEmpty());
    }

    /** Sort field ids for {@link #getBooksSorted}, shared with OPDSBrowserFragment's
     * "Sort by..." dialog (SortByDialog). Unlike CollectionFragment's Device Library tab, the
     * Calibre Library tab already has Rating/Published/Series available straight from the OPDS
     * feed's entries (see OPDSEntry/OPDSLoader), so those are offered here too. */
    public static final int SORT_FIELD_TITLE = 0;
    public static final int SORT_FIELD_AUTHOR = 1;
    public static final int SORT_FIELD_RATING = 2;
    public static final int SORT_FIELD_PUBLISHED = 3;
    public static final int SORT_FIELD_LAST_MODIFIED = 4;
    public static final int SORT_FIELD_SERIES = 5;

    /** Rebuilds books_cursor (same column layout as addBook() above) with rows re-ordered per
     * sortField/ascending, using entries_in_order's real parsed OPDSEntry fields --
     * MatrixCursor has no in-place reorder API, so a fresh one is constructed each time. This
     * only re-orders books already loaded onto *this* page; it can't reach into further pages
     * still sitting on the server, since the Calibre Content Server feed here is paginated and
     * this class only ever sees one page at a time. */
    public MatrixCursor getBooksSorted(int sortField, boolean ascending) {
        if (books_cursor_sorted && sorted_field == sortField && sorted_ascending == ascending) {
            // Same sort already applied and no new rows since (see addBook()) -- hand back the
            // exact same MatrixCursor instance rather than rebuilding an identical one. Callers
            // that compare cursor identity to decide whether their adapter is still valid (see
            // OPDSBrowserFragment#loadCurrentPage's alreadyLiveRendered) rely on this to avoid
            // pointlessly recreating that adapter -- and therefore losing GridView scroll
            // position -- on every re-run with unchanged sort params (e.g. simply returning from
            // previewing a book).
            return books_cursor;
        }
        List<OPDSEntry> sorted = sortedEntries(sortField, ascending);

        MatrixCursor cursor = new MatrixCursor(Book.COLUMNS);
        for (OPDSEntry e : sorted) {
            cursor.addRow(new Object[] { e.id, e.uuid, e.href, e.author, e.title, e.thumb, null });
        }
        books_cursor = cursor;
        books_cursor_sorted = true;
        sorted_field = sortField;
        sorted_ascending = ascending;
        return cursor;
    }

    /** Same ordering as {@link #getBooksSorted}, but as the full OPDSEntry list instead of a
     * MatrixCursor -- dùng cho OpdsBookListHolder/BookDetailActivity (vuốt trái/phải sang
     * sách kế tiếp/trước đó trong CÙNG trang OPDS đang xem, theo đúng thứ tự sort hiện tại).
     * Không cache lại như books_cursor ở trên vì lệnh gọi này không nằm trên hot path vẽ lưới --
     * chỉ chạy 1 lần mỗi khi người dùng bấm mở 1 cuốn sách. */
    public List<OPDSEntry> getEntriesSorted(int sortField, boolean ascending) {
        return sortedEntries(sortField, ascending);
    }

    private List<OPDSEntry> sortedEntries(int sortField, boolean ascending) {
        List<OPDSEntry> sorted = new ArrayList<OPDSEntry>(entries_in_order);
        Comparator<OPDSEntry> comparator;
        switch (sortField) {
            case SORT_FIELD_AUTHOR:
                comparator = Comparator.comparing((OPDSEntry e) -> nullToEmpty(e.author), String.CASE_INSENSITIVE_ORDER);
                break;
            case SORT_FIELD_RATING:
                comparator = Comparator.comparingDouble((OPDSEntry e) -> e.rating);
                break;
            case SORT_FIELD_PUBLISHED:
                comparator = Comparator.comparing((OPDSEntry e) -> nullToEmpty(e.published));
                break;
            case SORT_FIELD_LAST_MODIFIED:
                comparator = Comparator.comparing((OPDSEntry e) -> nullToEmpty(e.updated));
                break;
            case SORT_FIELD_SERIES:
                comparator = Comparator.comparing((OPDSEntry e) -> nullToEmpty(e.series), String.CASE_INSENSITIVE_ORDER)
                        .thenComparingDouble((OPDSEntry e) -> e.seriesIndex);
                break;
            case SORT_FIELD_TITLE:
            default:
                comparator = Comparator.comparing((OPDSEntry e) -> nullToEmpty(e.title), String.CASE_INSENSITIVE_ORDER);
                break;
        }
        if (!ascending) comparator = comparator.reversed();
        Collections.sort(sorted, comparator);
        return sorted;
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}
