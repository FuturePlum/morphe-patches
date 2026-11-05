/*
 * Custom addition: the offline mini player on every page of the app.
 *
 * While a downloaded track plays, a bar is laid over the mini player of the app, on the same
 * spot. A button on the bar goes back to the mini player of the app, and a small round chip
 * brings the offline bar back. Nothing of the app is replaced: the bar is a view added on top
 * of the main screen, so turning the setting off gives the stock screen back.
 */

package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.File;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseActivityHook;

@SuppressWarnings("unused")
public final class OfflineMiniBar
        implements OfflinePlaybackService.PlaybackListener, Application.ActivityLifecycleCallbacks {

    private static final String MAIN_ACTIVITY_NAME = "com.google.android.apps.youtube.music.activities.MusicActivity";
    private static final String DOWNLOADS_ACTIVITY_NAME = "com.google.android.gms.common.api.GoogleApiActivity";

    /** Views of the app looked up by name: the play button of its mini player and its tab bar. */
    private static final String MINI_PLAY_BUTTON_ID = "mini_player_play_pause_replay_button";
    private static final String[] TAB_BAR_IDS = {"pivot_bar", "bottom_bar"};

    private static final int BAR_FALLBACK_HEIGHT_DP = 64;
    /** The mini player of the app is lower than this; anything taller holds more than the bar. */
    private static final int BAR_MAX_HEIGHT_DP = 100;
    private static final int BUTTON_DP = 44;
    private static final int ARTWORK_DP = 44;
    private static final int ARTWORK_PIXELS = 144;
    private static final int CHIP_GAP_DP = 8;
    private static final int CHIP_END_MARGIN_DP = 12;
    private static final long NATIVE_TRACK_CHECK_DELAY_MILLISECONDS = 1200;
    private static final long OPEN_DEBOUNCE_MILLISECONDS = 800;
    /** A swipe down at least this long, and mostly vertical, moves the list to the other player. */
    private static final int SWIPE_DISTANCE_DP = 56;

    private static OfflineMiniBar instance;

    /** True while the offline bar is the one shown, false while the app's own mini player is. */
    private static volatile boolean offlineMode = true;

    // region Install

    /**
     * Injection point (the application start). Starts watching the screens of the app.
     */
    public static synchronized void install() {
        if (instance != null) return;
        try {
            Context context = Utils.getContext();
            if (context == null) return;

            OfflineMiniBar bar = new OfflineMiniBar();
            ((Application) context.getApplicationContext()).registerActivityLifecycleCallbacks(bar);
            VideoInformation.addVideoIdListener(videoId -> onNativeVideoId());
            instance = bar;
        } catch (Exception ex) {
            Logger.printException(() -> "OfflineMiniBar install failure", ex);
        }
    }

    /**
     * The app's own player started a track. Once the offline player has let go of the audio,
     * the app's mini player is the one to show.
     */
    private static void onNativeVideoId() {
        Utils.runOnMainThreadDelayed(() -> {
            if (!OfflinePlaybackService.isPlayingNow() && offlineMode) {
                offlineMode = false;
                OfflineMiniBar bar = instance;
                if (bar != null) bar.refresh();
            }
        }, NATIVE_TRACK_CHECK_DELAY_MILLISECONDS);
    }

    // endregion

    // region Views

    @Nullable
    private Activity activity;
    @Nullable
    private FrameLayout content;
    @Nullable
    private ViewTreeObserver.OnPreDrawListener preDrawListener;

    private LinearLayout bar;
    private ProgressBar progress;
    private ImageView artwork;
    private TextView titleView;
    private TextView subtitleView;
    private ImageButton playButton;
    private ImageButton nextButton;
    private TextView toOnlineButton;
    private TextView chip;

    private int[] tabBarIds = new int[0];
    private int miniPlayButtonId;

    // Playback state, as last reported.
    private String path = "";
    private String title = "";
    private boolean playing;
    private boolean showingPause;
    private String artist = "";

    // What is laid out right now, so a frame that changes nothing does nothing.
    private int shownHeight = -1;
    private float shownTranslation = Float.NaN;
    private float shownChipTranslation = Float.NaN;
    private long lastOpenTime;

    private void attach(Activity target) {
        if (activity == target && bar != null && bar.isAttachedToWindow()) return;
        detach();

        FrameLayout root = target.findViewById(android.R.id.content);
        if (root == null) return;

        activity = target;
        content = root;
        miniPlayButtonId = ResourceUtils.getIdentifier(ResourceType.ID, MINI_PLAY_BUTTON_ID);
        tabBarIds = new int[TAB_BAR_IDS.length];
        for (int i = 0; i < TAB_BAR_IDS.length; i++) {
            tabBarIds[i] = ResourceUtils.getIdentifier(ResourceType.ID, TAB_BAR_IDS[i]);
        }

        buildBar(target);
        buildChip(target);
        root.addView(bar, new FrameLayout.LayoutParams(-1, dp(BAR_FALLBACK_HEIGHT_DP), Gravity.BOTTOM));
        FrameLayout.LayoutParams chipParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        chipParams.setMarginEnd(dp(CHIP_END_MARGIN_DP));
        root.addView(chip, chipParams);
        bar.setVisibility(View.GONE);
        chip.setVisibility(View.GONE);

        // The mini player of the app slides and fades by itself, so the bar follows it on
        // every frame. A frame that changes nothing costs only a few lookups.
        preDrawListener = () -> {
            refresh();
            return true;
        };
        root.getViewTreeObserver().addOnPreDrawListener(preDrawListener);

        OfflinePlaybackService.addListener(this);
    }

    private void detach() {
        OfflinePlaybackService.removeListener(this);
        if (content != null) {
            if (preDrawListener != null && content.getViewTreeObserver().isAlive()) {
                content.getViewTreeObserver().removeOnPreDrawListener(preDrawListener);
            }
            if (bar != null) content.removeView(bar);
            if (chip != null) content.removeView(chip);
        }
        preDrawListener = null;
        content = null;
        activity = null;
        bar = null;
        chip = null;
        shownHeight = -1;
        shownTranslation = Float.NaN;
        shownChipTranslation = Float.NaN;
    }

    private void buildBar(Context context) {
        final int foreground = DownloadsUi.foreground();

        bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackground(DownloadsUi.fill(tintFor(null), 0));
        bar.setElevation(dp(24));
        // The bar takes the touches, so none of them reaches the mini player underneath.
        bar.setClickable(true);
        bar.setOnClickListener(v -> openDownloads());
        bar.setOnTouchListener(swipeDown(this::carryListOnline));

        progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressDrawable(DownloadsUi.flatProgress(DownloadsUi.withAlpha(foreground, 50), foreground));
        progress.setMax(1);
        bar.addView(progress, new LinearLayout.LayoutParams(-1, dp(2)));

        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), 0, dp(4), 0);
        bar.addView(row, new LinearLayout.LayoutParams(-1, 0, 1f));

        artwork = DownloadsUi.artwork(context, null, dp(4));
        row.addView(artwork, new LinearLayout.LayoutParams(dp(ARTWORK_DP), dp(ARTWORK_DP)));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12), 0, dp(8), 0);
        titleView = DownloadsUi.text(context, "", 14, foreground);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subtitleView = DownloadsUi.text(context, "", 12, DownloadsUi.secondary());
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(titleView, new LinearLayout.LayoutParams(-1, -2));
        texts.addView(subtitleView, new LinearLayout.LayoutParams(-1, -2));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        playButton = DownloadsUi.icon(context, "yt_fill_experimental_play_vd_theme_24",
                str("morphe_music_downloads_play_pause"), foreground);
        playButton.setOnClickListener(v -> OfflinePlaybackService.toggle(v.getContext()));
        row.addView(playButton, new LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)));

        nextButton = DownloadsUi.icon(context, "yt_fill_experimental_skip_next_vd_theme_24",
                str("morphe_music_downloads_next"), foreground);
        nextButton.setOnClickListener(v -> OfflinePlaybackService.skipNext(v.getContext()));
        row.addView(nextButton, new LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)));

        toOnlineButton = DownloadsUi.text(context, str("morphe_music_offline_mini_bar_to_online"), 12, foreground);
        toOnlineButton.setGravity(Gravity.CENTER);
        toOnlineButton.setPadding(dp(10), dp(6), dp(10), dp(6));
        toOnlineButton.setBackground(DownloadsUi.rippleCard(
                DownloadsUi.withAlpha(foreground, 26), DownloadsUi.withAlpha(foreground, 70), dp(1), dp(16)));
        toOnlineButton.setOnClickListener(v -> switchToOnline());
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(-2, -2);
        switchParams.setMargins(dp(2), 0, dp(8), 0);
        row.addView(toOnlineButton, switchParams);
    }

    private void buildChip(Context context) {
        final int foreground = DownloadsUi.foreground();
        chip = DownloadsUi.text(context, str("morphe_music_offline_mini_bar_to_offline"), 13, foreground);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(14), dp(8), dp(14), dp(8));
        chip.setBackground(DownloadsUi.fill(DownloadsUi.mix(DownloadsUi.background(), foreground, 0.2f), dp(20)));
        chip.setElevation(dp(24));
        chip.setOnClickListener(v -> switchToOffline());
        chip.setOnTouchListener(swipeDown(this::carryListOffline));
    }

    // endregion

    // region Layout

    private int dp(int value) {
        return DownloadsUi.dp(activity != null ? activity : Utils.getContext(), value);
    }

    private void refresh() {
        try {
            if (activity == null || content == null || bar == null || chip == null) return;

            if (!Settings.OFFLINE_MINI_BAR.get() || title.isEmpty()) {
                setShown(false, false);
                return;
            }

            final int contentHeight = content.getHeight();
            if (content.getWidth() <= 0 || contentHeight <= 0) return;

            int barHeight;
            int top;
            Rect nativeMini = nativeMiniBounds();
            if (nativeMini != null) {
                // Right on the mini player of the app, with the same size.
                barHeight = nativeMini.height();
                top = nativeMini.top;
            } else {
                Rect tabs = tabBarBounds();
                if (tabs == null) {
                    // A full page of the app, which has neither a tab bar nor a mini player.
                    setShown(false, false);
                    return;
                }
                barHeight = dp(BAR_FALLBACK_HEIGHT_DP);
                top = tabs.top - barHeight;
            }

            setShown(offlineMode, !offlineMode);
            if (offlineMode) {
                if (barHeight != shownHeight) {
                    shownHeight = barHeight;
                    ViewGroup.LayoutParams params = bar.getLayoutParams();
                    params.height = barHeight;
                    bar.setLayoutParams(params);
                }
                // Gravity puts the bar at the very bottom, and the translation lifts it.
                final float translation = -(contentHeight - (top + barHeight));
                if (translation != shownTranslation) {
                    shownTranslation = translation;
                    bar.setTranslationY(translation);
                }
            } else {
                final float chipTranslation = -(contentHeight - top + dp(CHIP_GAP_DP));
                if (chipTranslation != shownChipTranslation) {
                    shownChipTranslation = chipTranslation;
                    chip.setTranslationY(chipTranslation);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "OfflineMiniBar refresh failure", ex);
        }
    }

    private void setShown(boolean barShown, boolean chipShown) {
        final int barVisibility = barShown ? View.VISIBLE : View.GONE;
        final int chipVisibility = chipShown ? View.VISIBLE : View.GONE;
        if (bar.getVisibility() != barVisibility) bar.setVisibility(barVisibility);
        if (chip.getVisibility() != chipVisibility) chip.setVisibility(chipVisibility);
    }

    /** @return Where the app's own mini player is on this page, or null when it is not showing. */
    @Nullable
    private Rect nativeMiniBounds() {
        if (miniPlayButtonId == 0 || content == null) return null;

        View button = content.findViewById(miniPlayButtonId);
        if (button == null || !button.isShown() || button.getWidth() <= 0) return null;
        // It fades out while the full player of the app opens over it.
        if (effectiveAlpha(button) < 0.5f) return null;

        // The largest parent that is still as low as a bar is the mini player itself.
        View candidate = null;
        View view = button;
        for (int i = 0; i < 6; i++) {
            ViewParent parent = view.getParent();
            if (!(parent instanceof View) || parent == content) break;
            view = (View) parent;
            if (view.getHeight() > dp(BAR_MAX_HEIGHT_DP)) break;
            candidate = view;
        }
        if (candidate == null) return null;

        Rect bounds = boundsInContent(candidate);
        return bounds.height() >= dp(40) ? bounds : null;
    }

    @Nullable
    private Rect tabBarBounds() {
        if (content == null) return null;
        for (int id : tabBarIds) {
            if (id == 0) continue;
            View tabs = content.findViewById(id);
            if (tabs == null || !tabs.isShown() || tabs.getHeight() <= 0 || effectiveAlpha(tabs) < 0.5f) continue;

            Rect bounds = boundsInContent(tabs);
            if (bounds.top < content.getHeight()) return bounds;
        }
        return null;
    }

    private Rect boundsInContent(View view) {
        int[] origin = new int[2];
        int[] position = new int[2];
        content.getLocationInWindow(origin);
        view.getLocationInWindow(position);
        final int left = position[0] - origin[0];
        final int top = position[1] - origin[1];
        return new Rect(left, top, left + view.getWidth(), top + view.getHeight());
    }

    private float effectiveAlpha(View view) {
        float alpha = 1f;
        ViewParent parent;
        View current = view;
        for (int i = 0; i < 25 && current != null && current != content; i++) {
            alpha *= current.getAlpha();
            parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return alpha;
    }

    // endregion

    // region Actions

    private void switchToOnline() {
        offlineMode = false;
        // Nothing may play behind the mini player of the app.
        if (OfflinePlaybackService.isPlayingNow() && activity != null) {
            OfflinePlaybackService.toggle(activity);
        }
        refresh();
    }

    /**
     * Swipe down on the offline bar: the list goes to the app's player, with the track and the
     * spot reached. The tap on "Online" keeps what the app's player already had instead.
     */
    private void carryListOnline() {
        if (activity == null) return;
        PlayerBridge.toOnline(activity);
        offlineMode = false;
        refresh();
    }

    /**
     * Swipe down on the chip: the list of the app's player goes to the offline player, which
     * starts at the same spot. The offline bar shows again by itself once it plays.
     */
    private void carryListOffline() {
        if (activity == null) return;
        PlayerBridge.toOffline(activity);
    }

    private View.OnTouchListener swipeDown(Runnable action) {
        return new View.OnTouchListener() {
            private float downX;
            private float downY;
            private boolean tracking;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                final int type = event.getActionMasked();
                if (type == MotionEvent.ACTION_DOWN) {
                    tracking = true;
                    downX = event.getRawX();
                    downY = event.getRawY();
                } else if (type == MotionEvent.ACTION_CANCEL) {
                    tracking = false;
                } else if (type == MotionEvent.ACTION_UP && tracking) {
                    tracking = false;
                    final float down = event.getRawY() - downY;
                    final float across = Math.abs(event.getRawX() - downX);
                    if (down > dp(SWIPE_DISTANCE_DP) && down > across * 1.5f) {
                        // The touch is used up here, so it must not count as a tap.
                        view.setPressed(false);
                        action.run();
                        return true;
                    }
                }
                return false;
            }
        };
    }

    private void switchToOffline() {
        offlineMode = true;
        refresh();
    }

    private void openDownloads() {
        Activity target = activity;
        if (target == null) return;

        final long now = System.currentTimeMillis();
        if (now - lastOpenTime < OPEN_DEBOUNCE_MILLISECONDS) return;
        lastOpenTime = now;

        try {
            Intent intent = new Intent();
            intent.setClassName(target, DOWNLOADS_ACTIVITY_NAME);
            intent.setPackage(target.getPackageName());
            intent.setData(Uri.parse(BaseActivityHook.MORPHE_DOWNLOADS_INTENT));
            target.startActivity(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "OfflineMiniBar openDownloads failure", ex);
        }
    }

    // endregion

    // region Playback

    @Override
    public void onPlaybackChanged(String newPath, String newTitle, boolean isPlaying, int position, int duration) {
        Utils.runOnMainThread(() -> {
            if (bar == null) return;

            // Starting a downloaded track puts its bar forward.
            if (isPlaying && !playing) offlineMode = true;
            playing = isPlaying;
            title = newTitle;

            if (!newPath.equals(path)) {
                path = newPath;
                artist = "";
                applyTrack(newPath, newTitle);
            }

            progress.setMax(Math.max(1, duration));
            progress.setProgress(position);
            if (showingPause != isPlaying || playButton.getDrawable() == null) {
                showingPause = isPlaying;
                DownloadsUi.setIcon(playButton, isPlaying
                                ? "yt_fill_experimental_pause_vd_theme_24"
                                : "yt_fill_experimental_play_vd_theme_24",
                        DownloadsUi.foreground());
            }
            refresh();
        });
    }

    private void applyTrack(String trackPath, String trackTitle) {
        titleView.setText(trackTitle);
        subtitleView.setText(str("morphe_music_offline_mini_bar_label"));
        bar.setBackground(DownloadsUi.fill(tintFor(null), 0));
        if (trackPath.isEmpty()) return;

        // The cover and the artist are read from the disk, away from the main thread.
        Utils.submitOnBackgroundThread(() -> {
            OfflineTrack track = OfflineTrack.load(new File(trackPath));
            if (track == null) return null;
            final Bitmap cover = track.artwork(ARTWORK_PIXELS);
            final String trackArtist = track.displayArtist();
            Utils.runOnMainThread(() -> {
                // Another track may have started while this one was read.
                if (bar == null || !trackPath.equals(path)) return;
                artist = trackArtist == null ? "" : trackArtist;
                subtitleView.setText(artist.isEmpty()
                        ? str("morphe_music_offline_mini_bar_label")
                        : str("morphe_music_offline_mini_bar_label") + " · " + artist);
                if (cover != null) {
                    DownloadsUi.setArtwork(bar.getContext(), artwork, cover);
                    bar.setBackground(DownloadsUi.fill(tintFor(cover), 0));
                }
            });
            return null;
        });
    }

    /** Like the bar of the downloads page: a dark tone of the cover, or a lift of the page. */
    private static int tintFor(@Nullable Bitmap cover) {
        return cover == null
                ? DownloadsUi.mix(DownloadsUi.background(), DownloadsUi.foreground(), 0.09f)
                : DownloadsUi.mix(DownloadsUi.dominantColor(cover), Color.BLACK, 0.25f);
    }

    // endregion

    // region Lifecycle

    private static boolean isMainActivity(Activity activity) {
        return MAIN_ACTIVITY_NAME.equals(activity.getClass().getName());
    }

    @Override
    public void onActivityResumed(Activity target) {
        try {
            if (isMainActivity(target)) attach(target);
        } catch (Exception ex) {
            Logger.printException(() -> "OfflineMiniBar attach failure", ex);
        }
    }

    @Override
    public void onActivityDestroyed(Activity target) {
        try {
            if (target == activity) detach();
        } catch (Exception ex) {
            Logger.printException(() -> "OfflineMiniBar detach failure", ex);
        }
    }

    @Override public void onActivityCreated(Activity target, @Nullable Bundle state) { }
    @Override public void onActivityStarted(Activity target) { }
    @Override public void onActivityPaused(Activity target) { }
    @Override public void onActivityStopped(Activity target) { }
    @Override public void onActivitySaveInstanceState(Activity target, Bundle state) { }

    // endregion
}
