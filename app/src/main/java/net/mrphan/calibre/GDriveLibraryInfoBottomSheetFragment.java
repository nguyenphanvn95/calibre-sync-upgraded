package net.mrphan.calibre;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Dialog "Library details" (Phan 2 cua prompt nang cap): hien Last sync + nut Sync, va so
 * luong Books/Authors/Tags/Publishers/Series -- doc tu GDriveLibraryDb DANG MO cua
 * GDriveLibraryWebViewActivity thong qua GDriveOpdsHttpServer.getInstance().getDb() (KHONG tu mo
 * them 1 ket noi khac cho cung file .db). Nut Sync dung chung logic voi
 * GDriveOpdsLibraryListBottomSheet qua GDriveLibrarySyncHelper, sau do bao Activity cha nap lai
 * du lieu trong WebView (khong reload ca trang) qua OnLibraryRefreshedListener. */
public class GDriveLibraryInfoBottomSheetFragment extends BottomSheetDialogFragment {

    private static final String ARG_LIBRARY_ID = "library_id";

    public interface OnLibraryRefreshedListener {
        void onLibraryDataRefreshed();
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private OnLibraryRefreshedListener listener;

    private TextView tvLastSync;
    private Button btnSync;
    private ProgressBar pbSyncing;
    private TextView tvBookCount, tvAuthorCount, tvTagCount, tvPublisherCount, tvSeriesCount;

    public static GDriveLibraryInfoBottomSheetFragment newInstance(String libraryId) {
        GDriveLibraryInfoBottomSheetFragment f = new GDriveLibraryInfoBottomSheetFragment();
        Bundle args = new Bundle();
        args.putString(ARG_LIBRARY_ID, libraryId);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof OnLibraryRefreshedListener) {
            listener = (OnLibraryRefreshedListener) context;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.bottomsheet_gdrive_library_info, container, false);
        tvLastSync = v.findViewById(R.id.tvLastSync);
        btnSync = v.findViewById(R.id.btnSync);
        pbSyncing = v.findViewById(R.id.pbSyncing);
        tvBookCount = v.findViewById(R.id.tvBookCount);
        tvAuthorCount = v.findViewById(R.id.tvAuthorCount);
        tvTagCount = v.findViewById(R.id.tvTagCount);
        tvPublisherCount = v.findViewById(R.id.tvPublisherCount);
        tvSeriesCount = v.findViewById(R.id.tvSeriesCount);

        btnSync.setOnClickListener(view -> onSyncClicked());
        loadSummary();
        return v;
    }

    private String libraryId() {
        return getArguments() != null ? getArguments().getString(ARG_LIBRARY_ID) : null;
    }

    private void loadSummary() {
        if (getActivity() == null) return;
        GDriveOpdsLibrary library = GDriveOpdsLibraryStore.getById(getActivity(), libraryId());
        GDriveLibraryDb db = GDriveOpdsHttpServer.getInstance().getDb();
        if (library == null || db == null) {
            // Server chua chay hoac thu vien khong con ton tai -- khong co gi de hien, dong dialog.
            dismissAllowingStateLoss();
            return;
        }
        updateLastSyncLabel(library.getLastSyncedAtMillis());
        executor.execute(() -> {
            GDriveLibraryDb.LibrarySummary summary = db.getSummary();
            mainHandler.post(() -> bindSummary(summary));
        });
    }

    private void bindSummary(GDriveLibraryDb.LibrarySummary s) {
        if (tvBookCount == null) return; // View da bi huy (dialog dong truoc khi query xong)
        tvBookCount.setText(String.valueOf(s.bookCount));
        tvAuthorCount.setText(String.valueOf(s.authorCount));
        tvTagCount.setText(String.valueOf(s.tagCount));
        tvPublisherCount.setText(String.valueOf(s.publisherCount));
        tvSeriesCount.setText(String.valueOf(s.seriesCount));
    }

    private void updateLastSyncLabel(long millis) {
        if (tvLastSync == null) return;
        if (millis <= 0) {
            tvLastSync.setText(R.string.gdrive_library_info_never_synced);
            return;
        }
        String formatted = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT,
                Locale.getDefault()).format(new Date(millis));
        tvLastSync.setText(getString(R.string.gdrive_library_info_last_sync, formatted));
    }

    private void onSyncClicked() {
        if (getActivity() == null) return;
        GDriveOpdsLibrary library = GDriveOpdsLibraryStore.getById(getActivity(), libraryId());
        GDriveLibraryDb db = GDriveOpdsHttpServer.getInstance().getDb();
        if (library == null || db == null) return;

        setSyncing(true);
        Context appContext = getActivity().getApplicationContext();
        executor.execute(() -> {
            GDriveLibrarySyncHelper.Result result = GDriveLibrarySyncHelper.syncIfChanged(appContext, library, db);
            GDriveLibraryDb.LibrarySummary summary = db.getSummary();
            mainHandler.post(() -> {
                setSyncing(false);
                if (getActivity() == null) return;
                if (result.error != null) {
                    Toast.makeText(getActivity(), R.string.gdrive_opds_sync_failed_generic, Toast.LENGTH_LONG).show();
                    return;
                }
                updateLastSyncLabel(library.getLastSyncedAtMillis());
                bindSummary(summary);
                Toast.makeText(getActivity(), result.changed
                        ? R.string.gdrive_opds_refresh_updated
                        : R.string.gdrive_opds_refresh_unchanged, Toast.LENGTH_SHORT).show();
                if (result.changed && listener != null) listener.onLibraryDataRefreshed();
            });
        });
    }

    private void setSyncing(boolean syncing) {
        if (pbSyncing == null || btnSync == null) return;
        pbSyncing.setVisibility(syncing ? View.VISIBLE : View.GONE);
        btnSync.setVisibility(syncing ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        executor.shutdown();
    }
}
