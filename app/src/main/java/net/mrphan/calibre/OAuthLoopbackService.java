package net.mrphan.calibre;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.IOException;

/**
 * Foreground Service ôm lấy OAuthLoopbackServer thay vì để CloudOAuthActivity tự
 * {@code new OAuthLoopbackServer(...)} trực tiếp trong tiến trình của chính Activity đó.
 * <p>
 * TẠI SAO CẦN -- lỗi "127.0.0.1 refused to connect" (ERR_CONNECTION_REFUSED) khi Google redirect
 * về sau khi đăng nhập: trước đây OAuthLoopbackServer được khởi tạo ngay trong onCreate() của
 * CloudOAuthActivity, và NGAY SAU ĐÓ Activity mở Chrome Custom Tabs -- tức Activity (và mọi
 * background thread của nó, bao gồm NanoHTTPD) lập tức bị đẩy khỏi foreground. Trên nhiều máy
 * (ROM hãng thứ ba với battery optimization mạnh, hoặc kể cả AOSP thường khi máy đang tiết kiệm
 * pin), hệ điều hành hạ priority hoặc "đóng băng"/kill tiến trình ứng dụng ngay khi không còn
 * Activity nào ở foreground -- NanoHTTPD chạy trên một thread nền trong CÙNG tiến trình đó cũng bị
 * treo/kill theo. Vài giây sau, khi người dùng đăng nhập xong trên trang Google thật và Chrome cố
 * điều hướng về http://127.0.0.1:&lt;port&gt;/oauth2redirect thì không còn ai lắng nghe ở cổng đó
 * nữa.
 * <p>
 * Chạy OAuthLoopbackServer bên trong một Service ở foreground (có notification, giống
 * CalibreService) giữ cho tiến trình ứng dụng có priority ngang một foreground app trong suốt lúc
 * Custom Tabs đang mở, nên server sống sót cho tới khi redirect thật sự tới nơi.
 * <p>
 * Vòng đời: CloudOAuthActivity gọi {@code ContextCompat.startForegroundService()} (để
 * Service không tự onDestroy() ngay khi Activity unbind, xem shutdown() bên dưới) rồi
 * {@code bindService()} để lấy IBinder. Server được khởi động ngay trong onCreate() -- KHÔNG đợi
 * client bind xong -- để không phụ thuộc vào tốc độ của bindService() và tuân thủ đúng yêu cầu
 * gọi startForeground() trong vòng vài giây sau startForegroundService() của Android 8+.
 */
public class OAuthLoopbackService extends Service {

    /** Đường dẫn redirect (vd "/oauth2redirect") mà OAuthLoopbackServer sẽ khớp -- truyền tường
     * minh từ CloudOAuthActivity qua Intent extra thay vì đọc một hằng số Google cố định, để dùng
     * chung được cho cả Google Drive lẫn OneDrive. */
    static final String EXTRA_REDIRECT_PATH = "redirect_path";
    /** Tên hiển thị của provider (vd "Google Drive"/"OneDrive") dùng cho notification -- xem
     * goForeground(). */
    static final String EXTRA_PROVIDER_DISPLAY_NAME = "provider_display_name";

    private static final String NOTIFICATION_CHANNEL_ID = "cloud_oauth_loopback";
    private static final int FOREGROUND_NOTIFICATION_ID = 2;
    /** Độ trễ trước khi thực sự đóng NanoHTTPD sau khi shutdown() được gọi -- xem Javadoc của
     * shutdown() bên dưới cho lý do (tránh cắt ngang response HTML đang được ghi ra socket cho
     * Chrome ngay sau khi request redirect vừa được nhận). */
    private static final long SHUTDOWN_DELAY_MS = 1500;

    private final IBinder binder = new LocalBinder();

    private OAuthLoopbackServer server;
    private IOException startupError;
    /** volatile vì được set từ main thread (CloudOAuthActivity) nhưng đọc từ thread nền của
     * NanoHTTPD (OAuthLoopbackServer#serve() chạy trên thread riêng của nó). */
    private volatile OAuthLoopbackServer.RedirectListener listener;
    /** true sau khi server đã được khởi tạo lần đầu trong onStartCommand() -- các lệnh start()
     * lặp lại (Android có thể gọi onStartCommand() nhiều lần cho cùng một Service instance) không
     * được tạo server thứ hai đè lên cổng đang lắng nghe. */
    private boolean serverInitialized = false;

