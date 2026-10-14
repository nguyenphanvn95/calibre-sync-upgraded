package net.mrphan.calibre;

import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Base64;
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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.PreferenceManager;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.Iterator;
import java.util.Locale;

/**
 * Shows a single book's details for the Device Library tab: large cover, title/author, an
 * action row, and "Publication info"/"File info" cards, just like BookDetailActivity does for
 * the Calibre Library (OPDS) tab -- see that class for the original design this one mirrors.
 * <p>
 * Two differences from BookDetailActivity: (1) there is no Download button, since a Device
 * Library book is already saved on disk; (2) the metadata shown comes straight from this
 * book's entry in rootDirectory/metadata.calibre (via DeviceBookMetadataReader), read fresh
 * every time this screen opens, and every field found there is shown, not just a fixed subset
 * -- fields this screen doesn't have a dedicated label for still show up in the "Other info"
 * card instead of being silently dropped. The cover comes from CoverManager's on-disk cache
 * (Book.COVER_PATH), falling back to the metadata.calibre "thumbnail" entry and then to the
 * legacy SQLite thumbnail BLOB, the same priority order BookRecyclerAdapter already uses for
 * the list row thumbnails.
 */
public class DeviceBookDetailActivity extends AppCompatActivity {

    /** Key + 3 gia tri hop le cua pref_book_preview_fab_action -- xem javadoc day du o
     * BookDetailActivity (cung 1 bo hang so, lap lai vi 2 Activity nay khong co lop cha chung). */
    private static final String PREF_FAB_ACTION_KEY = "pref_book_preview_fab_action";
    private static final String PREF_FAB_ACTION_SEND_TO_EREADER = "send_to_ereader";
    private static final String PREF_FAB_ACTION_READ = "read";
    private static final String PREF_FAB_ACTION_LISTEN = "listen";

    public static final String EXTRA_UUID = "uuid";
    /** Ordered uuid của TOÀN BỘ danh sách đang duyệt (đúng thứ tự sort/filter hiện tại của
     * CollectionFragment hoặc BookSearchActivity) tại thời điểm người dùng bấm mở màn hình
     * preview này -- dùng để swipe trái/phải sang cuốn kế tiếp/trước đó mà không cần build lại
     * query sort ở đây. Optional: null/rỗng nếu màn hình này được mở từ nơi không có danh sách
     * duyệt kèm theo (vd deep link) -- khi đó swipe không làm gì cả thay vì crash. */
    public static final String EXTRA_BOOK_LIST_UUIDS = "book_list_uuids";
    /** Vị trí (0-based) của [uuid] bên trong EXTRA_BOOK_LIST_UUIDS. */
    public static final String EXTRA_BOOK_LIST_INDEX = "book_list_index";

