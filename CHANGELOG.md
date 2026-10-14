# CHANGELOG.md

Version-by-version log for calibre-sync builds. (Not to be confused with
`CHANGELOG_MIGRATION.md`, which is a one-time before/after table for the 2013 → AndroidX
rewrite, not a per-version log.) Version numbers here are informal build snapshots used in
prompts/zips, not the `versionCode`/`versionName` in `app/build.gradle.kts`.

## 5.54 — Sửa 2 crash: xóa sách ở Device Library, và mở lại sách EPUB sau khi xoay màn hình

- **`CollectionFragment#refreshBookList()` (crash `IndexOutOfBoundsException: Inconsistency
  detected... Invalid item position` khi vuốt xóa 1-2 cuốn sách liên tiếp):** nguyên nhân thực sự
  là bản trước đó chạy `Cursor.requery()` trên 1 thread nền trong khi RecyclerView trên main
  thread vẫn đang đọc **CHÍNH cursor đó** (`getItemCount()`/`onBindViewHolder` lúc scroll/layout)
  — `Cursor` không thread-safe, nên `requery()` nạp lại `CursorWindow` ngay giữa lúc main thread
  đang đọc làm RecyclerView nhận chỉ số không khớp dữ liệu thật → crash. Đưa `requery()` về lại
  main thread (đồng bộ) để loại bỏ hoàn toàn cuộc đua giữa 2 thread; bỏ luôn
  `ExecutorService`/cờ `mRefreshInFlight`/`mRefreshPending` không còn cần thiết.
- **`BookRecyclerAdapter#applyCursorDiff()`:** đóng luôn dòng đang vuốt-mở (`mOpenPosition`) sau
  mỗi lần diff, giống `swapCursor()` đã làm — tránh dòng vuốt-mở trỏ nhầm sang sách khác (hoặc
  sách vừa bị xóa) sau khi vị trí các dòng xáo trộn.
- **`EpubReaderActivity#onCreate()` (crash `Fragment$InstantiationException` /
  `NoSuchMethodException: EpubNavigatorFragment.<init>` khi xoay màn hình / chia đôi màn hình /
  bị hệ thống restore sau khi bị kill nền):** `FragmentManager` cố tự khôi phục lại
  `EpubNavigatorFragment` từ `savedInstanceState` NGAY TRONG `super.onCreate()`, bằng
  `FragmentFactory` mặc định (dựa vào reflection) — trước khi factory thật của app kịp được gán
  (factory thật cần đọc/parse file EPUB bất đồng bộ nên không thể sẵn sàng sớm hơn). Vì
  `EpubNavigatorFragment` (thư viện Readium) không có constructor không-tham-số công khai, lần
  khôi phục đó luôn ném lỗi và crash toàn app ở MỌI lần xoay màn hình, không chỉ lần mở đầu tiên.
  Fix: xóa entry `"android:support:fragments"` khỏi `savedInstanceState` trước khi gọi
  `super.onCreate()` — chỉ ảnh hưởng lúc Activity thực sự bị tạo lại, không đụng tới màn hình đọc
  đang hiển thị bình thường lúc chuyển app nền/tiền cảnh. `shouldAddFragment` giờ luôn `true`;
  `setUpNavigatorFragmentFactory()` tự thêm lại fragment mới sau khi factory thật sẵn sàng, y hệt
  lần mở sách đầu tiên.
- **`proguard-rules.pro`:** thêm keep rule chung `public <init>()` cho mọi lớp con của
  `androidx.fragment.app.Fragment`, cộng thêm `-keep` cho `org.readium.r2.navigator.**` /
  `.shared.**` / `.streamer.**` — phòng hờ thêm cho đúng loại crash trên (constructor không-
  tham-số bị R8 xóa ở bản release) và cho các model publication/metadata của Readium (được nạp
  bằng `kotlinx.serialization` qua tên field, R8 có thể đổi tên/xóa nếu không có rule này).

## 5.53 — Sửa nhãn ngày trên biểu đồ "Tiến trình" bị xuống dòng xấu (tab THÁNG)

- **`ProgressActivity#renderForPeriod()`:** trước đây tạo 1 `TextView` cho MỌI bucket (kể cả
  bucket rỗng không hiện nhãn) rồi chia đều `layout_weight = 1f` cho cả hàng — với tab THÁNG có
  ~28-31 cột, mỗi `TextView` chỉ được cấp một cột rất hẹp, khiến các số 2 chữ số ("11", "16",
  "21"...) bị bẻ xuống dòng giữa chừng (vd "1" xuống dòng rồi "1"), nhìn rất xấu so với ảnh mẫu
  (chỉ vài nhãn "3 9 15 21 27" trải đều 1 hàng).
- Fix: chỉ tạo `TextView` cho các bucket THỰC SỰ có nhãn (`buckets.filter { it.label.isNotEmpty() }`)
  thay vì toàn bộ bucket — nhãn được chia đều `weight = 1f` trên ít cột hơn nên mỗi cột đủ rộng.
  Thêm `isSingleLine = true` để phòng hờ không cho phép bẻ dòng dù trong trường hợp nào.
- Không đổi logic chọn ngày nào được hiện nhãn (`showEvery` trong `buildChartBuckets()`), chỉ đổi
  cách dựng UI của hàng nhãn.

## 5.52 — Thêm màn hình "Tiến trình" (thống kê thời gian đọc gộp toàn thư viện)

- **Màn hình mới "Tiến trình" (`ProgressActivity`), mở từ mục mới trong Navigation Drawer:**
  thời gian đọc theo ngày/tuần/tháng/năm (biểu đồ cột), chuỗi ngày đọc liên tục (streak) với kỷ
  lục, mục tiêu đọc hàng ngày tùy chỉnh (Dễ dàng/Trung bình/Khó khăn/Giá trị của bạn), so sánh
  "Xu hướng" với kỳ trước, tần suất dịch (số bản dịch trên mỗi 100 từ) và tốc độ đọc ước lượng
  (từ/phút). **Cố ý CỘNG DỒN theo ngày, KHÔNG phụ thuộc sách nào còn trong thư viện** — dùng
  bảng SQLite mới `daily_reading_stats` (`DATABASE_VERSION` 16 → 17) hoàn toàn tách biệt khỏi
  bảng `reading_time` theo-từng-chương/trang-của-1-cuốn-sách sẵn có (không đổi gì ở đó, màn hình
  "Thống kê" per-book cũ vẫn giữ nguyên).
- `ReadingTimeTracker` (dùng chung cho EPUB/PDF/CBZ) giờ mirror mỗi đoạn thời gian đọc đã chốt
  vào `DailyReadingStatsStore` mới, song song với việc ghi `reading_time` như trước.
- `LookupBottomSheetFragment` ghi nhận mỗi lần dịch thành công vào thống kê ngày, phục vụ "Tần
  suất dịch".

## 5.51 — Sửa "sách đã upload từ Calibre desktop không hiện ra khi đồng bộ Device Library"

- **Fix `RemoteTreeResolver#findChild()` so khớp TÊN THƯ MỤC GỐC tuyệt đối, gây phân kỳ 2 thư
  mục Drive khác nhau giữa Android và plugin Calibre desktop:** thư mục gốc trên Drive được
  resolve từ 2 nguồn độc lập -- Android dùng tên thật của thư mục lưu trữ cục bộ trên máy
  (`rootDir.getName()`), Calibre desktop dùng chuỗi "Cloud Sync Folder Name" người dùng tự gõ
  trong Settings của plugin. `String#equals()` cũ đòi khớp ký tự-đối-ký tự tuyệt đối: chỉ cần
  lệch 1 khoảng trắng thừa ở đầu/cuối hoặc 1 chữ hoa/thường (ví dụ "eBooks" so với "ebooks ")
  là đủ để 2 bên tạo/đọc 2 thư mục gốc HOÀN TOÀN KHÁC NHAU trên Drive -- kết quả: sách + cover +
  `calibre_sync_manifest.json` đã upload thành công từ desktop (kiểm chứng được qua log + tải
  trực tiếp file manifest), nhưng điện thoại tìm nhầm sang thư mục gốc rỗng của chính nó nên
  không bao giờ thấy được. Giờ so khớp tên thư mục (không áp dụng cho tên file cố định như
  `book.<ext>`/`cover.jpg`/`calibre_sync_manifest.json`) tự trim khoảng trắng thừa 2 đầu và
  không phân biệt hoa/thường trước khi so sánh. Đã đồng bộ fix tương ứng bên plugin Calibre
  desktop (`device_sync/tree_resolver.py`) để cả 2 phía cùng khoan dung như nhau.

## 5.50 — Sửa icon Navigation Drawer bị mờ/trùng lặp

- **Fix "Calibre Library" hiện icon đen kịt không rõ hình**: `ic_tab_csync` là PNG cũ (còn sót
  lại từ thời BottomNavigationView 3 tab) có nền đặc không trong suốt — khi NavigationView áp
  `itemIconTint` mặc định của theme, cả khối vuông bị tô đặc thành 1 màu, mất hết chi tiết bên
  trong. Đã xóa 4 file PNG (`drawable-ldpi/mdpi/hdpi/xhdpi/ic_tab_csync.png`) và thay bằng vector
  `drawable/ic_tab_csync.xml` hình "sách mở" (kiểu Material `menu_book`, nền trong suốt hoàn
  toàn) — tượng trưng đúng cho thư viện sách Calibre, phân biệt rõ với Device Library (thư mục),
  Calibre wireless device (mũi tên lên xuống) và Calibre Libraries on GDrive (mây + sách).
- **Fix icon OneDrive/Box trùng hệt nhau (đều ra 1 đám mây)**: `ic_provider_onedrive.xml` và
  `ic_provider_box.xml` trước đây dùng chung 1 `pathData` hình đám mây (giống cả `ic_provider_
  pcloud.xml`) nên 3 nút cloud provider không phân biệt được, kể cả ở tab Connection (không bị
  NavigationView ép tint) lẫn trong Cloud Sync submenu của nav drawer.
  - OneDrive: giữ dạng đám mây (đúng nhận diện) nhưng đổi màu xanh dương riêng (`#0A78D6`).
  - Box: đổi hẳn sang hình "chiếc hộp mở" (viền tam giác nắp hộp + đường seam giữa + đường băng
    keo dọc), màu cam vàng (`#F5A623`) — không sao chép logo Box chính thức, chỉ minh họa đúng
    nghĩa tên dịch vụ.
  - Google Drive giữ nguyên (đã có hình lá cờ tam giác 3 màu riêng biệt từ trước, chỉ bị
    NavigationView tint đơn sắc trong drawer — đây là hành vi chung áp dụng cho mọi icon trong
    drawer, không phải lỗi riêng của Google Drive).
  - Vì `ConnectionFragment`/`ProviderCardAdapter` gán icon trực tiếp bằng `setImageResource()`
    không ép tint màu, 2 icon sửa trên tự động hiển thị đúng ở cả tab Connection và submenu
    Cloud Sync trong nav drawer mà không cần sửa thêm code.

## 5.49 — Sách mới trong Device Library (đồng bộ từ Drive/import "+"/chép tay) thiếu metadata mở
rộng (publisher/series/tags/pubdate/identifiers/comments/đồng tác giả)

Theo chẩn đoán: `findNewBooks()` (con đường DUY NHẤT sách mới xuất hiện trong Device Library, áp
dụng cho cả 3 nguồn — Device Library Sync tải từ Drive, nút "+" import thủ công, và sách chép tay
vào thư mục) chỉ dùng `BookMetadataSniffer` để quét nhanh, cố tình chỉ đọc `dc:title`/
`dc:creator` (tác giả ĐẦU TIÊN) + cover rồi `insertMetadata()` với `tags=null` và overload rút
gọn — publisher/pubdate/series/seriesIndex/rating/languages/identifiers luôn `null`, dù file EPUB
gốc vẫn còn nguyên `content.opf` đầy đủ do Calibre nhúng khi export. Đã xác nhận plugin
`device_sync/` không có lỗi — `calibre_sync_manifest.json` cố tình không chứa các field này vì
thiết kế là để Android tự đọc OPF nhúng trong EPUB.

- **Fix**: `BookCrawler.findNewBooks()` — sau khi `insertMetadata()` ghi base row (title/author/
  cover) như cũ, nếu file là `.epub` thì gọi thêm `enrichNewEpubMetadata()` (method mới): đọc đầy
  đủ `EpubMetadataOpfReader.read(f)` (trước đây chỉ dùng trong "Hiệu đính" → "Lấy metadata từ
  ebook") rồi `MetadataDatabaseHelper#updateMetadataFields()` (đã có sẵn, đúng shape dữ liệu của
  `EpubMetadataOpfReader.Result`) để lấp publisher/pubdate/series/series_index/tags/languages/
  identifiers/comments/đủ tác giả (nối `" & "`)/custom columns.
  - Lỗi đọc `.opf` (file hỏng, thiếu field...) không làm rớt sách khỏi scan — bắt exception, sách
    vẫn giữ title/author/cover cơ bản đã `insertMetadata()` từ trước.
  - PDF/CBZ/định dạng khác giữ nguyên hành vi cũ (không có OPF chuẩn để đọc thêm).
