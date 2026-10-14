package net.mrphan.calibre;

import java.util.Locale;

/**
 * Suy ra nhan dinh dang sach (vd "EPUB", "PDF", "CBZ") tu phan mo rong file trong cot lpath
 * (Book.LPATH) - dung chung cho Device Library (duong dan tuong doi tren may) va Calibre
 * Library/OPDS (OPDSEntry.href, cung duoc nhoi vao cot lpath, xem OPDSPage#addBook). Chi dung
 * cho badge dinh dang o style Grid "Simple 2". Tra ve null neu khong nhan dang duoc, cac cho
 * goi phai an badge trong truong hop do.
 */
public final class BookFormatUtils {

    private BookFormatUtils() {
    }

    public static String formatLabelFromPath(String lpath) {
        if (lpath == null) return null;
        String path = lpath;
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) return null;
        String ext = path.substring(dot + 1).trim();
        if (ext.isEmpty() || ext.length() > 6) return null;
        return ext.toUpperCase(Locale.ROOT);
    }
}
