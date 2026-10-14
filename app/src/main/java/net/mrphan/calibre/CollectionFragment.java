package net.mrphan.calibre;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Device Library tab: books already downloaded to the device (mDB.getNotNull(..., "lpath")).
 * <p>
 * Upgraded from a GridView + BookCursorAdapter (CursorAdapter) to a RecyclerView +
 * BookRecyclerAdapter, which is what makes swipe-to-reveal (SwipeRevealCallback /
 * ItemTouchHelper) and the new Simple list / Carousel display modes possible -- ItemTouchHelper
 * only works on RecyclerView. BookCursorAdapter itself is untouched and still used by
 * BookSearchActivity and OPDSBrowserFragment (Calibre Library tab); this fragment now has its
 * own adapter so those other call sites can't be affected by anything below.
 */
public class CollectionFragment extends Fragment implements BookRecyclerAdapter.Listener,
        HeaderContentProvider {

	public interface CollectionFragmentListener {
		public void onBookLongClick(Bundle bundle);

        // public void onBookClick(String s);

        /** Asks the host activity to run a book-folder rescan through CalibreService (the
         * fragment has no direct reference to the bound service), and report back when done. */
        void requestRescan(RescanCallback callback);

        /** Reports that a book was deleted, so the host activity can ask CalibreService to
         * re-sync metadata.calibre without doing a full disk rescan. */
        void onBookDeleted();

        /** Reports multi-select mode entering/exiting, so the host activity can temporarily hide
         * its "Continue reading" FAB while the selection pill is showing (both float at the
         * bottom of the screen and would otherwise overlap) -- see MainActivity's
         * refreshContinueReadingFabVisibility() and its collectionSelectionModeActive flag. */
        void onCollectionSelectionModeChanged(boolean active);
    }

    /** Callback for requestRescan() above. */
    public interface RescanCallback {
        void onRescanFinished(int newBooksFound);
    }
    public static String TAB_TAG = "tab_collection";
	private RecyclerView lvBooklist;
	private Cursor booklistCursor;
	private static MetadataDatabaseHelper mDB;
	private boolean isShowingTags = false;
	/** Mirrors isShowingTags for the "Filter by... > Author" screen -- see showAuthors(). The
	 * two are mutually exclusive (only one category list, or the book grid, is ever shown at
	 * once), kept as separate booleans rather than merged into isShowingTags so existing call
	 * sites that only cared about "is a book actually visible right now" can just check both. */
	private boolean isShowingAuthors = false;
	/** Covers the newer "Filter by... > Series/Publisher/<Custom Column>" screens added in
	 * DATABASE_VERSION 13 -- unlike isShowingTags/isShowingAuthors (one fixed screen each), these
	 * are dynamic (any number of Custom Columns), so a single flag + "how to redraw the current
	 * one" callback is used instead of one boolean per screen. See showSeriesFilter()/
	 * showPublisherFilter()/showCustomColumnFilter() below, and every isShowingTags/
	 * isShowingAuthors check elsewhere in this file, all of which now also check this flag. */
	private boolean isShowingOtherFilterList = false;
	private Runnable mReshowOtherFilterList = null;
	private CollectionFragmentListener mListener;

	private BookRecyclerAdapter mAdapter;
	private TagAdapter mTagAdapter;
	private AuthorAdapter mAuthorAdapter;
	/** Backs whichever of Series/Publisher/<Custom Column> is currently showing -- see
	 * isShowingOtherFilterList. Reuses AuthorAdapter as-is: every one of these screens is the
	 * exact same shape (a single free-text column, click hands back that row's string), so a
	 * dedicated SeriesAdapter/PublisherAdapter/CustomColumnAdapter would just be copies of it. */
	private AuthorAdapter mValueListAdapter;
	private ItemTouchHelper mTouchHelper;
	private final PagerSnapHelper mSnapHelper = new PagerSnapHelper();

	/** The LayoutManager instance showBooks() last attached to lvBooklist for the book grid,
	 * and the ViewMode it was built for -- lets showBooks() tell "still the same book-grid
	 * layout as last time" apart from "view mode changed" or "lvBooklist was showing something
	 * else (tags/authors/other filter list) in between". Only replacing the LayoutManager when
	 * one of those actually happened (instead of unconditionally on every call) is what lets
	 * returning from previewing a book -- which re-runs showBooks() via onStart() ->
	 * refreshRootDirUiState() -- resume browsing at the same scroll position instead of jumping
	 * back to the top: a fresh LayoutManager instance has no memory of where the old one had
	 * scrolled to, even though the RecyclerView itself was never destroyed. */
	private RecyclerView.LayoutManager mBooksLayoutManager;
	private ViewMode mBooksLayoutViewMode;

	/** Drives the 3D Cover Flow transform (scale/alpha/rotationY/elevation) on lvBooklist's
	 * children while ViewMode.CAROUSEL is active -- see applyCarouselChrome(). */
	private CarouselCoverFlowEffect mCarouselEffect;

	/** Carousel card width in dp -- MUST stay in sync with item_book_carousel.xml's root
	 * FrameLayout width (see the comment on that file). Used to compute the RecyclerView's
	 * side padding that centers the first/last card, see layoutCarouselChrome(). */
	private static final int CAROUSEL_ITEM_WIDTH_DP = 260;
	/** Total vertical space one carousel card occupies in dp -- top margin (12) + cover height
	 * (380) + bottom margin (12) from item_book_carousel.xml. MUST stay in sync with that file.
	 * Used together with CAROUSEL_DETAILS_GAP_DP below to vertically center the whole
	 * "cover + title + author + dots" block, see layoutCarouselChrome(). */
	private static final int CAROUSEL_ITEM_HEIGHT_DP = 404;
	/** Gap in dp between the bottom edge of the centered cover and the top of the title text,
	 * see layoutCarouselChrome(). */
	private static final int CAROUSEL_DETAILS_GAP_DP = 20;

	private View carouselDetails;
	private TextView carouselTitle;
	private TextView carouselAuthor;
	private CarouselDotsIndicatorView carouselDots;
	private ImageButton carouselPrev;
	private ImageButton carouselNext;

	private View emptyState;
	private TextView emptyStateMessage;
	private Button emptyStateAction;
	/** true once this tab has auto-scanned the disk at least once this session (avoids
	 * rescanning every time the tab is switched back to -- only rescans again when the folder
	 * changes or the user taps the manual "Show books" action). */
	private boolean hasAutoScannedThisSession = false;

	/** Ids of the currently multi-selected books, only meaningful while mSelectionMode is true. */
	private final Set<Integer> mSelectedIds = new LinkedHashSet<>();
	private boolean mSelectionMode = false;
	private OnBackPressedCallback mBackPressedCallback;

	/** Floating "pill" quick-action bar shown at the bottom of the screen while mSelectionMode
	 * is active -- a duplicate of the existing header 3-dot overflow menu
	 * (collection_selection_mode.xml / onHeaderOverflowClick), NOT a replacement for it. See
	 * view_collection_selection_pill.xml for the full rationale. */
	private View mSelectionPill;
	/** bottomMargin the pill was laid out with in activity_collection.xml (16dp), i.e. its
	 * position when net.mrphan.calibre.reader.tts.GlobalTtsFloatingPanel is NOT showing --
	 * captured once in onCreateView() so repositionSelectionPill() has a stable baseline to add
	 * the music panel's height on top of, rather than hardcoding 16dp twice. */
	private int mSelectionPillBaseMarginPx = 0;
	/** True while net.mrphan.calibre.reader.tts.GlobalTtsFloatingPanel (the separate floating
	 * TTS/music mini player, attached to android.R.id.content by MainActivity, outside this
	 * fragment's own view tree) is visible -- see onMusicPanelVisibilityChanged() /
	 * repositionSelectionPill(). */
	private boolean mMusicPanelVisible = false;

	/** Nút "+" (menu_import_book) trên toolbar Device Library -- cho phep nguoi dung chon 1
	 * hoac nhieu file sach bat ky tu SAF (Files, Google Drive app, v.v.) va copy thang vao
	 * thu muc sach hien tai, khong can qua Calibre server/cloud sync. Dung OpenMultipleDocuments
	 * (khong phai GetContent) de ho tro chon nhieu file cung luc, giong MetadataEditorActivity's
	 * pickImage nhung cho phep multi-select. */
	private final androidx.activity.result.ActivityResultLauncher<String[]> mImportBookPicker =
			registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(), uris -> {
				if (uris != null && !uris.isEmpty()) importBookFiles(uris);
			});

	/** Persists the Device Library tab's chosen display mode across app restarts (mirrors
	 * OPDSBrowserFragment's PREF_OPDS_VIEW_COLUMNS for the Calibre Library tab). Historically
	 * this stored a plain column count (1/2/3/4); those four values are kept as-is below so
	 * upgrading the app doesn't reset an existing user's preference, with two new values (5, 6)
	 * added for Simple list / Carousel. */
	private static final String PREF_COLLECTION_VIEW_COLUMNS = "pref_collection_view_columns";
	private SharedPreferences mSettings;
	private ViewMode mViewMode = ViewMode.LIST;

	/** Persists the Device Library tab's chosen "Sort by..." field/direction across app
	 * restarts (mirrors PREF_COLLECTION_VIEW_COLUMNS above). See
	 * MetadataDatabaseHelper#getBooksSorted for what SORT_FIELD_* actually means. */
	private static final String PREF_COLLECTION_SORT_FIELD = "pref_collection_sort_field";
	private static final String PREF_COLLECTION_SORT_ASCENDING = "pref_collection_sort_ascending";
	/** Only meaningful when mSortField == CUSTOM_COLUMN_SORT_FIELD_ID -- see mSortCustomColumnLookup. */
	private static final String PREF_COLLECTION_SORT_CUSTOM_COLUMN = "pref_collection_sort_custom_column";
	private int mSortField = MetadataDatabaseHelper.SORT_FIELD_TITLE;
	private boolean mSortAscending = true;
	/** Stable persisted sentinel meaning "sort by a Custom Column", paired with
	 * mSortCustomColumnLookup for which one. Needed because showSortByDialog() hands out
	 * per-column ids starting at CUSTOM_COLUMN_SORT_FIELD_BASE fresh every time it opens (a
	 * Custom Column's position/count can change between syncs), so those transient ids aren't
	 * safe to persist across app restarts the way the fixed SORT_FIELD_* ones are. */
	private static final int CUSTOM_COLUMN_SORT_FIELD_ID = -100;
	private static final int CUSTOM_COLUMN_SORT_FIELD_BASE = 1000;
	/** lookup_name of the Custom Column currently sorted by, only meaningful when
	 * mSortField == CUSTOM_COLUMN_SORT_FIELD_ID. */
	private String mSortCustomColumnLookup = null;
	/** dialog-local id -> lookup_name, rebuilt every time showSortByDialog() opens; only valid
	 * for the lifetime of that one dialog (see setSortOption(), which resolves the id the user
	 * tapped back to a lookup_name via this map before it can go stale). */
	private final java.util.Map<Integer, String> mCustomColumnSortFields = new java.util.HashMap<>();

	private enum ViewMode {
		LIST(1, 1), GRID_2(2, 2), GRID_3(3, 3), GRID_4(4, 4), SIMPLE_LIST(5, 1), CAROUSEL(6, 1);

		final int prefValue;
		final int columns;

		ViewMode(int prefValue, int columns) {
			this.prefValue = prefValue;
			this.columns = columns;
		}

		static ViewMode fromPrefValue(int v) {
			for (ViewMode m : values()) if (m.prefValue == v) return m;
			return LIST;
		}
	}

	private static BookRecyclerAdapter.ViewMode toAdapterMode(ViewMode m) {
		switch (m) {
			case SIMPLE_LIST: return BookRecyclerAdapter.ViewMode.SIMPLE_LIST;
			case CAROUSEL: return BookRecyclerAdapter.ViewMode.CAROUSEL;
			case GRID_2:
			case GRID_3:
			case GRID_4: return BookRecyclerAdapter.ViewMode.GRID;
			default: return BookRecyclerAdapter.ViewMode.LIST;
		}
	}

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        // Verify that the host activity implements the callback interface
        try {
            mListener = (CollectionFragmentListener) activity;
        } catch (ClassCastException e) {
            // The activity doesn't implement the interface, throw exception
            throw new ClassCastException(activity.toString()
                    + " must implement CollectionFragmentListener");
        }
    }
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setHasOptionsMenu(true);
        mDB = new MetadataDatabaseHelper(getActivity());
        mSettings = PreferenceManager.getDefaultSharedPreferences(getActivity().getApplicationContext());
        mViewMode = ViewMode.fromPrefValue(mSettings.getInt(PREF_COLLECTION_VIEW_COLUMNS, 1));
        mGridStyle = GridDisplayStyle.readFrom(mSettings);
        mSortField = mSettings.getInt(PREF_COLLECTION_SORT_FIELD, MetadataDatabaseHelper.SORT_FIELD_TITLE);
        mSortAscending = mSettings.getBoolean(PREF_COLLECTION_SORT_ASCENDING, true);
        mSortCustomColumnLookup = mSettings.getString(PREF_COLLECTION_SORT_CUSTOM_COLUMN, null);
        if (mSortField == CUSTOM_COLUMN_SORT_FIELD_ID && mSortCustomColumnLookup == null) {
            // Corrupted/half-written prefs (shouldn't normally happen, setSortOption() always
            // writes both together) -- fall back rather than querying a Custom Column sort with
            // no lookup_name to join on.
            mSortField = MetadataDatabaseHelper.SORT_FIELD_TITLE;
        }

		mBackPressedCallback = new OnBackPressedCallback(false) {
			@Override
			public void handleOnBackPressed() {
				exitSelectionMode();
			}
		};
		requireActivity().getOnBackPressedDispatcher().addCallback(this, mBackPressedCallback);
	}
	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {
		View v = inflater.inflate(R.layout.activity_collection, container, false);
		lvBooklist = v.findViewById(R.id.col_list);
		emptyState = v.findViewById(R.id.col_empty_state);
		emptyStateMessage = v.findViewById(R.id.col_empty_state_message);
		emptyStateAction = v.findViewById(R.id.col_empty_state_action);
		emptyStateAction.setOnClickListener(view -> openBookFolderPicker());
		booklistCursor = loadSortedBooklistCursor();
		observeSyncWork();

		carouselDetails = v.findViewById(R.id.col_carousel_details);
		carouselTitle = v.findViewById(R.id.col_carousel_title);
		carouselAuthor = v.findViewById(R.id.col_carousel_author);
		carouselDots = v.findViewById(R.id.col_carousel_dots);
		carouselPrev = v.findViewById(R.id.col_carousel_prev);
		carouselNext = v.findViewById(R.id.col_carousel_next);
		mCarouselEffect = new CarouselCoverFlowEffect(lvBooklist, this::updateCarouselDetails);
		carouselPrev.setOnClickListener(view -> scrollCarouselBy(-1));
		carouselNext.setOnClickListener(view -> scrollCarouselBy(1));

		setupSelectionPill(v);

		return v;
	}

	/** Finds the floating selection pill (<include>d in activity_collection.xml) and wires each
	 * button straight to the SAME private methods the header 3-dot overflow menu already calls
	 * via onOptionsItemSelected() -- no MenuItem, no onOptionsItemSelected round-trip, since
	 * this is the same class. Called once from onCreateView(). */
	private void setupSelectionPill(View root) {
		mSelectionPill = root.findViewById(R.id.collection_selection_pill);
		if (mSelectionPill == null) return; // shouldn't happen, guard just in case of layout drift

		ViewGroup.LayoutParams lp = mSelectionPill.getLayoutParams();
		if (lp instanceof ViewGroup.MarginLayoutParams) {
			mSelectionPillBaseMarginPx = ((ViewGroup.MarginLayoutParams) lp).bottomMargin;
		}

		root.findViewById(R.id.pill_select_all).setOnClickListener(view -> selectAll());
		root.findViewById(R.id.pill_sync).setOnClickListener(view -> syncSelectedToCloud());
		root.findViewById(R.id.pill_share).setOnClickListener(view -> shareSelected());
		root.findViewById(R.id.pill_delete).setOnClickListener(view -> confirmDeleteSelected());
		root.findViewById(R.id.pill_exit).setOnClickListener(view -> exitSelectionMode());

		// Tint the delete icon/label with the same "destructive" red HeaderOverflowMenu uses for
		// this same action in the header 3-dot popup, so both places stay visually consistent.
		int destructiveColor = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.color_action_destructive);
		android.widget.ImageView deleteIcon = root.findViewById(R.id.pill_delete_icon);
		deleteIcon.setColorFilter(destructiveColor, android.graphics.PorterDuff.Mode.SRC_IN);
		TextView deleteLabel = root.findViewById(R.id.pill_delete_label);
		deleteLabel.setTextColor(destructiveColor);
	}
	@Override
	public void onDestroy() {
		super.onDestroy();
		mDB.close();
	}
	
	@Override
	public void onStart() {
		super.onStart();
        refreshRootDirUiState();
        refreshGridDisplayOptions();
        repositionSelectionPill();
	}

	/** Settings > Giao dien: "Show title and progress in grid view". Not handled via
	 * OnSharedPreferenceChangeListener (see mRootDirListener below) because that only catches
	 * changes happening while registered (onResume..onPause) -- Settings is always opened after
	 * this tab has already onPause()'d, so the listener would miss the change. Read fresh every
	 * time the tab comes back to foreground instead. */
	private boolean mShowGridTitleProgress = true;
	private GridDisplayStyle mGridStyle = GridDisplayStyle.DEFAULT;

	private void refreshGridDisplayOptions() {
		boolean show = mSettings.getBoolean(StorageUtils.PREF_GRID_SHOW_TITLE_PROGRESS, true);
		if (show != mShowGridTitleProgress) {
			mShowGridTitleProgress = show;
			if (mAdapter != null) mAdapter.setShowGridTitleProgress(mShowGridTitleProgress);
		}
		GridDisplayStyle style = GridDisplayStyle.readFrom(mSettings);
		if (style != mGridStyle) {
			mGridStyle = style;
			if (mAdapter != null) mAdapter.setGridStyle(mGridStyle);
			applyGridBackground();
		}
	}

	/** Nen cua lvBooklist doi theo Style Grid: "Gia sach" dung nen go am (bg_bookshelf_wall), cac
	 * style con lai tra ve nen mac dinh cua theme. Goi tu refreshGridDisplayOptions() (khi doi tu
	 * man hinh Settings quay lai) va tu showBooks() (khi tab duoc dung/hien lan dau). */
	private void applyGridBackground() {
		if (lvBooklist == null) return;
		if (mGridStyle == GridDisplayStyle.BOOKSHELF) {
			lvBooklist.setBackgroundResource(R.drawable.bg_bookshelf_wall);
		} else {
			lvBooklist.setBackground(null);
		}
	}

	private final SharedPreferences.OnSharedPreferenceChangeListener mRootDirListener =
			(sharedPreferences, key) -> {
				if (StorageUtils.PREF_ROOT_DIR_PATH.equals(key)) {
					hasAutoScannedThisSession = false; // force a rescan, the folder just changed
					refreshRootDirUiState();
				}
			};

	@Override
	public void onResume() {
		super.onResume();
		mSettings.registerOnSharedPreferenceChangeListener(mRootDirListener);
		// buildAutoSyncMenuTitle() doc CloudRemoteSyncPrefs, nhung gia tri do co the vua duoc
		// ghi trong luc tab nay KHONG o foreground -- vi du dong bo qua nut "Dong bo" trong dialog
		// "Thong tin thu vien cloud" o Settings > Ket noi, luc do observeSyncWork() cua fragment
		// nay co the da bi huy dang ky (view truoc do bi destroy). onPrepareOptionsMenu() chi tinh
		// lai title khi he thong duoc yeu cau invalidate, nen neu khong chu dong goi o day, menu se
		// tiep tuc hien gia tri CU (vd "chua tung dong bo") cho den lan invalidate ke tiep, du du
		// lieu that ra da moi. Goi lai moi lan tab nay tro lai foreground de dam bao luon dung, bat
		// ke dong bo duoc kich hoat tu dau.
		Activity activity = getActivity();
		if (activity != null) activity.invalidateOptionsMenu();
	}

	@Override
	public void onPause() {
		super.onPause();
		mSettings.unregisterOnSharedPreferenceChangeListener(mRootDirListener);
	}

	/** Called whenever the tab becomes visible again (onStart) and whenever pref_root_dir_path
	 * changes (see mRootDirListener above). Decides whether to show the empty-state or the real
	 * book list, and auto-triggers the first disk scan of the session once the folder is ready. */
	private void refreshRootDirUiState() {
		if (getActivity() == null) return;
		StorageUtils.RootDirState state = StorageUtils.getRootDirState(
				getActivity().getApplicationContext(), mSettings);
		switch (state) {
			case NOT_CONFIGURED:
				showEmptyState(R.string.collection_empty_not_configured,
						R.string.collection_empty_action_choose_folder);
				return;
			case NO_PERMISSION:
				showEmptyState(R.string.collection_empty_no_permission,
						R.string.collection_empty_action_grant_permission);
				return;
			case DIR_MISSING:
				showEmptyState(R.string.collection_empty_dir_missing,
						R.string.collection_empty_action_choose_again);
				return;
			case READY:
				hideEmptyState();
				if (!hasAutoScannedThisSession) {
					hasAutoScannedThisSession = true;
					triggerRescan(false);
				} else {
					showBooks();
				}
				return;
		}
	}

	private void showEmptyState(int messageResId, int actionResId) {
		emptyStateMessage.setText(messageResId);
		emptyStateAction.setText(actionResId);
		emptyStateAction.setVisibility(View.VISIBLE);
		emptyState.setVisibility(View.VISIBLE);
		lvBooklist.setVisibility(View.GONE);
	}

	/** Same as showEmptyState(), but with no action button -- used by showTags() below when
	 * there are no tags yet. There's no single relevant action to offer here (unlike "no book
	 * folder configured", which can offer "Choose folder"), so the button is just hidden instead
	 * of wired to something unrelated like openBookFolderPicker(). */
	private void showEmptyStateMessageOnly(int messageResId) {
		emptyStateMessage.setText(messageResId);
		emptyStateAction.setVisibility(View.GONE);
		emptyState.setVisibility(View.VISIBLE);
		lvBooklist.setVisibility(View.GONE);
	}

	private void hideEmptyState() {
		emptyState.setVisibility(View.GONE);
		lvBooklist.setVisibility(View.VISIBLE);
	}

	private void openBookFolderPicker() {
		if (getActivity() == null) return;
		// Simplest, lowest-risk approach: open the Settings sheet directly and reuse its existing
		// SAF/MANAGE_EXTERNAL_STORAGE permission logic instead of duplicating it here.
		// autoOpenFolderPicker=true makes SettingsSheet jump to the Storage tab and auto-tap
		// "Book Folder" as soon as it opens (was SettingsActivity#EXTRA_AUTO_OPEN_FOLDER_PICKER).
		new SettingsSheet(true).show(getChildFragmentManager(), SettingsSheet.TAG);
	}

	/** Shared by both the first auto-scan of the session and the manual "Show books" menu
	 * action. */
	private void triggerRescan(boolean showToastOnResult) {
		if (mListener == null) return;
		mListener.requestRescan(newBooksFound -> {
			booklistCursor = loadSortedBooklistCursor();
			showBooks();
			if (showToastOnResult && getActivity() != null) {
				String msg = newBooksFound > 0
						? getString(R.string.toast_rescan_found_books, newBooksFound)
						: getString(R.string.toast_rescan_no_new_books);
				Toast.makeText(getActivity(), msg, Toast.LENGTH_SHORT).show();
			}
		});
	}

	// ---- Manual "Import book" (dấu + trên toolbar) ---------------------------------------

	private void openImportBookPicker() {
		if (getActivity() == null) return;
		// "*/*" thay vi cac MIME type cu the: epub/mobi/azw3/fb2/pdb khong co MIME chuan duoc
		// moi DocumentsProvider nhan dien dung, loc that su dua tren duoi file o
		// importBookFiles() ben duoi (giong danh sach "Accepted Filetypes" trong Settings).
		mImportBookPicker.launch(new String[]{"*/*"});
	}

	/** Copy moi file da chon vao thu muc sach hien tai (StorageUtils.getRootDirectory()) roi
	 * kich hoat rescan giong het "Show books" thu cong -- day la cach don gian nhat de sach
	 * moi nhap xuat hien trong Device Library, tai su dung toan bo pipeline quet/ghi metadata
	 * san co (BookCrawler) thay vi tu chen thang vao SQLite. Chay tren 1 thread rieng vi day la
	 * I/O (copy file, co the vai chuc MB) -- lam tren main thread se lam UI dung khung. */
	private void importBookFiles(java.util.List<android.net.Uri> uris) {
		Activity activity = getActivity();
		if (activity == null || uris.isEmpty()) return;
		Context appContext = activity.getApplicationContext();
		SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(appContext);
		File rootDir = StorageUtils.getRootDirectory(appContext, settings);
		if (!rootDir.exists() && !rootDir.mkdirs()) {
			Toast.makeText(activity, R.string.import_book_result_failed, Toast.LENGTH_LONG).show();
			return;
		}
		Set<String> acceptedExts = new java.util.HashSet<>(
				java.util.Arrays.asList(CalibreService.getAcceptedExtensionsArray(appContext)));

		new Thread(() -> {
			int copied = 0;
			int failed = 0;
			for (android.net.Uri uri : uris) {
				String displayName = queryDisplayName(appContext, uri);
				String ext = extensionOf(displayName);
				if (ext == null || !acceptedExts.contains(ext.toLowerCase(java.util.Locale.ROOT))) {
					failed++;
					continue;
				}
				File dest = uniqueDestFile(rootDir, displayName);
				try (InputStream in = appContext.getContentResolver().openInputStream(uri);
					 OutputStream out = new FileOutputStream(dest)) {
					if (in == null) {
						failed++;
						continue;
					}
					byte[] buf = new byte[8192];
					int n;
					while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
					copied++;
				} catch (IOException e) {
					Logger.e(e);
					failed++;
				}
			}
			int finalCopied = copied;
			int finalFailed = failed;
			if (activity == null) return;
			activity.runOnUiThread(() -> reportImportResult(finalCopied, finalFailed));
		}).start();
	}

	/** Chay tren UI thread sau khi importBookFiles() xong: bao ket qua bang Toast rieng (khac
	 * voi Toast cua triggerRescan()), roi rescan im lang (khong Toast trung lap) de sach vua
	 * copy vao dia duoc BookCrawler nhan dien va hien ra trong danh sach. */
	private void reportImportResult(int copied, int failed) {
		if (getActivity() == null) return;
		if (copied == 0) {
			Toast.makeText(getActivity(), R.string.import_book_result_failed, Toast.LENGTH_LONG).show();
			return;
		}
		String msg = failed == 0
				? getString(R.string.import_book_result_success, copied)
				: getString(R.string.import_book_result_partial, copied, copied + failed, failed);
		Toast.makeText(getActivity(), msg, Toast.LENGTH_LONG).show();
		triggerRescan(false); // false: Toast ket qua da hien o tren, khong can Toast thu 2
	}

	/** @return ten file hien thi (vi du "MyBook.epub") tu SAF content:// Uri, qua
	 * OpenableColumns.DISPLAY_NAME -- fallback ve segment cuoi cua Uri neu truy van that bai
	 * (mot so DocumentsProvider hiem gap khong tra ve cot nay). */
	private static String queryDisplayName(Context context, android.net.Uri uri) {
		try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
			if (cursor != null && cursor.moveToFirst()) {
				int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
				if (idx >= 0) {
					String name = cursor.getString(idx);
					if (name != null && !name.isEmpty()) return name;
				}
			}
		} catch (Exception e) {
			Logger.e(e);
		}
		String last = uri.getLastPathSegment();
		return last != null ? last : "imported_book";
	}

	/** @return duoi file (khong co dau chấm), hoac null neu ten khong co duoi. */
	private static String extensionOf(String fileName) {
		if (fileName == null) return null;
		int dot = fileName.lastIndexOf('.');
		if (dot < 0 || dot == fileName.length() - 1) return null;
		return fileName.substring(dot + 1);
	}

	/** Neu rootDir/fileName da ton tai, them hau to " (1)", " (2)"... truoc phan duoi, giong
	 * kieu dat ten tranh ghi de quen thuoc tren Android/Desktop, thay vi im lang ghi de sach
	 * cu cung ten. */
	private static File uniqueDestFile(File dir, String fileName) {
		File candidate = new File(dir, fileName);
		if (!candidate.exists()) return candidate;
		int dot = fileName.lastIndexOf('.');
		String base = dot >= 0 ? fileName.substring(0, dot) : fileName;
		String ext = dot >= 0 ? fileName.substring(dot) : "";
		int n = 1;
		do {
			candidate = new File(dir, base + " (" + n + ")" + ext);
			n++;
		} while (candidate.exists());
		return candidate;
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		super.onCreateOptionsMenu(menu, inflater);
		if (mSelectionMode) {
			inflater.inflate(R.menu.collection_selection_mode, menu);
			return;
		}
		inflater.inflate(R.menu.collection_fragment, menu);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            SearchManager searchManager = (SearchManager) this.getActivity().getSystemService(Context.SEARCH_SERVICE);
            SearchView searchView = (SearchView) menu.findItem(R.id.menu_search).getActionView();
            // getActionView() is null if this item got pushed into the overflow menu instead of
            // shown inline (e.g. not enough room in the toolbar) -- guard instead of crashing.
            if (searchView != null) {
                searchView.setSearchableInfo(searchManager.getSearchableInfo(this.getActivity().getComponentName()));
            }
        }
	}

	@Override
	public void onPrepareOptionsMenu(Menu menu) {
		super.onPrepareOptionsMenu(menu);
		MenuItem autoSyncItem = menu.findItem(R.id.menu_auto_sync_now);
		if (autoSyncItem == null) return; // null trong luc mSelectionMode (menu khac dang inflate)
		autoSyncItem.setTitle(buildAutoSyncMenuTitle());
	}

	/** "Dong bo · X phut truoc" (hoac "chua tung dong bo") cho muc menu_auto_sync_now -- lay moc
	 * thoi gian gan nhat trong so TAT CA remote ho tro dong bo thu vien thiet bi (khong chi rieng
	 * cac remote da CHON o Settings > Ket noi > Dong bo tu dong).
	 * <p>
	 * BUG DA SUA: truoc day ham nay chi duyet AppLibraryAutoSyncPrefs.resolveSelectedRemotes(),
	 * tuc chi cac remote dang duoc CHON cho tinh nang auto-sync dinh ky. Nguoi dung bam "Dong bo"
	 * thu cong (menu nay hoac nut trong dialog "Thong tin thu vien cloud") van chay
	 * DeviceLibrarySyncWorker.DIRECTION_FULL_SYNC va ghi CloudRemoteSyncPrefs.setLastSyncNow()
	 * binh thuong cho remoteId do -- nhung neu remote do KHONG nam trong tap da chon auto-sync
	 * (vd auto-sync dang tat, hoac nguoi dung bo chon remote nay trong Settings), vong lap cu bo
	 * qua no hoan toan nen mostRecentSyncMillis luon = 0, hien "chua tung dong bo" mai mai du
	 * "Tac vu chay nen" da bao Hoan tat. Duyet toan bo remote ho tro sync (khong loc theo lua
	 * chon auto-sync) de menu nay phan anh dung TRANG THAI DONG BO THUC TE cua thu vien. */
	private CharSequence buildAutoSyncMenuTitle() {
		Activity activity = getActivity();
		if (activity == null) return getString(R.string.menu_device_library_auto_sync_never);
		long mostRecentSyncMillis = 0L;
		for (CloudRemote r : CloudRemoteStore.getAll(activity)) {
			if (!r.getType().supportsDeviceLibrarySync()) continue;
			long millis = CloudRemoteSyncPrefs.getLastSyncMillis(activity, r.getId());
			if (millis > mostRecentSyncMillis) mostRecentSyncMillis = millis;
		}
		if (mostRecentSyncMillis <= 0L) {
			return getString(R.string.menu_device_library_auto_sync_never);
		}
		CharSequence relative = android.text.format.DateUtils.getRelativeTimeSpanString(
				mostRecentSyncMillis, System.currentTimeMillis(),
				android.text.format.DateUtils.MINUTE_IN_MILLIS);
		return getString(R.string.menu_device_library_auto_sync_last_sync, relative);
	}

	/** "Dong bo" trong overflow menu Device Library -- dong bo CHU DONG toan bo thu vien ngay lap
	 * tuc (khong doi den tick dinh ky ke tiep), dung remote da chon o Settings > Ket noi > Dong bo
	 * tu dong. Enqueue THANG DeviceLibrarySyncScheduler#enqueueFullSync cho tung remote (khong di
	 * qua AppLibraryAutoSyncWorker) de khong lam xao tron lich trinh dinh ky dang cho (neu goi qua
	 * scheduleNextTick se huy mat tick dang cho va doi lich). */
	private void triggerActiveFullSyncFromMenu() {
		Activity activity = getActivity();
		if (activity == null) return;
		List<CloudRemote> remotes = AppLibraryAutoSyncPrefs.resolveSelectedRemotes(activity);
		if (remotes.isEmpty()) {
			Toast.makeText(activity, R.string.toast_auto_sync_no_remote_selected, Toast.LENGTH_LONG).show();
			return;
		}
		for (CloudRemote remote : remotes) {
			DeviceLibrarySyncScheduler.enqueueFullSync(activity, remote.getId());
		}
		Toast.makeText(activity, R.string.sync_started, Toast.LENGTH_SHORT).show();
		// observeSyncWork() da duoc goi san o onViewCreated()/onResume() (xem code hien co) nen se
		// tu rescan danh sach sach khi job SUCCEEDED, khong can lam gi them o day.
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.menu_collection_tags:
			exitSelectionMode();
			showFilterByDialog();
			break;
		case R.id.menu_collection_sort_by:
			exitSelectionMode();
			showSortByDialog();
			break;
		case R.id.menu_collection_books:
			exitSelectionMode();
            isShowingTags = false;
            isShowingAuthors = false;
            isShowingOtherFilterList = false;
            mReshowOtherFilterList = null;
            triggerRescan(true); // true = show a result Toast, this is an explicit manual action
            break;
            case R.id.menu_search:
                // See OPDSBrowserFragment#onOptionsItemSelected for why this must not be gated
                // to pre-Honeycomb only: once demoted to the overflow menu, tapping Search
                // delivers a plain click with no action view to expand, so it did nothing.
                if (this.getActivity() != null) {
                    this.getActivity().onSearchRequested();
                }
                break;
            case R.id.menu_view_mode:
                showViewModeDialog();
                break;
            case R.id.menu_selection_select_all:
                selectAll();
                break;
            case R.id.menu_selection_share:
                shareSelected();
                break;
            case R.id.menu_selection_delete:
                confirmDeleteSelected();
                break;
            case R.id.menu_selection_sync:
                syncSelectedToCloud();
                break;
            case R.id.menu_selection_exit:
                exitSelectionMode();
                break;
            case R.id.menu_random_book:
                openRandomBook();
                break;
            case R.id.menu_auto_sync_now:
                triggerActiveFullSyncFromMenu();
                break;
            case R.id.menu_background_jobs:
                BackgroundJobsBottomSheet.newInstance().show(getChildFragmentManager(), "background_jobs");
                break;
            case R.id.menu_import_book:
                exitSelectionMode();
                openImportBookPicker();
                break;
        }
        return super.onOptionsItemSelected(item);
	}

	/** Opens the floating "Display" picker (ViewModePickerDialog) for the Device Library tab's
	 * six view modes, replacing the old anchored submenu. Mirrors
	 * OPDSBrowserFragment#showViewModeDialog for the Calibre Library tab. */
	private void showViewModeDialog() {
		if (getActivity() == null) return;
		List<ViewModePickerDialog.Option> options = new ArrayList<>();
		options.add(new ViewModePickerDialog.Option(R.drawable.ic_view_list, R.string.view_mode_list, ViewMode.LIST.prefValue));
		options.add(new ViewModePickerDialog.Option(R.drawable.ic_view_grid_2, R.string.view_mode_grid_2, ViewMode.GRID_2.prefValue));
		options.add(new ViewModePickerDialog.Option(R.drawable.ic_view_grid_3, R.string.view_mode_grid_3, ViewMode.GRID_3.prefValue));
		options.add(new ViewModePickerDialog.Option(R.drawable.ic_view_grid_4, R.string.view_mode_grid_4, ViewMode.GRID_4.prefValue));
		options.add(new ViewModePickerDialog.Option(R.drawable.ic_view_simple_list, R.string.view_mode_simple_list, ViewMode.SIMPLE_LIST.prefValue));
		options.add(new ViewModePickerDialog.Option(R.drawable.ic_view_carousel, R.string.view_mode_carousel, ViewMode.CAROUSEL.prefValue));
		ViewModePickerDialog.show(getActivity(), options, mViewMode.prefValue,
				id -> setViewMode(ViewMode.fromPrefValue(id)));
	}

	/** Applies (and persists) the chosen display mode, mirroring
	 * OPDSBrowserFragment#setViewColumns for the Calibre Library tab. The tag list (see
	 * showTags()) is unaffected -- it always forces a single-column list regardless of this. */
	private void setViewMode(ViewMode mode) {
		if (mViewMode == mode) return;
		mViewMode = mode;
		mSettings.edit().putInt(PREF_COLLECTION_VIEW_COLUMNS, mode.prefValue).apply();
		if (isShowingTags) {
			showTags();
		} else if (isShowingAuthors) {
			showAuthors();
		} else if (isShowingOtherFilterList && mReshowOtherFilterList != null) {
			mReshowOtherFilterList.run();
		} else {
			showBooks();
		}
	}

	/** Builds the right LayoutManager for the current view mode: a horizontal LinearLayoutManager
	 * for Carousel, GridLayoutManager for the Grid modes, and a plain vertical
	 * LinearLayoutManager for List/Simple list. */
	private RecyclerView.LayoutManager buildLayoutManagerFor(ViewMode mode) {
		if (mode == ViewMode.CAROUSEL) {
			return new LinearLayoutManager(getActivity(), LinearLayoutManager.HORIZONTAL, false);
		}
		if (mode.columns > 1) {
			return new GridLayoutManager(getActivity(), mode.columns);
		}
		return new LinearLayoutManager(getActivity());
	}

	/** Attaches/detaches ItemTouchHelper and PagerSnapHelper for the current view mode. Swipe
	 * actions only make sense for the two list-shaped modes (a partial horizontal reveal on a
	 * grid cell or carousel card isn't good UX); PagerSnapHelper only applies to the horizontal
	 * carousel. Both take a null RecyclerView to detach, which is the documented way to swap
	 * them between different modes on the same RecyclerView. */
	private void setupTouchHelper() {
		mTouchHelper.attachToRecyclerView(mAdapter.isSwipeEnabled() ? lvBooklist : null);
		mSnapHelper.attachToRecyclerView(mViewMode == ViewMode.CAROUSEL ? lvBooklist : null);
		applyCarouselChrome();
	}

	/** Enables/disables all Carousel-only UI: the 3D Cover Flow transform on lvBooklist's
	 * children (CarouselCoverFlowEffect), the horizontal padding that centers the first/last
	 * card the same way every other card is centered, and the title/author/dots/arrows chrome
	 * below the RecyclerView. Called from setupTouchHelper() (itself called by showBooks()) any
	 * time the view mode changes, and also from hideCarouselChrome() below for the Filter-by-
	 * tags/author screens, which bypass showBooks() entirely. */
	private void applyCarouselChrome() {
		boolean carousel = mViewMode == ViewMode.CAROUSEL;
		int visibility = carousel ? View.VISIBLE : View.GONE;
		carouselDetails.setVisibility(visibility);
		carouselPrev.setVisibility(visibility);
		carouselNext.setVisibility(visibility);

		lvBooklist.removeOnScrollListener(mCarouselEffect);
		if (!carousel) {
			// 5dp matches activity_collection.xml's col_list android:paddingTop -- restored
			// explicitly here since Carousel mode overwrites paddingTop for vertical centering
			// (see layoutCarouselChrome()) and can't just be left as-is when switching away.
			lvBooklist.setPadding(0, dpToPx(5), 0, 0);
			return;
		}
		lvBooklist.addOnScrollListener(mCarouselEffect);
		// Posted because the RecyclerView's width/height aren't known yet during this same
		// layout pass (and carouselDetails needs a layout pass too, now that it's visible).
		lvBooklist.post(this::layoutCarouselChrome);
	}

	/** Centers the whole Carousel "cover + title + author + dots" block inside lvBooklist, both
	 * horizontally (so the snapped/centered card sits at screen-center instead of flush against
	 * an edge) and vertically (so the cover isn't stuck at the top with the details panel
	 * floating separately near the bottom -- see the reference "TUYỂN TẬP SÁCH" screenshot this
	 * mirrors).
	 * <p>
	 * lvBooklist fills the whole tab (activity_collection.xml), and its horizontal
	 * LinearLayoutManager top-aligns wrap_content-height children by default -- there's no
	 * built-in way to vertically center a row shorter than the RecyclerView itself. So instead:
	 * add top padding equal to half the leftover vertical space (leftover = lvBooklist's height
	 * minus the cover row's height minus the details panel's height minus a small gap), which
	 * pushes the cover row down to the middle; then give col_carousel_details a top margin of
	 * exactly that same padding plus the cover row height plus the gap, which lands it right
	 * under the now-centered cover. Called once right after a Carousel mode switch (via
	 * applyCarouselChrome()) and again any time the title/author text changes (via
	 * updateCarouselDetails()), since a 1-line vs 2-line title changes the details panel's
	 * measured height and would otherwise throw the centering off for that book. */
	private void layoutCarouselChrome() {
		if (!isAdded() || mViewMode != ViewMode.CAROUSEL) return;
		int width = lvBooklist.getWidth();
		int height = lvBooklist.getHeight();
		if (width == 0 || height == 0) return;

		// CAROUSEL_ITEM_WIDTH_DP MUST match item_book_carousel.xml's card width -- see that
		// constant's javadoc (this is what keeps the centered card actually centered instead of
		// drifting off to one side).
		int sidePadding = Math.max(0, (width - dpToPx(CAROUSEL_ITEM_WIDTH_DP)) / 2);

		int itemHeightPx = dpToPx(CAROUSEL_ITEM_HEIGHT_DP);
		int gapPx = dpToPx(CAROUSEL_DETAILS_GAP_DP);
		int detailsHeightPx = carouselDetails.getHeight();
		int blockHeight = itemHeightPx + gapPx + detailsHeightPx;
		int topInset = Math.max(0, (height - blockHeight) / 2);

		lvBooklist.setPadding(sidePadding, topInset, sidePadding, 0);

		RelativeLayout.LayoutParams detailsParams = (RelativeLayout.LayoutParams) carouselDetails.getLayoutParams();
		detailsParams.topMargin = topInset + itemHeightPx + gapPx;
		carouselDetails.setLayoutParams(detailsParams);

		mCarouselEffect.applyTransform();
	}

	/** Hides the Carousel-only chrome without touching mViewMode -- used by showTags()/
	 * showAuthors(), which swap lvBooklist over to a plain single-column category list that
	 * showBooks()/applyCarouselChrome() never runs against, so the carousel panel/arrows would
	 * otherwise keep floating over that screen if the user had been in Carousel mode. */
	private void hideCarouselChrome() {
		carouselDetails.setVisibility(View.GONE);
		carouselPrev.setVisibility(View.GONE);
		carouselNext.setVisibility(View.GONE);
		lvBooklist.removeOnScrollListener(mCarouselEffect);
		// 5dp matches activity_collection.xml's col_list android:paddingTop -- see the comment
		// in applyCarouselChrome()'s non-carousel branch.
		lvBooklist.setPadding(0, dpToPx(5), 0, 0);
	}

	/** CarouselCoverFlowEffect callback: refreshes the title/author panel and dot indicator to
	 * match whichever book is now centered. */
	private void updateCarouselDetails(int position) {
		if (booklistCursor == null || !booklistCursor.moveToPosition(position)) return;
		carouselTitle.setText(booklistCursor.getString(Book.TITLE));
		carouselAuthor.setText(booklistCursor.getString(Book.AUTHOR));
		carouselDots.setCount(booklistCursor.getCount());
		carouselDots.setSelectedPosition(position);
		// The title can wrap to 1 or 2 lines depending on the book, which changes
		// carouselDetails' measured height -- re-center so the panel stays snug under the
		// cover instead of drifting for books with a shorter/longer title.
		lvBooklist.post(this::layoutCarouselChrome);
	}

	/** Backs the prev/next arrow buttons: steps one card at a time from whichever card
	 * PagerSnapHelper currently considers "snapped", the same reference point smoothScroll uses
	 * elsewhere in this fragment. */
	private void scrollCarouselBy(int delta) {
		RecyclerView.LayoutManager lm = lvBooklist.getLayoutManager();
		if (lm == null) return;
		View snapView = mSnapHelper.findSnapView(lm);
		if (snapView == null) return;
		int current = lm.getPosition(snapView);
		int target = current + delta;
		if (target < 0 || target >= lm.getItemCount()) return;
		lvBooklist.smoothScrollToPosition(target);
	}

	private int dpToPx(int dp) {
		return Math.round(dp * getResources().getDisplayMetrics().density);
	}

	private void showBooks() {
		// Only build/attach a new LayoutManager when the book grid actually needs a different
		// one: the view mode changed since we last built one, or lvBooklist currently holds some
		// OTHER LayoutManager (e.g. the plain single-column one showTags()/showAuthors()/the
		// other filter-list screens use, if the user came from one of those). Otherwise -- the
		// common case of showBooks() simply re-running because the tab came back to the
		// foreground (e.g. Back from previewing a book) -- keep the exact same LayoutManager
		// instance so its remembered scroll offset survives. Swapping in a brand-new instance
		// every single call (the old behavior) always threw that scroll position away, which is
		// what made returning from a book preview jump back to the top of the list.
		if (mBooksLayoutManager == null || mBooksLayoutViewMode != mViewMode
				|| lvBooklist.getLayoutManager() != mBooksLayoutManager) {
			mBooksLayoutManager = buildLayoutManagerFor(mViewMode);
			mBooksLayoutViewMode = mViewMode;
			lvBooklist.setLayoutManager(mBooksLayoutManager);
		}
		if (mAdapter == null || mAdapter.getCursor() != booklistCursor) {
			mAdapter = new BookRecyclerAdapter(getActivity(), booklistCursor, this);
			lvBooklist.setAdapter(mAdapter);
			mAdapter.setShowGridTitleProgress(mShowGridTitleProgress);
			mAdapter.setGridStyle(mGridStyle);
			applyGridBackground();
			reloadBookExtras();
		}
		mAdapter.setViewMode(toAdapterMode(mViewMode));
		mAdapter.setSelectionState(mSelectionMode, mSelectedIds);
		// Recreate the ItemTouchHelper against the (possibly new) adapter instance. Detach the
		// previous one first -- ItemTouchHelper can't have its Callback swapped after
		// construction, and leaving an old instance attached would leave a second touch
		// interceptor on the RecyclerView alongside the new one.
		if (mTouchHelper != null) mTouchHelper.attachToRecyclerView(null);
		mTouchHelper = new ItemTouchHelper(new SwipeRevealCallback(mAdapter));
		setupTouchHelper();
		refreshBookList();
	}

	/** Batch-loads reading progress %/comments for every book currently on device and pushes it
	 * into mAdapter (see MetadataDatabaseHelper#getAllBookExtras / BookRecyclerAdapter#setExtras).
	 * Called right after mAdapter picks up a new cursor in showBooks() -- covers initial load,
	 * rescans, and tag filtering alike, since all three funnel through showBooks(). Not called
	 * on plain view-mode switches (setViewMode doesn't change the underlying data). */
	private void reloadBookExtras() {
		if (mAdapter != null) mAdapter.setExtras(mDB.getAllBookExtras());
	}

	/** "Filter by..." toolbar item (menu_collection_tags): picks which category to browse by,
	 * mirroring OPDSBrowserFragment#showFilterByDialog for the Calibre Library tab. Series/
	 * Publisher/Rating/Custom Column (added in DATABASE_VERSION 13) only ever show up here once
	 * at least one book on device actually has that data -- there's no point offering an empty
	 * "Filter by Series" screen for a library where nothing has been tagged with a series yet. */
	private void showFilterByDialog() {
		if (getActivity() == null) return;
		List<CharSequence> labels = new ArrayList<>();
		labels.add(getString(R.string.collection_filter_by_tags));
		labels.add(getString(R.string.collection_filter_by_author));
		labels.add(getString(R.string.collection_filter_by_reading_status));
		labels.add(getString(R.string.collection_filter_by_series));
		labels.add(getString(R.string.collection_filter_by_publisher));
		labels.add(getString(R.string.collection_filter_by_rating));
		final int FIXED_COUNT = 6;
		// Custom Column filters: dynamic, one entry per column that (a) isn't free-text
		// comments/composite (not useful as a discrete pick-a-value list) and (b) actually has
		// at least one book with a value for it on this device.
		final List<CustomColumnSync.ColumnDef> customColOptions = new ArrayList<>();
		for (CustomColumnSync.ColumnDef def : mDB.getAllCustomColumnDefs()) {
			if ("comments".equals(def.datatype) || "composite".equals(def.datatype)) continue;
			if (mDB.getDistinctCustomColumnValues(def.lookupName).isEmpty()) continue;
			customColOptions.add(def);
			labels.add(def.label);
		}
		new AlertDialog.Builder(getActivity())
				.setTitle(R.string.menu_collection_tags)
				.setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
					isShowingOtherFilterList = false;
					mReshowOtherFilterList = null;
					switch (which) {
						case 0:
							isShowingTags = true;
							isShowingAuthors = false;
							showTags();
							break;
						case 1:
							isShowingTags = false;
							isShowingAuthors = true;
							showAuthors();
							break;
						case 2:
							isShowingTags = false;
							isShowingAuthors = false;
							showReadingStatusDialog();
							break;
						case 3:
							isShowingTags = false;
							isShowingAuthors = false;
							showSeriesFilter();
							break;
						case 4:
							isShowingTags = false;
							isShowingAuthors = false;
							showPublisherFilter();
							break;
						case 5:
							isShowingTags = false;
							isShowingAuthors = false;
							showRatingFilter();
							break;
						default:
							int idx = which - FIXED_COUNT;
							if (idx >= 0 && idx < customColOptions.size()) {
								isShowingTags = false;
								isShowingAuthors = false;
								showCustomColumnFilter(customColOptions.get(idx));
							}
							break;
					}
				})
				.show();
	}

	/** "Sort by..." toolbar item (menu_collection_sort_by), placed right below "Filter by..."
	 * on both toolbars -- mirrors OPDSBrowserFragment#showSortByDialog for the Calibre Library
	 * tab. Series/Rating/Publisher/Published/File size (DATABASE_VERSION 13) join Title/Author/
	 * Recent/Last Opened here; Custom Column sort options are appended dynamically, one per
	 * column Calibre Desktop has actually sent for at least one book on device -- see
	 * MetadataDatabaseHelper#getBooksSortedByCustomColumn(). */
	private void showSortByDialog() {
		if (getActivity() == null) return;
		List<SortByDialog.Option> options = new ArrayList<>();
		options.add(new SortByDialog.Option(R.drawable.ic_menu_sort, R.string.sort_field_title, MetadataDatabaseHelper.SORT_FIELD_TITLE));
		options.add(new SortByDialog.Option(R.drawable.ic_stat_person, R.string.sort_field_author, MetadataDatabaseHelper.SORT_FIELD_AUTHOR));
		options.add(new SortByDialog.Option(R.drawable.ic_sort_calendar, R.string.sort_field_recent, MetadataDatabaseHelper.SORT_FIELD_RECENT));
		options.add(new SortByDialog.Option(R.drawable.ic_action_open_book, R.string.sort_field_last_opened, MetadataDatabaseHelper.SORT_FIELD_LAST_OPENED));
		options.add(new SortByDialog.Option(R.drawable.ic_stat_series, R.string.sort_field_series, MetadataDatabaseHelper.SORT_FIELD_SERIES));
		options.add(new SortByDialog.Option(R.drawable.ic_action_star, R.string.sort_field_rating, MetadataDatabaseHelper.SORT_FIELD_RATING));
		options.add(new SortByDialog.Option(R.drawable.ic_stat_publisher, R.string.sort_field_publisher, MetadataDatabaseHelper.SORT_FIELD_PUBLISHER));
		options.add(new SortByDialog.Option(R.drawable.ic_sort_calendar, R.string.sort_field_published, MetadataDatabaseHelper.SORT_FIELD_PUBDATE));
		options.add(new SortByDialog.Option(R.drawable.ic_settings_tab_storage, R.string.sort_field_file_size, MetadataDatabaseHelper.SORT_FIELD_FILE_SIZE));
		// Custom Column sort options: ids beyond the fixed SORT_FIELD_* range so setSortOption()
		// below can tell them apart; the lookup_name itself is looked up again from mSortField
		// via mCustomColumnSortFields (built fresh each time this dialog opens, since Custom
		// Columns can change between syncs).
		mCustomColumnSortFields.clear();
		int nextId = CUSTOM_COLUMN_SORT_FIELD_BASE;
		for (CustomColumnSync.ColumnDef def : mDB.getAllCustomColumnDefs()) {
			if ("comments".equals(def.datatype) || "composite".equals(def.datatype)) continue;
			mCustomColumnSortFields.put(nextId, def.lookupName);
			options.add(new SortByDialog.Option(R.drawable.ic_menu_get_metadata_from_ebook, def.label, nextId));
			nextId++;
		}
		int selectedDialogId = mSortField;
		if (mSortField == CUSTOM_COLUMN_SORT_FIELD_ID && mSortCustomColumnLookup != null) {
			for (java.util.Map.Entry<Integer, String> e : mCustomColumnSortFields.entrySet()) {
				if (e.getValue().equals(mSortCustomColumnLookup)) {
					selectedDialogId = e.getKey();
					break;
				}
			}
		}
		SortByDialog.show(getActivity(), options, selectedDialogId, mSortAscending, this::setSortOption);
	}

	/** Applies (and persists) the chosen sort field/direction, mirroring setViewMode() above.
	 * Only reloads the plain book list -- the Filter-by-tags/author/series/publisher/Custom
	 * Column screens always sort alphabetically by their own grouping key regardless, so
	 * there's nothing to re-sort while one of those is showing; the new choice simply takes
	 * effect next time the plain book list is shown.
	 * @param field either a fixed MetadataDatabaseHelper.SORT_FIELD_* constant, or one of the
	 *              dialog-local Custom Column ids showSortByDialog() just handed out (resolved
	 *              back to a stable lookup_name via mCustomColumnSortFields before being
	 *              persisted -- see CUSTOM_COLUMN_SORT_FIELD_ID's docs above). */
	private void setSortOption(int field, boolean ascending) {
		String customLookup = mCustomColumnSortFields.get(field);
		int resolvedField = customLookup != null ? CUSTOM_COLUMN_SORT_FIELD_ID : field;
		if (mSortField == resolvedField && mSortAscending == ascending
				&& java.util.Objects.equals(mSortCustomColumnLookup, customLookup)) return;
		mSortField = resolvedField;
		mSortAscending = ascending;
		mSortCustomColumnLookup = customLookup;
		SharedPreferences.Editor editor = mSettings.edit()
				.putInt(PREF_COLLECTION_SORT_FIELD, resolvedField)
				.putBoolean(PREF_COLLECTION_SORT_ASCENDING, ascending);
		if (customLookup != null) {
			editor.putString(PREF_COLLECTION_SORT_CUSTOM_COLUMN, customLookup);
		} else {
			editor.remove(PREF_COLLECTION_SORT_CUSTOM_COLUMN);
		}
		editor.apply();
		if (!isShowingTags && !isShowingAuthors && !isShowingOtherFilterList) {
			booklistCursor = loadSortedBooklistCursor();
			showBooks();
		}
	}

	/** Single source of truth for "run mSortField/mSortAscending (or mSortCustomColumnLookup)
	 * against the DB", used by every plain-book-grid load site instead of each picking between
	 * getBooksSorted()/getBooksSortedByCustomColumn() itself. */
	private Cursor loadSortedBooklistCursor() {
		if (mSortField == CUSTOM_COLUMN_SORT_FIELD_ID && mSortCustomColumnLookup != null) {
			return mDB.getBooksSortedByCustomColumn(Book.COLUMNS, mSortCustomColumnLookup, mSortAscending);
		}
		return mDB.getBooksSorted(Book.COLUMNS, mSortField, mSortAscending);
	}

	private void showTags() {
		// Folder/category-style navigation (plain tag names): always a single-column list,
		// regardless of the book grid's display mode -- a multi-column grid of plain text rows
		// doesn't make sense here. Mirrors OPDSBrowserFragment#loadCurrentPage's handling of
		// catalog links.
		if (mTouchHelper != null) mTouchHelper.attachToRecyclerView(null);
		mSnapHelper.attachToRecyclerView(null);
		hideCarouselChrome();
		Cursor cs = mDB.getTagsWithBooks();
		// BUG FIX: when no book has been tagged yet, cs.getCount() is 0 and the RecyclerView
		// legitimately renders nothing -- but nothing here ever showed/hid col_empty_state for
		// this branch (that view starts out gone and only showBooks()'s hideEmptyState() call
		// ever touched it again), so the fragment was left showing a totally bare RecyclerView
		// on top of the plain window background. On the app's dark theme that reads as a solid
		// black screen with no message and no way to tell that "Filter by tags" even worked.
		// Reuse col_empty_state (message only, no action button -- there's no single relevant
		// action to offer here) instead of leaving the screen blank.
		if (cs.getCount() == 0) {
			showEmptyStateMessageOnly(R.string.collection_empty_no_tags);
			mTagAdapter = null;
			return;
		}
		hideEmptyState();
		lvBooklist.setLayoutManager(new LinearLayoutManager(getActivity()));
		mTagAdapter = new TagAdapter(cs, tagId -> {
			isShowingTags = false;
			booklistCursor = mDB.getBooksWithTag(Book.COLUMNS, tagId);
			showBooks();
		});
		lvBooklist.setAdapter(mTagAdapter);
	}

	/** "Filter by... > Author" screen: lists every distinct author with at least one book on
	 * device, tapping one filters the grid down to just their books. Mirrors showTags() above
	 * almost exactly -- same single-column layout, same empty-state handling -- just backed by
	 * AuthorAdapter/getAuthorsWithBooks() instead of TagAdapter/getTagsWithBooks(). */
	private void showAuthors() {
		if (mTouchHelper != null) mTouchHelper.attachToRecyclerView(null);
		mSnapHelper.attachToRecyclerView(null);
		hideCarouselChrome();
		Cursor cs = mDB.getAuthorsWithBooks();
		if (cs.getCount() == 0) {
			showEmptyStateMessageOnly(R.string.collection_empty_no_authors);
			mAuthorAdapter = null;
			return;
		}
		hideEmptyState();
		lvBooklist.setLayoutManager(new LinearLayoutManager(getActivity()));
		mAuthorAdapter = new AuthorAdapter(cs, author -> {
			isShowingAuthors = false;
			booklistCursor = mDB.getBooksByAuthor(Book.COLUMNS, author);
			showBooks();
		});
		lvBooklist.setAdapter(mAuthorAdapter);
	}

	/** "Filter by... > Series" screen -- mirrors showAuthors() above exactly (same single-
	 * column layout, same reused AuthorAdapter), just backed by getSeriesWithBooks()/
	 * getBooksBySeries() instead. */
	private void showSeriesFilter() {
		if (mTouchHelper != null) mTouchHelper.attachToRecyclerView(null);
		mSnapHelper.attachToRecyclerView(null);
		hideCarouselChrome();
		Cursor cs = mDB.getSeriesWithBooks();
		if (cs.getCount() == 0) {
			showEmptyStateMessageOnly(R.string.collection_empty_no_series);
			mValueListAdapter = null;
			return;
		}
		hideEmptyState();
		isShowingOtherFilterList = true;
		mReshowOtherFilterList = this::showSeriesFilter;
		lvBooklist.setLayoutManager(new LinearLayoutManager(getActivity()));
		mValueListAdapter = new AuthorAdapter(cs, series -> {
			isShowingOtherFilterList = false;
			mReshowOtherFilterList = null;
			booklistCursor = mDB.getBooksBySeries(Book.COLUMNS, series);
			showBooks();
		});
		lvBooklist.setAdapter(mValueListAdapter);
	}

	/** "Filter by... > Publisher" screen -- mirrors showSeriesFilter() above exactly. */
	private void showPublisherFilter() {
		if (mTouchHelper != null) mTouchHelper.attachToRecyclerView(null);
		mSnapHelper.attachToRecyclerView(null);
		hideCarouselChrome();
		Cursor cs = mDB.getPublishersWithBooks();
		if (cs.getCount() == 0) {
			showEmptyStateMessageOnly(R.string.collection_empty_no_publishers);
			mValueListAdapter = null;
			return;
		}
		hideEmptyState();
		isShowingOtherFilterList = true;
		mReshowOtherFilterList = this::showPublisherFilter;
		lvBooklist.setLayoutManager(new LinearLayoutManager(getActivity()));
		mValueListAdapter = new AuthorAdapter(cs, publisher -> {
			isShowingOtherFilterList = false;
			mReshowOtherFilterList = null;
			booklistCursor = mDB.getBooksByPublisher(Book.COLUMNS, publisher);
			showBooks();
		});
		lvBooklist.setAdapter(mValueListAdapter);
	}

	/** "Filter by... > Rating" screen: like showReadingStatusDialog() above, the set of options
	 * is fixed (5 down to 1 stars, "at least N stars"), so a plain AlertDialog list is enough. */
	private void showRatingFilter() {
		if (getActivity() == null) return;
		final CharSequence[] items = new CharSequence[]{
				getString(R.string.collection_filter_rating_n_plus, 5),
				getString(R.string.collection_filter_rating_n_plus, 4),
				getString(R.string.collection_filter_rating_n_plus, 3),
				getString(R.string.collection_filter_rating_n_plus, 2),
				getString(R.string.collection_filter_rating_n_plus, 1),
		};
		final int[] thresholds = new int[]{5, 4, 3, 2, 1};
		new AlertDialog.Builder(getActivity())
				.setTitle(R.string.collection_filter_by_rating)
				.setItems(items, (dialog, which) -> {
					booklistCursor = mDB.getBooksWithMinRating(Book.COLUMNS, thresholds[which]);
					showBooks();
				})
				.show();
	}

	/** "Filter by... > <Custom Column label>" screen -- one column's distinct values (e.g. every
	 * genre in use), mirrors showSeriesFilter()/showPublisherFilter() above. For an is_multiple
	 * column, getBooksByCustomColumnValue() below matches any book that has this value among
	 * possibly several (see MetadataDatabaseHelper#getBooksByCustomColumnValue). */
	private void showCustomColumnFilter(CustomColumnSync.ColumnDef def) {
		if (mTouchHelper != null) mTouchHelper.attachToRecyclerView(null);
		mSnapHelper.attachToRecyclerView(null);
		hideCarouselChrome();
		List<String> values = mDB.getDistinctCustomColumnValues(def.lookupName);
		if (values.isEmpty()) {
			showEmptyStateMessageOnly(R.string.collection_empty_no_matches);
			mValueListAdapter = null;
			return;
		}
		hideEmptyState();
		isShowingOtherFilterList = true;
		mReshowOtherFilterList = () -> showCustomColumnFilter(def);
		lvBooklist.setLayoutManager(new LinearLayoutManager(getActivity()));
		// AuthorAdapter needs a Cursor (column 0 = the string to show/return) -- build a tiny
		// in-memory MatrixCursor rather than adding a whole 2nd adapter constructor just for
		// this one caller.
		android.database.MatrixCursor cs = new android.database.MatrixCursor(new String[]{"value"});
		for (String value : values) cs.addRow(new Object[]{value});
		mValueListAdapter = new AuthorAdapter(cs, value -> {
			isShowingOtherFilterList = false;
			mReshowOtherFilterList = null;
			booklistCursor = mDB.getBooksByCustomColumnValue(Book.COLUMNS, def.lookupName, value);
			showBooks();
		});
		lvBooklist.setAdapter(mValueListAdapter);
	}

	/** "Filter by... > Reading status" screen: unlike Tags/Author above, the set of options
	 * here is fixed (three buckets, not data-driven), so a plain AlertDialog list is enough --
	 * no need for a dedicated full-screen RecyclerView step like showTags()/showAuthors(). */
	private void showReadingStatusDialog() {
		if (getActivity() == null) return;
		final CharSequence[] items = new CharSequence[]{
				getString(R.string.collection_reading_status_new),
				getString(R.string.collection_reading_status_reading),
				getString(R.string.collection_reading_status_finished),
		};
		final MetadataDatabaseHelper.ReadingStatus[] statuses = new MetadataDatabaseHelper.ReadingStatus[]{
				MetadataDatabaseHelper.ReadingStatus.NEW,
				MetadataDatabaseHelper.ReadingStatus.READING,
				MetadataDatabaseHelper.ReadingStatus.FINISHED,
		};
		new AlertDialog.Builder(getActivity())
				.setTitle(R.string.collection_filter_by_reading_status)
				.setItems(items, (dialog, which) -> {
					booklistCursor = mDB.getBooksByReadingStatus(Book.COLUMNS, statuses[which]);
					showBooks();
				})
				.show();
	}

	// ---- BookRecyclerAdapter.Listener --------------------------------------------------

	@Override
	public void onBookOpen(String uuid) {
		// Row tapped: show the preview screen (cover, full metadata.calibre info, action row)
		// instead of jumping straight into the reader -- mirrors OPDSBrowserFragment#openBookDetail
		// for the Calibre Library tab. DeviceBookDetailActivity's own "Read" button is what
		// actually launches OpenBookActivity now.
		// Kem theo ca danh sach dang duyet (dung thu tu sort/filter hien tai cua booklistCursor)
		// de DeviceBookDetailActivity co the vuot trai/phai sang cuon ke tiep/truoc do.
		startActivity(new Intent(getActivity(), DeviceBookDetailActivity.class)
				.putExtra(DeviceBookDetailActivity.EXTRA_UUID, uuid)
				.putExtra(DeviceBookDetailActivity.EXTRA_BOOK_LIST_UUIDS, orderedUuidsOf(booklistCursor))
				.putExtra(DeviceBookDetailActivity.EXTRA_BOOK_LIST_INDEX, indexOfUuid(booklistCursor, uuid)));
	}

	/** Toan bo uuid trong [cursor] theo DUNG thu tu hien tai cua no (da sort/filter san boi
	 * loadSortedBooklistCursor()/cac ham loc theo tag-author-series...), dung lam
	 * EXTRA_BOOK_LIST_UUIDS cho DeviceBookDetailActivity. Khong lam thay doi vi tri hien tai cua
	 * cursor (luu/khoi phuc savedPos), giong het pattern selectAll() o tren. */
	private static String[] orderedUuidsOf(Cursor cursor) {
		if (cursor == null) return null;
		int savedPos = cursor.getPosition();
		String[] uuids = new String[cursor.getCount()];
		if (cursor.moveToFirst()) {
			int i = 0;
			do {
				uuids[i++] = cursor.getString(Book.UUID);
			} while (cursor.moveToNext());
		}
		cursor.moveToPosition(savedPos);
		return uuids;
	}

	/** Vi tri (0-based) cua [uuid] ben trong [cursor], hoac -1 neu khong tim thay. */
	private static int indexOfUuid(Cursor cursor, String uuid) {
		if (cursor == null || uuid == null) return -1;
		int savedPos = cursor.getPosition();
		int result = -1;
		if (cursor.moveToFirst()) {
			int i = 0;
			do {
				if (uuid.equals(cursor.getString(Book.UUID))) {
					result = i;
					break;
				}
				i++;
			} while (cursor.moveToNext());
		}
		cursor.moveToPosition(savedPos);
		return result;
	}

	@Override
	public void onBookLongPress(int id) {
		if (!mSelectionMode) {
			mSelectionMode = true;
			mSelectedIds.clear();
			mSelectedIds.add(id);
			updateSelectionUI();
		} else {
			toggleSelection(id);
		}
	}

	@Override
	public void onBookToggleSelect(int id) {
		toggleSelection(id);
	}

	@Override
	public void onShareRequested(int id) {
		shareBooks(new int[] {id});
	}

	@Override
	public void onDeleteRequested(int id) {
		confirmDeleteSingle(id);
	}

	@Override
	public void onSyncRequested(int id) {
		syncBooksToCloud(new int[] {id});
	}

	@Override
	public void onListenRequested(int id) {
		listenToBook(id);
	}

	@Override
	public void onReadRequested(int id) {
		readBook(id);
	}

	// ---- Multi-select -------------------------------------------------------------------

	private void toggleSelection(int id) {
		if (mSelectedIds.contains(id)) {
			mSelectedIds.remove(id);
		} else {
			mSelectedIds.add(id);
		}
		if (mSelectedIds.isEmpty()) {
			exitSelectionMode();
		} else {
			updateSelectionUI();
		}
	}

	private void selectAll() {
		if (booklistCursor == null) return;
		mSelectedIds.clear();
		int savedPos = booklistCursor.getPosition();
		if (booklistCursor.moveToFirst()) {
			do {
				mSelectedIds.add(booklistCursor.getInt(Book.ID));
			} while (booklistCursor.moveToNext());
		}
		booklistCursor.moveToPosition(savedPos);
		updateSelectionUI();
	}

	private void exitSelectionMode() {
		mSelectionMode = false;
		mSelectedIds.clear();
		updateSelectionUI();
	}

	private void updateSelectionUI() {
		if (mAdapter != null) mAdapter.setSelectionState(mSelectionMode, mSelectedIds);
		if (mBackPressedCallback != null) mBackPressedCallback.setEnabled(mSelectionMode);
		if (mSelectionPill != null) {
			mSelectionPill.setVisibility(mSelectionMode ? View.VISIBLE : View.GONE);
			if (mSelectionMode) repositionSelectionPill();
		}
		if (mListener != null) mListener.onCollectionSelectionModeChanged(mSelectionMode);
		Activity activity = getActivity();
		if (activity == null) return;
		if (mSelectionMode) {
			activity.setTitle(getString(R.string.selection_count_format, mSelectedIds.size()));
		} else {
			activity.setTitle(R.string.app_name);
		}
		activity.invalidateOptionsMenu();
	}

	/** Called from MainActivity whenever net.mrphan.calibre.reader.tts.GlobalTtsFloatingPanel
	 * (the separate floating TTS/music mini player) flips shown <-> hidden, so the selection
	 * pill can move out of its way -- see MainActivity's mTtsFloatingPanel VisibilityListener
	 * lambda, which forwards here via BarTab#getFragment(). */
	public void onMusicPanelVisibilityChanged(boolean musicPanelVisible) {
		mMusicPanelVisible = musicPanelVisible;
		repositionSelectionPill();
	}

	/** Adjusts the selection pill's bottomMargin so it always floats above
	 * GlobalTtsFloatingPanel when that panel is showing, instead of being covered by (or
	 * covering) it. Safe to call at any time, including while the pill is GONE (harmless, just
	 * sets a margin nothing currently reads) and before MainActivity/the panel exist yet
	 * (falls back to the base margin). */
	private void repositionSelectionPill() {
		if (mSelectionPill == null) return;
		ViewGroup.LayoutParams lp = mSelectionPill.getLayoutParams();
		if (!(lp instanceof ViewGroup.MarginLayoutParams)) return;
		ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;

		int targetMargin = mSelectionPillBaseMarginPx;
		if (mMusicPanelVisible && getActivity() instanceof MainActivity) {
			net.mrphan.calibre.reader.tts.GlobalTtsFloatingPanel ttsPanel =
					((MainActivity) getActivity()).getTtsFloatingPanel();
			if (ttsPanel != null && ttsPanel.isPanelVisible()) {
				int panelHeightPx = ttsPanel.getPanelHeightPx();
				if (panelHeightPx <= 0) {
					// Panel just became visible but hasn't laid out yet (height still 0) --
					// retry once more on the next frame instead of leaving the pill under-offset
					// for one frame. mSelectionPill is guaranteed non-null here (checked above).
					mSelectionPill.post(this::repositionSelectionPill);
				} else {
					targetMargin = mSelectionPillBaseMarginPx + panelHeightPx + dpToPx(8);
				}
			}
		}
		if (mlp.bottomMargin != targetMargin) {
			mlp.bottomMargin = targetMargin;
			mSelectionPill.setLayoutParams(mlp);
		}
	}

	// ---- Delete ---------------------------------------------------------------------------

	public void deleteBook(int id) {
		deleteBookFileAndDb(id);
		refreshBookList();
	}

	/** Deletes the book's file on disk plus its DB row, WITHOUT refreshing the list -- used by
	 * both deleteBook() (single book, refreshes immediately after) and the bulk multi-select
	 * delete below (which refreshes once after the whole loop instead of once per book). */
	private void deleteBookFileAndDb(int id) {
		try {
			Cursor cs = mDB.getById(new String[] {"lpath"}, id);
			if (cs.moveToFirst()) {
                SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getActivity().getApplicationContext());
                File root_dir = StorageUtils.getRootDirectory(getActivity().getApplicationContext(), settings);
                String lpath = cs.getString(0);
                CoverManager.deleteCoverFor(root_dir, lpath); // NEW: drop the cached cover too
				File fd = new File(root_dir, lpath);
				fd.delete();
				mDB.deleteBook(id);
				if (mListener != null) mListener.onBookDeleted();
			}
		} catch (Throwable e) {
			e.printStackTrace();
		}
	}

	private void confirmDeleteSingle(final int id) {
		if (getActivity() == null) return;
		new AlertDialog.Builder(getActivity())
				.setTitle(R.string.dialog_button_delete)
				.setMessage(R.string.dialog_deletefile_message)
				.setCancelable(true)
				.setNegativeButton(R.string.dialog_button_cancel, null)
				.setPositiveButton(R.string.dialog_button_delete, (dialog, which) -> deleteBook(id))
				.show();
	}

	private void confirmDeleteSelected() {
		if (getActivity() == null || mSelectedIds.isEmpty()) return;
		final int count = mSelectedIds.size();
		new AlertDialog.Builder(getActivity())
				.setTitle(R.string.dialog_delete_selected_title)
				.setMessage(getString(R.string.dialog_delete_selected_message, count))
				.setCancelable(true)
				.setNegativeButton(R.string.dialog_button_cancel, null)
				.setPositiveButton(R.string.dialog_button_delete, (dialog, which) -> {
					for (int id : new ArrayList<>(mSelectedIds)) {
						deleteBookFileAndDb(id);
					}
					exitSelectionMode();
					refreshBookList();
				})
				.show();
	}

	// ---- Share (Intent.ACTION_SEND[_MULTIPLE] via FileProvider) --------------------------

	private void shareSelected() {
		if (mSelectedIds.isEmpty()) return;
		int[] ids = new int[mSelectedIds.size()];
		int i = 0;
		for (int id : mSelectedIds) ids[i++] = id;
		shareBooks(ids);
	}

	private void shareBooks(int[] ids) {
		Activity activity = getActivity();
		if (activity == null) return;
		SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(activity.getApplicationContext());
		File root_dir = StorageUtils.getRootDirectory(activity.getApplicationContext(), settings);

		// Build the file list from the DB first (ShareUtils itself doesn't know about
		// MetadataDatabaseHelper/lpath, it only deals in plain File objects) -- this is also
		// where the previous cursor leak lived (cs was never closed on any path through this
		// loop), so every path now goes through the try/finally below instead.
		List<File> files = new ArrayList<>();
		for (int id : ids) {
			Cursor cs = mDB.getById(new String[] {"lpath"}, id);
			try {
				if (!cs.moveToFirst()) continue;
				files.add(new File(root_dir, cs.getString(0)));
			} finally {
				cs.close();
			}
		}

		ShareUtils.Result result = ShareUtils.shareFiles(activity, files);
		if (result.isPartialFailure()) {
			Toast.makeText(activity,
					getString(R.string.dialog_share_partial_failure, result.requested - result.shared, result.requested),
					Toast.LENGTH_LONG).show();
		}
	}

	// ---- Listen (TTS) / Read, swipe-right actions -----------------------------------------

	/** Mirrors DeviceBookDetailActivity#onReadAloudClicked: only works for EPUB, since
	 * TtsChapterListActivity reads plain text out of the epub. Non-epub books show the same
	 * error Toast the detail screen uses instead of silently doing nothing. */
	private void listenToBook(int id) {
		Activity activity = getActivity();
		if (activity == null) return;
		Cursor cs = mDB.getById(new String[] {"lpath", "uuid", "title"}, id);
		try {
			if (!cs.moveToFirst()) return;
			String lpath = cs.getString(0);
			String uuid = cs.getString(1);
			String title = cs.getString(2);
			if (lpath == null || !lpath.toLowerCase(java.util.Locale.ROOT).endsWith(".epub")) {
				Toast.makeText(activity, R.string.reader_tts_init_failed, Toast.LENGTH_SHORT).show();
				return;
			}
			SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(activity.getApplicationContext());
			File rootDir = StorageUtils.getRootDirectory(activity.getApplicationContext(), settings);
			File bookFile = new File(rootDir, lpath);
			startActivity(TtsChapterListActivity.buildIntent(activity, bookFile, uuid, title));
		} finally {
			cs.close();
		}
	}

	/** "Doc ngau nhien" trong overflow menu Device Library: chon 1 vi tri bat ky trong
	 * booklistCursor (tuc la trong pham vi dang duyet hien tai -- ca thu vien neu khong loc gi,
	 * hoac chi trong 1 tag/tac gia/... neu nguoi dung dang loc, giong nhu cach danh sach carousel
	 * hoat dong) va mo doc thang bang readBook(), tai su dung nguyen logic OpenBookActivity da co
	 * thay vi tu viet lai. */
	private void openRandomBook() {
		Activity activity = getActivity();
		if (activity == null) return;
		if (booklistCursor == null || booklistCursor.getCount() == 0) {
			Toast.makeText(activity, R.string.toast_random_book_empty, Toast.LENGTH_SHORT).show();
			return;
		}
		int savedPos = booklistCursor.getPosition();
		int randomPos = new java.util.Random().nextInt(booklistCursor.getCount());
		if (!booklistCursor.moveToPosition(randomPos)) {
			booklistCursor.moveToPosition(savedPos);
			return;
		}
		int id = booklistCursor.getInt(Book.ID);
		booklistCursor.moveToPosition(savedPos);
		readBook(id);
	}

	/** Mirrors DeviceBookDetailActivity#onReadClicked: the book is already on disk for a
	 * Device Library entry, so OpenBookActivity can resolve straight from uuid, no download
	 * step needed. */
	private void readBook(int id) {
		Activity activity = getActivity();
		if (activity == null) return;
		Cursor cs = mDB.getById(new String[] {"uuid"}, id);
		try {
			if (!cs.moveToFirst()) return;
			String uuid = cs.getString(0);
			startActivity(new Intent(activity, OpenBookActivity.class)
					.putExtra(OpenBookActivity.EXTRA_UUID, uuid));
		} finally {
			cs.close();
		}
	}

	// ---- Sync to/from cloud (Google Drive, OneDrive, see DeviceLibrarySyncManager) -------

	private void syncSelectedToCloud() {
		if (mSelectedIds.isEmpty()) return;
		int[] ids = new int[mSelectedIds.size()];
		int i = 0;
		for (int id : mSelectedIds) ids[i++] = id;
		exitSelectionMode();
		syncBooksToCloud(ids);
	}

	/** Diem vao chung cho sync 1 sach (swipe) hoac nhieu sach (multi-select). Chi enqueue
	 * WorkManager job -- xem DeviceLibrarySyncWorker, khong goi mang truc tiep tu Fragment. */
	private void syncBooksToCloud(int[] bookIds) {
		Activity activity = getActivity();
		if (activity == null) return;
		List<CloudRemote> syncableRemotes = new ArrayList<>();
		for (CloudRemote r : CloudRemoteStore.getAll(activity)) {
			if (r.getType().supportsDeviceLibrarySync()) syncableRemotes.add(r);
		}
		if (syncableRemotes.isEmpty()) {
			Toast.makeText(activity, R.string.sync_no_cloud_connected, Toast.LENGTH_LONG).show();
			return;
		}
		// Uuid, khong phai _id, la khoa on dinh giua cac lan requery/rescan.
		String[] uuids = mDB.getUuidsForIds(bookIds);
		if (syncableRemotes.size() == 1) {
			DeviceLibrarySyncScheduler.enqueueUpload(activity, syncableRemotes.get(0).getId(), uuids);
			Toast.makeText(activity, R.string.sync_started, Toast.LENGTH_SHORT).show();
			return;
		}
		// Nhieu remote cloud -- cho chon.
		CloudRemotePickerDialogFragment.newInstanceForUpload(syncableRemotes, uuids)
				.show(getChildFragmentManager(), "pick_sync_remote");
	}

	/** Quan sat DeviceLibrarySyncWorker qua LiveData<WorkInfo> -- khi job SUCCEEDED (thuong la
	 * sau nut "Dong bo" trong overflow menu), tu dong rescan giong het bam "Show books" thu cong,
	 * de sach vua tai ve hien ra trong danh sach. An toan de goi nhieu lan vi LiveData tu dedupe
	 * theo lifecycle owner (getViewLifecycleOwner()). */
	private void observeSyncWork() {
		if (getActivity() == null) return;
		WorkManager.getInstance(getActivity())
				.getWorkInfosForUniqueWorkLiveData(DeviceLibrarySyncScheduler.UNIQUE_WORK_NAME)
				.observe(getViewLifecycleOwner(), workInfos -> {
					if (workInfos == null) return;
					for (WorkInfo info : workInfos) {
						if (info.getState() == WorkInfo.State.SUCCEEDED) {
							triggerRescan(false);
							// Bat buoc phai invalidate o day: CloudRemoteSyncPrefs.setLastSyncNow()
							// da duoc DeviceLibrarySyncWorker ghi xong luc nay, nhung buildAutoSyncMenuTitle()
							// chi duoc tinh lai ben trong onPrepareOptionsMenu() -- ham nay KHONG tu dong
							// chay lai khi WorkManager bao SUCCEEDED, phai chu dong yeu cau he thong ve lai
							// menu thi nhan "Dong bo · X phut truoc" moi cap nhat dung ngay lap tuc.
							Activity a = getActivity();
							if (a != null) a.invalidateOptionsMenu();
						}
					}
				});
	}

	// ---- Cursor refresh -------------------------------------------------------------------

	// BUG FIX (crash "Inconsistency detected... Invalid item position" khi xoa sach, van con xay
	// ra du da co co che gom cac lan goi refreshBookList() lien tiep o ban truoc): nguyen nhan
	// THUC SU khong phai do goi refreshBookList() lien tiep, ma do requery() chay tren MOT
	// THREAD NEN (mRefreshExecutor) trong khi RecyclerView tren MAIN THREAD van dang doc TU CHINH
	// mCursor do (getItemCount()/onBindViewHolder trong luc scroll hoac layout) -- Cursor (cu the
	// la SQLiteCursor) khong thread-safe. requery() mutate CUNG MOT object Cursor tai cho (nap lai
	// CursorWindow) NGAY GIUA LUC main thread co the dang doc no; RecyclerView chot lai
	// getItemCount() luc bat dau layout roi doc lai du lieu sau do co the thay count/noi dung da
	// doi ngay giua chung -- day chinh la nguon goc chi so "Invalid item position" khong khop.
	// Co dinh bang cach dua requery() ve lai MAIN THREAD (dong bo) -- dung y tuong "chay nen de
	// tranh giat/ANR" da co truoc day, nhung sai vi no khong phai chay tren 1 Cursor DOC LAP, ma
	// tren CHINH cursor dang song cua RecyclerView. Thu vien tren dien thoai thuong chi vai tram
	// den vai nghin cuon nen 1 lan requery() (1 cau SQL SELECT co san index) van rat re -- neu sau
	// nay thu vien qua lon gay giat that su, cach dung la truy van 1 Cursor MOI hoan toan tren nen
	// (khong dung chung object voi Cursor dang gan RecyclerView) roi swapCursor() vao tren main
	// thread (xem BookRecyclerAdapter#swapCursor -- khong bao gio dung chung 1 Cursor giua 2
	// thread), chu khong phai requery() song song nhu truoc.
	protected void refreshBookList() {
		if (isShowingTags || isShowingAuthors || isShowingOtherFilterList) return;
		if (mAdapter == null) return;

		Cursor cursor = mAdapter.getCursor();
		if (cursor == null) return;

		List<BookRecyclerAdapter.RowSnapshot> oldSnapshot = mAdapter.captureSnapshot();
		boolean requerySuccess;
		try {
			requerySuccess = cursor.requery();
		} catch (Exception e) {
			Logger.e(e);
			requerySuccess = false;
		}
		if (requerySuccess) {
			mAdapter.applyCursorDiff(oldSnapshot);
		}
		if (getActivity() instanceof MainActivity) {
			((MainActivity) getActivity()).refreshHeaderIfShowing(this);
		}
	}

	// ---- HeaderContentProvider (nang cap header gon + Drawer duy nhat) --------------------

	@Override
	public String getHeaderTitle() {
		return getString(R.string.tab_collection);
	}

	@Override
	public String getHeaderCountLabel() {
		if (mAdapter == null || getActivity() == null) return null;
		int count = mAdapter.getItemCount();
		return getResources().getQuantityString(R.plurals.opds_library_book_count, count, count);
	}

	@Override
	public boolean supportsHeaderSearch() {
		return true; // collection_fragment.xml da co menu_search (BookSearchActivity toan cuc).
	}

	@Override
	public void onHeaderSearchQuery(String query) {
		// BUG FIX: startSearch()/onSearchRequested() cu se hien Search Dialog CUA HE THONG (thanh
		// icon app + o nhap + nut X) chong len ngay tren o search cua header moi, tao ra 2 thanh
		// tim kiem trung nhau. Mo thang Intent ACTION_SEARCH toi BookSearchActivity (dung
		// SearchManager.QUERY extra ma no doc trong onCreate) de bo qua hoan toan overlay do --
		// header moi la o nhap DUY NHAT nguoi dung thay.
		if (getActivity() != null) {
			Intent intent = new Intent(Intent.ACTION_SEARCH, null, getActivity(), BookSearchActivity.class);
			intent.putExtra(android.app.SearchManager.QUERY, query);
			startActivity(intent);
		}
	}

	@Override
	public void onHeaderRefresh() {
		// Tuong duong menu_collection_books cu ("Show books"): thoat selection mode (neu co) roi
		// rescan thu muc sach, co Toast ket qua.
		exitSelectionMode();
		isShowingTags = false;
		isShowingAuthors = false;
		isShowingOtherFilterList = false;
		mReshowOtherFilterList = null;
		triggerRescan(true);
	}

	@Override
	public void onHeaderOverflowClick(View anchorView) {
		androidx.appcompat.widget.PopupMenu popup =
				new androidx.appcompat.widget.PopupMenu(requireContext(), anchorView);
		popup.inflate(mSelectionMode ? R.menu.collection_selection_mode : R.menu.collection_fragment);
		// Header da co nut search rieng (btn_header_search) -- an muc trung lap trong popup.
		android.view.MenuItem searchItem = popup.getMenu().findItem(R.id.menu_search);
		if (searchItem != null) searchItem.setVisible(false);
		// BUG DA SUA: popup nay INFLATE THANG tu collection_fragment.xml, noi title mac dinh cua
		// menu_auto_sync_now la @string/menu_device_library_auto_sync_never ("chua tung dong bo")
		// gan cung trong XML -- khac voi onPrepareOptionsMenu() (menu he thong) da tu goi
		// buildAutoSyncMenuTitle() de tinh lai title dong. Vi day la 1 PopupMenu HOAN TOAN MOI,
		// khong di qua onPrepareOptionsMenu() bao gio, nen truoc day title luon dung nguyen ban
		// tinh "chua tung dong bo" du da dong bo thanh cong bao nhieu lan -- phai tu goi lai
		// buildAutoSyncMenuTitle() o day, dung y het onPrepareOptionsMenu().
		android.view.MenuItem autoSyncItem = popup.getMenu().findItem(R.id.menu_auto_sync_now);
		if (autoSyncItem != null) autoSyncItem.setTitle(buildAutoSyncMenuTitle());
		// PopupMenu chi dung de inflate/giu Menu (icon + group da khai bao trong XML) -- khong
		// goi popup.show() nua, vi PopupMenu he thong an icon va khong ve vach chia nhom.
		// HeaderOverflowMenu doc lai dung Menu nay de tu ve popup rieng (xem class do).
		HeaderOverflowMenu overflowMenu = new HeaderOverflowMenu(requireContext(), popup.getMenu());
		overflowMenu.setDestructiveItemIds(R.id.menu_selection_delete);
		overflowMenu.setOnMenuItemClickListener(this::onOptionsItemSelected);
		overflowMenu.show(anchorView);
	}

	/** Minimal RecyclerView adapter for the "Filter By Tags" screen (plain tag-name rows,
	 * android.R.layout.simple_list_item_1). Replaces TagCursorAdapter here only, since that
	 * class is a CursorAdapter and can't attach to a RecyclerView; TagCursorAdapter itself is
	 * left in place unused rather than deleted, in case anything else is added against it
	 * later. */
	private static class TagAdapter extends RecyclerView.Adapter<TagAdapter.VH> {
		interface OnTagClick { void onTagClick(int tagId); }

		private final Cursor cursor;
		private final OnTagClick listener;

		TagAdapter(Cursor cursor, OnTagClick listener) {
			this.cursor = cursor;
			this.listener = listener;
		}

		@NonNull
		@Override
		public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			View v = LayoutInflater.from(parent.getContext())
					.inflate(android.R.layout.simple_list_item_1, parent, false);
			return new VH(v);
		}

		@Override
		public void onBindViewHolder(@NonNull VH holder, int position) {
			if (cursor == null || !cursor.moveToPosition(position)) return;
			holder.text.setText(cursor.getString(1));
			final int tagId = cursor.getInt(0);
			holder.itemView.setOnClickListener(v -> listener.onTagClick(tagId));
		}

		@Override
		public int getItemCount() {
			return cursor == null ? 0 : cursor.getCount();
		}

		static class VH extends RecyclerView.ViewHolder {
			final TextView text;
			VH(@NonNull View itemView) {
				super(itemView);
				text = itemView.findViewById(android.R.id.text1);
			}
		}
	}

	/** Minimal RecyclerView adapter for the "Filter by... > Author" screen, mirroring
	 * TagAdapter above. Authors have no id column of their own (getAuthorsWithBooks() is a
	 * plain SELECT DISTINCT over the free-text "author" field) so, unlike TagAdapter, this
	 * hands back the author string itself rather than a numeric id. */
	private static class AuthorAdapter extends RecyclerView.Adapter<AuthorAdapter.VH> {
		interface OnAuthorClick { void onAuthorClick(String author); }

		private final Cursor cursor;
		private final OnAuthorClick listener;

		AuthorAdapter(Cursor cursor, OnAuthorClick listener) {
			this.cursor = cursor;
			this.listener = listener;
		}

		@NonNull
		@Override
		public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			View v = LayoutInflater.from(parent.getContext())
					.inflate(android.R.layout.simple_list_item_1, parent, false);
			return new VH(v);
		}

		@Override
		public void onBindViewHolder(@NonNull VH holder, int position) {
			if (cursor == null || !cursor.moveToPosition(position)) return;
			final String author = cursor.getString(0);
			holder.text.setText(author);
			holder.itemView.setOnClickListener(v -> listener.onAuthorClick(author));
		}

		@Override
		public int getItemCount() {
			return cursor == null ? 0 : cursor.getCount();
		}

		static class VH extends RecyclerView.ViewHolder {
			final TextView text;
			VH(@NonNull View itemView) {
				super(itemView);
				text = itemView.findViewById(android.R.id.text1);
			}
		}
	}

}
