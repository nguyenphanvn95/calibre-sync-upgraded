package net.mrphan.calibre;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/** Binds the fixed 9-item Cloud Libraries provider card list. Subtitles are supplied by the
 * host (ConnectionFragment) via SubtitleProvider rather than queried here, so this adapter
 * stays free of any DB/EncryptedSharedPreferences access. */
public class ProviderCardAdapter extends RecyclerView.Adapter<ProviderCardAdapter.ViewHolder> {

    public interface SubtitleProvider {
        String getSubtitle(ProviderCardType type);
    }

    public interface OnProviderCardClickListener {
        void onProviderCardClick(ProviderCardItem item);
    }

    /** Optional long-press hook, currently only used by the "Google Drive" card to offer a
     * "Disconnect" action without needing a separate menu/settings screen. */
    public interface OnProviderCardLongClickListener {
        /** @return true if the long-press was handled (suppresses the default click ripple's
         * long-press feedback), false to let the view handle it as normal (no-op). */
        boolean onProviderCardLongClick(ProviderCardItem item);
    }

    private final List<ProviderCardItem> items;
    private final SubtitleProvider subtitleProvider;
    private final OnProviderCardClickListener clickListener;
    private final OnProviderCardLongClickListener longClickListener;

    public ProviderCardAdapter(List<ProviderCardItem> items, SubtitleProvider subtitleProvider,
                                OnProviderCardClickListener clickListener) {
        this(items, subtitleProvider, clickListener, null);
    }

    public ProviderCardAdapter(List<ProviderCardItem> items, SubtitleProvider subtitleProvider,
                                OnProviderCardClickListener clickListener,
                                OnProviderCardLongClickListener longClickListener) {
        this.items = items;
        this.subtitleProvider = subtitleProvider;
        this.clickListener = clickListener;
        this.longClickListener = longClickListener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_provider_card, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ProviderCardItem item = items.get(position);
        holder.icon.setImageResource(item.iconRes);
        holder.title.setText(item.titleRes);

        if (item.comingSoon) {
            holder.subtitle.setText(R.string.cloud_coming_soon_subtitle);
            holder.subtitle.setVisibility(View.VISIBLE);
            holder.badge.setVisibility(View.GONE);
        } else {
            String subtitle = subtitleProvider != null ? subtitleProvider.getSubtitle(item.type) : null;
            if (subtitle == null || subtitle.isEmpty()) {
                holder.subtitle.setVisibility(View.GONE);
            } else {
                holder.subtitle.setText(subtitle);
                holder.subtitle.setVisibility(View.VISIBLE);
            }
            holder.badge.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) clickListener.onProviderCardClick(item);
        });
        holder.itemView.setOnLongClickListener(v ->
                longClickListener != null && longClickListener.onProviderCardLongClick(item));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView subtitle;
        final TextView badge;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.ivProviderIcon);
            title = itemView.findViewById(R.id.tvProviderTitle);
            subtitle = itemView.findViewById(R.id.tvProviderSubtitle);
            badge = itemView.findViewById(R.id.tvProviderBadge);
        }
    }
}
