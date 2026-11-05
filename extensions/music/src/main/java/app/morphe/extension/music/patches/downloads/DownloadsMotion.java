/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.StateListAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.util.StateSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.ListPopupWindow;

import java.util.List;

import app.morphe.extension.music.settings.Settings;

/**
 * Motion of the downloads screen and of the offline players: springs that overshoot and settle,
 * presses that give way under the finger, lists that rise into place.
 */
final class DownloadsMotion {

    /**
     * A damped spring: it runs past the target, comes back and settles. The first number is how
     * fast the swing dies out, the second how fast it swings.
     */
    static final class Spring implements TimeInterpolator {
        private final float damping;
        private final float frequency;

        Spring(float damping, float frequency) {
            this.damping = damping;
            this.frequency = frequency;
        }

        @Override
        public float getInterpolation(float t) {
            if (t >= 1f || !enabled()) return 1f;
            return (float) (1d - Math.exp(-damping * t) * Math.cos(frequency * t));
        }
    }

    /** Runs about a quarter past the target: for small pops and presses. */
    static final TimeInterpolator BOUNCY = new Spring(5f, 12f);

    /** Runs about a tenth past the target: for panels and things that travel far. */
    static final TimeInterpolator SOFT = new Spring(7f, 9f);

    /** A sheet that rises and settles: a little past its place, and no more. */
    static final TimeInterpolator PANEL = new Spring(8f, 8.5f);

    /** Barely runs past the target: for what must not show what is behind it. */
    static final TimeInterpolator SETTLE = new Spring(9f, 7f);

    /** Arrives fast and lands softly, without running past. */
    static final TimeInterpolator ENTER = new Gated(new PathInterpolator(0.05f, 0.7f, 0.1f, 1f));

    /** Leaves slowly and then goes. */
    static final TimeInterpolator EXIT = new Gated(new PathInterpolator(0.3f, 0f, 0.8f, 0.15f));

    /** A list is only staggered as far as the screen can show. */
    private static final int MAX_STAGGERED = 12;
    private static final long STAGGER_STEP_MILLISECONDS = 34;

    private DownloadsMotion() {
    }

    /** Whether the user left the animations on, in the settings. */
    static boolean enabled() {
        return Settings.SMOOTH_ANIMATIONS.get();
    }

    /** Plays the curve it wraps, or lands on the end at once when the animations are off. */
    private static final class Gated implements TimeInterpolator {
        private final TimeInterpolator curve;

        Gated(TimeInterpolator curve) {
            this.curve = curve;
        }

        @Override
        public float getInterpolation(float t) {
            return enabled() ? curve.getInterpolation(t) : 1f;
        }
    }

    private static float density(View view) {
        return view.getResources().getDisplayMetrics().density;
    }

    /**
     * The view gives way under the finger and springs back when it is let go. The pressed state
     * drives it, so it does not take over the touch listener of the view.
     */
    static void pressable(View view, float pressedScale) {
        if (!enabled()) return;
        ObjectAnimator press = ObjectAnimator.ofPropertyValuesHolder(view,
                PropertyValuesHolder.ofFloat(View.SCALE_X, pressedScale),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, pressedScale));
        press.setDuration(110);
        press.setInterpolator(new DecelerateInterpolator());

