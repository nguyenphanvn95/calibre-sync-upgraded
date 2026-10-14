# Chi tiết nâng cấp Calibre Sync

## Mục tiêu

Nâng cấp Calibre Sync để có thêm engine **Nghe Epub TTS** trong chế độ đọc to, đồng thời bổ sung theme, ảnh nền và font chữ từ Waka Toolkit/Bookerly cho trình đọc EPUB. Người dùng có thể nhập thủ công thư mục model Nghe Epub chứa `model-tts.json` và các file `.enc`, tương tự cách nhập model Sherpa TTS.

## Nguồn tham chiếu

- Ứng dụng Nghe Epub đã phân tích: `D:\My Projects\Sachtinhgon\nghe-epub\Nghe_Epub_EPUB_Read_Aloud_TTS.xapk~1`
- Mã giải bằng JADX: `D:\My Projects\Sachtinhgon\nghe-epub\jadx-main`
- Runner kiểm thử TTS: `D:\My Projects\Sachtinhgon\nghe-epub\tts-preview-runner`
- Waka Toolkit: `D:\My Projects\Waka Tools`
- Font Bookerly: `C:\Users\Admin\Downloads\font sách bookerly`
- Dự án Calibre Sync: `D:\AndroidMaker\projects\calibre-sync`

## Nghe Epub TTS

Đã thêm native bridge `PiperNcnn` và các thư viện native `libg2p_ffi.so`, `libpiperncnn.so` cho ABI `arm64-v8a`. Asset G2P tối thiểu được đưa vào `app/src/main/assets/minimal_g2p_data` và được copy vào vùng dữ liệu app khi khởi tạo engine.

Đã thêm importer/store cho model Nghe Epub:

- `NgheEpubModelImporter.kt`
- `NgheEpubModelStore.kt`

Importer cho phép chọn thư mục model, đọc `model-tts.json`, tìm file `.enc`, copy model vào vùng lưu riêng của Calibre Sync và lưu danh sách voice đã nhập.

Đã thêm engine:

- `NgheEpubTtsEngine.kt`

Engine đăng ký id `nghe_epub_tts`, liệt kê voice từ model đã import, load model theo voice đang chọn, tổng hợp PCM qua `PiperNcnn.synthesizeToBuffer(...)`, phát bằng `AudioTrack` ở sample rate `24000`, hỗ trợ cache/prefetch, stop, shutdown, volume và speech rate.

Các điểm tích hợp chính:

- `TtsEngineRegistry.kt`
- `CalibreSyncApplication.kt`
- `SettingsFragment.java`
- `SettingsSheet.kt`
- `preference_tts.xml`
- `strings.xml`
- `proguard-rules.pro`

## Sửa lỗi TTS sau khi test

Đã sửa Edge TTS theo luồng Waka reader trong `D:\My Projects\Waka Tools\src\epub-reader\read-aloud.js`:

- Cập nhật Chromium/Edge User-Agent và `Sec-MS-GEC-Version` theo Waka.
- Bỏ `Origin` chrome-extension hardcode cũ trong WebSocket request.
- Gửi `speech.config` kèm `X-Timestamp`.
- Chuyển tên voice trong SSML từ short name sang dạng `Microsoft Server Speech Text to Speech Voice (vi-VN, HoaiMyNeural)`.
- Giữ output MP3 cho Android `MediaPlayer`; Waka dùng WebM/Opus vì chạy trong browser.
- Chỉ gom binary payload khi WebSocket frame có header `Path:audio`, tránh ghi nhầm frame khác vào file âm thanh.

File đã sửa:

- `EdgeTtsDrm.kt`
- `EdgeTtsClient.kt`

Đã sửa lỗi tốc độ bị đảo ngược: khi người dùng chọn `x2` thì engine phải đọc nhanh hơn, `x0.5` phải đọc chậm hơn. Qua phân tích APK Nghe Epub, tham số native là `lengthScale`, nên Calibre Sync chuyển đổi bằng công thức `lengthScale = 1.0 / speechRateScale` trước khi gọi native.

Đã sửa cơ chế ngắt câu trong `TtsSentenceSplitter.kt` theo hướng gần với Nghe Epub hơn: ưu tiên ngắt ở dấu kết câu mạnh, giữ dấu câu trong đoạn đọc, chỉ ngắt mềm ở dấu phẩy/gạch ngang/bullet khi câu quá dài, và chỉ fallback theo khoảng trắng khi không còn điểm ngắt tự nhiên. Việc này giúp hạn chế tình trạng ngắt giữa câu gây khó nghe.

