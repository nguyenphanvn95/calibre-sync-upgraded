package net.mrphan.calibre;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;

import java.io.File;

/**
 * "Send to E-Reader" flow: shows the send2ereader instance URL (so the user can open it in
 * their e-reader's own browser and get a pairing key), an input for that key, and a Send
 * button. Mirrors the pairing-key handshake send2ereader itself requires -- see
 * Send2EreaderClient's class Javadoc for the full protocol explanation.
 * <p>
 * The caller passes in a File that is already fully ready to upload -- DeviceBookDetailActivity
 * passes the on-disk library file directly, while BookDetailActivity downloads a throwaway
 * temp copy into cache dir first (a Calibre Library / OPDS entry may not have a local file
 * yet). Either way, this fragment itself never downloads anything, it only uploads.
 * <p>
 * If deleteFileAfterUse is set, the backing file is deleted once this dialog is dismissed
 * (success or failure/cancel) -- used by BookDetailActivity for its temp download copy, so
 * cache dir doesn't accumulate throwaway files. DeviceBookDetailActivity leaves this false,
 * since its File is the actual permanent library copy on disk.
 */
public class SendToEreaderDialogFragment extends DialogFragment {

    private static final String ARG_FILE_PATH = "file_path";
    private static final String ARG_DISPLAY_NAME = "display_name";
    private static final String ARG_MIME_TYPE = "mime_type";
    private static final String ARG_DELETE_AFTER_USE = "delete_after_use";

    public static SendToEreaderDialogFragment newInstance(File file, String displayName, String mimeType) {
        return newInstance(file, displayName, mimeType, false);
    }

    public static SendToEreaderDialogFragment newInstance(File file, String displayName, String mimeType,
                                                            boolean deleteFileAfterUse) {
        SendToEreaderDialogFragment fragment = new SendToEreaderDialogFragment();
        Bundle args = new Bundle();
        args.putString(ARG_FILE_PATH, file.getAbsolutePath());
        args.putString(ARG_DISPLAY_NAME, displayName);
        args.putString(ARG_MIME_TYPE, mimeType);
        args.putBoolean(ARG_DELETE_AFTER_USE, deleteFileAfterUse);
        fragment.setArguments(args);
        return fragment;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_send_to_ereader, null, false);

        TextView instanceUrlView = view.findViewById(R.id.s2e_instance_url);
        EditText keyInput = view.findViewById(R.id.s2e_key_input);
        Button openBrowserButton = view.findViewById(R.id.s2e_btn_open_browser);
        View progress = view.findViewById(R.id.s2e_progress);

        String baseUrl = Send2EreaderClient.getBaseUrl(requireContext());
        instanceUrlView.setText(getString(R.string.s2e_instance_hint, baseUrl));
        openBrowserButton.setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(baseUrl))));

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.bookdetail_send_to_ereader)
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.s2e_btn_send, null) // set below to avoid auto-dismiss
                .create();

        dialog.setOnShowListener(d -> {
            // UI nang cap: doi 2 nut mac dinh (deu chi la text button phang) thanh Cancel mau
            // xam nhat + Send la 1 pill xanh dac, giong bo doi nut o duoi anh mau "Send to
            // Kindle/Kobo" thay vi 2 chu Huy/Gui tran nhu truoc.
            Button cancelButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            cancelButton.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.darker_gray));

            Button sendButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            sendButton.setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.bg_button_pill_send));
            sendButton.setTextColor(Color.WHITE);
            sendButton.setAllCaps(false);
            float density = requireContext().getResources().getDisplayMetrics().density;
            int hPad = Math.round(20 * density);
            int vPad = Math.round(8 * density);
            sendButton.setPadding(hPad, vPad, hPad, vPad);
            ((ViewGroup.MarginLayoutParams) sendButton.getLayoutParams()).leftMargin = Math.round(8 * density);

            sendButton.setOnClickListener(v -> {
                String key = keyInput.getText().toString().trim();
                if (TextUtils.isEmpty(key)) {
                    keyInput.setError(getString(R.string.s2e_error_key_required));
                    return;
                }
                sendButton.setEnabled(false);
                sendButton.setAlpha(0.5f);
                progress.setVisibility(View.VISIBLE);

                Bundle args = getArguments();
                File file = new File(args.getString(ARG_FILE_PATH));
                String displayName = args.getString(ARG_DISPLAY_NAME);
                String mimeType = args.getString(ARG_MIME_TYPE);

                Send2EreaderClient.uploadFile(requireContext(), key, file, displayName, mimeType,
                        new Send2EreaderClient.Callback() {
                    @Override
                    public void onSuccess() {
                        if (!isAdded()) return;
                        Toast.makeText(requireContext(), R.string.s2e_success, Toast.LENGTH_LONG).show();
                        dismiss();
                    }

                    @Override
                    public void onFailure(String message) {
                        if (!isAdded()) return;
                        sendButton.setEnabled(true);
                        sendButton.setAlpha(1f);
                        progress.setVisibility(View.GONE);
                        Toast.makeText(requireContext(),
                                getString(R.string.s2e_error_upload_failed, message), Toast.LENGTH_LONG).show();
                    }
                });
            });
        });

        return dialog;
    }

    /** Cleans up the caller's throwaway temp copy (if any) regardless of whether the dialog was
     * cancelled, dismissed after a successful send, or the activity is simply going away --
     * onDismiss fires in all of those cases. A file the caller flagged as permanent (the Device
     * Library case) is left untouched. */
    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialogInterface) {
        super.onDismiss(dialogInterface);
        Bundle args = getArguments();
        if (args == null || !args.getBoolean(ARG_DELETE_AFTER_USE, false)) return;
        String path = args.getString(ARG_FILE_PATH);
        if (TextUtils.isEmpty(path)) return;
        File file = new File(path);
        if (file.exists() && !file.delete()) {
            Logger.d("SendToEreaderDialogFragment: could not delete temp file " + path);
        }
    }
}
