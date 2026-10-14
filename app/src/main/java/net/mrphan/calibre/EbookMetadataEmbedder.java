package net.mrphan.calibre;

import java.io.File;

/** Nhúng metadata (và tuỳ chọn ảnh bìa mới) trực tiếp vào 1 file ebook trên đĩa, cho nút "Lưu
 * vào file ebook" của MetadataEditorActivity. Xem EbookMetadataEmbedderFactory để biết định
 * dạng nào hiện đang có implementation thật (chỉ EPUB tính đến hiện tại). */
interface EbookMetadataEmbedder {

    /** @return true nếu định dạng này (theo phần mở rộng, không phân biệt hoa/thường) có thể
     * nhúng metadata trực tiếp vào file bằng embedder này. */
    boolean supports(String extension);

    /** Nhúng form vào bookFile tại chỗ. Ghi an toàn: tạo file tạm rồi rename đè lên file gốc,
     * không bao giờ sửa trực tiếp file gốc trong lúc đang đọc dở nó. Ném Exception nếu thất bại
     * -- caller phải bắt và báo lỗi rõ ràng cho người dùng, KHÔNG được âm thầm coi như đã lưu
     * thành công trong khi file gốc chưa hề thay đổi. */
    void embed(File bookFile, BookMetadataForm form) throws Exception;
}