## Theme, ảnh nền và font chữ

Đã bổ sung font đọc từ Waka Toolkit và Bookerly vào assets của reader, đồng thời đưa các font này vào danh sách chọn font của trình đọc EPUB.

Đã bổ sung các theme màu Waka và các theme ảnh nền Waka. Ảnh nền được đặt trong:

- `app/src/main/assets/reader_backgrounds`

Thumbnail tương ứng cho nút chọn theme được đặt trong:

- `app/src/main/assets/reader_background_thumbnails`

Các file chính đã cập nhật:

- `ReaderFontChoice.kt`
- `ReaderColorTheme.kt`
- `ReaderColorSwatch.kt`
- `ReaderSettingsColorPage.kt`
- `EpubReaderActivity.kt`
- `strings.xml`
- `values-vi/strings.xml`

Đã sửa dark mode của reader để nền tối và chữ sáng hoạt động đúng. Với theme có ảnh nền, CSS reader giờ chèn ảnh nền bằng data URI/base64 để Readium áp dụng ổn định trong nội dung sách.

Đã hoàn thiện nút chọn theme: theme nền ảnh không còn hiển thị ô màu phẳng kèm chữ `Aa`, mà dùng thumbnail của chính ảnh nền tương ứng, giống cách Waka reader hiển thị.

Đã chỉnh ảnh nền phủ toàn màn hình reader. Khi chọn theme có ảnh nền, Calibre Sync đồng bộ ảnh nền ở cả hai lớp:

- CSS bên trong nội dung sách Readium.
- Nền native phía sau navigator, status bar và navigation bar.

Nhờ đó các khoảng trống phía trên/dưới trang không còn bị trắng tách khỏi ảnh nền.

Sau khi test thực tế vẫn còn thấy hai dải trắng ở đầu/cuối trang, đã vá thêm lớp Readium/WebView:

- Ép WebView của navigator trong suốt.
- Chèn CSS nền vào cả document chính, iframe và shadowRoot nếu Readium dùng.
- Dùng lớp `html::before/body::before` fixed phủ toàn viewport để ảnh nền không bị giới hạn bởi vùng nội dung chương.
- Ép các wrapper phổ biến của Readium như viewport/spread/page/container trong suốt.
- Sau khi phát hiện regression ảnh nền không hiện, đã đưa ảnh nền trở lại trực tiếp trên `html/body/:root` và giữ native background làm fallback. Nếu Readium reject `submitPreferences()`, Calibre Sync vẫn áp CSS/native fallback và vẫn lưu theme đã chọn.

Đã thêm thanh kéo **Độ đậm ảnh nền** cho các theme có ảnh nền. Thanh này chỉ hiện khi đang chọn theme ảnh, mặc định `50%` giống Waka reader, được lưu vào SharedPreferences và áp dụng ngay cho cả nền native lẫn CSS trong nội dung sách.

## Phiên bản và What's New

Đã nâng phiên bản ứng dụng lên `3.3.0` với `versionCode 17`.

Đã cập nhật What's New để nêu các thay đổi chính:

- Thêm engine Nghe Epub TTS.
- Bổ sung font Waka Toolkit, Bookerly và theme ảnh nền Waka.
- Sửa dark mode của trình đọc EPUB.
- Cải thiện ngắt câu khi đọc to.
- Sửa tốc độ Nghe Epub TTS bị đảo ngược.

## Build

Lệnh build đã dùng:

```powershell
$env:GRADLE_OPTS='-Djavax.net.ssl.trustStoreType=Windows-ROOT'
.\gradlew.bat assembleDebug --no-daemon
.\gradlew.bat assembleRelease --no-daemon
```

Kết quả gần nhất:

- Debug build thành công.
- APK debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release build thành công trước lần vá CSS cuối.
- APK release: `app/build/outputs/apk/release/app-release.apk`
- Debug build mới nhất đã gom cả bản vá full-screen background/opacity.
- Lệnh build gần nhất cần `--max-workers=1` do máy build thiếu RAM khi Gradle chạy dex/R8 với daemon mặc định.

## Lưu ý

