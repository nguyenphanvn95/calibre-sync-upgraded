package net.mrphan.calibre;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Liệt kê các thư viện Calibre-on-GDrive đã thêm, với Duyệt/Sửa/Xóa mỗi hàng (PopupMenu), 1 nút
 * "Làm mới" mỗi hàng, và 1 hàng "+ Thêm thư viện khác". Hiện từ card "Calibre Libraries on GDrive"
 * khi đã có ≥1 thư viện. Kiến trúc bám sát 1-1 CloudRemoteListBottomSheet (đang dùng cho
 * WebDAV/FTP). */
public class GDriveOpdsLibraryListBottomSheet extends BottomSheetDialogFragment
        implements AddGDriveOpdsLibraryDialogFragment.OnLibrarySavedListener {

    private RecyclerView rvLibraries;
    private LibraryListAdapter adapter;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Nhận chuỗi text quét được từ QrScanActivity. Đăng ký ở đây (field level, không phải trong
    // onCreateView/onClickListener) vì registerForActivityResult() bắt buộc phải gọi trước khi
    // Fragment vào state STARTED.
    private final ActivityResultLauncher<Intent> qrScanLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() != android.app.Activity.RESULT_OK || result.getData() == null) return;
                String scannedText = result.getData().getStringExtra(QrScanActivity.EXTRA_SCANNED_TEXT);
                onQrScanned(scannedText);
            });

    public static GDriveOpdsLibraryListBottomSheet newInstance() {
        return new GDriveOpdsLibraryListBottomSheet();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.bottomsheet_gdrive_opds_library_list, container, false);
        rvLibraries = v.findViewById(R.id.rvLibraries);
        rvLibraries.setLayoutManager(new LinearLayoutManager(getActivity()));
        refreshList();

        TextView tvAddAnother = v.findViewById(R.id.tvAddAnotherLibrary);
        tvAddAnother.setOnClickListener(view ->
                AddGDriveOpdsLibraryDialogFragment.newInstance().show(getChildFragmentManager(), "add_gdrive_opds_library"));

        TextView tvScanQr = v.findViewById(R.id.tvScanQrLibrary);
        tvScanQr.setOnClickListener(view ->
                qrScanLauncher.launch(new Intent(getActivity(), QrScanActivity.class)));

        return v;
    }

    /** Kết quả quét QR (tính năng "Nhập thư viện bằng QR"): chuỗi thô lấy được từ camera CHƯA
     * chắc là link Google Drive hợp lệ, tách fileId bằng đúng hàm
     * GDrivePublicFileClient.extractFileId() đang dùng cho luồng gõ tay, để 2 luồng luôn nhất
     * quán 1 quy tắc parse link duy nhất. Nếu tách được, mở AddGDriveOpdsLibraryDialogFragment
     * với link đã pre-fill + tự trigger validate, KHÔNG lưu thư viện trực tiếp ở đây, vẫn phải đi
     * qua fetchMetadata() để xác nhận file thật sự tồn tại/public trước khi cho lưu, y hệt luồng
     * thủ công. */
    private void onQrScanned(@Nullable String rawScannedText) {
        if (getActivity() == null) return;
        String fileId = GDrivePublicFileClient.extractFileId(rawScannedText);
        if (fileId == null) {
            Toast.makeText(getActivity(), R.string.gdrive_opds_qr_invalid_link, Toast.LENGTH_LONG).show();
            return;
        }
        AddGDriveOpdsLibraryDialogFragment.newInstanceFromQrScan(rawScannedText)
                .show(getChildFragmentManager(), "add_gdrive_opds_library");
    }

    private void refreshList() {
        if (getActivity() == null) return;
        List<GDriveOpdsLibrary> all = GDriveOpdsLibraryStore.getAll(getActivity());
        adapter = new LibraryListAdapter(all);
        rvLibraries.setAdapter(adapter);
    }

    @Override
    public void onLibrarySaved() {
        refreshList();
        if (getParentFragment() instanceof AddCloudRemoteDialogFragment.OnCloudRemoteSavedListener) {
            ((AddCloudRemoteDialogFragment.OnCloudRemoteSavedListener) getParentFragment()).onCloudRemoteSaved();
        }
    }

    private void openBrowse(GDriveOpdsLibrary library) {
        Intent intent = new Intent(getActivity(), GDriveLibraryWebViewActivity.class);
        intent.putExtra(GDriveLibraryWebViewActivity.EXTRA_LIBRARY_ID, library.getId());
        startActivity(intent);
    }

    private void openEdit(GDriveOpdsLibrary library) {
        AddGDriveOpdsLibraryDialogFragment.newInstanceForEdit(library.getId())
                .show(getChildFragmentManager(), "add_gdrive_opds_library");
    }

    private void confirmDelete(GDriveOpdsLibrary library) {
        new AlertDialog.Builder(getActivity())
                .setMessage(getString(R.string.gdrive_opds_confirm_delete_library, library.getDisplayName()))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    GDriveOpdsLibraryStore.delete(getActivity(), library.getId());
                    onLibrarySaved();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Nút "Làm mới" của 1 hàng (Phần 1.3 của prompt nâng cấp): tải metadata_public.json mới,
     * SO SÁNH chữ ký (generated_at/book_count) với bản đã cache trong GDriveLibraryDb TRƯỚC khi
     * quyết định có cần xóa/ghi lại bảng "books" hay không -- tránh rebuildFromJson() mù quáng
     * như trước đây. holder truyền vào để bật/tắt trạng thái loading đúng hàng đang bấm (không
     * cần dialog full-screen). */
    private void refreshLibrary(GDriveOpdsLibrary library, LibraryListAdapter.VH holder) {
        setRowRefreshing(holder, true);
        executor.execute(() -> {
            GDriveLibraryDb db = new GDriveLibraryDb(getActivity().getApplicationContext(), library.getId());
            GDriveLibrarySyncHelper.Result result = GDriveLibrarySyncHelper.syncIfChanged(
                    getActivity().getApplicationContext(), library, db);
            db.close();
            mainHandler.post(() -> {
                setRowRefreshing(holder, false);
                if (getActivity() == null) return;
                if (result.error != null) {
                    Toast.makeText(getActivity(), R.string.gdrive_opds_sync_failed_generic, Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(getActivity(), result.changed
                        ? R.string.gdrive_opds_refresh_updated
                        : R.string.gdrive_opds_refresh_unchanged, Toast.LENGTH_SHORT).show();
                refreshList();
            });
        });
    }

    private void setRowRefreshing(LibraryListAdapter.VH holder, boolean refreshing) {
        holder.pbRefreshing.setVisibility(refreshing ? View.VISIBLE : View.GONE);
        holder.refreshButton.setVisibility(refreshing ? View.GONE : View.VISIBLE);
        holder.itemView.setEnabled(!refreshing);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        executor.shutdown();
    }

    private class LibraryListAdapter extends RecyclerView.Adapter<LibraryListAdapter.VH> {
        private final List<GDriveOpdsLibrary> items;

        LibraryListAdapter(List<GDriveOpdsLibrary> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_gdrive_opds_library, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            GDriveOpdsLibrary library = items.get(position);
            holder.name.setText(library.getDisplayName());
            holder.subtitle.setText(library.getCachedBookCount() >= 0
                    ? getResources().getQuantityString(R.plurals.opds_library_book_count, library.getCachedBookCount(), library.getCachedBookCount())
                    : getString(R.string.gdrive_opds_not_synced_yet));
            holder.itemView.setOnClickListener(v -> openBrowse(library));
            holder.refreshButton.setOnClickListener(v -> refreshLibrary(library, holder));
            holder.menuButton.setOnClickListener(v -> {
                PopupMenu popup = new PopupMenu(getActivity(), holder.menuButton);
                popup.getMenu().add(0, 1, 0, R.string.cloud_action_browse);
                popup.getMenu().add(0, 2, 1, R.string.cloud_action_edit);
                popup.getMenu().add(0, 3, 2, R.string.cloud_action_delete);
                popup.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == 1) openBrowse(library);
                    else if (item.getItemId() == 2) openEdit(library);
                    else if (item.getItemId() == 3) confirmDelete(library);
                    return true;
                });
                popup.show();
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView name, subtitle;
            final View menuButton;
            final View refreshButton;
            final View pbRefreshing;

            VH(@NonNull View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.tvLibraryName);
                subtitle = itemView.findViewById(R.id.tvLibrarySubtitle);
                menuButton = itemView.findViewById(R.id.btnLibraryMenu);
                refreshButton = itemView.findViewById(R.id.btnLibraryRefresh);
                pbRefreshing = itemView.findViewById(R.id.pbLibraryRefreshing);
            }
        }
    }
}
