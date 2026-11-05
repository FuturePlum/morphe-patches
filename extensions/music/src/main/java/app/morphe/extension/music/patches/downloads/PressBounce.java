/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.DecelerateInterpolator;
import android.widget.AbsSeekBar;
import android.widget.EditText;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Every small button of the app gives way under the finger and springs back when it is let go,
 * the way the buttons of the downloads screen do.
 * <p>
 * The touches of each window are watched, and the button under the finger is found among the
 * views. Nothing here knows the ids or the classes of the app, so it keeps working from one
 * version to the next. Lists, bars, rows and anything that already moves by itself are left alone.
 */
public final class PressBounce {

    private static final float PRESSED_SCALE = 0.93f;

    /** A target is a button, not a row or a page: it is narrower than this part of the screen. */
    private static final float MAX_WIDTH_FRACTION = 0.6f;
    private static final float MAX_HEIGHT_FRACTION = 0.3f;
    private static final float MIN_SIZE_DP = 20;

    private static final Set<Window> wrappedWindows =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean installed;

    private PressBounce() {
    }

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
            Logger.printDebug(() -> "Press bounce installed");
        } catch (Exception ex) {
            Logger.printException(() -> "install failure", ex);
        }
    }

    private static void wrap(Activity activity) {
        try {
            if (activity.getClass().getName().contains("GoogleApiActivity")) return;

            Window window = activity.getWindow();
            if (window == null || window.getCallback() == null) return;
            if (!wrappedWindows.add(window)) return;

            window.setCallback(new PressWindowCallback(window, window.getCallback()));
        } catch (Exception ex) {
            Logger.printException(() -> "wrap failure", ex);
        }
    }

    /**
     * Watches the touches of a window, and lets the button under the finger give way.
     */
    private static final class PressWindowCallback implements Window.Callback {
        private final Window window;
        private final Window.Callback delegate;
        private final float density;
        private final int minSizePx;

        private View pressed;
        private float restScaleX;
        private float restScaleY;

        PressWindowCallback(Window window, Window.Callback delegate) {
            this.window = window;
            this.delegate = delegate;
            this.density = window.getContext().getResources().getDisplayMetrics().density;
            this.minSizePx = Math.round(MIN_SIZE_DP * density);
        }

        // region Touches

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            try {
                onTouch(event);
            } catch (Throwable t) {
                Logger.printException(() -> "Press bounce touch failure", t);
                release();
            }
            return delegate.dispatchTouchEvent(event);
        }

        private void onTouch(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    release();
                    if (DownloadsMotion.enabled()) {
                        View target = findTarget(window.getDecorView(),
                                Math.round(event.getRawX()), Math.round(event.getRawY()), true);
                        if (target != null) press(target);
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    // Once the finger leaves the button the tap is lost, and so is the press.
                    if (pressed != null && !isInside(pressed, event.getRawX(), event.getRawY())) {
                        release();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_POINTER_DOWN:
                    release();
                    break;
                default:
                    break;
            }
        }

        private void press(View target) {
            pressed = target;
            restScaleX = target.getScaleX();
            restScaleY = target.getScaleY();
            target.animate().cancel();
            target.animate()
                    .scaleX(restScaleX * PRESSED_SCALE)
                    .scaleY(restScaleY * PRESSED_SCALE)
                    .setDuration(110)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }

        private void release() {
            final View view = pressed;
            if (view == null) return;
            pressed = null;

            view.animate().cancel();
            view.animate()
                    .scaleX(restScaleX)
                    .scaleY(restScaleY)
                    .setDuration(460)
                    .setInterpolator(DownloadsMotion.BOUNCY)
                    .start();
        }

        // endregion

        // region Finding the button

        private boolean isInside(View view, float rawX, float rawY) {
            int[] at = new int[2];
            view.getLocationOnScreen(at);
            // The view is a little smaller while pressed, so the finger gets some slack.
            final float slack = 12 * density;
            return rawX >= at[0] - slack && rawX <= at[0] + view.getWidth() + slack
                    && rawY >= at[1] - slack && rawY <= at[1] + view.getHeight() + slack;
        }

        /**
         * The deepest view under the finger that is a button, looking from the top of the stack.
         */
        private View findTarget(View view, int x, int y, boolean isRoot) {
            if (view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0f) return null;
            if (!isRoot && !isInside(view, x, y, 0)) return null;

            if (view instanceof ViewGroup group) {
                // Their children are not real views, and they scroll, play or draw by themselves.
                if (!isWalkable(group)) return isButton(view) ? view : null;
                for (int i = group.getChildCount() - 1; i >= 0; i--) {
                    View found = findTarget(group.getChildAt(i), x, y, false);
                    if (found != null) return found;
                }
            }
            return isButton(view) ? view : null;
        }

        private boolean isInside(View view, int rawX, int rawY, int slack) {
            int[] at = new int[2];
            view.getLocationOnScreen(at);
            return rawX >= at[0] - slack && rawX <= at[0] + view.getWidth() + slack
                    && rawY >= at[1] - slack && rawY <= at[1] + view.getHeight() + slack;
        }

        private boolean isWalkable(ViewGroup group) {
            final String name = group.getClass().getName();
            return !name.contains("Litho") && !name.contains("WebView");
        }

        private boolean isButton(View view) {
            if (!view.isClickable() || !view.isEnabled()) return false;
            if (view instanceof AbsSeekBar || view instanceof EditText) return false;
            // Whoever gave it a state animator already decides how it moves.
            if (view.getStateListAnimator() != null) return false;
            if (view.getClass().getName().contains("Litho")) return false;

            final DisplayMetrics screen = view.getResources().getDisplayMetrics();
            final int width = view.getWidth();
            final int height = view.getHeight();
            return width >= minSizePx && height >= minSizePx
                    && width <= screen.widthPixels * MAX_WIDTH_FRACTION
                    && height <= screen.heightPixels * MAX_HEIGHT_FRACTION;
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
}
