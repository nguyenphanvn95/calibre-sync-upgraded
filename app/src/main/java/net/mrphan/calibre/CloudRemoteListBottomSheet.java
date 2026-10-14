package net.mrphan.calibre;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.List;

/** Lists the WebDAV/FTP remotes the user has already added, with Browse/Edit/Delete per row
 * (via a PopupMenu, item_cloud_remote.xml) and a footer row to add another. Shown from the
 * "WebDAV / FTP / NAS" card when at least one remote already exists. */
public class CloudRemoteListBottomSheet extends BottomSheetDialogFragment
        implements AddCloudRemoteDialogFragment.OnCloudRemoteSavedListener {

    private RecyclerView rvRemotes;
    private RemoteAdapter adapter;

    public static CloudRemoteListBottomSheet newInstance() {
        return new CloudRemoteListBottomSheet();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.bottomsheet_cloud_remote_list, container, false);
        rvRemotes = v.findViewById(R.id.rvRemotes);
        rvRemotes.setLayoutManager(new LinearLayoutManager(getActivity()));
        refreshList();

        TextView tvAddAnother = v.findViewById(R.id.tvAddAnother);
        tvAddAnother.setOnClickListener(view ->
                AddCloudRemoteDialogFragment.newInstance().show(getParentFragmentManager(), "add_cloud_remote"));

        return v;
    }

    private void refreshList() {
        if (getActivity() == null) return;
        List<CloudRemote> all = CloudRemoteStore.getAll(getActivity());
        java.util.List<CloudRemote> filtered = new java.util.ArrayList<>();
        for (CloudRemote r : all) {
            if (r.getType() == CloudRemoteType.WEBDAV || r.getType() == CloudRemoteType.FTP) filtered.add(r);
        }
        adapter = new RemoteAdapter(filtered);
        rvRemotes.setAdapter(adapter);
    }

    @Override
    public void onCloudRemoteSaved() {
        refreshList();
        // Also bubble up so ConnectionFragment's card subtitle ("{n} máy chủ đã thêm") updates.
        if (getParentFragment() instanceof AddCloudRemoteDialogFragment.OnCloudRemoteSavedListener) {
            ((AddCloudRemoteDialogFragment.OnCloudRemoteSavedListener) getParentFragment()).onCloudRemoteSaved();
        }
    }

    private void openBrowse(CloudRemote remote) {
        Intent intent = new Intent(getActivity(), CloudBrowserActivity.class);
        intent.putExtra(CloudBrowserActivity.EXTRA_REMOTE_ID, remote.getId());
        startActivity(intent);
    }

    private void openEdit(CloudRemote remote) {
        // Editing reuses the add dialog pre-filled would need extra plumbing; for now, deleting
        // and re-adding is the supported flow, so route "Edit" to the same add dialog with the
        // right type preselected (host/user/etc are re-entered).
        AddCloudRemoteDialogFragment.newInstancePreselected(remote.getType())
                .show(getParentFragmentManager(), "add_cloud_remote");
    }

    private void confirmDelete(CloudRemote remote) {
        new AlertDialog.Builder(getActivity())
                .setMessage(getString(R.string.cloud_confirm_delete_remote, remote.getDisplayName()))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    CloudRemoteStore.delete(getActivity(), remote.getId());
                    refreshList();
                    onCloudRemoteSaved();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private class RemoteAdapter extends RecyclerView.Adapter<RemoteAdapter.VH> {
        private final List<CloudRemote> items;

        RemoteAdapter(List<CloudRemote> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_cloud_remote, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            CloudRemote remote = items.get(position);
            holder.name.setText(remote.getDisplayName());
            holder.type.setText(remote.getType() == CloudRemoteType.FTP
                    ? getString(R.string.cloud_type_ftp) : getString(R.string.cloud_type_webdav));
            holder.itemView.setOnClickListener(v -> openBrowse(remote));
            holder.menuButton.setOnClickListener(v -> {
                PopupMenu popup = new PopupMenu(getActivity(), holder.menuButton);
                popup.getMenu().add(0, 1, 0, R.string.cloud_action_browse);
                popup.getMenu().add(0, 2, 1, R.string.cloud_action_edit);
                popup.getMenu().add(0, 3, 2, R.string.cloud_action_delete);
                popup.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == 1) openBrowse(remote);
                    else if (item.getItemId() == 2) openEdit(remote);
                    else if (item.getItemId() == 3) confirmDelete(remote);
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
            final TextView name, type;
            final View menuButton;

            VH(@NonNull View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.tvRemoteName);
                type = itemView.findViewById(R.id.tvRemoteType);
                menuButton = itemView.findViewById(R.id.btnRemoteMenu);
            }
        }
    }
}
