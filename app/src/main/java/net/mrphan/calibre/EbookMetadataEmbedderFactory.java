package net.mrphan.calibre;

/** Quyết định: (1) định dạng nào nên HIỆN nút "Lưu vào file ebook" trên MetadataEditorActivity,
 * (2) định dạng nào thực sự có embedder biết nhúng metadata thật. Hai câu hỏi này tách riêng vì
 * theo yêu cầu, mọi định dạng trừ PDF đều hiện nút (dù chưa chắc đã nhúng được thật cho định
 * dạng đó) -- xem embedderFor(): MOBI/AZW3/FB2/... hiện chưa có implementation, bấm nút với các
 * định dạng đó phải báo lỗi rõ ràng thay vì âm thầm bỏ qua hoặc giả vờ thành công. */
final class EbookMetadataEmbedderFactory {

    private EbookMetadataEmbedderFactory() {}

    private static final EbookMetadataEmbedder EPUB = new EpubMetadataEmbedder();

    /** true nếu định dạng này nên HIỆN nút "Lưu vào file ebook" -- theo yêu cầu, mọi định dạng
     * trừ PDF đều hiện nút (PDF không nhúng được theo cách này trong scope của tính năng này). */
    static boolean shouldShowSaveToFileButton(String extension) {
        return extension != null && !extension.isEmpty() && !"pdf".equalsIgnoreCase(extension);
    }

    /** @return embedder biết nhúng thật cho định dạng này, hoặc null nếu chưa hỗ trợ (caller
     * phải báo lỗi rõ ràng cho người dùng, KHÔNG được âm thầm bỏ qua). */
    static EbookMetadataEmbedder embedderFor(String extension) {
        if (EPUB.supports(extension)) return EPUB;
        return null; // MOBI/AZW3/FB2/... : chưa triển khai trong bản này
    }
}