- File: chỉ `BookCrawler.java` (thêm import `java.util.List`/`java.util.Map`, gọi
  `enrichNewEpubMetadata()` trong nhánh sách mới, thêm 2 method mới `enrichNewEpubMetadata()`/
  `isEmpty()`). Không đụng `device_sync/`, `DeviceLibrarySyncManager.java`,
  `MetadataDatabaseHelper.java`, hay `EpubMetadataOpfReader.java` — cả 3 đã đủ chức năng từ trước,
  chỉ thiếu chỗ gọi nối lại.
- **Lưu ý**: sách đã sync trước bản vá này (đã nằm sẵn trong DB với metadata cụt) KHÔNG tự được
  backfill — `findNewBooks()` chỉ chạy cho file chưa có trong DB. Backfill sách cũ là việc riêng,
  chưa làm trong lượt này.
- **Chưa build/run-verified** — không có Android SDK/Gradle trong môi trường sửa code hiện tại; đã
  đối chiếu thủ công `EpubMetadataOpfReader.Result` (title/authors/publisher/pubDate/languages/
  series/seriesIndex/tags/identifiers/comments/customColumnDefs/customColumnValues) và chữ ký đầy
  đủ của `updateMetadataFields()` khớp chính xác với code thật trong repo, cần build/chạy qua
  checklist 6 bước (đặc biệt bước 6: EPUB có `.opf` cố tình hỏng) trước khi release.

## 5.48 — Fix mất metadata mở rộng (cover/series/publisher/rating/pubdate/ngôn ngữ/ISBN) khi tải
sách GDrive Library vào Device Library qua nút "Tải"

Theo báo cáo: sách nguồn GDrive tải về qua nút **"Tải"** trên `BookDetailActivity` (không qua
WebView "Thư viện mini") thiếu vĩnh viễn cover/series/nhà xuất bản/đánh giá/ngày xuất bản/ngôn
ngữ/ISBN trong Device Library, dù `metadata_public.json` trên Drive có đủ dữ liệu này (đã xác
nhận plugin desktop `gdrive_sync` không có bug, không cần đụng vào code Python).

- **Nguyên nhân**: nút "Tải" đi qua `OpenBookActivity#onPostExecute → MetadataDatabaseHelper#
  insertBook(uuid, lpath)`, hàm này CHỈ `update` cột `lpath`. Toàn bộ phần "đủ dữ liệu hay không"
  phụ thuộc dòng sách đã được đăng ký từ trước qua `GDriveBookEntryBuilder#
  ensureBareMetadataRegistered()` — hàm này trước đây CỐ TÌNH chỉ ghi `uuid/author/title/tags`
  (+ `comments` nếu có), bỏ qua hoàn toàn cover/series/publisher/pubdate/rating/languages/isbn.
  (Luồng `GDriveLibraryJsBridge#downloadBook` gọi từ chính WebView đã ghi đủ field từ trước, không
  liên quan bug này.)
- **Fix**: `GDriveBookEntryBuilder#ensureBareMetadataRegistered()` giờ đăng ký ĐẦY ĐỦ field ngay
  từ bước này (trước khi người dùng bấm Tải), cùng quy ước chuyển đổi cột mà
  `GDriveLibraryJsBridge#buildInsertJsonFor()` đã dùng cho luồng WebView (đặc biệt: rating cần
  chia 2 từ thang 0-10 thô của Calibre sang thang 0-5 mà `insertMetadata()`/
  `backfillExtendedMetadataIfMissing()` chờ nhận). Rẽ 2 nhánh:
  - Chưa có dòng nào (`!alreadyRegistered`): gọi `insertMetadata()` bản đầy đủ (kèm cover tải tạm
    thành BLOB vào cột `thumbnail`, vì lúc này chưa có `lpath` để ghi cover ra file thật qua
    `CoverManager`).
  - Đã có dòng (sách từng tải/từng preview trước fix): CHỈ bổ sung field còn thiếu qua
    `backfillExtendedMetadataIfMissing()`/`saveCommentsIfEmpty()`/`updateThumbnail()` — KHÔNG gọi
    lại `insertMetadata()` (sẽ `cv.putNull("lpath")` nếu tham số `lpath` là null, xoá mất trạng
    thái "đã tải về" của sách đã có file trên máy), giống cách `OPDSLoader.java` đang làm cho
    luồng OPDS.
  - Tải cover là best-effort qua `GDrivePublicFileClient` (API key công khai, không OAuth) — lỗi
    mạng không chặn việc đăng ký các field còn lại.
- **Fix phụ**: `GDriveLibraryJsBridge#buildInsertJsonFor()` tách tên nhiều tác giả nhầm bằng dấu
  phẩy (`authorsRaw.split(",")`), trong khi plugin desktop nối bằng `" & "` và
  `GDriveLibraryDb#getAuthorCount()` trong cùng file Android cũng tách bằng `"&"` — sửa lại
  `split("\\s*&\\s*")` cho nhất quán, tránh dính liền tên nhiều tác giả.
- File: `GDriveBookEntryBuilder.java`, `GDriveLibraryJsBridge.java`. Không đụng `OPDSLoader.java`
  (đã đúng từ trước), `GDriveLibraryJsBridge#downloadBook`, hay schema `GDriveLibraryDb`.
- **Chưa build/run-verified** — môi trường sửa code hiện tại không có Android SDK/Gradle để build
  thật; đã đối chiếu thủ công từng chữ ký hàm gọi tới (`insertMetadata`,
  `backfillExtendedMetadataIfMissing`, `saveComments`/`saveCommentsIfEmpty`, `updateThumbnail`,
  `getByUuid`, `OPDSFormatUtils.parseIsoToEpochMillisOrNull`, `GDrivePublicFileClient.openStream`)
  và toàn bộ cột cursor (`GDriveLibraryDb#getBookFullById`) khớp với code thực tế trong repo, nhưng
  cần build/chạy thật trước khi release theo checklist đã đề ra.

## 5.47 — Fix hiệu ứng "cong trang" (page curl) bị vỡ/rách chữ khi kéo ngón tay

Theo ảnh chụp màn hình người dùng gửi: khi vuốt ngón tay để lật trang (chế độ curl), phần dải
"cuộn giấy" hiển thị chữ bị nghiền vụn thành nhiều sọc dọc chồng chéo, đọc không được — càng kéo
xa càng nặng.

- **Nguyên nhân**: `PageCurlOverlayView.drawCurlBandForRow()` lấy `srcSpan = width - srcStart`,
  tức là toàn bộ phần trang CÒN LẠI (từ đường gấp đến hết mép phải — có thể tới 80-90% chiều rộng
  trang) làm vùng nguồn, rồi nén hết vào `bandWidth` (chỉ ~`radius`, tối đa 15% chiều rộng trang).
  Kéo tay càng xa, càng nhiều nội dung (nhiều dòng chữ) bị ép vào 1 dải hẹp → vỡ hình.
- **Fix**: giới hạn `srcSpan` theo đúng độ dài cung vật lý của phần giấy đang cuộn quanh hình trụ
  tại vị trí đó — xấp xỉ `radius * π` (nửa chu vi, khớp với `curlAngle` chạy 0→π qua
  `CURL_STRIP_COUNT` cột trong `drawCurlBandForRow`), thay vì toàn bộ phần trang còn lại. Tỉ lệ
  nén giờ hợp lý (~π:1) giống cách các engine curl khác (eschao/android-PageFlip, Readest) lấy mẫu
  texture cho bề mặt cong, không còn nhồi nhét toàn bộ nội dung phía sau vào 1 dải hẹp.
- File: `PageCurlOverlayView.kt` (`drawCurlBandForRow`). Không đổi `PageCurlMath.kt` (toán hình
  học đường gấp/bán kính vẫn đúng, lỗi chỉ nằm ở bước lấy mẫu bitmap để vẽ).

## 5.46 — Fix đánh dấu/ghi chú bị nhân đôi khi đồng bộ + bookmark đã xóa bị đồng bộ "kéo lại"

Theo báo cáo người dùng: mỗi lần đồng bộ, cùng 1 đoạn đánh dấu (highlight)/ghi chú lại xuất hiện
thêm 1 lần trong "Đánh dấu chữ & ghi chú" (nhân đôi, nhân ba...); và xóa 1 bookmark xong, đồng bộ
lại vẫn thấy bookmark đó quay lại — như thể chỉ xóa hiển thị chứ không xóa thật.

- **Nguyên nhân 1 (nhân đôi)**: `SyncProgress#applyRemoteNewerIntoLocal()` chỉ tra "đã có chưa"
  cho type `"bookmark"` (`"bookmark".equals(a.type) ? db.findBookmarkId(...) : -1`) — mọi
  highlight/note tải về từ `calibre_sync_progress.json` đều bị coi là CHƯA CÓ nên
  `db.addAnnotation()` được gọi lại từ đầu mỗi lần đồng bộ, kể cả khi annotation đó đã tồn tại y
  hệt trong SQLite local rồi.
- **Nguyên nhân 2 (bookmark đã xóa bị kéo lại)**: `removeAnnotation()` trước đây `DELETE` thật
  khỏi SQLite — không còn dấu vết gì để biết annotation này VỪA bị người dùng xóa chủ đích (khác
  với "máy này chưa từng có annotation đó"). Lần đồng bộ kế tiếp, `mergeLocalInto()` merge dữ liệu
  local (đã không còn annotation đó) vào bản cloud (vẫn còn) mà không "dọn" annotation đã xóa khỏi
  bản cloud, nên bản merge cuối cùng vẫn còn annotation đó → `applyRemoteNewerIntoLocal()` add lại
  y như annotation chưa từng bị xóa.
- **Fix**:
  - `MetadataDatabaseHelper`: thêm cột `deleted_at` (DATABASE_VERSION 15) cho
    `reader_annotations`. `removeAnnotation()` đổi sang XÓA MỀM (set `deleted_at` + cập nhật
    `updated_at`, không `DELETE` hàng thật nữa). `getAnnotations()`/`findBookmarkId()` (dùng cho
    UI) lọc `deleted_at IS NULL` nên hành vi hiển thị KHÔNG đổi gì cả — item vẫn biến mất khỏi
    danh sách ngay khi xóa. Thêm `findAnnotationId(uuid, type, locatorJson)` — bản tổng quát của
    `findBookmarkId` cho MỌI loại (bookmark lẫn highlight/note), và `applyRemoteDeletion(...)` để
    áp tombstone từ máy khác vào máy này.
  - `SyncProgress.AnnotationEntry`: thêm field `deleted` (đọc từ cột `deleted_at` khi merge local,
    ghi/đọc field `"deleted"` trong JSON — file JSON cũ không có field này mặc định `false`, tức
    không annotation nào trong file cũ từng là tombstone, đúng ý nghĩa).
  - `applyRemoteNewerIntoLocal()`: dùng `findAnnotationId()` (mọi type) thay `findBookmarkId()`
    (chỉ bookmark) để quyết định thêm-mới-hay-bỏ-qua → hết nhân đôi. Annotation có `deleted=true`
    (tombstone) không còn bị `addAnnotation()` lại nữa — nếu máy này vẫn còn bản sống của nó thì
    áp xóa mềm theo (`applyRemoteDeletion`), không thì bỏ qua.
- Nguyên tắc merge theo `updatedAt` lớn hơn thắng (đã có sẵn trong `mergeLocalInto()`) tự động áp
  dụng đúng cho cả tombstone, vì xóa mềm cũng cập nhật `updated_at` giống mọi chỉnh sửa khác — xóa
  mới hơn thắng bản chưa xóa cũ hơn từ máy khác, và ngược lại.

**Chưa build/run-verify trên thiết bị thật** (sandbox không có Android SDK/gradle để compile thật).

## 5.45 — Fix nút "Đánh dấu" trong menu chọn văn bản không phản hồi

Theo báo cáo người dùng: trong menu chọn văn bản (thanh nổi màu đen hiện lên khi bôi đen văn bản
trong Reader), bấm "Đánh dấu" không có phản ứng gì, trong khi "Ghi chú" và "Tìm trong sách" vẫn
hoạt động bình thường.

