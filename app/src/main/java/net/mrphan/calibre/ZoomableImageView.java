package net.mrphan.calibre;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

/**
 * A plain ImageView with pinch-to-zoom (ScaleGestureDetector) and one/two-finger pan, plus
 * double-tap to reset. Written from scratch instead of adding a third-party zoom-ImageView
 * dependency, per the reader spec's preference to avoid unnecessary/unreviewed licenses for a
 * small, self-contained piece of functionality. Used by PdfReaderActivity and CbzReaderActivity.
 */
public class ZoomableImageView extends androidx.appcompat.widget.AppCompatImageView {

    private static final float MIN_SCALE = 1f;
    private static final float MAX_SCALE = 5f;

    private final Matrix matrix = new Matrix();
    private float scale = 1f;
    private float lastFocusX, lastFocusY;
    private ScaleGestureDetector scaleGestureDetector;
    private GestureDetector gestureDetector;
    private int viewWidth, viewHeight;
    private float drawableWidth, drawableHeight;

    public ZoomableImageView(Context context) {
        super(context);
        init(context);
    }

    public ZoomableImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setScaleType(ScaleType.MATRIX);
        scaleGestureDetector = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new GestureListener());
    }

    /** Call after setImageBitmap()/setImageDrawable() to reset zoom/pan for the new image. */
    public void resetZoom() {
        scale = 1f;
        centerImage();
        invalidate();
    }

    /** Zoom quanh tam view hien tai (khong phai theo diem cham) -- dung cho nut bam +/-,
     * khac voi ScaleListener.onScale() von zoom quanh tieu diem 2 ngon tay. */
    public void zoomBy(float factor) {
        float newScale = Math.max(MIN_SCALE, Math.min(scale * factor, MAX_SCALE));
        float appliedFactor = newScale / scale;
        if (appliedFactor == 1f) return;
        scale = newScale;
        matrix.postScale(appliedFactor, appliedFactor, viewWidth / 2f, viewHeight / 2f);
        setImageMatrix(matrix);
    }

    public void zoomIn() {
        zoomBy(1.5f);
    }

    public void zoomOut() {
        zoomBy(1f / 1.5f);
    }

    public float getCurrentScale() {
        return scale;
    }

    public static float getMinScale() {
        return MIN_SCALE;
    }

    public static float getMaxScale() {
        return MAX_SCALE;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        viewWidth = w;
        viewHeight = h;
        centerImage();
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        if (drawable != null) {
            drawableWidth = drawable.getIntrinsicWidth();
            drawableHeight = drawable.getIntrinsicHeight();
        }
        centerImage();
    }

    private void centerImage() {
        if (drawableWidth == 0 || drawableHeight == 0 || viewWidth == 0 || viewHeight == 0) return;
        matrix.reset();
        float fitScale = Math.min(viewWidth / drawableWidth, viewHeight / drawableHeight);
        float dx = (viewWidth - drawableWidth * fitScale) / 2f;
        float dy = (viewHeight - drawableHeight * fitScale) / 2f;
        matrix.postScale(fitScale, fitScale);
        matrix.postTranslate(dx, dy);
        scale = 1f;
        setImageMatrix(matrix);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleGestureDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                lastFocusX = event.getX();
                lastFocusY = event.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                if (scale > MIN_SCALE) {
                    float dx = event.getX() - lastFocusX;
                    float dy = event.getY() - lastFocusY;
                    matrix.postTranslate(dx, dy);
                    setImageMatrix(matrix);
                    lastFocusX = event.getX();
                    lastFocusY = event.getY();
                }
                break;
        }
        return true;
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float newScale = scale * detector.getScaleFactor();
            newScale = Math.max(MIN_SCALE, Math.min(newScale, MAX_SCALE));
            float factor = newScale / scale;
            scale = newScale;
            matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
            setImageMatrix(matrix);
            return true;
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (scale > MIN_SCALE) {
                centerImage();
            } else {
                float targetScale = 2.5f;
                matrix.postScale(targetScale, targetScale, e.getX(), e.getY());
                scale = targetScale;
                setImageMatrix(matrix);
            }
            return true;
        }
    }
}
