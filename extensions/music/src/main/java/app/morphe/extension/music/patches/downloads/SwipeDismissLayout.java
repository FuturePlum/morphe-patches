/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

/**
 * A bar that closes when it is swiped sideways or down, like the mini player of the app.
 * The swipe works from anywhere on the bar, buttons included: the buttons only get a cancel
 * when the finger starts to move, so they never fire by accident.
 */
final class SwipeDismissLayout extends LinearLayout {

    private static final long ANIMATION_MILLISECONDS = 180;
    private static final float DISMISS_DISTANCE = 0.4f;
    private static final float FLING_VELOCITY = 1000f;

    private static final int NONE = 0;
    private static final int HORIZONTAL = 1;
    private static final int DOWN = 2;

    private final Runnable onDismissed;
    private final int slop;

    private float downX;
    private float downY;
    private int axis = NONE;
    private boolean dragging;
    @Nullable
    private VelocityTracker tracker;

    /** @param onDismissed Runs once the bar has slid away. */
    SwipeDismissLayout(Context context, Runnable onDismissed) {
        super(context);
        this.onDismissed = onDismissed;
        this.slop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                downX = event.getRawX();
                downY = event.getRawY();
                axis = NONE;
                dragging = false;
                resetTracker();
                tracker = VelocityTracker.obtain();
                tracker.addMovement(event);
            }
            case MotionEvent.ACTION_MOVE -> {
                if (tracker == null || dragging) break;
                tracker.addMovement(event);
                final float dx = event.getRawX() - downX;
                final float dy = event.getRawY() - downY;
                if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                    axis = HORIZONTAL;
                } else if (dy > slop && dy > Math.abs(dx)) {
                    axis = DOWN;
                }
                if (axis != NONE) {
                    dragging = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> resetTracker();
            default -> {
            }
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!dragging) return super.onTouchEvent(event);

        if (tracker != null) tracker.addMovement(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE -> {
                final float dx = event.getRawX() - downX;
                final float dy = event.getRawY() - downY;
                if (axis == HORIZONTAL) setTranslationX(dx);
                else setTranslationY(Math.max(0, dy));
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                float velocity = 0;
                if (tracker != null) {
                    tracker.computeCurrentVelocity(1000);
                    velocity = axis == HORIZONTAL ? tracker.getXVelocity() : tracker.getYVelocity();
                }
                resetTracker();
                dragging = false;
                finishDrag(event.getActionMasked() == MotionEvent.ACTION_UP, velocity);
            }
            default -> {
            }
        }
        return true;
    }

    private void finishDrag(boolean released, float velocity) {
        final boolean horizontal = axis == HORIZONTAL;
        final float offset = horizontal ? getTranslationX() : getTranslationY();
        final float size = horizontal ? getWidth() : getHeight();
        final boolean sameWay = Math.signum(velocity) == Math.signum(offset);
        final boolean away = released && size > 0
                && (Math.abs(offset) > size * DISMISS_DISTANCE
                || (Math.abs(velocity) > FLING_VELOCITY && sameWay));

        if (!away) {
            animate().translationX(0).translationY(0).setDuration(ANIMATION_MILLISECONDS)
                    .setInterpolator(new DecelerateInterpolator()).start();
            return;
        }
        final float target = horizontal ? Math.signum(offset) * size : size;
        var animation = animate().setDuration(ANIMATION_MILLISECONDS)
                .setInterpolator(new AccelerateInterpolator());
        if (horizontal) animation.translationX(target);
        else animation.translationY(target);
        animation.withEndAction(() -> {
            // Out of sight, and back in place for the next time the bar is shown.
            setVisibility(View.GONE);
            setTranslationX(0);
            setTranslationY(0);
            onDismissed.run();
        }).start();
    }

    private void resetTracker() {
        if (tracker != null) {
            tracker.recycle();
            tracker = null;
        }
    }
}