- **Nguyên nhân**: khi hệ thống dùng đường fallback `ActionMode` (thanh nổi mặc định của Android,
  hiện khi lớp JS chặn `contextmenu` không kịp/không chặn được menu hệ thống — xem kdoc của
  `ReaderSelectionToolbar`), bấm "Đánh dấu" gọi `showColorSwatchPopup()` để mở popup chọn màu.
  Popup này là 1 `PopupWindow` cố tình đặt `isFocusable = false` (để không cướp focus khỏi lựa
  chọn văn bản khi mở từ thanh "pill" kiểu Readest — nơi không có UI hệ thống nào cạnh tranh).
  Nhưng trong đường fallback `ActionMode`, thanh nổi hệ thống vẫn đang giữ focus cửa sổ, nên popup
  không focusable này không thể vẽ đè lên trên được — popup vẫn được tạo ra nhưng nằm PHÍA SAU
  thanh menu hệ thống, người dùng không thấy gì cả. "Ghi chú" vẫn hoạt động vì `AlertDialog` là 1
  cửa sổ riêng luôn cướp focus — mà Android tự động ẩn thanh `ActionMode` nổi ngay khi cửa sổ mất
  focus.
- **Lần sửa đầu (không đủ)**: gọi `mode?.finish()` trước khi mở popup, để đóng thanh hệ thống
  trước. Không giải quyết được — nhiều khả năng do việc gọi `finish()` ngay bên trong chính
  callback `onActionItemClicked()` của `ActionMode` đó (tự đóng chính nó từ bên trong khi đang xử
  lý sự kiện của nó) tạo ra tình huống đua (race) với `WebView`, khiến `showAtLocation()` gặp lỗi
  kiểu `BadTokenException` bị nuốt âm thầm đâu đó trong tầng dispatch của `WebView`, không thấy
  trong log.
- **Fix thật sự**: thêm hàm `showHighlightColorDialog()` — cùng layout 4 chấm màu
  (`view_reader_selection_color_popup.xml`) nhưng hiển thị bằng `AlertDialog` thay vì
  `PopupWindow`. `AlertDialog` luôn là 1 cửa sổ top-level tự cướp focus, giống hệt cơ chế khiến
  "Ghi chú" vốn đã hoạt động đúng — nên không còn phụ thuộc vào việc đóng/mở `ActionMode` đúng
  thời điểm nữa. `onActionItemClicked()` (cả nhánh `cachedSnapshot` lẫn nhánh fallback
  `withSnapshot()`) đổi sang gọi hàm này thay vì `showColorSwatchPopup()`.
- Thanh "pill" kiểu Readest (khi lớp JS chặn menu hệ thống thành công) KHÔNG đổi gì — vẫn dùng
  `showColorSwatchPopup()` (PopupWindow) như cũ, vì đường đó không có `ActionMode` hệ thống cạnh
  tranh z-order/focus nên không gặp lỗi này.

**Chưa build/run-verify trên thiết bị thật** (sandbox không có Android SDK/gradle để compile thật).

## 5.44 — Fix đồng bộ tiến trình đọc bị trễ hàng chục phút

Theo báo cáo người dùng: đặt chu ky auto-sync trong Reader là 3 phút nhưng lần đồng bộ gần nhất
lại cách hơn 40 phút.

- **Nguyên nhân**: `DeviceLibrarySyncScheduler#enqueueProgressSync()` (dùng cho nút "Sync" trong
  Reader và vòng lặp auto-sync định kỳ của `EpubReaderActivity`) trước đây APPEND vào CHUNG
  unique work name (`device_library_cloud_sync`) với upload/download/full-sync. Nếu chuỗi "Đồng
  bộ tự động toàn bộ thư viện" (`AppLibraryAutoSyncWorker`) đang có 1+ `enqueueFullSync()` cho
  thư viện lớn chạy/chờ sẵn trong CHÍNH hàng đợi đó, mỗi tick đồng bộ tiến trình đọc bị xếp hàng
  PHÍA SAU các full-sync đó và phải đợi chúng chạy xong hết mới tới lượt — có thể trễ hàng chục
  phút dù người dùng đặt chu kỳ chỉ vài phút trong Reader.
- **Fix**: `enqueueProgressSync()` giờ dùng 1 unique work name RIÊNG
  (`device_library_progress_sync`), không còn bao giờ bị chặn bởi upload/download/full-sync toàn
  bộ thư viện. An toàn vì `calibre_sync_progress.json` và `manifest.json` là 2 file khác nhau
  trên remote; rủi ro duy nhất còn lại (progress-only job và full-sync job — full-sync cũng tự
  gọi `syncProgressOnly()` nội bộ — ghi đè `calibre_sync_progress.json` gần như đồng thời) là
  cùng loại race condition đa-thiết-bị mà `SyncProgress.mergeLocalInto()` đã xử lý sẵn, không
  phải rủi ro mới.
- `BackgroundJobsBottomSheet`/`CollectionFragment` không đổi gì: chúng chỉ quan sát
  `UNIQUE_WORK_NAME` (upload/download/full-sync) — progress-only sync vốn đã không hiện trong
  danh sách "tác vụ chạy nền" này từ trước.

## 5.43 — Tách phần điều phối TTS ra khỏi EpubReaderActivity (bước 1/N)

Hạng mục 5/5 của prompt nâng cấp Reader & TTS Pipeline (2026-07). `EpubReaderActivity.kt` dài
2386 dòng — pass này CHỈ tách phần điều phối TTS (mini player + full player + tô sáng câu đang
đọc), không đụng các phần khác (image tool, search, highlights/bookmarks, cloud sync UI...).

- File mới `reader/tts/TtsSessionCoordinator.kt`: gom `bind()`/`unbind()` (collect
  `TtsPlaybackState` để bind mini player + áp highlight), `clearHighlightNow()`, và logic
  key-dedup (`lastHighlightedSentenceKey`)/gating (`highlightOn`/`autoFollowOn`) trước đây nằm
  trực tiếp trong `EpubReaderActivity`. KHÔNG sở hữu `TtsController`/`TtsPlaybackService` — chỉ
  là "dây nối" giữa state và UI, nhận `getNavigatorFragment`/`getPublication`/`autoFollow` dưới
  dạng lambda (không giữ Activity Context/View trực tiếp).
- `EpubReaderActivity.kt`: xoá field rời `ttsStateCollectJob`/`ttsHighlightCollectJob`/
  `lastHighlightedSentenceKey`, thay bằng 1 field `ttsSessionCoordinator: TtsSessionCoordinator`
  (`by lazy`). `attachMiniPlayerAndFullPlayer()` rút gọn còn dựng mini player rồi gọi
  `ttsSessionCoordinator.bind(...)`. `clearTtsSentenceHighlightNow()` giờ chỉ gọi
  `ttsSessionCoordinator.clearHighlightNow()`. Xoá hẳn `applyTtsSentenceHighlightIfNeeded()`
  (logic đã chuyển sang coordinator). `onStop()`: thay `ttsStateCollectJob?.cancel()` bằng
  `ttsSessionCoordinator.unbind()`.
- Lưu ý khi tách: `TtsSessionCoordinator` nhận `ttsSettingsStore` dưới dạng lambda
  (`getTtsSettingsStore: () -> TtsSettingsStore`) thay vì giá trị trực tiếp — `ttsSettingsStore`
  của Activity là `lateinit var` được gán bất đồng bộ sau khi mở xong publication, có thể CHƯA
  sẵn sàng tại thời điểm coordinator được khởi tạo (`by lazy`, kích hoạt ngay lần đầu `bind()`
  từ `onServiceConnected`, có thể xảy ra trước khi publication mở xong). Truyền lambda giữ đúng
  tính an toàn của code gốc, nơi `ttsSettingsStore` chỉ thực sự được đọc khi 1 phiên đọc THẬT SỰ
  đang active.
- Không đổi Activity sang Jetpack Compose/AAC ViewModel đầy đủ — `TtsSessionCoordinator` là
  plain Kotlin class nhận `LifecycleOwner`, không kéo thêm dependency mới.

**Chưa build/run-verify trên thiết bị thật** (sandbox không có Android SDK/gradle để compile
thật — xem ghi chú ở các mục 5.39, 5.40, 5.42 bên dưới về cùng hạn chế này).

## 5.42 — Sleep timer fade-out & preview giọng đọc

Hạng mục 4/5 của prompt nâng cấp Reader & TTS Pipeline (2026-07).

- **4.1 Fade-out âm lượng trước khi sleep timer dừng**: `TtsEngine.kt` thêm method optional
  `setPlaybackVolume(volume: Float)` (mặc định no-op, không phá interface hiện có — engine mới
  không cần implement gì thêm). `NghiTtsEngine` implement qua `AudioTrack.setVolume()`,
  `EdgeTtsEngine` qua `MediaPlayer.setVolume(left, right)`; `SystemTtsEngine` giữ no-op có ghi
  chú rõ lý do (`android.speech.tts.TextToSpeech` chỉ nhận volume qua Bundle mỗi lần `speak()`,
  không chỉnh runtime được). `TtsController.startSleepTimer()`: fade âm lượng dần đều trong 10
  giây cuối (20 bước, ~500ms/bước) trước khi `pause()` thật sự, reset về đầy âm sau đó;
  `cancelSleepTimer()` cũng reset về đầy âm để không kẹt volume thấp nếu huỷ giữa lúc đang fade.
- **4.2 Preview giọng đọc trong `TtsVoicePickerDialog`**: thêm nút loa (`ImageButton
  tts_voice_item_preview`, icon `ic_reader_volume` có sẵn) trên mỗi dòng giọng trong
  `item_tts_voice.xml`, phát câu mẫu "Xin chào, đây là giọng đọc mẫu." khi bấm.
  `TtsEngineRegistry.kt`: thêm `newInstance(id)` để tạo 1 instance engine RIÊNG (khác với
  `byId()` vốn trả về instance DÙNG CHUNG với phiên đọc chính) — sửa đúng điểm cảnh báo nêu
  trong prompt gốc (nếu preview gọi `setVoice()`/`speak()` thẳng lên instance dùng chung sẽ đổi
  state của engine đang phục vụ phiên đọc chính). `TtsVoicePickerDialog.playPreview()`: tạo
  instance MỚI qua `newInstance()` cho MỖI lần bấm, `shutdown()` ngay sau khi phát xong (trong
  `finally`) — không giữ nhiều instance TextToSpeech/AudioTrack/MediaPlayer song song nếu người
  dùng bấm preview nhiều giọng liên tục. Lỗi chỉ log, không hiện Toast.
- Thêm string `reader_tts_voice_item_preview_description`.

**Chưa build/run-verify trên thiết bị thật** (sandbox không có Android SDK để compile).

## 5.41 — Prefetch window thích ứng theo độ trễ tổng hợp thực tế

