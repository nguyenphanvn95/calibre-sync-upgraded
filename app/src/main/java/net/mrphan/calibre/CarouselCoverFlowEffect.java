package net.mrphan.calibre;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

/**
 * Turns a plain horizontal RecyclerView (CollectionFragment's lvBooklist in
 * ViewMode.CAROUSEL, paired with PagerSnapHelper -- see CollectionFragment#setupTouchHelper)
 * into a 3D "Cover Flow" carousel: the card nearest the RecyclerView's horizontal center is
 * drawn full-size and fully opaque with a strong drop shadow, and cards further from center
 * shrink, fade and tilt away in 3D (rotationY) the further out they are. This mirrors the
 * classic iPod/Calibre-Companion cover flow shown in the reference screenshot the user
 * supplied, and only needs plain View properties (no ViewPager2/PageTransformer) since
 * item_book_carousel.xml's negative side margins already let neighbouring cards overlap once
 * clipChildren/clipToPadding are turned off on the RecyclerView (see activity_collection.xml).
 * <p>
 * Attach as a RecyclerView.OnScrollListener only while ViewMode.CAROUSEL is active; call
 * {@link #applyTransform()} once more right after attaching (post()'d past the next layout
 * pass) since onScrolled() never fires on its own for a RecyclerView that hasn't scrolled yet.
 */
final class CarouselCoverFlowEffect extends RecyclerView.OnScrollListener {

    /** How small the furthest-out visible card gets, as a fraction of full size. */
    private static final float MIN_SCALE = 0.82f;
    /** How transparent the furthest-out visible card gets. */
    private static final float MIN_ALPHA = 0.55f;
    /** How far the side cards tilt away in 3D, in degrees. */
    private static final float MAX_ROTATION_Y = 22f;
    /** Camera distance (in dp, scaled by density below) -- higher pulls the vanishing point
     * further away so the rotationY tilt reads as a gentle curve instead of an exaggerated
     * fisheye warp. Android's default camera distance is far too close for a card this big. */
    private static final float CAMERA_DISTANCE_DP = 14000f;
    private static final float MIN_CARD_ELEVATION_DP = 1f;
    private static final float MAX_CARD_ELEVATION_DP = 14f;

    interface OnCenterItemChangedListener {
        /** Fired whenever the card closest to center changes, e.g. to refresh the title/author
         * panel and dot indicator below the carousel. Not fired on every pixel of scroll. */
        void onCenterItemChanged(int adapterPosition);
    }

    private final RecyclerView recyclerView;
    private final OnCenterItemChangedListener listener;
    private int lastCenterPosition = RecyclerView.NO_POSITION;

    CarouselCoverFlowEffect(@NonNull RecyclerView recyclerView, OnCenterItemChangedListener listener) {
        this.recyclerView = recyclerView;
        this.listener = listener;
    }

    @Override
    public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
        applyTransform();
    }

    @Override
    public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
        if (newState == RecyclerView.SCROLL_STATE_IDLE) applyTransform();
    }

    /** Recomputes and re-applies the transform for every currently laid-out child. Safe to call
     * any time the RecyclerView has a non-zero width (i.e. after at least one layout pass). */
    void applyTransform() {
        int width = recyclerView.getWidth();
        if (width == 0) return;
        float density = recyclerView.getResources().getDisplayMetrics().density;
        float centerX = width / 2f;

        int nearestPosition = RecyclerView.NO_POSITION;
        float nearestDistancePx = Float.MAX_VALUE;

        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            View child = recyclerView.getChildAt(i);
            float childCenterX = (child.getLeft() + child.getRight()) / 2f;
            float distancePx = childCenterX - centerX;
            // Normalized to roughly [-1, 1] across half the RecyclerView's width, then clamped
            // so cards further out (e.g. the 2nd/3rd offscreen neighbour) don't overshoot past
            // the fully-shrunk/faded/tilted end state.
            float t = Math.max(-1f, Math.min(1f, distancePx / centerX));
            float absT = Math.abs(t);

            float scale = 1f - (1f - MIN_SCALE) * absT;
            float alpha = 1f - (1f - MIN_ALPHA) * absT;
            float rotationY = -t * MAX_ROTATION_Y;

            child.setScaleX(scale);
            child.setScaleY(scale);
            child.setAlpha(alpha);
            child.setRotationY(rotationY);
            child.setCameraDistance(CAMERA_DISTANCE_DP * density);
            // Centered card should stack visually above its tilted neighbours.
            child.setTranslationZ((1f - absT) * density);

            View cardView = child.findViewById(R.id.bi_cover_card);
            if (cardView instanceof MaterialCardView) {
                float elevationDp = MAX_CARD_ELEVATION_DP
                        - (MAX_CARD_ELEVATION_DP - MIN_CARD_ELEVATION_DP) * absT;
                ((MaterialCardView) cardView).setCardElevation(elevationDp * density);
            }

            float absDistancePx = Math.abs(distancePx);
            if (absDistancePx < nearestDistancePx) {
                nearestDistancePx = absDistancePx;
                nearestPosition = recyclerView.getChildAdapterPosition(child);
            }
        }

        if (nearestPosition != RecyclerView.NO_POSITION && nearestPosition != lastCenterPosition) {
            lastCenterPosition = nearestPosition;
            if (listener != null) listener.onCenterItemChanged(nearestPosition);
        }
    }

    int getLastCenterPosition() {
        return lastCenterPosition;
    }
}
