package net.mrphan.calibre;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.text.NumberFormat;
import java.util.List;

/**
 * Renders each OPDSLibraryBlock on the "choose a library" screen as a card (icon badge, name,
 * and a book-count subtitle once known -- see item_library_card.xml) instead of the old plain
 * android.R.layout.simple_list_item_1 text row.
 *
 * Deliberately a plain BaseAdapter (not ArrayAdapter) since ArrayAdapter's built-in filtering/
 * toString() row rendering isn't used here -- this fully custom-inflates its own row layout.
 * Still handed straight to GridView#setAdapter exactly like the ArrayAdapter it replaces (see
 * OPDSBrowserFragment#renderLibrarySelection); item click handling there is unchanged, since it
 * already resolves taps by position within mLibraries rather than by adapter class/item type.
 */
class LibraryCardAdapter extends BaseAdapter {

    private final Context mContext;
    private final List<OPDSLibraryBlock> mLibraries;
    private final LayoutInflater mInflater;

    LibraryCardAdapter(Context context, List<OPDSLibraryBlock> libraries) {
        mContext = context;
        mLibraries = libraries;
        mInflater = LayoutInflater.from(context);
    }

    @Override
    public int getCount() {
        return mLibraries.size();
    }

    @Override
    public OPDSLibraryBlock getItem(int position) {
        return mLibraries.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    private static class ViewHolder {
        TextView name;
        TextView subtitle;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = mInflater.inflate(R.layout.item_library_card, parent, false);
            holder = new ViewHolder();
            holder.name = (TextView) convertView.findViewById(R.id.library_card_name);
            holder.subtitle = (TextView) convertView.findViewById(R.id.library_card_subtitle);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        OPDSLibraryBlock library = getItem(position);
        holder.name.setText(library.name);
        String subtitle = subtitleFor(library);
        if (subtitle == null) {
            holder.subtitle.setVisibility(View.GONE);
        } else {
            holder.subtitle.setVisibility(View.VISIBLE);
            holder.subtitle.setText(subtitle);
        }
        return convertView;
    }

    /** @return the subtitle text for a library's current bookCount state, or null to hide the
     * subtitle entirely (server never reported a count -- see OPDSLibraryStatsLoader). */
    private String subtitleFor(OPDSLibraryBlock library) {
        if (library.bookCount == OPDSLibraryBlock.COUNT_LOADING) {
            return mContext.getString(R.string.opds_library_count_loading);
        }
        if (library.bookCount == OPDSLibraryBlock.COUNT_UNKNOWN) {
            return null;
        }
        String formatted = NumberFormat.getIntegerInstance().format(library.bookCount);
        return mContext.getResources().getQuantityString(
                R.plurals.opds_library_book_count, library.bookCount, formatted);
    }
}
