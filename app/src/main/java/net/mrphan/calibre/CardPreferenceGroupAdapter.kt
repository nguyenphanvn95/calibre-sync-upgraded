package net.mrphan.calibre

import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder

/**
 * PreferenceGroupAdapter tuy bien: cac Preference lien tiep nhau (khong phai PreferenceCategory)
 * duoc gom thanh MOT the bo goc dung chung, giong cach anh Readest tham khao nhom cac hang duoi
 * cung mot tieu de ("Language", "Dictionaries", "Word Lens"...). Hang dau bo goc tren, hang cuoi
 * bo goc duoi, hang giua vuong va sat nhau (khong margin doc), co duong ke mo giua cac hang. Nhom
 * chi co 1 hang thi bo goc ca 4 canh (xem bg_app_settings_card_single.xml, dung cung the o Tang 1,
 * xem prompt-nang-cap-app-settings-card-ui.md muc 7).
 */
class CardPreferenceGroupAdapter(preferenceScreen: PreferenceScreen) :
    PreferenceGroupAdapter(preferenceScreen) {

    private val cardMarginPx by lazy {
        (6 * preferenceScreen.context.resources.displayMetrics.density).toInt()
    }
    private val sideMarginPx by lazy {
        (16 * preferenceScreen.context.resources.displayMetrics.density).toInt()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val preference = getItem(position) ?: return
        val itemView = holder.itemView

        if (preference is PreferenceCategory) {
            // Header thuan tuy: khong the, khong ripple, khong margin doc theo kieu card.
            itemView.background = null
            itemView.foreground = null
            return
        }

        val isFirst = position == 0 || getItem(position - 1) is PreferenceCategory
        val isLast = position == itemCount - 1 || getItem(position + 1) is PreferenceCategory

        val bgRes = when {
            isFirst && isLast -> R.drawable.bg_app_settings_card_single
            isFirst -> R.drawable.bg_app_settings_card_top
            isLast -> R.drawable.bg_app_settings_card_bottom
            else -> R.drawable.bg_app_settings_card_middle
        }
        itemView.setBackgroundResource(bgRes)

        val params = itemView.layoutParams as? MarginLayoutParams
        if (params != null) {
            params.leftMargin = sideMarginPx
            params.rightMargin = sideMarginPx
            params.topMargin = if (isFirst) cardMarginPx else 0
            params.bottomMargin = if (isLast) cardMarginPx else 0
            itemView.layoutParams = params
        }

        // Duong ke mong giua cac hang chung mot the: an o hang cuoi cung cua nhom de day the
        // khong bi ke, hien o cac hang con lai (xem layout_app_settings_card_preference.xml).
        val divider = itemView.findViewById<View>(R.id.app_settings_card_row_divider)
        divider?.visibility = if (isLast) View.GONE else View.VISIBLE
    }
}
