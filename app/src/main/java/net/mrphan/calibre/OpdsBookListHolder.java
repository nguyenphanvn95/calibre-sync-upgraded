package net.mrphan.calibre;

import java.util.List;

/**
 * Giữ tạm 1 danh sách {@link OPDSEntry} (kèm cả bìa đã tải, {@link OPDSEntry#thumb}) TRONG BỘ NHỚ
 * cho BookDetailActivity vuốt trái/phải qua lại (xem OPDSBrowserFragment#openBookDetail và
 * BookDetailActivity#onSwipeToAdjacentBook) -- KHÔNG được truyền qua Intent extra (Serializable)
 * như bản đầu tiên từng làm.
 * <p>
 * Lý do: 1 trang OPDS có thể có hàng chục cuốn, mỗi cuốn kèm 1 ảnh bìa (OPDSEntry#thumb). Gộp cả
 * danh sách đó serialize vào 1 Intent extra rất dễ vượt quá giới hạn kích thước 1 giao dịch Binder
 * (~1MB) ngay cả khi mở Activity trong CÙNG app/tiến trình (Intent luôn phải marshal qua
 * ActivityManagerService) -- ném {@code android.os.TransactionTooLargeException} ngay tại lệnh gọi
 * {@code startActivity()}. Ngoại lệ đó lại bị nuốt mất bởi khối try/catch chung quanh mọi thao tác
 * bấm trong OPDSBrowserFragment#onItemClick (bắt Exception để hiện Toast
 * R.string.opds_error_navigation "Couldn't open that link..." cho MỌI lỗi điều hướng), nên triệu
 * chứng nhìn thấy là sách không mở được kèm thông báo lỗi không liên quan gì đến nguyên nhân thật.
 * <p>
 * Giải pháp: giữ danh sách ở đây (cùng tiến trình, không qua Binder), Intent chỉ mang theo 1
 * EXTRA_ENTRY đơn lẻ (nhẹ, đã hoạt động tốt từ trước) + vị trí trong danh sách + 1 cờ boolean báo
 * "đọc danh sách từ đây". Chỉ 1 danh sách được giữ tại 1 thời điểm -- đủ dùng cho 1 phiên vuốt qua
 * lại trong 1 trang OPDS đang xem; mỗi lần bấm mở 1 cuốn TỪ danh sách duyệt
 * (OPDSBrowserFragment#openBookDetail) sẽ GHI ĐÈ danh sách cũ. Nếu app bị hệ thống kill và phục
 * hồi (giữ liệu tĩnh trong RAM bị mất), get() trả về null -- BookDetailActivity coi như không có
 * ngữ cảnh danh sách để vuốt, không crash.
 */
final class OpdsBookListHolder {
    private static List<OPDSEntry> entries;

    private OpdsBookListHolder() {}

    static void set(List<OPDSEntry> list) {
        entries = list;
    }

    static List<OPDSEntry> get() {
        return entries;
    }
}
