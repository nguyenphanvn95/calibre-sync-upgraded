package net.mrphan.calibre;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

/**
 * Helper dung chung cho BookDetailActivity va DeviceBookDetailActivity: 1 hang metadata co icon
 * dung truoc (giong calibre-web-companion), va 1 ChipGroup cho Tags thay vi 1 dong text noi dau
 * phay. Tach ra day thay vi copy trong tung Activity vi ca 2 man hinh can y het logic nay.
 */
public final class BookDetailIconRow {

    private BookDetailIconRow() {
    }

    /** Them 1 hang "icon + text" vao container neu formattedText khong rong, dung
     * view_book_detail_info_row.xml (mot TextView voi drawableStart). Tra ve false va KHONG them
     * gi neu formattedText rong, giong noi dung "cot khong co du lieu thi an di". */
    public static boolean addIconRow(LinearLayout container, @DrawableRes int iconRes, String formattedText) {
        if (TextUtils.isEmpty(formattedText)) return false;
        Context context = container.getContext();
        TextView row = (TextView) LayoutInflater.from(context)
                .inflate(R.layout.view_book_detail_info_row, container, false);
        row.setText(formattedText);
        row.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0);
        container.addView(row);
        return true;
    }

    /** Nhu addIconRow, nhung nhan resource id + 1 gia tri, tu ghep chuoi qua
     * getString(labelResId, value), de goi thay the truc tiep cho addRowIfPresent() cu ma khong
     * phai tu ghep chuoi o call site. */
    public static boolean addIconRow(LinearLayout container, @DrawableRes int iconRes,
                                      Context stringContext, @StringRes int labelResId, String value) {
        if (TextUtils.isEmpty(value)) return false;
        return addIconRow(container, iconRes, stringContext.getString(labelResId, value));
    }

    /** Them 1 ChipGroup hien thi moi tag nhu 1 chip bo tron rieng (giong calibre-web-companion),
     * thay vi mot dong text "tag1, tag2, tag3" noi dau phay. Tra ve false va khong them gi neu
     * mang tags rong. Chip khong the bam (khong co OnClickListener), chi de hien thi. */
    public static boolean addTagsChipRow(LinearLayout container, String[] tags) {
        if (tags == null || tags.length == 0) return false;
        Context context = container.getContext();
        ChipGroup chipGroup = new ChipGroup(context);
        chipGroup.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        int paddingPx = Math.round(2 * context.getResources().getDisplayMetrics().density);
        chipGroup.setPadding(0, paddingPx, 0, paddingPx);
        boolean any = false;
        for (String tag : tags) {
            if (TextUtils.isEmpty(tag)) continue;
            Chip chip = new Chip(context);
            chip.setText(tag);
            chip.setClickable(false);
            chip.setCheckable(false);
            chip.setChipBackgroundColorResource(android.R.color.transparent);
            chip.setChipStrokeWidth(1.5f);
            chipGroup.addView(chip);
            any = true;
        }
        if (!any) return false;
        container.addView(chipGroup);
        return true;
    }
}
