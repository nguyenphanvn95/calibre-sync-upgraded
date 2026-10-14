package net.mrphan.calibre;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lets the user link a Google Drive, OneDrive, Box, or Dropbox account, or add a new WebDAV/FTP
 * remote. Opened either from the "WebDAV / FTP / NAS" card (newInstance(), defaults to WebDAV),
 * the "Google Drive" card (newInstancePreselected(GOOGLE_DRIVE)), the "OneDrive" card
 * (newInstancePreselected(ONEDRIVE)), the "Box" card (newInstancePreselected(BOX)), or the
 * "Dropbox" card (newInstancePreselected(DROPBOX)) -- each preselect skips straight to that
 * provider's tab.
 */
public class AddCloudRemoteDialogFragment extends DialogFragment {

    public interface OnCloudRemoteSavedListener {
        void onCloudRemoteSaved();
    }

    private static final String ARG_PRESELECT = "preselect";

    private com.google.android.material.card.MaterialCardView cardGoogleDrive;
    private com.google.android.material.card.MaterialCardView cardOneDrive;
    private com.google.android.material.card.MaterialCardView cardBox;
    private com.google.android.material.card.MaterialCardView cardDropbox;
    private com.google.android.material.card.MaterialCardView cardWebDav;
    private com.google.android.material.card.MaterialCardView cardFtp;
    /** Which provider card is currently checked. Cards are plain checkable MaterialCardViews
     * (no RadioGroup-style mutual exclusivity built in), so this field plus selectProvider()
     * below is what keeps exactly one of them checked at a time. */
    private CloudRemoteType selectedType = CloudRemoteType.WEBDAV;
    private View groupGoogleDrive;
    private View groupOneDrive;
    private View groupBox;
    private View groupDropbox;
    private View groupWebDavFtp;
    private TextView tvGoogleDriveStatus;
    private Button btnLoginGoogleDrive;
    private TextView tvOneDriveStatus;
    private Button btnLoginOneDrive;
    private TextView tvBoxStatus;
    private Button btnLoginBox;
    private TextView tvDropboxStatus;
    private Button btnLoginDropbox;
    private TextInputLayout tilHost, tilPort, tilUsername;
    private TextInputEditText etDisplayName, etHost, etPort, etUsername, etPassword, etBasePath;
    private CheckBox cbUseTls;
    private TextView tvTestResult;
    private ProgressBar pbTestConnection;
    private Button btnTestConnection, btnSave;

    /** Loai provider dang dang nhap qua loginLauncher - dat ngay truoc launch(), doc lai trong
     * callback vi ca 2 nut "Sign in with Google"/"Sign in with Microsoft" dung chung 1
     * ActivityResultLauncher (phai dang ky truoc STARTED, khong the tao 2 launcher dong theo
     * dieu kien). */
    private CloudRemoteType pendingLoginType = CloudRemoteType.GOOGLE_DRIVE;

    private ActivityResultLauncher<android.content.Intent> loginLauncher;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static AddCloudRemoteDialogFragment newInstance() {
        return new AddCloudRemoteDialogFragment();
    }

