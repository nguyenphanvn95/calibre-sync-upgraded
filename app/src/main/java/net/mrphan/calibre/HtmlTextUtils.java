package net.mrphan.calibre;

import android.text.Html;
import android.text.TextUtils;

/**
 * Tien ich xu ly chuoi HTML tho lay tu truong "comments"/"summary" cua Calibre (OPF, OPDS feed).
 * Cac truong nay thuong chua vai the p/br don gian, can chuyen ve plain text truoc khi hien thi
 * tren cac TextView trong danh sach sach (List mode) va man chi tiet sach.
 */
public final class HtmlTextUtils {

    private HtmlTextUtils() {
    }

    /** Chuyen HTML tho thanh plain text, gop khoang trang thua, tra ve null neu rong sau khi
     * strip (de goi noi con GONE duoc TextView tuong ung nhu truoc). */
    public static String stripHtml(String html) {
        if (TextUtils.isEmpty(html)) return null;
        String text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString();
        // Calibre hay chen nhieu dong trong lien tiep giua cac doan p, gom lai con toi da 1 dong
        // trong giua 2 doan cho gon, giong cach Readest hien thi mo ta rut gon.
        text = text.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return TextUtils.isEmpty(text) ? null : text;
    }
}
