package net.mrphan.calibre;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.List;

/**
 * Ban sao cua LibraryCardAdapter nhung cho danh sach GDriveOpdsLibrary (thu vien Calibre da luu
 * tren Google Drive), dung khi OPDSBrowserFragment khong ket noi duoc toi Content Server that va
 * chuyen sang man hinh "duyet thu vien online" (xem
 * OPDSBrowserFragment#renderOfflineGDriveLibraries). Tai su dung dung layout item_library_card.xml
 * de giu nguyen style card cua man hinh "Chon thu vien" that (LibraryCardAdapter), chi khac nguon
 * du lieu va cach lay subtitle (cachedBookCount, khong phai bookCount dang tai truc tiep tu mang).
 */
class GDriveOpdsLibraryCardAdapter extends BaseAdapter {

    private final Context mContext;
    private final List<GDriveOpdsLibrary> mLibraries;
    private final LayoutInflater mInflater;

    GDriveOpdsLibraryCardAdapter(Context context, List<GDriveOpdsLibrary> libraries) {
        mContext = context;
        mLibraries = libraries;
        mInflater = LayoutInflater.from(context);
    }

    @Override
    public int getCount() {
        return mLibraries.size();
    }

    @Override
    public GDriveOpdsLibrary getItem(int position) {
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

        GDriveOpdsLibrary library = getItem(position);
        holder.name.setText(library.getDisplayName());
        int count = library.getCachedBookCount();
        if (count < 0) {
            holder.subtitle.setVisibility(View.GONE);
        } else {
            holder.subtitle.setVisibility(View.VISIBLE);
            String formatted = java.text.NumberFormat.getIntegerInstance().format(count);
            holder.subtitle.setText(mContext.getResources()
                    .getQuantityString(R.plurals.opds_library_book_count, count, formatted));
        }
        return convertView;
    }
}