    /** Opens the dialog with `preselect` already selected in the radio group, e.g. so tapping
     * the "Google Drive" card jumps straight into that tab instead of defaulting to WebDAV. */
    public static AddCloudRemoteDialogFragment newInstancePreselected(CloudRemoteType preselect) {
        AddCloudRemoteDialogFragment f = new AddCloudRemoteDialogFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PRESELECT, preselect.name());
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // MUST be registered before the Fragment is STARTED.
        loginLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (getActivity() == null) return;
            CloudRemoteType type = pendingLoginType;
            if (result.getResultCode() == Activity.RESULT_OK) {
                String label = result.getData() != null
                        ? result.getData().getStringExtra(CloudOAuthActivity.EXTRA_ACCOUNT_LABEL)
                        : null;
                if (label == null) {
                    label = getString(labelStringRes(type));
                }
                // App chi ho tro 1 tai khoan dang nhap tai 1 thoi diem CHO MOI loai provider --
                // neu da co san 1 CloudRemote cung loai (tu lan dang nhap truoc), tai su dung lai
                // ID cu thay vi tao ID ngau nhien moi. Neu khong lam vay, moi lan (re)login se
                // tao them 1 ban ghi trung lap trong danh sach (xem CloudRemoteStore.save, chi
                // khu trung theo id).
                String existingId = null;
                for (CloudRemote r : CloudRemoteStore.getAll(getActivity())) {
                    if (r.getType() == type) {
                        existingId = r.getId();
                        break;
                    }
                }
                CloudRemote remote = new CloudRemote(
                        existingId != null ? existingId : java.util.UUID.randomUUID().toString(),
                        label, type);
                CloudRemoteStore.save(getActivity(), remote, null);
                notifySavedAndDismiss();
            } else if (result.getResultCode() == CloudOAuthActivity.RESULT_OAUTH_ERROR) {
                String message = result.getData() != null
                        ? result.getData().getStringExtra(CloudOAuthActivity.EXTRA_ERROR_MESSAGE)
                        : null;
                int failedReasonRes = loginFailedReasonStringRes(type);
                int failedRes = loginFailedStringRes(type);
                Toast.makeText(getActivity(),
                        message != null ? getString(failedReasonRes, message) : getString(failedRes),
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(getActivity(),
                        loginCancelledStringRes(type),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private int labelStringRes(CloudRemoteType type) {
        if (type == CloudRemoteType.GOOGLE_DRIVE) return R.string.cloud_type_google_drive;
        if (type == CloudRemoteType.ONEDRIVE) return R.string.cloud_card_onedrive;
        if (type == CloudRemoteType.BOX) return R.string.cloud_card_box;
        return R.string.cloud_card_dropbox;
    }

    private int loginFailedReasonStringRes(CloudRemoteType type) {
        if (type == CloudRemoteType.GOOGLE_DRIVE) return R.string.cloud_login_google_failed_reason;
        if (type == CloudRemoteType.ONEDRIVE) return R.string.cloud_login_onedrive_failed_reason;
        if (type == CloudRemoteType.BOX) return R.string.cloud_login_box_failed_reason;
        return R.string.cloud_login_dropbox_failed_reason;
    }

    private int loginFailedStringRes(CloudRemoteType type) {
        if (type == CloudRemoteType.GOOGLE_DRIVE) return R.string.cloud_login_google_failed;
        if (type == CloudRemoteType.ONEDRIVE) return R.string.cloud_login_onedrive_failed;
        if (type == CloudRemoteType.BOX) return R.string.cloud_login_box_failed;
        return R.string.cloud_login_dropbox_failed;
    }

    private int loginCancelledStringRes(CloudRemoteType type) {
        if (type == CloudRemoteType.GOOGLE_DRIVE) return R.string.cloud_login_google_cancelled;
        if (type == CloudRemoteType.ONEDRIVE) return R.string.cloud_login_onedrive_cancelled;
        if (type == CloudRemoteType.BOX) return R.string.cloud_login_box_cancelled;
        return R.string.cloud_login_dropbox_cancelled;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        LayoutInflater inflater = LayoutInflater.from(getActivity());
        View v = inflater.inflate(R.layout.dialog_add_cloud_remote, null);

        cardGoogleDrive = v.findViewById(R.id.cardGoogleDrive);
        cardOneDrive = v.findViewById(R.id.cardOneDrive);
        cardBox = v.findViewById(R.id.cardBox);
        cardDropbox = v.findViewById(R.id.cardDropbox);
        cardWebDav = v.findViewById(R.id.cardWebDav);
        cardFtp = v.findViewById(R.id.cardFtp);
        groupGoogleDrive = v.findViewById(R.id.groupGoogleDrive);
        groupOneDrive = v.findViewById(R.id.groupOneDrive);
        groupBox = v.findViewById(R.id.groupBox);
        groupDropbox = v.findViewById(R.id.groupDropbox);
        groupWebDavFtp = v.findViewById(R.id.groupWebDavFtp);
        tvGoogleDriveStatus = v.findViewById(R.id.tvGoogleDriveStatus);
        btnLoginGoogleDrive = v.findViewById(R.id.btnLoginGoogleDrive);
        tvOneDriveStatus = v.findViewById(R.id.tvOneDriveStatus);
        btnLoginOneDrive = v.findViewById(R.id.btnLoginOneDrive);
        tvBoxStatus = v.findViewById(R.id.tvBoxStatus);
        btnLoginBox = v.findViewById(R.id.btnLoginBox);
        tvDropboxStatus = v.findViewById(R.id.tvDropboxStatus);
        btnLoginDropbox = v.findViewById(R.id.btnLoginDropbox);
        tilHost = v.findViewById(R.id.tilHost);
        tilPort = v.findViewById(R.id.tilPort);
        tilUsername = v.findViewById(R.id.tilUsername);
        etDisplayName = v.findViewById(R.id.etDisplayName);
        etHost = v.findViewById(R.id.etHost);
        etPort = v.findViewById(R.id.etPort);
        etUsername = v.findViewById(R.id.etUsername);
        etPassword = v.findViewById(R.id.etPassword);
        etBasePath = v.findViewById(R.id.etBasePath);
        cbUseTls = v.findViewById(R.id.cbUseTls);
        tvTestResult = v.findViewById(R.id.tvTestResult);
        pbTestConnection = v.findViewById(R.id.pbTestConnection);
        btnTestConnection = v.findViewById(R.id.btnTestConnection);
        btnSave = v.findViewById(R.id.btnSave);

        cardGoogleDrive.setOnClickListener(view -> selectProvider(CloudRemoteType.GOOGLE_DRIVE));
        cardOneDrive.setOnClickListener(view -> selectProvider(CloudRemoteType.ONEDRIVE));
        cardBox.setOnClickListener(view -> selectProvider(CloudRemoteType.BOX));
        cardDropbox.setOnClickListener(view -> selectProvider(CloudRemoteType.DROPBOX));
        cardWebDav.setOnClickListener(view -> selectProvider(CloudRemoteType.WEBDAV));
        cardFtp.setOnClickListener(view -> selectProvider(CloudRemoteType.FTP));

        String preselect = getArguments() != null ? getArguments().getString(ARG_PRESELECT) : null;
        if (CloudRemoteType.GOOGLE_DRIVE.name().equals(preselect)) {
            selectProvider(CloudRemoteType.GOOGLE_DRIVE);
        } else if (CloudRemoteType.ONEDRIVE.name().equals(preselect)) {
            selectProvider(CloudRemoteType.ONEDRIVE);
        } else if (CloudRemoteType.BOX.name().equals(preselect)) {
            selectProvider(CloudRemoteType.BOX);
        } else if (CloudRemoteType.DROPBOX.name().equals(preselect)) {
            selectProvider(CloudRemoteType.DROPBOX);
        } else if (CloudRemoteType.FTP.name().equals(preselect)) {
            selectProvider(CloudRemoteType.FTP);
        } else {
            selectProvider(CloudRemoteType.WEBDAV);
        }

        tvGoogleDriveStatus.setText(R.string.cloud_login_google_hint);
        btnLoginGoogleDrive.setOnClickListener(view -> {
            pendingLoginType = CloudRemoteType.GOOGLE_DRIVE;
            loginLauncher.launch(CloudOAuthActivity.createIntent(getActivity(), CloudRemoteType.GOOGLE_DRIVE));
        });

        tvOneDriveStatus.setText(R.string.cloud_login_onedrive_hint);
        btnLoginOneDrive.setOnClickListener(view -> {
            pendingLoginType = CloudRemoteType.ONEDRIVE;
            loginLauncher.launch(CloudOAuthActivity.createIntent(getActivity(), CloudRemoteType.ONEDRIVE));
        });

        tvBoxStatus.setText(R.string.cloud_login_box_hint);
        btnLoginBox.setOnClickListener(view -> {
            pendingLoginType = CloudRemoteType.BOX;
            loginLauncher.launch(CloudOAuthActivity.createIntent(getActivity(), CloudRemoteType.BOX));
        });

        tvDropboxStatus.setText(R.string.cloud_login_dropbox_hint);
        btnLoginDropbox.setOnClickListener(view -> {
            pendingLoginType = CloudRemoteType.DROPBOX;
            loginLauncher.launch(CloudOAuthActivity.createIntent(getActivity(), CloudRemoteType.DROPBOX));
        });

        btnTestConnection.setOnClickListener(view -> testConnection());
        btnSave.setOnClickListener(view -> onSaveClick());

        return new AlertDialog.Builder(getActivity())
                .setTitle(R.string.cloud_add_remote)
                .setView(v)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
    }

    /** Marks `type`'s card as the only checked one and shows the matching form section below.
     * Called both from the cards' click listeners and to preselect a card on dialog open. */
    private void selectProvider(CloudRemoteType type) {
        selectedType = type;
        cardGoogleDrive.setChecked(type == CloudRemoteType.GOOGLE_DRIVE);
        cardOneDrive.setChecked(type == CloudRemoteType.ONEDRIVE);
        cardBox.setChecked(type == CloudRemoteType.BOX);
        cardDropbox.setChecked(type == CloudRemoteType.DROPBOX);
        cardWebDav.setChecked(type == CloudRemoteType.WEBDAV);
        cardFtp.setChecked(type == CloudRemoteType.FTP);

        boolean isGoogleDrive = type == CloudRemoteType.GOOGLE_DRIVE;
        boolean isOneDrive = type == CloudRemoteType.ONEDRIVE;
        boolean isBox = type == CloudRemoteType.BOX;
        boolean isDropbox = type == CloudRemoteType.DROPBOX;
        groupGoogleDrive.setVisibility(isGoogleDrive ? View.VISIBLE : View.GONE);
        groupOneDrive.setVisibility(isOneDrive ? View.VISIBLE : View.GONE);
        groupBox.setVisibility(isBox ? View.VISIBLE : View.GONE);
        groupDropbox.setVisibility(isDropbox ? View.VISIBLE : View.GONE);
        groupWebDavFtp.setVisibility((isGoogleDrive || isOneDrive || isBox || isDropbox) ? View.GONE : View.VISIBLE);
    }

    private boolean validate() {
        boolean ok = true;
        if (etHost.getText() == null || etHost.getText().toString().trim().isEmpty()) {
            tilHost.setError(getString(R.string.cloud_error_required));
            ok = false;
        } else {
            tilHost.setError(null);
        }
        if (etUsername.getText() == null || etUsername.getText().toString().trim().isEmpty()) {
            tilUsername.setError(getString(R.string.cloud_error_required));
            ok = false;
        } else {
            tilUsername.setError(null);
        }
        int port = parsePort();
        if (port < 1 || port > 65535) {
            tilPort.setError(getString(R.string.cloud_error_invalid_port));
            ok = false;
        } else {
            tilPort.setError(null);
        }
        return ok;
    }

    private int parsePort() {
        try {
            return Integer.parseInt(etPort.getText() == null ? "" : etPort.getText().toString().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private CloudRemote buildRemoteFromForm() {
        CloudRemoteType type = selectedType == CloudRemoteType.FTP ? CloudRemoteType.FTP : CloudRemoteType.WEBDAV;
        String displayNameText = etDisplayName.getText() == null ? "" : etDisplayName.getText().toString().trim();
        String displayName = displayNameText.isEmpty() ? etHost.getText().toString().trim() : displayNameText;
        CloudRemote remote = CloudRemote.newWithRandomId(displayName, type);
        remote.setHost(etHost.getText().toString().trim());
        remote.setPort(parsePort());
        remote.setUsername(etUsername.getText().toString().trim());
        remote.setUseTls(cbUseTls.isChecked());
        String basePath = etBasePath.getText() == null ? "" : etBasePath.getText().toString().trim();
        remote.setBasePath(basePath.isEmpty() ? "/" : basePath);
        return remote;
    }

    private void testConnection() {
        if (!validate()) return;
        CloudRemote remote = buildRemoteFromForm();
        String password = etPassword.getText() == null ? "" : etPassword.getText().toString();
        setTestingInProgress(true);
        executor.execute(() -> {
            boolean success;
            String errorMessage = null;
            CloudProvider provider = null;
            try {
                provider = CloudProviderFactory.create(getActivity().getApplicationContext(), remote, password);
                success = provider.testConnection();
            } catch (Exception e) {
                Logger.e(e);
                success = false;
                errorMessage = e.getMessage();
            } finally {
                if (provider != null) provider.close();
            }
            boolean finalSuccess = success;
            String finalError = errorMessage;
            mainHandler.post(() -> {
                setTestingInProgress(false);
                if (getActivity() == null) return;
                if (finalSuccess) {
                    tvTestResult.setText(R.string.cloud_test_connection_success);
                } else {
                    tvTestResult.setText(finalError != null
                            ? getString(R.string.cloud_test_connection_failed_reason, finalError)
                            : getString(R.string.cloud_test_connection_failed));
                }
            });
        });
    }

    private void setTestingInProgress(boolean inProgress) {
        pbTestConnection.setVisibility(inProgress ? View.VISIBLE : View.GONE);
        btnTestConnection.setEnabled(!inProgress);
        btnSave.setEnabled(!inProgress);
    }

    private void onSaveClick() {
        if (!validate()) return;
        CloudRemote remote = buildRemoteFromForm();
        String password = etPassword.getText() == null ? "" : etPassword.getText().toString();
        CloudRemoteStore.save(getActivity(), remote, password);
        notifySavedAndDismiss();
    }

    private void notifySavedAndDismiss() {
        if (getParentFragment() instanceof OnCloudRemoteSavedListener) {
            ((OnCloudRemoteSavedListener) getParentFragment()).onCloudRemoteSaved();
        } else if (getActivity() instanceof OnCloudRemoteSavedListener) {
            ((OnCloudRemoteSavedListener) getActivity()).onCloudRemoteSaved();
        }
        dismiss();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }
}
