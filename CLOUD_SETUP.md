# Thiết lập Cloud Libraries (Google Drive / OneDrive / Box / Dropbox / WebDAV / FTP / Calibre Content Server)

Bản nâng cấp này thêm mục **Cloud Libraries** vào tab **Connection**. Phần lớn các card
hoạt động ngay không cần thiết lập gì thêm; riêng **Google Drive**, **OneDrive**, **Box**, và
**Dropbox** cần bạn đăng ký một OAuth App (xem mục 1, 4, 4b, 4c tương ứng) trước khi có thể đăng
nhập — nhưng khác với các thư viện dùng Web Client ID, bạn **không cần dán bất kỳ chuỗi Client ID nào vào
code hay `local.properties`**: Google Sign-In tự động khớp app với Client ID đã đăng ký dựa vào
package name + chữ ký (SHA-1) của app, nên chỉ cần làm đúng các bước dưới đây trên Cloud
Console là đủ.

## 1. Google Drive — bắt buộc để đăng nhập

Từ bản này, đăng nhập Google Drive dùng **OAuth 2.0 Authorization Code Flow** chuẩn (giống
plugin "Calibre Google Drive Sync"): Client ID + Client Secret hardcode trong
`GoogleDriveOAuthConfig.java`, hiển thị trang đăng nhập Google trong một WebView nội bộ (không
mở Chrome/app khác). Không còn phụ thuộc SHA-1 hay package signature như trước.

1. Vào https://console.cloud.google.com/projectcreate, tạo project mới (hoặc dùng project có sẵn).
2. Vào **APIs & Services > Library**, tìm **Google Drive API**, bấm **Enable**.
3. Vào **APIs & Services > Credentials > Create Credentials > OAuth client ID**, chọn loại
   **Web application** (không phải Android).
4. Ở mục **Authorized redirect URIs**, thêm đúng:
   ```
   http://localhost/oauth2redirect
   ```
   (URL này không bao giờ thực sự được tải — WebView chặn lại ngay khi phát hiện, xem
   `GoogleDriveOAuthActivity`.)
5. Copy **Client ID** và **Client Secret** vừa tạo, dán vào hai hằng số `CLIENT_ID` /
   `CLIENT_SECRET` trong `app/src/main/java/net/mrphan/calibre/GoogleDriveOAuthConfig.java`.
   **Không commit Client Secret thật lên git công khai.**
6. Vào **APIs & Services > OAuth consent screen**:
   - Điền tên app.
   - Thêm scope `.../auth/drive`.
   - Nếu app đang ở chế độ **Testing**, **bắt buộc** thêm chính email Google của bạn vào mục
     **Test users** — nếu không sẽ gặp lỗi `access_blocked` khi đăng nhập.
7. Không cần khai báo package name hay SHA-1 ở đâu cả.

