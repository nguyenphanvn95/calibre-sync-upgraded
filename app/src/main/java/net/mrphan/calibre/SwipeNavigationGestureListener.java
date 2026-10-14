package net.mrphan.calibre;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;

/**
 * Nhận diện vuốt ngang toàn màn hình để chuyển sang preview cuốn sách kế tiếp/trước đó -- dùng
 * chung bởi DeviceBookDetailActivity (Thư viện thiết bị) và BookDetailActivity (Thư viện Calibre
 * OPDS + GDrive), tách ra từ bản gốc trong DeviceBookDetailActivity để cả 2 màn hình luôn cùng 1
 * ngưỡng nhận diện thay vì lặp lại logic.
 * <p>
 * Chỉ lắng nghe {@link #onFling}, không consume touch event nào -- caller vẫn phải tự gọi
 * {@code gestureDetector.onTouchEvent(ev)} bên trong {@code dispatchTouchEvent()} rồi luôn gọi
 * {@code super.dispatchTouchEvent(ev)} sau đó như bình thường, để ScrollView/nút bấm bên trong
 * không bị ảnh hưởng gì (xem cách EpubReaderActivity/ReaderChromeOverlay#dispatchContentTouch
 * quan sát touch song song mà không chặn WebView, cùng 1 kiểu).
 */
class SwipeNavigationGestureListener extends GestureDetector.SimpleOnGestureListener {

    interface Callback {
        /** Vuốt phải -> trái (ngón tay đi từ phải sang trái): mở cuốn KẾ TIẾP. */
        void onSwipeRightToLeft();
        /** Vuốt trái -> phải: mở cuốn TRƯỚC ĐÓ. */
        void onSwipeLeftToRight();
    }

    private static final int SWIPE_MIN_DISTANCE_DP = 80;
    private static final int SWIPE_THRESHOLD_VELOCITY_DP = 150;

    private final Context context;
    private final Callback callback;

    SwipeNavigationGestureListener(Context context, Callback callback) {
        this.context = context;
        this.callback = callback;
    }

    /** Chỉ coi là vuốt ngang khi quãng đường ngang lớn hơn hẳn quãng đường dọc (loại các cử chỉ
     * cuộn dọc lệch nhẹ) và đủ nhanh/đủ xa để không lẫn với thao tác chạm thường. */
    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        if (e1 == null) return false;
        float density = context.getResources().getDisplayMetrics().density;
        float dx = e2.getX() - e1.getX();
        float dy = e2.getY() - e1.getY();
        if (Math.abs(dx) <= Math.abs(dy) * 2) return false; // qua doc, khong phai vuot ngang
        if (Math.abs(dx) < SWIPE_MIN_DISTANCE_DP * density) return false;
        if (Math.abs(velocityX) < SWIPE_THRESHOLD_VELOCITY_DP * density) return false;
        if (dx < 0) {
            callback.onSwipeRightToLeft();
        } else {
            callback.onSwipeLeftToRight();
        }
        return true;
    }
}
