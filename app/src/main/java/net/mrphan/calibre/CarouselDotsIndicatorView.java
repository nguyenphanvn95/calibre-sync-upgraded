package net.mrphan.calibre;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.core.graphics.ColorUtils;

/**
 * Plain dot-strip page indicator shown below the Carousel (see item_book_carousel.xml /
 * CollectionFragment#applyCarouselChrome), tracking which book is currently centered. Deliberately
 * hand-rolled instead of pulling in a "DotsIndicator"-style library: this only ever needs to draw
 * a row of filled circles with one highlighted, so a couple of Paint objects + onDraw is simpler
 * and lighter than wiring up + version-pinning another Gradle dependency for it.
 * <p>
 * When there are more books than {@link #MAX_VISIBLE_DOTS}, only a sliding window of dots
 * centered on the current selection is drawn (mirroring how most cover-flow/carousel UIs keep
 * the indicator strip a fixed width instead of growing without bound for a 500-book library).
 */
public class CarouselDotsIndicatorView extends View {

    private static final int MAX_VISIBLE_DOTS = 9;

    private int count = 0;
    private int selected = 0;

    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dotRadiusPx;
    private final float selectedRadiusPx;
    private final float spacingPx;

    public CarouselDotsIndicatorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = context.getResources().getDisplayMetrics().density;
        dotRadiusPx = 3f * density;
        selectedRadiusPx = 4.5f * density;
        spacingPx = 14f * density;
        // Muted neutral for the un-selected dots (textColorSecondary, at reduced alpha) and the
        // app's existing accent color for the selected one (colorPrimary -- already reused all
        // over the app as an accent, see the comment on colorPrimary's usage in colors.xml),
        // instead of the old hardcoded white-on-white which only worked on a dark background.
        // This adapts automatically to light/dark theme like the reference screenshot's muted
        // gray strip with one colored highlight dot.
        int mutedColor = resolveThemeColor(context, android.R.attr.textColorSecondary, 0xFF9E9E9E);
        int accentColor = resolveThemeColor(context, com.google.android.material.R.attr.colorPrimary, 0xFF009688);
        dotPaint.setColor(ColorUtils.setAlphaComponent(mutedColor, 130));
        selectedPaint.setColor(accentColor);
    }

    /** Resolves a theme attribute (e.g. ?attr/colorPrimary) to an actual color int, falling back
     * to fallbackColor if the current theme doesn't define it. */
    private static int resolveThemeColor(Context context, int attr, int fallbackColor) {
        TypedValue typedValue = new TypedValue();
        boolean resolved = context.getTheme().resolveAttribute(attr, typedValue, true);
        if (!resolved || typedValue.type < TypedValue.TYPE_FIRST_COLOR_INT
                || typedValue.type > TypedValue.TYPE_LAST_COLOR_INT) {
            return fallbackColor;
        }
        return typedValue.data;
    }

    /** Total number of books in the current Carousel list. */
    public void setCount(int newCount) {
        if (count == newCount) return;
        count = newCount;
        requestLayout();
        invalidate();
    }

    /** Index (0-based) of the book currently centered in the carousel. */
    public void setSelectedPosition(int position) {
        if (selected == position) return;
        selected = position;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int visible = Math.max(1, Math.min(count, MAX_VISIBLE_DOTS));
        int desiredWidth = Math.round(visible * spacingPx) + getPaddingLeft() + getPaddingRight();
        int desiredHeight = Math.round(selectedRadiusPx * 2) + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(
                resolveSize(desiredWidth, widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (count <= 0) return;
        int visible = Math.min(count, MAX_VISIBLE_DOTS);
        // Slide the visible window so it's centered on the current selection, clamped so it
        // never runs past either end of the actual book list.
        int windowStart = Math.max(0, Math.min(selected - visible / 2, count - visible));

        float centerY = getHeight() / 2f;
        float totalWidth = visible * spacingPx;
        float startX = (getWidth() - totalWidth) / 2f + spacingPx / 2f;

        for (int i = 0; i < visible; i++) {
            int bookIndex = windowStart + i;
            float cx = startX + i * spacingPx;
            boolean isSelected = bookIndex == selected;
            canvas.drawCircle(cx, centerY, isSelected ? selectedRadiusPx : dotRadiusPx,
                    isSelected ? selectedPaint : dotPaint);
        }
    }
}
