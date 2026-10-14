package net.mrphan.calibre;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;
import androidx.fragment.app.Fragment;
import android.view.*;
import android.view.View.OnClickListener;
import android.widget.*;
import android.widget.AdapterView.OnItemClickListener;
import net.mrphan.calibre.widget.GradientLoadingBar;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

@SuppressLint("NewApi")
public class OPDSBrowserFragment extends Fragment implements HeaderContentProvider {

    public interface OPDSFragmentListener {
        // public void onBookClick(String uuid, String url);
        public void noServerInPreferences();
        /** Content Server replied HTTP 401 (requires login, or the saved credentials are wrong). */
        public void onUnauthorized();
    }
    private static final String ARG_OVERRIDE_ROOT_URL = "override_root_url";
    private static final String ARG_OVERRIDE_TITLE = "override_title";
    // Cache cho URL template OpenSearch da resolve tu <link rel="search"> (xem
    // performCalibreServerSearch()) -- tranh phai tai lai tai lieu OpenSearchDescription moi lan
    // tim kiem, chi tai lai neu href cua link "search" doi (vd. chuyen sang thu vien khac).
    private String cachedOpenSearchDescriptorUrl;
    private String cachedOpenSearchTemplate;

    /** Cache cua tieu de gan nhat truyen qua setActivityTitle(...) -- header dung chung
     * (HeaderContentProvider#getHeaderTitle) doc tu day thay vi tu ActionBar da bi an
     * (xem MainActivity#onCreate goi getSupportActionBar().hide()). */
    private String mHeaderTitle;

    /** Dùng cho GDriveOpdsLibraryBrowserActivity: mở fragment này trỏ thẳng vào 1 URL cụ thể
     * (server OPDS giả lập nội bộ, http://127.0.0.1:<port>/) thay vì đọc pref_cs_url như luồng
     * Content Server bình thường — KHÔNG cần username/password (server local không yêu cầu auth). */
    public static OPDSBrowserFragment newInstanceForUrl(String rootUrl, String title) {
        OPDSBrowserFragment f = new OPDSBrowserFragment();
        Bundle args = new Bundle();
        args.putString(ARG_OVERRIDE_ROOT_URL, rootUrl);
        args.putString(ARG_OVERRIDE_TITLE, title);
        f.setArguments(args);
        return f;
    }

    private OPDSFragmentListener mListener;
    private static GridView lvList;
    private static GradientLoadingBar pbProgress;
    private static TextView tvMore;
    private URL urlCSRoot;
    private OPDSPage currentPage;
    private CsCredentialStore mCsCredentialStore;

    /** Local metadata DB, used here only to look up reading progress % (via
     * getAllBookExtras()) for books in this OPDS feed that also happen to be downloaded on
     * device. Opened in onCreate, closed in onDestroy -- mirrors CollectionFragment's mDB
     * lifecycle. */
    private MetadataDatabaseHelper mDB;
    /** uuid -> progress/comments, batch-loaded once (see MetadataDatabaseHelper#getAllBookExtras)
     * and reused by every BookCursorAdapter created while this fragment is alive. Comments here
     * are ignored in favor of OPDSEntry.summary (passed separately as `page`); only
     * progressPercent is actually consulted for this tab. */
    private java.util.Map<String, MetadataDatabaseHelper.BookExtras> mBookExtrasMap;

    /** Persists the Calibre Library tab's chosen display mode (list, or grid with 2/3/4 books
     * per row) across app restarts. Value is the number of columns: 1 means the classic list. */
    private static final String PREF_OPDS_VIEW_COLUMNS = "pref_opds_view_columns";
    private SharedPreferences mSettings;
    /** Current number of columns for the *book grid* (1 = list). Folder/category navigation
     * pages (see loadCurrentPage()) always show as a single-column list regardless of this,
     * since a multi-column grid of plain text folder names doesn't make sense. */
    private int mViewColumns = 1;
    private GridDisplayStyle mGridStyle = GridDisplayStyle.DEFAULT;
    /** Menu item cua o tim kiem, luu lai de onOptionsItemSelected() co the tu mo no ra
     * (expandActionView) khi no bi day vao overflow menu -- xem case R.id.menu_search. */
    private MenuItem mSearchMenuItem;

    /** Persists the Calibre Library tab's chosen "Sort by..." field/direction across app
     * restarts (mirrors PREF_OPDS_VIEW_COLUMNS above). Only re-orders whatever page is
     * currently loaded -- see OPDSPage#getBooksSorted -- so it's re-applied after every
     * loadCurrentPage() rather than being a server-side query parameter. */
    private static final String PREF_OPDS_SORT_FIELD = "pref_opds_sort_field";
    private static final String PREF_OPDS_SORT_ASCENDING = "pref_opds_sort_ascending";
    private int mSortField = OPDSPage.SORT_FIELD_TITLE;
    private boolean mSortAscending = true;

    public static String TAB_TAG = "tab_opds";
    private Stack<URL> url_stack = new Stack<URL>();

    /** Libraries parsed out of the root feed on the first load, cached for the lifetime of this
     * Fragment instance so returning to the "choose a library" screen (e.g. via Back/Up from a
     * library's default page) never needs another network request -- see onRootFeedLoaded()
     * and returnToLibrarySelection(). Null until the root feed has been parsed once. */
    private List<OPDSLibraryBlock> mLibraries;
    /** The library currently being browsed, or null while showing the "choose a library"
     * screen (mLibraries) or before the root feed has loaded at all. */
    private OPDSLibraryBlock mCurrentLibrary;
    /** URL of the root feed itself (as actually resolved/redirected, from OPDSPage#peekURL()),
     * used as the base to resolve every OPDSLibraryBlock href (defaultHref and each filter's
     * href) against -- those hrefs were captured while parsing the root feed, so they must be
     * resolved relative to *that* page, not whatever page happens to be on screen when the user
     * later taps a filter. */
    private URL mRootFeedUrl;
    /** True for exactly the one beginParsingXml() call that fetches the root feed, so
     * onParseComplete() can tell that particular result apart from any other page load and
     * route it to onRootFeedLoaded() instead of the normal loadCurrentPage() rendering path. */
    private boolean mLoadingRootFeed = false;
    /** True while the GridView is showing the "choose a library" list (mLibraries) rather than
     * a normal catalog/book page -- distinguishes a tap on that list from a tap on an ordinary
     * catalog-links page, since both use a plain android.R.layout.simple_list_item_1
     * ArrayAdapter and are otherwise indistinguishable by adapter class alone. */
    private boolean mShowingLibrarySelection = false;
    /** True while showing the "GDrive offline libraries" fallback screen (Content Server root feed
     * failed to load for a REAL connection reason -- see onRootFeedConnectionFailed) instead of a
     * normal catalog/book page or the real library-selection screen. Mutually exclusive with
     * mShowingLibrarySelection. */
    private boolean mShowingGDriveOfflineLibraries = false;
    private View mOfflineBanner;
    private TextView mOfflineBannerMessage;
    /** Non-null while a fetch of an unresolved library's own "Library: X" switch link is in
     * flight (see OPDSLibraryBlock#isResolved and openLibrary()) -- lets onParseComplete tell
     * that particular result apart from a normal page load and route it to
     * onLibrarySwitchResolved() instead. Cleared as soon as that result arrives. */
    private OPDSLibraryBlock mResolvingLibrary;
    /** The page OPDSLoader#onBookAdded is currently live-rendering into lvList as its books
     * arrive, or null. Deliberately a SEPARATE field from currentPage (not just reusing it):
     * onParseComplete() below decides how to route a just-finished parse (root feed? library
     * switch? normal page?) precisely by comparing currentPage against the page it received --
     * if onBookAdded updated currentPage itself as a side effect of live-rendering, that
     * comparison would always see them as "already equal" and onParseComplete would skip its
     * routing entirely (e.g. never call onRootFeedLoaded() for the GDrive single-library feed,
     * which IS a book page but must still be recognised and unpacked as the root feed once). */
    private OPDSPage mLiveRenderedPage;