    /** Metadata.calibre keys already shown with a dedicated label elsewhere on this screen (or
     * that are purely internal bookkeeping) -- everything else found in the book's JSON entry
     * still gets shown, as a plain "key: value" row in the "Other info" card. Keeping this as a
     * Set (rather than an enum of known Metadata fields) means a metadata.calibre written by a
     * different Calibre version, with fields this list doesn't know about yet, still surfaces
     * completely instead of silently disappearing. */
    private static final java.util.Set<String> KNOWN_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
            "uuid", "lpath", "title", "title_sort", "authors", "author_sort", "author_sort_map",
            "publisher", "pubdate", "last_modified", "timestamp", "languages", "series",
            "series_index", "rating", "tags", "identifiers", "comments", "size", "thumbnail",
            "cover_path", "_new_book_", "application_id",
            "user_metadata" // Dinh nghia (schema) cua Custom Column, khong phai gia tri, da duoc
                             // addCustomColumnRows()/CustomColumnSync tieu thu day du, khong hien
                             // lai thanh 1 khoi JSON tho o "Thong tin khac".
    ));

    private MetadataDatabaseHelper mDB;
    private String uuid;
    /** Full JSON entry for this book from metadata.calibre, or null if the file doesn't exist or
     * has no entry for this uuid yet (e.g. a book found on disk but not yet through a full scan
     * cycle). Every render*() method below must tolerate this being null. */
    private JSONObject bookJson;
    /** Raw bytes of whichever cover source was found (on-disk cache, embedded thumbnail, or the
     * legacy SQLite BLOB), kept around only so the "view cover" action can hand them to
     * ViewCoverActivity without re-reading anything. Null if this book has no cover at all. */
    private byte[] coverBytes;
    /** rootDirectory as of onCreate() -- was a local variable there before the Share button
     * needed it again in onShareClicked(); computing it a second time would work too (it's just
     * a settings read) but keeping the one value avoids the two ever disagreeing mid-lifecycle
     * if settings change while this screen is open. */
    private File rootDir;
    /** Same "prefer metadata.calibre's lpath, fall back to the DB row's lpath" resolution
     * renderFileInfo() already does locally -- lifted to a field so onShareClicked() can reuse
     * it without duplicating that fallback logic. */
    private String resolvedLpath;
    /** metadata_id của quyển sách này trong bảng metadata, dùng để đọc/ghi Local tags qua
     * MetadataDatabaseHelper#getTagNamesForBook/addTagToBook/removeTagFromBook. */
    private int metadataId = -1;

    /** Xem javadoc EXTRA_BOOK_LIST_UUIDS/EXTRA_BOOK_LIST_INDEX. null/-1 nghĩa là màn hình này
     * không có ngữ cảnh danh sách duyệt để swipe qua lại (vd mở từ deep link). */
    private String[] bookListUuids;
    private int bookListIndex = -1;
    /** Nhận diện vuốt trái/phải toàn màn hình để chuyển sang preview cuốn kế tiếp/trước đó --
     * xem onSwipeToAdjacentBook() và dispatchTouchEvent() bên dưới. Chỉ lắng nghe onFling(),
     * không consume touch event nào (dispatchTouchEvent vẫn gọi super sau đó như bình thường)
     * nên ScrollView cuộn dọc (dbd_scroll) hoàn toàn không bị ảnh hưởng -- cùng cách
     * ReaderChromeOverlay#dispatchContentTouch quan sát touch song song mà không chặn WebView. */
    private GestureDetector swipeGestureDetector;

    /** Mở MetadataEditorActivity khi bấm nút "Hiệu đính" và làm mới toàn bộ màn hình nếu có
     * thay đổi (MetadataEditorActivity.RESULT_EXTRA_CHANGED = true) -- đơn giản, chắc chắn hiển
     * thị đúng dữ liệu vừa Hiệu đính (title/author/cover/mọi field khác đều có thể đã đổi),
     * tương tự cách các activity khác trong app tự làm mới sau 1 thao tác sửa dữ liệu. */
    private final ActivityResultLauncher<Intent> editMetadataLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null
                        && result.getData().getBooleanExtra(MetadataEditorActivity.RESULT_EXTRA_CHANGED, false)) {
                    recreate();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_book_detail);

        uuid = getIntent().getStringExtra(EXTRA_UUID);
        if (TextUtils.isEmpty(uuid)) {
            finish();
            return;
        }
        bookListUuids = getIntent().getStringArrayExtra(EXTRA_BOOK_LIST_UUIDS);
        bookListIndex = getIntent().getIntExtra(EXTRA_BOOK_LIST_INDEX, -1);
        swipeGestureDetector = new GestureDetector(this, new SwipeNavigationGestureListener(this, swipeCallback));

        mDB = new MetadataDatabaseHelper(this);
        Cursor cs = mDB.getByUuid(Book.COLUMNS, uuid);
        if (!cs.moveToFirst()) {
            cs.close();
            finish();
            return;
        }

        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        rootDir = StorageUtils.getRootDirectory(getApplicationContext(), settings);
        bookJson = DeviceBookMetadataReader.findByUuid(rootDir, uuid);
        coverBytes = loadCoverBytes(rootDir, cs);

        String fallbackTitle = cs.getString(Book.TITLE);
        String fallbackAuthor = cs.getString(Book.AUTHOR);
        String fallbackLpath = cs.getString(Book.LPATH);
        metadataId = cs.getInt(Book.ID);
        cs.close();

        findViewById(R.id.dbd_btn_back).setOnClickListener(v -> finish());
        applyWindowInsets();

        renderHeader(fallbackTitle, fallbackAuthor);
        renderPublicationInfo();
        renderFileInfo(fallbackLpath, rootDir);
        renderOtherInfo();
        wireActionButtons();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mDB != null) mDB.close();
    }

    /** Quan sát MỌI touch event của màn hình (kể cả những cái ScrollView/nút bấm bên trong đã
     * xử lý) để nhận diện vuốt ngang toàn màn hình -- luôn gọi super sau đó nên hành vi cuộn dọc
     * của dbd_scroll và các nút bấm không hề bị ảnh hưởng, xem javadoc swipeGestureDetector. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (swipeGestureDetector != null) swipeGestureDetector.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    /** Vuốt phải -> trái (ngón tay đi từ phải sang trái, dx < 0): mở cuốn KẾ TIẾP trong danh
     * sách duyệt. Vuốt trái -> phải (dx > 0): mở cuốn TRƯỚC ĐÓ. */
    private final SwipeNavigationGestureListener.Callback swipeCallback = new SwipeNavigationGestureListener.Callback() {
        @Override public void onSwipeRightToLeft() { onSwipeToAdjacentBook(1); }
        @Override public void onSwipeLeftToRight() { onSwipeToAdjacentBook(-1); }
    };

    /** @param direction +1 = cuon KE TIEP (vuot phai->trai), -1 = cuon TRUOC DO (vuot trai->phai). */
    private void onSwipeToAdjacentBook(int direction) {
        if (bookListUuids == null || bookListUuids.length == 0 || bookListIndex < 0) {
            // Man hinh nay khong duoc mo kem 1 danh sach duyet (vd deep link) -- khong co gi de
            // chuyen qua, im lang bo qua thay vi bao loi kho hieu.
            return;
        }
        int newIndex = bookListIndex + direction;
        if (newIndex < 0) {
            Toast.makeText(this, R.string.bookdetail_swipe_first_book, Toast.LENGTH_SHORT).show();
            return;
        }
        if (newIndex >= bookListUuids.length) {
            Toast.makeText(this, R.string.bookdetail_swipe_last_book, Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(this, DeviceBookDetailActivity.class)
                .putExtra(EXTRA_UUID, bookListUuids[newIndex])
                .putExtra(EXTRA_BOOK_LIST_UUIDS, bookListUuids)
                .putExtra(EXTRA_BOOK_LIST_INDEX, newIndex));
        // BUG FIX: truoc day khong override gi ca nen he thong dung chung 1 kieu animation mac
        // dinh (activity moi luon "xuat hien" tu ben phai) cho CA 2 chieu vuot, khien vuot
        // trai->phai (mo cuon TRUOC DO) lai thay man hinh xuat hien tu phai giong het chieu
        // nguoc lai -- sai huong so voi ngon tay. direction > 0 (vuot phai->trai, mo cuon KE
        // TIEP): man hinh moi vao tu phai. direction < 0 (vuot trai->phai, mo cuon TRUOC DO):
        // man hinh moi phai vao tu TRAI.
        if (direction > 0) {
            overridePendingTransition(R.anim.book_detail_enter_from_right, R.anim.book_detail_exit_to_left);
        } else {
            overridePendingTransition(R.anim.book_detail_enter_from_left, R.anim.book_detail_exit_to_right);
        }
        // finish() thay vi giu lai tren back-stack: swipe la "chuyen sang" chu khong phai "mo
        // moi" -- nut Back sau khi swipe nhieu lan van chi quay ve dung 1 buoc, ve lai man hinh
        // danh sach duyet ban dau, khong phai lui qua tung cuon da luot.
        finish();
    }

    /** No classic ActionBar on this screen (Theme.Calibre.BookDetail), so nothing reserves space
     * for the status bar automatically: the cover is meant to draw full-bleed behind it, but the
     * overlay back button and the scrollable content below need their own padding/margin so they
     * don't end up under the status bar or the gesture nav bar. Identical pattern to
     * BookDetailActivity#applyWindowInsets. */
    private void applyWindowInsets() {
        View backButton = findViewById(R.id.dbd_btn_back);
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

        View scroll = findViewById(R.id.dbd_scroll);
        View root = findViewById(R.id.dbd_root);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            root.setPadding(root.getPaddingLeft(), root.getPaddingTop(),
                    root.getPaddingRight(), bars.bottom);
            return insets;
        });
    }

    // ---- Cover loading -----------------------------------------------------------------

    /** Same priority order as BookRecyclerAdapter#loadCoverBitmap, just returning the raw bytes
     * instead of a decoded (and downsampled) Bitmap, since this screen also needs the bytes for
     * the full-screen "view cover" action:
     * (1) the on-disk cached cover file at Book.COVER_PATH, if the file still actually exists;
     * (2) the "thumbnail" entry embedded in this book's metadata.calibre JSON, if present;
     * (3) the legacy thumbnail BLOB in SQLite (Book.THUMB);
     * (4) null, so the caller falls back to the placeholder drawable. */
    private byte[] loadCoverBytes(File rootDir, Cursor cs) {
        try {
            int coverIdx = Book.COVER_PATH;
            if (coverIdx < cs.getColumnCount() && !cs.isNull(coverIdx)) {
                String coverPath = cs.getString(coverIdx);
                if (!TextUtils.isEmpty(coverPath)) {
                    File coverFile = new File(rootDir, coverPath);
                    if (coverFile.exists()) {
                        byte[] fromFile = readAllBytes(coverFile);
                        if (fromFile != null) return fromFile;
                    }
                }
            }
        } catch (Exception e) {
            Logger.e(e);
        }
        if (bookJson != null) {
            JSONArray thumb = bookJson.optJSONArray("thumbnail");
            if (thumb != null && thumb.length() > 2) {
                try {
                    byte[] fromJson = Base64.decode(thumb.optString(2), Base64.DEFAULT);
                    if (fromJson.length > 0) return fromJson;
                } catch (Exception e) {
                    Logger.e(e);
                }
            }
        }
        try {
            byte[] blob = cs.getBlob(Book.THUMB);
            if (blob != null && blob.length > 0) return blob;
        } catch (Exception ignored) {
            // fall through, no cover available
        }
        return null;
    }

    private static byte[] readAllBytes(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int c;
            while ((c = in.read(buf)) > 0) out.write(buf, 0, c);
            return out.toByteArray();
        } catch (Exception e) {
            Logger.e(e);
            return null;
        }
    }

    /** Chay tren background thread (CoverDecodeExecutor), xem BookDetailActivity#
     * decodeDetailCoverOffThread cho cung logic/ly do (uoc luong chieu cao theo ty le 2:3 tu
     * chieu rong man hinh). */
    private static Bitmap decodeDetailCoverOffThread(byte[] coverBytes, int targetWidth) {
        int targetHeight = Math.round(targetWidth * 1.5f);
        return CoverManager.decodeSampledBitmapFromBytes(coverBytes, targetWidth, targetHeight);
    }

    // ---- Rendering ----------------------------------------------------------------------

    private void renderHeader(String fallbackTitle, String fallbackAuthor) {
        ImageView cover = findViewById(R.id.dbd_cover);
        if (coverBytes != null) {
            // Cache + decode bat dong bo giong BookDetailActivity (xem upgrade_1.md muc 3.1) --
            // truoc day decodeByteArray() chay dong bo tren main thread moi lan mo trang chi tiet.
            CoverCache.init(this);
            String cacheKey = "detail-blob:" + (uuid != null ? uuid : String.valueOf(coverBytes.length));
            Bitmap cached = CoverCache.getBitmap(cacheKey);
            if (cached != null) {
                cover.setImageBitmap(cached);
            } else {
                byte[] finalCoverBytes = coverBytes;
                int targetWidth = getResources().getDisplayMetrics().widthPixels;
                CoverDecodeExecutor.decodeAsync(
                        () -> decodeDetailCoverOffThread(finalCoverBytes, targetWidth),
                        bitmap -> {
                            if (bitmap != null && !isFinishing() && !isDestroyed()) {
                                CoverCache.putBitmap(cacheKey, bitmap);
                                cover.setImageBitmap(bitmap);
                            }
                        });
            }
        }

        String title = (bookJson != null) ? bookJson.optString("title", null) : null;
        if (TextUtils.isEmpty(title)) title = fallbackTitle;
        ((TextView) findViewById(R.id.dbd_title)).setText(title);
        setTitle(title);

        String author = joinArray(bookJson != null ? bookJson.optJSONArray("authors") : null);
        if (TextUtils.isEmpty(author) && bookJson != null) {
            author = bookJson.optString("author_sort", null);
        }
        if (TextUtils.isEmpty(author)) author = fallbackAuthor;
        TextView authorView = findViewById(R.id.dbd_author);
        if (!TextUtils.isEmpty(author)) {
            authorView.setText(author);
            authorView.setVisibility(View.VISIBLE);
        } else {
            authorView.setVisibility(View.GONE);
        }
    }

    /** Adds one row per metadata.calibre field this screen has a dedicated label for, skipping
     * any that are absent -- hides the whole "Publication info" card if bookJson is null or
     * every field it might contribute turns out to be empty. Comments/summary render below the
     * row list rather than as a row, same layout position as BookDetailActivity's entry.summary. */
    private void renderPublicationInfo() {
        LinearLayout container = findViewById(R.id.dbd_metadata_container);
        boolean any = false;
        if (bookJson != null) {
            if (BookInfoColumns.isVisible(this, BookInfoColumns.KEY_PUBLISHER)) {
                any |= BookDetailIconRow.addIconRow(container, R.drawable.ic_stat_publisher,
                        this, R.string.bookdetail_row_publisher, bookJson.optString("publisher", null));
            }
            if (BookInfoColumns.isVisible(this, BookInfoColumns.KEY_PUBLISHED)) {
                any |= BookDetailIconRow.addIconRow(container, R.drawable.ic_sort_calendar,
                        this, R.string.devicebd_row_pubdate,
                        OPDSFormatUtils.formatIsoDatePrefix(bookJson.optString("pubdate", null)));
            }
            if (BookInfoColumns.isVisible(this, BookInfoColumns.KEY_UPDATED)) {
                any |= BookDetailIconRow.addIconRow(container, R.drawable.ic_sort_calendar,
                        this, R.string.bookdetail_row_updated,
                        OPDSFormatUtils.formatIsoDatePrefix(nonEmpty(
                                bookJson.optString("last_modified", null),
                                bookJson.optString("timestamp", null))));
            }
            if (BookInfoColumns.isVisible(this, BookInfoColumns.KEY_LANGUAGE)) {
                any |= BookDetailIconRow.addIconRow(container, R.drawable.ic_selection_translate,
                        this, R.string.bookdetail_row_language, joinArray(bookJson.optJSONArray("languages")));
            }
            if (BookInfoColumns.isVisible(this, BookInfoColumns.KEY_SERIES)) {
                any |= addSeriesRowIfPresent(container);
            }
            if (BookInfoColumns.isVisible(this, BookInfoColumns.KEY_RATING)) {
                any |= BookDetailIconRow.addIconRow(container, R.drawable.ic_action_star,
                        this, R.string.devicebd_row_rating, formatRating(bookJson));
            }
            if (BookInfoColumns.isVisible(this, BookInfoColumns.KEY_TAGS)) {
                any |= BookDetailIconRow.addTagsChipRow(container, jsonArrayToStringArray(bookJson.optJSONArray("tags")));
            }
            if (BookInfoColumns.isVisible(this, BookInfoColumns.KEY_IDENTIFIERS)) {
                any |= BookDetailIconRow.addIconRow(container, R.drawable.ic_action_info,
                        this, R.string.devicebd_row_identifiers, joinIdentifiers(bookJson.optJSONObject("identifiers")));
            }
            if (BookInfoColumns.isVisible(this, BookInfoColumns.KEY_CUSTOM_COLUMNS)) {
                any |= addCustomColumnRows(container);
            }
        }

        TextView summary = findViewById(R.id.dbd_row_summary);
        boolean showDescription = BookInfoColumns.isVisible(this, BookInfoColumns.KEY_DESCRIPTION);
        String comments = (bookJson != null) ? HtmlTextUtils.stripHtml(bookJson.optString("comments", null)) : null;
        if (showDescription && !TextUtils.isEmpty(comments)) {
            summary.setText(comments);
            summary.setVisibility(View.VISIBLE);
            any = true;
        } else {
            summary.setVisibility(View.GONE);
        }

        View card = findViewById(R.id.dbd_publication_card);
        card.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** Adds one row per Custom Column the entry's "user_metadata" block actually has a value
     * for (DATABASE_VERSION 13) -- reuses CustomColumnSync.parseFromWireJson() (already
     * normalizes rating to 0-5, joins is_multiple values, etc.) instead of re-parsing
     * "user_metadata" a third way here. Row text is built manually ("label: value") rather than
     * through BookDetailIconRow.addIconRow()'s labelResId overload, since a Custom Column's
     * label is a runtime string from Calibre Desktop, not a compile-time string resource. */
    private boolean addCustomColumnRows(LinearLayout container) {
        if (bookJson == null) return false;
        CustomColumnSync.ParsedCustomColumns parsed = CustomColumnSync.parseFromWireJson(bookJson);
        if (parsed.values.isEmpty()) return false;
        java.util.Map<String, CustomColumnSync.ColumnDef> defsByLookup = new java.util.HashMap<>();
        for (CustomColumnSync.ColumnDef d : parsed.defs) defsByLookup.put(d.lookupName, d);
        boolean any = false;
        for (CustomColumnSync.ColumnValue v : parsed.values) {
            CustomColumnSync.ColumnDef def = defsByLookup.get(v.lookupName);
            if (def == null || TextUtils.isEmpty(v.valueText)) continue;
            String display = def.isMultiple
                    ? TextUtils.join(", ", v.valueText.split(String.valueOf(CustomColumnSync.MULTI_JOIN)))
                    : v.valueText;
            any |= BookDetailIconRow.addIconRow(container, R.drawable.ic_action_info, def.label + ": " + display);
        }
        return any;
    }

    private boolean addSeriesRowIfPresent(LinearLayout container) {
        if (bookJson == null) return false;
        String series = bookJson.optString("series", null);
        if (TextUtils.isEmpty(series)) return false;
        double index = bookJson.optDouble("series_index", -1);
        String text = (index > 0)
                ? getString(R.string.devicebd_row_series_with_index, series, OPDSFormatUtils.formatNumber(index))
                : getString(R.string.devicebd_row_series, series);
        return BookDetailIconRow.addIconRow(container, R.drawable.ic_stat_series, text);
    }

    /** JSONArray -> String[] loai bo phan tu rong, dung cho addTagsChipRow (can 1 mang tag rieng
     * le, khac joinArray() von tra ve 1 chuoi da noi bang ", " cho cac hang text thuong). */
    private static String[] jsonArrayToStringArray(JSONArray arr) {
        if (arr == null || arr.length() == 0) return null;
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, null);
            if (!TextUtils.isEmpty(s)) out.add(s);
        }
        return out.isEmpty() ? null : out.toArray(new String[0]);
    }

    private void renderFileInfo(String fallbackLpath, File rootDir) {
        LinearLayout container = findViewById(R.id.dbd_file_info_container);
        String lpath = (bookJson != null) ? bookJson.optString("lpath", null) : null;
        if (TextUtils.isEmpty(lpath)) lpath = fallbackLpath;
        resolvedLpath = lpath;

        BookDetailIconRow.addIconRow(container, R.drawable.ic_stat_book, this, R.string.devicebd_row_filename, lpath);
        BookDetailIconRow.addIconRow(container, R.drawable.ic_menu_get_metadata_from_ebook, this, R.string.devicebd_row_format, extensionOf(lpath));

        long size = (bookJson != null) ? bookJson.optLong("size", -1) : -1;
        if (size <= 0 && !TextUtils.isEmpty(lpath)) {
            File bookFile = new File(rootDir, lpath);
            if (bookFile.exists()) size = bookFile.length();
        }
        if (size > 0) {
            BookDetailIconRow.addIconRow(container, R.drawable.ic_settings_tab_storage, this, R.string.devicebd_row_size, OPDSFormatUtils.formatFileSize(size));
        }

        BookDetailIconRow.addIconRow(container, R.drawable.ic_action_info, this, R.string.devicebd_row_calibre_id, uuid);
    }

    /** Anything left in bookJson that isn't in KNOWN_KEYS still gets shown here, as a plain
     * "key: value" row -- see the KNOWN_KEYS javadoc for why. Only scalar-ish values (strings,
     * numbers, booleans) are rendered directly; nested arrays/objects fall back to their JSON
     * text so the field is still visible instead of silently dropped. */
    private void renderOtherInfo() {
        View card = findViewById(R.id.dbd_other_card);
        if (bookJson == null) {
            card.setVisibility(View.GONE);
            return;
        }
        LinearLayout container = findViewById(R.id.dbd_other_info_container);
        boolean any = false;
        Iterator<String> keys = bookJson.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (KNOWN_KEYS.contains(key)) continue;
            // Gia tri Custom Column (vd "#last_opened", "#reading_finished") da duoc
            // addCustomColumnRows() hien thi dep (kem nhan that + an neu rong), khong bao gio
            // hien lai thanh "#last_opened: None" o day.
            if (key.startsWith("#")) continue;
            Object value = bookJson.opt(key);
            String text = formatUnknownValue(value);
            if (TextUtils.isEmpty(text)) continue;
            addRow(container, key + ": " + text);
            any = true;
        }
        card.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    private static String formatUnknownValue(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof String) {
            String s = (String) value;
            // Python None dang bi serialize thanh chuoi van ban "None" o mot so cot, coi nhu rong,
            // dung "khong co du lieu thi an di" giong moi field khac, khong hien chu "None" cho
            // nguoi dung cuoi.
            return "None".equals(s) ? null : s;
        }
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        // JSONArray/JSONObject/anything else: show its JSON form rather than drop it.
        String text = value.toString();
        return TextUtils.isEmpty(text) || "{}".equals(text) || "[]".equals(text) ? null : text;
    }

    // ---- Small row/formatting helpers ----------------------------------------------------

    /** Mirrors BookDetailActivity#renderFileInfo's row inflation: a plain platform row layout
     * with the same textSize/padding BookDetailInfoRow declares, applied directly since that
     * style's layout_width/height/padding attrs only take effect via an XML style= attribute,
     * not through a runtime API on an already-inflated view. */
    private void addRow(LinearLayout container, String text) {
        LayoutInflater inflater = LayoutInflater.from(this);
        TextView row = (TextView) inflater.inflate(android.R.layout.simple_list_item_1, container, false);
        row.setTextSize(13);
        row.setPadding(0, 4, 0, 4);
        row.setText(text);
        container.addView(row);
    }

    private static String joinArray(JSONArray arr) {
        if (arr == null || arr.length() == 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, null);
            if (TextUtils.isEmpty(s)) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static String joinIdentifiers(JSONObject identifiers) {
        if (identifiers == null || identifiers.length() == 0) return null;
        StringBuilder sb = new StringBuilder();
        Iterator<String> keys = identifiers.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            String value = identifiers.optString(key, null);
            if (TextUtils.isEmpty(value)) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(key).append(": ").append(value);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static String formatRating(JSONObject bookJson) {
        double rating = bookJson.optDouble("rating", -1);
        if (rating <= 0) return null;
        return OPDSFormatUtils.formatNumber(rating);
    }

    private static String extensionOf(String lpath) {
        if (TextUtils.isEmpty(lpath) || !lpath.contains(".")) return null;
        return lpath.substring(lpath.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
    }

    private static String nonEmpty(String a, String b) {
        return !TextUtils.isEmpty(a) ? a : b;
    }

    // ---- Actions ---------------------------------------------------------------------------

    private void wireActionButtons() {
        ImageButton btnRead = findViewById(R.id.dbd_btn_read);
        ImageButton btnViewCover = findViewById(R.id.dbd_btn_view_cover);
        ImageButton btnEditMetadata = findViewById(R.id.dbd_btn_edit_metadata);
        ImageButton btnToggleRead = findViewById(R.id.dbd_btn_toggle_read);
        ImageButton btnShare = findViewById(R.id.dbd_btn_share);

        btnRead.setOnClickListener(v -> onReadClicked());
        btnViewCover.setOnClickListener(v -> onViewCoverClicked());
        btnEditMetadata.setOnClickListener(v -> editMetadataLauncher.launch(
                new Intent(this, MetadataEditorActivity.class)
                        .putExtra(MetadataEditorActivity.EXTRA_UUID, uuid)));
        btnToggleRead.setOnClickListener(v -> onReadAloudClicked());
        btnShare.setOnClickListener(v -> onShareClicked());

        // No cover to show yet -- disable rather than hide, so the action row's layout stays
        // stable across books that do/don't have a cover cached.
        btnViewCover.setEnabled(coverBytes != null);
        btnViewCover.setAlpha(coverBytes != null ? 1f : 0.4f);

        // "Nghe sách" (listen via TTS) only applies to EPUB (plain text) -- disable rather than
        // hide, same reasoning as btnViewCover above, so the action row layout stays stable.
        boolean isEpub = isEpub();
        btnToggleRead.setEnabled(isEpub);
        btnToggleRead.setAlpha(isEpub ? 1f : 0.4f);

        // FAB (dbd_btn_send_to_ereader) khong con co dinh la "Gui toi E-Reader" nua -- text/icon/
        // hanh dong cua no gio phu thuoc pref_book_preview_fab_action, xem applyFabAction().
        applyFabAction();
    }

    /** @return true neu file dang xem la EPUB (dua theo duoi resolvedLpath) -- dung chung cho ca
     * dieu kien disable btnToggleRead trong hang nut icon ngang (wireActionButtons) lan dieu kien
     * disable FAB khi FAB dang duoc gan hanh dong "Nghe" (applyFabAction). */
    private boolean isEpub() {
        return !TextUtils.isEmpty(resolvedLpath)
                && resolvedLpath.toLowerCase(Locale.ROOT).endsWith(".epub");
    }

    /** Doc pref_book_preview_fab_action (mac dinh "send_to_ereader" neu chua set) va cau hinh
     * text/icon/click-listener cho FAB goc duoi-phai theo 1 trong 3 hanh dong: Gui toi E-Reader /
     * Doc / Nghe -- xem javadoc day du o BookDetailActivity#applyFabAction (logic giong het,
     * chi khac dieu kien EPUB dung isEpub() thay vi hasEpub()). */
    private void applyFabAction() {
        ExtendedFloatingActionButton fab = findViewById(R.id.dbd_btn_send_to_ereader);
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
                boolean isEpub = isEpub();
                fab.setEnabled(isEpub);
                fab.setAlpha(isEpub ? 1f : 0.4f);
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

    /** The book is already on disk for a Device Library entry, so unlike BookDetailActivity's
     * version of this action, we can open TtsChapterListActivity directly -- no need to go
     * through OpenBookActivity's download step. */
    private void onReadAloudClicked() {
        if (TextUtils.isEmpty(resolvedLpath) || !resolvedLpath.toLowerCase(Locale.ROOT).endsWith(".epub")) {
            Toast.makeText(this, R.string.reader_tts_init_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        File bookFile = new File(rootDir, resolvedLpath);
        String title = ((TextView) findViewById(R.id.dbd_title)).getText().toString();
        startActivity(TtsChapterListActivity.buildIntent(this, bookFile, uuid, title));
    }

    /** Shares this single book's file via ShareUtils (same Intent.ACTION_SEND + FileProvider
     * path the Device Library list's swipe-to-share action uses -- see
     * CollectionFragment#shareBooks). Reuses rootDir/resolvedLpath computed once in
     * onCreate()/renderFileInfo() instead of resolving either again here. */
    private void onShareClicked() {
        if (TextUtils.isEmpty(resolvedLpath) || rootDir == null) {
            Toast.makeText(this, R.string.dialog_share_error, Toast.LENGTH_SHORT).show();
            return;
        }
        File bookFile = new File(rootDir, resolvedLpath);
        ShareUtils.shareFile(this, bookFile);
    }

    /** The book is already on disk for a Device Library entry -- resolve straight to the cached
     * file, no download step needed, unlike BookDetailActivity's version of this method (a
     * Calibre Library / OPDS entry may not have a local file yet). */
    private void onSendToEreaderClicked() {
        String lpath = (bookJson != null) ? bookJson.optString("lpath", null) : null;
        if (TextUtils.isEmpty(lpath)) {
            Toast.makeText(this, R.string.s2e_error_no_file, Toast.LENGTH_SHORT).show();
            return;
        }
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        File rootDir = StorageUtils.getRootDirectory(getApplicationContext(), settings);
        File bookFile = new File(rootDir, lpath);
        if (!bookFile.exists()) {
            Toast.makeText(this, R.string.s2e_error_no_file, Toast.LENGTH_SHORT).show();
            return;
        }
        String title = (bookJson != null) ? bookJson.optString("title", "book") : "book";
        String mimeType = guessMimeTypeFromExtension(extensionOf(lpath));
        // deleteFileAfterUse=false: this is the permanent on-disk library copy, not a temp
        // download -- unlike BookDetailActivity's throwaway cache-dir copy, it must survive
        // after the dialog closes.
        SendToEreaderDialogFragment.newInstance(bookFile, title + "." + extensionOf(lpath), mimeType, false)
                .show(getSupportFragmentManager(), "send_to_ereader");
    }

    /** Maps a file extension to the MIME type send2ereader's /upload multipart body should
     * declare for it -- see the (near-identical, deliberately not shared to keep each activity
     * self-contained for its own href/extension shape) BookDetailActivity#guessMimeTypeFromHref. */
    private static String guessMimeTypeFromExtension(String extension) {
        if (extension == null) return "application/octet-stream";
        switch (extension.toUpperCase(java.util.Locale.ROOT)) {
            case "EPUB": return "application/epub+zip";
            case "PDF": return "application/pdf";
            case "MOBI": return "application/x-mobipocket-ebook";
            case "AZW3": return "application/vnd.amazon.ebook";
            default: return "application/octet-stream";
        }
    }

    /** The book is already on disk (that's the definition of being in the Device Library), so
     * unlike BookDetailActivity's "Read" button, this never needs to download anything --
     * OpenBookActivity opens whatever's cached for this uuid straight away. */
    private void onReadClicked() {
        startActivity(new Intent(this, OpenBookActivity.class)
                .putExtra(OpenBookActivity.EXTRA_UUID, uuid));
    }

    private void onViewCoverClicked() {
        if (coverBytes == null) return;
        startActivity(new Intent(this, ViewCoverActivity.class)
                .putExtra(ViewCoverActivity.EXTRA_COVER_BYTES, coverBytes));
    }

    /** Local tags and mark as read/unread mirror BookDetailActivity's placeholders for the same
     * features -- see that class's onNotYetImplemented for why. */
    private void onNotYetImplemented() {
        Toast.makeText(this, R.string.bookdetail_feature_coming_soon, Toast.LENGTH_SHORT).show();
    }
}