Hạng mục 3/5 của prompt nâng cấp Reader & TTS Pipeline (2026-07). `TtsController.kt`: thêm
`RollingLatencyTracker` (rolling average 5 mẫu gần nhất) đo thời gian tổng hợp thực tế quanh
`engine.prefetch()` (bỏ qua kết quả < 50ms vì đó là cache-hit, không phải tổng hợp thật —
ngưỡng phân biệt cache đọc đĩa (~<20ms) với tổng hợp thật (Edge TTS/NghiTTS đều >>50ms thực
tế). `adaptivePrefetchAheadCount()`: suy ra số câu cần prefetch trước để "che" hết độ trễ tổng
hợp trung bình, dựa trên thời lượng đọc trung bình 1 câu gần nhất (`lastSentenceLength`) — kết
quả luôn giới hạn trong `[1, giá trị người dùng chọn trong Settings]`, KHÔNG bao giờ vượt trần
người dùng đặt. `resumePlaybackLoop()`: dùng `adaptivePrefetchAheadCount()` thay
`settingsStore.getPrefetchAheadCount()` trực tiếp. Không thêm UI hiển thị độ trễ đo được, không
đổi UI Settings hiện có (vẫn giữ vai trò "trần trên").

## 5.40 — Double-buffer scratch file cho EdgeTtsEngine

Hạng mục 2/5 của prompt nâng cấp Reader & TTS Pipeline (2026-07). `EdgeTtsEngine.playMp3Bytes()`
trước đây ghi đè 1 file duy nhất (`edge_tts_reusable.mp3`) mỗi câu — nếu người dùng bấm "câu
tiếp" liên tục trong lúc `prepareAsync()` câu trước còn chạy dở, có rủi ro lý thuyết file bị ghi
đè ngay trong lúc `MediaPlayer` đang đọc dở. Đổi sang luân phiên giữa 2 file cố định
(`edge_tts_reusable_0.mp3`/`edge_tts_reusable_1.mp3`, tạo sẵn trong cacheDir, không tạo tên file
mới mỗi lần) — `nextScratchFile()` luân phiên chỉ số, đảm bảo lần ghi mới luôn nhắm vào file
KHÔNG đang được `MediaPlayer` đọc. `shutdown()` dọn cả 2 file thay vì 1. Không đổi gì ở
`TtsController` (lớp phòng thủ thứ 2, bổ sung cho `playJob?.cancel()` đã có sẵn).

## 5.39 — Làm cứng TtsSentenceHighlighter (định vị câu tô sáng chính xác hơn)

Hạng mục 1/5 (ưu tiên cao nhất) của prompt nâng cấp Reader & TTS Pipeline (2026-07).
`TtsSentenceHighlighter.buildLocator()` trước đây chỉ dùng `progression` + `Locator.Text
(highlight = ...)`, không có `before`/`after` — nếu Readium không khớp được đoạn `highlight`
trong DOM (câu chứa ký tự đặc biệt, hoặc trùng đoạn văn bản khác gần đó), `applyDecorations()`
sẽ âm thầm không hiện gì.

- `TtsPlaybackState.kt`: thêm 2 trường mới `currentSentenceContextBefore`/`currentSentenceContextAfter`
  (40 ký tự trước/sau câu đang đọc, cắt từ `chapter.text` gốc — KHÔNG qua từ điển phát âm/chuẩn
  hoá), non-breaking (có default rỗng).
- `TtsController.kt`: thêm field `chapterRawText` (giữ lại văn bản GỐC của chương, gán trong
  `startChapter()`/reset trong `stop()`), hàm `contextBefore()`/`contextAfter()` cắt ngữ cảnh từ
  đó, cập nhật `resumePlaybackLoop()` để ghi 2 trường mới vào state mỗi lần đổi câu.
- `TtsSentenceHighlighter.buildLocator()`: truyền `before`/`after` vào `Locator.Text` (rỗng →
  không truyền, `Locator.Text` chấp nhận `null`) để Readium khớp text chính xác hơn khi câu bị
  trùng lặp ở nhiều chỗ trong chương.
- `TtsSentenceHighlighter.apply()`: thêm log xác nhận (tag `TtsHighlight`, filter được qua
  logcat) khi `applyDecorations()` thành công lẫn khi thất bại — cần XOÁ sau khi đã xác nhận ổn
  định qua vài lần đọc thử thực tế trên thiết bị.

**Chưa build/run-verify trên thiết bị thật**: sandbox không có Android SDK/thiết bị thật để
compile/chạy thử — cách định vị `progression + text (+ before/after)` với `applyDecorations()`
chưa có tiền lệ đã build-verified trong repo này trước hạng mục này (`nav.go(locator)` thì đã
có tiền lệ qua tính năng Tìm kiếm). Cần kiểm tra thật trên thiết bị bằng logcat filter
"TtsHighlight" trước khi coi tính năng này ổn định.

## 5.38 — Trang preview sách (Calibre Library/GDrive): đủ metadata hơn, đổi nút "Thẻ cục bộ" thành "Chia sẻ"

Theo yêu cầu người dùng, dựa trên ảnh chụp màn hình preview 1 sách từ thư viện GDrive.

1. **Chưa hiện đầy đủ metadata (mô tả hiện nguyên thẻ HTML `<div><p>...</p>`)**:
   - `BookDetailActivity#renderPublicationInfo`: `bd_row_summary` trước đây `setText(entry.summary)`
     thẳng chuỗi thô — nếu Calibre lưu comments dạng HTML (rất phổ biến) thì các thẻ `<div>`/`<p>`
     hiện nguyên văn thay vì được format, đúng như ảnh chụp. Đã đổi sang
     `HtmlTextUtils.stripHtml(entry.summary)`, y hệt cách `DeviceBookDetailActivity` đã làm cho
     sách trong Device Library — 2 màn hình preview giờ nhất quán.
   - `GDriveBookEntryBuilder#build`: chưa từng gán `entry.updated` (last_modified) lẫn
     `entry.published` (pubdate) — nên dòng "Cập nhật lần cuối"/"Ngày xuất bản" luôn ẩn với sách
     GDrive dù dữ liệu gốc có sẵn. `GDriveLibraryDb`: bảng cache `books` trước đây không có cột
     `pubdate`/`timestamp` (chỉ có `last_modified`) — thêm 2 cột này (DB_VERSION 1 → 2, chỉ là
     cache nên tự rebuild ở lần đồng bộ tiếp theo), đọc từ `metadata_public.json` (`pubdate`,
     `timestamp` hoặc `date_added` dự phòng), và trả về trong `getBookFullById`/`buildLibraryJson`.

2. **Khi tải sách chưa nhập đủ metadata vào Device Library**:
   - `GDriveLibraryJsBridge#buildInsertJsonFor`: trước đây bỏ qua hoàn toàn
     publisher/pubdate/series/series_index/rating/languages/identifiers(isbn) với lý do
     "TABLE_METADATA không có cột tương ứng" — lý do đó đã LỖI THỜI: `MetadataDatabaseHelper` từ
     DATABASE_VERSION 13 (đợt nâng cấp metadata Phase 1-4 trước đó) đã có đủ các cột này và
     `insertJSON()` đã đọc đúng các key JSON tương ứng — chỉ là `buildInsertJsonFor` chưa được cập
     nhật theo. Đã bổ sung đọc đầy đủ các field trên từ `GDriveLibraryDb` và đưa vào JSON truyền
     cho `insertJSON()` (chú ý: `rating` giữ nguyên thang 0-10 thô như Calibre, KHÔNG chia 2 ở đây
     vì `insertJSON()` tự chuẩn hoá).

3. **Đổi nút "Thẻ cục bộ" (bd_btn_tags, trước đây chỉ hiện toast "Sắp ra mắt", chưa làm gì) thành
   "Chia sẻ", đổi thứ tự nút thành: Chia sẻ > Xem bìa > Nghe sách > Đọc > Tải xuống**
   (`activity_book_detail.xml`, `BookDetailActivity#wireActionButtons`): nút Chia sẻ mới
   (`bd_btn_share`, icon `ic_menu_share`, cùng string `action_share` với `DeviceBookDetailActivity`)
   — nếu sách đã có file tải sẵn trong Device Library (theo uuid) thì chia sẻ thẳng file đó qua
   `ShareUtils.shareFile` giống hệt Device Library; nếu chưa tải, chia sẻ tạm tiêu đề + tác giả
   dạng text (`ACTION_SEND`) thay vì im lặng không làm gì.

- **Chưa build/run-verified** (môi trường không có Android SDK/Gradle) — đã rà soát logic +
  kiểm tra cân bằng ngoặc từng file. Đặc biệt cần build thử và mở lại 1 thư viện GDrive đã có sẵn
  (DB cache cũ, version 1) để xác nhận việc nâng `DB_VERSION` lên 2 tự động rebuild đúng, không bị
  crash hay mất sách đang hiện; cũng nên xác nhận `metadata_public.json` thực tế có đúng field tên
  "pubdate"/"timestamp" như code đang giả định (nếu tên khác, cần sửa lại tên key đọc trong
  `GDriveLibraryDb#insertBook`).

## 5.37 — Sửa lỗi không tải/nhập sách vào Device Library từ Calibre Content Server (WebView)

Theo yêu cầu người dùng: bấm "Download" trên trang web Content Server (nhúng qua WebView trong
app) không tải được sách vào Device Library, trong khi OPDS và Google Drive vẫn hoạt động bình
thường.

- **Nguyên nhân gốc**: giao diện web mới của Calibre Content Server ("book list" app) là 1
  single-page app — nút "Download" của nó không điều hướng trang (navigation) và không tạo ra
  1 request HTTP thật với header `Content-Disposition` như 1 trang web tĩnh thông thường; thay
  vào đó nó `fetch()` sách về dưới dạng `Blob` trong JS rồi tự bấm 1 thẻ `<a download>` trỏ tới
  1 `blob:` URL để giữ nguyên trang. `WebView#setDownloadListener()` (cơ chế duy nhất
  `ContentServerBrowserActivity` dùng trước đây) CHỈ được gọi khi WebView tự nhận diện 1 điều
  hướng mạng thật sự dẫn tới file tải về — nó không bao giờ thấy `blob:` URL, nên bấm
  "Download" không có phản ứng gì cả (khác với OPDS dùng `HttpURLConnection` trực tiếp của
  riêng app, và Google Drive dùng SDK gốc, không qua WebView).
- **`ContentServerBrowserActivity`**: thêm `DOWNLOAD_HOOK_JS`, 1 đoạn JS được `evaluateJavascript()`
  tiêm vào sau mỗi `onPageFinished()` — gắn 1 listener `click` ở capture phase, bắt click vào bất
  kỳ thẻ `<a download>` nào (dấu hiệu chuẩn của "đây là 1 lượt tải file", đúng thứ nút Download
  của Content Server dùng): nếu `href` là `blob:` thì tự `fetch()` + đọc thành base64 trong JS rồi
  gửi cho native qua bridge mới `AndroidCsBridge` (`saveBlobData`); nếu là URL server bình thường
  thì gửi `href` + tên file (từ thuộc tính `download`) cho native tải qua `saveRemoteUrl` — tận
  dụng đúng tên file thật thay vì đoán từ URL `/get/FORMAT/book_id/library_id` (đoạn cuối URL là
  library id chứ không phải tên sách, nên trước đây nếu server không trả `Content-Disposition`
  thì file lưu ra sẽ không có phần mở rộng đúng và bị BookCrawler bỏ qua khi quét thư mục).
- **`startDownload()`**: thêm tham số `filenameHint` tùy chọn (ưu tiên hơn header/URL) để dùng tên
  file đã biết từ thuộc tính `download` của JS. **`saveDownloadedBytes()`** (hàm mới): ghi thẳng
  bytes của Blob (không cần request HTTP thứ 2, vì `blob:` URL chỉ tồn tại trong context JS của
  trang) vào thư mục sách rồi rescan, giống hệt luồng cũ.
- Giữ nguyên `setDownloadListener()` cũ làm phương án dự phòng cho trường hợp 1 bản Content
  Server nào đó vẫn tải qua điều hướng HTTP thật.
- **Chưa build/run-verified** (môi trường chỉnh sửa không có Android SDK/Gradle) — mới rà soát
  logic bằng tay + kiểm tra cân bằng ngoặc `{}`/`()`. Cần build thử và bấm thử nút Download trên
  Content Server thật trước khi phát hành, để xác nhận đúng cấu trúc DOM nút Download hiện tại
  của bản Calibre server đang dùng (thẻ `<a download>` là cách chuẩn phổ biến nhất nhưng có thể
  lệch nếu bản Calibre server cụ thể đó dùng cơ chế khác).

## 5.36 — Nho vi tri duyet o Device Library va Calibre Library (OPDS)

Theo yeu cau nguoi dung: duyet thu vien (moi che do hien thi), bam vao 1 cuon sach de xem
preview, bam quay lai thi bi nhay ve dau thu vien thay vi tiep tuc o vi tri vua cuon toi --
rat bat tien khi phai cuon lai tu dau. Bam "Show books" (Device Library) hoac lan dau tai
Calibre Library (OPDS) van bat dau tu dau nhu binh thuong.

- **Nguyen nhan goc**: ca 2 man hinh deu giu nguyen View (RecyclerView/GridView) qua vong doi
  Activity (mo BookDetailActivity/DeviceBookDetailActivity de preview khong huy View cua
  Fragment ben duoi) -- nhung ham ve lai danh sach (`showBooks()`/`loadCurrentPage()`) lai bi
  goi lai moi lan tab quay ve foreground (`onStart()`), va ca 2 deu VO TINH tao lai
  LayoutManager/Adapter/Cursor moi toanh moi lan nhu vay du khong co gi thay doi that su --
  khien RecyclerView/GridView mat het vi tri cuon da nho.
- **`CollectionFragment`** (Device Library): `showBooks()` truoc day goi
  `lvBooklist.setLayoutManager(...)` VO DIEU KIEN moi lan chay. Gio chi tao/gan LayoutManager
  moi khi thuc su can (doi che do xem, hoac dang chuyen tu man hinh Tags/Authors/filter khac
  ve) -- neu khong, giu nguyen instance LayoutManager cu (theo doi qua 2 field moi
  `mBooksLayoutManager`/`mBooksLayoutViewMode`) de no tu giu vi tri cuon da co. Bam "Show
  books" (`onHeaderRefresh` -> rescan) van nap Cursor moi -> Adapter moi -> RecyclerView tu
  nhay ve dau nhu cu, khong doi.
- **`OPDSPage`** (Calibre Library): `getBooksSorted()` truoc day LUON dung MatrixCursor moi
  (khong co cach sap xep tai cho). Gio cache lai sortField/ascending cua lan goi truoc; goi
  lai voi cung tham so (va khong co sach moi them vao qua `addBook()`, vd. nut "More") tra ve
  DUNG instance Cursor cu thay vi tao ban sao giong het. Nho vay
  `OPDSBrowserFragment#loadCurrentPage()`'s `alreadyLiveRendered` (so sanh identity Cursor)
  nhan ra dung "khong co gi doi" va bo qua viec tao lai BookCursorAdapter/goi
  `GridView#setAdapter()` -- day chinh la cho lam mat vi tri cuon truoc day. Tai OPDS lai/doi
  thu vien/lan dau tai van tao OPDSPage moi hoan toan nen tu nhien bat dau tu dau, khong can
  logic rieng.
- **`OPDSBrowserFragment`**: them guard nho cho `lvList.setNumColumns(mViewColumns)` (chi goi
  khi gia tri thuc su doi) de tranh dung cham khong can thiet vao GridView.
- **Chua build/run-verified** (moi truong chinh sua khong co Android SDK/Gradle offline
  cache) -- thay doi gioi han o 2 file (`OPDSPage.java`, `CollectionFragment.java`) cong 1
  doan comment/guard nho trong `OPDSBrowserFragment.java`, khong doi API cong khai nao.

## 5.35 — Man hinh "What's New" thay cho "About" + nut "Thoat" trong Navigation Drawer

Theo yeu cau nguoi dung (kem 2 anh mau tu Moon+ Reader): muc "About" trong Navigation Drawer
(icon hamburger) gio mo 1 man hinh "What's New" day du thay vi AlertDialog chi co so version,
va them 1 nut "Thoat" ngay ben duoi de dong han app.

- **`WhatsNewActivity`** (file moi): man hinh moi mo phong dung bo cuc anh mau — header co nut
  back + tieu de dang `"<ten app> <versionName> (<versionCode>)"`, ben duoi la danh sach cuon
  duoc cac khoi `"What's new in vX.X"` + bullet, dung 2 mang song song
  `whats_new_versions`/`whats_new_bullets` trong `strings.xml` (co ban dich `values-vi/`) —
  noi dung rut gon tu 11 muc trong `CHANGELOG.md` (5.34 → 3.48), viet lai theo huong nguoi
  dung cuoi thay vi chi tiet ky thuat (ten class/ham) cua file log nay.
- **`MainActivity`**: `showAboutDialog()` (AlertDialog cu) bi thay bang
  `startActivity(WhatsNewActivity.buildIntent(this))` khi bam `nav_about` (giu nguyen id, chi
  doi hanh vi + nhan `@string/nav_about` tu "About" thanh "What's New"/"Co gi moi").
- **`nav_drawer_menu.xml`**: them muc `nav_exit` ("Thoat") ngay duoi `nav_about`, icon moi
  `ic_menu_exit` (vector "power", cung phong cach voi `ic_action_info.xml` da co san).
- **`MainActivity#exitApp()`** (ham moi, xu ly click `nav_exit`): `stopService()` cho
  `CalibreService` (foreground service dang lang nghe Wireless Device) truoc, roi
  `finishAffinity()` + `System.exit(0)` de dong han process — khong chi finish() rieng
  `MainActivity` va bo lai service/thong bao foreground chay nen.
- String moi (`nav_exit`, `whats_new_title_format`, `whats_new_section_format`,
  `whats_new_versions`, `whats_new_bullets`) o ca `values/strings.xml` va
  `values-vi/strings.xml` (rieng `whats_new_versions` khong can ban dich vi chi la so phien
  ban).
- **Chua build/run-verified** (moi truong chinh sua khong co Android SDK/Gradle offline
  cache) — da ra soat thay the bang cach: (a) parse toan bo `*.xml` trong `app/src/main/res`
  bang `xml.etree.ElementTree` (260 file, khong con loi "not well-formed"; phat hien va sua 4
  cho dung nham `"--"` trong comment XML `<!-- -->` giong dung loi da ghi nhan o REVIEW NOTES
  cuoi file nay); (b) doi chieu 2 mang `whats_new_versions`/`whats_new_bullets` cung do dai (11
  muc) o ca `values/` lan `values-vi/`; (c) dem can bang dau ngoac `{}`/`()` trong
  `WhatsNewActivity.java` va cac doan sua trong `MainActivity.java`; (d) doi chieu moi
  `R.id`/`R.string`/`R.array`/`R.layout`/`R.drawable` moi tham chieu trong code voi dung khai
  bao thuc te trong XML, khong thay thieu sot nao. Can build thu bang `./gradlew assembleDebug`
  tren may co Android SDK truoc khi phat hanh.

## 5.34 — Nâng cấp metadata (Phase 4): hiệu đính Custom Column + vá lỗi mất dữ liệu khi rescan

Đây là bản quan trọng nhất trong cả loạt nâng cấp: khi đọc lại `MetadataEditorActivity` (đã có
sẵn từ trước, hiệu đính đầy đủ publisher/pubdate/series/rating/languages/identifiers) đối chiếu
với Phase 1, lộ ra 1 lỗi thật — sửa xong 1 field ở "Hiệu đính" thì field đó chỉ được ghi vào
`metadata.calibre`, KHÔNG được đồng bộ vào các cột SQLite mới thêm ở Phase 1. Lần đồng bộ kế
tiếp (bất kỳ sách nào, không nhất thiết là sách vừa sửa) sẽ khiến
`BookCrawler#writeMetadataFile()` REBUILD lại toàn bộ file từ SQLite (qua
`exportAllBooksAsJSON()`) và âm thầm ghi đè mất đúng field vừa sửa tay. Bản này vá lỗi đó, đồng
thời thêm khả năng hiệu đính/đọc/hiển thị Custom Column.

- **`MetadataDatabaseHelper.updateMetadataFields()`**: thêm 1 overload đầy đủ field
  (publisher/pubdate/series/series_index/rating/languages/identifiers_json + Custom Column),
  `DeviceBookMetadataWriter#save()` giờ gọi bản này thay vì bản rút gọn cũ — đóng đúng lỗ hổng
  ở trên. Chữ ký 5 tham số cũ vẫn giữ nguyên (delegate sang bản mới với mọi field mới = null).
- **`CustomColumnSync`**: thêm `computeValueNum()` (tính value_num từ text đã gõ tay, dùng khi
  lưu từ UI thay vì từ JSON đồng bộ) và `applyValuesIntoWireJson()` (chiều ngược lại — Def+value
  → khối "user_metadata" trong JSON) — dùng chung cho cả `exportAllBooksAsJSON()` lẫn
  `DeviceBookMetadataWriter`, tránh viết trùng logic 2 nơi.
- **`MetadataEditorActivity`**: thêm mục "Cột tùy biến (Custom Column)" — 1 dòng/cột cho mọi
  Custom Column app đã biết, sửa giá trị trực tiếp (nhiều giá trị cách nhau bằng dấu phẩy).
  Không thể thêm/xóa cột (chỉ Calibre Desktop mới định nghĩa cột được).
- **`EpubMetadataOpfReader`**: "Lấy metadata từ ebook" giờ đọc thêm các thẻ
  `<meta name="calibre:user_metadata:#lookup">` trong content.opf, dựng lại đúng khối
  "user_metadata" rồi tái dùng `CustomColumnSync#parseFromWireJson()` — cột nào Get-from-ebook
  tìm thấy mà form chưa có sẽ được thêm dòng mới (không ghi đè dòng đã có).
- **`DeviceBookDetailActivity`**: thêm hiển thị Custom Column trên trang chi tiết sách (đọc
  thẳng từ "user_metadata" trong metadata.calibre), có toggle ẩn/hiện riêng
  ("Cột tùy biến (Custom Column)") trong Cài đặt > các cột thông tin sách, cùng cơ chế với
  Publisher/Series/Rating/... đã có sẵn.

**Phase 5 (OPDS/Calibre Library tab): quyết định không làm.** Tab đó hiển thị dữ liệu lấy trực
tiếp từ OPDS feed do Calibre content server trả về, không đọc SQLite/metadata.calibre của Phase
1-4 — feed OPDS chuẩn không mang theo Custom Column, nên không có gì để hiệu đính/hiển thị thêm
ở phía app cho tab này mà không cần Calibre content server hỗ trợ thêm ở phía server.

## 5.33 — Nâng cấp metadata (Phase 2+3): Sắp xếp/Filter theo Series, Nhà xuất bản, Đánh giá, Custom Column

Xây trên nền Phase 1 (5.32): "Sắp xếp"/"Filter by..." của tab Device Library giờ dùng được
mọi field mới lẫn Custom Column bất kỳ, không chỉ Title/Author/Recent/Last Opened/Tags/Author/
Reading status như trước.

- **`MetadataDatabaseHelper`**:
  - `getBooksSorted()` thêm `SORT_FIELD_SERIES` (theo series rồi `series_index`),
    `SORT_FIELD_RATING`, `SORT_FIELD_PUBLISHER`, `SORT_FIELD_PUBDATE`, `SORT_FIELD_FILE_SIZE`
    — sách chưa có dữ liệu ở field nào luôn xếp cuối, bất kể chiều tăng/giảm, thay vì nhảy
    lên đầu khi chọn giảm dần.
  - `getBooksSortedByCustomColumn()` (hàm mới): sắp xếp theo giá trị 1 Custom Column bất kỳ
    (LEFT JOIN sang `custom_column_values`).
  - `getSeriesWithBooks()`/`getBooksBySeries()`, `getPublishersWithBooks()`/
    `getBooksByPublisher()`, `getBooksWithMinRating()`, `getDistinctCustomColumnValues()`/
    `getBooksByCustomColumnValue()` (hàm mới): nền cho các màn "Filter by..." mới.
- **`SortByDialog.Option`** có thêm 1 constructor nhận `CharSequence` label trực tiếp (thay vì
  chỉ resource id) — cần thiết vì nhãn Custom Column ("Genre", "Đánh giá riêng"...) đến từ
  Calibre Desktop lúc chạy, không phải chuỗi biên dịch sẵn.
- **`CollectionFragment`** (tab Device Library):
  - "Sắp xếp" có thêm Series/Đánh giá/Nhà xuất bản/Ngày xuất bản/Kích thước tệp, cộng 1 mục
    cho mỗi Custom Column (trừ kiểu `comments`/`composite`) mà Calibre Desktop đã gửi ít nhất
    1 lần. Lựa chọn Custom Column được nhớ qua lần khởi động lại app bằng cách lưu lại tên
    `lookup_name` (ổn định), không lưu id tạm của dialog (id đó tạo lại mỗi lần mở, có thể đổi
    thứ tự giữa các lần đồng bộ).
  - "Filter by..." có thêm Series/Nhà xuất bản/Đánh giá (tối thiểu N sao) + 1 mục cho mỗi
    Custom Column có dữ liệu — mỗi Custom Column mở ra 1 màn danh sách giá trị (giống màn Tags/
    Author sẵn có), chọn 1 giá trị sẽ lọc xuống đúng những sách có giá trị đó.
  - Thêm cờ chung `isShowingOtherFilterList` (+ `mReshowOtherFilterList`) để các màn Series/
    Nhà xuất bản/Custom Column mới không xung đột với logic Sắp xếp/làm mới sẵn có của Tags/
    Author, mà không cần 1 boolean cứng riêng cho từng loại (số Custom Column là động).
  - Tái dùng `AuthorAdapter` cho mọi màn danh sách giá trị mới (Series/Nhà xuất bản/Custom
    Column) thay vì viết thêm 3 Adapter gần như giống hệt nhau.

## 5.32 — Nâng cấp metadata (Phase 1): không còn đánh rơi field chuẩn + Custom Column

Nền tảng dữ liệu cho việc round-trip đầy đủ metadata Calibre Desktop (field chuẩn +
Custom Column bất kỳ) qua giao thức đồng bộ Wireless Device. Đây là **Phase 1** trong
kế hoạch nâng cấp nhiều giai đoạn (schema + parser); UI Sắp xếp/Filter mở rộng theo các
field/cột mới, hiệu đính Custom Column trong `MetadataEditorActivity`, và đọc Custom
Column từ `content.opf` cục bộ (`EpubMetadataOpfReader`) vẫn còn ở giai đoạn sau, chưa
làm trong bản này.

- **`DATABASE_VERSION` 12 → 13** (`MetadataDatabaseHelper`), migration cộng thêm hoàn
  toàn (không đụng dữ liệu cũ):
  - `TABLE_METADATA` có thêm `publisher`, `pubdate`, `series`, `series_index`, `rating`
    (thang 0-5), `languages` (CSV), `identifiers_json`, `date_added`, `file_size` — các
    field chuẩn Calibre gửi sẵn trong JSON đồng bộ nhưng trước đây bị `insertJSON()` bỏ
    qua hoàn toàn.
  - 2 bảng mới dạng EAV (Entity-Attribute-Value), vì số lượng/loại Custom Column khác
    nhau giữa từng thư viện Calibre của từng người dùng: `custom_column_defs` (định
    nghĩa cột: `lookup_name`, `label`, `datatype`, `is_multiple`, `display_json`,
    `sort_order`) và `custom_column_values` (`metadata_id`, `lookup_name`, `value_text`,
    `value_num`).
- **`CustomColumnSync.java`** (file mới): parser dùng chung, đọc khối `user_metadata`
  trong JSON đồng bộ Wireless Device thành `ColumnDef`/`ColumnValue`, chuẩn hoá rating
  Calibre (0-10) về thang 0-5 dùng chung với `OPDSEntry`/`BookDetailActivity`, nối giá
  trị cột `is_multiple` bằng ký tự Unit Separator (`\u001F`) thay vì dấu phẩy.
- **`insertJSON()`** giờ đọc và lưu đầy đủ các field chuẩn ở trên cùng mọi Custom Column
  (`#genre`, `#mytags`,...) tìm thấy trong `user_metadata`, thay vì chỉ
  `uuid/lpath/author/title/tags/thumbnail/cover_path/comments` như trước.
- **`insertMetadata(...)`** có thêm 1 overload nhận đủ field mới (16 tham số); chữ ký cũ
  (7 tham số) giữ nguyên và các call site hiện có (`BookCrawler`, `OPDSLoader`,
  `GDriveLibraryJsBridge`, `BookDetailActivity`, `OpenExternalBookActivity`) không cần
  sửa gì.
- **`exportAllBooksAsJSON()`** giờ xuất ngược lại đầy đủ các field chuẩn + Custom Column
  (đảo ngược đúng phép chuẩn hoá ở trên: rating nhân lại 2, danh sách join lại thành
  mảng...) khi ghi `metadata.calibre`, đối xứng với `insertJSON()`.
- **`OPDSFormatUtils.parseIsoToEpochMillisOrNull(...)`** (hàm mới): parse ISO-8601 bằng
  `java.time.OffsetDateTime` (đã bật `coreLibraryDesugaring` từ trước cho minSdk 24) ra
  epoch millis, dùng cho `date_added` và Custom Column kiểu `datetime`.
- **API mới trên `MetadataDatabaseHelper`**: `upsertCustomColumnDefs()`,
  `upsertCustomColumnValues()`, `getAllCustomColumnDefs()`,
  `getCustomColumnValuesForBook()` — nền tảng cho UI Sắp xếp/Filter theo Custom Column ở
  Phase tiếp theo.

## 5.30 — Quét mã QR để nhập thư viện Calibre-on-GDrive

Thêm 1 cách nhập thư viện Calibre-on-GDrive mới bằng camera, bên cạnh cách
gõ tay link/ID đã có: nút "Scan QR" cạnh "+ Add another library" trong
`GDriveOpdsLibraryListBottomSheet`.

- **Dependency mới**: CameraX (`camera-core`/`camera-camera2`/
  `camera-lifecycle`/`camera-view` 1.5.1) + ML Kit Barcode Scanning
  (`barcode-scanning` 17.3.0), cả hai chạy hoàn toàn on-device, không kéo theo
  Play Services nặng, không phát sinh network/OAuth scope mới.
- **Permission mới**: `android.permission.CAMERA` +
  `<uses-feature android:name="android.hardware.camera" android:required="false" />`
  trong `AndroidManifest.xml` — khai `required="false"` vì QR chỉ là cách
  nhập bổ sung, máy không có camera vẫn gõ tay ID/link được như trước.
- **`QrScanActivity` (file mới)**: màn hình full-screen dùng CameraX
  `ImageAnalysis` + ML Kit `BarcodeScanner`, chỉ có 1 việc là decode 1 mã QR
  rồi trả chuỗi text thô về qua `ActivityResultLauncher` (không dùng
  `startActivityForResult()` kiểu cũ). Không tự parse/validate link.
- **`GDriveOpdsLibraryListBottomSheet`**: đăng ký `qrScanLauncher` ở field
  level, nút "Scan QR" mở `QrScanActivity`; kết quả quét được đưa qua
  `GDrivePublicFileClient.extractFileId()` (đúng hàm luồng gõ tay đang dùng)
  rồi mở `AddGDriveOpdsLibraryDialogFragment` với link đã pre-fill.
- **`AddGDriveOpdsLibraryDialogFragment`**: thêm factory method
  `newInstanceFromQrScan()` (không đổi `newInstance()`/`newInstanceForEdit()`
  đã có) — pre-fill ô nhập rồi tự gọi `scheduleValidation()` ngay khi dialog
  mở ra, vì quét QR không tạo sự kiện gõ phím cho `TextWatcher` bắt.
- **Tái sử dụng nguyên vẹn** toàn bộ pipeline thêm thư viện thủ công đã có:
  `GDrivePublicFileClient.fetchMetadata()`/`extractFileId()`,
  `GDriveOpdsLibraryStore.save()`, `autoDownloadAndCache()` — luồng QR chỉ
  thay thế bước gõ tay bằng camera đọc ra 1 chuỗi text, không viết logic
  parse/validate/save riêng.
- String resources mới (`gdrive_opds_qr_scan_button` và các thông báo
  quyền camera/lỗi camera/link không hợp lệ) ở cả `values/strings.xml` và
  `values-vi/strings.xml`.

## 5.16 — Đổi Package Name: net.sengjea.calibre → net.mrphan.calibre

Đổi toàn bộ Application ID/package name của app. **Đây là thay đổi identity
của app** — Android coi đây là 1 app hoàn toàn khác app cũ (không cài đè lên
bản `net.sengjea.calibre` đang có trên máy, dữ liệu/cài đặt cũ không tự
chuyển sang).

- `namespace`/`applicationId`/`manifestPlaceholders["appAuthRedirectScheme"]`
  trong `app/build.gradle.kts`, 2 dòng `-keep class` trong
  `app/proguard-rules.pro`, toàn bộ `package .../import .../android:name=...`
  trong `AndroidManifest.xml` và ~220 file `.java`/`.kt` (bao gồm cả di
  chuyển thư mục `app/src/main/java/net/sengjea/calibre` →
  `app/src/main/java/net/mrphan/calibre`), cùng vài chỗ nhắc tên package
  trong comment ở `layout/*.xml`, `values/strings.xml`, `values/arrays.xml`.
- Không có Firebase/Hilt/Room/Compose/JNI/reflection theo package name trong
  project này, nên không phát sinh thêm việc gì ở các mục đó.
- `android:authorities` của FileProvider dùng sẵn `${applicationId}.fileprovider`
  (placeholder Gradle) nên tự động đổi theo, không phải sửa tay.
- **Không đổi**: OAuth Google Drive (Client ID/Secret hardcode trong
  `GoogleDriveOAuthConfig.java`, xem CLOUD_SETUP.md mục 2) và redirect URI
  OneDrive/Box/Dropbox (dùng chung 1 loopback server nội bộ
  `http://localhost/oauth2redirect`, xem `OAuthProviderSpec.REDIRECT_PATH`) —
  cả hai đều KHÔNG phụ thuộc package name/SHA-1, nên không cần đăng ký lại gì
  trên Google/Microsoft/Box/Dropbox console.
- **Cần cập nhật thủ công**: restriction "Application restrictions" của Drive
  API key hạn chế (`GDRIVE_PUBLIC_API_KEY`, dùng cho thư viện Calibre trên
  GDrive công khai — xem CLOUD_SETUP.md mục 3b) trên Google Cloud Console,
  hiện đang khai package name `net.sengjea.calibre` + SHA-1 chữ ký app; phải
  đổi sang `net.mrphan.calibre` (giữ nguyên SHA-1 nếu vẫn ký cùng 1 keystore).
- **Giữ nguyên có chủ đích** (không đổi): 2 dòng lịch sử trong
  `CHANGELOG_MIGRATION.md` ghi lại quyết định TỪNG đưa ra ở lần migration
  trước, và các dòng liên quan trong `CHANGELOG.md` các bản trước — đây là
  log lịch sử ghi lại đúng những gì đã đúng tại thời điểm đó, sửa lại sẽ làm
  sai lệch lịch sử. Cũng giữ nguyên các dòng javadoc "Created by/User:
  sengjea" (tác giả gốc 2013) — đó là ghi công tác giả, không phải package
  name, không thuộc phạm vi đổi package.

## 5.15 — Công tắc ẩn/hiện Title + Progress ở Grid view

Settings > Giao diện: thêm 1 `CheckBoxPreference` duy nhất
(`pref_grid_show_title_progress`, mặc định bật) — tắt thì ẩn cả tựa sách lẫn
% tiến trình đọc ở chế độ Grid (cả 2 tab), chỉ còn lướt ảnh bìa sát nhau.
List/Simple list/Carousel không bị ảnh hưởng, luôn hiện tựa sách như cũ.

- `StorageUtils.PREF_GRID_SHOW_TITLE_PROGRESS` (mới, hằng số dùng chung, theo
  đúng tiền lệ `PREF_ROOT_DIR_PATH`) — 1 nguồn duy nhất cho pref key, dùng lại
  ở cả `CollectionFragment`, `BookRecyclerAdapter`, `OPDSBrowserFragment`,
  `BookCursorAdapter`.
- **Device Library**: `BookRecyclerAdapter.setShowGridTitleProgress()` (mirror
  `setViewMode()`) — chỉ áp dụng khi `mViewMode == ViewMode.GRID`.
  `CollectionFragment.refreshGridDisplayOptions()` đọc lại pref mỗi
  `onStart()` (không dùng `OnSharedPreferenceChangeListener` vì màn hình
  Settings luôn mở sau khi tab này đã `onPause()`, listener sẽ bỏ lỡ thay
  đổi) và đẩy vào adapter mới tạo trong `showBooks()`.
- **Calibre Library (OPDS)**: `BookCursorAdapter.bindView()` đọc thẳng
  `SharedPreferences` mỗi lần bind khi `mGridMode == true` (theo đúng tiền lệ
  `loadCoverBitmap()` đã làm), không thêm tham số constructor mới để khỏi
  phải sửa 4 overload constructor + các call site không liên quan (ví dụ
  `BookSearchActivity`). `OPDSBrowserFragment.onStart()` ép
  `notifyDataSetChanged()` để các dòng đang hiện vẽ lại ngay khi tab quay về
  foreground, vì `CursorAdapter` không tự vẽ lại chỉ vì SharedPreferences đổi
  ở nơi khác.
- Không đổi `book_item_grid.xml`/`item_book_grid_select.xml` — 2 layout này đã
  tự ẩn `bi_progress` khi không có dữ liệu, code Java chỉ thêm 1 lớp điều
  kiện đè lên.

## 5.14 — Nút "Sort by..." (Device Library + Calibre Library)

Thêm nút "Sort by..." ngay dưới "Filter by..." trên toolbar của cả 2 tab, mở dialog chọn
trường sắp xếp + chiều Ascending/Descending (giao diện giống mock-up: banner màu primary
"Sort Ascending"/"Sort Descending" ở đầu, danh sách trường bên dưới).

- `SortByDialog` (mới, dùng chung 2 tab, cùng phong cách với `ViewModePickerDialog`):
  `dialog_sort_by.xml` tái dùng `item_view_mode_dialog_row.xml` cho từng dòng trường; bấm vào
  banner chỉ đảo chiều tại chỗ, bấm vào 1 trường mới thực sự áp dụng + đóng dialog.
- **Device Library** (`CollectionFragment`): `MetadataDatabaseHelper#getBooksSorted` — chỉ 4
  trường có dữ liệu thật trong DB cục bộ: Title, Author, Recent (proxy qua `_id` — không có cột
  ngày thêm sách), Last Opened (cột `last_locator_updated_at` có sẵn). Lựa chọn được lưu vào
  `pref_collection_sort_field`/`pref_collection_sort_ascending`.
- **Calibre Library** (`OPDSBrowserFragment`): `OPDSPage#getBooksSorted` — sắp xếp lại ngay
  trên client các entry ĐÃ TẢI của trang hiện tại (feed OPDS phân trang từ server, không thể
  sort xuyên trang), dùng dữ liệu đã parse sẵn trong `OPDSEntry`: Title, Author, Rating,
  Published, Last Modified (`updated`), Series. Lựa chọn lưu vào
  `pref_opds_sort_field`/`pref_opds_sort_ascending`, áp dụng lại mỗi lần `loadCurrentPage()`.
- Icon mới: `ic_menu_sort` (toolbar + trường Title), `ic_sort_calendar` (Published/Recent),
  `ic_sort_clock` (Last Modified) — tái dùng `ic_stat_person`/`ic_action_star`/`ic_stat_series`
  đã có sẵn cho Author/Rating/Series.
- **Giới hạn đã biết**: Device Library KHÔNG có Rating/Published/Series (không lưu trong DB cục
  bộ) — khác với mock-up gốc (giao diện Calibre desktop) có đủ 9 trường + Custom column; muốn
  đủ như bản desktop cần đọc `metadata.calibre` JSON của từng sách (xem `DeviceBookMetadataWriter`)
  và cache lại vào DB, nằm ngoài phạm vi thay đổi này.
- Đồng thời dọn các ký tự "--" còn sót trong comment XML (`dialog_view_mode_picker.xml`,
  `item_view_mode_dialog_row.xml`, `collection_fragment.xml`, `opds_fragment.xml`) — không hợp
  lệ theo spec XML dù hầu hết parser vẫn bỏ qua được.

## 4.24 — Dialog "Thông tin thư viện cloud" + Đồng bộ 2 chiều 1 chạm trong tab Connection

Tab Connection: bấm vào Google Drive/OneDrive/Box/Dropbox đã đăng nhập giờ mở dialog thông tin
thư viện + đồng bộ 2 chiều 1 chạm, thay vì mở thẳng trình duyệt file.

- `CloudRemoteInfoBottomSheetFragment` (mới, cùng phong cách UI với
  `GDriveLibraryInfoBottomSheetFragment`): hiện "Đồng bộ lần cuối" + nút "Đồng bộ" + 3 cột số
  liệu (Sách/Tác giả/Thẻ — Device Library cục bộ không lưu Publisher/Series, xem
  `MetadataDatabaseHelper#getLocalLibrarySummary`) + nút "Duyệt file" (vẫn mở
  `CloudBrowserActivity` như hành vi cũ) + nút "Ngắt kết nối".
- Nút "Đồng bộ" enqueue `DeviceLibrarySyncWorker.DIRECTION_FULL_SYNC` (mới) qua
  `DeviceLibrarySyncScheduler#enqueueFullSync` — chạy `uploadBooks(null)` rồi
  `downloadMissingBooks()` trong CÙNG 1 lần worker, dùng lại engine 2 chiều đã có sẵn trong
  `DeviceLibrarySyncManager` (không viết lại từ đầu).
- `CloudRemoteSyncPrefs` (mới): lưu mốc "đồng bộ lần cuối" riêng theo từng remote id, tách khỏi
  schema JSON của `CloudRemoteStore`.
- `BackgroundJobsBottomSheet` hiện nhãn riêng "Đồng bộ 2 chiều với %1$s" cho job
  `DIRECTION_FULL_SYNC` thay vì nhầm thành "Đang tải lên".
- Long-press (đăng xuất) trên các card này **không đổi hành vi** — chỉ dọn thêm
  `CloudRemoteSyncPrefs` cho gọn dữ liệu khi ngắt kết nối (từ cả dialog mới lẫn long-press cũ).
- WebDAV/FTP/NAS không nằm trong phạm vi thay đổi này — giữ nguyên hành vi duyệt file cũ.
- **Giới hạn đã biết**: đồng bộ 2 chiều hiện tại chỉ tải về sách đang THIẾU ở local
  (`downloadMissingBooks()` không đổi) — sách tồn tại ở cả 2 phía nhưng bản cloud mới hơn sẽ
  KHÔNG được tải lại đè lên bản cục bộ. Xem khối REVIEW NOTES ở cuối file.



Nâng cấp lớn cho hệ thống đồng bộ 2 chiều giữa thiết bị và cloud remote (Google Drive/Dropbox/
Box/OneDrive/WebDAV/FTP), theo mô hình file-sync engine của readest. Không đổi UI/UX người dùng
thấy — thay đổi hoàn toàn ở tầng logic bên dưới `DeviceLibrarySyncManager`.

**1. Cấu trúc remote mới, khoá bất biến theo `uuid`** (`RemoteTreeResolver` — file mới, thay thế
`GoogleDriveFolderResolver`):

- Trước: mirror cây thư mục `Author/Title/` — đổi tên sách trên Calibre Desktop làm sync tưởng
  nhầm là sách mới, upload trùng, rác thư mục cũ không bao giờ dọn.
- Sau: `<remoteRoot>/books/<uuid>/{book.<ext>, cover.<ext>}` — `uuid` không đổi theo vòng đời
  sách, đổi tên/tác giả không ảnh hưởng gì tới remote.
- Khi 2 thiết bị race nhau tạo cùng 1 thư mục/file lần đầu sync gần như đồng thời, cả hai đều hội
  tụ về "người thắng xác định" (refId nhỏ nhất theo thứ tự từ điển) ở lần sync kế tiếp, không cần
  khoá phân tán.

**2. `SyncManifest` schema v2** — thêm `contentHash`/`coverHash` (SHA-256, `FileHashUtils` — file
mới) và `updatedAt`/`deletedAt` cho từng entry. Đọc được manifest v1 hiện có mà không crash (lazy
migration — entry cũ tự động dùng cấu trúc mới ở lần upload kế tiếp).

**3. Bỏ qua upload thừa bằng content-hash** (`DeviceLibrarySyncManager#uploadOneBook`) — so sánh
nhanh bằng `mtime` trước, chỉ tính SHA-256 khi `mtime` đã đổi, chỉ thực sự upload khi hash khác.
Cover được đánh giá độc lập với sách (trước đây ăn theo, gây upload lại cover ở mọi lần sync).

**4. Tombstone khi xoá sách** — sách bị xoá khỏi 1 thiết bị (giữ `uuid`, `lpath = NULL` trong DB,
theo đúng hành vi có sẵn của `MetadataDatabaseHelper#deleteBook`) được đánh dấu `deletedAt` trong
manifest thay vì bị các thiết bị khác coi là "sách mới cần tải lại" ở lần sync sau
(`MetadataDatabaseHelper#getAllUuidsWithNullLpathArray` — hàm mới).

**5. Chống lost-update (merge-before-write)** — trước khi ghi `manifest.json`, tải lại bản mới
nhất từ remote và hợp nhất theo `updatedAt` (last-write-wins) với những gì phiên sync hiện tại đã
sửa, để sách do 1 thiết bị khác vừa thêm trong lúc phiên này đang chạy không bị ghi đè mất.

**6. Bounded concurrency pool** (`BoundedTaskPool` — file mới) — tối đa 4 upload/download đồng
thời thay vì vòng `for` tuần tự, giảm đáng kể độ trễ tổng trên mạng di động độ trễ cao. 1 sách lỗi
không làm chết cả phiên sync.

**7. Dropbox: streaming download + chunked upload session** (`DropboxCloudProvider`) — bỏ
`body.bytes()` (nạp hết file vào RAM, có thể OOM với audiobook/PDF lớn) sang ghi thẳng từ network
stream ra đĩa; file >150MB tự động chuyển sang `upload_session` (start/append_v2/finish) thay vì
lỗi thẳng do vượt giới hạn "simple upload" của Dropbox.

**8. Lỗi có ngữ nghĩa** (`CloudSyncException`, `BackoffRetry` — file mới) — Dropbox/Google Drive
giờ ném lỗi phân loại (`AUTH_FAILED`/`NOT_FOUND`/`RATE_LIMIT`/`NETWORK`/`CONFLICT`) thay vì
`IOException` chung chung. `DeviceLibrarySyncWorker` không còn retry vô hạn cho lỗi xác thực vĩnh
viễn; lỗi rate-limit (429) tôn trọng header `Retry-After` của server thay vì backoff mù.

**Tương thích ngược:** `CloudProvider#uploadFile(File, String)` (2 tham số) vẫn hoạt động y hệt
cũ qua default method — không cần sửa `SyncProgress`. Entry manifest v1 hiện có được đọc bình
thường và tự nâng cấp dần (lazy migration), không cần migration script/thao tác thủ công từ người
dùng.



**1. Sửa lỗi TTS phát nền tự tắt khi chuyển sang app khác** (`TtsPlaybackService`):

- Nguyên nhân gốc: `ttsController.state` là `StateFlow` khởi tạo sẵn bằng `TtsPlaybackState.idle()`
  (`isPlaying=false`, `sentenceCount=0`). Ngay khi `onCreate()`'s `.onEach{}.launchIn(lifecycleScope)`
  bắt đầu lắng nghe, nó nhận ngay giá trị khởi tạo này — trùng khớp y hệt điều kiện "phiên đọc đã
  kết thúc" ở dưới, khiến `stopSelfSafely()` bị gọi ngay lập tức, giải phóng `mediaSession` trước cả
  khi người dùng bấm phát. Vì `mediaSession` đã "chết" từ đầu, Android không còn lý do giữ sống
  Foreground Service loại `mediaPlayback` khi app rời foreground.
- Fix: thêm cờ `hasEverStartedPlaying`, chỉ set `true` khi thực sự có `sentenceCount > 0`. Điều
  kiện tự dừng service giờ chỉ kích hoạt khi cờ này đã `true` trước đó, không còn bị kích hoạt bởi
  giá trị `idle()` ban đầu.
- Thêm `android:stopWithTask="false"` cho khai báo `<service>` trong `AndroidManifest.xml` để
  Service không bị hệ thống dừng khi người dùng vuốt app khỏi Recents.

**2. Nút "+" nhập sách thủ công trên toolbar Device Library** (`CollectionFragment`):

- Icon `ic_menu_add_book` mới, `showAsAction="always"` để luôn hiện ngay bên trái nút overflow
  (3 chấm) — trước đó không có cách nào thêm sách vào Device Library ngoài Calibre server/cloud
  sync/rescan thư mục.
- Mở SAF `ActivityResultContracts.OpenMultipleDocuments()` (`"*/*"`) cho phép chọn 1 hoặc nhiều
  file cùng lúc; lọc theo đúng danh sách "Accepted Filetypes" hiện có trong Settings
  (`CalibreService.getAcceptedExtensionsArray`), copy vào `StorageUtils.getRootDirectory()` trên
  1 thread riêng (I/O), tránh ghi đè file trùng tên (hậu tố `" (1)"`, `" (2)"`...), rồi tái sử dụng
  `triggerRescan()` sẵn có để BookCrawler nhận diện và hiện sách mới trong danh sách.
- **Chưa build/run-verified** (môi trường chỉnh sửa không có Android SDK) — cần build thử và kiểm
  tra trên thiết bị thật, đặc biệt là quyền SAF và các định dạng file không có MIME type chuẩn
  (mobi/azw3/fb2/pdb).

## 4.18 — "Từ điển phát âm" (Pronunciation Dictionary) cho chế độ TTS "Đọc cho nghe"

Thêm màn hình quản lý một từ điển phát âm TOÀN CỤC (áp dụng cho mọi sách) cho phép người dùng
tự định nghĩa cách đọc thay thế cho tên riêng/từ khó đọc (ví dụ `David` → `Đa vít`), để engine
TTS phát âm đúng hơn mà không làm lệch chữ hiển thị trên màn hình so với nguyên văn trong sách.

- Icon "sách mở" mới (`ic_selection_dictionary`, tái dùng từ nút Từ điển của thanh chọn văn
  bản) trên `TtsPlayerBottomSheet`, ngay bên phải icon "Sherpa TTS" — mở
  `TtsDictionaryManagerActivity` (danh sách + Thêm/Sửa/Xóa).
- Bảng mới `tts_pronunciation_dictionary` trong `MetadataDatabaseHelper`
  (`DATABASE_VERSION` 11 → 12), cột `source_text` có `UNIQUE COLLATE NOCASE`.
- Package mới `net.sengjea.calibre.reader.tts.dictionary`: `TtsDictionaryEntry`,
  `TtsDictionaryStore` (CRUD blocking, cùng phong cách với `TtsChapterProgressStore` có sẵn),
  `TtsDictionaryReplacer` (thay thế nguyên từ, không phân biệt hoa/thường, giới hạn: không nhận
  đúng ranh giới khi cả 2 phía đều là từ tiếng Việt có dấu — xem KDoc), `TtsDictionaryManagerActivity`
  + `TtsDictionaryEntryAdapter`.
- `TtsController#startChapter` nạp lại từ điển mỗi lần bắt đầu 1 chương (áp dụng thay đổi ngay
  không cần restart app), tính riêng `speechSentences` (đã qua từ điển) song song với
  `sentences` (bản gốc, vẫn là thứ duy nhất đưa vào `currentSentenceText` hiển thị). Xử lý race
  condition giữa việc nạp từ điển (coroutine bất đồng bộ) và vòng phát bằng cách fallback về câu
  gốc cho tới khi nạp xong.
- **Chưa build/run-verified** (môi trường chỉnh sửa không có Android SDK) — cần build thử và
  kiểm tra migration DB (đặc biệt là từ DATABASE_VERSION 11 lên 12) trên thiết bị thật trước khi
  phát hành.

## 4.2 — Tab "Calibre Library" tự chuyển sang thư viện GDrive khi mất kết nối server

Fixes the blank-screen bug where a *configured but unreachable* OPDS Content Server (wrong
IP, server off, network change, ...) left the "Calibre Library" tab completely empty with no
error and no progress bar — the `p == null` branch of `onParseComplete()` silently fell
through to a no-op `updateCurrentPage()` instead of being routed anywhere. This case is
distinct from "no server configured yet" (`noServerInPreferences()`) and from HTTP 401
(`onUnauthorized()`), both of which are unchanged.

- `OPDSBrowserFragment` now detects this specific failure (`mLoadingRootFeed` still true when
  `onParseComplete(null)` fires) and stays on the tab instead of bouncing out to the manual
  "Connect to server" dialog.
- Shows a dismissible-by-retry banner ("Couldn't connect to the Calibre server...") with a
  "Retry" button, directly above the list — new `opds_offline_banner` block in
  `activity_opds.xml`, new strings in `values/strings.xml` and `values-vi/strings.xml`.
- Below the banner, lists every Calibre library already saved on Google Drive
  (`GDriveOpdsLibraryStore.getAll()`), reusing the exact same card style as the real "choose a
  library" screen (`item_library_card.xml`) via a new `GDriveOpdsLibraryCardAdapter` (a
  `GDriveOpdsLibrary`-flavored twin of `LibraryCardAdapter`). Tapping a card opens
  `GDriveLibraryWebViewActivity`, identical to `GDriveOpdsLibraryListBottomSheet#openBrowse()`.
  If no GDrive library has been saved yet, the banner switches to a "no libraries yet" message.
