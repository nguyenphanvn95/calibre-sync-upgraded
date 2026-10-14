package net.mrphan.calibre;

import android.app.Dialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Thêm hoặc sửa 1 GDriveOpdsLibrary: nhập tên hiển thị (tùy chọn) + ID/link chia sẻ của
 * metadata_public.json, tự động validate (debounce 600ms, gọi GDrivePublicFileClient#fetchMetadata
 * trong background thread) trước khi cho lưu. */
public class AddGDriveOpdsLibraryDialogFragment extends DialogFragment {

    public interface OnLibrarySavedListener {
        void onLibrarySaved();
    }

    private static final String ARG_EDIT_LIBRARY_ID = "edit_library_id";
    private static final String ARG_PREFILL_LINK = "prefill_link";
    private static final long DEBOUNCE_MS = 600;

    private TextInputEditText etDisplayName, etFileIdOrLink;
    private ImageView ivValidationStatus;
    private ProgressBar pbValidating;
    private TextView tvValidationMessage;
    private AlertDialog dialog;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingValidation;

    /** Kết quả lần validate gần nhất — chỉ cho bấm Lưu khi != null (đã xác nhận file tồn tại và
     * công khai). Reset về null mỗi khi nội dung ô nhập đổi. */
    private String validatedFileId;

    public static AddGDriveOpdsLibraryDialogFragment newInstance() {
        return new AddGDriveOpdsLibraryDialogFragment();
    }

    public static AddGDriveOpdsLibraryDialogFragment newInstanceForEdit(String libraryId) {
        AddGDriveOpdsLibraryDialogFragment f = new AddGDriveOpdsLibraryDialogFragment();
        Bundle args = new Bundle();
        args.putString(ARG_EDIT_LIBRARY_ID, libraryId);
        f.setArguments(args);
        return f;
    }

    /** Mở dialog thêm thư viện với link/ID đã pre-fill sẵn từ kết quả quét QR (xem
     * GDriveOpdsLibraryListBottomSheet#onQrScanned). Vẫn đi qua đúng luồng validate/save như khi
     * gõ tay, chỉ khác ở chỗ ô nhập được điền sẵn và tự trigger validate ngay khi dialog mở ra
     * thay vì đợi TextWatcher bắt sự kiện gõ phím (quét QR không tạo ra sự kiện gõ phím nào). */
    public static AddGDriveOpdsLibraryDialogFragment newInstanceFromQrScan(String prefillLink) {
        AddGDriveOpdsLibraryDialogFragment f = new AddGDriveOpdsLibraryDialogFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PREFILL_LINK, prefillLink);
        f.setArguments(args);
        return f;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View v = LayoutInflater.from(getActivity()).inflate(R.layout.dialog_add_gdrive_opds_library, null);

        TextInputLayout tilDisplayName = v.findViewById(R.id.tilLibraryDisplayName);
        TextInputLayout tilFileIdOrLink = v.findViewById(R.id.tilLibraryFileIdOrLink);
        etDisplayName = v.findViewById(R.id.etLibraryDisplayName);
        etFileIdOrLink = v.findViewById(R.id.etLibraryFileIdOrLink);
        ivValidationStatus = v.findViewById(R.id.ivValidationStatus);
        pbValidating = v.findViewById(R.id.pbValidating);
        tvValidationMessage = v.findViewById(R.id.tvValidationMessage);

        String editId = getArguments() != null ? getArguments().getString(ARG_EDIT_LIBRARY_ID) : null;
        String prefillLink = getArguments() != null ? getArguments().getString(ARG_PREFILL_LINK) : null;
        GDriveOpdsLibrary editing = editId != null ? GDriveOpdsLibraryStore.getById(getActivity(), editId) : null;
        if (editing != null) {
            etDisplayName.setText(editing.getDisplayName());
            etFileIdOrLink.setText(editing.getDriveFileId());
            validatedFileId = editing.getDriveFileId();
        } else if (prefillLink != null && !prefillLink.trim().isEmpty()) {
            // Điền sẵn link quét được từ QR. setText() ở đây KHÔNG tự bắn TextWatcher trước khi
            // dialog hiện ra (view chưa attach xong action listener của AlertDialog), nên phải tự
            // gọi scheduleValidation() ngay sau, xem dialog.setOnShowListener() bên dưới.
            etFileIdOrLink.setText(prefillLink);
        }

