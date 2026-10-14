package net.mrphan.calibre;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/** Lists the contents of the current folder in CloudBrowserActivity. Only shows a download
 * progress bar for the one file currently being downloaded (set via setDownloading()); every
 * other row shows its file size instead. */
public class CloudFileAdapter extends RecyclerView.Adapter<CloudFileAdapter.ViewHolder> {

    public interface OnFileClickListener {
        void onFileClick(CloudFile file);
    }

    private final List<CloudFile> items;
    private final OnFileClickListener clickListener;
    private String downloadingRefId;
    private int downloadingProgress;

    public CloudFileAdapter(List<CloudFile> items, OnFileClickListener clickListener) {
        this.items = items;
        this.clickListener = clickListener;
    }

    public void setDownloading(String refId, int progressPercent) {
        this.downloadingRefId = refId;
        this.downloadingProgress = progressPercent;
        notifyDataSetChanged();
    }

    public void clearDownloading() {
        this.downloadingRefId = null;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_cloud_file, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        CloudFile file = items.get(position);
        holder.name.setText(file.getName());
        holder.icon.setImageResource(file.isDirectory()
                ? R.drawable.ic_provider_webdav
                : R.drawable.ic_provider_local_storage);

        if (file.getRefId().equals(downloadingRefId)) {
            holder.progress.setVisibility(View.VISIBLE);
            holder.progress.setIndeterminate(downloadingProgress < 0);
            if (downloadingProgress >= 0) holder.progress.setProgress(downloadingProgress);
            holder.meta.setText(R.string.cloud_downloading);
        } else {
            holder.progress.setVisibility(View.GONE);
            holder.meta.setText(file.isDirectory() ? "" : FileNameUtils.formatFileSize(file.getSizeBytes()));
        }

        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) clickListener.onFileClick(file);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final TextView meta;
        final ProgressBar progress;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.ivFileIcon);
            name = itemView.findViewById(R.id.tvFileName);
            meta = itemView.findViewById(R.id.tvFileMeta);
            progress = itemView.findViewById(R.id.pbFileProgress);
        }
    }
}
