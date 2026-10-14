package net.mrphan.calibre;

import android.graphics.Canvas;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Swipe-to-reveal for the Device Library tab's list rows (item_book_swipe.xml /
 * item_book_simple_swipe.xml):
 * - Dragging a row LEFT slides its foreground content aside to expose the Sync/Share/Delete
 *   buttons sitting underneath on the right (swipe_actions_background), like Gmail's swipe
 *   actions.
 * - Dragging a row RIGHT slides its foreground content the other way to expose the
 *   Listen/Read buttons sitting underneath on the left (swipe_actions_background_start).
 * This is NOT swipe-to-dismiss - the row never leaves the list on its own; getSwipeThreshold()
 * returning > 1.0 stops ItemTouchHelper from ever calling onSwiped(), which is where a dismiss
 * would normally happen.
 * <p>
 * Only ever attached while BookRecyclerAdapter#isSwipeEnabled() is true (LIST/SIMPLE_LIST view
 * modes); CollectionFragment detaches it (attachToRecyclerView(null)) for GRID/CAROUSEL.
 */
public class SwipeRevealCallback extends ItemTouchHelper.SimpleCallback {

    private final BookRecyclerAdapter adapter;

    public SwipeRevealCallback(BookRecyclerAdapter adapter) {
        // dragDirs = 0: no drag-to-reorder. swipeDirs = LEFT | RIGHT: both swipe directions
        // are handled ourselves below (reveal, not dismiss).
        super(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT);
        this.adapter = adapter;
    }

    @Override
    public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder,
                           @NonNull RecyclerView.ViewHolder target) {
        return false; // no drag-and-drop reordering
    }

    @Override
    public float getSwipeThreshold(@NonNull RecyclerView.ViewHolder viewHolder) {
        // > 1.0 tells ItemTouchHelper the user can never swipe "far enough" to trigger
        // onSwiped()/dismiss - we handle open/close ourselves in onChildDraw/clearView instead.
        return 2.0f;
    }

    @Override
    public float getSwipeEscapeVelocity(float defaultValue) {
        // Raise the fling-velocity threshold so a quick flick doesn't trigger the (empty)
        // onSwiped() dismiss path; all opening/closing goes through clearView()'s snap instead.
        return defaultValue * 6f;
    }

    @Override
    public long getAnimationDuration(@NonNull RecyclerView recyclerView, int animationType,
                                      float animateDx, float animateDy) {
        // Fixes the "must tap a swipe-revealed button several times" bug: onChildDraw() below
        // never calls super.onChildDraw(), so ItemTouchHelper's own idea of this row's position
        // (itemView.getTranslationX()) never actually moves - only our own bvh.foreground child
        // does. But ItemTouchHelper still starts an internal recover animation on every swipe
        // end regardless, and keeps it "pending" for its default duration (a few hundred ms).
        // While pending, a fresh finger-down anywhere on that same row is treated as "grab the
        // still-recovering item" and swallowed instead of reaching the Sync/Share/Delete or
        // Listen/Read button underneath - so the very first tap right after opening a row often
        // does nothing, and it can take several attempts before one lands outside that window.
        // Collapsing this (otherwise purely internal, invisible) recover animation to effectively
        // 0ms removes the swallow window without changing anything the user actually sees, since
        // the real, visible open/close motion is the separate 150ms animate() in clearView().
        if (animationType == ItemTouchHelper.ANIMATION_TYPE_SWIPE_SUCCESS
                || animationType == ItemTouchHelper.ANIMATION_TYPE_SWIPE_CANCEL) {
            return 1L;
        }
        return super.getAnimationDuration(recyclerView, animationType, animateDx, animateDy);
    }

    @Override
    public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView,
                             @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY,
                             int actionState, boolean isCurrentlyActive) {
        if (actionState != ItemTouchHelper.ACTION_STATE_SWIPE) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
            return;
        }
        if (!(viewHolder instanceof BookRecyclerAdapter.BookViewHolder)) return;
        BookRecyclerAdapter.BookViewHolder bvh = (BookRecyclerAdapter.BookViewHolder) viewHolder;
        if (bvh.foreground == null) return;

        // Clamp independently per direction: never past -swipeMaxDxEnd going left (Sync/Share/
        // Delete area) nor past +swipeMaxDxStart going right (Listen/Read area).
        float clamped = Math.max(-bvh.swipeMaxDxEnd, Math.min(bvh.swipeMaxDxStart, dX));
        bvh.foreground.setTranslationX(clamped);
        // Deliberately NOT calling super.onChildDraw(): the default SimpleCallback
        // implementation also translates/fades the whole itemView for a dismiss animation,
        // which we don't want here.
    }

    @Override
    public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
        super.clearView(recyclerView, viewHolder);
        if (!(viewHolder instanceof BookRecyclerAdapter.BookViewHolder)) return;
        BookRecyclerAdapter.BookViewHolder bvh = (BookRecyclerAdapter.BookViewHolder) viewHolder;
        if (bvh.foreground == null) return;

        float current = bvh.foreground.getTranslationX();
        int pos = bvh.getBindingAdapterPosition();

        float target;
        int openDirection;
        if (current <= -bvh.swipeMaxDxEnd * 0.4f) {
            // Snapped open toward the LEFT-revealed buttons (Sync/Share/Delete, on the right).
            target = -bvh.swipeMaxDxEnd;
            openDirection = BookRecyclerAdapter.OPEN_DIRECTION_END;
        } else if (current >= bvh.swipeMaxDxStart * 0.4f) {
            // Snapped open toward the RIGHT-revealed buttons (Listen/Read, on the left).
            target = bvh.swipeMaxDxStart;
            openDirection = BookRecyclerAdapter.OPEN_DIRECTION_START;
        } else {
            target = 0f;
            openDirection = BookRecyclerAdapter.OPEN_DIRECTION_NONE;
        }
        bvh.foreground.animate().translationX(target).setDuration(150).start();

        if (pos == RecyclerView.NO_POSITION) return;
        if (openDirection != BookRecyclerAdapter.OPEN_DIRECTION_NONE) {
            adapter.setOpenPosition(pos, openDirection);
        } else if (adapter.getOpenPosition() == pos) {
            adapter.setOpenPosition(RecyclerView.NO_POSITION, BookRecyclerAdapter.OPEN_DIRECTION_NONE);
        }
    }

    @Override
    public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
        // Intentionally empty: getSwipeThreshold() = 2.0f means ItemTouchHelper never actually
        // calls this in practice, but SimpleCallback declares it abstract so it must be
        // implemented. Open/close handling lives in onChildDraw/clearView above.
    }
}
