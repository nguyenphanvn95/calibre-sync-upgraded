package net.mrphan.calibre;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import net.mrphan.calibre.reader.chrome.ReaderChromeHost;
import net.mrphan.calibre.reader.chrome.ReaderChromeOverlay;
import net.mrphan.calibre.reader.chrome.ReaderKind;
import net.mrphan.calibre.reader.stats.DailyReadingStatsStore;
import net.mrphan.calibre.reader.stats.PageReadingStatsSource;
import net.mrphan.calibre.reader.stats.ReadingStatsDialog;
import net.mrphan.calibre.reader.stats.ReadingTimeTracker;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.lifecycle.LifecycleOwnerKt;

/**
 * Minimal in-app PDF reader: renders each page as a bitmap via android.graphics.pdf.PdfRenderer
 * (built into the Android SDK since API 21, no external dependency and therefore no additional
 * license to worry about -- see README/CHANGELOG "In-app reader" section for the license
 * comparison against MuPDF/PSPDFKit that ruled those out).
 *
 * PDF pages are displayed as rendered images (no reflow) -- this matches how PDFs are shown by
 * effectively every mobile PDF viewer, since the format itself doesn't reflow.
 *
 * Memory handling: only the current page +/- 1 is kept rendered as a Bitmap at any time (see
 * PdfPagerAdapter), all others are recycle()'d, so a long scanned-image-heavy PDF does not OOM
 * the app. Rendering happens on a single background thread (PdfRenderer itself is not safe for
 * concurrent access from multiple threads) and results are posted back to the main thread.
 *
 * Reader chrome: this Activity implements ReaderChromeHost (see net.mrphan.calibre.reader.chrome)
 * so the shared floating-toolbar overlay -- also used by EpubReaderActivity/CbzReaderActivity --
 * can drive this reader without any reader-specific code living in the overlay itself. TOC,
 * reference mode, highlights and font-size controls have no PdfRenderer equivalent (no outline
 * API, no reflowable text), so those ReaderAction entries are hidden for PDF via
 * ReaderAction#isRelevantFor(); everything else either has a real implementation below or reports
 * "not supported" through showMessage() rather than silently doing nothing.
 */
public class PdfReaderActivity extends AppCompatActivity implements ReaderChromeHost {

