/*
 * Swipe a track of a playlist to the right to play it next.
 *
 * Everything happens inside the app: no request is sent. The swipe replays a tap on the row,
 * and the tap command is swapped for the app's own 'play next' queue command
 * (see PlaylistTrackTapPatch#overrideCommandForSwipe).
 */

package app.morphe.extension.music.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.ActionMode;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class PlaylistSwipePlayNextPatch {

    /**
     * How long the tap that the swipe replays may take to reach the command resolver.
     */
    private static final long REQUEST_TIMEOUT_MS = 350;

    /**
     * Uptime of the replayed tap, or 0 when there is none.
     */
    private static volatile long requestUptimeMs;

    private static boolean installed;

    private static final Set<Window> wrappedWindows =
            Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Injection point, from the main activity onCreate.
     */
    public static void install() {
        try {
            if (installed) return;

            Context context = Utils.getContext();
            if (context == null) return;
            Application application = (Application) context.getApplicationContext();
            application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                    wrap(activity);
                }

                @Override
                public void onActivityStarted(Activity activity) {
                    wrap(activity);
                }

                @Override
                public void onActivityResumed(Activity activity) {
                    wrap(activity);
                }

                @Override
                public void onActivityPaused(Activity activity) {
                }

                @Override
                public void onActivityStopped(Activity activity) {
                }

                @Override
                public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                }

                @Override
                public void onActivityDestroyed(Activity activity) {
                }
            });
            installed = true;
            Logger.printDebug(() -> "Swipe to play next installed");
        } catch (Exception ex) {
            Logger.printException(() -> "install failure", ex);
        }
    }

    /**
     * Called by the command resolver hook, for every command.
     *
     * @return If the command is the tap that a swipe replayed. The request is used up.
     */
    public static boolean consumeRequest() {
        long requested = requestUptimeMs;
        if (requested == 0) return false;

        requestUptimeMs = 0;
        return SystemClock.uptimeMillis() - requested <= REQUEST_TIMEOUT_MS;
    }

    /**
     * Called once the track has been queued.
     */
    public static void showQueuedFeedback() {
        Utils.showToastShort(str("morphe_music_swipe_play_next_added"));
    }

    private static void wrap(Activity activity) {
        try {
            // The settings and downloads screens have swipes of their own.
            if (activity.getClass().getName().contains("GoogleApiActivity")) return;

            Window window = activity.getWindow();
            if (window == null || window.getCallback() == null) return;
            if (!wrappedWindows.add(window)) return;

            window.setCallback(new SwipeWindowCallback(window, window.getCallback()));
        } catch (Exception ex) {
            Logger.printException(() -> "wrap failure", ex);
        }
    }

    private enum State {
        /**
         * No gesture, or the gesture has nothing to do with this feature.
         */
        IGNORED,
        /**
         * A finger is down on a row, and it is not yet known if this is a swipe.
         */
        TRACKING,
        /**
         * The swipe is ours, and the app no longer receives the touches.
         */
        LOCKED
    }

    /**
     * Watches the touches of a window, and takes over the ones that swipe a row to the right.
     */
    private static final class SwipeWindowCallback implements Window.Callback {
        private static final float EDGE_GUTTER_DP = 28;
        private static final float TRIGGER_SHIFT_DP = 56;
        private static final float MAX_SHIFT_DP = 120;
        private static final float MIN_ROW_HEIGHT_DP = 40;
        private static final float MAX_ROW_HEIGHT_DP = 160;

        private final Window window;
        private final Window.Callback delegate;
        private final float density;
        private final int touchSlop;

        private State state = State.IGNORED;
        private float downRawX;
        private float downRawY;
        private View row;
        private ViewGroup list;
        private RevealDrawable reveal;
        private boolean armed;
        private float shift;
        private ValueAnimator settleAnimator;

        SwipeWindowCallback(Window window, Window.Callback delegate) {
            this.window = window;
            this.delegate = delegate;
            Context context = window.getContext();
            this.density = context.getResources().getDisplayMetrics().density;
            this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        // region Touches

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            boolean consumed = false;
            try {
                consumed = onTouch(event);
            } catch (Throwable t) {
                Logger.printException(() -> "Swipe touch failure", t);
                abort();
            }
            return consumed || delegate.dispatchTouchEvent(event);
        }

        private boolean onTouch(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    onDown(event);
                    return false;
                case MotionEvent.ACTION_MOVE:
                    return onMove(event);
                case MotionEvent.ACTION_UP:
                    return onUp(event);
                case MotionEvent.ACTION_CANCEL:
                    return onCancel();
                case MotionEvent.ACTION_POINTER_DOWN:
                    // A second finger is never a swipe of ours.
                    if (state == State.LOCKED) {
                        onCancel();
                        return true;
                    }
                    state = State.IGNORED;
                    return false;
                default:
                    return state == State.LOCKED;
            }
        }

        private void onDown(MotionEvent event) {
            abort();
            // A tap the user makes now is never one of ours.
            requestUptimeMs = 0;

            state = State.IGNORED;
            if (!Settings.SWIPE_PLAY_NEXT.get()) return;
            if (event.getPointerCount() != 1) return;
            if (!findRow(event)) return;

            downRawX = event.getRawX();
            downRawY = event.getRawY();
            state = State.TRACKING;
        }

        private boolean onMove(MotionEvent event) {
            if (state == State.IGNORED) return false;

            float dx = event.getRawX() - downRawX;
            float dy = event.getRawY() - downRawY;

            if (state == State.LOCKED) {
                updateShift(dx);
                return true;
            }

            // TRACKING
            if (Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx)) {
                // A scroll.
                state = State.IGNORED;
                return false;
            }
            if (dx > touchSlop * 1.5f && dx > Math.abs(dy) * 1.6f) {
                lock(event);
                updateShift(dx);
                return true;
            }
            return false;
        }

        private boolean onUp(MotionEvent event) {
            if (state != State.LOCKED) {
                state = State.IGNORED;
                return false;
            }

            boolean trigger = armed;
            View tappedRow = row;
            if (trigger && tappedRow != null) {
                // The tap must land on a row that is back in place.
                abort();
                replayTap(event, tappedRow);
            } else {
                settle(false);
            }
            state = State.IGNORED;
            return true;
        }

        private boolean onCancel() {
            boolean wasLocked = state == State.LOCKED;
            if (wasLocked) settle(false);
            state = State.IGNORED;
            return wasLocked;
        }

        /**
         * Starts the swipe: the app is told that its touch is over.
         */
        private void lock(MotionEvent event) {
            // A swipe that starts while the previous one is still settling takes over the row.
            if (settleAnimator != null) settleAnimator.cancel();

            MotionEvent cancel = MotionEvent.obtain(event);
            try {
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                delegate.dispatchTouchEvent(cancel);
            } finally {
                cancel.recycle();
            }

            state = State.LOCKED;
            armed = false;
            shift = 0;

            reveal = new RevealDrawable(density);
            reveal.setBounds(0, 0, list.getWidth(), list.getHeight());
            reveal.label = str("morphe_music_swipe_play_next_label");
            list.getOverlay().add(reveal);
        }

        /**
         * Replays a tap on the row. The command it sends is swapped by
         * {@link PlaylistTrackTapPatch#overrideCommand}.
         */
        private void replayTap(MotionEvent source, View tappedRow) {
            int[] rowLocation = new int[2];
            int[] decorLocation = new int[2];
            tappedRow.getLocationOnScreen(rowLocation);
            window.getDecorView().getLocationOnScreen(decorLocation);

            // The text of the row, away from its buttons.
            float x = rowLocation[0] - decorLocation[0] + tappedRow.getWidth() * 0.45f;
            float y = rowLocation[1] - decorLocation[1] + tappedRow.getHeight() / 2f;

            long now = SystemClock.uptimeMillis();
            requestUptimeMs = now;

            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x, y, 0);
            try {
                down.setSource(source.getSource());
                up.setSource(source.getSource());
                delegate.dispatchTouchEvent(down);
                delegate.dispatchTouchEvent(up);
            } finally {
                down.recycle();
                up.recycle();
            }

            // The tap may reach the resolver a little later. If it did not, drop the request.
            Utils.runOnMainThreadDelayed(() -> {
                if (requestUptimeMs == now) requestUptimeMs = 0;
            }, REQUEST_TIMEOUT_MS + 50);
        }

        // endregion

        // region Row detection

        /**
         * Looks for a row of a vertical list under the finger. A row that already swipes by itself
         * (albums, the queue) is left alone.
         */
        private boolean findRow(MotionEvent event) {
            View decor = window.getDecorView();
            int[] decorLocation = new int[2];
            decor.getLocationOnScreen(decorLocation);
            // Keep clear of the back gesture of the system.
            if (event.getRawX() - decorLocation[0] < EDGE_GUTTER_DP * density) return false;

            View hit = deepestViewAt(decor, (int) event.getRawX(), (int) event.getRawY());
            if (hit == null) return false;

            View foundRow = null;
            ViewGroup foundList = null;
            View view = hit;
            while (view != null) {
                if (isNativeSwipeLayout(view) || hasIdNamed(view, "queue")) return false;

                ViewParent parent = view.getParent();
                if (foundRow == null && parent instanceof ViewGroup
                        && isVerticalList((ViewGroup) parent, view)) {
                    foundRow = view;
                    foundList = (ViewGroup) parent;
                }
                view = parent instanceof View ? (View) parent : null;
            }

            if (foundRow == null) return false;
            row = foundRow;
            list = foundList;
            final View loggedRow = foundRow;
            final ViewGroup loggedList = foundList;
            Logger.printDebug(() -> "Swipe row found: " + loggedRow.getClass().getName()
                    + " in list: " + loggedList.getClass().getName());
            return true;
        }

        private boolean isVerticalList(ViewGroup candidate, View candidateRow) {
            if (!isRecyclerView(candidate)
                    && !candidate.canScrollVertically(1) && !candidate.canScrollVertically(-1)) {
                return false;
            }
            // A carousel scrolls sideways, and its cards are narrow.
            if (candidate.canScrollHorizontally(1) || candidate.canScrollHorizontally(-1)) return false;
            if (candidateRow.getWidth() < candidate.getWidth() * 0.7f) return false;

            float height = candidateRow.getHeight();
            return height >= MIN_ROW_HEIGHT_DP * density && height <= MAX_ROW_HEIGHT_DP * density;
        }

        private static boolean isRecyclerView(View view) {
            for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
                if ("androidx.recyclerview.widget.RecyclerView".equals(type.getName())) return true;
            }
            return false;
        }

        /**
         * The app's own row that swipes.
         */
        private static boolean isNativeSwipeLayout(View view) {
            return view.getClass().getName().contains("swipelayout.SwipeLayout");
        }

        private static boolean hasIdNamed(View view, String part) {
            int id = view.getId();
            if (id == View.NO_ID) return false;
            try {
                String name = view.getResources().getResourceEntryName(id);
                return name != null && name.contains(part);
            } catch (Resources.NotFoundException ex) {
                return false;
            }
        }

        private static View deepestViewAt(View view, int rawX, int rawY) {
            if (view.getVisibility() != View.VISIBLE) return null;

            int[] location = new int[2];
            view.getLocationOnScreen(location);
            if (rawX < location[0] || rawX >= location[0] + view.getWidth()
                    || rawY < location[1] || rawY >= location[1] + view.getHeight()) {
                return null;
            }

            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = group.getChildCount() - 1; i >= 0; i--) {
                    View found = deepestViewAt(group.getChildAt(i), rawX, rawY);
                    if (found != null) return found;
                }
            }
            return view;
        }

        // endregion

        // region Drawing

        private void updateShift(float dx) {
            if (row == null || reveal == null) return;

            float max = MAX_SHIFT_DP * density;
            // Resists more and more, like a rubber band.
            shift = dx <= 0 ? 0 : max * (1f - (float) Math.exp(-0.75f * dx / max));
            apply(shift);

            boolean nowArmed = shift >= TRIGGER_SHIFT_DP * density;
            if (nowArmed != armed) {
                armed = nowArmed;
                reveal.armed = armed;
                if (armed) list.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                list.invalidate();
            }
        }

        private void apply(float value) {
            row.setTranslationX(value);
            reveal.rowLeft = row.getLeft();
            reveal.rowTop = row.getTop();
            reveal.rowBottom = row.getBottom();
            reveal.shift = value;
            list.invalidate();
        }

        /**
         * Brings the row back, and drops what was drawn.
         */
        private void settle(boolean quick) {
            final View settledRow = row;
            final ViewGroup settledList = list;
            final RevealDrawable settledReveal = reveal;
            row = null;
            list = null;
            reveal = null;
            if (settledRow == null || settledReveal == null) return;

            if (settleAnimator != null) settleAnimator.cancel();
            ValueAnimator animator = ValueAnimator.ofFloat(shift, 0f);
            if (Settings.SMOOTH_ANIMATIONS.get()) {
                animator.setDuration(quick ? 80 : 300);
                animator.setInterpolator(new android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f));
            } else {
                animator.setDuration(quick ? 80 : 180);
            }
            animator.addUpdateListener(a -> {
                float value = (float) a.getAnimatedValue();
                settledRow.setTranslationX(value);
                settledReveal.rowLeft = settledRow.getLeft();
                settledReveal.rowTop = settledRow.getTop();
                settledReveal.rowBottom = settledRow.getBottom();
                settledReveal.shift = value;
                settledList.invalidate();
            });
            animator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    finish();
                }

                @Override
                public void onAnimationCancel(android.animation.Animator animation) {
                    finish();
                }

                private void finish() {
                    settledRow.setTranslationX(0);
                    settledList.getOverlay().remove(settledReveal);
                    settledList.invalidate();
                }
            });
            settleAnimator = animator;
            animator.start();
        }

        /**
         * Gives up the current gesture without any animation.
         */
        private void abort() {
            if (state == State.LOCKED || reveal != null) {
                if (row != null) row.setTranslationX(0);
                if (list != null && reveal != null) {
                    list.getOverlay().remove(reveal);
                    list.invalidate();
                }
            }
            row = null;
            list = null;
            reveal = null;
            armed = false;
            shift = 0;
            state = State.IGNORED;
        }

        // endregion

        // region Window.Callback, passed through

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            return delegate.dispatchKeyEvent(event);
        }

        @Override
        public boolean dispatchKeyShortcutEvent(KeyEvent event) {
            return delegate.dispatchKeyShortcutEvent(event);
        }

        @Override
        public boolean dispatchTrackballEvent(MotionEvent event) {
            return delegate.dispatchTrackballEvent(event);
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent event) {
            return delegate.dispatchGenericMotionEvent(event);
        }

        @Override
        public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
            return delegate.dispatchPopulateAccessibilityEvent(event);
        }

        @Override
        public View onCreatePanelView(int featureId) {
            return delegate.onCreatePanelView(featureId);
        }

        @Override
        public boolean onCreatePanelMenu(int featureId, Menu menu) {
            return delegate.onCreatePanelMenu(featureId, menu);
        }

        @Override
        public boolean onPreparePanel(int featureId, View view, Menu menu) {
            return delegate.onPreparePanel(featureId, view, menu);
        }

        @Override
        public boolean onMenuOpened(int featureId, Menu menu) {
            return delegate.onMenuOpened(featureId, menu);
        }

        @Override
        public boolean onMenuItemSelected(int featureId, MenuItem item) {
            return delegate.onMenuItemSelected(featureId, item);
        }

        @Override
        public void onWindowAttributesChanged(WindowManager.LayoutParams attrs) {
            delegate.onWindowAttributesChanged(attrs);
        }

        @Override
        public void onContentChanged() {
            delegate.onContentChanged();
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            delegate.onWindowFocusChanged(hasFocus);
        }

        @Override
        public void onAttachedToWindow() {
            delegate.onAttachedToWindow();
        }

        @Override
        public void onDetachedFromWindow() {
            delegate.onDetachedFromWindow();
        }

        @Override
        public void onPanelClosed(int featureId, Menu menu) {
            delegate.onPanelClosed(featureId, menu);
        }

        @Override
        public boolean onSearchRequested() {
            return delegate.onSearchRequested();
        }

        @Override
        public boolean onSearchRequested(SearchEvent searchEvent) {
            return delegate.onSearchRequested(searchEvent);
        }

        @Override
        public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) {
            return delegate.onWindowStartingActionMode(callback);
        }

        @Override
        public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type) {
            return delegate.onWindowStartingActionMode(callback, type);
        }

        @Override
        public void onActionModeStarted(ActionMode mode) {
            delegate.onActionModeStarted(mode);
        }

        @Override
        public void onActionModeFinished(ActionMode mode) {
            delegate.onActionModeFinished(mode);
        }

        @Override
        public void onProvideKeyboardShortcuts(List<KeyboardShortcutGroup> data, Menu menu, int deviceId) {
            delegate.onProvideKeyboardShortcuts(data, menu, deviceId);
        }

        @Override
        public void onPointerCaptureChanged(boolean hasCapture) {
            delegate.onPointerCaptureChanged(hasCapture);
        }

        // endregion
    }

    /**
     * What shows behind the row while it slides: a colored strip with 'Play next'.
     */
    private static final class RevealDrawable extends Drawable {
        private static final int COLOR_IDLE = 0xFF2B2B2B;
        private static final int COLOR_ARMED = 0xFF2962FF;

        private final float density;
        private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint foreground = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path icon = new Path();
        private final Rect textBounds = new Rect();

        String label = "";
        boolean armed;
        float shift;
        int rowLeft;
        int rowTop;
        int rowBottom;

        RevealDrawable(float density) {
            this.density = density;
            foreground.setColor(0xFFFFFFFF);
            foreground.setTextSize(14 * density);
            foreground.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        }

        @Override
        public void draw(Canvas canvas) {
            if (shift < 1) return;

            background.setColor(armed ? COLOR_ARMED : COLOR_IDLE);
            float left = rowLeft;
            float right = rowLeft + shift;
            float centerY = (rowTop + rowBottom) / 2f;

            canvas.save();
            canvas.clipRect(left, rowTop, right, rowBottom);
            canvas.drawRect(left, rowTop, right, rowBottom, background);

            // The content slides in with the row, its right edge following the edge of the row.
            float padding = 16 * density;
            foreground.getTextBounds(label, 0, label.length(), textBounds);
            float textRight = right - padding;
            float textLeft = textRight - textBounds.width();
            canvas.drawText(label, textLeft, centerY - textBounds.exactCenterY(), foreground);

            // 'Play next' icon: a play triangle.
            float size = 16 * density;
            float iconRight = textLeft - 10 * density;
            float iconLeft = iconRight - size;
            icon.reset();
            icon.moveTo(iconLeft, centerY - size / 2);
            icon.lineTo(iconRight, centerY);
            icon.lineTo(iconLeft, centerY + size / 2);
            icon.close();
            canvas.drawPath(icon, foreground);
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