    private final OPDSLoader mOPDS = new OPDSLoader() {
        public void onParseComplete(OPDSPage p) {
            pbProgress.setVisibility(View.GONE);
            //tvDump.setVisibility(View.GONE);
            lvList.setEnabled(true);
            if (p == null && mLoadingRootFeed) {
                // Root feed thuc su la nguon KHONG THE tai (mat mang, server tat, timeout, ...) --
                // KHAC voi truong hop urlCSRoot == null (chua cau hinh, da co noServerInPreferences() xu
                // ly rieng o onStart()) va khac 401 (da co onUnauthorized() rieng, khong roi vao day).
                // Truoc ban vá nay, nhanh else ben duoi (updateCurrentPage(), von la no-op vi currentPage
                // van null) khien man hinh trang tron khong co thong bao gi ca.
                mLoadingRootFeed = false;
                onRootFeedConnectionFailed();
                return;
            }
            if (p != null && currentPage != p) {
                currentPage = p;
                if (mLoadingRootFeed) {
                    mLoadingRootFeed = false;
                    onRootFeedLoaded(p);
                } else if (mResolvingLibrary != null) {
                    OPDSLibraryBlock library = mResolvingLibrary;
                    mResolvingLibrary = null;
                    onLibrarySwitchResolved(library, p);
                } else {
                    loadCurrentPage();
                }
            } else {
                updateCurrentPage();
            }
        }

        @Override
        public void onStartParse() {
            pbProgress.setVisibility(View.VISIBLE);
            try {
                ((BaseAdapter) lvList.getAdapter()).notifyDataSetInvalidated();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        public void onParseProgress(int pcnt) {

        }

        @Override
        void onBookAdded(OPDSPage page, OPDSEntry entry) {
            // KHONG loai tru truong hop mLoadingRootFeed: voi feed GDriveOpdsHttpServer, "root
            // feed" CHINH LA trang sach (xem onRootFeedLoaded ben duoi) -- Content Server that
            // (nhieu thu vien) khong bao gio phat ra <entry> sach truc tiep o root feed (chi co
            // catalog links), nen onBookAdded von di khong the tu no goi cho truong hop do; guard
            // nay vi vay khong can thiet va viec bo no giup GDrive route hien sach tien do ngay
            // trong luot tai duy nhat cua no thay vi phai doi.
            if (mResolvingLibrary != null) return;
            if (lvList == null || getActivity() == null) return;
            if (page == mLiveRenderedPage) {
                // Tiep tuc trang dang duoc live-render (mot lan tai "More", hoac 1 sach ke tiep
                // cua chinh trang dau tien) -- adapter da gan dung vao page.getBooks() roi, chi
                // can ve lai de dong/anh bia vua them hien ra ngay.
                pbProgress.setVisibility(View.GONE);
                try {
                    ((BaseAdapter) lvList.getAdapter()).notifyDataSetChanged();
                } catch (Exception ignored) { }
                return;
            }
            // Sach dau tien cua 1 trang MOI: hien no (va luoi/grid) ngay lap tuc thay vi doi
            // phan con lai cua feed -- day chinh la co che khien tung anh bia hien ra ngay khi
            // tai xong. CO Y KHONG dong bo currentPage o day (xem mLiveRenderedPage) -- viec
            // "trang nay ROT CUOC la loai gi" (root feed / library switch / trang thuong) van
            // do onParseComplete() quyet dinh nhu cu khi ca feed da xong.
            mLiveRenderedPage = page;
            mShowingLibrarySelection = false;
            pbProgress.setVisibility(View.GONE);
            lvList.setEnabled(true);
            lvList.setNumColumns(mViewColumns);
            lvList.setAdapter(new BookCursorAdapter(getActivity(), page.getBooks(), false,
                    mViewColumns > 1, page, mBookExtrasMap, mGridStyle));
        }

        @Override
        public void onUnauthorized() {
            pbProgress.setVisibility(View.GONE);
            lvList.setEnabled(true);
            if (getActivity() == null) return;
            Toast.makeText(getActivity(), R.string.opds_error_unauthorized, Toast.LENGTH_LONG).show();
            mListener.onUnauthorized();
        }
    };
    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        // Verify that the host activity implements the callback interface
        try {
            mListener = (OPDSFragmentListener) activity;
        } catch (ClassCastException e) {
            // The activity doesn't implement the interface, throw exception
            throw new ClassCastException(activity.toString()
                    + " must implement OPDSFragmentListener");
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mOPDS.setContext(getActivity());
        setHasOptionsMenu(true);

        //Settings related initialisation
        mSettings = PreferenceManager.getDefaultSharedPreferences(getActivity().getApplicationContext());
        mViewColumns = mSettings.getInt(PREF_OPDS_VIEW_COLUMNS, 1);
        mGridStyle = GridDisplayStyle.readFrom(mSettings);
        mSortField = mSettings.getInt(PREF_OPDS_SORT_FIELD, OPDSPage.SORT_FIELD_TITLE);
        mSortAscending = mSettings.getBoolean(PREF_OPDS_SORT_ASCENDING, true);

        // Reading progress %: batch-loaded once for the lifetime of this fragment, same
        // rationale as CollectionFragment#reloadBookExtras -- avoids a DB query per row while
        // scrolling. Only books also present in the local metadata DB (i.e. already downloaded)
        // will have a non-null entry here; the rest simply show no % (see BookCursorAdapter).
        mDB = new MetadataDatabaseHelper(getActivity());
        mBookExtrasMap = mDB.getAllBookExtras();

        String overrideRootUrl = getArguments() != null ? getArguments().getString(ARG_OVERRIDE_ROOT_URL) : null;
        if (overrideRootUrl != null) {
            // Server OPDS giả lập nội bộ (GDriveOpdsHttpServer) — không auth, không phụ thuộc
            // pref_cs_url của Content Server thật.
            try {
                urlCSRoot = new URL(overrideRootUrl);
            } catch (MalformedURLException e) {
                urlCSRoot = null;
            }
            mCsCredentialStore = null; // không dùng tới trong nhánh này
        } else {
            try {
                urlCSRoot = new URL(mSettings.getString("pref_cs_url",""));
            } catch (MalformedURLException e) {
                urlCSRoot = null;
            }
            mCsCredentialStore = new CsCredentialStore(getActivity().getApplicationContext());
            if (urlCSRoot != null) {
                String csUsername = mCsCredentialStore.getUsername(urlCSRoot.getHost(), urlCSRoot.getPort());
                mOPDS.setCredentials(csUsername, mCsCredentialStore.getPassword(urlCSRoot.getHost(), urlCSRoot.getPort()));
            }
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.activity_opds,container,false);
        pbProgress = (GradientLoadingBar) v.findViewById(R.id.col_prog);
        mOfflineBanner = v.findViewById(R.id.opds_offline_banner);
        mOfflineBannerMessage = (TextView) v.findViewById(R.id.tvOfflineBannerMessage);
        Button btnOfflineRetry = (Button) v.findViewById(R.id.btnOfflineRetry);
        btnOfflineRetry.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                retryConnectToServer();
            }
        });
        //tvDump = (TextView) v.findViewById(R.id.col_dump);
        //tvDump.setVisibility(TextView.VISIBLE);