    private ParcelFileDescriptor pfd;
    private PdfRenderer renderer;
    private ViewPager2 viewPager;
    private TextView pageIndicator;
    private MetadataDatabaseHelper mDB;
    private String uuid;
    private String bookTitle;
    private int pageCount;
    private boolean fullscreenOn = false;
    /** Duong dan file anh bia doc tu EXTRA_COVER_PATH NGAY LUC onCreate() (truoc khi co reload
     * nao xoa extra do) -- dung cho ca hieu ung MO lan hieu ung DONG sach trong close(), thay vi
     * doc lai getIntent().getStringExtra() luc dong vi reloadBook() ben duoi da chu dong xoa
     * extra nay truoc khi restart Activity. Xem kdoc BookOpenCloseDoorEffect va reloadBook(). */
    private String openBookCoverPath;
    private ReaderChromeOverlay chromeOverlay;
    private ReadingTimeTracker readingTimeTracker;
    private PdfPagerAdapter pdfPagerAdapter;
    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // --- Floating "now playing" TTS panel -- keeps a background "Đọc cho nghe" session (started
    // from a different, EPUB book) visible while reading a PDF here, mirroring Readest's floating
    // audio bar. See net.mrphan.calibre.reader.tts.GlobalTtsFloatingPanel's kdoc.
    private net.mrphan.calibre.reader.tts.GlobalTtsFloatingPanel mTtsFloatingPanel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader_pdf);
        mTtsFloatingPanel = new net.mrphan.calibre.reader.tts.GlobalTtsFloatingPanel(this);

        uuid = getIntent().getStringExtra(ReaderActivity.EXTRA_UUID);
        bookTitle = getIntent().getStringExtra(ReaderActivity.EXTRA_TITLE);
        if (bookTitle == null) bookTitle = "";
        String path = getIntent().getStringExtra(ReaderActivity.EXTRA_BOOK_PATH);
        mDB = new MetadataDatabaseHelper(getApplicationContext());
        readingTimeTracker = new ReadingTimeTracker(mDB, uuid, new DailyReadingStatsStore(mDB));

        viewPager = findViewById(R.id.pdf_pager);
        pageIndicator = findViewById(R.id.pdf_page_indicator);
        chromeOverlay = new ReaderChromeOverlay(getWindow().getDecorView(), this);

        try {
            pfd = ParcelFileDescriptor.open(new File(path), ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(pfd);
        } catch (Exception e) {
            Logger.e(e);
            // FIX: trước đây chỉ hiện thông báo chung chung "Could not open this book in the
            // reader." (reader_error_open_failed) cho MỌI lỗi -- file không tồn tại, thiếu
            // quyền đọc, PDF hỏng/có mật khẩu, v.v. đều hiện y hệt nhau, nên không ai (kể cả
            // developer) biết được nguyên nhân thật sự nếu không cắm máy vào adb logcat. Giờ nối
            // thêm tên lớp exception thật (vd. "FileNotFoundException", "SecurityException") vào
            // cuối thông báo -- đủ để phân biệt "file bị xoá/đường dẫn sai" (FileNotFoundException)
            // với "PDF hỏng hoặc có mật khẩu, PdfRenderer không mở được" (thường là IOException/
            // SecurityException từ chính PdfRenderer) ngay trên máy, không cần kết nối máy tính.
            // Logger.e(e) ở trên vẫn ghi đầy đủ stack trace vào Logcat (tag "CSync") và vào
            // calibre-debug.log trong thư mục sách nếu Settings > Hệ thống > "pref_debug" đang
            // bật (xem CalibreService#onSharedPreferenceChanged), cho trường hợp cần xem chi
            // tiết hơn tên exception đơn thuần.
            String detail = e.getClass().getSimpleName();
            String message = detail.isEmpty()
                    ? getString(R.string.reader_error_open_failed)
                    : getString(R.string.reader_error_open_failed) + " (" + detail + ")";
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        pageCount = renderer.getPageCount();
        int startPage = restoreLastPage();
        if (startPage < 0 || startPage >= pageCount) startPage = 0;

        pdfPagerAdapter = new PdfPagerAdapter(renderer, renderExecutor, mainHandler, pageCount, startPage);
        viewPager.setAdapter(pdfPagerAdapter);
        viewPager.setOffscreenPageLimit(1);

        viewPager.setCurrentItem(startPage, false);
        updatePageIndicator(startPage, pageCount);
        readingTimeTracker.onUnitChanged(PageReadingStatsSource.keyFor(startPage));

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updatePageIndicator(position, pageCount);
                saveLastPage(position);
                readingTimeTracker.onUnitChanged(PageReadingStatsSource.keyFor(position));
            }
        });

        // Hieu ung "cua doi" khi mo sach -- chi phat khi Intent den tu OpenBookActivity#
        // openBook() (co EXTRA_COVER_PATH); reloadBook() ben duoi da chu dong xoa extra nay
        // truoc khi restart nen doi setting doc se KHONG lam phat lai hieu ung.
        //
        // Doc va luu vao field openBookCoverPath NGAY O DAY (truoc khi reloadBook() co co hoi xoa
        // extra) de close() ben duoi van co anh bia dung cho hieu ung dong, ke ca khi user da
        // reload sach truoc khi dong.
        openBookCoverPath = getIntent().getStringExtra(ReaderActivity.EXTRA_COVER_PATH);
        net.mrphan.calibre.reader.BookOpenCloseDoorEffect.playOpenIfEnabled(
                this, openBookCoverPath != null ? new File(openBookCoverPath) : null);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mTtsFloatingPanel != null) mTtsFloatingPanel.attach();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mTtsFloatingPanel != null) mTtsFloatingPanel.detach();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (readingTimeTracker != null) readingTimeTracker.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (readingTimeTracker != null) readingTimeTracker.onPause();
    }

    /** See ReaderChromeOverlay#dispatchContentTouch() kdoc for why this is done here (Activity
     * dispatch) rather than a touch listener on the ViewPager2 -- the event is always forwarded to
     * super afterwards unmodified, so ViewPager2's own swipe handling is unaffected. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (chromeOverlay != null && viewPager != null) {
            chromeOverlay.dispatchContentTouch(ev, viewPager.getWidth());
        }
        return super.dispatchTouchEvent(ev);
    }

    private void updatePageIndicator(int position, int pageCount) {
        pageIndicator.setText(getString(R.string.reader_page_indicator, position + 1, pageCount));
    }

    private int restoreLastPage() {
        String json = mDB.getLastLocator(uuid);
        if (json == null) return 0;
        try {
            JSONObject o = new JSONObject(json);
            if ("pdf_page".equals(o.optString("type"))) {
                return Math.max(0, o.optInt("page", 0));
            }
        } catch (JSONException e) {
            Logger.e(e);
        }
        return 0;
    }

    private void saveLastPage(int page) {
        try {
            JSONObject o = new JSONObject().put("type", "pdf_page").put("page", page);
            mDB.saveLastLocator(uuid, o.toString());
        } catch (JSONException e) {
            Logger.e(e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (readingTimeTracker != null) readingTimeTracker.flush();
        if (pdfPagerAdapter != null) {
            pdfPagerAdapter.recycleAll();
        }
        renderExecutor.shutdownNow();
        try {
            if (renderer != null) renderer.close();
            if (pfd != null) pfd.close();
        } catch (Exception e) {
            Logger.e(e);
        }
        mDB.close();
    }

    // --- ReaderChromeHost ----------------------------------------------------------------------

    @NonNull
    @Override
    public String getBookTitle() {
        return bookTitle;
    }

    @NonNull
    @Override
    public ReaderKind getReaderKind() {
        return ReaderKind.PDF;
    }

    @Override
    public int currentProgressPercent() {
        if (pageCount <= 0) return 0;
        int position = viewPager.getCurrentItem();
        return Math.min(100, Math.round((position + 1) * 100f / pageCount));
    }

    @Override
    public void close() {
        // Hieu ung dong sach chay xong roi moi finish() -- xem kdoc BookOpenCloseDoorEffect va
        // field openBookCoverPath o tren. Neu setting tat/khong co anh bia, playCloseIfEnabled()
        // goi finish() ngay lap tuc (dong bo), khong lam cham viec dong sach.
        net.mrphan.calibre.reader.BookOpenCloseDoorEffect.playCloseIfEnabled(
                this,
                openBookCoverPath != null ? new File(openBookCoverPath) : null,
                this::finish);
    }

    @Override
    public void toggleChrome() {
        chromeOverlay.toggle();
    }

    @Override
    public void showMessage(@NonNull String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @NonNull
    @Override
    public String formatString(int resId, @NonNull Object... formatArgs) {
        return getString(resId, formatArgs);
    }

    @NonNull
    @Override
    public androidx.fragment.app.FragmentManager fragmentManager() {
        return getSupportFragmentManager();
    }

    @Override
    public boolean goBack() {
        int current = viewPager.getCurrentItem();
        if (current <= 0) return false;
        viewPager.setCurrentItem(current - 1, true);
        return true;
    }

    @Override
    public boolean goForward() {
        int current = viewPager.getCurrentItem();
        if (current >= pageCount - 1) return false;
        viewPager.setCurrentItem(current + 1, true);
        return true;
    }

    // No text layer to search -- PdfRenderer only rasterizes pages.
    @Override
    public boolean openSearch() {
        return false;
    }

    @Override
    public boolean openGoToLocation() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(this)
                .setTitle(R.string.reader_action_goto)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    try {
                        int page = Integer.parseInt(input.getText().toString().trim()) - 1;
                        if (page >= 0 && page < pageCount) {
                            viewPager.setCurrentItem(page, true);
                        } else {
                            showMessage(getString(R.string.reader_error_open_failed));
                        }
                    } catch (NumberFormatException ignored) {
                        // Empty/non-numeric input -- just do nothing, same as cancelling.
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        return true;
    }

    // PdfRenderer has no outline/bookmark-tree API -- hidden via ReaderAction#isRelevantFor()
    // anyway, this is only reached if something calls it directly.
    @Override
    public boolean openTableOfContents() {
        return false;
    }

    @Override
    public boolean openColorAndContrastSettings() {
        return false;
    }

    // EPUB-only: xuat 1 chuong sang PDF can doc duoc HTML/reading order cua Readium, khong
    // co y nghia cho Pdf (da la file PDF san).
    @Override
    public boolean printCurrentChapterAsPdf() {
        return false;
    }

    @Override
    public int currentScreenBrightness() {
        return -1;
    }

    @Override
    public boolean setScreenBrightness(int value) {
        return false;
    }

    @Override
    public String currentColorThemeId() {
        return null;
    }

    @Override
    public boolean setColorTheme(String themeId) {
        return false;
    }

    @Override
    public java.util.List<Float> chapterTickFractions() {
        return java.util.Collections.emptyList();
    }

    @Override
    public float currentProgressFraction() {
        return currentProgressPercent() / 100f;
    }

    @Override
    public boolean jumpToProgressFraction(float fraction) {
        return false;
    }

    @Override
    public boolean goToPreviousChapter() {
        return false;
    }

    @Override
    public boolean goToNextChapter() {
        return false;
    }

    @Override
    public String currentChapterTitle() {
        return "";
    }

    // --- Cloud progress sync (chua ho tro cho PDF, xem prompt-nang-cap-reader-sync.md muc 9.17) ---

    @Override
    public boolean isProgressSyncAvailable() {
        return false;
    }

    @Override
    public Long lastProgressSyncedAt() {
        return null;
    }

    @Override
    public boolean isProgressSyncInProgress() {
        return false;
    }

    @Override
    public boolean syncProgressNow(kotlin.jvm.functions.Function1<? super Boolean, kotlin.Unit> onDone) {
        return false;
    }

    @Override
    public boolean openBookSyncInfo() {
        return false;
    }

    @Override
    public int autoSyncIntervalMinutes() {
        return 0;
    }

    @Override
    public void setAutoSyncIntervalMinutes(int minutes) {
        // Khong ho tro tren PDF.
    }

    // TODO: MetadataDatabaseHelper only stores a single "last locator" per book today, not a list
    // of named bookmarks -- a real implementation needs a bookmarks table first.
    @Override
    public boolean toggleHighlightsBrowser() {
        return false;
    }

    // TODO: no bookmarks *list* UI for PDF yet -- that would need its own sheet that understands
    // page-number locators, unlike ReaderBookmarksSheet which is EPUB/Locator-specific. Only the
    // quick add/remove-at-current-page toggle below is wired up.
    @Override
    public boolean toggleBookmarksPanel() {
        return false;
    }

    /**
     * Bookmarks a page using the same {@code reader_annotations} table EpubReaderActivity uses
     * (see MetadataDatabaseHelper#addAnnotation), storing a small {"type":"pdf_page","page":N}
     * locator JSON instead of a Readium Locator -- same convention MetadataDatabaseHelper's own
     * last_locator column already uses for PDF/CBZ (see its DATABASE_VERSION 4 migration
     * comment).
     */
    @Override
    public boolean isCurrentLocationBookmarked() {
        return mDB.findBookmarkId(uuid, pdfPageLocatorJson(viewPager.getCurrentItem())) >= 0;
    }

    @Override
    public boolean toggleBookmarkAtCurrentLocation() {
        String locatorJson = pdfPageLocatorJson(viewPager.getCurrentItem());
        long existingId = mDB.findBookmarkId(uuid, locatorJson);
        if (existingId >= 0) {
            mDB.removeAnnotation(existingId);
        } else {
            mDB.addAnnotation(uuid, "bookmark", locatorJson, null, null, null);
        }
        return true;
    }

    private String pdfPageLocatorJson(int page) {
        return "{\"type\":\"pdf_page\",\"page\":" + page + "}";
    }

    // Rendered page bitmaps have no reflow/scroll concept the way an EPUB's HTML does -- paging
    // through pre-rendered bitmaps *is* this reader's only navigation mode.
    @Override
    public boolean isScrollModeEnabled() {
        return false;
    }

    @Override
    public boolean setScrollMode(boolean enabled) {
        return false;
    }

    @Override
    public boolean isAutoScrollEnabled() {
        return false;
    }

    @Override
    public boolean toggleAutoScroll() {
        return false;
    }

    @Override
    public boolean openAutoScrollSpeedControls() {
        return false;
    }

    // No Readium EpubPreferences-style theme here; PDF pages are rendered bitmaps of the
    // original page, so there's no separate content/background color to recolor independently
    // of the page image itself.
    @Override
    public net.mrphan.calibre.reader.chrome.ReaderThemeMode currentThemeMode() {
        return net.mrphan.calibre.reader.chrome.ReaderThemeMode.LIGHT;
    }

    @Override
    public boolean setThemeMode(net.mrphan.calibre.reader.chrome.ReaderThemeMode mode) {
        return false;
    }

    @Override
    public boolean isImmersiveModeEnabled() {
        return fullscreenOn;
    }

    @Override
    public boolean openReadingStats() {
        PageReadingStatsSource source = new PageReadingStatsSource(
                getString(R.string.reader_stats_unit_page),
                readingTimeTracker,
                pageCount,
                viewPager.getCurrentItem());
        ReadingStatsDialog.INSTANCE.show(this, LifecycleOwnerKt.getLifecycleScope(this), source);
        return true;
    }

    @Override
    public boolean openReaderBackgroundMusic() {
        return false;
    }

    // Rendered page bitmaps, not reflowable text -- there is no font size to change.
    @Override
    public boolean openFontSizeControls() {
        return false;
    }

    // Rendered page bitmaps, not reflowable text -- no text layer to read aloud.
    @Override
    public boolean toggleReadAloud() {
        return false;
    }

    @Override
    public boolean isReadAloudActive() {
        return false;
    }

    // Chua nam trong pham vi prompt-nang-cap-phim-am-luong-vung-cham-lat-trang.md -- PDF paging
    // qua bitmap render san, khong dung ReaderChromeOverlay's edge-tap logic cho lat trang.
    @Override
    public boolean isEdgeTapPageTurnEnabled() {
        return false;
    }

    @Override
    public boolean isEdgeTapPageTurnInverted() {
        return false;
    }

    @Override
    public boolean openPreferences() {
        return false;
    }

    @Override
    public boolean openReaderSettingsProfiles() {
        return false;
    }

    @Override
    public boolean toggleFullscreen() {
        WindowInsetsControllerCompat controller =
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        fullscreenOn = !fullscreenOn;
        if (fullscreenOn) {
            controller.hide(WindowInsetsCompat.Type.systemBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars());
        }
        return true;
    }

    @Override
    public boolean openInspector() {
        return false;
    }

    @Override
    public boolean resetInterfaceDefaults() {
        chromeOverlay.refreshActions();
        return true;
    }

    /** See EpubReaderActivity#reloadBook() kdoc for why this restarts with a fresh Intent instead
     * of calling recreate() -- the same "savedInstanceState != null takes a different, less
     * reliable restore path" reasoning applies here too. */
    @Override
    public boolean reloadBook() {
        if (readingTimeTracker != null) readingTimeTracker.flush();
        Intent restartIntent = new Intent(this, PdfReaderActivity.class);
        restartIntent.putExtras(getIntent());
        restartIntent.removeExtra(ReaderActivity.EXTRA_COVER_PATH);
        restartIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(restartIntent);
        finish();
        overridePendingTransition(0, 0);
        return true;
    }

    @Override
    public boolean openBookPicker() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
        return true;
    }

    @Override
    public boolean openMetadataEditor() {
        if (uuid == null) return false;
        startActivity(new Intent(this, MetadataEditorActivity.class)
                .putExtra(MetadataEditorActivity.EXTRA_UUID, uuid));
        return true;
    }

    /**
     * Renders one page per position, lazily, and caches decoded Bitmaps by position so paging
     * back to an already-rendered page doesn't re-render it.
     *
     * Bitmap recycling is tied to RecyclerView's own {@link #onViewRecycled} callback rather than
     * any manual "position is more than N away from the current page" distance check. An earlier
     * version tried to evict by distance, but that recycle() could fire while the ViewPager2
     * swipe *transition* still had the outgoing page's ImageView attached and mid-draw -- the
     * distance check has no way to know that, only RecyclerView itself does. Calling recycle()
     * from anything other than onViewRecycled is exactly what caused the repeated "Canvas: trying
     * to use a recycled bitmap" crashes: onViewRecycled is the one point Android guarantees a
     * ViewHolder's view is no longer being drawn anywhere, so it's the only safe place to free the
     * Bitmap it was showing.
     */
    private static class PdfPagerAdapter extends RecyclerView.Adapter<PdfPageViewHolder> {
        private final PdfRenderer renderer;
        private final ExecutorService executor;
        private final Handler mainHandler;
        private final int pageCount;
        private final android.util.SparseArray<Bitmap> bitmapCache = new android.util.SparseArray<>();

        PdfPagerAdapter(PdfRenderer renderer, ExecutorService executor, Handler mainHandler, int pageCount,
                         int startPosition) {
            this.renderer = renderer;
            this.executor = executor;
            this.mainHandler = mainHandler;
            this.pageCount = pageCount;
        }

        @Override
        public PdfPageViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            ZoomableImageView imageView = new ZoomableImageView(parent.getContext());
            imageView.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new PdfPageViewHolder(imageView);
        }

        @Override
        public void onBindViewHolder(PdfPageViewHolder holder, int position) {
            holder.boundPosition = position;
            Bitmap cached = bitmapCache.get(position);
            if (cached != null) {
                holder.imageView.setImageBitmap(cached);
                return;
            }
            executor.execute(() -> {
                Bitmap bmp = renderPage(position);
                if (bmp == null) return;
                mainHandler.post(() -> {
                    // Stale-callback guard: by the time this background render finishes,
                    // RecyclerView may already have recycled/rebound this exact ViewHolder
                    // instance to a different position (fast swiping). If so, this bitmap was
                    // rendered for a page nobody is asking for anymore -- drop it immediately
                    // instead of caching an orphaned Bitmap that would never get recycled.
                    if (holder.getBindingAdapterPosition() != position || holder.boundPosition != position) {
                        bmp.recycle();
                        return;
                    }
                    bitmapCache.put(position, bmp);
                    holder.imageView.setImageBitmap(bmp);
                });
            });
        }

        @Override
        public void onViewRecycled(PdfPageViewHolder holder) {
            int position = holder.boundPosition;
            holder.imageView.setImageBitmap(null);
            holder.boundPosition = RecyclerView.NO_POSITION;
            if (position == RecyclerView.NO_POSITION) return;
            Bitmap b = bitmapCache.get(position);
            if (b != null) {
                bitmapCache.remove(position);
                b.recycle();
            }
        }

        /** Synchronized: android.graphics.pdf.PdfRenderer explicitly documents that only one
         * page can be open at a time and it must not be accessed from multiple threads at once. */
        private synchronized Bitmap renderPage(int position) {
            PdfRenderer.Page page = null;
            try {
                page = renderer.openPage(position);
                Bitmap bitmap = Bitmap.createBitmap(page.getWidth() * 2, page.getHeight() * 2, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(0xFFFFFFFF);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bitmap;
            } catch (Exception e) {
                Logger.e(e);
                return null;
            } finally {
                // Must close even on failure: PdfRenderer allows only one open Page at a time, so
                // a page leaked here on an exception path would make every subsequent openPage()
                // call throw IllegalStateException and silently blank out the rest of the book.
                if (page != null) {
                    try {
                        page.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        void recycleAll() {
            for (int i = 0; i < bitmapCache.size(); i++) {
                Bitmap b = bitmapCache.valueAt(i);
                if (b != null) b.recycle();
            }
            bitmapCache.clear();
        }

        @Override
        public int getItemCount() {
            return pageCount;
        }
    }

    private static class PdfPageViewHolder extends RecyclerView.ViewHolder {
        final ZoomableImageView imageView;
        int boundPosition = RecyclerView.NO_POSITION;
        PdfPageViewHolder(ZoomableImageView imageView) {
            super(imageView);
            this.imageView = imageView;
        }
    }
}