    class LocalBinder extends Binder {
        OAuthLoopbackService getService() {
            return OAuthLoopbackService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Chỉ hiện notification tạm ở đây -- goForeground() thật (với tên provider) chạy lại
        // trong onStartCommand() ngay khi có Intent, vì onCreate() không nhận được Intent.
        goForeground(null);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String providerDisplayName = intent != null
                ? intent.getStringExtra(EXTRA_PROVIDER_DISPLAY_NAME) : null;
        goForeground(providerDisplayName);

        // PHẢI khởi tạo OAuthLoopbackServer ở đây (không phải onCreate()) vì redirectPath chỉ có
        // được từ Intent của onStartCommand() -- Android luôn gọi onStartCommand() ngay sau
        // onCreate() trong cùng một lượt start() nên vẫn khởi động đủ sớm để không bị hệ thống
        // kill trước khi CloudOAuthActivity kịp bindService().
        if (!serverInitialized) {
            serverInitialized = true;
            String redirectPath = intent != null ? intent.getStringExtra(EXTRA_REDIRECT_PATH) : null;
            if (redirectPath == null) {
                redirectPath = OAuthProviderSpec.REDIRECT_PATH;
            }
            try {
                server = new OAuthLoopbackServer(redirectPath, queryParams -> {
                    OAuthLoopbackServer.RedirectListener currentListener = listener;
                    if (currentListener != null) {
                        currentListener.onRedirectReceived(queryParams);
                    }
                });
            } catch (IOException e) {
                Logger.e(e);
                startupError = e;
            }
        }

        // START_NOT_STICKY: nếu hệ thống vẫn kill Service này (hiếm, vì đang ở foreground), không
        // có ý nghĩa gì để tự khởi động lại mà không có redirect listener/port cũ --
        // CloudOAuthActivity sẽ phát hiện việc này qua onServiceDisconnected() và báo lỗi.
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    /** Cổng đang lắng nghe, hoặc null nếu NanoHTTPD start lỗi trong onCreate() -- xem
     * getStartupError() để lấy nguyên nhân. */
    @Nullable
    Integer getListeningPort() {
        return server != null ? server.getListeningPort() : null;
    }

    @Nullable
    IOException getStartupError() {
        return startupError;
    }

    void setRedirectListener(@Nullable OAuthLoopbackServer.RedirectListener listener) {
        this.listener = listener;
    }

    /** Gọi từ CloudOAuthActivity ngay khi luồng OAuth kết thúc (thành công/lỗi/huỷ) --
     * KHÔNG đóng NanoHTTPD ngay lập tức tại đây.
     * <p>
     * TẠI SAO TRÌ HOÃN -- lỗi "127.0.0.1 refused to connect" ngay TRÊN CHÍNH request redirect vừa
     * thành công (dù app vẫn đổi token OK): OAuthLoopbackServer#serve() gọi
     * {@code listener.onRedirectReceived(...)} (dẫn tới hàm này được gọi, qua
     * CloudOAuthActivity#handleRedirect) NGAY TRONG LÚC XỬ LÝ request đó, TRƯỚC KHI
     * serve() kịp return Response (trang HTML "Đăng nhập thành công") để NanoHTTPD ghi ra socket
     * cho Chrome. Nếu server.stop() chạy ngay lập tức ở đây (trên main thread, gần như đồng thời
     * với lúc NanoHTTPD đang ghi response đó trên thread nền của chính nó), stop() đóng luôn
     * server socket + ngắt các kết nối đang dở -- có thể cắt ngang NGAY response đang được ghi
     * cho request redirect, khiến Chrome thấy kết nối bị đóng giữa chừng (refused/reset) dù
     * app đã lấy được "code" và đổi token thành công từ trước đó rồi. Đợi SHUTDOWN_DELAY_MS đảm
     * bảo NanoHTTPD ghi xong response (chỉ mất vài mili-giây trong thực tế) trước khi đóng cổng.
     * <p>
     * Activity phải gọi unbindService() TRƯỚC khi gọi hàm này (xem
     * CloudOAuthActivity#shutdownLoopbackService) -- Service vẫn tiếp tục sống bình thường
     * sau khi Activity unbind/finish() vì đã được start riêng bằng startForegroundService(), nên
     * việc trì hoãn ở đây không phụ thuộc vào vòng đời của Activity.
     */
    void shutdown() {
        listener = null;
        new Handler(Looper.getMainLooper()).postDelayed(this::shutdownNow, SHUTDOWN_DELAY_MS);
    }

    private void shutdownNow() {
        if (server != null) {
            server.stop();
            server = null;
        }
        //noinspection deprecation -- overload nhận boolean tương thích tới minSdk 24; overload
        // nhận STOP_FOREGROUND_REMOVE (int) chỉ có từ API 33.
        stopForeground(true);
        stopSelf();
    }

    /** @param providerDisplayNameOrNull tên provider (vd "Google Drive"/"OneDrive") để chèn vào
     * cloud_oauth_notification_title, hoặc null nếu chưa biết (vd lúc onCreate() chạy trước khi
     * Intent của onStartCommand() tới nơi -- notification sẽ được cập nhật lại ngay sau đó). */
    private void goForeground(@Nullable String providerDisplayNameOrNull) {
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(R.string.cloud_oauth_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
        }
        String title = providerDisplayNameOrNull != null
                ? getString(R.string.cloud_oauth_notification_title, providerDisplayNameOrNull)
                : getString(R.string.cloud_oauth_notification_channel_name);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(getString(R.string.cloud_oauth_notification_text))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        Notification notification = builder.build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Lưới an toàn nếu Activity chưa kịp gọi shutdown() (vd. tiến trình bị hệ thống kill) --
        // đảm bảo cổng loopback luôn được giải phóng.
        if (server != null) {
            server.stop();
            server = null;
        }
    }
}
