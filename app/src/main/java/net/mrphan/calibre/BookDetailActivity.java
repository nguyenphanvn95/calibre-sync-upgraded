package net.mrphan.calibre;

import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.PreferenceManager;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import java.io.File;
import java.net.URL;
import java.util.List;

/**
 * Shows a single book's details for the Calibre Library (OPDS) tab: large cover, title/author,
 * an action row, and "Publication info"/"File info" cards. Replaces the previous behaviour of
 * OPDSBrowserFragment#onItemClick, which downloaded/opened the book immediately on tap with no
 * intermediate screen -- see the "Book Detail" feature notes for the full design, including why
 * several of the action buttons below are local-only stand-ins for Calibre-Web server actions
 * (edit metadata, replace cover, server-side shelves/read-state) that the Content Server's
 * read-only OPDS feed has no API for.
 */
public class BookDetailActivity extends AppCompatActivity {

    /** Key + 3 gia tri hop le cua pref_book_preview_fab_action (ListPreference trong
     * preference_appearance.xml, entryValues khai bao trong arrays.xml) -- xem applyFabAction().
     * Cung 1 bo hang so nay duoc lap lai o DeviceBookDetailActivity vi 2 Activity khong co lop
     * cha chung de dat ham/hang so dung chung, giong cach cac Activity nay da tu chua rieng
     * onReadClicked()/onReadAloudClicked()/onSendToEreaderClicked() cua minh. */
    private static final String PREF_FAB_ACTION_KEY = "pref_book_preview_fab_action";
    private static final String PREF_FAB_ACTION_SEND_TO_EREADER = "send_to_ereader";
    private static final String PREF_FAB_ACTION_READ = "read";
    private static final String PREF_FAB_ACTION_LISTEN = "listen";

    public static final String EXTRA_ENTRY = "entry";
    /** URL of the OPDS page this entry was parsed from, needed to resolve any relative hrefs in
     * entry.formats/entry.href -- always resolve with `new URL(pageUrl, href)`, never by hand
     * assembling protocol+host+port+href (see the identical warning in OPDSBrowserFragment,
     * which used to silently break on any href that wasn't a bare root-relative path). */
    public static final String EXTRA_PAGE_URL = "page_url";
    public static final String EXTRA_AUTH_HEADER = "auth_header";

    /** Vị trí (0-based) của [entry] hiện tại bên trong 1 trong 2 danh sách duyệt dưới đây --
     * dùng chung cho cả 2 nguồn (Calibre OPDS / GDrive), chỉ có 1 nguồn khác null tại 1 thời
     * điểm. -1 nếu màn hình này không có ngữ cảnh danh sách duyệt (vd deep link). */
    public static final String EXTRA_BOOK_LIST_INDEX = "book_list_index";
    /** Nguồn "Calibre Library (OPDS)": true nếu danh sách OPDSEntry cho TRANG đang xem (xem
     * OPDSPage#getEntriesSorted) đã được OPDSBrowserFragment#openBookDetail nạp sẵn vào
     * {@link OpdsBookListHolder} -- đọc từ đó thay vì từ Intent extra, xem javadoc đầy đủ ở
     * OpdsBookListHolder về lý do (danh sách kèm bìa từng cuốn dễ vượt giới hạn kích thước 1
     * giao dịch Binder nếu nhét thẳng vào Intent, gây TransactionTooLargeException ngay tại
     * startActivity()). Optional/false nếu màn hình được mở từ nguồn GDrive hoặc không có ngữ
     * cảnh danh sách. Giới hạn trong phạm vi trang đang tải -- không thể vuốt sang trang OPDS kế
     * tiếp còn nằm trên server, giống hệt hạn chế của OPDSPage#getBooksSorted (không phải lỗi,
     * là đánh đổi để tránh phải tự tải thêm trang giữa lúc đang xem preview). */
    public static final String EXTRA_BOOK_LIST_FROM_OPDS_HOLDER = "book_list_from_opds_holder";
    /** Nguồn "Libraries on GDrive": chỉ mảng bookId (int[]) của các sách trên CÙNG 1 trang
     * WebView đang xem (app.js#openBookDetailNative, giới hạn PAGE_SIZE=20 sách/trang) -- KHÔNG
     * phải OPDSEntry đầy đủ, vì dựng đầy đủ (kèm tải bìa qua HTTP) cho toàn bộ danh sách ngay
     * lúc mở màn hình sẽ chậm không cần thiết khi người dùng chỉ xem 1 cuốn. Mỗi lần vuốt sang
     * 1 cuốn khác, resolveGdriveAdjacentEntry() mới thật sự dựng OPDSEntry cho ĐÚNG cuốn đó qua
     * GDriveBookEntryBuilder (chạy nền, xem EXTRA_GDRIVE_LIBRARY_ID/EXTRA_GDRIVE_PORT). Optional/
     * null nếu màn hình được mở từ nguồn Calibre OPDS hoặc không có ngữ cảnh danh sách. */
    public static final String EXTRA_BOOK_LIST_GDRIVE_IDS = "book_list_gdrive_ids";
    /** id thư viện GDrive (GDriveOpdsLibrary#getId()) dùng để tự dựng lại 1 GDriveLibraryDb khi
     * vuốt -- bắt buộc phải có khi EXTRA_BOOK_LIST_GDRIVE_IDS khác null. */
    public static final String EXTRA_GDRIVE_LIBRARY_ID = "gdrive_library_id";
    /** Cổng server cục bộ (GDriveOpdsHttpServer) đang phục vụ thư viện GDrive này -- dùng để xây
     * href bìa/định dạng cho OPDSEntry dựng lúc vuốt, xem GDriveBookEntryBuilder#build. */
    public static final String EXTRA_GDRIVE_PORT = "gdrive_port";

