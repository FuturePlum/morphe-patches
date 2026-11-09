/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.music.patches.DownloadsPatch;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;

/**
 * Mini player of the offline music, shown on every page of the app while an offline track is
 * loaded, just above the navigation bar. It has a play/pause button, previous and next.
 * A tap on it opens the big offline player.
 * <p>
 * When online music starts playing, the offline player stops and this bar goes away, so the
 * app is back on its own player. The offline queue is kept.
 */
public final class OfflineMiniPlayerOverlay {

    /** The screen of the downloads and of the settings. It has its own mini player. */
    private static final String SCREEN_HOST_SUFFIX = "GoogleApiActivity";
    /** After the offline music starts, the online player may still send a last tick. */
    private static final long ONLINE_GRACE_MILLISECONDS = 3000;
    private static final long PLACEMENT_MILLISECONDS = 350;
    private static final long NAV_SCAN_MILLISECONDS = 800;
    private static final int ARTWORK_PIXELS = 256;
    private static final float SECONDARY_DIM = 0.72f;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final ExecutorService loader = Executors.newSingleThreadExecutor();

    private static boolean installed;
    @Nullable
    private static Bar bar;

    // What the offline service plays right now.
    private static String playingPath = "";
    private static String playingTitle = "";
    private static boolean playing;
    private static int position;
    private static int duration;
    private static long activeSince;

    // Details of the track of the bar, kept so a new page shows them at once.
    private static String cachedPath = "";
    private static String cachedTitle = "";
    private static String cachedArtist = "";
    @Nullable
    private static Bitmap cachedArtwork;

    /** Track whose bar was swiped away, so the bar stays hidden until the player stops. */
    private static String dismissedPath = "";

    private static long lastOnlineTime = -1;

    private OfflineMiniPlayerOverlay() {
    }

    /** Injection point, at the start of the app. */
    public static void install() {
        try {
            if (installed) return;
            Context context = Utils.getContext();
            if (context == null) return;
            Application application = (Application) context.getApplicationContext();
            installed = true;

            OfflinePlaybackService.addListener(OfflineMiniPlayerOverlay::onPlayback);
            OfflineQueue.addListener(() -> {
                Bar current = bar;
                if (current != null) current.updateButtons();
            });
            application.registerActivityLifecycleCallbacks(new Lifecycle());
        } catch (Exception ex) {
            Logger.printException(() -> "Could not install the offline mini player", ex);
        }
    }

    /**
     * Injection point, called about every second while the online player plays.
     * Moving time means online music is playing, so the offline player gives way to it.
     */
    public static void onOnlineProgress(long timeMilliseconds) {
        final long previous = lastOnlineTime;
        lastOnlineTime = timeMilliseconds;
        if (previous < 0 || previous == timeMilliseconds) return;
        if (playingPath.isEmpty()) return;
        if (SystemClock.elapsedRealtime() - activeSince < ONLINE_GRACE_MILLISECONDS) return;

        try {
            Context context = Utils.getContext();
            if (context == null) return;
            Logger.printDebug(() -> "Online music plays, the offline player stops");
            // The queue is kept: only the player and its notification go away.
            OfflinePlaybackService.stop(context);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not stop the offline player", ex);
        }
    }

    private static void onPlayback(String path, String title, boolean isPlaying,
                                   int newPosition, int newDuration) {
        handler.post(() -> {
            if (path.isEmpty()) dismissedPath = "";
            final boolean pathChanged = !path.equals(playingPath);
            if (pathChanged || (isPlaying && !playing)) activeSince = SystemClock.elapsedRealtime();
            playingPath = path;
            playingTitle = title;
            playing = isPlaying;
            position = newPosition;
            duration = newDuration;

            Bar current = bar;
            if (current != null) current.refresh(pathChanged);
        });
    }

    // ------------------------------------------------------------------ Pages

    private static final class Lifecycle implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(Activity activity) {
            attach(activity);
        }

        @Override public void onActivityPaused(Activity activity) {
            detach(activity);
        }

        @Override public void onActivityDestroyed(Activity activity) {
            detach(activity);
        }

