package net.mrphan.calibre;

import android.view.View;

/**
 * Duoc 3 fragment chinh (ConnectionFragment, CollectionFragment, OPDSBrowserFragment) implement
 * de MainActivity dieu khien header dung chung (app_header.xml) thay vi moi fragment tu ve
 * toolbar rieng qua onCreateOptionsMenu(). Cac fragment van giu nguyen setHasOptionsMenu(true)
 * va onCreateOptionsMenu/onOptionsItemSelected cua chinh minh (dung lai cho PopupMenu cua nut 3
 * cham, xem onHeaderOverflowClick) -- interface nay chi la lop wiring moi, khong thay the logic cu.
 */
public interface HeaderContentProvider {

    /** Tieu de hien o dong tren cua header, vi du "One's Custom", "Ket noi". */
    String getHeaderTitle();

    /** Tra ve null/rong neu khong can hien dong phu (vi du Connection). */
    String getHeaderCountLabel();

    /** false thi an han nut search tren header cho trang nay. */
    boolean supportsHeaderSearch();

    /** Goi khi nguoi dung nhap/submit tu khoa trong o search cua header. */
    void onHeaderSearchQuery(String query);

    /** Goi khi nguoi dung bam nut refresh tren header. */
    void onHeaderRefresh();

    /**
     * Goi khi nguoi dung bam nut 3 cham. Trien khai don gian nhat: inflate lai dung menu XML
     * fragment dang dung (vd R.menu.collection_fragment) vao 1 PopupMenu neo tai anchorView, roi
     * forward item duoc chon sang onOptionsItemSelected() cua chinh fragment.
     */
    void onHeaderOverflowClick(View anchorView);
}
