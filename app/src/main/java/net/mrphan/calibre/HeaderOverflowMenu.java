package net.mrphan.calibre;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.util.HashSet;
import java.util.Set;

/**
 * Popup "3 cham" tuy chinh, thay the androidx.appcompat.widget.PopupMenu#show() mac dinh dang
 * dung trong ConnectionFragment/CollectionFragment/OPDSBrowserFragment#onHeaderOverflowClick().
 *
 * Ly do can class rieng: PopupMenu he thong LUON AN icon cua moi MenuItem (MenuPopupHelper ep
 * showIcon=false) va KHONG ve vach ngang phan chia giua cac <group> khac nhau, du menu XML co
 * khai bao day du android:icon / nhieu group id rieng biet. HeaderOverflowMenu doc lai dung doi
 * tuong Menu da duoc inflate/tuy chinh visibility san (vd sau khi an muc menu_search trung voi
 * nut rieng tren header), tu ve 1 PopupWindow rieng (the CardView bo goc + do bong, xem
 * popup_header_overflow.xml) hien icon + tieu de moi dong, va tu chen 1 vach ngang
 * (item_header_overflow_divider.xml) moi khi group id doi khac dong lien truoc do. Lay cam hung
 * tu menu 3 cham cua Edge mobile: nhom chuc nang chinh hien o tren, tach vach roi den nhom
 * "khac" (vd dong bo, tac vu nen, ngat ket noi) o duoi.
 *
 * API co tinh giu nguyen chu ky cua PopupMenu (inflate menu tu ben ngoai truyen vao qua
 * constructor, setOnMenuItemClickListener, show(anchor)) de cac call site hien co chi can doi 2
 * dong cuoi, khong phai viet lai logic inflate/an muc nao ca.
 */
public class HeaderOverflowMenu {

    /** Cung chu ky voi androidx.appcompat.widget.PopupMenu.OnMenuItemClickListener de forward
     * thang sang onOptionsItemSelected() hien co cua tung fragment, khong doi logic ben trong. */
    public interface OnMenuItemClickListener {
        boolean onMenuItemClick(@NonNull MenuItem item);
    }

    private final Context context;
    private final Menu menu;
    private final Set<Integer> destructiveItemIds = new HashSet<>();
    private OnMenuItemClickListener listener;

    public HeaderOverflowMenu(@NonNull Context context, @NonNull Menu menu) {
        this.context = context;
        this.menu = menu;
    }

    /** Cac MenuItem id se duoc to icon + tieu de mau canh bao (vd "Ngat ket noi", "Xoa"), goi
     * truoc show(). Thuong la muc nam o nhom cuoi cung (sau vach chia). */
    public HeaderOverflowMenu setDestructiveItemIds(int... ids) {
        destructiveItemIds.clear();
        for (int id : ids) destructiveItemIds.add(id);
        return this;
    }

    public void setOnMenuItemClickListener(OnMenuItemClickListener listener) {
        this.listener = listener;
    }

    /** Dung y het cach goi androidx.appcompat.widget.PopupMenu#show(): neo ngay duoi anchor
     * (thuong la btn_header_overflow), canh phai popup trung canh phai anchor. */
    public void show(@NonNull View anchor) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View card = inflater.inflate(R.layout.popup_header_overflow, null, false);
        LinearLayout list = card.findViewById(R.id.header_overflow_list);

        int popupWidth = context.getResources().getDimensionPixelSize(R.dimen.header_overflow_popup_width);
        final PopupWindow popupWindow = new PopupWindow(card, popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popupWindow.setOutsideTouchable(true);
        // Mac dinh PopupWindow tu ve them 1 khung nen he thong (panel background) neu khong dat
        // rieng, se de len va che mat goc bo tron cua CardView ben trong -- dat trong suot de chi
        // con lai bo goc + do bong that su cua card.
        popupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popupWindow.setElevation(dpToPx(8));

        int normalTextColor = resolveThemeColor(android.R.attr.textColorPrimary);
        int iconTintColor = resolveThemeColor(androidx.appcompat.R.attr.colorControlNormal);
        int destructiveColor = ContextCompat.getColor(context, R.color.color_action_destructive);

        int previousGroupId = 0;
        boolean first = true;
        boolean anyRowAdded = false;

        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            if (!item.isVisible() || item.hasSubMenu()) continue;

            if (!first && item.getGroupId() != previousGroupId) {
                list.addView(inflater.inflate(R.layout.item_header_overflow_divider, list, false));
            }
            first = false;
            previousGroupId = item.getGroupId();
            anyRowAdded = true;

            View row = inflater.inflate(R.layout.item_header_overflow_entry, list, false);
            ImageView iconView = row.findViewById(R.id.header_overflow_icon);
            TextView titleView = row.findViewById(R.id.header_overflow_title);

            boolean destructive = destructiveItemIds.contains(item.getItemId());
            Drawable icon = item.getIcon();
            if (icon != null) {
                iconView.setImageDrawable(icon);
                // Bo qua tint/fillColor co san trong file drawable (mot so icon menu cu hardcode
                // android:tint="#FFFFFF", chi hop voi nen toi) -- luon ep 1 mau trung tinh duy
                // nhat cho ca popup, giong cach btn_header_overflow tu dong doi mau qua
                // ?attr/colorControlNormal theo Sang/Toi.
                iconView.setColorFilter(destructive ? destructiveColor : iconTintColor, PorterDuff.Mode.SRC_IN);
                iconView.setVisibility(View.VISIBLE);
            } else {
                iconView.setVisibility(View.INVISIBLE);
            }
            titleView.setText(item.getTitle());
            titleView.setTextColor(destructive ? destructiveColor : normalTextColor);

            row.setOnClickListener(v -> {
                popupWindow.dismiss();
                if (listener != null) listener.onMenuItemClick(item);
            });

            list.addView(row);
        }

        if (!anyRowAdded) return; // khong co muc nao de hien, tranh mo 1 popup rong

        int xOff = anchor.getWidth() - popupWidth; // canh phai popup trung canh phai nut 3 cham
        int yOff = dpToPx(2);
        popupWindow.showAsDropDown(anchor, xOff, yOff);
    }

    private int resolveThemeColor(int attr) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(attr, value, true);
        // BUG DA SUA: nhieu attr theme (dac biet android:attr/textColorPrimary) tro toi 1
        // ColorStateList (doi mau theo enabled/pressed/...), khong phai 1 mau don. Truoc day code
        // lay thang value.data trong moi truong hop -- voi ColorStateList, data KHONG phai mau
        // ARGB hop le (thuong ra 0 = trong suot hoan toan), khien ca icon lan tieu de trong popup
        // bien mat het (chi con vach chia nhom hien vi no dung mau hardcode rieng, khong qua ham
        // nay) du kich thuoc/vi tri popup van dung. Neu resolveAttribute tra ve 1 resourceId that
        // su (nghia la tro toi 1 resource mau, du la mau don hay ColorStateList), phai doc lai
        // qua ContextCompat.getColor() de lay dung mau mac dinh (trang thai "normal") cua no.
        if (value.resourceId != 0) {
            return ContextCompat.getColor(context, value.resourceId);
        }
        return value.data;
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                context.getResources().getDisplayMetrics());
    }
}