        @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) { }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
    }

    private static void attach(Activity activity) {
        try {
            if (activity.getClass().getName().endsWith(SCREEN_HOST_SUFFIX)) return;
            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof ViewGroup)) return;

            if (bar != null) bar.detach();
            bar = new Bar(activity, (ViewGroup) content);
            bar.attach();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not show the offline mini player", ex);
        }
    }

    private static void detach(Activity activity) {
        Bar current = bar;
        if (current != null && current.activity == activity) {
            current.detach();
            bar = null;
        }
    }

    // -------------------------------------------------------------------- Bar

    private static final class Bar {
        final Activity activity;
        private final ViewGroup content;
        private final LinearLayout root;
        private final ImageView artwork;
        private final TextView title;
        private final TextView artist;
        private final ImageButton previous;
        private final ImageButton play;
        private final ImageButton next;
        private final View fill;
        private final View rest;

        private boolean showingPause;
        private boolean iconsReady;
        private float shownProgress = -1f;
        private int appliedMargin = -1;
        private long lastScan;
        @Nullable
        private WeakReference<View> navRef;

        private final Runnable placement = new Runnable() {
            @Override public void run() {
                place();
                handler.postDelayed(this, PLACEMENT_MILLISECONDS);
            }
        };

        Bar(Activity activity, ViewGroup content) {
            this.activity = activity;
            this.content = content;

            // Swiped sideways or down, the bar closes and the offline player stops.
            root = new SwipeDismissLayout(activity, this::dismiss);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(ThemeUtils.getDialogBackgroundColor());
            root.setElevation(dp(8));
            // Touches stop here, so nothing behind the bar reacts to them.
            root.setClickable(true);
            root.setVisibility(View.GONE);

            View divider = new View(activity);
            divider.setBackgroundColor(ripple());
            root.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));

            LinearLayout line = new LinearLayout(activity);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(dp(16), 0, 0, 0);
            line.setClickable(true);
            line.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple()), null,
                    new ColorDrawable(Color.WHITE)));
            line.setOnClickListener(v -> DownloadsPatch.openDownloadsPlayer());

            artwork = new ImageView(activity);
            artwork.setBackgroundColor(Utils.adjustColorBrightness(foreground(), 0.2f));
            artwork.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(4));
                }
            });
            artwork.setClipToOutline(true);
            showPlaceholder();
            line.addView(artwork, new LinearLayout.LayoutParams(dp(48), dp(48)));

            LinearLayout labels = new LinearLayout(activity);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setGravity(Gravity.CENTER_VERTICAL);
            labels.setPadding(dp(16), 0, dp(4), 0);

            title = new TextView(activity);
            title.setTextSize(14);
            title.setTextColor(foreground());
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            artist = new TextView(activity);
            artist.setTextSize(14);
            artist.setTextColor(Utils.adjustColorBrightness(foreground(), SECONDARY_DIM));
            artist.setSingleLine(true);
            artist.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(title);
            labels.addView(artist);
            line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

            previous = button(OfflineIcons.Icon.PREVIOUS, "morphe_music_downloads_previous");
            previous.setOnClickListener(v -> OfflinePlaybackService.skipPrevious(activity));
            play = button(OfflineIcons.Icon.PLAY, "morphe_music_downloads_play_pause");
            play.setOnClickListener(v -> OfflinePlaybackService.toggle(activity));
            next = button(OfflineIcons.Icon.NEXT, "morphe_music_downloads_next");
            next.setOnClickListener(v -> OfflinePlaybackService.skipNext(activity));
            line.addView(previous, new LinearLayout.LayoutParams(dp(48), dp(48)));
            line.addView(play, new LinearLayout.LayoutParams(dp(48), dp(48)));
            line.addView(next, new LinearLayout.LayoutParams(dp(48), dp(48)));
            root.addView(line, new LinearLayout.LayoutParams(-1, dp(60)));

            // Thin progress line, the seeking is done in the big player.
            LinearLayout progress = new LinearLayout(activity);
            fill = new View(activity);
            fill.setBackgroundColor(foreground());
            rest = new View(activity);
            rest.setBackgroundColor(Utils.adjustColorBrightness(foreground(), 0.3f));
            progress.addView(fill, new LinearLayout.LayoutParams(0, -1, 0f));
            progress.addView(rest, new LinearLayout.LayoutParams(0, -1, 1f));
            root.addView(progress, new LinearLayout.LayoutParams(-1, dp(2)));
        }

        void attach() {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2,
                    Gravity.BOTTOM);
            content.addView(root, params);
            refresh(true);
            handler.postDelayed(placement, PLACEMENT_MILLISECONDS);
        }

        void detach() {
            handler.removeCallbacks(placement);
            content.removeView(root);
        }

        private void dismiss() {
            dismissedPath = playingPath;
            OfflinePlaybackService.stop(activity);
        }

        private boolean hidden() {
            return playingPath.isEmpty() || playingPath.equals(dismissedPath);
        }

        void refresh(boolean pathChanged) {
            if (hidden()) {
                root.setVisibility(View.GONE);
                return;
            }
            if (pathChanged) loadTrack();
            updatePlayIcon();
            updateButtons();
            updateProgress();
            // After the layout pass, so the navigation bar already has its size.
            if (root.getVisibility() != View.VISIBLE) content.post(this::place);
        }

        void updateButtons() {
            setEnabled(next, OfflineQueue.hasNext());
            // Previous restarts the track when nothing comes before it.
            setEnabled(previous, !playingPath.isEmpty());
        }

        private void updatePlayIcon() {
            if (iconsReady && playing == showingPause) return;
            iconsReady = true;
            showingPause = playing;
            play.setImageDrawable(OfflineIcons.get(
                    playing ? OfflineIcons.Icon.PAUSE : OfflineIcons.Icon.PLAY, dp(24), foreground()));
        }

        private void updateProgress() {
            float ratio = duration <= 0 ? 0f : Math.min(1f, Math.max(0f, position / (float) duration));
            // The service reports twice a second, a layout pass is only worth a visible step.
            if (Math.abs(ratio - shownProgress) < 0.002f) return;
            shownProgress = ratio;
            setWeight(fill, ratio);
            setWeight(rest, 1f - ratio);
        }

        private void setWeight(View view, float weight) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) view.getLayoutParams();
            params.weight = weight;
            view.setLayoutParams(params);
        }

        private void loadTrack() {
            final String path = playingPath;
            if (path.equals(cachedPath)) {
                showTrack(cachedTitle, cachedArtist, cachedArtwork);
                return;
            }
            showTrack(playingTitle, "", null);
            loader.execute(() -> {
                try {
                    OfflineTrack track = OfflineTrack.load(new File(path));
                    Bitmap bitmap = track.artworkFile().isFile() ? track.artwork(ARTWORK_PIXELS) : null;
                    handler.post(() -> {
                        cachedPath = path;
                        cachedTitle = track.displayTitle();
                        cachedArtist = track.displayArtist();
                        cachedArtwork = bitmap;
                        if (path.equals(playingPath) && bar == this) {
                            showTrack(cachedTitle, cachedArtist, bitmap);
                        }
                    });
                } catch (Exception ex) {
                    Logger.printException(() -> "Could not read the playing track", ex);
                }
            });
        }

        private void showTrack(String trackTitle, String trackArtist, @Nullable Bitmap bitmap) {
            title.setText(trackTitle);
            artist.setText(trackArtist);
            if (bitmap != null) {
                artwork.setImageBitmap(bitmap);
                artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
            } else {
                showPlaceholder();
            }
        }

        private void showPlaceholder() {
            artwork.setImageDrawable(OfflineIcons.get(OfflineIcons.Icon.NOTE, dp(24),
                    Utils.adjustColorBrightness(foreground(), SECONDARY_DIM)));
            artwork.setScaleType(ImageView.ScaleType.CENTER);
        }

        // -------------------------------------------------------- Placement

        /** Sits right above the navigation bar, and goes away when that bar is hidden. */
        private void place() {
            if (hidden()) {
                root.setVisibility(View.GONE);
                return;
            }
            int margin;
            View nav = findNavigationBar();
            if (nav != null) {
                int[] navLocation = new int[2];
                int[] contentLocation = new int[2];
                nav.getLocationInWindow(navLocation);
                content.getLocationInWindow(contentLocation);
                margin = Math.max(0, contentLocation[1] + content.getHeight() - navLocation[1]);
                // The bar is hidden (the player is open, a full screen page...), or slid away.
                if (!nav.isShown() || nav.getAlpha() < 0.05f || margin < nav.getHeight() / 2) {
                    root.setVisibility(View.GONE);
                    return;
                }
            } else {
                margin = systemBottomInset();
            }

            root.setVisibility(View.VISIBLE);
            if (margin != appliedMargin) {
                appliedMargin = margin;
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) root.getLayoutParams();
                params.bottomMargin = margin;
                root.setLayoutParams(params);
            }
        }

        @SuppressWarnings("deprecation")
        private int systemBottomInset() {
            try {
                WindowInsets insets = content.getRootWindowInsets();
                return insets == null ? 0 : insets.getSystemWindowInsetBottom();
            } catch (Exception ex) {
                return 0;
            }
        }

        @Nullable
        private View findNavigationBar() {
            View cached = navRef == null ? null : navRef.get();
            if (cached != null && cached.isAttachedToWindow()) return cached;

            final long now = SystemClock.elapsedRealtime();
            if (now - lastScan < NAV_SCAN_MILLISECONDS) return null;
            lastScan = now;

            int[] contentLocation = new int[2];
            content.getLocationInWindow(contentLocation);
            View found = scan(content, 0, content.getWidth(),
                    contentLocation[1] + content.getHeight());
            navRef = found == null ? null : new WeakReference<>(found);
            return found;
        }

        /**
         * The navigation bar is the row of tabs stuck to the bottom of the page: wide, not very
         * tall, with several visible children. Nothing relies on the name of a view of the app.
         */
        @Nullable
        private View scan(View view, int depth, int contentWidth, int contentBottom) {
            if (depth > 14 || view == root || !(view instanceof ViewGroup group)) return null;

            if (isNavigationBar(group, contentWidth, contentBottom)) return group;
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                View found = scan(group.getChildAt(i), depth + 1, contentWidth, contentBottom);
                if (found != null) return found;
            }
            return null;
        }

        private boolean isNavigationBar(ViewGroup group, int contentWidth, int contentBottom) {
            final int width = group.getWidth();
            final int height = group.getHeight();
            if (!group.isShown() || width < contentWidth * 0.9f
                    || height < dp(40) || height > dp(130)) {
                return false;
            }
            int visibleChildren = 0;
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                if (group.getChildAt(i).getVisibility() == View.VISIBLE) visibleChildren++;
            }
            if (visibleChildren < 3) return false;

            int[] location = new int[2];
            group.getLocationInWindow(location);
            return Math.abs(location[1] + height - contentBottom) <= dp(4);
        }

        // ---------------------------------------------------------- Helpers

        private ImageButton button(OfflineIcons.Icon icon, String descriptionKey) {
            ImageButton button = new ImageButton(activity);
            button.setImageDrawable(OfflineIcons.get(icon, dp(24), foreground()));
            button.setContentDescription(app.morphe.extension.shared.StringRef.str(descriptionKey));
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple()), null,
                    new ShapeDrawable(new OvalShape())));
            return button;
        }

        private void setEnabled(ImageButton button, boolean enabled) {
            button.setEnabled(enabled);
            button.setAlpha(enabled ? 1f : .35f);
        }

        private int dp(int value) {
            return (int) (value * activity.getResources().getDisplayMetrics().density + .5f);
        }

        private static int foreground() {
            return ThemeUtils.getAppForegroundColor();
        }

        private static int ripple() {
            final int foreground = foreground();
            return Color.argb(60, Color.red(foreground), Color.green(foreground), Color.blue(foreground));
        }
    }
}
