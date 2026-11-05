/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

/**
 * A row that is dragged to the right to trigger an action, like the 'Play next' swipe of the
 * rows of the stock app. The row follows the finger one to one until the threshold, then only
 * a part of the movement is kept, so the end of the drag feels like it pulls back.
 * Letting go past the threshold runs the action and the row slides back.
 */
@SuppressLint("ViewConstructor")
final class SwipeRowLayout extends FrameLayout {

    /** Share of the finger movement that still moves the row once the threshold is passed. */
    private static final float RESISTANCE_PAST_THRESHOLD = 0.35f;

    /** The row never leaves more than this share of its width, however far the finger goes. */
    private static final float MAX_TRAVEL_FRACTION = 0.6f;

    /** The movement has to be this much more horizontal than vertical, so scrolling stays easy. */
    private static final float HORIZONTAL_DOMINANCE = 2f;

    private static final long SETTLE_MILLISECONDS = 200;

    private final View surface;
    private final View reveal;
    private final ImageView revealIcon;
    private final TextView revealLabel;
    private final GradientDrawable revealBackground;
    private final int idleColor;
    private final int armedColor;
    private final int threshold;
    private final int touchSlop;
    private final Runnable onSwiped;

    private float downX;
    private float downY;
    private boolean dragging;

    /** Whether letting go now would run the action. */
    private boolean armed;

    /**
     * @param content      The row that is dragged.
     * @param surfaceColor Opaque color of the page, so the layer under the row stays hidden.
     * @param idleColor    Color of the layer under the row before the threshold.
     * @param armedColor   Color of the layer once letting go would run the action.
     * @param thresholdPx  How far the finger has to go to arm the action.
     */
    SwipeRowLayout(Context context, View content, int surfaceColor, int idleColor,
                   int armedColor, int foreground, int cornerPx, int thresholdPx,
                   String label, Runnable onSwiped) {
        super(context);
        this.idleColor = idleColor;
        this.armedColor = armedColor;
        this.threshold = thresholdPx;
        this.onSwiped = onSwiped;
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        final float density = context.getResources().getDisplayMetrics().density;

        LinearLayout revealRow = new LinearLayout(context);
        revealRow.setOrientation(LinearLayout.HORIZONTAL);
        revealRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        revealRow.setPadding((int) (22 * density), 0, 0, 0);

        revealBackground = new GradientDrawable();
        revealBackground.setCornerRadius(cornerPx);
        revealBackground.setColor(idleColor);
        revealRow.setBackground(revealBackground);

        final int iconSize = (int) (24 * density);
        revealIcon = new ImageView(context);
        revealIcon.setImageDrawable(new PlayNextIcon(foreground, iconSize));
        revealRow.addView(revealIcon, new LinearLayout.LayoutParams(iconSize, iconSize));

        revealLabel = new TextView(context);
        revealLabel.setText(label);
        revealLabel.setTextSize(14);
        revealLabel.setTextColor(foreground);
        revealLabel.setTypeface(Typeface.DEFAULT_BOLD);
        revealLabel.setSingleLine(true);
        LinearLayout.LayoutParams labelParams =
                new LinearLayout.LayoutParams(-2, -2);
        labelParams.leftMargin = (int) (12 * density);
        revealRow.addView(revealLabel, labelParams);

        reveal = revealRow;
        reveal.setVisibility(INVISIBLE);
        addView(reveal, new LayoutParams(-1, -1));

        FrameLayout movingSurface = new FrameLayout(context);
        movingSurface.setBackgroundColor(surfaceColor);
        movingSurface.addView(content, new LayoutParams(-1, -2));
        surface = movingSurface;
        addView(surface, new LayoutParams(-1, -2));
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && startsHorizontalDrag(event)) {
                    beginDrag();
                    drag(event.getX() - downX - touchSlop);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                break;
            default:
                break;
        }
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && startsHorizontalDrag(event)) beginDrag();
                if (dragging) drag(event.getX() - downX - touchSlop);
                return true;
            case MotionEvent.ACTION_UP:
                finishDrag(true);
                return true;
            case MotionEvent.ACTION_CANCEL:
                finishDrag(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private boolean startsHorizontalDrag(MotionEvent event) {
        final float dx = event.getX() - downX;
        final float dy = event.getY() - downY;
        return dx > touchSlop && dx > HORIZONTAL_DOMINANCE * Math.abs(dy);
    }

    private void beginDrag() {
        dragging = true;
        // The list must not start scrolling in the middle of the gesture.
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        surface.animate().cancel();
        reveal.setVisibility(VISIBLE);
    }

    private void drag(float movement) {
        final float raw = Math.max(0f, movement);
        float travel = raw <= threshold
                ? raw
                : threshold + (raw - threshold) * RESISTANCE_PAST_THRESHOLD;
        travel = Math.min(travel, getWidth() * MAX_TRAVEL_FRACTION);
        surface.setTranslationX(travel);

        final float progress = threshold <= 0 ? 1f : Math.min(1f, travel / threshold);
        revealIcon.setAlpha(progress);
        revealLabel.setAlpha(progress);

        final boolean nowArmed = raw >= threshold;
        if (nowArmed != armed) {
            armed = nowArmed;
            revealBackground.setColor(armed ? armedColor : idleColor);
            if (armed) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                DownloadsMotion.popIn(revealIcon, 0.55f);
            }
        }
    }

    private void finishDrag(boolean mayTrigger) {
        final boolean trigger = mayTrigger && dragging && armed;
        dragging = false;
        armed = false;

        surface.animate()
                .translationX(0f)
                .setDuration(SETTLE_MILLISECONDS + 260)
                .setInterpolator(DownloadsMotion.SOFT)
                .withEndAction(() -> {
                    reveal.setVisibility(INVISIBLE);
                    revealBackground.setColor(idleColor);
                })
                .start();

        if (trigger) onSwiped.run();
    }

    @Override
    protected void onDetachedFromWindow() {
        surface.animate().cancel();
        super.onDetachedFromWindow();
    }

    /** Lines of a queue followed by a play triangle, drawn so no icon of the app is guessed at. */
    private static final class PlayNextIcon extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path triangle = new Path();
        private final int size;

        PlayNextIcon(int color, int size) {
            this.size = size;
            paint.setColor(color);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            final Rect bounds = getBounds();
            final float w = bounds.width();
            final float h = bounds.height();

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(h * 0.09f);
            for (float y : new float[]{0.25f, 0.5f, 0.75f}) {
                canvas.drawLine(bounds.left + w * 0.1f, bounds.top + h * y,
                        bounds.left + w * 0.55f, bounds.top + h * y, paint);
            }

            paint.setStyle(Paint.Style.FILL);
            triangle.reset();
            triangle.moveTo(bounds.left + w * 0.66f, bounds.top + h * 0.3f);
            triangle.lineTo(bounds.left + w * 0.92f, bounds.top + h * 0.5f);
            triangle.lineTo(bounds.left + w * 0.66f, bounds.top + h * 0.7f);
            triangle.close();
            canvas.drawPath(triangle, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
    }
}
