package net.mrphan.calibre;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.SparseArray;
import android.view.MotionEvent;
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
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import androidx.lifecycle.LifecycleOwnerKt;

/**
 * Minimal in-app CBZ (comic-book-zip) reader. A CBZ is just a .zip full of page images, so this
 * uses only java.util.zip.ZipFile (no external dependency): list the image entries, sort them
 * into reading order, and decode one page (+/- 1 neighbor) at a time into a Bitmap, same memory
 * policy as PdfReaderActivity. CBR (rar) is intentionally not supported -- see README.
 *
 * Reader chrome: implements ReaderChromeHost (net.mrphan.calibre.reader.chrome) so the shared
 * floating-toolbar overlay can drive this reader the same way it drives PdfReaderActivity /
 * EpubReaderActivity -- see PdfReaderActivity's kdoc for the reasoning, which applies unchanged
 * here (CBZ has no outline/search/font-size story either, so those actions are hidden or report
 * "not supported" the same way).
 */
public class CbzReaderActivity extends AppCompatActivity implements ReaderChromeHost {

    private static final java.util.Set<String> IMAGE_EXTENSIONS = new java.util.HashSet<>(
            java.util.Arrays.asList("jpg", "jpeg", "png", "gif", "webp", "bmp"));

    private ZipFile zipFile;
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
    private final ExecutorService decodeExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader_cbz);

        uuid = getIntent().getStringExtra(ReaderActivity.EXTRA_UUID);
        bookTitle = getIntent().getStringExtra(ReaderActivity.EXTRA_TITLE);
        if (bookTitle == null) bookTitle = "";
        String path = getIntent().getStringExtra(ReaderActivity.EXTRA_BOOK_PATH);
        mDB = new MetadataDatabaseHelper(getApplicationContext());
        readingTimeTracker = new ReadingTimeTracker(mDB, uuid, new DailyReadingStatsStore(mDB));

        viewPager = findViewById(R.id.cbz_pager);
        pageIndicator = findViewById(R.id.cbz_page_indicator);
        chromeOverlay = new ReaderChromeOverlay(getWindow().getDecorView(), this);

        List<ZipEntry> pages;
        try {
            zipFile = new ZipFile(path);
            pages = listImageEntriesInReadingOrder(zipFile);
        } catch (IOException e) {
            Logger.e(e);
            Toast.makeText(this, R.string.reader_error_open_failed, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (pages.isEmpty()) {
            Toast.makeText(this, R.string.reader_error_open_failed, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        pageCount = pages.size();
        CbzPagerAdapter adapter = new CbzPagerAdapter(zipFile, pages, decodeExecutor, mainHandler);
        viewPager.setAdapter(adapter);
        viewPager.setOffscreenPageLimit(1);

        int startPage = restoreLastPage();
        if (startPage >= 0 && startPage < pages.size()) {
            viewPager.setCurrentItem(startPage, false);
        }
        updatePageIndicator(startPage, pages.size());
        readingTimeTracker.onUnitChanged(PageReadingStatsSource.keyFor(startPage));

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updatePageIndicator(position, pages.size());
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

    /** Sorts image entries "naturally" (page2 before page10) so multi-digit page numbers read
     * in the right order instead of plain lexicographic ("page10" before "page2"). */
    private static List<ZipEntry> listImageEntriesInReadingOrder(ZipFile zip) {
        List<ZipEntry> result = new ArrayList<>();
        java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;
            String name = entry.getName().toLowerCase(java.util.Locale.ROOT);
            int dot = name.lastIndexOf('.');
            String ext = dot >= 0 ? name.substring(dot + 1) : "";
            if (IMAGE_EXTENSIONS.contains(ext)) {
                result.add(entry);
            }
        }
        Collections.sort(result, new NaturalOrderComparator());
        return result;
    }

    private static class NaturalOrderComparator implements Comparator<ZipEntry> {
        @Override
        public int compare(ZipEntry a, ZipEntry b) {
            return naturalCompare(a.getName(), b.getName());
        }
        private static int naturalCompare(String s1, String s2) {
            int i = 0, j = 0;
            while (i < s1.length() && j < s2.length()) {
                char c1 = s1.charAt(i), c2 = s2.charAt(j);
                if (Character.isDigit(c1) && Character.isDigit(c2)) {
                    int start1 = i, start2 = j;
                    while (i < s1.length() && Character.isDigit(s1.charAt(i))) i++;
                    while (j < s2.length() && Character.isDigit(s2.charAt(j))) j++;
                    String num1 = s1.substring(start1, i).replaceFirst("^0+(?!$)", "");
                    String num2 = s2.substring(start2, j).replaceFirst("^0+(?!$)", "");
                    if (num1.length() != num2.length()) return num1.length() - num2.length();
                    int cmp = num1.compareTo(num2);
                    if (cmp != 0) return cmp;
                } else {
                    if (c1 != c2) return c1 - c2;
                    i++; j++;
                }
            }
            return (s1.length() - i) - (s2.length() - j);
        }
    }

    private void updatePageIndicator(int position, int pageCount) {
        pageIndicator.setText(getString(R.string.reader_page_indicator, position + 1, pageCount));
    }

    private int restoreLastPage() {
        String json = mDB.getLastLocator(uuid);
        if (json == null) return 0;
        try {
            JSONObject o = new JSONObject(json);
            if ("cbz_page".equals(o.optString("type"))) {
                return Math.max(0, o.optInt("page", 0));
            }
        } catch (JSONException e) {
            Logger.e(e);
        }
        return 0;
    }

    private void saveLastPage(int page) {
        try {
            JSONObject o = new JSONObject().put("type", "cbz_page").put("page", page);
            mDB.saveLastLocator(uuid, o.toString());
        } catch (JSONException e) {
            Logger.e(e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (readingTimeTracker != null) readingTimeTracker.flush();
        if (viewPager.getAdapter() instanceof CbzPagerAdapter) {
            ((CbzPagerAdapter) viewPager.getAdapter()).recycleAll();
        }
        decodeExecutor.shutdownNow();
        try {
            if (zipFile != null) zipFile.close();
        } catch (IOException e) {
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
        return ReaderKind.CBZ;
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

    // Page images, no text layer -- nothing to search.
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

    // ZipFile gives us a flat list of page images, no outline -- hidden via
    // ReaderAction#isRelevantFor() anyway, this is only reached if called directly.
    @Override
    public boolean openTableOfContents() {
        return false;
    }

    @Override
    public boolean openColorAndContrastSettings() {
        return false;
    }

    // EPUB-only: xuat 1 chuong sang PDF can doc duoc HTML/reading order cua Readium, khong
    // co y nghia cho Cbz (anh, khong co "chuong" theo nghia nay).
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

    // --- Cloud progress sync (chua ho tro cho CBZ, xem prompt-nang-cap-reader-sync.md muc 9.17) ---

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
        // Khong ho tro tren CBZ.
    }

    @Override
    public boolean toggleHighlightsBrowser() {
        return false;
    }

    // TODO: no bookmarks *list* UI for CBZ yet -- that would need its own sheet that understands
    // page-number locators, unlike ReaderBookmarksSheet which is EPUB/Locator-specific. Only the
    // quick add/remove-at-current-page toggle below is wired up.
    @Override
    public boolean toggleBookmarksPanel() {
        return false;
    }

    /** See PdfReaderActivity#isCurrentLocationBookmarked() -- identical convention, same shared
     * {@code reader_annotations} table, just a CBZ page index instead of a PDF one. */
    @Override
    public boolean isCurrentLocationBookmarked() {
        return mDB.findBookmarkId(uuid, cbzPageLocatorJson(viewPager.getCurrentItem())) >= 0;
    }

    @Override
    public boolean toggleBookmarkAtCurrentLocation() {
        String locatorJson = cbzPageLocatorJson(viewPager.getCurrentItem());
        long existingId = mDB.findBookmarkId(uuid, locatorJson);
        if (existingId >= 0) {
            mDB.removeAnnotation(existingId);
        } else {
            mDB.addAnnotation(uuid, "bookmark", locatorJson, null, null, null);
        }
        return true;
    }

    private String cbzPageLocatorJson(int page) {
        return "{\"type\":\"cbz_page\",\"page\":" + page + "}";
    }

    // Page images, no reflow/scroll concept -- paging through images *is* the only navigation
    // mode this reader has.
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

    // Page images, not reflowable text -- there is no font size to change.
    @Override
    public boolean openFontSizeControls() {
        return false;
    }

    // Page images, not reflowable text -- no text layer to read aloud.
    @Override
    public boolean toggleReadAloud() {
        return false;
    }

    @Override
    public boolean isReadAloudActive() {
        return false;
    }

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
     * of calling recreate(). */
    @Override
    public boolean reloadBook() {
        if (readingTimeTracker != null) readingTimeTracker.flush();
        Intent restartIntent = new Intent(this, CbzReaderActivity.class);
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

    private static class CbzPagerAdapter extends RecyclerView.Adapter<CbzPageViewHolder> {
        private final ZipFile zipFile;
        private final List<ZipEntry> pages;
        private final ExecutorService executor;
        private final Handler mainHandler;
        private final SparseArray<Bitmap> bitmapCache = new SparseArray<>();

        CbzPagerAdapter(ZipFile zipFile, List<ZipEntry> pages, ExecutorService executor, Handler mainHandler) {
            this.zipFile = zipFile;
            this.pages = pages;
            this.executor = executor;
            this.mainHandler = mainHandler;
        }

        @Override
        public CbzPageViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            ZoomableImageView imageView = new ZoomableImageView(parent.getContext());
            imageView.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            return new CbzPageViewHolder(imageView);
        }

        @Override
        public void onBindViewHolder(CbzPageViewHolder holder, int position) {
            Bitmap cached = bitmapCache.get(position);
            if (cached != null) {
                holder.imageView.setImageBitmap(cached);
                return;
            }
            executor.execute(() -> {
                Bitmap bmp = decodePage(position);
                if (bmp == null) return;
                mainHandler.post(() -> {
                    bitmapCache.put(position, bmp);
                    holder.imageView.setImageBitmap(bmp);
                    recycleFarPages(position);
                });
            });
        }

        private Bitmap decodePage(int position) {
            try (InputStream in = zipFile.getInputStream(pages.get(position))) {
                return BitmapFactory.decodeStream(in);
            } catch (IOException e) {
                Logger.e(e);
                return null;
            }
        }

        private void recycleFarPages(int keepAround) {
            for (int i = 0; i < bitmapCache.size(); i++) {
                int key = bitmapCache.keyAt(i);
                if (Math.abs(key - keepAround) > 1) {
                    Bitmap b = bitmapCache.get(key);
                    if (b != null) b.recycle();
                    bitmapCache.remove(key);
                    i--;
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
            return pages.size();
        }
    }

    private static class CbzPageViewHolder extends RecyclerView.ViewHolder {
        final ZoomableImageView imageView;
        CbzPageViewHolder(ZoomableImageView imageView) {
            super(imageView);
            this.imageView = imageView;
        }
    }
}
