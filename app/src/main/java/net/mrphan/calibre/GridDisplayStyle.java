package net.mrphan.calibre;

import android.content.SharedPreferences;

/**
 * 3 kieu hien thi cho che do Grid (Device Library + Calibre Library OPDS): Default (nhu hien
 * tai), Bookshelf (gia sach go, chi hien bia, khong hien tua/tien trinh) va Simple2 (bia
 * full-bleed + tua overlay + badge dinh dang). Dung chung cho BookRecyclerAdapter (Device
 * Library, RecyclerView) va BookCursorAdapter (Calibre Library/OPDS, GridView) de 2 tab luon
 * dong bo cung 1 lua chon trong Settings > Giao dien.
 * <p>
 * Gia tri luu trong SharedPreferences la String (key StorageUtils.PREF_GRID_DISPLAY_STYLE) de
 * tuong thich truc tiep voi ListPreference (entryValues la mang String).
 */
public enum GridDisplayStyle {
    DEFAULT("default"),
    BOOKSHELF("bookshelf"),
    SIMPLE2("simple2");

    public final String prefValue;

    GridDisplayStyle(String prefValue) {
        this.prefValue = prefValue;
    }

    public static GridDisplayStyle fromPrefValue(String v) {
        if (v == null) return DEFAULT;
        for (GridDisplayStyle s : values()) {
            if (s.prefValue.equals(v)) return s;
        }
        return DEFAULT;
    }

    /** Doc truc tiep tu SharedPreferences, dung o ca CollectionFragment lan OPDSBrowserFragment. */
    public static GridDisplayStyle readFrom(SharedPreferences settings) {
        return fromPrefValue(settings.getString(StorageUtils.PREF_GRID_DISPLAY_STYLE, "default"));
    }
}
