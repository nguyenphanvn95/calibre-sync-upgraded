package net.mrphan.calibre;

import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * User: sengjea
 * Date: 25/12/13
 * Time: 21:40
 * <p>
 * Ket qua tim kiem sach (Android Search Framework, ACTION_SEARCH). mDB.search() (xem
 * MetadataDatabaseHelper) chi tra ve sach da co "lpath" (da tai ve may) nen day la 1 tap con cua
 * Device Library -- vi vay man hinh nay gio dung chung BookRecyclerAdapter + item_book_swipe.xml
 * voi CollectionFragment (Device Library tab), thay vi ListView + BookCursorAdapter rieng nhu
 * truoc: bam vao 1 dong se mo man hinh preview (DeviceBookDetailActivity) thay vi mo doc luon,
 * va co swipe trai/phai de lo cac nut Sync/Share/Delete va Listen/Read, giong het Device Library.
 */
public class BookSearchActivity extends AppCompatActivity implements BookRecyclerAdapter.Listener {
    private static MetadataDatabaseHelper mDB;
    private String query;
    private BookRecyclerAdapter mAdapter;
    private static RecyclerView lvBookList;
    private ItemTouchHelper mTouchHelper;
    private View emptyState;

    // --- Floating "now playing" TTS panel -- keeps a background "Đọc cho nghe" session visible
    // while the user browses search results here, mirroring Readest's floating audio bar. See
    // net.mrphan.calibre.reader.tts.GlobalTtsFloatingPanel's kdoc.
    private net.mrphan.calibre.reader.tts.GlobalTtsFloatingPanel mTtsFloatingPanel;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.setContentView(R.layout.activity_book_search);
        mTtsFloatingPanel = new net.mrphan.calibre.reader.tts.GlobalTtsFloatingPanel(this);
        // Get the intent, verify the action and get the query
        Intent intent = getIntent();
        if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            query = intent.getStringExtra(SearchManager.QUERY);
        }
        if (query == null) {
            // Not opened via ACTION_SEARCH with a query extra (e.g. launched some other way) --
            // fall back to an empty string so BookRecyclerAdapter/MetadataDatabaseHelper#search()
            // shows every book instead of silently matching the literal text "%null%".
            query = "";
        }

        lvBookList = findViewById(R.id.col_list);
        emptyState = findViewById(R.id.search_empty_state);
        lvBookList.setLayoutManager(new LinearLayoutManager(this));
        // On targetSdk 35, edge-to-edge is enforced by default and this Activity (unlike
        // MainActivity's tabs_main.xml, which has android:fitsSystemWindows="true") never
        // consumed the system bar insets, so the list was drawn full-screen behind the status
        // bar -- the first row's text overlapped the clock/battery icons. Same root cause and
        // fix as SettingsActivity's list (see its onViewCreated for the longer explanation):
        // apply the insets as padding directly to the list instead of the layout.
        lvBookList.setClipToPadding(false);
        ViewCompat.setOnApplyWindowInsetsListener(lvBookList, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), bars.top, v.getPaddingRight(), bars.bottom);
            return insets;
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        mDB = new MetadataDatabaseHelper(getApplicationContext());
        mAdapter = new BookRecyclerAdapter(this, mDB.search(Book.COLUMNS, query), this);
        mAdapter.setViewMode(BookRecyclerAdapter.ViewMode.LIST);
        lvBookList.setAdapter(mAdapter);
        // Swipe-to-reveal Sync/Share/Delete (swipe left) and Listen/Read (swipe right), same
        // ItemTouchHelper.Callback as the Device Library tab -- see SwipeRevealCallback's
        // Javadoc. LIST mode always has isSwipeEnabled() == true, so this stays attached.
        mTouchHelper = new ItemTouchHelper(new SwipeRevealCallback(mAdapter));
        mTouchHelper.attachToRecyclerView(mAdapter.isSwipeEnabled() ? lvBookList : null);
        refreshEmptyState();
        mTtsFloatingPanel.attach();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mTouchHelper != null) mTouchHelper.attachToRecyclerView(null);
        mDB.close();
        mTtsFloatingPanel.detach();
    }

    // ---- BookRecyclerAdapter.Listener --------------------------------------------------

    @Override
    public void onBookOpen(String uuid) {
        // Row tapped: show the preview screen instead of jumping straight into the reader --
        // mirrors CollectionFragment#onBookOpen / OPDSBrowserFragment#openBookDetail.
        // DeviceBookDetailActivity's own "Read" button is what actually launches
        // OpenBookActivity. Search results stay on the back stack so the user can come back
        // and open a different result afterwards.
        // Kem theo ca danh sach ket qua tim kiem hien tai de DeviceBookDetailActivity co the
        // vuot trai/phai sang cuon ke tiep/truoc do trong CUNG danh sach nay -- xem
        // CollectionFragment#orderedUuidsOf/indexOfUuid, logic giong het nhau.
        Cursor searchCursor = mAdapter.getCursor();
        startActivity(new Intent(getApplicationContext(), DeviceBookDetailActivity.class)
                .putExtra(DeviceBookDetailActivity.EXTRA_UUID, uuid)
                .putExtra(DeviceBookDetailActivity.EXTRA_BOOK_LIST_UUIDS, orderedUuidsOf(searchCursor))
                .putExtra(DeviceBookDetailActivity.EXTRA_BOOK_LIST_INDEX, indexOfUuid(searchCursor, uuid)));
    }

    /** Toan bo uuid trong [cursor] theo dung thu tu hien tai cua no, dung lam
     * EXTRA_BOOK_LIST_UUIDS. Khong lam thay doi vi tri hien tai cua cursor. Sao chep tu
     * CollectionFragment#orderedUuidsOf (2 lop khong chung superclass de dung chung ham). */
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
        // No multi-select mode on the search-results screen -- long-press is a no-op here.
    }

    @Override
    public void onBookToggleSelect(int id) {
        // Selection mode never turns on (see onBookLongPress above), so this is never called.
    }

    @Override
    public void onShareRequested(int id) {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        File rootDir = StorageUtils.getRootDirectory(getApplicationContext(), settings);
        Cursor cs = mDB.getById(new String[] {"lpath"}, id);
        try {
            if (!cs.moveToFirst()) return;
            List<File> files = new ArrayList<>();
            files.add(new File(rootDir, cs.getString(0)));
            ShareUtils.Result result = ShareUtils.shareFiles(this, files);
            if (result.isPartialFailure()) {
                Toast.makeText(this,
                        getString(R.string.dialog_share_partial_failure, result.requested - result.shared, result.requested),
                        Toast.LENGTH_LONG).show();
            }
        } finally {
            cs.close();
        }
    }

    @Override
    public void onDeleteRequested(final int id) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_button_delete)
                .setMessage(R.string.dialog_deletefile_message)
                .setCancelable(true)
                .setNegativeButton(R.string.dialog_button_cancel, null)
                .setPositiveButton(R.string.dialog_button_delete, (dialog, which) -> deleteBook(id))
                .show();
    }

    private void deleteBook(int id) {
        try {
            Cursor cs = mDB.getById(new String[] {"lpath"}, id);
            if (cs.moveToFirst()) {
                SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
                File rootDir = StorageUtils.getRootDirectory(getApplicationContext(), settings);
                String lpath = cs.getString(0);
                CoverManager.deleteCoverFor(rootDir, lpath);
                new File(rootDir, lpath).delete();
                mDB.deleteBook(id);
            }
            cs.close();
        } catch (Throwable e) {
            Logger.e(e);
        }
        mAdapter.swapCursor(mDB.search(Book.COLUMNS, query));
        refreshEmptyState();
    }

    private void refreshEmptyState() {
        if (emptyState == null || mAdapter == null) return;
        boolean isEmpty = mAdapter.getItemCount() == 0;
        emptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        lvBookList.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onSyncRequested(int id) {
        List<CloudRemote> syncableRemotes = new ArrayList<>();
        for (CloudRemote r : CloudRemoteStore.getAll(this)) {
            if (r.getType().supportsDeviceLibrarySync()) syncableRemotes.add(r);
        }
        if (syncableRemotes.isEmpty()) {
            Toast.makeText(this, R.string.sync_no_cloud_connected, Toast.LENGTH_LONG).show();
            return;
        }
        String[] uuids = mDB.getUuidsForIds(new int[] {id});
        if (syncableRemotes.size() == 1) {
            DeviceLibrarySyncScheduler.enqueueUpload(this, syncableRemotes.get(0).getId(), uuids);
            Toast.makeText(this, R.string.sync_started, Toast.LENGTH_SHORT).show();
            return;
        }
        CloudRemotePickerDialogFragment.newInstanceForUpload(syncableRemotes, uuids)
                .show(getSupportFragmentManager(), "pick_sync_remote");
    }

    @Override
    public void onListenRequested(int id) {
        Cursor cs = mDB.getById(new String[] {"lpath", "uuid", "title"}, id);
        try {
            if (!cs.moveToFirst()) return;
            String lpath = cs.getString(0);
            String uuid = cs.getString(1);
            String title = cs.getString(2);
            if (lpath == null || !lpath.toLowerCase(java.util.Locale.ROOT).endsWith(".epub")) {
                Toast.makeText(this, R.string.reader_tts_init_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            File rootDir = StorageUtils.getRootDirectory(getApplicationContext(), settings);
            File bookFile = new File(rootDir, lpath);
            startActivity(TtsChapterListActivity.buildIntent(this, bookFile, uuid, title));
        } finally {
            cs.close();
        }
    }

    @Override
    public void onReadRequested(int id) {
        // Explicit "Read" swipe action -- straight to the reader, same destination as
        // DeviceBookDetailActivity's own Read button.
        Cursor cs = mDB.getById(new String[] {"uuid"}, id);
        try {
            if (!cs.moveToFirst()) return;
            String uuid = cs.getString(0);
            startActivity(new Intent(getApplicationContext(), OpenBookActivity.class)
                    .putExtra(OpenBookActivity.EXTRA_UUID, uuid));
        } finally {
            cs.close();
        }
    }
}
