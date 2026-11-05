/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.Locale;

import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;

/** Small drawing helpers shared by the downloads page and the full screen player. */
final class DownloadsUi {

    static final int FAVORITE_COLOR = Color.parseColor("#FF4E6A");

    /** Dimming the foreground gives a readable secondary on any background. */
    private static final float SECONDARY_DIM = 0.72f;

    private DownloadsUi() {
    }

    static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + .5f);
    }

    static int foreground() {
        return ThemeUtils.getAppForegroundColor();
    }

    static int background() {
        return ThemeUtils.getAppBackgroundColor();
    }

    static int secondary() {
        return Utils.adjustColorBrightness(foreground(), SECONDARY_DIM);
    }

    /** The color with another opacity, from 0 to 255. */
    static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    static int rippleColor() {
        return withAlpha(foreground(), 60);
    }

    /** @param amount Share of {@code over} in the result, from 0 to 1. */
    static int mix(int base, int over, float amount) {
        return Color.rgb(
                Math.round(Color.red(base) * (1 - amount) + Color.red(over) * amount),
                Math.round(Color.green(base) * (1 - amount) + Color.green(over) * amount),
                Math.round(Color.blue(base) * (1 - amount) + Color.blue(over) * amount));
    }

    static TextView text(Context context, String value, int sp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    static GradientDrawable fill(int color, float radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusPx);
        return drawable;
    }

    static GradientDrawable oval(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    /** A round ripple, with an optional round fill under it. */
    static Drawable circleRipple(@Nullable Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor()),
                content, new ShapeDrawable(new OvalShape()));
    }

    /** An icon button using a drawable of the app, which carries no color of its own. */
    static ImageButton icon(Context context, String drawable, String description, int tint) {
        ImageButton button = new ImageButton(context);
        setIcon(button, drawable, tint);
        button.setContentDescription(description);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10));
        button.setBackground(circleRipple(null));
        DownloadsMotion.pressable(button, 0.82f);
        return button;
    }

    static void setIcon(ImageView button, String drawable, int tint) {
        button.setImageDrawable(ResourceUtils.getDrawable(drawable));
        button.setColorFilter(tint);
    }

    /** A button that is lit with a soft round fill while its option is on. */
    static void setToggled(ImageButton button, boolean toggled) {
        button.setBackground(circleRipple(toggled ? oval(withAlpha(foreground(), 36)) : null));
    }

    static void roundCorners(View view, float radiusPx) {
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View outlined, Outline outline) {
                outline.setRoundRect(0, 0, outlined.getWidth(), outlined.getHeight(), radiusPx);
            }
        });
        view.setClipToOutline(true);
    }

    static void makeRound(View view) {
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View outlined, Outline outline) {
                outline.setOval(0, 0, outlined.getWidth(), outlined.getHeight());
            }
        });
        view.setClipToOutline(true);
    }

    /** A cover, or a placeholder holding the play icon of the app when the track has none. */
    static ImageView artwork(Context context, @Nullable Bitmap bitmap, float cornerPx) {
        ImageView view = new ImageView(context);
        view.setBackgroundColor(Utils.adjustColorBrightness(foreground(), 0.2f));
        roundCorners(view, cornerPx);
        setArtwork(context, view, bitmap);
        return view;
    }

    static void setArtwork(Context context, ImageView view, @Nullable Bitmap bitmap) {
        if (bitmap != null) {
            view.clearColorFilter();
            view.setPadding(0, 0, 0, 0);
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
            view.setImageBitmap(bitmap);
        } else {
            view.setScaleType(ImageView.ScaleType.CENTER);
            view.setImageDrawable(
                    ResourceUtils.getDrawable("yt_fill_experimental_play_circle_vd_theme_24"));
            view.setColorFilter(foreground());
            view.setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16));
        }
    }

    static String clock(int milliseconds) {
        final int seconds = Math.max(0, milliseconds / 1000);
        if (seconds < 3600) {
            return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        }
        return String.format(Locale.ROOT, "%d:%02d:%02d",
                seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    /**
     * The tone the player is washed in, taken from the cover like the stock player does.
     * Kept dark enough for the white controls to stay readable.
     */
    static int dominantColor(@Nullable Bitmap bitmap) {
        if (bitmap == null || bitmap.getWidth() == 0 || bitmap.getHeight() == 0) {
            return mix(background(), foreground(), 0.12f);
        }
        final int side = 12;
        Bitmap small = Bitmap.createScaledBitmap(bitmap, side, side, true);

        double red = 0;
        double green = 0;
        double blue = 0;
        double weightSum = 0;
        float[] hsv = new float[3];
        for (int x = 0; x < side; x++) {
            for (int y = 0; y < side; y++) {
                final int pixel = small.getPixel(x, y);
                Color.colorToHSV(pixel, hsv);
                // Vivid pixels speak for the cover more than grey ones do.
                final double weight = 0.15 + hsv[1] * hsv[2];
                red += Color.red(pixel) * weight;
                green += Color.green(pixel) * weight;
                blue += Color.blue(pixel) * weight;
                weightSum += weight;
            }
        }
        if (weightSum <= 0) return mix(background(), foreground(), 0.12f);

        final int average = Color.rgb((int) (red / weightSum),
                (int) (green / weightSum), (int) (blue / weightSum));
        Color.colorToHSV(average, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.min(0.5f, Math.max(0.22f, hsv[2] * 0.7f));
        return Color.HSVToColor(hsv);
    }

    /**
     * A thin bar that stays easy to hit.
     *
     * @param alwaysThumb Whether the round handle is always drawn, or only while dragging.
     */
    static SeekBar seekBar(Context context, int touchDp, int heightDp, boolean alwaysThumb,
                           SeekBar.OnSeekBarChangeListener listener) {
        SeekBar bar = new SeekBar(context);
        bar.setProgressDrawable(seekDrawable(context, touchDp, heightDp));
        bar.setBackground(null);
        bar.setSplitTrack(false);
        if (alwaysThumb) showThumb(context, bar, true);
        else showThumb(context, bar, false);
        bar.setOnSeekBarChangeListener(listener);
        return bar;
    }

    static void showThumb(Context context, SeekBar bar, boolean visible) {
        if (!visible) {
            bar.setThumb(null);
            bar.setThumbOffset(0);
            return;
        }
        final int size = dp(context, 12);
        ShapeDrawable thumb = new ShapeDrawable(new OvalShape());
        thumb.setIntrinsicWidth(size);
        thumb.setIntrinsicHeight(size);
        thumb.getPaint().setColor(foreground());
        bar.setThumb(thumb);
        bar.setThumbOffset(size / 2);
    }

    /**
     * The thickness comes from the drawable because {@code setMinHeight} and
     * {@code setMaxHeight} are only available from API 29.
     */
    private static Drawable seekDrawable(Context context, int touchDp, int heightDp) {
        final float radius = dp(context, heightDp);
        Drawable track = fill(withAlpha(foreground(), 70), radius);
        Drawable played = fill(foreground(), radius);

        LayerDrawable layers = new LayerDrawable(new Drawable[]{
                track, new ClipDrawable(played, Gravity.START, ClipDrawable.HORIZONTAL)});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);

        final int inset = (dp(context, touchDp) - dp(context, heightDp)) / 2;
        layers.setLayerInset(0, 0, inset, 0, inset);
        layers.setLayerInset(1, 0, inset, 0, inset);
        return layers;
    }

    /** A flat progress drawable for a bar that is only looked at, such as the mini player's. */
    static Drawable flatProgress(int trackColor, int playedColor) {
        LayerDrawable layers = new LayerDrawable(new Drawable[]{
                new ColorDrawable(trackColor),
                new ClipDrawable(new ColorDrawable(playedColor), Gravity.START, ClipDrawable.HORIZONTAL)});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        return layers;
    }

    /** A rounded fill, with a ripple that is cut to the same shape. */
    static Drawable rippleRounded(int color, float radiusPx) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor()),
                fill(color, radiusPx), fill(Color.WHITE, radiusPx));
    }

    /** A fill with a thin outline, for a row or card that has to stand out from the page. */
    static Drawable rippleCard(int fillColor, int strokeColor, int strokePx, float radiusPx) {
        GradientDrawable shape = fill(fillColor, radiusPx);
        shape.setStroke(strokePx, strokeColor);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor()),
                shape, fill(Color.WHITE, radiusPx));
    }

    /** A thin progress bar with round ends, for a bar that is only looked at. */
    static Drawable roundedProgress(int trackColor, int playedColor, float radiusPx) {
        LayerDrawable layers = new LayerDrawable(new Drawable[]{
                fill(trackColor, radiusPx),
                new ClipDrawable(fill(playedColor, radiusPx), Gravity.START, ClipDrawable.HORIZONTAL)});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        return layers;
    }

    /** A thin line that separates two parts of a screen without shouting. */
    static View hairline(Context context) {
        View line = new View(context);
        line.setBackgroundColor(withAlpha(foreground(), 28));
        return line;
    }

    /** A view that is as wide and as tall as the smaller of the two sides it is given. */
    static final class SquareLayout extends FrameLayout {
        SquareLayout(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = MeasureSpec.getSize(heightMeasureSpec);
            if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) width = Integer.MAX_VALUE;
            if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) height = Integer.MAX_VALUE;

            int side = Math.min(width, height);
            if (side == Integer.MAX_VALUE) side = 0;

            final int childSpec = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY);
            measureChildren(childSpec, childSpec);
            setMeasuredDimension(side, side);
        }
    }
}