- "Retry" resets the fallback state and calls `loadRootFeed()` again; returning to this tab
  from another tab (`onStart()`) only re-renders the cached fallback list, it never silently
  retries the network in the background.
- View-mode/filter/search menu items are hidden while the fallback screen is showing, same
  treatment as the real library-selection screen.
- Scope: `OPDSBrowserFragment.java` (new fields/methods only, no existing method signatures
  removed), new `GDriveOpdsLibraryCardAdapter.java`, `activity_opds.xml`, `strings.xml` /
  `values-vi/strings.xml`. `OPDSLoader.java`, `noServerInPreferences()`/`onUnauthorized()`, and
  the GDrive WebView browsing flow itself were not touched.

## 3.51 — Unified "Cài đặt đọc" (Reader Settings) tabbed panel

Merges the reader-settings-tabbed feature (built and tested against 3.47 as 3.48) onto the
3.50 GDrive-webview-upgrade base. No GDrive/cloud code was touched; this release is purely
additive on top of 3.50.

- `☰` menu → "Hiển thị" → "Tinh chỉnh" (`PreferencesAction`) now opens a single tabbed
  bottom sheet (`ReaderSettingsSheet`) instead of reporting "không hỗ trợ". Tabs, icon-cloned
  from Readest's mobile UI (Aa / grid / palette / hand / speaker):
  - **Phông chữ** — font size, font weight, font family (now incl. Cursive/Monospace),
    "Ghi đè kiểu của sách" master switch.
  - **Bố cục** — line height, page margins, 2-column toggle, plus (only while "Ghi đè kiểu
    của sách" is on) paragraph spacing/indent, word/letter spacing, justify, hyphenation.
  - **Màu** — brightness, the 11 color themes, light/dark toggle (same logic as the existing
    "Màu nền & Tương phản" footer button, just re-hosted).
  - **Thao tác** — "Giữ màn hình sáng khi đọc" (new: a plain window flag, persisted).
  - **Đọc to** — default TTS voice/speed for the next read-aloud session, background-music
    shortcut.
- Only tabs with a real, wired feature were built — no "Ngôn ngữ/Dịch", "Trợ năng", or "TTS
  Highlighting" tabs, since this app has no translation engine, no accessibility settings to
  configure, and no word/sentence-highlight-while-reading mechanism yet.
- Every new Layout-tab field is a real `org.readium.r2.navigator.epub.EpubPreferences`
  property (font weight, page margins, column count, word/letter/paragraph spacing,
  paragraph indent, text align, hyphens) — nothing was added as a UI-only no-op.
- `EpubReaderActivity`: all preference mutations (font/layout/color/scroll) now funnel through
  one `buildCurrentEpubPreferences()` + `applyAllEpubPreferences()` pair instead of each
  `setXxx()` submitting only its own field — fixes a pre-existing bug where changing font size
  silently reset the color theme (Readium's `submitPreferences()` replaces the whole set, it
  doesn't merge).
- The old footer quick actions (`FontSizeAction` → "Cỡ chữ", `ColorContrastAction` → "Màu nền &
  Tương phản") and their dialogs (`dialog_reader_display_settings.xml`,
  `dialog_reader_color_settings.xml`) are untouched and still work exactly as before; the new
  sheet is purely an additional entry point, not a replacement.
- Scope of this release: `reader/chrome/settings/**` (new package), `EpubReaderActivity.kt`
  (only `openPreferences()` + the preference-building/restore functions touched), and the
  matching `res/layout|drawable` + `strings.xml` additions. `SettingsActivity`, `preference.xml`,
  `CalibreService`, cloud providers, sync logic, and DB schema were not touched.

## 3.50 — GDrive library WebView upgrade

Replaced `GDriveOpdsLibraryBrowserActivity` (RecyclerView-based OPDS browser) with
`GDriveLibraryWebViewActivity` + `GDriveLibraryJsBridge`, embedding the "Thư viện mini" web UI
(`assets/gdrive_library/`) in a WebView over the existing local OPDS HTTP server — same
architecture as `ContentServerBrowserActivity`. Adds library-refresh strings/UI on top of the
GDrive library list.

## 3.48 — Reader Settings tabbed panel (first implementation, pre-3.50 base)

Original implementation of the feature merged into 3.51 above, built against a pre-GDrive-
webview snapshot (~3.47). Superseded by 3.51, which carries the same feature onto the current
3.50 codebase.

<!--
REVIEW NOTES — rà soát trước khi đóng gói zip (ngày: 2026-07-25)

1. [FIXED] Comment XML mới thêm chứa chuỗi "--" giữa nội dung, khiến file không "well-formed"
   (XML spec cấm "--" bên trong <!-- --> ngoại trừ ngay trước "-->") — file:
   app/src/main/res/values/strings.xml (comment nhóm string cloud_remote_info_*), dòng: gần
   cuối file; và app/src/main/res/layout/bottomsheet_cloud_remote_info.xml, dòng: comment
   "3 cột số liệu".
   Nguyên nhân: viết comment theo văn phong "A -- B" (giống nhiều comment Java // trong
   codebase) nhưng áp dụng nhầm vào comment XML <!-- --> vốn có quy tắc khác.
   Cách sửa: thay "--" bằng dấu gạch ngang dài "—" (em dash) trong 2 comment mới này. Đã xác
   nhận lại bằng cách parse toàn bộ *.xml trong app/src/main/res bằng
   xml.etree.ElementTree — không còn lỗi "not well-formed" nào.

2. [FIXED] Lỗi XML có sẵn TỪ TRƯỚC (không phải do lần nâng cấp này gây ra, phát hiện khi rà
   soát toàn bộ *.xml trong app/src/main/res) — file:
   app/src/main/res/layout/item_sync_info_field.xml, dòng 6 (comment đầu file).
   Nguyên nhân: comment giải thích lý do không dùng <merge> làm root chứa chuỗi "gay NPE +
   trung id giua 5 hang -- xem BookSyncInfoSheet.kt crash NPE." — cùng lỗi "--" như mục 1,
   khiến AAPT/AAPT2 có thể từ chối build resource này (XML không well-formed).
   Cách sửa: thay "--" bằng "—" tại đúng vị trí đó, không đổi nội dung/ý nghĩa comment.
   Đã xác nhận lại: không còn file *.xml nào trong app/src/main/res bị lỗi "not well-formed".

3. [KNOWN LIMITATION] "Đồng bộ 2 chiều" (DIRECTION_FULL_SYNC) không xử lý xung đột nội dung khi
   1 sách tồn tại ở CẢ 2 phía (điện thoại + cloud) nhưng bản trên cloud mới hơn (vd bị 1 thiết
   bị khác ghi đè) — file: app/src/main/java/net/sengjea/calibre/DeviceLibrarySyncManager.java,
   hàm downloadMissingBooks() (không sửa trong lần nâng cấp này).
   Lý do: downloadMissingBooks() chỉ tải sách CHƯA CÓ FILE CỤC BỘ (!localFile.exists()) — đây
   là giới hạn có chủ đích của engine hiện tại (tránh nguy cơ mất dữ liệu đọc dở/annotation nếu
   ghi đè nhầm bản cục bộ đang mở dở trong Reader). Yêu cầu người dùng ("update dữ liệu mới hơn
   từ cloud xuống điện thoại nếu có") vì vậy chỉ được đáp ứng một phần (sách MỚI hoàn toàn trên
   cloud thì tải về được, sách đã tồn tại 2 phía thì không so sánh lại).
   Đề xuất theo dõi: thiết kế riêng 1 prompt/PR khác — so sánh hash/mtime 2 chiều cho sách đã
   tồn tại ở cả 2 phía, và xử lý cẩn thận trường hợp sách đang mở trong Reader đúng lúc bị ghi
   đè (khoá file/hoãn ghi đè cho tới khi đóng Reader).

4. [KNOWN LIMITATION] Dialog "Thông tin thư viện cloud" chỉ hiện 3 cột số liệu (Sách/Tác
   giả/Thẻ), không có Publisher/Series như ảnh mẫu người dùng gửi (5 cột) — file:
   app/src/main/java/net/sengjea/calibre/MetadataDatabaseHelper.java (bảng "metadata" không có
   cột publisher/series), app/src/main/res/layout/bottomsheet_cloud_remote_info.xml.
   Lý do: Device Library cục bộ (MetadataDatabaseHelper, bảng metadata) khác với
   GDriveLibraryDb (đọc từ 1 file library.db calibre đầy đủ, có bảng publishers/series riêng)
   — không bịa số liệu 0/giả để tránh gây hiểu lầm cho người dùng.
   Đề xuất theo dõi: hạng mục riêng — thêm cột publisher/series vào bảng metadata + tăng
   DATABASE_VERSION (hiện là 12) + đồng bộ 2 trường này từ Calibre Content Server khi thêm
   sách vào Device Library. Không làm vội trong lần này vì đụng migration DB + nhiều luồng ghi
   dữ liệu khác.

5. [KNOWN LIMITATION] Không thêm bản dịch cho các string mới (cloud_remote_info_*,
   background_job_title_full_sync) vào values-de/strings.xml, values-it/strings.xml,
   values-vi/strings.xml — file: app/src/main/res/values/strings.xml (chỉ thêm ở đây).
   Lý do: kiểm tra bằng `find app/src/main/res -maxdepth 1 -type d -name "values*"` xác nhận
   project có 4 thư mục values*/ (values, values-de, values-it, values-vi), nhưng values-de/it/vi
   là các bộ dịch CŨ, KHÔNG đầy đủ (vd values-de chỉ 87 dòng, values-it 89 dòng, values-vi 194
   dòng so với values/ có 792+ dòng) và KHÔNG chứa các string của tính năng tương tự gần đây
   nhất (background_job_title_upload/download, gdrive_library_info_title — đã kiểm tra bằng
   grep, không có trong values-vi/de/it) — theo đúng tiền lệ này, string mới của lần nâng cấp
   này cũng chỉ thêm vào values/strings.xml (mặc định), không phá vỡ quy ước hiện có của
   project.
   Đề xuất theo dõi: nếu muốn dịch đầy đủ, cần 1 hạng mục riêng rà soát lại toàn bộ chênh lệch
   giữa values/ và values-de/it/vi (chênh lệch này đã tồn tại từ trước, không phải do lần nâng
   cấp này gây ra).

6. [KNOWN LIMITATION] Không build được bằng Gradle thật để xác nhận biên dịch (môi trường rà
   soát không có Android SDK/Gradle offline cache, không có mạng để tải Gradle
   distribution/dependencies) — đã rà soát thay thế bằng cách: (a) đọc lại từng file đã
   sửa/tạo mới bằng mắt, đối chiếu chữ ký method thật (uploadBooks, downloadMissingBooks,
   syncProgressOnly, CloudTokenManager.getInstance, GDriveCoverDiskCache.clearAll,
   CloudBrowserActivity.EXTRA_REMOTE_ID, TABLE_METADATA/TABLE_TAGS...) trong codebase gốc; (b)
   đếm cân bằng dấu ngoặc nhọn {} ở mọi file .java bị đổi; (c) parse toàn bộ *.xml trong
   app/src/main/res bằng xml.etree.ElementTree để bắt lỗi cú pháp resource; (d) đối chiếu mọi
   R.string/R.layout/R.id được tham chiếu trong code mới với những gì thực sự khai báo trong
   strings.xml/layout XML, không thấy thiếu sót nào.
   Đề xuất theo dõi: chạy `./gradlew assembleDebug` (hoặc build trong Android Studio) trên máy
   có Android SDK trước khi phát hành, theo đúng checklist kiểm thử thủ công ở mục 5 của prompt
   nâng cấp.
-->
