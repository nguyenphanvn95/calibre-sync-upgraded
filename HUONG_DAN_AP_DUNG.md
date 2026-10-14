# Mục 3 — Bỏ AsyncTask: hướng dẫn áp dụng

Zip này chứa 4 file (giữ nguyên đường dẫn `app/src/main/java/net/mrphan/calibre/...`), copy đè
lên project của bạn:

| Việc cần làm | File |
|---|---|
| Ghi đè | `Send2EreaderClient.java` (vẫn là Java — chỉ đổi AsyncTask → `ExecutorService` riêng, mẫu `CoverDecodeExecutor`) |
| **Xoá** `AsyncDownloader.java`, thêm mới `AsyncDownloader.kt` | AsyncTask → suspend function |
| **Xoá** `ConnectServerDialogFragment.java`, thêm mới `ConnectServerDialogFragment.kt` | `AsyncIPResolver` (AsyncTask) → coroutine trên `viewLifecycleOwner.lifecycleScope` |
| **Xoá** `OpenBookActivity.java`, thêm mới `OpenBookActivity.kt` | Đổi base class `Activity` → `AppCompatActivity` (để có `lifecycleScope`) + gọi `AsyncDownloader.download()` mới |

Không cần sửa gì ở Manifest/styles — `OpenBookActivity` kế thừa `@style/AppTheme`
(`Theme.MaterialComponents.DayNight`), vốn đã là hậu duệ của `Theme.AppCompat`.

## Trước khi build
- Đảm bảo `kotlinx-coroutines-android` (đã có sẵn trong `build.gradle.kts`, dòng ~215) và
  `androidx.lifecycle:lifecycle-runtime-ktx` (kéo theo transitively qua fragment/appcompat theo
  comment sẵn có trong `build.gradle.kts`) có trên classpath — 2 file `.kt` mới dùng
  `lifecycleScope`/`viewLifecycleOwner.lifecycleScope`.
- Sync Gradle rồi build lại — không có thay đổi dependency mới nào cần thêm.

## Nên test thủ công sau khi áp dụng
1. Tải sách qua OPDS/Smart Device App (`OpenBookActivity`/`AsyncDownloader`) — cả trường hợp
   thành công và trường hợp mạng lỗi/URL hết hạn — xem toast "Đã huỷ tải" chỉ hiện đúng lúc.
2. Xoay màn hình hoặc bấm Back giữa lúc đang tải sách — kiểm tra không crash, không leak (có thể
   soi bằng LeakCanary nếu project đã tích hợp).
3. Màn "Kết nối tới Calibre" (`ConnectServerDialogFragment`) — nhập host/port, bấm Kết nối, và
   thử dismiss dialog ngay giữa lúc đang resolve IP.
4. "Gửi tới e-reader" (`Send2EreaderClient`, trong `BookDetailActivity`/`DeviceBookDetailActivity`)
   — upload 1 file, xem callback thành công/thất bại vẫn báo đúng.

## Việc CHƯA làm trong lần này
Mục 4 (gộp `PdfReaderActivity`/`CbzReaderActivity`/`EpubReaderActivity` về 1 Activity) — theo kế
hoạch đã thống nhất, đây là refactor rủi ro cao hơn, cần làm riêng và test kỹ hơn nhiều so với
mục 3. Nhắn khi bạn muốn bắt đầu mục đó.
