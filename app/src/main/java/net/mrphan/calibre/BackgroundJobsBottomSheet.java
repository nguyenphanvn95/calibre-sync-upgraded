package net.mrphan.calibre;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Danh sach tac vu dong bo (upload/download Device Library <-> cloud) dang co trong WorkManager,
 * mo tu muc "Tac vu chay nen" o cuoi overflow menu cua tab Device Library (CollectionFragment).
 * Ghep BackgroundJobStore (ten/remote/so sach/thoi diem tao -- WorkInfo khong mang cac truong
 * nay) voi LiveData<List<WorkInfo>> cua UNIQUE_WORK_NAME (trang thai + tien trinh done/total qua
 * setProgressAsync trong DeviceLibrarySyncWorker) de ra 1 dong hien thi day du. Sap xep tang dan
 * theo thoi diem tao (cu -> moi) dung theo yeu cau UI. */
public class BackgroundJobsBottomSheet extends BottomSheetDialogFragment {

    private RecyclerView rvJobs;
    private View emptyView;
    private Button btnClearHistory;
    private JobAdapter adapter;
    /** WorkInfo moi nhat da nhan tu LiveData -- dung boi onClearHistoryClicked() de biet job nao
     * dang con "song" (khong duoc xoa), khong can query lai WorkManager luc bam nut. */
    private List<WorkInfo> latestWorkInfos = new ArrayList<>();

