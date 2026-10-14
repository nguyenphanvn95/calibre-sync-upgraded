package net.mrphan.calibre;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * Danh sach cac CloudRemote (Google Drive va/hoac OneDrive) de nguoi dung chon khi co
 * NHIEU HON 1 remote da ket noi -- xem CollectionFragment#syncSelectedToCloud. Voi hien trang
 * app chi ho tro 1 tai khoan cho MOI loai provider dang nhap tai 1 thoi diem (xem
 * CloudTokenManager), dialog nay hien ra khi nguoi dung da dang nhap CA Google Drive LAN OneDrive
 * cung luc.
 * <p>
 * Truoc day con co che do "download" (newInstanceForDownload(), dung cho muc menu rieng "Dong bo
 * tu cloud") -- da bo di vi trung chuc nang voi nut "Dong bo" (menu_auto_sync_now, dong bo 2
 * chieu ca upload lan download trong 1 lan chay) da co san trong overflow menu Device Library,
 * xem CollectionFragment#triggerActiveFullSyncFromMenu.
 */
public class CloudRemotePickerDialogFragment extends DialogFragment {

    private static final String ARG_REMOTE_IDS = "remote_ids";
    private static final String ARG_REMOTE_NAMES = "remote_names";
    private static final String ARG_UUIDS = "uuids";

    public static CloudRemotePickerDialogFragment newInstanceForUpload(List<CloudRemote> remotes, String[] uuids) {
        CloudRemotePickerDialogFragment f = new CloudRemotePickerDialogFragment();
        Bundle args = argsFor(remotes);
        args.putStringArray(ARG_UUIDS, uuids);
        f.setArguments(args);
        return f;
    }

    private static Bundle argsFor(List<CloudRemote> remotes) {
        Bundle args = new Bundle();
        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        for (CloudRemote r : remotes) {
            ids.add(r.getId());
            names.add(r.getDisplayName());
        }
        args.putStringArrayList(ARG_REMOTE_IDS, ids);
        args.putStringArrayList(ARG_REMOTE_NAMES, names);
        return args;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Bundle args = requireArguments();
        List<String> ids = args.getStringArrayList(ARG_REMOTE_IDS);
        List<String> names = args.getStringArrayList(ARG_REMOTE_NAMES);
        String[] uuids = args.getStringArray(ARG_UUIDS);
        CharSequence[] items = names == null ? new CharSequence[0] : names.toArray(new CharSequence[0]);

        return new AlertDialog.Builder(requireActivity())
                .setTitle(R.string.action_sync_to_cloud)
                .setItems(items, (DialogInterface dialog, int which) -> {
                    if (ids == null || which < 0 || which >= ids.size() || getActivity() == null) return;
                    String remoteId = ids.get(which);
                    DeviceLibrarySyncScheduler.enqueueUpload(getActivity(), remoteId, uuids);
                    android.widget.Toast.makeText(getActivity(), R.string.sync_started, android.widget.Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.dialog_button_cancel, null)
                .create();
    }
}