Xong bước 6 là có thể build và đăng nhập Google Drive ngay. Nếu Google chặn trang đăng nhập
trong WebView (lỗi "disallowed_useragent" hoặc trang cảnh báo "This browser or app may not be
secure"), app sẽ báo lỗi rõ ràng thay vì crash — đây là hạn chế phía Google, thử đăng nhập lại
hoặc kiểm tra kết nối mạng. Muốn đăng xuất, nhấn giữ (long-press) vào card "Google Drive" khi
đã đăng nhập để mở hộp thoại "Disconnect".

## 2. Calibre Content Server (WebView Browser) — không cần thiết lập gì thêm

Card thứ 3 ("Calibre Content Server") mở một trình duyệt web nhúng (WebView) ngay trong
app, trỏ tới trang gốc của Content Server. Nó **tái sử dụng chính xác** host/port/scheme/
username/password mà bạn đã cấu hình khi kết nối Calibre qua Wireless Devices / Content
Server ở màn hình kết nối thủ công như bình thường — không cần đăng nhập hay cấu hình gì
riêng cho tính năng này. Nếu bạn chưa từng kết nối Content Server lần nào, bấm vào card
này sẽ mở luôn màn hình kết nối thủ công để bạn kết nối trước.

## 3. WebDAV / FTP / NAS — không cần Google Cloud Console

Nhập trực tiếp host/user/pass ngay trong app qua card "WebDAV / FTP / NAS" — không cần
thiết lập gì bên ngoài.

## 3b. Calibre Libraries on GDrive — cần 1 Drive API key hạn chế (không phải OAuth)

Card **"Calibre Libraries on GDrive"** (ngay dưới "Google Drive") duyệt các thư viện Calibre
đã được xuất ra file `metadata_public.json` và chia sẻ công khai ("Anyone with the link") —
**không** cần đăng nhập Google. App cần một Drive API key hạn chế (read-only) để gọi Drive v3
REST API công khai:

1. Trên cùng project ở bước 1, vào **APIs & Services > Credentials > Create Credentials > API
   key**.
2. Bấm **Restrict key**: giới hạn **API restrictions** chỉ cho **Google Drive API**, và
   **Application restrictions** theo package name `net.mrphan.calibre` + SHA-1 chữ ký app
   (giống bước 4 ở mục 1).
3. Thêm dòng sau vào `gradle.properties` ở thư mục gốc project (KHÔNG commit giá trị thật vào
   git):
   ```
   GDRIVE_PUBLIC_API_KEY=your_restricted_key_here
   ```
4. Trong dialog thêm thư viện, dán ID hoặc link chia sẻ của file `metadata_public.json` (đã
   share "Anyone with the link") — app tự kiểm tra và báo lỗi cụ thể nếu file không tồn tại
   hoặc chưa công khai.

## 4. OneDrive — cần đăng ký App trên Microsoft Entra (Azure AD)

Đăng nhập OneDrive dùng cùng cơ chế OAuth 2.0 Authorization Code + PKCE với Google Drive (Chrome
Custom Tabs + loopback server nội bộ), nhưng là public client nên **không cần client secret**.
Xem javadoc `OAuthProviderSpec.forOneDrive()` để biết các bước đăng ký App registration trên
`portal.azure.com` (Supported account types, redirect URI `http://localhost`, API permission
`Files.ReadWrite` + `offline_access` + `User.Read`), rồi dán Application (client) ID vào
`OAuthProviderSpec.forOneDrive()`.

## 4b. Box — cần đăng ký OAuth App trên Box Developer Console

Đăng nhập Box cũng dùng OAuth 2.0 Authorization Code + PKCE dùng chung hạ tầng với Google
Drive/OneDrive, nhưng **khác OneDrive**: Box bắt buộc phải có `client_secret` ngay cả với luồng
Authorization Code (không có chế độ public-client-only).

1. Vào https://cloud.app.box.com/developers/console, bấm **Create New App**.
2. Chọn loại **Custom App**, phương thức xác thực **User Authentication (OAuth 2.0)**.
3. Tab **Configuration** > **OAuth 2.0 Redirect URI**: thêm đúng
   ```
   http://localhost/oauth2redirect
   ```
   (URI này không bao giờ thực sự được tải bởi trình duyệt bên ngoài — Chrome Custom Tabs +
   loopback server nội bộ tự bắt lại, xem `CloudOAuthActivity`/`OAuthLoopbackService`, dùng
   chung `OAuthProviderSpec.REDIRECT_PATH` với Google Drive/OneDrive.)
4. Tab **Configuration** > **Application Scopes**: tick **"Read and write all files and folders
   stored in Box"** (`root_readwrite`). Không cần bật "Manage users" hay webhook, và không cần
   submit app để Box review vì app chỉ dùng cho tài khoản cá nhân của người dùng (giống mô hình
   Google Drive/OneDrive ở trên).
5. Copy **Client ID** và **Client Secret**, dán vào hai hằng số trong
   `OAuthProviderSpec.forBox()` (`app/src/main/java/net/mrphan/calibre/OAuthProviderSpec.java`).
   **Không commit Client Secret thật lên git công khai.**

Xong bước 5 là có thể build và đăng nhập Box ngay. Muốn đăng xuất, nhấn giữ (long-press) vào card
"Box" khi đã đăng nhập để mở hộp thoại "Disconnect".

Lưu ý kỹ thuật (đã xử lý sẵn trong `BoxCloudProvider`, không cần biết để dùng nhưng hữu ích nếu
debug): refresh token của Box xoay vòng sau mỗi lần refresh; thư mục/file trùng tên khi
tạo/upload trả về **409 Conflict** thay vì tự đổi tên như Google Drive/OneDrive; tải file đi qua
một bước redirect 302 sang `dl.boxcloud.com` không kèm header `Authorization`; và "simple upload"
chỉ được khuyến nghị cho file dưới 50MB (file lớn hơn cần "Chunked Upload", chưa cài trong bản
này).

## 4c. Dropbox — cần đăng ký OAuth App trên Dropbox App Console

Đăng nhập Dropbox cũng dùng OAuth 2.0 Authorization Code + PKCE dùng chung hạ tầng với Google
Drive/OneDrive/Box, nhưng **giống OneDrive hơn Box**: Dropbox hỗ trợ PKCE cho public client nên
**không cần** `client_secret`.

1. Vào https://www.dropbox.com/developers/apps, bấm **Create app**.
2. Chọn **Scoped access**, loại truy cập **Full Dropbox** (hoặc **App folder** nếu muốn giới hạn
   phạm vi vào một thư mục riêng cho app).
3. Tab **Settings** > mục **OAuth 2** > **Redirect URIs**: thêm đúng
   ```
   http://localhost/oauth2redirect
   ```
   (URI này không bao giờ thực sự được tải bởi trình duyệt bên ngoài — Chrome Custom Tabs +
   loopback server nội bộ tự bắt lại, xem `CloudOAuthActivity`/`OAuthLoopbackService`, dùng
   chung `OAuthProviderSpec.REDIRECT_PATH` với Google Drive/OneDrive/Box.)
4. Tab **Permissions**: tick `files.metadata.write`, `files.metadata.read`,
   `files.content.write`, `files.content.read`, rồi bấm **Submit**.
5. Tab **Settings**, copy **App key** (không cần **App secret**), dán vào hằng số
   `REPLACE_WITH_YOUR_DROPBOX_APP_KEY` trong `OAuthProviderSpec.forDropbox()`
   (`app/src/main/java/net/mrphan/calibre/OAuthProviderSpec.java`).

Xong bước 5 là có thể build và đăng nhập Dropbox ngay. Muốn đăng xuất, nhấn giữ (long-press) vào
card "Dropbox" khi đã đăng nhập để mở hộp thoại "Disconnect".

Lưu ý kỹ thuật (đã xử lý sẵn trong `DropboxCloudProvider`, không cần biết để dùng nhưng hữu ích
nếu debug): Dropbox dùng **đường dẫn (path)** thay vì id server-cấp làm định danh chính cho mọi
thao tác, nên refId của mỗi CloudFile ở đây chính là `path_lower`; thư mục gốc là chuỗi rỗng `""`
(không phải `"/"`); tạo/upload trùng tên trả về **409 Conflict** (không tự đổi tên như Google
Drive/OneDrive) — code tự gọi `get_metadata` để lấy lại `path_lower` hiện có và coi như đã có sẵn,
cùng tinh thần idempotent với Box; và "simple upload" chỉ được khuyến nghị cho file dưới 150MB
(file lớn hơn cần "upload session", chưa cài trong bản này).

## 5. Các card còn lại (pCloud, Nextcloud, ownCloud, Local storage)

Đây là các card **placeholder** cho bản cập nhật này — bấm vào chỉ hiện thông báo "Tính
năng này sẽ được hỗ trợ trong một bản cập nhật trong tương lai", không cần (và không thể)
thiết lập gì cho chúng ở phiên bản này. **Dropbox không còn nằm trong nhóm này** — xem mục 4c ở
trên, đây giờ là một card OAuth hoạt động đầy đủ giống Google Drive/OneDrive/Box.

## 6. Đồng bộ Device Library lên Google Drive / OneDrive / Box / Dropbox

Sau khi đăng nhập Google Drive (mục 1), OneDrive (mục 4), Box (mục 4b), hoặc Dropbox (mục 4c),
tab **Device Library** có thêm 2 chiều đồng bộ, không cần thiết lập gì thêm — chỉ cần đã đăng
nhập. Cấu trúc thư mục và cách hoạt động mô tả dưới đây lấy Google Drive làm ví dụ nhưng áp dụng
tương tự cho cả 4 provider (`CloudRemoteType.supportsDeviceLibrarySync()`).

**Cấu trúc thư mục tạo ra trên Drive**: dưới `My Drive`, app tạo (hoặc dùng lại nếu đã có sẵn)
1 thư mục duy nhất trùng tên với thư mục sách trên máy (ví dụ `Books`), bên trong:

- `calibre_sync_manifest.json` — danh sách sách đã đồng bộ (uuid, đường dẫn, id file trên Drive).
- `calibre_sync_progress.json` — tiến trình đọc, bookmark/highlight, thời gian đọc, mô tả sách.
- Cây thư mục con + file sách/cover **mirror chính xác** cấu trúc `lpath` trên máy (giữ nguyên
  dấu tiếng Việt/Unicode), ví dụ `Tac gia A/Truyen X/tap-1.epub`.

**Cách hoạt động**:

- **Đồng bộ lên cloud**: vuốt trái 1 cuốn (hoặc chọn nhiều cuốn) trong Device Library, bấm nút
  Sync (icon mây xanh lá). App chỉ tải lên sách/cover/tiến trình *mới hơn hoặc chưa có* trên
  Drive, chạy nền (không chặn đọc sách khác).
- **Đồng bộ từ cloud**: menu 3 chấm > "Đồng bộ từ cloud". App tải về sách còn thiếu trên máy
  (theo `calibre_sync_manifest.json`) và merge tiến trình đọc/bookmark theo nguyên tắc "mới nhất
  thắng" (so timestamp).
- Cả 2 chiều đều chạy qua `WorkManager`, có thông báo tiến trình, tự động thử lại nếu mất mạng
  giữa chừng.

**Lưu ý quan trọng**:

- App **không tự xoá file trên Drive** khi xoá sách trên máy — xoá 2 chiều nằm ngoài phạm vi
  tính năng này, để tránh mất dữ liệu ngoài ý muốn nếu lỡ xoá nhầm trên máy.
- Nếu trên `My Drive` đã có sẵn 1 thư mục **trùng tên** với thư mục sách trên máy nhưng KHÔNG
  phải do app này tạo ra (ví dụ thư mục "Books" chứa ảnh gia đình), app sẽ dùng luôn thư mục đó
  làm đích đồng bộ — đổi tên thư mục sách trên máy trước khi bật tính năng này nếu muốn tránh
  trường hợp này.