    public static BackgroundJobsBottomSheet newInstance() {
        return new BackgroundJobsBottomSheet();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.bottomsheet_background_jobs, container, false);
        rvJobs = v.findViewById(R.id.rvJobs);
        emptyView = v.findViewById(R.id.tvEmpty);
        btnClearHistory = v.findViewById(R.id.btnClearJobsHistory);
        btnClearHistory.setOnClickListener(view -> onClearHistoryClicked());
        rvJobs.setLayoutManager(new LinearLayoutManager(getActivity()));
        adapter = new JobAdapter(new ArrayList<>());
        rvJobs.setAdapter(adapter);
        observeJobs();
        return v;
    }

    private void observeJobs() {
        if (getActivity() == null) return;
        WorkManager.getInstance(getActivity())
                .getWorkInfosForUniqueWorkLiveData(DeviceLibrarySyncScheduler.UNIQUE_WORK_NAME)
                .observe(getViewLifecycleOwner(), this::refresh);
    }

    private void refresh(List<WorkInfo> workInfos) {
        if (getActivity() == null) return;
        latestWorkInfos = workInfos != null ? workInfos : new ArrayList<>();
        List<BackgroundJobStore.JobMeta> metas = BackgroundJobStore.getAllSortedOldestFirst(getActivity());
        List<Row> rows = new ArrayList<>();
        for (BackgroundJobStore.JobMeta meta : metas) {
            WorkInfo info = findById(latestWorkInfos, meta.id);
            // WorkManager da tu don dep job qua cu -- khong con WorkInfo thi bo qua, khong co
            // trang thai that de hien thi.
            if (info == null) continue;
            rows.add(new Row(meta, info));
        }
        adapter.submit(rows);
        emptyView.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        rvJobs.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        btnClearHistory.setEnabled(!rows.isEmpty());
    }

    /** Xoa tat ca lich su TRU cac job dang chay/dang cho (RUNNING/ENQUEUED/BLOCKED) -- job dang
     * chay van tiep tuc hien thi tien trinh binh thuong, chi cac dong da xong (thanh cong/that
     * bai) hoac mo coi bi bo di. */
    private void onClearHistoryClicked() {
        if (getActivity() == null) return;
        Set<String> activeIds = new HashSet<>();
        for (WorkInfo info : latestWorkInfos) {
            switch (info.getState()) {
                case RUNNING:
                case ENQUEUED:
                case BLOCKED:
                    activeIds.add(info.getId().toString());
                    break;
                default:
                    break;
            }
        }
        BackgroundJobStore.clearFinished(getActivity(), activeIds);
        Toast.makeText(getActivity(), R.string.background_jobs_clear_history_toast, Toast.LENGTH_SHORT).show();
        refresh(latestWorkInfos);
    }

    @Nullable
    private static WorkInfo findById(@Nullable List<WorkInfo> workInfos, String id) {
        if (workInfos == null) return null;
        for (WorkInfo info : workInfos) {
            if (info.getId().toString().equals(id)) return info;
        }
        return null;
    }

    /** 1 dong hien thi = metadata tinh (ten/remote/so sach) ghep voi WorkInfo song (trang
     * thai/tien trinh). */
    private static final class Row {
        final BackgroundJobStore.JobMeta meta;
        final WorkInfo info;
        Row(BackgroundJobStore.JobMeta meta, WorkInfo info) {
            this.meta = meta;
            this.info = info;
        }
    }

    private class JobAdapter extends RecyclerView.Adapter<JobAdapter.VH> {
        private List<Row> rows;

        JobAdapter(List<Row> rows) {
            this.rows = rows;
        }

        void submit(List<Row> newRows) {
            this.rows = newRows;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_background_job, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            Row row = rows.get(position);
            boolean isUpload = BackgroundJobStore.DIRECTION_UPLOAD.equals(row.meta.direction);
            boolean isFullSync = BackgroundJobStore.DIRECTION_FULL_SYNC.equals(row.meta.direction);

            if (isFullSync) {
                holder.tvTitle.setText(getString(R.string.background_job_title_full_sync, row.meta.remoteName));
            } else {
                holder.tvTitle.setText(isUpload
                        ? getString(R.string.background_job_title_upload, row.meta.remoteName)
                        : getString(R.string.background_job_title_download, row.meta.remoteName));
            }

            int done = row.info.getProgress().getInt(DeviceLibrarySyncWorker.KEY_DONE, 0);
            int total = row.info.getProgress().getInt(DeviceLibrarySyncWorker.KEY_TOTAL, 0);

            switch (row.info.getState()) {
                case RUNNING:
                    showSpinner(holder);
                    if (total > 0) {
                        holder.tvContent.setText(getString(R.string.background_job_progress_format, done, total));
                        holder.progressBar.setIndeterminate(false);
                        holder.progressBar.setMax(total);
                        holder.progressBar.setProgress(done);
                    } else {
                        holder.tvContent.setText(R.string.background_job_status_running);
                        holder.progressBar.setIndeterminate(true);
                    }
                    break;
                case SUCCEEDED:
                    showIcon(holder, R.drawable.ic_status_success);
                    holder.tvContent.setText(row.meta.bookCount > 0
                            ? getString(R.string.background_job_content_book_count, row.meta.bookCount)
                            : getString(R.string.background_job_status_done));
                    holder.progressBar.setIndeterminate(false);
                    holder.progressBar.setMax(1);
                    holder.progressBar.setProgress(1);
                    break;
                case FAILED:
                case CANCELLED:
                    showIcon(holder, R.drawable.ic_status_error);
                    holder.tvContent.setText(R.string.background_job_status_failed);
                    holder.progressBar.setIndeterminate(false);
                    holder.progressBar.setProgress(0);
                    break;
                case ENQUEUED:
                case BLOCKED:
                default:
                    showSpinner(holder);
                    holder.tvContent.setText(R.string.background_job_status_waiting);
                    holder.progressBar.setIndeterminate(true);
                    break;
            }
        }

        private void showSpinner(VH holder) {
            holder.spinner.setVisibility(View.VISIBLE);
            holder.statusIcon.setVisibility(View.GONE);
        }

        private void showIcon(VH holder, int drawableRes) {
            holder.spinner.setVisibility(View.GONE);
            holder.statusIcon.setVisibility(View.VISIBLE);
            holder.statusIcon.setImageResource(drawableRes);
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView tvTitle, tvContent;
            final ProgressBar progressBar;
            final ProgressBar spinner;
            final ImageView statusIcon;

            VH(View v) {
                super(v);
                tvTitle = v.findViewById(R.id.tvJobTitle);
                tvContent = v.findViewById(R.id.tvJobContent);
                progressBar = v.findViewById(R.id.progressJob);
                spinner = v.findViewById(R.id.progressSpinner);
                statusIcon = v.findViewById(R.id.ivStatusIcon);
            }
        }
    }
}