        ObjectAnimator release = ObjectAnimator.ofPropertyValuesHolder(view,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f));
        release.setDuration(460);
        release.setInterpolator(BOUNCY);

        StateListAnimator states = new StateListAnimator();
        states.addState(new int[]{android.R.attr.state_pressed}, press);
        states.addState(StateSet.WILD_CARD, release);
        view.setStateListAnimator(states);
    }

    /** Views come up from below one after the other, each with a small spring at the end. */
    static void staggerIn(List<View> views, float risePx) {
        staggerIn(views, risePx, 0, MAX_STAGGERED);
    }

    static void staggerIn(List<View> views, float risePx, long firstDelayMilliseconds, int max) {
        if (!enabled()) return;
        final int count = Math.min(views.size(), max);
        for (int i = 0; i < count; i++) {
            final View view = views.get(i);
            final long delay = firstDelayMilliseconds + i * STAGGER_STEP_MILLISECONDS;

            // A view that rests dimmed, like a track already played, comes back to that.
            final float rest = view.getAlpha();
            view.setAlpha(0f);
            view.setTranslationY(risePx);

            ObjectAnimator fade = ObjectAnimator.ofFloat(view, View.ALPHA, 0f, rest);
            fade.setDuration(240);
            fade.setStartDelay(delay);
            fade.start();

            view.animate().translationY(0f)
                    .setStartDelay(delay)
                    .setDuration(520)
                    .setInterpolator(SOFT)
                    .start();
        }
    }

    /** Grows from a smaller size with a spring, for what has just changed under the eye. */
    static void popIn(View view, float fromScale) {
        if (!enabled()) return;
        view.setScaleX(fromScale);
        view.setScaleY(fromScale);
        ObjectAnimator pop = ObjectAnimator.ofPropertyValuesHolder(view,
                PropertyValuesHolder.ofFloat(View.SCALE_X, fromScale, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, fromScale, 1f));
        pop.setDuration(520);
        pop.setInterpolator(BOUNCY);
        pop.start();
    }

    /** A view that is brought in from the side, fading in while it springs into place. */
    static void slideFadeIn(View view, float fromXPx) {
        if (!enabled()) return;
        view.setAlpha(0f);
        view.setTranslationX(fromXPx);

        ObjectAnimator fade = ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f);
        fade.setDuration(220);
        fade.start();

        ObjectAnimator slide = ObjectAnimator.ofFloat(view, View.TRANSLATION_X, fromXPx, 0f);
        slide.setDuration(480);
        slide.setInterpolator(SOFT);
        slide.start();
    }

    /** A bar coming up from the edge of the screen with a spring. */
    static void riseFromEdge(View view, float risePx) {
        if (!enabled()) return;
        view.setAlpha(0f);
        view.setTranslationY(risePx);

        ObjectAnimator fade = ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f);
        fade.setDuration(200);
        fade.start();

        ObjectAnimator rise = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, risePx, 0f);
        rise.setDuration(560);
        rise.setInterpolator(SOFT);
        rise.start();
    }

    /** A little hop and a wobble, for a button that has just switched something on. */
    static void wobble(View view) {
        if (!enabled()) return;
        final float density = density(view);

        ObjectAnimator tilt = ObjectAnimator.ofFloat(view, View.ROTATION, -26f, 0f);
        tilt.setDuration(560);
        tilt.setInterpolator(BOUNCY);
        tilt.start();

        ObjectAnimator hop = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, -6f * density, 0f);
        hop.setDuration(560);
        hop.setInterpolator(BOUNCY);
        hop.start();
    }

    /** A push in the direction of the action, that rebounds: skipping to the next or the last track. */
    static void nudge(View view, float fromXPx) {
        if (!enabled()) return;
        ObjectAnimator push = ObjectAnimator.ofFloat(view, View.TRANSLATION_X, fromXPx, 0f);
        push.setDuration(500);
        push.setInterpolator(BOUNCY);
        push.start();
    }

    /** The row shrinks away and the ones under it close the gap, then {@code onEnd} runs. */
    static void collapse(View view, Runnable onEnd) {
        if (!enabled()) {
            onEnd.run();
            return;
        }
        final int startHeight = view.getHeight();
        final ViewGroup.LayoutParams params = view.getLayoutParams();
        if (startHeight <= 0 || params == null) {
            onEnd.run();
            return;
        }

        final float density = density(view);
        ValueAnimator animator = ValueAnimator.ofFloat(1f, 0f);
        animator.setDuration(280);
        animator.setInterpolator(new PathInterpolator(0.3f, 0f, 0.8f, 0.4f));
        animator.addUpdateListener(a -> {
            final float fraction = (float) a.getAnimatedValue();
            view.setAlpha(fraction);
            view.setTranslationX((1f - fraction) * 36f * density);
            params.height = Math.max(1, Math.round(startHeight * fraction));
            view.setLayoutParams(params);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                onEnd.run();
            }
        });
        animator.start();
    }

    /**
     * A menu that unfolds from the button that opened it. It is made of the window of the
     * popup, which is clipped at its edges, so it grows without running past its final size.
     */
    static void animatePopup(ListPopupWindow popup, View anchor) {
        if (!enabled()) return;
        final View list = popup.getListView();
        if (list == null) return;
        final View decor = list.getRootView();
        if (decor == null || decor == anchor.getRootView()) return;

        decor.setAlpha(0f);
        decor.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                decor.getViewTreeObserver().removeOnPreDrawListener(this);
                try {
                    final int[] anchorAt = new int[2];
                    final int[] popupAt = new int[2];
                    anchor.getLocationOnScreen(anchorAt);
                    decor.getLocationOnScreen(popupAt);

                    final float pivotX = Math.max(0f, Math.min(decor.getWidth(),
                            anchorAt[0] + anchor.getWidth() / 2f - popupAt[0]));
                    final boolean below = popupAt[1] >= anchorAt[1];
                    decor.setPivotX(pivotX);
                    decor.setPivotY(below ? 0f : decor.getHeight());
                    decor.setScaleX(0.78f);
                    decor.setScaleY(0.78f);
                    decor.animate().alpha(1f).scaleX(1f).scaleY(1f)
                            .setDuration(300).setInterpolator(ENTER).start();
                } catch (Exception ex) {
                    decor.setAlpha(1f);
                }
                return true;
            }
        });
    }
}