        etFileIdOrLink.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { scheduleValidation(s.toString()); }
        });

        dialog = new AlertDialog.Builder(getActivity())
                .setTitle(editing != null ? R.string.gdrive_opds_edit_library_title : R.string.gdrive_opds_add_library_title)
                .setView(v)
                .setPositiveButton(R.string.dialog_save, null) // gán listener thủ công bên dưới để tự kiểm soát dismiss
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> onSaveClick(editing));
            updateSaveButtonEnabled();
            if (editing == null && prefillLink != null && !prefillLink.trim().isEmpty()) {
                scheduleValidation(prefillLink);
            }
        });

        if (editing != null) {
            // Đã có sẵn giá trị hợp lệ từ trước, không cần re-validate ngay khi mở dialog Sửa.
            showValidationResult(true, getString(R.string.gdrive_opds_validation_reusing_saved));
        }

        return dialog;
    }

    private void scheduleValidation(String rawInput) {
        validatedFileId = null;
        updateSaveButtonEnabled();
        if (pendingValidation != null) mainHandler.removeCallbacks(pendingValidation);

        String fileId = GDrivePublicFileClient.extractFileId(rawInput);
        if (fileId == null) {
            showValidationIdle();
            return;
        }

        pendingValidation = () -> runValidation(fileId, rawInput);
        mainHandler.postDelayed(pendingValidation, DEBOUNCE_MS);
    }

    private void runValidation(String fileId, String rawInputAtSchedule) {
        showValidationChecking();
        executor.execute(() -> {
            GDrivePublicFileClient client = new GDrivePublicFileClient(new okhttp3.OkHttpClient());
            try {
                org.json.JSONObject meta = client.fetchMetadata(fileId);
                String name = meta.optString("name", fileId);
                mainHandler.post(() -> {
                    // Bỏ qua nếu người dùng đã gõ thêm/xóa gì đó trong lúc chờ network.
                    if (!rawInputAtSchedule.equals(currentRawInput())) return;
                    validatedFileId = fileId;
                    updateSaveButtonEnabled();
                    showValidationResult(true, getString(R.string.gdrive_opds_validation_found_file, name));
                    if ((etDisplayName.getText() == null || etDisplayName.getText().toString().trim().isEmpty())) {
                        // Gợi ý tên hiển thị = tên file, người dùng vẫn có thể sửa lại.
                        etDisplayName.setHint(name);
                    }
                });
            } catch (GDriveFileException e) {
                mainHandler.post(() -> {
                    if (!rawInputAtSchedule.equals(currentRawInput())) return;
                    validatedFileId = null;
                    updateSaveButtonEnabled();
                    showValidationResult(false, messageForError(e));
                });
            }
        });
    }

    private String currentRawInput() {
        return etFileIdOrLink.getText() == null ? "" : etFileIdOrLink.getText().toString();
    }

    private String messageForError(GDriveFileException e) {
        switch (e.type) {
            case NOT_FOUND:
                return getString(R.string.gdrive_opds_validation_not_found);
            case NOT_PUBLIC:
                return getString(R.string.gdrive_opds_validation_not_public);
            case QUOTA_EXCEEDED:
                return getString(R.string.gdrive_opds_validation_quota_exceeded);
            case NETWORK:
                return getString(R.string.gdrive_opds_validation_network_error);
            default:
                return getString(R.string.gdrive_opds_validation_unknown_error);
        }
    }

    private void showValidationIdle() {
        pbValidating.setVisibility(View.GONE);
        ivValidationStatus.setVisibility(View.GONE);
        tvValidationMessage.setVisibility(View.GONE);
    }

    private void showValidationChecking() {
        pbValidating.setVisibility(View.VISIBLE);
        ivValidationStatus.setVisibility(View.GONE);
        tvValidationMessage.setVisibility(View.GONE);
    }

    private void showValidationResult(boolean valid, String message) {
        pbValidating.setVisibility(View.GONE);
        ivValidationStatus.setVisibility(View.VISIBLE);
        ivValidationStatus.setImageResource(valid ? R.drawable.ic_status_connected : R.drawable.ic_status_error);
        tvValidationMessage.setVisibility(View.VISIBLE);
        tvValidationMessage.setText(message);
    }

    private void updateSaveButtonEnabled() {
        if (dialog == null) return;
        android.widget.Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null) positive.setEnabled(validatedFileId != null);
    }

    private void onSaveClick(@Nullable GDriveOpdsLibrary editing) {
        if (validatedFileId == null || getActivity() == null) return;
        String displayNameText = etDisplayName.getText() == null ? "" : etDisplayName.getText().toString().trim();
        String displayName = !displayNameText.isEmpty() ? displayNameText
                : (etDisplayName.getHint() != null ? etDisplayName.getHint().toString() : validatedFileId);

        GDriveOpdsLibrary library = editing != null ? editing : GDriveOpdsLibrary.newWithRandomId(displayName, validatedFileId);
        library.setDisplayName(displayName);
        library.setDriveFileId(validatedFileId);
        GDriveOpdsLibraryStore.save(getActivity(), library);

        if (getParentFragment() instanceof OnLibrarySavedListener) {
            ((OnLibrarySavedListener) getParentFragment()).onLibrarySaved();
        } else if (getActivity() instanceof OnLibrarySavedListener) {
            ((OnLibrarySavedListener) getActivity()).onLibrarySaved();
        }
        autoDownloadAndCache(getActivity().getApplicationContext(), library);
        dismiss();
    }

    /** Phần 1.2 của prompt nâng cấp: tự tải + build cache SQLite (y hệt logic
     * GDriveOpdsHttpServer#start() làm khi mở thư viện) ngay sau khi lưu thành công, để
     * item_gdrive_opds_library hiện đúng số sách (cachedBookCount) ngay -- không phải đợi lần mở
     * thư viện đầu tiên. Chạy trên 1 thread nền tách biệt khỏi lifecycle của dialog (dialog đã
     * dismiss() ngay sau khi gọi hàm này) nên KHÔNG dùng executor/mainHandler của fragment (có
     * thể đã bị onDestroyView() dọn) -- tự tạo 1 thread riêng, callback thẳng vào
     * GDriveOpdsLibraryListBottomSheet qua refreshList() của chính OnLibrarySavedListener đã gọi
     * ở trên là đủ (bottomsheet tự refreshList() lại khi cần, không cần callback thêm ở đây). */
    private static void autoDownloadAndCache(android.content.Context appContext, GDriveOpdsLibrary library) {
        new Thread(() -> {
            GDriveLibraryDb db = new GDriveLibraryDb(appContext, library.getId());
            try {
                GDrivePublicFileClient client = new GDrivePublicFileClient(new okhttp3.OkHttpClient());
                String raw = client.downloadJson(library.getDriveFileId());
                db.rebuildFromJson(raw, library.getDriveFileId());
                library.setCachedBookCount(db.getBookCount());
                library.setLastSyncedAtMillis(System.currentTimeMillis());
                GDriveOpdsLibraryStore.save(appContext, library);
            } catch (Exception e) {
                // Tai am tham that bai khong sao -- nguoi dung van co the mo thu vien binh
                // thuong (GDriveOpdsHttpServer#start() se tu thu tai lai), chi la
                // cachedBookCount se hien "Not synced yet" cho toi luc do thay vi ngay lap tuc.
                Logger.e(e);
            } finally {
                db.close();
            }
        }, "GDriveOpdsAutoCache").start();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (pendingValidation != null) mainHandler.removeCallbacks(pendingValidation);
    }
}
