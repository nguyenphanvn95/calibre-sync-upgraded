package net.mrphan.calibre;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Toàn bộ field có thể sửa trên màn hình Hiệu đính metadata (MetadataEditorActivity). Field
 * nào sách hiện tại không có giá trị thì để null/rỗng ở đây -- KHÔNG bịa giá trị mặc định, để
 * form hiển thị đúng "trống" thay vì "null" hay một giá trị giả. */
public class BookMetadataForm {
    public String uuid;
    /** Chỉ để biết định dạng file (đuôi mở rộng) và tìm đúng file trên đĩa khi nhúng -- không
     * có ô nào trên form cho sửa trường này. */
    public String lpath;
    public String title = "";
    public String titleSort = "";
    public List<String> authors = new ArrayList<>();
    public String authorSort = "";
    public String publisher = "";
    /** ISO 8601, ví dụ "2024-05-01T00:00:00+00:00" hoặc chỉ "2024-05-01". */
    public String pubDate = "";
    public List<String> languages = new ArrayList<>();
    public String series = "";
    /** Giữ dạng String trên form (người dùng gõ số thập phân như "1", "2.5"), parse double lúc lưu. */
    public String seriesIndex = "";
    /** 0-5, giữ dạng String trên form giống seriesIndex. */
    public String rating = "";
    public List<String> tags = new ArrayList<>();
    /** isbn -> "8935...", asin -> "...", v.v. LinkedHashMap để giữ đúng thứ tự người dùng nhập. */
    public Map<String, String> identifiers = new LinkedHashMap<>();
    /** Mô tả sách -- HTML (như Calibre desktop hay lưu) hoặc text thuần đều được lưu nguyên,
     * không parse/convert qua lại. */
    public String comments = "";

    /** Định nghĩa + giá trị hiện tại của mọi Custom Column app đã từng biết cho sách này
     * (DATABASE_VERSION 13) -- xem MetadataEditorActivity#populateCustomColumnRows() (hiển thị)
     * và readFormFromViews() (đọc lại sau khi người dùng sửa). customColumnValues dùng dạng
     * value_text CANONICAL giống TABLE_CUSTOM_COLUMN_VALUES lưu (nối bằng
     * CustomColumnSync.MULTI_JOIN cho cột is_multiple), KHÔNG phải dạng người dùng gõ trên UI
     * (dấu phẩy) -- việc quy đổi 2 chiều nằm ở MetadataEditorActivity, không phải ở đây, để form
     * này luôn là 1 "ảnh chụp" đúng những gì sẽ được lưu. Không có cách nào TẠO Custom Column mới
     * hoàn toàn từ app này (chỉ Calibre Desktop mới định nghĩa cột), nên danh sách defs chỉ có
     * thể là tập con của những gì đã đồng bộ/đọc được, chưa từng thêm ở đây. */
    public List<CustomColumnSync.ColumnDef> customColumnDefs = new ArrayList<>();
    public Map<String, String> customColumnValues = new LinkedHashMap<>();

    /** Bìa mới do người dùng chọn trong phiên chỉnh sửa này, null nếu không đổi bìa. Không phải
     * một phần dữ liệu bền của sách (đó là "thumbnail" trong metadata.calibre / cover_path
     * trong SQLite) -- chỉ là input tạm thời cho DeviceBookMetadataWriter#save() và
     * EbookMetadataEmbedder#embed() dùng trong 1 lần lưu. */
    public transient byte[] newCoverBytes;
}
