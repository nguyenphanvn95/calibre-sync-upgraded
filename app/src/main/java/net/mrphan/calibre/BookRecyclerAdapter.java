package net.mrphan.calibre;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * RecyclerView replacement for BookCursorAdapter, used ONLY by the Device Library tab
 * (CollectionFragment). BookCursorAdapter itself is left untouched, since BookSearchActivity
 * and OPDSBrowserFragment (Calibre Library tab) still use it on top of GridView/ListView.
 * <p>
 * Reads from the same Cursor shape as BookCursorAdapter (Book.COLUMNS: _id, uuid, lpath,
 * author, title, thumbnail, cover_path), just via RecyclerView.Adapter#onBindViewHolder instead
 * of CursorAdapter#bindView. Supports four row layouts (see ViewMode) and an optional
 * multi-select overlay; the two list-shaped layouts (LIST, SIMPLE_LIST) also support
 * swipe-to-reveal Sync/Share/Delete (swipe left) and Listen/Read (swipe right), driven
 * externally by SwipeRevealCallback via {@link #getOpenPosition()} / {@link #getOpenDirection()}
 * / {@link #setOpenPosition(int, int)}.
 */
public class BookRecyclerAdapter extends RecyclerView.Adapter<BookRecyclerAdapter.BookViewHolder> {

    /** Mirrors CollectionFragment.ViewMode; kept as a separate lightweight enum here so this
     * adapter doesn't need to import the fragment class. */
    public enum ViewMode {
        LIST, SIMPLE_LIST, GRID, CAROUSEL
    }

    public interface Listener {
        /** Row tapped while NOT in selection mode: open the book. */
        void onBookOpen(String uuid);
        /** Row long-pressed: enter selection mode (if not already active) and toggle this id. */
        void onBookLongPress(int id);
        /** Row tapped WHILE in selection mode: toggle this id instead of opening the book. */
        void onBookToggleSelect(int id);
        /** Share button (swipe action or, if ever wired elsewhere, direct tap) for a single book. */
        void onShareRequested(int id);
        /** Delete button (swipe action) for a single book, with its own confirmation. */
        void onDeleteRequested(int id);
        /** Sync button (swipe action) for a single book -- enqueues a Google Drive upload via
         * DeviceLibrarySyncScheduler, see CollectionFragment#syncBooksToCloud. */
        void onSyncRequested(int id);
        /** Listen button (swipe action) for a single book: opens the TTS chapter list. Only
         * meaningful for EPUB books; CollectionFragment shows a Toast for anything else. */
        void onListenRequested(int id);
        /** Read button (swipe action) for a single book: opens it in the reader
         * (OpenBookActivity), same destination as DeviceBookDetailActivity's own Read button. */
        void onReadRequested(int id);
    }

    private final Context mContext;
    private Cursor mCursor;
    private ViewMode mViewMode = ViewMode.LIST;
    private final Listener mListener;

    /** Non-null and non-empty only while multi-select is active; checked ids are drawn with
     * the overlay checkbox ticked. Owned by CollectionFragment -- this adapter only reads it. */
    private Set<Integer> mSelectedIds;
    private boolean mSelectionMode = false;

    /** Which side is currently revealed for the swiped-open row (mOpenPosition), see
     * setOpenPosition(). OPEN_DIRECTION_END = Sync/Share/Delete on the right (swipe LEFT).
     * OPEN_DIRECTION_START = Listen/Read on the left (swipe RIGHT). */
    public static final int OPEN_DIRECTION_NONE = 0;
    public static final int OPEN_DIRECTION_END = 1;
    public static final int OPEN_DIRECTION_START = 2;

    /** Adapter position of the currently swiped-open row (LIST/SIMPLE_LIST only), or
     * RecyclerView.NO_POSITION if none is open. Only one row can be open at a time. */
    private int mOpenPosition = RecyclerView.NO_POSITION;
    /** Which side mOpenPosition is open toward; meaningless while mOpenPosition is NO_POSITION. */
    private int mOpenDirection = OPEN_DIRECTION_NONE;

    /** uuid -> reading progress %/comments, batch-loaded once by CollectionFragment (see
     * MetadataDatabaseHelper#getAllBookExtras). Null is fine -- treated as "no data", hides
     * progress/comment rows entirely. */
    private Map<String, MetadataDatabaseHelper.BookExtras> mExtrasByUuid;

    /** Settings > Giao dien: "Show title and progress in grid view" -- only takes effect while
     * mViewMode == ViewMode.GRID (see onBindViewHolder below); List/Simple list/Carousel always
     * show the title regardless. Pushed in by CollectionFragment (see refreshGridDisplayOptions
     * there), not read directly here since this adapter has no Context-independent access to
     * SharedPreferences that would survive being unit-testable, and to mirror mViewMode's own
     * push-based convention immediately above. */
    private boolean mShowGridTitleProgress = true;

    public void setShowGridTitleProgress(boolean show) {
        if (mShowGridTitleProgress == show) return;
        mShowGridTitleProgress = show;
        notifyDataSetChanged();
    }

    public BookRecyclerAdapter(Context context, Cursor cursor, Listener listener) {
        mContext = context;
        mCursor = cursor;
        mListener = listener;
        setHasStableIds(true);
    }

    public void setViewMode(ViewMode mode) {
        if (mViewMode == mode) return;
        mViewMode = mode;
        mOpenPosition = RecyclerView.NO_POSITION;
        notifyDataSetChanged();
    }

    public ViewMode getViewMode() {
        return mViewMode;
    }

    private GridDisplayStyle mGridStyle = GridDisplayStyle.DEFAULT;

    public void setGridStyle(GridDisplayStyle style) {
        if (style == null || mGridStyle == style) return;
        mGridStyle = style;
        mOpenPosition = RecyclerView.NO_POSITION;
        notifyDataSetChanged();
    }

    public GridDisplayStyle getGridStyle() {
        return mGridStyle;
    }

    /** So khac biet (XOR) toi da giua 2 lan setSelectionState() ma van con dang gia tri de tim
     * TUNG vi tri thay doi qua mCursor (xem findPositionForId()) thay vi notifyDataSetChanged()
     * toan bo -- vuot qua nguong nay (vd. bam "Chon tat ca" tren thu vien lon) thi quet ca cursor
     * nhieu lan (O(diff * n)) se cham hon 1 lan notifyDataSetChanged() don gian, nen fallback
     * ve full refresh cho an toan. */
    private static final int SELECTION_DIFF_FULL_REFRESH_THRESHOLD = 20;

    public void setSelectionState(boolean selectionMode, Set<Integer> selectedIds) {
        boolean modeChanged = (mSelectionMode != selectionMode);
        Set<Integer> oldIds = mSelectedIds;
        mSelectionMode = selectionMode;
        // BUG FIX (checkbox khong hien tick tu cuon thu 2 tro di): CollectionFragment giu 1
        // Set<Integer> mSelectedIds DUY NHAT (final), moi lan chon/bo chon no sua TRUC TIEP
        // (add/remove) vao chinh set do roi truyen NGUYEN tham chieu xuong day. Neu adapter chi
        // luu lai tham chieu (mSelectedIds = selectedIds), thi sau lan bind dau tien, oldIds va
        // selectedIds ben duoi se la CUNG MOT OBJECT da bi mutate truoc khi ham nay duoc goi ->
        // symmetricDifference() luon ra rong -> notifyItemChanged() khong bao gio chay -> checkbox
        // dung nguyen trang thai cu tren man hinh (thuong la chua tick) du du lieu chon (dung de
        // Delete/Sync/Share) van dung. Copy sang mot Set moi doc lap de lan so sanh SAU van con
        // "anh chup" dung cua trang thai TRUOC do.
        mSelectedIds = (selectedIds != null) ? new java.util.HashSet<>(selectedIds) : null;

        if (modeChanged) {
            // Vao/thoat che do chon: checkbox chuyen VISIBLE<->GONE tren MOI dong dang hien --
            // day la thay doi that su anh huong toan bo danh sach, notifyDataSetChanged() o day
            // la dung, khong phai thua.
            notifyDataSetChanged();
            return;
        }

        // BUG FIX (UX/Hieu nang): truoc day moi lan bam CHECK/UNCHECK 1 cuon sach duy nhat trong
        // luc dang o che do chon (CollectionFragment#toggleSelection -> updateSelectionUI) se
        // goi setSelectionState() va rebind LAI TOAN BO cac dong dang hien tren man hinh chi de
        // doi trang thai checkbox cua 1 dong -- gay giat/nhap nhay khi bam chon nhieu cuon lien
        // tiep tren danh sach dai. Ma khac biet giua 2 tap hop id (thuong chi 1 phan tu moi lan
        // bam) va chi notifyItemChanged() dung nhung dong THUC SU doi trang thai checked.
        Set<Integer> changedIds = symmetricDifference(oldIds, selectedIds);
        if (changedIds.size() > SELECTION_DIFF_FULL_REFRESH_THRESHOLD) {
            notifyDataSetChanged();
            return;
        }
        for (int id : changedIds) {
            int position = findPositionForBookId(id);
            if (position != RecyclerView.NO_POSITION) notifyItemChanged(position);
        }
    }

    private static Set<Integer> symmetricDifference(Set<Integer> a, Set<Integer> b) {
        Set<Integer> result = new java.util.HashSet<>();
        if (a != null) {
            for (Integer x : a) if (b == null || !b.contains(x)) result.add(x);
        }
        if (b != null) {
            for (Integer x : b) if (a == null || !a.contains(x)) result.add(x);
        }
        return result;
    }

    /** Quet mCursor de tim vi tri (adapter position) cua 1 book id -- dung cho ca
     * setSelectionState() (id) va setExtras() (uuid, xem overload ben duoi). O(n) moi lan goi,
     * nhung chi goi voi so luong nho (xem SELECTION_DIFF_FULL_REFRESH_THRESHOLD), an toan hon
     * nhieu so voi rebind toan bo danh sach dang hien. */
    private int findPositionForBookId(int id) {
        if (mCursor == null || mCursor.isClosed()) return RecyclerView.NO_POSITION;
        int savedPos = mCursor.getPosition();
        try {
            if (mCursor.moveToFirst()) {
                do {
                    if (mCursor.getInt(Book.ID) == id) return mCursor.getPosition();
                } while (mCursor.moveToNext());
            }
        } finally {
            mCursor.moveToPosition(savedPos);
        }
        return RecyclerView.NO_POSITION;
    }

    public boolean isSwipeEnabled() {
        return mViewMode == ViewMode.LIST || mViewMode == ViewMode.SIMPLE_LIST;
    }

    public int getOpenPosition() {
        return mOpenPosition;
    }

    public int getOpenDirection() {
        return mOpenDirection;
    }

    /** Closes whichever row is currently swiped open, if any (e.g. on scroll, or before
     * opening a different row). Safe to call when nothing is open. */
    public void closeOpenRow() {
        if (mOpenPosition == RecyclerView.NO_POSITION) return;
        int prev = mOpenPosition;
        mOpenPosition = RecyclerView.NO_POSITION;
        mOpenDirection = OPEN_DIRECTION_NONE;
        notifyItemChanged(prev);
    }

    public void setOpenPosition(int position, int direction) {
        int prev = mOpenPosition;
        mOpenPosition = position;
        mOpenDirection = direction;
        if (prev != RecyclerView.NO_POSITION && prev != position) notifyItemChanged(prev);
    }

    /** Swaps in a fresh Cursor after the underlying query changes (tag filter, search, or a
     * plain requery after an edit) -- mirrors CursorAdapter#swapCursor/changeCursor, since this
     * adapter is not Cursor-backed via CursorAdapter anymore. Closes the old cursor exactly
     * like CursorAdapter did, so callers don't need to manage that themselves. */
    public void swapCursor(Cursor newCursor) {
        if (mCursor == newCursor) return;
        Cursor old = mCursor;
        // DiffUtil o day so sanh 2 Cursor THUC SU KHAC NHAU (old con giu nguyen du lieu cua no,
        // newCursor la mot query moi) -- khac voi truong hop requery() in-place, xem
        // captureSnapshot()/applyCursorDiff() ben duoi cho truong hop do.
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new BookDiffCallback(
                rowsFromCursor(old), newCursor));
        mCursor = newCursor;
        mOpenPosition = RecyclerView.NO_POSITION;
        diffResult.dispatchUpdatesTo(this);
        // Giu nguyen hanh vi dong cursor cu nhu truoc day.
        if (old != null && !old.isClosed()) old.close();
    }

    public Cursor getCursor() {
        return mCursor;
    }

    /** Snapshot nhe cua 1 row, dung de doi chieu DiffUtil khi khong the giu 2 Cursor rieng biet
     * (xem captureSnapshot()/applyCursorDiff()). Chi luu cac truong thuc su anh huong hien thi
     * (title/author/cover_path) -- giong danh sach cot BookDiffCallback.areContentsTheSame() da
     * so sanh truoc day. */
    static final class RowSnapshot {
        final String uuid;
        final String title;
        final String author;
        final String coverPath;

        RowSnapshot(String uuid, String title, String author, String coverPath) {
            this.uuid = uuid;
            this.title = title;
            this.author = author;
            this.coverPath = coverPath;
        }
    }

    private static List<RowSnapshot> rowsFromCursor(Cursor c) {
        List<RowSnapshot> rows = new ArrayList<>();
        if (c == null || c.isClosed()) return rows;
        int savedPos = c.getPosition();
        try {
            if (c.moveToFirst()) {
                do {
                    rows.add(new RowSnapshot(
                            fieldAt(c, c.getPosition(), Book.UUID),
                            fieldAt(c, c.getPosition(), Book.TITLE),
                            fieldAt(c, c.getPosition(), Book.AUTHOR),
                            fieldAt(c, c.getPosition(), Book.COVER_PATH)));
                } while (c.moveToNext());
            }
        } finally {
            c.moveToPosition(savedPos);
        }
        return rows;
    }

    /** Chup snapshot cua cursor HIEN TAI (truoc khi requery() -- xem CollectionFragment#
     * refreshBookList) de lam "old" cho DiffUtil sau nay. Phai chup TRUOC khi requery() vi
     * Cursor.requery() mutate CUNG MOT object Cursor tai cho -- sau khi requery() xong khong con
     * cach nao doc lai du lieu CU tu chinh cursor do nua. */
    List<RowSnapshot> captureSnapshot() {
        return rowsFromCursor(mCursor);
    }

    /** Goi sau khi cursor (cung object, xem captureSnapshot()) da duoc requery() xong -- so sanh
     * oldSnapshot (chup truoc do) voi noi dung MOI cua mCursor, chi rebind dung nhung row thuc su
     * thay doi thay vi notifyDataSetChanged() rebind toan bo (xem upgrade_1.md muc 1.3 va 4.1:
     * truoc day sau moi lan sync/doi filter, toan bo cover nhay lai cung luc va vi tri scroll co
     * the bi "nhay"). Phai goi tren main thread (dispatchUpdatesTo goi cac notifyItem*() cua
     * RecyclerView.Adapter), va PHAI goi SAU KHI requery() da chay xong tren CHINH main thread do
     * -- xem CollectionFragment#refreshBookList (BUG FIX: requery() tung chay tren thread nen
     * trong khi RecyclerView doc CUNG cursor nay tren main thread, gay crash "Inconsistency
     * detected" khi xoa sach vi Cursor khong thread-safe). */
    void applyCursorDiff(List<RowSnapshot> oldSnapshot) {
        if (mCursor == null || mCursor.isClosed()) {
            notifyDataSetChanged();
            return;
        }
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(
                new BookDiffCallback(oldSnapshot, mCursor));
        // Dong bat ky dong nao dang swipe-mo -- giong swapCursor(): sau khi vi tri cac dong xao
        // tron/xoa bot qua diff, mOpenPosition cu co the tro sang dong KHAC (hoac dong da bi xoa
        // mat), khong con dung dong nguoi dung vua vuot nua.
        mOpenPosition = RecyclerView.NO_POSITION;
        mOpenDirection = OPEN_DIRECTION_NONE;
        diffResult.dispatchUpdatesTo(this);
    }

    /** Goi tu CollectionFragment sau moi lan reload du lieu (giong swapCursor) -- xem
     * MetadataDatabaseHelper#getAllBookExtras(), 1 truy van duy nhat cho ca danh sach.
     * <p>
     * BUG FIX (Hieu nang): truoc day goi notifyDataSetChanged() vo dieu kien moi lan reload
     * (rescan/sync/sua metadata), du thuc te thuong chi vai cuon thay doi progress%/comment giua
     * 2 lan reload -- gay ca danh sach dang hien nhap nhay lai. Diff tung uuid giua map CU va MOI,
     * chi notifyItemChanged() dung nhung dong thuc su doi. */
    public void setExtras(Map<String, MetadataDatabaseHelper.BookExtras> extras) {
        Map<String, MetadataDatabaseHelper.BookExtras> oldExtras = mExtrasByUuid;
        mExtrasByUuid = extras;

        if (mCursor == null || mCursor.isClosed()) {
            notifyDataSetChanged();
            return;
        }

        int savedPos = mCursor.getPosition();
        try {
            if (mCursor.moveToFirst()) {
                do {
                    String uuid = mCursor.getString(Book.UUID);
                    MetadataDatabaseHelper.BookExtras before =
                            (oldExtras != null) ? oldExtras.get(uuid) : null;
                    MetadataDatabaseHelper.BookExtras after =
                            (extras != null) ? extras.get(uuid) : null;
                    if (!bookExtrasEqual(before, after)) {
                        notifyItemChanged(mCursor.getPosition());
                    }
                } while (mCursor.moveToNext());
            }
        } finally {
            mCursor.moveToPosition(savedPos);
        }
    }

    private static boolean bookExtrasEqual(
            MetadataDatabaseHelper.BookExtras a, MetadataDatabaseHelper.BookExtras b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return Objects.equals(a.progressPercent, b.progressPercent)
                && Objects.equals(a.comments, b.comments);
    }

    private static final int TYPE_LIST = 0;
    private static final int TYPE_SIMPLE = 1;
    private static final int TYPE_GRID_DEFAULT = 2;
    private static final int TYPE_CAROUSEL = 3;
    private static final int TYPE_GRID_BOOKSHELF = 4;
    private static final int TYPE_GRID_SIMPLE2 = 5;

    @Override
    public int getItemViewType(int position) {
        switch (mViewMode) {
            case SIMPLE_LIST:
                return TYPE_SIMPLE;
            case CAROUSEL:
                return TYPE_CAROUSEL;
            case GRID:
                switch (mGridStyle) {
                    case BOOKSHELF: return TYPE_GRID_BOOKSHELF;
                    case SIMPLE2: return TYPE_GRID_SIMPLE2;
                    default: return TYPE_GRID_DEFAULT;
                }
            default:
                return TYPE_LIST;
        }
    }

    @Override
    public long getItemId(int position) {
        if (mCursor == null || !mCursor.moveToPosition(position)) return RecyclerView.NO_ID;
        return mCursor.getLong(Book.ID);
    }

    @NonNull
    @Override
    public BookViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(mContext);
        int layoutRes;
        switch (viewType) {
            case TYPE_SIMPLE: layoutRes = R.layout.item_book_simple_swipe; break;
            case TYPE_GRID_DEFAULT: layoutRes = R.layout.item_book_grid_select; break;
            case TYPE_GRID_BOOKSHELF: layoutRes = R.layout.item_book_grid_select_bookshelf; break;
            case TYPE_GRID_SIMPLE2: layoutRes = R.layout.item_book_grid_select_simple2; break;
            case TYPE_CAROUSEL: layoutRes = R.layout.item_book_carousel; break;
            default: layoutRes = R.layout.item_book_swipe; break;
        }
        View v = inflater.inflate(layoutRes, parent, false);
        return new BookViewHolder(v, viewType);
    }

    /** Cover thumbnails are decoded downsampled to roughly this box (see
     * CoverManager#decodeSampledBitmapFromFile) instead of at full resolution -- row/grid/
     * carousel cells are always much smaller than a typical cover image, and decoding full size
     * for every bound row would stutter scrolling on long lists. */
    private static final int THUMB_MAX_WIDTH = 300;
    private static final int THUMB_MAX_HEIGHT = 450;

    @Override
    public void onBindViewHolder(@NonNull BookViewHolder holder, int position) {
        if (mCursor == null || !mCursor.moveToPosition(position)) return;

        bindCoverThumbnail(holder.thumbnail, mContext, mCursor);

        holder.title.setText(mCursor.getString(Book.TITLE));
        // CAROUSEL always hides its own per-card title/author -- the centered book's title/
        // author is shown once, below the whole carousel, by CollectionFragment's
        // col_carousel_title/col_carousel_author (see item_book_carousel.xml's class comment).
        // Style Bookshelf khong bao gio hien tua/tien trinh, bat ke pref_grid_show_title_progress
        // (giong tham chieu Readest, xem GridDisplayStyle). Style Simple 2 thi nguoc lai: LUON
        // hien tua/tien trinh, bat ke pref_grid_show_title_progress -- nguoi dung khong duoc tu
        // tat o style nay (xem SettingsFragment#applyGridShowTitleProgressLockState, khoa +
        // ep checkbox bat san khi chon Simple 2).
        boolean hideGridTitle = mViewMode == ViewMode.GRID
                && mGridStyle != GridDisplayStyle.SIMPLE2
                && (!mShowGridTitleProgress || mGridStyle == GridDisplayStyle.BOOKSHELF);
        holder.title.setVisibility((hideGridTitle || mViewMode == ViewMode.CAROUSEL)
                ? View.GONE : View.VISIBLE);
        // GRID layout no longer has bi_author (dropped to match Readest -- see
        // item_book_grid_select.xml); findViewById returns null there, so guard before setText.
        if (holder.author != null) {
            holder.author.setText(mCursor.getString(Book.AUTHOR));
            holder.author.setVisibility(mViewMode == ViewMode.CAROUSEL ? View.GONE : View.VISIBLE);
        }
        if (holder.uuid != null) holder.uuid.setText(mCursor.getString(Book.UUID));

        // Badge dinh dang (EPUB/PDF/CBZ...), chi ton tai trong layout style Simple 2 -- null o cac
        // style khac, findViewById tra null nen phai check truoc.
        if (holder.formatBadge != null) {
            String label = BookFormatUtils.formatLabelFromPath(mCursor.getString(Book.LPATH));
            if (label != null) {
                holder.formatBadge.setText(label);
                holder.formatBadge.setVisibility(View.VISIBLE);
            } else {
                holder.formatBadge.setVisibility(View.GONE);
            }
        }

        final int id = mCursor.getInt(Book.ID);
        final String uuid = mCursor.getString(Book.UUID);

        MetadataDatabaseHelper.BookExtras extras =
                (mExtrasByUuid != null) ? mExtrasByUuid.get(uuid) : null;

        // Comment, chi LIST (item_book_swipe.xml) co id bi_comment; grid/simple/carousel
        // khong co, findViewById tra null.
        if (holder.comment != null) {
            String rawComments = (extras != null) ? extras.comments : null;
            // extras.comments lay tu MetadataDatabaseHelper, giu nguyen HTML goc cua Calibre, phai
            // strip truoc khi hien thi tren dong mo ta rut gon cua List mode.
            String comments = HtmlTextUtils.stripHtml(rawComments);
            if (comments != null) {
                holder.comment.setText(comments);
                holder.comment.setVisibility(View.VISIBLE);
            } else {
                holder.comment.setVisibility(View.GONE);
            }
        }

        // % tien trinh -- LIST va GRID co id bi_progress. Grid an ca title lan progress cung
        // luc khi tat pref_grid_show_title_progress (xem mShowGridTitleProgress o tren).
        if (holder.progress != null) {
            Integer percent = (extras != null) ? extras.progressPercent : null;
            boolean progressAllowedHere = (mViewMode != ViewMode.GRID)
                    || mGridStyle == GridDisplayStyle.SIMPLE2
                    || (mShowGridTitleProgress && mGridStyle != GridDisplayStyle.BOOKSHELF);
            if (percent != null && progressAllowedHere) {
                holder.progress.setText(percent + "%");
                holder.progress.setVisibility(View.VISIBLE);
            } else {
                holder.progress.setVisibility(View.GONE);
            }
        }

        boolean selected = mSelectionMode && mSelectedIds != null && mSelectedIds.contains(id);
        if (holder.checkbox != null) {
            holder.checkbox.setVisibility(mSelectionMode ? View.VISIBLE : View.GONE);
            holder.checkbox.setChecked(selected);
        }

        if (holder.foreground != null) {
            float dx = 0f;
            if (position == mOpenPosition) {
                dx = (mOpenDirection == OPEN_DIRECTION_START) ? holder.swipeMaxDxStart : -holder.swipeMaxDxEnd;
            }
            holder.foreground.setTranslationX(dx);
        }

        // For TYPE_LIST/SIMPLE the foreground layer (swipe_foreground_content) is the view
        // that's actually clickable=true in its XML and fully covers itemView while closed, so
        // the tap/long-press listeners must go there -- attaching them to itemView instead
        // would silently never fire. TYPE_GRID/CAROUSEL have no separate foreground layer, so
        // itemView itself is the right target.
        View clickTarget = holder.foreground != null ? holder.foreground : holder.itemView;
        clickTarget.setOnClickListener(v -> {
            if (mSelectionMode) {
                mListener.onBookToggleSelect(id);
            } else {
                closeOpenRow();
                mListener.onBookOpen(uuid);
            }
        });
        clickTarget.setOnLongClickListener(v -> {
            closeOpenRow();
            mListener.onBookLongPress(id);
            return true;
        });

        if (holder.actionShare != null) {
            holder.actionShare.setOnClickListener(v -> {
                closeOpenRow();
                mListener.onShareRequested(id);
            });
        }
        if (holder.actionDelete != null) {
            holder.actionDelete.setOnClickListener(v -> {
                closeOpenRow();
                mListener.onDeleteRequested(id);
            });
        }
        if (holder.actionSync != null) {
            holder.actionSync.setOnClickListener(v -> {
                closeOpenRow();
                mListener.onSyncRequested(id);
            });
        }
        if (holder.actionListen != null) {
            holder.actionListen.setOnClickListener(v -> {
                closeOpenRow();
                mListener.onListenRequested(id);
            });
        }
        if (holder.actionRead != null) {
            holder.actionRead.setOnClickListener(v -> {
                closeOpenRow();
                mListener.onReadRequested(id);
            });
        }
    }

    /** Cache-key on dinh cho 1 lan bind: uu tien cover_path tren dia (giong thu tu uu tien trong
     * decodeCoverOffThread ben duoi); fallback ve "blob:<uuid>" khi khong co file tren dia, de van
     * co the cache duoc cover lay tu BLOB SQLite (usbms tu Calibre Desktop). */
    private static String coverCacheKey(Context context, Cursor cs) {
        try {
            int coverIdx = Book.COVER_PATH;
            if (coverIdx < cs.getColumnCount() && !cs.isNull(coverIdx)) {
                String coverPath = cs.getString(coverIdx);
                if (coverPath != null && !coverPath.isEmpty()) return "file:" + coverPath;
            }
        } catch (Exception ignored) { }
        String uuid = cs.getString(Book.UUID);
        return uuid != null ? "blob:" + uuid : null;
    }

    /** Set cover cho 1 ImageView, uu tien cache trong bo nho (CoverCache), decode bat dong bo
     * (CoverDecodeExecutor) khi cache miss. Dung ImageView#setTag() de danh dau "row nay dang cho
     * cover uuid X" -- day la ky thuat chuan cho van de kinh dien cua RecyclerView + async image
     * loading: khi decode xong (co the mat vai chuc ms), row co the DA BI TAI SU DUNG cho item khac
     * (user scroll nhanh). Neu khong check tag truoc khi setImageBitmap, anh cua item cu se bi ve
     * nham len item moi trong 1 khoanh khac roi moi duoc ghi de dung -- hieu ung "nhay anh". */
    private static void bindCoverThumbnail(ImageView thumb, Context context, Cursor cs) {
        CoverCache.init(context);
        String cacheKey = coverCacheKey(context, cs);
        String bindToken = cs.getString(Book.UUID); // dung de so khop row khi callback ve
        thumb.setTag(bindToken);

        Bitmap cached = CoverCache.getBitmap(cacheKey);
        if (cached != null) {
            thumb.setImageBitmap(cached);
            return;
        }

        // Placeholder ngay lap tuc -- truoc day decode chay dong bo nen "khong co luc dang tai";
        // gio decode chay async nen PHAI set placeholder truoc, neu khong row vua duoc tai su dung
        // se hien tam anh cua item cu cho toi khi decode xong (xem upgrade_1.md muc 4.2).
        thumb.setImageResource(R.drawable.dumbbook);

        Context appContext = context.getApplicationContext();
        // Snapshot du lieu can cho decode NGAY BAY GIO (tren main thread) -- Cursor KHONG an toan de
        // doc tu background thread sau khi con tro cursor da di chuyen sang row khac do scroll tiep.
        int coverIdx = Book.COVER_PATH;
        String coverPath = (coverIdx < cs.getColumnCount() && !cs.isNull(coverIdx))
                ? cs.getString(coverIdx) : null;
        byte[] blobFallback;
        try {
            blobFallback = cs.getBlob(Book.THUMB);
        } catch (Exception e) {
            blobFallback = null;
        }
        byte[] finalBlobFallback = blobFallback;

        CoverDecodeExecutor.decodeAsync(() -> decodeCoverOffThread(appContext, coverPath, finalBlobFallback),
                bitmap -> {
                    // Row co the da bi recycle sang item khac trong luc decode -- chi set anh neu tag
                    // hien tai VAN con la bindToken luc enqueue.
                    if (bitmap != null && bindToken != null && bindToken.equals(thumb.getTag())) {
                        CoverCache.putBitmap(cacheKey, bitmap);
                        thumb.setImageBitmap(bitmap);
                    }
                    // Bitmap null (khong tim thay cover o ca 2 nguon): giu nguyen placeholder da set
                    // o tren, khong can lam gi them.
                });
    }

    /** Chay tren background thread (CoverDecodeExecutor) -- KHONG duoc dong Cursor o day, chi dung
     * cac gia tri da snapshot san (coverPath/blobFallback), giu dung thu tu uu tien nhu truoc day:
     * (1) file tren dia, (2) BLOB SQLite, (3) null. */
    private static Bitmap decodeCoverOffThread(Context appContext, String coverPath, byte[] blobFallback) {
        if (coverPath != null && !coverPath.isEmpty()) {
            try {
                SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(appContext);
                File rootDir = StorageUtils.getRootDirectory(appContext, settings);
                File coverFile = new File(rootDir, coverPath);
                if (coverFile.exists()) {
                    Bitmap fromFile = CoverManager.decodeSampledBitmapFromFile(
                            coverFile, THUMB_MAX_WIDTH, THUMB_MAX_HEIGHT);
                    if (fromFile != null) return fromFile;
                }
            } catch (Exception e) {
                Logger.e(e);
            }
        }
        if (blobFallback != null) {
            try {
                Bitmap fromBlob = BitmapFactory.decodeByteArray(blobFallback, 0, blobFallback.length);
                if (fromBlob != null) return fromBlob;
            } catch (Exception ignored) { }
        }
        return null;
    }

    @Override
    public int getItemCount() {
        return mCursor == null ? 0 : mCursor.getCount();
    }

    /** So sanh danh sach RowSnapshot cu (chup truoc requery/truoc khi doi cursor) voi Cursor
     * moi -- cho phep DiffUtil chi rebind dung nhung row thuc su thay doi, thay vi
     * notifyDataSetChanged() rebind toan bo danh sach dang hien thi moi lan refresh. Khoa so sanh
     * la uuid (on dinh qua cac lan requery/doi filter), khong phai vi tri hay _id. */
    private static final class BookDiffCallback extends DiffUtil.Callback {
        private final List<RowSnapshot> oldRows;
        private final Cursor newCursor;

        BookDiffCallback(List<RowSnapshot> oldRows, Cursor newCursor) {
            this.oldRows = oldRows != null ? oldRows : new ArrayList<>();
            this.newCursor = newCursor;
        }

        @Override public int getOldListSize() { return oldRows.size(); }
        @Override public int getNewListSize() { return newCursor == null ? 0 : newCursor.getCount(); }

        @Override
        public boolean areItemsTheSame(int oldPos, int newPos) {
            String oldUuid = (oldPos < oldRows.size()) ? oldRows.get(oldPos).uuid : null;
            String newUuid = fieldAt(newCursor, newPos, Book.UUID);
            return Objects.equals(oldUuid, newUuid);
        }

        @Override
        public boolean areContentsTheSame(int oldPos, int newPos) {
            // So sanh cac field anh huong den hien thi truc tiep. Khong can so sanh MOI cot -- chi
            // can cac cot thuc su duoc bind trong onBindViewHolder (title/author/cover_path/progress
            // se thay doi khi user sua metadata/doc tiep/sync lai cover).
            if (oldPos >= oldRows.size()) return false;
            RowSnapshot old = oldRows.get(oldPos);
            return Objects.equals(old.uuid, fieldAt(newCursor, newPos, Book.UUID))
                    && Objects.equals(old.title, fieldAt(newCursor, newPos, Book.TITLE))
                    && Objects.equals(old.author, fieldAt(newCursor, newPos, Book.AUTHOR))
                    && Objects.equals(old.coverPath, fieldAt(newCursor, newPos, Book.COVER_PATH));
        }
    }

    private static String fieldAt(Cursor c, int pos, int columnIndex) {
        if (c == null || !c.moveToPosition(pos)) return null;
        try {
            return columnIndex < c.getColumnCount() && !c.isNull(columnIndex)
                    ? c.getString(columnIndex) : null;
        } catch (Exception e) {
            return null;
        }
    }

    static class BookViewHolder extends RecyclerView.ViewHolder {
        final ImageView thumbnail;
        final TextView title;
        /** Null for GRID (bi_author dropped from item_book_grid_select.xml to match Readest). */
        final TextView author;
        final TextView uuid;
        final CheckBox checkbox;
        /** Null for GRID/CAROUSEL (no swipe layer there). */
        final View foreground;
        final View actionShare;
        final View actionDelete;
        /** Null for GRID/CAROUSEL (no swipe layer there), same as actionShare/actionDelete. */
        final View actionSync;
        /** Null for GRID/CAROUSEL (no swipe layer there), same as actionSync. */
        final View actionListen;
        final View actionRead;
        /** Max leftward reveal (Sync/Share/Delete area), swipe_actions_width. */
        final float swipeMaxDxEnd;
        /** Max rightward reveal (Listen/Read area), swipe_actions_width_start. */
        final float swipeMaxDxStart;
        /** Description/comment snippet, LIST only -- null elsewhere (findViewById returns null
         * when item_book_swipe.xml's bi_comment id isn't present in the inflated layout). */
        final TextView comment;
        /** Reading progress %, LIST and GRID -- null for SIMPLE_LIST/CAROUSEL. */
        final TextView progress;
        /** Badge dinh dang (EPUB/PDF...), chi khac null cho TYPE_GRID_SIMPLE2 (xem
         * item_book_grid_select_simple2.xml). Null o moi layout khac. */
        final TextView formatBadge;

        BookViewHolder(@NonNull View itemView, int viewType) {
            super(itemView);
            thumbnail = itemView.findViewById(R.id.bi_thumbnail);
            title = itemView.findViewById(R.id.bi_title);
            author = itemView.findViewById(R.id.bi_author);
            uuid = itemView.findViewById(R.id.bi_uuid);
            checkbox = itemView.findViewById(R.id.bi_select_checkbox);
            foreground = itemView.findViewById(R.id.swipe_foreground_content);
            actionShare = itemView.findViewById(R.id.action_share);
            actionDelete = itemView.findViewById(R.id.action_delete);
            actionSync = itemView.findViewById(R.id.action_sync);
            actionListen = itemView.findViewById(R.id.action_listen);
            actionRead = itemView.findViewById(R.id.action_read);
            swipeMaxDxEnd = itemView.getResources().getDimension(R.dimen.swipe_actions_width);
            swipeMaxDxStart = itemView.getResources().getDimension(R.dimen.swipe_actions_width_start);
            comment = itemView.findViewById(R.id.bi_comment);
            progress = itemView.findViewById(R.id.bi_progress);
            formatBadge = itemView.findViewById(R.id.bi_format_badge);
        }
    }
}
