/*
 * Small views of the downloads page: the playing indicator of a row, the cover mosaic.
 */

package app.morphe.extension.music.patches.downloads;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.List;

final class DownloadsWidgets {

    private DownloadsWidgets() {
    }

    /** A round outline with nothing inside, for the secondary round buttons. */
    static GradientDrawable ring(int color, int strokePx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(Color.TRANSPARENT);
        drawable.setStroke(strokePx, color);
        return drawable;
    }

    /**
     * Four covers in a grid, or a single one when there are fewer than four.
     *
     * @param covers At least one cover.
     */
    static View mosaic(Context context, List<Bitmap> covers) {
        if (covers.size() < 4) return cover(context, covers.get(0));

        LinearLayout grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int row = 0; row < 2; row++) {
            LinearLayout line = new LinearLayout(context);
            for (int column = 0; column < 2; column++) {
                line.addView(cover(context, covers.get(row * 2 + column)),
                        new LinearLayout.LayoutParams(0, -1, 1));
            }
            grid.addView(line, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        return grid;
    }

    private static ImageView cover(Context context, Bitmap bitmap) {
        ImageView view = new ImageView(context);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setImageBitmap(bitmap);
        return view;
    }

    /**
     * The three bars the app draws over the cover of the track that is playing.
     * They dance while the music plays and rest while it is paused.
     */
    static final class EqualizerView extends View {
        private static final int BARS = 3;
        private static final long LOOP_MILLISECONDS = 1200;

        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint scrimPaint = new Paint();
        private final RectF bar = new RectF();
        /** Heights of the bars while paused, and phase shifts between them while playing. */
        private final float[] resting = {0.55f, 0.95f, 0.7f};
        private final float[] shifts = {0f, 0.37f, 0.73f};

        private ValueAnimator animator;
        private float phase;
        private boolean playing;

        EqualizerView(Context context, int color) {
            super(context);
            barPaint.setColor(color);
            scrimPaint.setColor(0x99000000);
        }

        void setPlaying(boolean value) {
            if (value == playing) return;
            playing = value;
            if (playing) start();
            else stop();
            invalidate();
        }

        private void start() {
            if (animator != null || !isAttachedToWindow()) return;

            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(LOOP_MILLISECONDS);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        private void stop() {
            if (animator == null) return;
            animator.cancel();
            animator = null;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (playing) start();
        }

        @Override
        protected void onDetachedFromWindow() {
            stop();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawRect(0, 0, getWidth(), getHeight(), scrimPaint);

            final float side = Math.min(getWidth(), getHeight());
            final float barWidth = side * 0.1f;
            final float gap = side * 0.07f;
            final float maxHeight = side * 0.4f;
            final float totalWidth = BARS * barWidth + (BARS - 1) * gap;

            float left = (getWidth() - totalWidth) / 2f;
            final float bottom = (getHeight() + maxHeight) / 2f;
            for (int i = 0; i < BARS; i++) {
                // Two swings per loop, so the end of the loop meets its start.
                final float level = playing
                        ? 0.3f + 0.7f * (float) Math.abs(Math.sin(Math.PI * (phase * 2f + shifts[i])))
                        : resting[i];
                final float height = Math.max(barWidth, maxHeight * level);
                bar.set(left, bottom - height, left + barWidth, bottom);
                canvas.drawRoundRect(bar, barWidth / 2f, barWidth / 2f, barPaint);
                left += barWidth + gap;
            }
        }
    }
}