- Nghe Epub TTS hiện phụ thuộc native `arm64-v8a`.
- Lần đầu tổng hợp câu có thể chậm do phải load model và pipeline native.
- Model `.enc` cần đi kèm `model-tts.json` đúng cấu trúc của Nghe Epub.
- Nếu một sách cụ thể vẫn ngắt câu chưa tự nhiên, nên lấy đoạn text gốc của sách đó để tinh chỉnh tiếp quy tắc tách câu.

## Vá ảnh nền phủ status bar và navigation bar

Sau khi test thực tế vẫn còn thấy header/footer hệ thống là màu trơn, đã cập nhật tiếp EPUB reader để chạy edge-to-edge bằng `WindowCompat.setDecorFitsSystemWindows(window, false)`.

Với theme có ảnh nền, `statusBarColor` và `navigationBarColor` được chuyển sang trong suốt, đồng thời tắt contrast enforcement trên Android 10+ để hệ thống không tự phủ lại một lớp màu đặc lên thanh điều hướng. Nhờ đó lớp ảnh nền native phía sau reader có thể phủ cả vùng status bar và navigation bar.

Với theme màu trơn, reader vẫn giữ màu thanh hệ thống theo màu nền của theme như trước để không ảnh hưởng các chế độ đọc không dùng ảnh nền.

Sau khi đối chiếu thêm với APK Waka (`Waka_-_Sch_Ni_Ebook_Truyn.xapk`), xác nhận Waka đặt một `ImageView` full-screen ở lớp native root phía sau WebView/header/bottom của reader. Calibre Sync đã có lớp native tương tự, nhưng còn thiếu một số window/theme flags nên trên máy test status bar và navigation bar vẫn bị Android vẽ màu đặc.

Đã bổ sung tiếp:

- Theme `Theme.Calibre.Reader` khai báo status/navigation bar trong suốt, tắt contrast enforcement và cho phép layout vào vùng cutout.
- `EpubReaderActivity` cấu hình `FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS`, xóa translucent flags cũ, đặt window background trong suốt.
- Duy trì các cờ legacy `SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN` và `SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION` cả khi không bật immersive mode, để ảnh nền native thật sự nằm sau status bar/navigation bar.
- Sau khi test vẫn thấy system bar trắng trên một số máy, đã đặt thêm cùng ảnh nền vào `window.decorView.background` bằng `LayerDrawable` gồm màu nền theme và ảnh nền theo opacity. Nhờ đó vùng status bar/navigation bar vẫn có ảnh nền ngay cả khi Android hoặc WebView chừa inset ngoài layout content.

## Gỡ nền GIF Waka APK

Đã loại bỏ 3 nền GIF clone từ Waka APK (Den.gif, Xam.gif, Sapie.gif) khỏi assets, thumbnail và danh sách theme vì hiển thị không đẹp.

## Vá tiếp fullscreen background reader

Đã ép `android.R.id.content` và `epub_reader_root` bỏ padding inset hệ thống, consume `WindowInsetsCompat` ở lớp content reader và bật thêm `FLAG_LAYOUT_NO_LIMITS` để ảnh nền có thể phủ vào vùng status bar/navigation bar.

Sau khi header reader bị trùng vào status bar, đã đổi listener inset của content sang chỉ xóa padding nhưng vẫn trả lại `WindowInsetsCompat` gốc, để `ReaderChromeOverlay` tiếp tục nhận được status/navigation bar inset và tự đẩy header/footer khỏi vùng hệ thống.

## Bổ sung bộ ảnh nền VIP

Đã thêm bộ theme ảnh nền từ `C:\Users\Admin\Downloads\background vip` vào reader Calibre Sync.

- Copy ảnh nền vào `app/src/main/assets/reader_backgrounds`.
- Copy thumbnail tương ứng vào `app/src/main/assets/reader_background_thumbnails`.
- Đặt tên theo quy ước `vip_*-bg.*` và `vip_*-bg-thumbnail.*` để `ReaderColorTheme.thumbnailImageAsset` tự nhận đúng thumbnail.
- Đăng ký 11 theme mới trong `ReaderColorTheme.kt`: city, hoa đẹp, hoa đẹp 2, hoa nước, núi non, nước biển, sứa biển, tổ quốc, tổ quốc 2, tuổi thơ, vô định.
- Bổ sung nhãn i18n trong `values/strings.xml` và `values-vi/strings.xml`.