        View vmore = inflater.inflate(R.layout.listfooter_more, null);
        vmore.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View arg0) {
                try {
                    URL base = currentPage.peekURL();
                    // URL(base, spec) is the standard way to resolve a link found inside a
                    // fetched document: it correctly handles a href that's root-relative
                    // ("/opds/..."), relative to the current page ("subdir/..."), or a full
                    // absolute URL, unlike rebuilding protocol+host+port+href by hand (which
                    // silently produced a broken URL, and therefore silently did nothing, for
                    // any href that wasn't a bare root-relative path).
                    URL urlMore = new URL(base, currentPage.getNavigationalLinks().get("next"));
                    loadURL(urlMore, true);
                } catch (Exception e) {
                    Logger.e(e);
                    if (getActivity() != null) {
                        Toast.makeText(getActivity(), R.string.opds_error_navigation, Toast.LENGTH_SHORT).show();
                    }
                }

            }
        });
        tvMore = (TextView) vmore.findViewById(R.id.listfooter_more);

        lvList = (GridView) v.findViewById(R.id.col_list);
        lvList.setNumColumns(mViewColumns);
        // GridView has no addFooterView (that's ListView-only), so the "more" button is just a
        // plain view placed below the grid in the same vertical LinearLayout, rather than a
        // footer row inside the adapter's own item list. Its own OnClickListener (set above)
        // handles taps directly, same as before.
        ((ViewGroup) v).addView(vmore, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        lvList.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position,
                                    long id) {

                try {

                    if (mShowingGDriveOfflineLibraries) {
                        Object item = parent.getItemAtPosition(position);
                        if (item instanceof GDriveOpdsLibrary) {
                            openGDriveLibraryWebView((GDriveOpdsLibrary) item);
                        }
                    } else if (mShowingLibrarySelection) {
                        if (mLibraries != null && position >= 0 && position < mLibraries.size()) {
                            openLibrary(mLibraries.get(position));
                        }
                    } else if (parent.getAdapter().getClass() == BookCursorAdapter.class) {
                        Cursor cs = (Cursor) parent.getItemAtPosition(position);
                        String uuid = cs.getString(Book.UUID);
                        OPDSEntry entry = currentPage.getEntryForUuid(uuid);
                        // entry can be null if the page was replaced (e.g. user navigated away)
                        // between the grid being populated and this click landing -- safe no-op.
                        if (entry != null) {
                            openBookDetail(entry);
                        }
                    } else if (parent.getAdapter().getClass() == ArrayAdapter.class) {
                        // Resolve by list position, not by title text: Calibre's multi-library
                        // root feed repeats the same category titles ("By Tac gia", etc.) once
                        // per library, and a title lookup would always resolve to whichever
                        // library's href was parsed last (see OPDSPage's class comment) --
                        // that's why browsing into e.g. "Library: Waka.vn" never reliably landed
                        // on that library's own books.
                        String href = currentPage.getCatalogLinkAt(position);
                        if (href == null) return;
                        // See the "more" button's onClick above for why URL(base, spec) replaces
                        // the old hand-assembled protocol+host+port+href: catalog links such as
                        // "Library: Waka.vn" (a Calibre content-server multi-library switch link)
                        // were silently failing to navigate whenever their href wasn't a bare
                        // root-relative path.
                        URL tmp_url = new URL(currentPage.peekURL(), href);
                        loadURL(tmp_url,false);
                    }
                } catch (Exception e) {
                    Logger.e(e);
                    if (getActivity() != null) {
                        Toast.makeText(getActivity(), R.string.opds_error_navigation, Toast.LENGTH_SHORT).show();
                    }
                }
            }

        });
        return v;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mDB != null) mDB.close();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.opds_fragment, menu);
        MenuItem searchItem = menu.findItem(R.id.menu_search);
        mSearchMenuItem = searchItem;
        if (searchItem != null) searchItem.setVisible(!mShowingGDriveOfflineLibraries);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB && searchItem != null
                && !mShowingGDriveOfflineLibraries) {
            SearchView searchView = (SearchView) searchItem.getActionView();
            // getActionView() is null if this item got pushed into the overflow menu instead of
            // shown inline (e.g. not enough room in the toolbar) -- guard instead of crashing.
            if (searchView != null) {
                // BUG FIX: ca 2 loai thu vien (GDrive va Content Server thuong) gio deu tim
                // NGAY BEN TRONG thu vien dang duyet qua chinh SearchView nay, khong con dung
                // SearchManager toan cuc cho nhanh Content Server nua -- SearchManager luon mo
                // BookSearchActivity, activity do chi tim trong sach DA TAI VE MAY (mot tap con
                // rat nho cua catalog server khi dang duyet OPDS), nen truoc day bam Go hay ra
                // man hinh ket qua rong/den vi khong co empty-state. Chi khac nguon URL tim
                // kiem: performGDriveSearch() goi endpoint ?search= cua GDriveOpdsHttpServer,
                // performCalibreServerSearch() resolve <link rel="search"> chuan OpenSearch cua
                // Content Server that su.
                searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                    @Override
                    public boolean onQueryTextSubmit(String query) {
                        if (isGDriveLibrary()) {
                            performGDriveSearch(query);
                        } else {
                            performCalibreServerSearch(query);
                        }
                        searchView.clearFocus();
                        return true;
                    }

                    @Override
                    public boolean onQueryTextChange(String newText) {
                        return false;
                    }
                });
            }
        }
        MenuItem filterItem = menu.findItem(R.id.menu_filter_by);
        if (filterItem != null) filterItem.setVisible(mCurrentLibrary != null);

        // The list/grid display mode only applies to the book grid -- the "choose a library"
        // screen always renders a fixed single-column list of library cards (see
        // renderLibrarySelection) regardless of this setting, so hide it there entirely rather
        // than let picking an option silently blow away the library list (see setViewColumns).
        MenuItem viewModeItem = menu.findItem(R.id.menu_view_mode);
        if (viewModeItem != null) viewModeItem.setVisible(!mShowingLibrarySelection && !mShowingGDriveOfflineLibraries);

        // "Sort by..." only re-orders the book grid itself (see OPDSPage#getBooksSorted), so
        // hide it anywhere that grid isn't showing -- same conditions as menu_view_mode above,
        // plus the folder/category navigation screens (currentPage.hasCatalogLinks()).
        MenuItem sortByItem = menu.findItem(R.id.menu_sort_by);
        if (sortByItem != null) {
            boolean showingBookGrid = !mShowingLibrarySelection && !mShowingGDriveOfflineLibraries
                    && currentPage != null && !currentPage.hasCatalogLinks();
            sortByItem.setVisible(showingBookGrid);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem mitem) {
        switch (mitem.getItemId()) {
            case android.R.id.home:
           //case R.id.menu_goback:
                loadPreviousPage();
                break;
            case R.id.menu_search:
                // The SearchView only auto-expands itself when the item is shown inline in the
                // toolbar. Once it's demoted to the overflow menu (not enough room -- see
                // onCreateOptionsMenu above), tapping it just delivers a plain click here with no
                // action view to expand, so it used to do nothing on any modern device.
                // BUG FIX: khong con phan biet GDrive vs Content Server o day nua -- ca 2 deu
                // dung chung 1 SearchView voi OnQueryTextListener rieng (xem onCreateOptionsMenu),
                // nen luon tu mo action view cua no thay vi roi vao onSearchRequested() (mo
                // BookSearchActivity toan cuc, chi tim sach da tai ve may).
                if (mSearchMenuItem != null) {
                    mSearchMenuItem.expandActionView();
                }
                break;
            case R.id.menu_view_mode:
                showViewModeDialog();
                break;
            case R.id.menu_filter_by:
                showFilterByDialog();
                break;
            case R.id.menu_sort_by:
                showSortByDialog();
                break;
        }

        return super.onOptionsItemSelected(mitem);
    }

    /** Opens the floating "Display" picker (ViewModePickerDialog) for the Calibre Library tab's
     * four view modes, replacing the old anchored submenu. Mirrors
     * CollectionFragment#showViewModeDialog for the Device Library tab. */
    private void showViewModeDialog() {
        if (getActivity() == null) return;
        List<ViewModePickerDialog.Option> options = new ArrayList<>();
        options.add(new ViewModePickerDialog.Option(R.drawable.ic_view_list, R.string.view_mode_list, 1));
        options.add(new ViewModePickerDialog.Option(R.drawable.ic_view_grid_2, R.string.view_mode_grid_2, 2));
        options.add(new ViewModePickerDialog.Option(R.drawable.ic_view_grid_3, R.string.view_mode_grid_3, 3));
        options.add(new ViewModePickerDialog.Option(R.drawable.ic_view_grid_4, R.string.view_mode_grid_4, 4));
        ViewModePickerDialog.show(getActivity(), options, mViewColumns, this::setViewColumns);
    }

    /** Applies (and persists) the chosen display mode for the *book grid*. Folder/category
     * pages are unaffected -- see loadCurrentPage(), which always forces a single column for
     * those regardless of mViewColumns. */
    private void setViewColumns(int columns) {
        if (mViewColumns == columns) return;
        mViewColumns = columns;
        mSettings.edit().putInt(PREF_OPDS_VIEW_COLUMNS, columns).apply();
        // The menu item that gets here is hidden while mShowingLibrarySelection (see
        // onCreateOptionsMenu), but guard anyway rather than let loadCurrentPage() stomp the
        // library-card list with whatever stale currentPage happens to be cached.
        if (mShowingLibrarySelection) return;
        // Re-run whichever adapter loadCurrentPage() would already have picked (book grid vs.
        // folder list), now with the new column count / item layout.
        loadCurrentPage();
    }
    public boolean loadPreviousPage() {
        if (mOPDS.pageHasParent()) {
            currentPage = mOPDS.getPreviousPage();
            loadCurrentPage();
            return true;
        }
        if (mCurrentLibrary != null) {
            returnToLibrarySelection();
            return true;
        }
        return false;
    }
    /** Style Grid duoc BookCursorAdapter chon layout ngay luc newView() (khac voi
     * pref_grid_show_title_progress, doc lai moi lan bindView()) -- nen doi Style Grid trong luc tab
     * dang o nen bat buoc phai tao lai adapter, khong the chi notifyDataSetChanged(). Goi tu onStart()
     * ben duoi, truoc doan notifyDataSetChanged() da co san cho pref_grid_show_title_progress. */
    private void applyGridDisplayStyleIfChanged() {
        GridDisplayStyle newStyle = GridDisplayStyle.readFrom(mSettings);
        if (newStyle == mGridStyle) return;
        mGridStyle = newStyle;
        applyGridBackground();
        if (lvList != null && lvList.getAdapter() instanceof BookCursorAdapter && currentPage != null) {
            lvList.setAdapter(new BookCursorAdapter(getActivity(), currentPage.getBooks(), false,
                    mViewColumns > 1, currentPage, mBookExtrasMap, mGridStyle));
        }
    }

    /** Nen cua lvList doi theo Style Grid, giong CollectionFragment#applyGridBackground(). */
    private void applyGridBackground() {
        if (lvList == null) return;
        if (mGridStyle == GridDisplayStyle.BOOKSHELF) {
            lvList.setBackgroundResource(R.drawable.bg_bookshelf_wall);
        } else {
            lvList.setBackground(null);
        }
    }
    @Override
    public void onStart() {
        super.onStart();
        applyGridDisplayStyleIfChanged();
        // BookCursorAdapter doc pref_grid_show_title_progress truc tiep trong bindView() (khong
        // cache), nhung CursorAdapter chi tu goi lai bindView() cho cac dong dang hien khi co
        // notifyDataSetChanged()/requery -- khong tu ve lai chi vi SharedPreferences doi o noi
        // khac (vi du man hinh Settings). Ep ve lai o day de ap dung ngay khi tab quay ve
        // foreground, khong can restart app.
        if (lvList != null && lvList.getAdapter() instanceof BookCursorAdapter) {
            ((BaseAdapter) lvList.getAdapter()).notifyDataSetChanged();
        }
        if (mOPDS.isParsingXml()) {
            pbProgress.setVisibility(View.VISIBLE);

        } else if (currentPage == null) {
            if (mShowingGDriveOfflineLibraries) {
                // Da that bai ket noi truoc do trong cung phien Fragment nay -- chi ve lai danh sach da
                // cache (khong goi mang lai), nguoi dung phai chu dong bam "Thu lai" (xem
                // retryConnectToServer()) de tranh danh sach dang xem bi thay bat ngo bang loading khi ho
                // chi don gian chuyen qua tab khac roi quay lai.
                renderOfflineGDriveLibraries();
            } else if (urlCSRoot != null) {
                loadRootFeed();
            } else {
                mListener.noServerInPreferences();
            }
        } else if (mShowingLibrarySelection) {
            setActivityTitle(R.string.opds_select_library);
            renderLibrarySelection();
        } else {
            if (mCurrentLibrary != null) {
                setActivityTitle(mCurrentLibrary.name);
            }
            loadCurrentPage();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        ActionBar ab = getSupportActionBar();
        if (ab != null) ab.setDisplayHomeAsUpEnabled(false);
    }

    /** Returns the host Activity's support ActionBar, or null if unavailable. The tab/back
     * arrow toggling in this Fragment used to call the deprecated (non-support)
     * Activity#getActionBar(), which is always null on an AppCompatActivity that never calls
     * setActionBar() with a native framework ActionBar (this app's Activities don't) -- so
     * those calls were silent no-ops. getSupportActionBar() is the one that's actually backed
     * by this app's theme (see BarTabActivity) and is what CloudBrowserActivity/
     * ContentServerBrowserActivity already use successfully for the same purpose. */
    private ActionBar getSupportActionBar() {
        Activity activity = getActivity();
        if (activity instanceof AppCompatActivity) {
            return ((AppCompatActivity) activity).getSupportActionBar();
        }
        return null;
    }

    private void setActivityTitle(CharSequence title) {
        mHeaderTitle = title == null ? null : title.toString();
        if (getActivity() != null) getActivity().setTitle(title);
    }

    private void setActivityTitle(int titleResId) {
        mHeaderTitle = getString(titleResId);
        if (getActivity() != null) getActivity().setTitle(titleResId);
    }

    /** Up/Back arrow should show whenever there's somewhere to go back to: either a parent page
     * in the normal catalog back-stack, or -- new in this version -- simply being inside a
     * library at all, since Back/Up from a library's own default page (no back-stack parent)
     * now returns to the "choose a library" screen instead of being a no-op (see
     * loadPreviousPage()). */
    private void updateHomeUpIndicator() {
        ActionBar ab = getSupportActionBar();
        if (ab != null) ab.setDisplayHomeAsUpEnabled(mOPDS.pageHasParent() || mCurrentLibrary != null);
    }

    /** Kicks off the one-time load of the OPDS root feed. Its result is routed to
     * onRootFeedLoaded() rather than the normal loadCurrentPage() path -- see onParseComplete()
     * in the mOPDS listener above. */
    private void loadRootFeed() {
        mLoadingRootFeed = true;
        loadURL(urlCSRoot, false);
    }

    /** First-load handler for the root feed: splits it into per-library blocks (see
     * OPDSLibraryBlock) and shows the "choose a library" screen instead of the old flat dump of
     * every "By ..." / "Library: ..." entry. */
    private void onRootFeedLoaded(OPDSPage rootPage) {
        mShowingGDriveOfflineLibraries = false;
        if (mOfflineBanner != null) mOfflineBanner.setVisibility(View.GONE);
        mRootFeedUrl = rootPage.peekURL();
        mLibraries = OPDSLibraryBlock.extractFrom(rootPage, computeSingleLibraryFallbackName());
        mCurrentLibrary = null;
        if (isGDriveLibrary() && mLibraries.size() == 1) {
            // Thu vien GDrive luon chi co dung 1 "thu vien ao" (server cuc bo khong co khai niem
            // nhieu thu vien) -- vao thang trong do, bo qua man hinh "Chon thu vien" chi co 1 the
            // duy nhat. BUG FIX: truoc day day la 1 nhanh doc thang currentPage (rootPage) vi feed
            // goc CHINH LA trang sach (khong co catalog link nao); nay feed goc cua GDrive da co
            // catalog link (xem GDriveOpdsHttpServer#buildFiltersOnlyFeed, phan sua Loi 1: "Filter
            // by" khong co gi de chon), nen phai di qua openLibrary() nhu binh thuong de lay ve
            // trang sach mac dinh ("Moi nhat") -- 1 request them vao server local (127.0.0.1) la
            // khong dang ke.
            openLibrary(mLibraries.get(0));
            return;
        }
        setActivityTitle(R.string.opds_select_library);
        if (getActivity() != null) getActivity().invalidateOptionsMenu();
        renderLibrarySelection();
        fetchLibraryCounts();
    }

    /** Root feed cua Content Server that KHONG the tai duoc vi ly do ket noi thuc su (mat mang,
     * server tat, sai IP, timeout...). Thay vi bo mac man hinh trang tron nhu truoc day, o lai tab
     * Calibre Library, bao cho nguoi dung biet, va cho phep duyet ngay cac thu vien Calibre da luu
     * tren Google Drive (khong can Content Server, xem GDriveOpdsLibraryStore/GDriveLibraryWebViewActivity). */
    private void onRootFeedConnectionFailed() {
        if (getActivity() == null) return;
        setActivityTitle(R.string.opds_offline_libraries_title);
        getActivity().invalidateOptionsMenu();
        renderOfflineGDriveLibraries();
    }

    /** Ve lai man hinh fallback (khong goi mang) -- co the goi lai nhieu lan, vi du khi quay lai tab
     * nay tu tab khac (xem onStart()) hoac sau khi xoa/them 1 thu vien GDrive trong luc dang o day. */
    private void renderOfflineGDriveLibraries() {
        if (lvList == null || getActivity() == null) return;
        mShowingLibrarySelection = false;
        mShowingGDriveOfflineLibraries = true;
        updateHomeUpIndicator();

        java.util.List<GDriveOpdsLibrary> libraries = GDriveOpdsLibraryStore.getAll(getActivity());
        if (mOfflineBanner != null) mOfflineBanner.setVisibility(View.VISIBLE);
        if (mOfflineBannerMessage != null) {
            mOfflineBannerMessage.setText(libraries.isEmpty()
                    ? R.string.opds_offline_banner_no_libraries
                    : R.string.opds_offline_banner_has_libraries);
        }

        lvList.setNumColumns(1);
        lvList.setAdapter(new GDriveOpdsLibraryCardAdapter(getActivity(), libraries));
        if (tvMore != null) tvMore.setVisibility(View.GONE);
    }

    /** Nut "Thu lai" cua banner: an banner + danh sach fallback, roi thu tai lai root feed. Neu van
     * loi, onRootFeedConnectionFailed() se duoc goi lai va man hinh fallback hien ra lai binh thuong. */
    private void retryConnectToServer() {
        if (urlCSRoot == null) {
            // Khong con URL nao de thu (nguoi dung xoa cau hinh server trong luc dang o man fallback) --
            // dua ve dung luong cu.
            if (mListener != null) mListener.noServerInPreferences();
            return;
        }
        mShowingGDriveOfflineLibraries = false;
        if (mOfflineBanner != null) mOfflineBanner.setVisibility(View.GONE);
        loadRootFeed();
    }

    /** Mo 1 thu vien GDrive tu man hinh fallback de duyet trong WebView -- y het hanh vi cua
     * GDriveOpdsLibraryListBottomSheet#openBrowse(), tai su dung Activity da co san. */
    private void openGDriveLibraryWebView(GDriveOpdsLibrary library) {
        if (getActivity() == null) return;
        Intent intent = new Intent(getActivity(), GDriveLibraryWebViewActivity.class);
        intent.putExtra(GDriveLibraryWebViewActivity.EXTRA_LIBRARY_ID, library.getId());
        startActivity(intent);
    }

    /** True neu fragment nay dang duyet 1 thu vien GDrive (mo qua
     * GDriveOpdsLibraryBrowserActivity/newInstanceForUrl), tuc la dang noi chuyen voi server cuc
     * bo GDriveOpdsHttpServer thay vi 1 Content Server that o xa. */
    private boolean isGDriveLibrary() {
        return getArguments() != null && getArguments().getString(ARG_OVERRIDE_ROOT_URL) != null;
    }

    /** Kicks off one best-effort background fetch per library (see OPDSLibraryStatsLoader) to
     * fill in each card's "N books" subtitle once known. Only fetched once per library for the
     * lifetime of this Fragment -- the result is cached directly on the OPDSLibraryBlock (see
     * its bookCount field), so returning to this screen later (returnToLibrarySelection) never
     * re-fetches it, and each callback here only needs to refresh the one row it affects. */
    private void fetchLibraryCounts() {
        if (mLibraries == null || mRootFeedUrl == null) return;
        final String authHeader = mOPDS.getAuthorizationHeader();
        for (final OPDSLibraryBlock library : mLibraries) {
            if (library.bookCount != OPDSLibraryBlock.COUNT_LOADING) continue;
            URL target;
            try {
                target = new URL(library.getResolveBaseUrl(), library.defaultHref);
            } catch (Exception e) {
                library.bookCount = OPDSLibraryBlock.COUNT_UNKNOWN;
                continue;
            }
            OPDSLibraryStatsLoader.fetchCount(target, authHeader, new OPDSLibraryStatsLoader.Callback() {
                @Override
                public void onCount(int count) {
                    library.bookCount = count;
                    // Only refresh the visible list if we're still showing it (the user may
                    // have since entered a library, or left the screen/Fragment entirely) --
                    // notifyDataSetChanged() on a GridView showing a different adapter/page
                    // would be a harmless no-op anyway, but skip it for clarity.
                    if (mShowingLibrarySelection && lvList != null && lvList.getAdapter() instanceof LibraryCardAdapter) {
                        ((LibraryCardAdapter) lvList.getAdapter()).notifyDataSetChanged();
                    }
                }
            });
        }
    }

    /** Fallback library name for a Content Server that only has one library -- and therefore
     * never emits a "Library: X" entry in its root feed for OPDSLibraryBlock to read a name
     * from (see OPDSLibraryBlock#extractFrom). Prefers the configured server's host:port
     * (matching how other screens in this app already label a Content Server, see
     * ConnectionFragment), falling back to a generic string resource if that isn't available. */
    private String computeSingleLibraryFallbackName() {
        String overrideTitle = getArguments() != null ? getArguments().getString(ARG_OVERRIDE_TITLE) : null;
        if (overrideTitle != null && !overrideTitle.isEmpty()) return overrideTitle;
        if (urlCSRoot != null && urlCSRoot.getHost() != null && !urlCSRoot.getHost().isEmpty()) {
            return urlCSRoot.getPort() > 0
                    ? urlCSRoot.getHost() + ":" + urlCSRoot.getPort()
                    : urlCSRoot.getHost();
        }
        return getString(R.string.opds_library_fallback_name);
    }

    /** Renders the cached mLibraries list. Never triggers a network request -- callable both
     * right after the root feed first parses and any time Back/Up returns here later. */
    private void renderLibrarySelection() {
        if (lvList == null || mLibraries == null) return;
        mShowingLibrarySelection = true;
        lvList.setNumColumns(1);
        lvList.setAdapter(new LibraryCardAdapter(getActivity(), mLibraries));
        if (tvMore != null) tvMore.setVisibility(TextView.GONE);
        updateHomeUpIndicator();
    }

    /** Enters a library: sets the toolbar title, loads its default "Newest" page, and reveals
     * the "Filter by..." menu item. The library's default page is deliberately given no
     * back-stack parent (see OPDSLoader#discardCurrentPage) so that Back/Up from it returns to
     * the library list rather than to the raw root feed. Libraries other than the one already
     * active on the server aren't resolved yet (see OPDSLibraryBlock#isResolved) -- those go
     * through resolveThenOpenLibrary() for one extra fetch first. */
    private void openLibrary(OPDSLibraryBlock library) {
        if (!library.isResolved()) {
            resolveThenOpenLibrary(library);
            return;
        }
        try {
            URL target = new URL(library.getResolveBaseUrl(), library.defaultHref);
            enterLibrary(library, target);
        } catch (Exception e) {
            Logger.e(e);
            if (getActivity() != null) {
                Toast.makeText(getActivity(), R.string.opds_error_navigation, Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** Follows an unresolved library's own "Library: X" switch link -- this flips the server's
     * active library and returns that library's own top-level catalog (filters + defaultHref),
     * handled by onLibrarySwitchResolved() once it loads. The switch-link fetch itself is
     * dropped from the back-stack (see OPDSLoader#discardCurrentPage) so it never becomes a
     * page the user can navigate "back" into -- only the real "Newest" page it leads to
     * (loaded by onLibrarySwitchResolved) should be reachable, exactly like the already-resolved
     * path above. */
    private void resolveThenOpenLibrary(OPDSLibraryBlock library) {
        if (library.defaultHref == null || library.getResolveBaseUrl() == null) return;
        try {
            URL switchUrl = new URL(library.getResolveBaseUrl(), library.defaultHref);
            mResolvingLibrary = library;
            mOPDS.resetPages();
            mOPDS.discardCurrentPage();
            loadURL(switchUrl, false);
        } catch (Exception e) {
            mResolvingLibrary = null;
            Logger.e(e);
            if (getActivity() != null) {
                Toast.makeText(getActivity(), R.string.opds_error_navigation, Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** Result of resolveThenOpenLibrary(): the server has now switched to this library, and `p`
     * is its own top-level catalog. Extracts its real filters/defaultHref (see
     * OPDSLibraryBlock#resolveFrom) and, on success, proceeds exactly like opening an
     * already-resolved library. Refreshes the library's book-count subtitle too, since the one
     * fetched earlier (if any) was necessarily fetched against the wrong -- switch-link --
     * href. */
    private void onLibrarySwitchResolved(OPDSLibraryBlock library, OPDSPage p) {
        library.resolveFrom(p);
        if (!library.isResolved()) {
            Logger.e(new Exception("Library switch response had no filters to resolve: " + library.name));
            if (getActivity() != null) {
                Toast.makeText(getActivity(), R.string.opds_error_navigation, Toast.LENGTH_SHORT).show();
            }
            returnToLibrarySelection();
            return;
        }
        try {
            URL target = new URL(library.getResolveBaseUrl(), library.defaultHref);
            enterLibrary(library, target);
            refetchLibraryCount(library);
        } catch (Exception e) {
            Logger.e(e);
            if (getActivity() != null) {
                Toast.makeText(getActivity(), R.string.opds_error_navigation, Toast.LENGTH_SHORT).show();
            }
            returnToLibrarySelection();
        }
    }

    /** Shared tail of both openLibrary() paths above: sets the toolbar title, marks this as the
     * current library, and navigates to its "Newest" page with no back-stack parent. */
    private void enterLibrary(OPDSLibraryBlock library, URL target) {
        mCurrentLibrary = library;
        mShowingLibrarySelection = false;
        setActivityTitle(library.name);
        if (getActivity() != null) getActivity().invalidateOptionsMenu();
        mOPDS.resetPages();
        mOPDS.discardCurrentPage();
        loadURL(target, false);
    }

    /** Re-fetches just this one library's book-count subtitle (see fetchLibraryCounts) now that
     * it has a real "By Newest" href to query -- only relevant right after
     * onLibrarySwitchResolved(), since that library's previous count (if fetched at all) came
     * from its switch-link href rather than a real book listing. */
    private void refetchLibraryCount(final OPDSLibraryBlock library) {
        URL target;
        try {
            target = new URL(library.getResolveBaseUrl(), library.defaultHref);
        } catch (Exception e) {
            return;
        }
        final String authHeader = mOPDS.getAuthorizationHeader();
        library.bookCount = OPDSLibraryBlock.COUNT_LOADING;
        OPDSLibraryStatsLoader.fetchCount(target, authHeader, new OPDSLibraryStatsLoader.Callback() {
            @Override
            public void onCount(int count) {
                library.bookCount = count;
                if (mShowingLibrarySelection && lvList != null && lvList.getAdapter() instanceof LibraryCardAdapter) {
                    ((LibraryCardAdapter) lvList.getAdapter()).notifyDataSetChanged();
                }
            }
        });
    }

    /** Back/Up from a library's default page (see loadPreviousPage()): returns to the cached
     * "choose a library" screen without any network request. */
    private void returnToLibrarySelection() {
        mCurrentLibrary = null;
        setActivityTitle(R.string.opds_select_library);
        if (getActivity() != null) getActivity().invalidateOptionsMenu();
        renderLibrarySelection();
    }

    /** "Filter by..." overflow menu item: lists every filter actually parsed from this
     * library's own slice of the root feed (built dynamically -- see OPDSLibraryBlock -- never
     * hard-coded, since different Calibre versions/servers can offer a different set of
     * filters, or label them differently). Picking one navigates exactly like tapping any other
     * catalog link (loadURL, pushing the current page as its parent), so Back/Up from the newly
     * loaded filter goes back to whichever filter was showing before it. */
    private void showFilterByDialog() {
        if (mCurrentLibrary == null || mRootFeedUrl == null || getActivity() == null) return;
        final List<String> titles = mCurrentLibrary.getFilterTitles();
        if (titles.isEmpty()) return;
        final OPDSLibraryBlock library = mCurrentLibrary;
        // Filters were parsed off of library.getResolveBaseUrl() -- the root feed itself for
        // whichever library was already active there, but the post-switch catalog page for any
        // other library (see OPDSLibraryBlock#resolveFrom) -- so that's the base each href must
        // be resolved against, not always mRootFeedUrl.
        final URL filterBaseUrl = library.getResolveBaseUrl() != null ? library.getResolveBaseUrl() : mRootFeedUrl;
        CharSequence[] items = titles.toArray(new CharSequence[titles.size()]);
        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.opds_filter_by)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String href = library.getFilterHrefAt(which);
                        if (href == null) return;
                        try {
                            loadURL(new URL(filterBaseUrl, href), false);
                        } catch (Exception e) {
                            Logger.e(e);
                            if (getActivity() != null) {
                                Toast.makeText(getActivity(), R.string.opds_error_navigation, Toast.LENGTH_SHORT).show();
                            }
                        }
                    }
                })
                .show();
    }

    /** "Sort by..." toolbar item (menu_sort_by), placed right below "Filter by..." on both
     * toolbars -- mirrors CollectionFragment#showSortByDialog for the Device Library tab.
     * Unlike that one, this tab already has Rating/Published/Series available straight from
     * the OPDS feed's entries (see OPDSPage#getBooksSorted), so those are offered here too.
     * Note this only re-orders whichever page is currently loaded -- the Content Server feed
     * here is paginated, so it can't reach into further pages still sitting on the server. */
    private void showSortByDialog() {
        if (getActivity() == null) return;
        List<SortByDialog.Option> options = new ArrayList<>();
        options.add(new SortByDialog.Option(R.drawable.ic_menu_sort, R.string.sort_field_title, OPDSPage.SORT_FIELD_TITLE));
        options.add(new SortByDialog.Option(R.drawable.ic_stat_person, R.string.sort_field_author, OPDSPage.SORT_FIELD_AUTHOR));
        options.add(new SortByDialog.Option(R.drawable.ic_action_star, R.string.sort_field_rating, OPDSPage.SORT_FIELD_RATING));
        options.add(new SortByDialog.Option(R.drawable.ic_sort_calendar, R.string.sort_field_published, OPDSPage.SORT_FIELD_PUBLISHED));
        options.add(new SortByDialog.Option(R.drawable.ic_sort_clock, R.string.sort_field_last_modified, OPDSPage.SORT_FIELD_LAST_MODIFIED));
        options.add(new SortByDialog.Option(R.drawable.ic_stat_series, R.string.sort_field_series, OPDSPage.SORT_FIELD_SERIES));
        SortByDialog.show(getActivity(), options, mSortField, mSortAscending, this::setSortOption);
    }

    /** Applies (and persists) the chosen sort field/direction, mirroring setViewColumns()
     * below. Re-runs loadCurrentPage() so OPDSPage#getBooksSorted picks it up immediately. */
    private void setSortOption(int field, boolean ascending) {
        if (mSortField == field && mSortAscending == ascending) return;
        mSortField = field;
        mSortAscending = ascending;
        mSettings.edit()
                .putInt(PREF_OPDS_SORT_FIELD, field)
                .putBoolean(PREF_OPDS_SORT_ASCENDING, ascending)
                .apply();
        loadCurrentPage();
    }

    /** Tim kiem NGAY BEN TRONG thu vien GDrive dang duyet, dieu huong y het 1 lan bam filter (xem
     * showFilterByDialog()) thay vi mo 1 Activity tim kiem toan cuc khac -- goi lai chinh URL feed
     * hien tai kem tham so ?search=, GDriveOpdsHttpServer da ho tro san tham so nay
     * (GDriveLibraryDb#queryBooksCursor). Rong -> xoa bo loc tim kiem, quay lai danh sach day du. */
    private void performGDriveSearch(String query) {
        if (getActivity() == null) return;
        URL base = (currentPage != null) ? currentPage.peekURL() : urlCSRoot;
        if (base == null) return;
        try {
            String q = query == null ? "" : query.trim();
            String href = "/?search=" + java.net.URLEncoder.encode(q, "UTF-8");
            loadURL(new URL(base, href), false);
        } catch (Exception e) {
            Logger.e(e);
            Toast.makeText(getActivity(), R.string.opds_error_navigation, Toast.LENGTH_SHORT).show();
        }
    }

    /** Tim kiem NGAY BEN TRONG thu vien Calibre Content Server (OPDS) that su dang duyet.
     * TRUOC DAY man hinh nay khong tu tim kiem ma di qua Intent.ACTION_SEARCH toi
     * BookSearchActivity -- activity do chi query MetadataDatabaseHelper (CSDL sach DA TAI VE
     * MAY), hoan toan khong lien quan gi den catalog tren server. Voi mot thu vien OPDS dang
     * duyet (thuong la sach CHUA tai ve), ket qua gan nhu luon rong -- va vi BookSearchActivity
     * khong co empty-state (chi la RecyclerView trong tren nen den), nguoi dung thay "man hinh
     * den xi" thay vi thong bao khong co ket qua. Fix: tim that su tren server, dung
     * <link rel="search"> ma feed goc/hien tai quang cao (chuan OpenSearch -- rel="search" tro
     * toi 1 tai lieu OpenSearchDescription nho chua URL template thuc su, KHONG phai URL tim
     * kiem truc tiep, xem OPDSLoader#resolveOpenSearchTemplate()). */
    private void performCalibreServerSearch(String query) {
        if (getActivity() == null || currentPage == null) return;
        String descriptorHref = currentPage.getNavigationalLinks().get("search");
        if (descriptorHref == null) {
            // Server khong quang cao OpenSearch (hiem, nhung co the xay ra voi server OPDS tu
            // che khac Calibre) -- bao ro cho nguoi dung thay vi im lang hien man hinh rong.
            Toast.makeText(getActivity(), R.string.opds_search_not_supported, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            URL descriptorUrl = new URL(currentPage.peekURL(), descriptorHref);
            if (descriptorUrl.toString().equals(cachedOpenSearchDescriptorUrl)
                    && cachedOpenSearchTemplate != null) {
                loadCalibreSearchUrl(cachedOpenSearchTemplate, query);
                return;
            }
            if (pbProgress != null) pbProgress.setVisibility(View.VISIBLE);
            mOPDS.resolveOpenSearchTemplate(descriptorUrl, new OPDSLoader.OpenSearchTemplateCallback() {
                @Override
                public void onResult(String template) {
                    if (getActivity() == null) return;
                    if (pbProgress != null) pbProgress.setVisibility(View.GONE);
                    if (template == null) {
                        Toast.makeText(getActivity(), R.string.opds_search_not_supported, Toast.LENGTH_LONG).show();
                        return;
                    }
                    cachedOpenSearchDescriptorUrl = descriptorUrl.toString();
                    cachedOpenSearchTemplate = template;
                    loadCalibreSearchUrl(template, query);
                }
            });
        } catch (Exception e) {
            Logger.e(e);
            Toast.makeText(getActivity(), R.string.opds_error_navigation, Toast.LENGTH_SHORT).show();
        }
    }

    /** Thay {searchTerms} trong URL template OpenSearch bang tu khoa da ma hoa roi tai trang do,
     * y het 1 lan dieu huong binh thuong (loadURL). */
    private void loadCalibreSearchUrl(String template, String query) {
        if (currentPage == null) return;
        try {
            String q = query == null ? "" : query.trim();
            String encoded = java.net.URLEncoder.encode(q, "UTF-8");
            String href = template.replace("{searchTerms}", encoded).replace("%7BsearchTerms%7D", encoded);
            loadURL(new URL(currentPage.peekURL(), href), false);
        } catch (Exception e) {
            Logger.e(e);
            if (getActivity() != null) {
                Toast.makeText(getActivity(), R.string.opds_error_navigation, Toast.LENGTH_SHORT).show();
            }
        }
    }

    public void loadCurrentPage() {
        if (currentPage == null || lvList == null) return;
        mShowingLibrarySelection = false;
        updateHomeUpIndicator();
        if (currentPage.hasCatalogLinks()) {
            // Folder/category navigation (e.g. "By Author", "By Tags", "Library: ..."): always
            // a single-column list, regardless of the book grid's display mode -- a multi-column
            // grid of plain text rows doesn't make sense here.
            lvList.setNumColumns(1);
            ArrayAdapter<String> aa = new ArrayAdapter<String>(getActivity(),android.R.layout.simple_list_item_1);
            for (String s : currentPage.getCatalogLinks()) {
                aa.add(s);
            }
            lvList.setAdapter(aa);
        } else {
            // Guarded (unlike an unconditional call) so simply re-running loadCurrentPage() with
            // the SAME column count -- e.g. onStart() re-running this after a plain Back from
            // previewing a book -- doesn't poke GridView's layout for no reason.
            if (lvList.getNumColumns() != mViewColumns) lvList.setNumColumns(mViewColumns);
            // Apply the current "Sort by..." choice before deciding whether the adapter needs
            // rebuilding. getBooksSorted() only rebuilds books_cursor (MatrixCursor has no
            // in-place reorder) when sortField/ascending actually changed since its last call, or
            // new rows were added since (see OPDSPage#addBook) -- otherwise it hands back that
            // exact same cursor instance, which is what lets alreadyLiveRendered below recognise
            // "nothing actually changed" and skip rebuilding the adapter too. That, in turn, is
            // what lets returning from a book preview resume the GridView at its previous scroll
            // position instead of jumping back to the top: loadCurrentPage() re-runs on every
            // onStart() (see this Fragment's onStart()), including a plain Back with the sort
            // unchanged, and a freshly-`new`ed adapter/cursor pair would otherwise reset it every
            // single time regardless of whether anything actually needed re-rendering.
            currentPage.getBooksSorted(mSortField, mSortAscending);
            // Neu trang nay da duoc onBookAdded() live-render san (truong hop thuong gap: feed
            // vua tai xong dung la trang dang hien tren man hinh) thi adapter hien tai da gan
            // dung vao currentPage.getBooks() roi -- tao lai adapter moi o day se lam GridView
            // nhay ve dau danh sach (mat vi tri cuon) 1 cach khong can thiet.
            boolean alreadyLiveRendered = currentPage == mLiveRenderedPage
                    && lvList.getAdapter() instanceof BookCursorAdapter
                    && ((BookCursorAdapter) lvList.getAdapter()).getCursor() == currentPage.getBooks();
            if (!alreadyLiveRendered) {
                lvList.setAdapter(new BookCursorAdapter(getActivity(), currentPage.getBooks(), false,
                        mViewColumns > 1, currentPage, mBookExtrasMap, mGridStyle));
            }
        }
        updateCurrentPage();
    }
    public void updateCurrentPage() {
        if (currentPage == null || tvMore == null || lvList == null) return;
        if (currentPage.getNavigationalLinks().containsKey("next")) {
            tvMore.setVisibility(TextView.VISIBLE);
            tvMore.setText(getString(R.string.opds_more));

        } else {
            tvMore.setVisibility(TextView.GONE);
        }
        try {
            ((BaseAdapter) lvList.getAdapter()).notifyDataSetChanged();
        } catch (NullPointerException e) {
            e.printStackTrace();
        }
    }

    public void loadURL(URL url, boolean more) {
        if (mOPDS.beginParsingXml(url, more)) {
            pbProgress.setVisibility(View.VISIBLE);
            //tvDump.setVisibility(View.VISIBLE);
            //tvDump.setText(String.format(getString(R.string.opds_loading), url.toString()));
        }
    }

    /** Opens the "Book Details" screen for a tapped book instead of downloading/opening it
     * straight away -- see BookDetailActivity, which now owns the download/read/format-chooser
     * flow that used to live directly in this onItemClick handler. */
    private void openBookDetail(OPDSEntry entry) {
        if (getActivity() == null) return;
        Intent i = new Intent(getActivity(), BookDetailActivity.class);
        i.putExtra(BookDetailActivity.EXTRA_ENTRY, entry);
        i.putExtra(BookDetailActivity.EXTRA_PAGE_URL, currentPage.peekURL().toString());
        i.putExtra(BookDetailActivity.EXTRA_AUTH_HEADER, mOPDS.getAuthorizationHeader());
        // Kem theo ca danh sach sach dang hien thi TREN TRANG OPDS nay (dung thu tu sort hien
        // tai, giong het cursor dang ve luoi) de BookDetailActivity co the vuot trai/phai sang
        // cuon ke tiep/truoc do -- xem javadoc OpdsBookListHolder ve ly do PHAI giu danh sach nay
        // trong bo nho (OpdsBookListHolder) thay vi nhet thang vao Intent extra: 1 trang OPDS co
        // the co hang chuc cuon, moi cuon kem 1 anh bia -- serialize ca danh sach vao Intent rat
        // de vuot gioi han kich thuoc giao dich Binder (~1MB), gay TransactionTooLargeException
        // ngay tai startActivity() (tung bi nuot mat boi try/catch chung quanh onItemClick, hien
        // nham thanh Toast "Couldn't open that link"). Cung ly do gioi han trong pham vi trang
        // dang tai (khong the vuot sang trang OPDS ke tiep con nam tren server), giong han che
        // getBooksSorted() da ghi trong OPDSPage.
        List<OPDSEntry> sortedEntries = currentPage.getEntriesSorted(mSortField, mSortAscending);
        OpdsBookListHolder.set(sortedEntries);
        i.putExtra(BookDetailActivity.EXTRA_BOOK_LIST_FROM_OPDS_HOLDER, true);
        i.putExtra(BookDetailActivity.EXTRA_BOOK_LIST_INDEX, sortedEntries.indexOf(entry));
        startActivity(i);
    }

    // ---- HeaderContentProvider (nang cap header gon + Drawer duy nhat) --------------------

    @Override
    public String getHeaderTitle() {
        return mHeaderTitle != null ? mHeaderTitle : getString(R.string.tab_library);
    }

    @Override
    public String getHeaderCountLabel() {
        // Chi hien khi dang duyet mot thu vien cu the VA server/OPDSLibraryStatsLoader da tra
        // ve duoc tong so sach (COUNT_LOADING/COUNT_UNKNOWN deu am, xem OPDSLibraryBlock) --
        // trang "chon thu vien" hoac trang dang tai khong co con so nay de hien.
        if (mCurrentLibrary != null && mCurrentLibrary.bookCount >= 0) {
            int count = mCurrentLibrary.bookCount;
            return getResources().getQuantityString(R.plurals.opds_library_book_count, count, count);
        }
        return null;
    }

    @Override
    public boolean supportsHeaderSearch() {
        // Giong dieu kien an/hien menu_search cu trong onCreateOptionsMenu.
        return !mShowingGDriveOfflineLibraries;
    }

    @Override
    public void onHeaderSearchQuery(String query) {
        if (isGDriveLibrary()) {
            // Thu vien GDrive phai tim NGAY BEN TRONG thu vien dang duyet, khong duoc di qua
            // SearchManager toan cuc -- xem chu thich goc trong onCreateOptionsMenu() ben tren.
            performGDriveSearch(query);
        } else {
            // BUG FIX: truoc day nhanh nay mo BookSearchActivity qua Intent.ACTION_SEARCH --
            // activity do chi tim trong sach DA TAI VE MAY (MetadataDatabaseHelper), khong lien
            // quan gi den catalog cua Content Server dang duyet. Voi thu vien OPDS (thuong toan
            // sach chua tai ve), ket qua gan nhu luon rong va hien ra nhu "man hinh den xi" vi
            // BookSearchActivity khong co empty-state. Gio tim that su tren server, cung 1 co
            // che voi thu vien GDrive (chi khac nguon URL template, xem
            // performCalibreServerSearch()).
            performCalibreServerSearch(query);
        }
    }

    @Override
    public void onHeaderRefresh() {
        if (mShowingGDriveOfflineLibraries) {
            retryConnectToServer();
        } else if (currentPage != null) {
            loadURL(currentPage.peekURL(), false);
        } else if (urlCSRoot != null) {
            loadRootFeed();
        }
    }

    @Override
    public void onHeaderOverflowClick(View anchorView) {
        androidx.appcompat.widget.PopupMenu popup =
                new androidx.appcompat.widget.PopupMenu(requireContext(), anchorView);
        popup.inflate(R.menu.opds_fragment);
        // popup.inflate() chi nap tinh XML, khong goi lai onCreateOptionsMenu() -- lap lai dung
        // cac dieu kien an/hien no da ap dung o do (tru menu_search, header da co nut rieng).
        Menu menu = popup.getMenu();
        MenuItem searchItem = menu.findItem(R.id.menu_search);
        if (searchItem != null) searchItem.setVisible(false);
        MenuItem filterItem = menu.findItem(R.id.menu_filter_by);
        if (filterItem != null) filterItem.setVisible(mCurrentLibrary != null);
        MenuItem viewModeItem = menu.findItem(R.id.menu_view_mode);
        if (viewModeItem != null)
            viewModeItem.setVisible(!mShowingLibrarySelection && !mShowingGDriveOfflineLibraries);
        MenuItem sortByItem = menu.findItem(R.id.menu_sort_by);
        if (sortByItem != null) {
            boolean showingBookGrid = !mShowingLibrarySelection && !mShowingGDriveOfflineLibraries
                    && currentPage != null && !currentPage.hasCatalogLinks();
            sortByItem.setVisible(showingBookGrid);
        }
        // PopupMenu chi dung de inflate/giu Menu (icon da khai bao trong XML) -- khong goi
        // popup.show() nua, vi PopupMenu he thong an icon va khong ve vach chia nhom.
        // HeaderOverflowMenu doc lai dung Menu nay de tu ve popup rieng (xem class do).
        HeaderOverflowMenu overflowMenu = new HeaderOverflowMenu(requireContext(), menu);
        overflowMenu.setOnMenuItemClickListener(this::onOptionsItemSelected);
        overflowMenu.show(anchorView);
    }

}