    private OPDSEntry entry;
    private URL pageUrl;
    private String authHeader;

    /** Xem javadoc EXTRA_BOOK_LIST_FROM_OPDS_HOLDER/EXTRA_BOOK_LIST_GDRIVE_IDS ở trên -- đúng 1
     * trong 2 khác null/true tại 1 thời điểm, hoặc cả 2 đều null/false nếu màn hình không có ngữ
     * cảnh danh sách duyệt để vuốt qua lại. */
    private List<OPDSEntry> bookListEntries;
    private int[] bookListGdriveIds;
    private String gdriveLibraryId;
    private int gdrivePort = -1;
    private int bookListIndex = -1;
    private GestureDetector swipeGestureDetector;
    /** true trong lúc đang tự dựng OPDSEntry cho cuốn GDrive kế/trước ở nền (mạng cục bộ, xem
     * resolveGdriveAdjacentEntry) -- chặn vuốt liên tiếp trong lúc đó thay vì xếp hàng chồng
     * chéo nhiều lần mở activity. Nguồn Calibre OPDS không cần cờ này vì lấy thẳng từ bộ nhớ,
     * không có độ trễ đáng kể. */
    private boolean resolvingGdriveSwipe = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_book_detail);

        entry = (OPDSEntry) getIntent().getSerializableExtra(EXTRA_ENTRY);
        authHeader = getIntent().getStringExtra(EXTRA_AUTH_HEADER);
        if (entry == null) {
            finish();
            return;
        }
        try {
            pageUrl = new URL(getIntent().getStringExtra(EXTRA_PAGE_URL));
        } catch (Exception e) {
            Logger.e(e);
            finish();
            return;
        }

        findViewById(R.id.bd_btn_back).setOnClickListener(v -> finish());
        applyWindowInsets();

        bookListIndex = getIntent().getIntExtra(EXTRA_BOOK_LIST_INDEX, -1);
        boolean fromOpdsHolder = getIntent().getBooleanExtra(EXTRA_BOOK_LIST_FROM_OPDS_HOLDER, false);
        bookListEntries = fromOpdsHolder ? OpdsBookListHolder.get() : null;
        bookListGdriveIds = getIntent().getIntArrayExtra(EXTRA_BOOK_LIST_GDRIVE_IDS);
        gdriveLibraryId = getIntent().getStringExtra(EXTRA_GDRIVE_LIBRARY_ID);
        gdrivePort = getIntent().getIntExtra(EXTRA_GDRIVE_PORT, -1);
        swipeGestureDetector = new GestureDetector(this, new SwipeNavigationGestureListener(this, swipeCallback));

        renderHeader();
        renderPublicationInfo();
        renderFileInfo();
        wireActionButtons();
    }

    /** No classic ActionBar on this screen (see Theme.Calibre.BookDetail), so nothing reserves
     * space for the status bar automatically: the cover is meant to draw full-bleed behind it,
     * but the overlay back button and the scrollable content below need their own padding/margin
     * so they don't end up under the status bar or the gesture nav bar. Same root cause and
     * pattern as BookSearchActivity#onStart/SettingsActivity on targetSdk 35. */
    private void applyWindowInsets() {
        View backButton = findViewById(R.id.bd_btn_back);
        int backButtonMarginDp = 8;
        float density = getResources().getDisplayMetrics().density;
        int baseMarginPx = Math.round(backButtonMarginDp * density);
        ViewCompat.setOnApplyWindowInsetsListener(backButton, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            lp.topMargin = bars.top + baseMarginPx;
            lp.leftMargin = bars.left + baseMarginPx;
            v.setLayoutParams(lp);
            return insets;
        });

        View scroll = findViewById(R.id.bd_scroll);
        View root = findViewById(R.id.bd_root);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            root.setPadding(root.getPaddingLeft(), root.getPaddingTop(),
                    root.getPaddingRight(), bars.bottom);
            return insets;
        });
    }

    private void renderHeader() {
        ImageView cover = findViewById(R.id.bd_cover);
        if (entry.thumb != null) {
            // Cache + decode bat dong bo giong BookRecyclerAdapter/BookCursorAdapter (xem
            // upgrade_1.md muc 3.1) -- truoc day decodeByteArray() chay dong bo tren main thread
            // moi lan mo trang chi tiet, co the giat/ANR ngan voi cover full-res lon.
            CoverCache.init(this);
            String cacheKey = "detail-blob:" + (entry.uuid != null ? entry.uuid : entry.href);
            Bitmap cached = CoverCache.getBitmap(cacheKey);
            if (cached != null) {
                cover.setImageBitmap(cached);
            } else {
                byte[] thumbBytes = entry.thumb;
                // Decode kich thuoc lon hon THUMB_MAX_WIDTH/HEIGHT (300x450) cua danh sach --
                // dung chieu rong man hinh thuc te, tranh decode full-res khong can thiet nhung
                // van net hon anh nho trong list.
                int targetWidth = getResources().getDisplayMetrics().widthPixels;
                CoverDecodeExecutor.decodeAsync(
                        () -> decodeDetailCoverOffThread(thumbBytes, targetWidth),
                        bitmap -> {
                            if (bitmap != null && !isFinishing() && !isDestroyed()) {
                                CoverCache.putBitmap(cacheKey, bitmap);
                                cover.setImageBitmap(bitmap);
                            }
                        });
            }
        }
        ((TextView) findViewById(R.id.bd_title)).setText(entry.title);
        TextView authorView = findViewById(R.id.bd_author);
        if (!TextUtils.isEmpty(entry.author)) {
            authorView.setText(entry.author);
            authorView.setVisibility(View.VISIBLE);
        } else {
            authorView.setVisibility(View.GONE);
        }
        setTitle(entry.title);
    }

    /** Chay tren background thread (CoverDecodeExecutor). Uoc luong chieu cao muc tieu theo ty le
     * khung anh cover pho bien (2:3) tu targetWidth, vi day la anh cover full-bleed dang chiem
     * toan bo chieu rong man hinh (khac voi thumbnail vuong/gan vuong trong list). */
    private static Bitmap decodeDetailCoverOffThread(byte[] thumbBytes, int targetWidth) {
        int targetHeight = Math.round(targetWidth * 1.5f);
        return CoverManager.decodeSampledBitmapFromBytes(thumbBytes, targetWidth, targetHeight);
    }

    /** Renders the "Publication info" card. Each row respects BookInfoColumns.isVisible() (see
     * SettingsActivity's "Book info columns" setting) on top of the usual setRowOrHide()
     * empty-hides-the-row behaviour -- a column the user turned off never shows even if the
     * feed *did* provide a value for it, and a column that's still on but genuinely has no
     * value from the feed stays hidden exactly like before this setting existed. */
    private void renderPublicationInfo() {
        boolean any = false;
        if (BookInfoColumns.isVisible(this, BookInfoColumns.KEY_UPDATED)) {
            any |= setRowOrHide(R.id.bd_row_updated, R.string.bookdetail_row_updated, OPDSFormatUtils.formatIsoDatePrefix(entry.updated));
        } else {
            findViewById(R.id.bd_row_updated).setVisibility(View.GONE);
        }
        if (BookInfoColumns.isVisible(this, BookInfoColumns.KEY_PUBLISHED)) {
            any |= setRowOrHide(R.id.bd_row_published, R.string.devicebd_row_pubdate, OPDSFormatUtils.formatIsoDatePrefix(entry.published));
        } else {
            findViewById(R.id.bd_row_published).setVisibility(View.GONE);
        }
        if (BookInfoColumns.isVisible(this, BookInfoColumns.KEY_LANGUAGE)) {
            any |= setRowOrHide(R.id.bd_row_language, R.string.bookdetail_row_language, entry.language);
        } else {
            findViewById(R.id.bd_row_language).setVisibility(View.GONE);
        }
        if (BookInfoColumns.isVisible(this, BookInfoColumns.KEY_PUBLISHER)) {
            any |= setRowOrHide(R.id.bd_row_publisher, R.string.bookdetail_row_publisher, entry.publisher);
        } else {
            findViewById(R.id.bd_row_publisher).setVisibility(View.GONE);
        }
        if (BookInfoColumns.isVisible(this, BookInfoColumns.KEY_SERIES) && entry.series != null) {
            String text = entry.seriesIndex > 0
                    ? getString(R.string.devicebd_row_series_with_index, entry.series, OPDSFormatUtils.formatNumber(entry.seriesIndex))
                    : getString(R.string.devicebd_row_series, entry.series);
            ((TextView) findViewById(R.id.bd_row_series)).setText(text);
            findViewById(R.id.bd_row_series).setVisibility(View.VISIBLE);
            any = true;
        } else {
            findViewById(R.id.bd_row_series).setVisibility(View.GONE);
        }
        if (BookInfoColumns.isVisible(this, BookInfoColumns.KEY_RATING) && entry.rating > 0) {
            any |= setRowOrHide(R.id.bd_row_rating, R.string.devicebd_row_rating, OPDSFormatUtils.formatNumber(entry.rating));
        } else {
            findViewById(R.id.bd_row_rating).setVisibility(View.GONE);
        }
        // Tags: bo hang text cu (bd_row_tags), dung ChipGroup rieng chen truoc no.
        if (BookInfoColumns.isVisible(this, BookInfoColumns.KEY_TAGS) && entry.tags != null && entry.tags.length > 0) {
            LinearLayout tagsContainer = findViewById(R.id.bd_tags_container);
            any |= BookDetailIconRow.addTagsChipRow(tagsContainer, entry.tags);
            tagsContainer.setVisibility(View.VISIBLE);
        } else {
            findViewById(R.id.bd_tags_container).setVisibility(View.GONE);
        }
        findViewById(R.id.bd_row_tags).setVisibility(View.GONE); // Thay hoan toan boi bd_tags_container.
        if (BookInfoColumns.isVisible(this, BookInfoColumns.KEY_IDENTIFIERS) && !entry.identifiers.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<String, String> e : entry.identifiers.entrySet()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(e.getKey()).append(": ").append(e.getValue());
            }
            any |= setRowOrHide(R.id.bd_row_identifiers, R.string.devicebd_row_identifiers, sb.toString());
        } else {
            findViewById(R.id.bd_row_identifiers).setVisibility(View.GONE);
        }

        TextView summary = findViewById(R.id.bd_row_summary);
        boolean showDescription = BookInfoColumns.isVisible(this, BookInfoColumns.KEY_DESCRIPTION);
        // BUG FIX: entry.summary from GDrive/OPDS comments is often raw HTML ("<div><p>...</p>
        // </div>") -- setText() used to render it literally as visible tags instead of
        // formatted text. HtmlTextUtils.stripHtml() is the same helper DeviceBookDetailActivity
        // already uses for the Device Library's book detail screen, kept here too so both
        // screens behave the same way.
        String plainSummary = HtmlTextUtils.stripHtml(entry.summary);
        if (showDescription && !TextUtils.isEmpty(plainSummary)) {
            summary.setText(plainSummary);
            summary.setVisibility(View.VISIBLE);
            any = true;
        } else {
            summary.setVisibility(View.GONE);
        }

        findViewById(R.id.bd_publication_card).setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** Hides the whole row if the server didn't provide this field -- never shows "null" or an
     * empty line. Now returns boolean (true = row had data and was shown) so the caller can fold
     * it into the "any" flag that decides whether the whole card should be visible at all,
     * instead of always being void like before. */
    private boolean setRowOrHide(int viewId, int labelResId, String value) {
        TextView tv = findViewById(viewId);
        if (TextUtils.isEmpty(value)) {
            tv.setVisibility(View.GONE);
            return false;
        }
        tv.setVisibility(View.VISIBLE);
        tv.setText(getString(labelResId, value));
        return true;
    }

    private void renderFileInfo() {
        LinearLayout container = findViewById(R.id.bd_file_info_container);
        List<OPDSEntry.Format> formats = entry.formats;
        if (formats == null || formats.isEmpty()) return;
        LayoutInflater inflater = LayoutInflater.from(this);
        for (OPDSEntry.Format f : formats) {
            TextView row = (TextView) inflater.inflate(android.R.layout.simple_list_item_1, container, false);
            row.setTextSize(13);
            row.setPadding(0, 4, 0, 4);
            String label = OPDSFormatUtils.formatLabel(f);
            String sizeStr = f.fileSizeBytes > 0 ? OPDSFormatUtils.formatFileSize(f.fileSizeBytes) : null;
            row.setText(sizeStr != null
                    ? getString(R.string.bookdetail_row_file_with_size, label, sizeStr)
                    : getString(R.string.bookdetail_row_file_no_size, label));
            container.addView(row);
        }
    }

    private void wireActionButtons() {
        ImageButton btnRead = findViewById(R.id.bd_btn_read);
        ImageButton btnDownload = findViewById(R.id.bd_btn_download);
        ImageButton btnViewCover = findViewById(R.id.bd_btn_view_cover);
        ImageButton btnShare = findViewById(R.id.bd_btn_share);
        ImageButton btnToggleRead = findViewById(R.id.bd_btn_toggle_read);

        btnDownload.setOnClickListener(v -> onDownloadButtonTapped());
        btnRead.setOnClickListener(v -> onReadClicked());
        btnViewCover.setOnClickListener(v -> onViewCoverClicked());
        btnShare.setOnClickListener(v -> onShareClicked());
        btnToggleRead.setOnClickListener(v -> onReadAloudClicked());

        // No cover to show yet -- disable rather than hide, so the action row's layout stays
        // stable across books that do/don't have a cover cached.
        btnViewCover.setEnabled(entry.thumb != null);
        btnViewCover.setAlpha(entry.thumb != null ? 1f : 0.4f);

        // "Nghe sách" (listen via TTS) only applies to EPUB (plain text) -- disable rather than
        // hide, same reasoning as btnViewCover above, so the action row layout stays stable.
        boolean hasEpub = hasEpub();
        btnToggleRead.setEnabled(hasEpub);
        btnToggleRead.setAlpha(hasEpub ? 1f : 0.4f);

        // FAB (bd_btn_send_to_ereader) khong con co dinh la "Gui toi E-Reader" nua -- text/icon/
        // hanh dong cua no gio phu thuoc pref_book_preview_fab_action, xem applyFabAction().
        applyFabAction();
    }

    /** @return true neu entry hien tai co dinh dang EPUB -- dung chung cho ca dieu kien disable
     * btnToggleRead trong hang nut icon ngang (wireActionButtons) lan dieu kien disable FAB khi
     * FAB dang duoc gan hanh dong "Nghe" (applyFabAction), de 2 noi luon dong bo voi nhau. */
    private boolean hasEpub() {
        return OPDSFormatUtils.hasFormat(entry.formats, "EPUB");
    }

    /** Doc pref_book_preview_fab_action (mac dinh "send_to_ereader" neu chua set, giu dung hanh
     * vi cu truoc khi co setting nay) va cau hinh text/icon/click-listener cho FAB goc duoi-phai
     * theo 1 trong 3 hanh dong: Gui toi E-Reader / Doc / Nghe. Tach rieng khoi wireActionButtons()
     * vi day la phan duy nhat co the can goi lai doc lap (vd sau nay neu man hinh nay ho tro
     * refresh pref ma khong phai goi lai toan bo wireActionButtons). */
    private void applyFabAction() {
        ExtendedFloatingActionButton fab = findViewById(R.id.bd_btn_send_to_ereader);
        String action = PreferenceManager.getDefaultSharedPreferences(this)
                .getString(PREF_FAB_ACTION_KEY, PREF_FAB_ACTION_SEND_TO_EREADER);
        switch (action) {
            case PREF_FAB_ACTION_READ:
                fab.setText(R.string.bookdetail_action_read);
                fab.setIconResource(R.drawable.ic_action_open_book);
                fab.setOnClickListener(v -> onReadClicked());
                fab.setEnabled(true);
                fab.setAlpha(1f);
                break;
            case PREF_FAB_ACTION_LISTEN:
                fab.setText(R.string.bookdetail_action_read_aloud);
                fab.setIconResource(R.drawable.ic_action_headphones);
                fab.setOnClickListener(v -> onReadAloudClicked());
                // Nghe (TTS) chi ap dung EPUB -- cung dieu kien voi btnToggleRead trong hang nut
                // icon ngang o tren. Disable + mo thay vi an han, de nguoi dung hieu day la "sach
                // nay khong doc to duoc" chu khong phai loi/khong phan ung.
                boolean hasEpub = hasEpub();
                fab.setEnabled(hasEpub);
                fab.setAlpha(hasEpub ? 1f : 0.4f);
                break;
            case PREF_FAB_ACTION_SEND_TO_EREADER:
            default:
                fab.setText(R.string.bookdetail_send_to_ereader);
                fab.setIconResource(R.drawable.ic_action_send);
                fab.setOnClickListener(v -> onSendToEreaderClicked());
                fab.setEnabled(true);
                fab.setAlpha(1f);
                break;
        }
    }

    /** Share: unlike DeviceBookDetailActivity's Share button (always has a local file, since that
     * screen only ever shows books already in the Device Library), a Calibre Library / OPDS /
     * GDrive preview may not have been downloaded yet. If a file is already cached for this
     * uuid (same MetadataDatabaseHelper lookup onSendToEreaderClicked()/resolveFileThenOpenSendDialog()
     * use), share that file exactly like DeviceBookDetailActivity does; otherwise fall back to
     * sharing the book's title/author (plain text via ACTION_SEND) so the button always does
     * something useful instead of silently failing when there's no file to attach yet. */
    private void onShareClicked() {
        File cachedFile = resolveCachedFileForUuid(entry.uuid);
        if (cachedFile != null) {
            ShareUtils.shareFile(this, cachedFile);
            return;
        }
        String text = TextUtils.isEmpty(entry.author)
                ? entry.title
                : getString(R.string.bookdetail_share_text_fallback, entry.title, entry.author);
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, entry.title);
        shareIntent.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(shareIntent, getString(R.string.action_share)));
    }

    /** @return the on-disk file already downloaded for [uuid] via the Device Library, or null if
     * this book hasn't been downloaded yet (no lpath in MetadataDatabaseHelper) or the file was
     * removed outside the app. Same lookup resolveFileThenOpenSendDialog() below already does for
     * "Send to E-Reader" -- pulled out so onShareClicked() can reuse it. */
    private File resolveCachedFileForUuid(String uuid) {
        if (uuid == null) return null;
        MetadataDatabaseHelper db = new MetadataDatabaseHelper(this);
        try {
            Cursor cs = db.getByUuid(new String[]{"lpath"}, uuid);
            try {
                if (cs.moveToFirst() && !cs.isNull(0)) {
                    SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
                    File rootDir = StorageUtils.getRootDirectory(getApplicationContext(), settings);
                    File cachedFile = new File(rootDir, cs.getString(0));
                    if (cachedFile.exists()) return cachedFile;
                }
                return null;
            } finally {
                cs.close();
            }
        } finally {
            db.close();
        }
    }

    // --- Listen (TTS): unlike Read/Download, this always goes through OpenBookActivity with
    // EXTRA_OPEN_AS_AUDIO so a not-yet-downloaded EPUB is fetched first, then routed to
    // TtsChapterListActivity instead of the normal text reader. Only enabled when the entry has
    // an EPUB format (see wireActionButtons above). ---
    private void onReadAloudClicked() {
        List<OPDSEntry.Format> formats = entry.formats;
        OPDSEntry.Format epubFormat = null;
        if (formats != null) {
            for (OPDSEntry.Format f : formats) {
                if ("EPUB".equalsIgnoreCase(OPDSFormatUtils.formatLabel(f))) {
                    epubFormat = f;
                    break;
                }
            }
        }
        if (epubFormat == null) {
            Toast.makeText(this, R.string.reader_tts_init_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            URL url = new URL(pageUrl, epubFormat.href);
            startActivity(new Intent(this, OpenBookActivity.class)
                    .putExtra(OpenBookActivity.EXTRA_UUID, entry.uuid)
                    .putExtra(OpenBookActivity.EXTRA_URL, url.toString())
                    .putExtra(OpenBookActivity.EXTRA_AUTH_HEADER, authHeader)
                    .putExtra(OpenBookActivity.EXTRA_FORCE_DOWNLOAD, false)
                    .putExtra(OpenBookActivity.EXTRA_OPEN_AS_AUDIO, true));
        } catch (Exception e) {
            Logger.e(e);
            Toast.makeText(this, R.string.opds_error_navigation, Toast.LENGTH_SHORT).show();
        }
    }

    // --- Download: reuses the shared format-chooser dialog (OPDSFormatUtils) instead of the
    // duplicate copy that used to live in OPDSBrowserFragment.
    //
    // forceDownload controls what OpenBookActivity does when a file is already cached for this
    // book's uuid (the local metadata DB only ever keeps one lpath per uuid, regardless of which
    // format it was downloaded as -- see MetadataDatabaseHelper's schema). With forceDownload
    // false (the "Read" button), a cached file is opened straight away, same as before. With
    // forceDownload true (the "Download" button), the picked format's URL is always fetched and
    // replaces whatever's cached -- previously this was always false, so picking e.g. PDF from
    // the format chooser for a book that already had a cached EPUB just silently reopened that
    // EPUB instead of downloading the PDF. ---
    private void onDownloadClicked(boolean forceDownload) {
        List<OPDSEntry.Format> formats = entry.formats;
        if (formats != null && formats.size() > 1) {
            OPDSFormatUtils.showFormatChooserDialog(this, formats, pageUrl, entry.uuid,
                    (uuid, resolvedUrl) -> startDownload(resolvedUrl, forceDownload));
        } else {
            try {
                String href = (formats != null && !formats.isEmpty()) ? formats.get(0).href : entry.href;
                if (href == null) {
                    Toast.makeText(this, R.string.opds_error_navigation, Toast.LENGTH_SHORT).show();
                    return;
                }
                startDownload(new URL(pageUrl, href), forceDownload);
            } catch (Exception e) {
                Logger.e(e);
                Toast.makeText(this, R.string.opds_error_navigation, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startDownload(URL url, boolean forceDownload) {
        startDownload(url, forceDownload, entry.uuid);
    }

    private void startDownload(URL url, boolean forceDownload, String uuidOverride) {
        startActivity(new Intent(this, OpenBookActivity.class)
                .putExtra(OpenBookActivity.EXTRA_UUID, uuidOverride)
                .putExtra(OpenBookActivity.EXTRA_URL, url.toString())
                .putExtra(OpenBookActivity.EXTRA_AUTH_HEADER, authHeader)
                .putExtra(OpenBookActivity.EXTRA_FORCE_DOWNLOAD, forceDownload));
    }

    /** Diem vao thuc su cua nut "Tai" -- neu sach nay (theo uuid) da co san lpath trong Device
     * Library, hoi lai truoc: "Ghi de" tai lai dung file cu (forceDownload=true nhu truoc gio,
     * onDownloadClicked() da co san), hoac "Tai them ban moi" tao 1 ban ghi doc lap (uuid moi) thay
     * vi ghi de. Sach CHUA co trong Device Library thi tai luon nhu cu, khong hoi gi ca. */
    private void onDownloadButtonTapped() {
        if (entry.uuid == null || !isAlreadyInDeviceLibrary(entry.uuid)) {
            onDownloadClicked(true);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.gdrive_opds_already_in_device_library_title)
                .setMessage(R.string.gdrive_opds_already_in_device_library_message)
                .setPositiveButton(R.string.gdrive_opds_redownload_overwrite, (d, w) -> onDownloadClicked(true))
                .setNegativeButton(R.string.gdrive_opds_redownload_add_new, (d, w) -> onDownloadClickedAsNewCopy())
                .setNeutralButton(android.R.string.cancel, null)
                .show();
    }

    private boolean isAlreadyInDeviceLibrary(String uuid) {
        MetadataDatabaseHelper metaDb = new MetadataDatabaseHelper(getApplicationContext());
        try {
            Cursor cs = metaDb.getByUuid(new String[]{"lpath"}, uuid);
            try {
                return cs.moveToFirst() && !cs.isNull(0);
            } finally {
                cs.close();
            }
        } finally {
            metaDb.close();
        }
    }

    /** "Tai them ban moi": dang ky truoc 1 uuid MOI (khac entry.uuid goc) voi metadata "tam" (giong
     * ensureBareMetadataRegistered() ben GDriveLibraryJsBridge), roi tai ve gan voi uuid moi do --
     * OpenBookActivity#insertBook(uuid, lpath) chi UPDATE dung 1 dong theo uuid nen 2 uuid khac nhau
     * => 2 dong doc lap trong Device Library cho cung 1 cuon sach, dung y nghia "them ban moi". */
    private void onDownloadClickedAsNewCopy() {
        String newUuid = java.util.UUID.randomUUID().toString();
        MetadataDatabaseHelper metaDb = new MetadataDatabaseHelper(getApplicationContext());
        try {
            String[] tagsArr = entry.tags != null ? entry.tags : new String[0];
            metaDb.insertMetadata(newUuid, null, entry.author, entry.title, tagsArr, null, null);
        } finally {
            metaDb.close();
        }
        List<OPDSEntry.Format> formats = entry.formats;
        if (formats != null && formats.size() > 1) {
            OPDSFormatUtils.showFormatChooserDialog(this, formats, pageUrl, entry.uuid,
                    (uuid, resolvedUrl) -> startDownload(resolvedUrl, true, newUuid));
        } else {
            try {
                String href = (formats != null && !formats.isEmpty()) ? formats.get(0).href : entry.href;
                if (href == null) {
                    Toast.makeText(this, R.string.opds_error_navigation, Toast.LENGTH_SHORT).show();
                    return;
                }
                startDownload(new URL(pageUrl, href), true, newUuid);
            } catch (Exception e) {
                Logger.e(e);
                Toast.makeText(this, R.string.opds_error_navigation, Toast.LENGTH_SHORT).show();
            }
        }
    }

    // --- Read: OpenBookActivity already handles both cases (book already on disk -> opens it
    // straight away; not on disk yet -> downloads then opens), so "Read" reuses the exact same
    // code path as "Download", just without forcing a re-download of an already-cached file. ---
    private void onReadClicked() {
        onDownloadClicked(false);
    }

    private void onViewCoverClicked() {
        if (entry.thumb == null) return;
        startActivity(new Intent(this, ViewCoverActivity.class)
                .putExtra(ViewCoverActivity.EXTRA_COVER_BYTES, entry.thumb));
    }

    // --- Send to E-Reader: relays the book through the send2ereader service (see
    // Send2EreaderClient's class Javadoc for the full pairing-key protocol). Unlike
    // DeviceBookDetailActivity, a Calibre Library / OPDS entry may not have a local file yet --
    // pick the acquisition URL (reusing the same format-chooser flow as Download/Read), then
    // either open straight from an existing local cache (via MetadataDatabaseHelper's lpath for
    // this uuid, the same lookup OpenBookActivity#openBook uses) or download a temp copy into
    // cache dir first. ---
    private void onSendToEreaderClicked() {
        List<OPDSEntry.Format> formats = entry.formats;
        if (formats != null && formats.size() > 1) {
            OPDSFormatUtils.showFormatChooserDialog(this, formats, pageUrl, entry.uuid,
                    (uuid, resolvedUrl) -> resolveFileThenOpenSendDialog(resolvedUrl));
        } else {
            try {
                String href = (formats != null && !formats.isEmpty()) ? formats.get(0).href : entry.href;
                if (href == null) {
                    Toast.makeText(this, R.string.opds_error_navigation, Toast.LENGTH_SHORT).show();
                    return;
                }
                resolveFileThenOpenSendDialog(new URL(pageUrl, href));
            } catch (Exception e) {
                Logger.e(e);
                Toast.makeText(this, R.string.opds_error_navigation, Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** If a file is already cached on disk for this uuid (same MetadataDatabaseHelper lookup
     * OpenBookActivity#openBook uses), send that straight away without re-downloading --
     * whatever format it was originally fetched as. Otherwise fetch a throwaway copy into cache
     * dir (getExternalCacheDir(), not the main library folder) just for this upload, and have
     * the dialog delete it once it's done. */
    private void resolveFileThenOpenSendDialog(URL resolvedUrl) {
        MetadataDatabaseHelper db = new MetadataDatabaseHelper(this);
        try {
            Cursor cs = db.getByUuid(new String[] {"lpath"}, entry.uuid);
            try {
                if (cs.moveToFirst() && !cs.isNull(0)) {
                    String lpath = cs.getString(0);
                    SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
                    File rootDir = StorageUtils.getRootDirectory(getApplicationContext(), settings);
                    File cachedFile = new File(rootDir, lpath);
                    if (cachedFile.exists()) {
                        String mimeType = guessMimeTypeFromHref(lpath);
                        SendToEreaderDialogFragment.newInstance(cachedFile, entry.title, mimeType, false)
                                .show(getSupportFragmentManager(), "send_to_ereader");
                        return;
                    }
                }
            } finally {
                cs.close();
            }
        } finally {
            db.close();
        }
        downloadTempThenOpenSendDialog(resolvedUrl);
    }

    private void downloadTempThenOpenSendDialog(URL resolvedUrl) {
        File tempFile = new File(getExternalCacheDir(), "s2e_" + entry.uuid + "_" + fileNameFromUrl(resolvedUrl));
        Toast.makeText(this, R.string.s2e_downloading, Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            boolean ok = downloadToFile(resolvedUrl, tempFile, authHeader);
            runOnUiThread(() -> {
                if (!ok) {
                    Toast.makeText(this, R.string.s2e_error_download_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                String mimeType = guessMimeTypeFromHref(resolvedUrl.toString());
                // deleteFileAfterUse=true: this is a throwaway cache-dir copy made only for this
                // upload, unlike DeviceBookDetailActivity's permanent on-disk library file.
                SendToEreaderDialogFragment.newInstance(tempFile, entry.title, mimeType, true)
                        .show(getSupportFragmentManager(), "send_to_ereader");
            });
        }).start();
    }

    /** Plain blocking download using the same HttpURLConnection + Basic Auth pattern as
     * AsyncDownloader, but writing straight to a known temp path in cache dir instead of resolving
     * a filename from Content-Disposition -- this is a throwaway upload copy, not a permanent
     * library file, so the exact on-disk name doesn't matter. */
    private boolean downloadToFile(URL url, File targetFile, String authHeader) {
        java.net.HttpURLConnection connection = null;
        try {
            connection = (java.net.HttpURLConnection) url.openConnection();
            if (authHeader != null) connection.setRequestProperty("Authorization", authHeader);
            connection.connect();
            if (connection.getResponseCode() != java.net.HttpURLConnection.HTTP_OK) return false;
            try (java.io.InputStream in = connection.getInputStream();
                 java.io.OutputStream out = new java.io.FileOutputStream(targetFile)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            }
            return true;
        } catch (Exception e) {
            Logger.e(e);
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String fileNameFromUrl(URL url) {
        String path = url.getPath();
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** Same mapping OPDSFormatUtils.guessFormatFromHref/guessFormatFromMime already do in the
     * opposite direction (format from mime/href) -- kept as a small separate helper here rather
     * than forcing a shared signature change on OPDSFormatUtils for this one call site. */
    private static String guessMimeTypeFromHref(String href) {
        String upper = href.toUpperCase(java.util.Locale.ROOT);
        if (upper.contains("EPUB")) return "application/epub+zip";
        if (upper.contains("PDF")) return "application/pdf";
        if (upper.contains("MOBI")) return "application/x-mobipocket-ebook";
        return "application/octet-stream";
    }

    /** Local tags and mark as read/unread still need a local DB schema change that should be
     * built and tested as its own follow-up change -- see the "Book Detail" feature notes,
     * phase 7. The stub button that used to sit here (bd_btn_tags, wired to a plain "coming
     * soon" toast) was replaced with a working Share button (bd_btn_share, see onShareClicked()). */

    /** Vuốt phải -> trái: mở cuốn KẾ TIẾP trong danh sách duyệt. Vuốt trái -> phải: mở cuốn
     * TRƯỚC ĐÓ. Xem javadoc EXTRA_BOOK_LIST_FROM_OPDS_HOLDER/EXTRA_BOOK_LIST_GDRIVE_IDS ở đầu file --
     * đúng cách xử lý phụ thuộc nguồn nào đang có mặt. */
    private final SwipeNavigationGestureListener.Callback swipeCallback = new SwipeNavigationGestureListener.Callback() {
        @Override public void onSwipeRightToLeft() { onSwipeToAdjacentBook(1); }
        @Override public void onSwipeLeftToRight() { onSwipeToAdjacentBook(-1); }
    };

    /** Quan sát MỌI touch event của màn hình (kể cả những cái bd_scroll/nút bấm bên trong đã xử
     * lý) để nhận diện vuốt ngang toàn màn hình -- luôn gọi super sau đó nên hành vi cuộn dọc
     * của bd_scroll và các nút bấm không hề bị ảnh hưởng, giống hệt DeviceBookDetailActivity. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (swipeGestureDetector != null) swipeGestureDetector.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    /** @param direction +1 = cuốn KẾ TIẾP (vuốt phải->trái), -1 = cuốn TRƯỚC ĐÓ (vuốt trái->phải). */
    private void onSwipeToAdjacentBook(int direction) {
        if (resolvingGdriveSwipe) return; // dang tu dung 1 OPDSEntry khac o nen, cho no xong da
        int newIndex = bookListIndex + direction;

        if (bookListEntries != null && !bookListEntries.isEmpty()) {
            if (!checkSwipeBounds(newIndex, bookListEntries.size())) return;
            // Khong can goi lai OpdsBookListHolder.set() -- van la CUNG 1 danh sach trong bo nho
            // tu luc mo man hinh dau tien, chi can bao BookDetailActivity moi doc lai tu do.
            startActivity(new Intent(this, BookDetailActivity.class)
                    .putExtra(EXTRA_ENTRY, bookListEntries.get(newIndex))
                    .putExtra(EXTRA_PAGE_URL, pageUrl.toString())
                    .putExtra(EXTRA_AUTH_HEADER, authHeader)
                    .putExtra(EXTRA_BOOK_LIST_FROM_OPDS_HOLDER, true)
                    .putExtra(EXTRA_BOOK_LIST_INDEX, newIndex));
            applySwipeTransition(direction);
            finish();
            return;
        }

        if (bookListGdriveIds != null && bookListGdriveIds.length > 0) {
            if (!checkSwipeBounds(newIndex, bookListGdriveIds.length)) return;
            resolveGdriveAdjacentEntry(newIndex, direction);
            return;
        }

        // Man hinh nay khong duoc mo kem 1 danh sach duyet (vd deep link) -- khong co gi de
        // chuyen qua, im lang bo qua thay vi bao loi kho hieu.
    }

    /** BUG FIX: truoc day khong override gi ca nen he thong dung chung 1 kieu animation mac dinh
     * (activity moi luon "xuat hien" tu ben phai) cho CA 2 chieu vuot, khien vuot trai->phai (mo
     * cuon TRUOC DO) lai thay man hinh xuat hien tu phai giong het chieu nguoc lai -- sai huong
     * so voi ngon tay. Dung chung cho ca 2 diem goi startActivity() o tren (nhanh OPDS holder tu
     * bo nho va nhanh GDrive bat dong bo). @param direction +1 = mo cuon KE TIEP (man hinh moi
     * vao tu phai), -1 = mo cuon TRUOC DO (man hinh moi vao tu trai). */
    private void applySwipeTransition(int direction) {
        if (direction > 0) {
            overridePendingTransition(R.anim.book_detail_enter_from_right, R.anim.book_detail_exit_to_left);
        } else {
            overridePendingTransition(R.anim.book_detail_enter_from_left, R.anim.book_detail_exit_to_right);
        }
    }

    /** @return false (và đã tự hiện Toast biên phù hợp) nếu newIndex nằm ngoài [0, size). */
    private boolean checkSwipeBounds(int newIndex, int size) {
        if (newIndex < 0) {
            Toast.makeText(this, R.string.bookdetail_swipe_first_book, Toast.LENGTH_SHORT).show();
            return false;
        }
        if (newIndex >= size) {
            Toast.makeText(this, R.string.bookdetail_swipe_last_book, Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    /** Nguồn GDrive chỉ mang theo bookId (nhẹ), nên phải tự dựng OPDSEntry đầy đủ (kèm tải bìa
     * qua HTTP cục bộ) cho ĐÚNG 1 cuốn sắp chuyển sang, trên 1 thread nền -- xem javadoc
     * EXTRA_BOOK_LIST_GDRIVE_IDS. Server là localhost (GDriveOpdsHttpServer) nên độ trễ thường
     * chỉ vài chục-vài trăm ms, không cần splash loading riêng. */
    private void resolveGdriveAdjacentEntry(int newIndex, int direction) {
        if (gdriveLibraryId == null || gdrivePort < 0) return;
        resolvingGdriveSwipe = true;
        int targetBookId = bookListGdriveIds[newIndex];
        new Thread(() -> {
            OPDSEntry newEntry = null;
            GDriveLibraryDb db = new GDriveLibraryDb(getApplicationContext(), gdriveLibraryId);
            try {
                newEntry = GDriveBookEntryBuilder.build(getApplicationContext(), db, gdrivePort, targetBookId);
            } catch (Exception e) {
                Logger.e(e);
            } finally {
                db.close();
            }
            OPDSEntry resolvedEntry = newEntry;
            runOnUiThread(() -> {
                resolvingGdriveSwipe = false;
                if (isFinishing() || isDestroyed()) return;
                if (resolvedEntry == null) {
                    // Sach vua bi xoa khoi GDrive giua luc dang duyet, hoac loi mang cuc bo --
                    // khong the mo preview cho no, bao loi ngan gon thay vi crash/man hinh trang.
                    Toast.makeText(this, R.string.bookdetail_swipe_load_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                startActivity(new Intent(this, BookDetailActivity.class)
                        .putExtra(EXTRA_ENTRY, resolvedEntry)
                        .putExtra(EXTRA_PAGE_URL, pageUrl.toString())
                        .putExtra(EXTRA_AUTH_HEADER, authHeader)
                        .putExtra(EXTRA_BOOK_LIST_GDRIVE_IDS, bookListGdriveIds)
                        .putExtra(EXTRA_GDRIVE_LIBRARY_ID, gdriveLibraryId)
                        .putExtra(EXTRA_GDRIVE_PORT, gdrivePort)
                        .putExtra(EXTRA_BOOK_LIST_INDEX, newIndex));
                applySwipeTransition(direction);
                finish();
            });
        }).start();
    }
}
