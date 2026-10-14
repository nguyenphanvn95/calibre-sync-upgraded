package net.mrphan.calibre;

import java.io.IOException;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * HTTP server tối giản chạy trên loopback (127.0.0.1) để nhận redirect từ Chrome Custom Tabs sau
 * khi người dùng đăng nhập/cấp quyền xong trên trang Google hoặc Microsoft thật (KHÔNG còn dùng
 * WebView để "đọc trộm" URL redirect như trước -- xem CloudOAuthActivity).
 * <p>
 * Đây chính là "Loopback IP address flow" theo RFC 8252: redirect_uri dạng
 * http://127.0.0.1:&lt;port&gt;/oauth2redirect với port được cấp phát ngẫu nhiên lúc runtime (bind
 * cổng 0 -> hệ điều hành tự chọn một cổng TCP còn trống); cả Google (client loại "Desktop app")
 * lẫn Microsoft (platform "Mobile and desktop applications") đều bỏ qua phần cổng khi so khớp
 * redirect URI cho loại client này -- xem OAuthProviderSpec. redirectPath được truyền tường minh
 * từ ngoài vào (thay vì đọc thẳng một hằng số Google riêng) để server này dùng chung được cho cả
 * 2 provider mà không phụ thuộc lẫn nhau.
 * <p>
 * Đã có sẵn dependency org.nanohttpd:nanohttpd trong project (dùng cho GDriveOpdsHttpServer),
 * nên tái sử dụng luôn thay vì thêm một HTTP server implementation khác.
 */
final class OAuthLoopbackServer extends NanoHTTPD {

    /** Gọi trên một thread nền của NanoHTTPD (KHÔNG phải main thread) ngay khi bắt được request
     * khớp redirectPath -- caller phải tự post về main thread trước khi đụng tới UI/Activity, xem
     * CloudOAuthActivity#onRedirectReceivedOnServerThread. */
    interface RedirectListener {
        void onRedirectReceived(Map<String, String> queryParams);
    }

    private final String redirectPath;
    private final RedirectListener listener;

    /** port=0 để hệ điều hành tự cấp phát một cổng TCP còn trống trên loopback; đọc cổng thật sự
     * được cấp qua getListeningPort() sau khi start() trả về. */
    OAuthLoopbackServer(String redirectPath, RedirectListener listener) throws IOException {
        super("127.0.0.1", 0);
        this.redirectPath = redirectPath;
        this.listener = listener;
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
    }

    @Override
    public Response serve(IHTTPSession session) {
        if (redirectPath.equals(session.getUri())) {
            listener.onRedirectReceived(session.getParms());
            String html = "<html><body style=\"font-family:sans-serif;text-align:center;"
                    + "margin-top:64px;color:#333\">"
                    + "<h3>Dang nhap thanh cong</h3>"
                    + "<p>Ban co the dong tab nay va quay lai ung dung Calibre Sync.</p>"
                    + "</body></html>";
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html);
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
    }
}
