/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.StringRef.str;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.ResourceUtils;

/**
 * The full screen player of the offline playback, modeled on the one of YouTube Music:
 * a cover washed in its own color, the usual controls, and a sheet with the queue.
 * <p>
 * It only draws. Every action goes to {@link OfflinePlaybackService}, which keeps playing
 * whether or not this view is on screen.
 */
@SuppressLint({"ClickableViewAccessibility", "ViewConstructor"})
final class OfflineFullPlayer extends FrameLayout {

    /** What the player needs from the screen that hosts it. */
    interface Host {
        @Nullable
        OfflineTrack findTrack(String path);

        boolean isFavorite(OfflineTrack track);

        void toggleFavorite(OfflineTrack track);

        /** Called when the player opens or closes, so the screen can hide its own toolbar. */
        void onShowingChanged(boolean showing);
    }

    private static final int ARTWORK_PIXELS = 1024;
    private static final int QUEUE_ARTWORK_PIXELS = 128;
    private static final int QUEUE_ROW_DP = 60;
    private static final int SEEK_TOUCH_DP = 28;
    private static final int SEEK_HEIGHT_DP = 4;
    private static final int[] SLEEP_MINUTES = {5, 10, 15, 30, 45, 60};
    private static final long SLIDE_MILLISECONDS = 260;
    private static final long TINT_MILLISECONDS = 450;
    private static final float PAUSED_ARTWORK_SCALE = 0.88f;

    /** Dragging the player down further than this before letting go closes it. */
    private static final int CLOSE_DRAG_DP = 100;

    /** Covers of the rows of the queue, so that redrawing the queue does not read the disk again. */
    private static final LruCache<String, Bitmap> QUEUE_ARTWORK = new LruCache<>(96);

    private final Host host;
    private final GradientDrawable backdrop;
    private int tint;

    private final LinearLayout topBar;
    private final FrameLayout artArea;
    private final DownloadsUi.SquareLayout artSquare;
    private final ImageView artwork;
    private final TextView title;
    private final TextView artist;
    private final ImageButton heart;
    private final SeekBar seek;
    private final TextView elapsed;
    private final TextView total;
    private final ImageButton shuffleButton;
    private final ImageButton previousButton;
    private final ImageButton playButton;
    private final ImageButton nextButton;
    private final ImageButton repeatButton;
    private final LinearLayout queuePill;
    private final LinearLayout sleepPill;
    private final TextView sleepLabel;

    private String boundPath = "";
    @Nullable
    private OfflineTrack boundTrack;
    private boolean userSeeking;
    private boolean showingPause;
    private boolean playIconReady;
    private boolean artworkScaledForPlaying = true;
    private boolean hiding;
    /** The player is being pulled up from the mini player and is not fully open yet. */
    private boolean peeking;
    private final int touchSlop;
    private float dragDownX;
    private float dragDownY;
    private boolean dragCandidate;
    private boolean draggingDown;
    private int lastQueueVersion = -1;
    private String shownSleepLabel = "";

    @Nullable
    private FrameLayout sheetLayer;
    @Nullable
    private LinearLayout sheetPanel;
    private boolean queueSheetOpen;
    private boolean dragging;
    @Nullable
    private ScrollView queueScroll;
    @Nullable
    private LinearLayout queueList;
    @Nullable
    private TextView queueCount;
    private final List<View> queueRows = new ArrayList<>();

    OfflineFullPlayer(Context context, Host host) {
        super(context);
        this.host = host;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        final int fg = DownloadsUi.foreground();
        final int secondary = DownloadsUi.secondary();
        tint = DownloadsUi.dominantColor(null);

        setVisibility(GONE);
        // Swallows touches, so nothing below the player reacts to them.
        setClickable(true);
        setBackgroundColor(DownloadsUi.background());

        backdrop = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{tint, DownloadsUi.background()});
        View backdropView = new View(context);
        backdropView.setBackground(backdrop);
        addView(backdropView, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        addView(content, new FrameLayout.LayoutParams(-1, -1));

        // Top bar: close, and what the screen is.
        topBar = new LinearLayout(context);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8), dp(4), dp(8), 0);
        ImageButton close = DownloadsUi.icon(context, "yt_outline_experimental_chevron_down_vd_theme_24",
                str("morphe_music_downloads_close_player"), fg);
        close.setOnClickListener(v -> hide());
        topBar.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView heading = DownloadsUi.text(context, str("morphe_music_downloads_now_playing"), 14, secondary);
        heading.setGravity(Gravity.CENTER);
        topBar.addView(heading, new LinearLayout.LayoutParams(0, -1, 1));
        topBar.addView(new View(context), new LinearLayout.LayoutParams(dp(48), dp(48)));
        content.addView(topBar, new LinearLayout.LayoutParams(-1, dp(56)));

        // The cover takes whatever height is left and stays square.
        artArea = new FrameLayout(context);
        artArea.setPadding(dp(24), dp(8), dp(24), dp(8));
        artSquare = new DownloadsUi.SquareLayout(context);
        artSquare.setBackgroundColor(Color.argb(60, 0, 0, 0));
        DownloadsUi.roundCorners(artSquare, dp(16));
        artSquare.setElevation(dp(12));
        artwork = new ImageView(context);
        artSquare.addView(artwork, new FrameLayout.LayoutParams(-1, -1));
        artArea.addView(artSquare, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        content.addView(artArea, new LinearLayout.LayoutParams(-1, 0, 1));

        // Title, artist and the favorite heart.
        LinearLayout info = new LinearLayout(context);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(24), dp(8), dp(12), 0);
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        title = DownloadsUi.text(context, "", 22, fg);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        marquee(title);
        artist = DownloadsUi.text(context, "", 16, secondary);
        marquee(artist);
        labels.addView(title, new LinearLayout.LayoutParams(-1, -2));
        labels.addView(artist, new LinearLayout.LayoutParams(-1, -2));
        info.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        heart = DownloadsUi.icon(context, "yt_outline_experimental_heart_vd_theme_24",
                str("morphe_music_downloads_favorite"), fg);
        heart.setStateListAnimator(null);
        heart.setOnClickListener(v -> {
            if (boundTrack == null) return;
            host.toggleFavorite(boundTrack);
            refreshHeart();
            DownloadsMotion.popIn(heart, host.isFavorite(boundTrack) ? 0.35f : 0.75f);
        });
        info.addView(heart, new LinearLayout.LayoutParams(dp(48), dp(48)));
        content.addView(info, new LinearLayout.LayoutParams(-1, -2));

        // Seek bar with the time on both sides.
        seek = DownloadsUi.seekBar(context, SEEK_TOUCH_DP, SEEK_HEIGHT_DP, true,
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onStartTrackingTouch(SeekBar bar) {
                        userSeeking = true;
                    }

                    @Override
                    public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                        if (fromUser) elapsed.setText(DownloadsUi.clock(value));
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar bar) {
                        userSeeking = false;
                        OfflinePlaybackService.seekTo(bar.getProgress());
                    }
                });
        seek.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(-1, dp(SEEK_TOUCH_DP));
        seekParams.topMargin = dp(10);
        seekParams.leftMargin = dp(12);
        seekParams.rightMargin = dp(12);
        content.addView(seek, seekParams);

        LinearLayout times = new LinearLayout(context);
        times.setPadding(dp(24), 0, dp(24), 0);
        elapsed = DownloadsUi.text(context, "0:00", 12, secondary);
        total = DownloadsUi.text(context, "0:00", 12, secondary);
        times.addView(elapsed, new LinearLayout.LayoutParams(0, -2, 1));
        times.addView(total, new LinearLayout.LayoutParams(-2, -2));
        content.addView(times, new LinearLayout.LayoutParams(-1, -2));

        // Shuffle, previous, play, next, repeat.
        LinearLayout controls = new LinearLayout(context);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(12), dp(8), dp(12), dp(4));

        shuffleButton = DownloadsUi.icon(context, "yt_outline_experimental_shuffle_vd_theme_24",
                str("morphe_music_downloads_shuffle"), fg);
        shuffleButton.setOnClickListener(v -> {
            OfflinePlaybackService.setShuffle(!OfflinePlaybackService.isShuffleEnabled());
            updateModes();
        });
        controls.addView(shuffleButton, new LinearLayout.LayoutParams(0, dp(56), 1));

        previousButton = DownloadsUi.icon(context, "yt_fill_experimental_skip_previous_vd_theme_24",
                str("morphe_music_downloads_previous"), fg);
        previousButton.setOnClickListener(v -> {
            OfflinePlaybackService.skipPrevious(getContext());
            DownloadsMotion.nudge(previousButton, -dp(12));
        });
        controls.addView(previousButton, new LinearLayout.LayoutParams(0, dp(56), 1));

        playButton = new ImageButton(context);
        playButton.setContentDescription(str("morphe_music_downloads_play_pause"));
        playButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        playButton.setPadding(dp(22), dp(22), dp(22), dp(22));
        playButton.setBackground(DownloadsUi.circleRipple(DownloadsUi.oval(fg)));
        playButton.setColorFilter(DownloadsUi.background());
        playButton.setOnClickListener(v -> OfflinePlaybackService.toggle(getContext()));
        DownloadsMotion.pressable(playButton, 0.9f);
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(76), dp(76));
        playParams.leftMargin = dp(8);
        playParams.rightMargin = dp(8);
        controls.addView(playButton, playParams);

        nextButton = DownloadsUi.icon(context, "yt_fill_experimental_skip_next_vd_theme_24",
                str("morphe_music_downloads_next"), fg);
        nextButton.setOnClickListener(v -> {
            OfflinePlaybackService.skipNext(getContext());
            DownloadsMotion.nudge(nextButton, dp(12));
        });
        controls.addView(nextButton, new LinearLayout.LayoutParams(0, dp(56), 1));

        repeatButton = DownloadsUi.icon(context, "yt_outline_experimental_repeat_vd_theme_24",
                str("morphe_music_downloads_repeat"), fg);
        repeatButton.setOnClickListener(v -> {
            OfflinePlaybackService.cycleRepeat();
            updateModes();
        });
        controls.addView(repeatButton, new LinearLayout.LayoutParams(0, dp(56), 1));
        content.addView(controls, new LinearLayout.LayoutParams(-1, -2));

        // Bottom: the queue and the sleep timer.
        LinearLayout bottom = new LinearLayout(context);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(dp(16), dp(12), dp(16), dp(20));
        queuePill = pill("yt_outline_experimental_queue_vd_theme_24", str("morphe_music_downloads_up_next"));
        queuePill.setOnClickListener(v -> showQueueSheet());
        DownloadsMotion.pressable(queuePill, 0.92f);
        sleepPill = pill("yt_outline_experimental_sleep_timer_vd_theme_24", str("morphe_music_downloads_sleep_timer"));
        sleepLabel = (TextView) sleepPill.getChildAt(1);
        sleepPill.setOnClickListener(v -> showSleepSheet());
        DownloadsMotion.pressable(sleepPill, 0.92f);
        LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(-2, dp(40));
        pillParams.leftMargin = dp(6);
        pillParams.rightMargin = dp(6);
        bottom.addView(queuePill, pillParams);
        bottom.addView(sleepPill, pillParams);
        content.addView(bottom, new LinearLayout.LayoutParams(-1, -2));

        // Sideways on the cover changes track. Dragging down from anywhere closes the player,
        // which is handled by the player itself, see onInterceptTouchEvent.
        artArea.setOnTouchListener(createSwipeListener());

        updateModes();
    }

    // ------------------------------------------------------------------ showing

    boolean isShowing() {
        return getVisibility() == VISIBLE && !hiding;
    }

    void show() {
        if (isShowing()) return;
        animate().cancel();
        hiding = false;
        setVisibility(VISIBLE);

        final int height = parentHeight();
        setTranslationY(height);
        animate().translationY(0).setDuration(SLIDE_MILLISECONDS + 120)
                .setInterpolator(DownloadsMotion.ENTER).start();
        host.onShowingChanged(true);
    }

    void hide() {
        if (getVisibility() != VISIBLE || hiding) return;
        peeking = false;
        closeSheetNow();
        hiding = true;
        host.onShowingChanged(false);
        animate().translationY(parentHeight()).setDuration(SLIDE_MILLISECONDS + 20)
                .setInterpolator(DownloadsMotion.EXIT)
                .withEndAction(() -> {
                    hiding = false;
                    setVisibility(GONE);
                    setTranslationY(0);
                }).start();
    }

    /**
     * Opening by pulling the mini player up: the player follows the finger.
     *
     * @param pulledPx How far the finger went up since it touched the mini player.
     */
    void dragOpen(float pulledPx) {
        if (hiding) return;
        if (!peeking) {
            if (getVisibility() == VISIBLE) return;
            animate().cancel();
            peeking = true;
            setTranslationY(parentHeight());
            setVisibility(VISIBLE);
        }
        setTranslationY(Math.max(0, parentHeight() - pulledPx));
    }

    /** Lets go of the pull: the player finishes opening, or slides back down. */
    void settleOpen(boolean open) {
        if (!peeking) return;
        peeking = false;
        if (open) {
            animate().translationY(0).setDuration(SLIDE_MILLISECONDS + 80)
                    .setInterpolator(DownloadsMotion.ENTER).start();
            host.onShowingChanged(true);
            return;
        }
        animate().translationY(parentHeight()).setDuration(SLIDE_MILLISECONDS)
                .setInterpolator(DownloadsMotion.ENTER)
                .withEndAction(() -> {
                    setVisibility(GONE);
                    setTranslationY(0);
                }).start();
    }

    // ------------------------------------------------------------------ drag down to close

    private boolean canDragDown(MotionEvent event) {
        return sheetLayer == null && !hiding && getVisibility() == VISIBLE && !isOnSeekBar(event);
    }

    /** A vertical move on the seek bar would otherwise close the player while seeking. */
    private boolean isOnSeekBar(MotionEvent event) {
        int[] at = new int[2];
        seek.getLocationOnScreen(at);
        final float x = event.getRawX();
        final float y = event.getRawY();
        return x >= at[0] && x <= at[0] + seek.getWidth()
                && y >= at[1] - dp(8) && y <= at[1] + seek.getHeight() + dp(8);
    }

    private boolean startsDragDown(MotionEvent event) {
        final float down = event.getRawY() - dragDownY;
        final float side = Math.abs(event.getRawX() - dragDownX);
        return down > touchSlop && down > side * 1.2f;
    }

    /** Takes the touch away from the buttons as soon as it clearly goes down. */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragDownX = event.getRawX();
                dragDownY = event.getRawY();
                draggingDown = false;
                dragCandidate = canDragDown(event);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (dragCandidate && startsDragDown(event)) {
                    draggingDown = true;
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    /** Also reached by touches that no button wanted: the background, the title, the times. */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragDownX = event.getRawX();
                dragDownY = event.getRawY();
                draggingDown = false;
                dragCandidate = canDragDown(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!draggingDown && dragCandidate && startsDragDown(event)) draggingDown = true;
                if (draggingDown) setTranslationY(Math.max(0, event.getRawY() - dragDownY));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                final boolean was = draggingDown;
                final float moved = event.getRawY() - dragDownY;
                draggingDown = false;
                dragCandidate = false;
                if (!was) return true;
                if (event.getActionMasked() == MotionEvent.ACTION_UP && moved > dp(CLOSE_DRAG_DP)) {
                    hide();
                } else if (!hiding) {
                    animate().translationY(0).setDuration(300)
                            .setInterpolator(DownloadsMotion.ENTER).start();
                }
                return true;
            }
            default:
                return true;
        }
    }

    /** @return Whether the back button was used up, by closing the sheet or the player. */
    boolean handleBack() {
        if (sheetLayer != null) {
            closeSheet();
            return true;
        }
        if (isShowing()) {
            hide();
            return true;
        }
        return false;
    }

    private int parentHeight() {
        View parent = getParent() instanceof View ? (View) getParent() : null;
        final int height = parent == null ? getHeight() : parent.getHeight();
        return height > 0 ? height : dp(800);
    }

    // ------------------------------------------------------------------ updates

    /** Called on every playback tick, and whenever the track or the state changes. */
    void onPlaybackChanged(String path, String songTitle, boolean playing, int position, int duration) {
        if (path.isEmpty()) {
            boundPath = "";
            boundTrack = null;
            if (isShowing()) hide();
            return;
        }
        if (!path.equals(boundPath)) bindTrack(path, songTitle);

        updatePlayIcon(playing, getVisibility() == VISIBLE);
        if (playing != artworkScaledForPlaying) {
            // The cover shrinks a little while paused, so the state reads at a glance.
            artworkScaledForPlaying = playing;
            artSquare.animate().scaleX(playing ? 1f : PAUSED_ARTWORK_SCALE)
                    .scaleY(playing ? 1f : PAUSED_ARTWORK_SCALE).setDuration(520)
                    .setInterpolator(DownloadsMotion.BOUNCY).start();
        }

        if (!userSeeking) {
            seek.setMax(Math.max(1, duration));
            seek.setProgress(position);
            elapsed.setText(DownloadsUi.clock(position));
        }
        total.setText(DownloadsUi.clock(duration));

        final int version = OfflinePlaybackService.getQueueVersion();
        if (version != lastQueueVersion) {
            lastQueueVersion = version;
            updateModes();
            if (queueSheetOpen && !dragging) rebuildQueue();
        }
        updateSleepLabel();
    }

    /** Redraws the heart, after the favorite changed on this screen or somewhere else. */
    void refreshHeart() {
        final boolean favorite = boundTrack != null && host.isFavorite(boundTrack);
        DownloadsUi.setIcon(heart, favorite
                        ? "yt_fill_experimental_heart_vd_theme_24"
                        : "yt_outline_experimental_heart_vd_theme_24",
                favorite ? DownloadsUi.FAVORITE_COLOR : DownloadsUi.foreground());
    }

    private void bindTrack(String path, String fallbackTitle) {
        boundPath = path;
        boundTrack = host.findTrack(path);
        if (boundTrack == null) boundTrack = OfflineTrack.load(new File(path));

        title.setText(boundTrack.displayTitle().isEmpty() ? fallbackTitle : boundTrack.displayTitle());
        artist.setText(boundTrack.displayArtist());

        Bitmap bitmap = boundTrack.artwork(ARTWORK_PIXELS);
        DownloadsUi.setArtwork(getContext(), artwork, bitmap);
        tintTo(DownloadsUi.dominantColor(bitmap));
        refreshHeart();
    }

    private void tintTo(int target) {
        final int from = tint;
        tint = target;
        ValueAnimator animator = ValueAnimator.ofObject(new ArgbEvaluator(), from, target);
        animator.setDuration(TINT_MILLISECONDS);
        animator.addUpdateListener(value ->
                backdrop.setColors(new int[]{(int) value.getAnimatedValue(), DownloadsUi.background()}));
        animator.start();
    }

    private void updateModes() {
        final int fg = DownloadsUi.foreground();
        final int secondary = DownloadsUi.secondary();

        final boolean shuffled = OfflinePlaybackService.isShuffleEnabled();
        DownloadsUi.setIcon(shuffleButton, "yt_outline_experimental_shuffle_vd_theme_24",
                shuffled ? fg : secondary);
        DownloadsUi.setToggled(shuffleButton, shuffled);

        final int repeat = OfflinePlaybackService.getRepeatMode();
        DownloadsUi.setIcon(repeatButton, repeat == OfflinePlaybackService.REPEAT_ONE
                        ? "yt_outline_experimental_repeat1_vd_theme_24"
                        : "yt_outline_experimental_repeat_vd_theme_24",
                repeat == OfflinePlaybackService.REPEAT_OFF ? secondary : fg);
        DownloadsUi.setToggled(repeatButton, repeat != OfflinePlaybackService.REPEAT_OFF);

        final int index = OfflinePlaybackService.queueCurrentIndex();
        final int size = OfflinePlaybackService.queueSnapshot().size();
        final boolean hasNext = (index >= 0 && index + 1 < size)
                || (repeat == OfflinePlaybackService.REPEAT_ALL && size > 0);
        nextButton.setEnabled(hasNext);
        nextButton.setAlpha(hasNext ? 1f : .35f);
    }

    private void updateSleepLabel() {
        String label;
        boolean active = true;
        if (OfflinePlaybackService.isSleepAtEndOfTrack()) {
            label = str("morphe_music_downloads_sleep_end_of_track");
        } else if (OfflinePlaybackService.getSleepEndsAt() > 0) {
            final long remaining = OfflinePlaybackService.getSleepEndsAt() - SystemClock.elapsedRealtime();
            label = DownloadsUi.clock((int) Math.max(0, remaining));
        } else {
            label = str("morphe_music_downloads_sleep_timer");
            active = false;
        }
        if (label.equals(shownSleepLabel)) return;

        shownSleepLabel = label;
        sleepLabel.setText(label);
        sleepPill.setBackground(DownloadsUi.rippleRounded(
                DownloadsUi.withAlpha(DownloadsUi.foreground(), active ? 70 : 34), dp(20)));
    }

    private void updatePlayIcon(boolean playing, boolean morph) {
        // This runs on every tick, so the icon is only touched when the state flips.
        if (playIconReady && playing == showingPause) return;

        final boolean animateNow = playIconReady && morph;
        playIconReady = true;
        showingPause = playing;
        if (animateNow && startIconMorph(playing)) return;

        playButton.setImageDrawable(ResourceUtils.getDrawable(playing
                ? "yt_fill_experimental_pause_vd_theme_24"
                : "yt_fill_experimental_play_vd_theme_24"));
    }

    /** The app ships the drawable its own player uses to morph between the two icons. */
    private boolean startIconMorph(boolean playing) {
        String name = playing
                ? "player_play_pause_vector_transition"
                : "player_pause_play_vector_transition";

        int identifier = ResourceUtils.getDrawableIdentifier(name + "_delhi");
        if (identifier == 0) identifier = ResourceUtils.getDrawableIdentifier(name);
        if (identifier == 0) return false;

        Drawable drawable = getContext().getDrawable(identifier);
        if (!(drawable instanceof AnimatedVectorDrawable morph)) return false;

        playButton.setImageDrawable(morph);
        morph.start();
        return true;
    }

    // ------------------------------------------------------------------ gestures

    /** Sideways on the cover changes track, the cover follows the finger a little. */
    private OnTouchListener createSwipeListener() {
        return new OnTouchListener() {
            private float downX;
            private boolean swiping;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                final float dx = event.getRawX() - downX;

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        swiping = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(dx) > touchSlop) swiping = true;
                        if (swiping) artSquare.setTranslationX(dx * 0.6f);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (swiping && Math.abs(dx) > dp(90)) {
                            if (dx < 0) OfflinePlaybackService.skipNext(getContext());
                            else OfflinePlaybackService.skipPrevious(getContext());
                        }
                        settle();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        settle();
                        return true;
                    default:
                        return false;
                }
            }

            private void settle() {
                swiping = false;
                artSquare.animate().translationX(0).setDuration(520)
                        .setInterpolator(DownloadsMotion.BOUNCY).start();
            }
        };
    }

    // ------------------------------------------------------------------ sheets

    private void openSheet(View body, boolean tall) {
        closeSheetNow();
        final Context context = getContext();

        final FrameLayout layer = new FrameLayout(context);
        View scrim = new View(context);
        scrim.setBackgroundColor(Color.argb(150, 0, 0, 0));
        scrim.setOnClickListener(v -> closeSheet());
        layer.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClickable(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(DownloadsUi.mix(DownloadsUi.background(), DownloadsUi.foreground(), 0.10f));
        final float radius = dp(24);
        background.setCornerRadius(radius);
        background.setStroke(dp(1), DownloadsUi.withAlpha(DownloadsUi.foreground(), 36));
        panel.setBackground(background);
        // The sheet floats above the player, with a gap on the sides and below.
        DownloadsUi.roundCorners(panel, radius);
        panel.setElevation(dp(10));

        View grab = new View(context);
        grab.setBackground(DownloadsUi.fill(DownloadsUi.withAlpha(DownloadsUi.foreground(), 90), dp(2)));
        LinearLayout.LayoutParams grabParams = new LinearLayout.LayoutParams(dp(36), dp(4));
        grabParams.gravity = Gravity.CENTER_HORIZONTAL;
        grabParams.topMargin = dp(10);
        grabParams.bottomMargin = dp(6);
        panel.addView(grab, grabParams);
        panel.addView(body, tall
                ? new LinearLayout.LayoutParams(-1, 0, 1)
                : new LinearLayout.LayoutParams(-1, -2));

        final int height = getHeight() > 0 ? getHeight() : dp(800);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(-1,
                tall ? (int) (height * 0.75f) : -2, Gravity.BOTTOM);
        panelParams.setMargins(dp(8), 0, dp(8), dp(8));
        layer.addView(panel, panelParams);
        addView(layer, new FrameLayout.LayoutParams(-1, -1));

        sheetLayer = layer;
        sheetPanel = panel;
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(SLIDE_MILLISECONDS).start();
        panel.setTranslationY(height);
        panel.animate().translationY(0).setDuration(560)
                .setInterpolator(DownloadsMotion.PANEL).start();
    }

    private void closeSheet() {
        final FrameLayout layer = sheetLayer;
        final LinearLayout panel = sheetPanel;
        if (layer == null || panel == null) return;

        sheetLayer = null;
        sheetPanel = null;
        queueSheetOpen = false;
        dragging = false;
        if (layer.getChildCount() > 0) {
            layer.getChildAt(0).animate().alpha(0f).setDuration(SLIDE_MILLISECONDS).start();
        }
        panel.animate().translationY(getHeight()).setDuration(SLIDE_MILLISECONDS + 20)
                .setInterpolator(DownloadsMotion.EXIT)
                .withEndAction(() -> removeView(layer)).start();
    }

    private void closeSheetNow() {
        queueSheetOpen = false;
        dragging = false;
        if (sheetLayer != null) removeView(sheetLayer);
        sheetLayer = null;
        sheetPanel = null;
    }

    private void showSleepSheet() {
        final Context context = getContext();
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, dp(16));

        TextView heading = DownloadsUi.text(context, str("morphe_music_downloads_sleep_timer"), 20,
                DownloadsUi.foreground());
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(dp(20), dp(4), dp(20), dp(8));
        body.addView(heading, new LinearLayout.LayoutParams(-1, -2));

        final boolean active = OfflinePlaybackService.getSleepEndsAt() > 0
                || OfflinePlaybackService.isSleepAtEndOfTrack();
        if (active) {
            body.addView(sleepRow(str("morphe_music_downloads_sleep_off"),
                    OfflinePlaybackService::cancelSleepTimer));
        }
        for (int minutes : SLEEP_MINUTES) {
            final String label = minutes == 60
                    ? str("morphe_music_downloads_sleep_hour")
                    : str("morphe_music_downloads_sleep_minutes", minutes);
            body.addView(sleepRow(label, () -> OfflinePlaybackService.setSleepTimer(minutes)));
        }
        body.addView(sleepRow(str("morphe_music_downloads_sleep_end_of_track"),
                OfflinePlaybackService::setSleepAtEndOfTrack));
        openSheet(body, false);
    }

    private View sleepRow(String label, Runnable action) {
        TextView row = DownloadsUi.text(getContext(), label, 16, DownloadsUi.foreground());
        row.setPadding(dp(14), dp(14), dp(14), dp(14));
        row.setBackground(DownloadsUi.rippleRounded(Color.TRANSPARENT, dp(14)));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.setMargins(dp(8), 0, dp(8), 0);
        row.setLayoutParams(rowParams);
        row.setOnClickListener(v -> {
            closeSheet();
            action.run();
            updateSleepLabel();
        });
        return row;
    }

    private void showQueueSheet() {
        final Context context = getContext();
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(4), dp(20), dp(8));
        TextView heading = DownloadsUi.text(context, str("morphe_music_downloads_up_next"), 20,
                DownloadsUi.foreground());
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        queueCount = DownloadsUi.text(context, "", 14, DownloadsUi.secondary());
        header.addView(queueCount, new LinearLayout.LayoutParams(-2, -2));
        body.addView(header, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(-1, dp(1));
        lineParams.setMargins(dp(20), 0, dp(20), dp(4));
        body.addView(DownloadsUi.hairline(context), lineParams);

        queueScroll = new ScrollView(context);
        queueList = new LinearLayout(context);
        queueList.setOrientation(LinearLayout.VERTICAL);
        queueList.setPadding(dp(8), 0, dp(8), dp(16));
        queueScroll.addView(queueList);
        body.addView(queueScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        openSheet(body, true);
        queueSheetOpen = true;
        rebuildQueue();

        final int current = OfflinePlaybackService.queueCurrentIndex();
        final ScrollView scroll = queueScroll;
        scroll.post(() -> scroll.scrollTo(0, Math.max(0, (current - 1) * dp(QUEUE_ROW_DP))));

        // Only the rows around the track playing are in view, and only when the sheet opens:
        // the list is rebuilt after every drag, and must not replay this each time.
        final int first = Math.max(0, current - 1);
        if (first < queueRows.size()) {
            DownloadsMotion.staggerIn(new ArrayList<>(queueRows.subList(first, queueRows.size())),
                    dp(18), 180, 8);
        }
    }

    private void rebuildQueue() {
        if (!queueSheetOpen || queueList == null) return;

        queueRows.clear();
        queueList.removeAllViews();
        final List<String> paths = OfflinePlaybackService.queueSnapshot();
        final int current = OfflinePlaybackService.queueCurrentIndex();
        if (queueCount != null) {
            queueCount.setText(str("morphe_music_downloads_track_count", paths.size()));
        }

        for (int i = 0; i < paths.size(); i++) {
            View row = queueRow(i, paths.get(i), i == current, paths.size());
            queueRows.add(row);
            queueList.addView(row, new LinearLayout.LayoutParams(-1, dp(QUEUE_ROW_DP)));
        }
    }

    private View queueRow(int index, String path, boolean current, int count) {
        final Context context = getContext();
        final int fg = DownloadsUi.foreground();
        OfflineTrack found = host.findTrack(path);
        final OfflineTrack track = found != null ? found : OfflineTrack.load(new File(path));

        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), 0, dp(4), 0);
        row.setBackground(current
                ? DownloadsUi.rippleCard(DownloadsUi.withAlpha(fg, 30),
                        DownloadsUi.withAlpha(fg, 70), dp(1), dp(14))
                : DownloadsUi.rippleRounded(Color.TRANSPARENT, dp(14)));
        // What has already been played fades back, so what is still to come stands out.
        if (index < OfflinePlaybackService.queueCurrentIndex()) row.setAlpha(0.55f);
        row.setOnClickListener(v -> OfflinePlaybackService.jumpTo(index));

        ImageView handle = new ImageView(context);
        handle.setScaleType(ImageView.ScaleType.CENTER);
        handle.setContentDescription(str("morphe_music_downloads_reorder"));
        DownloadsUi.setIcon(handle, "yt_outline_experimental_equal_vd_theme_24", DownloadsUi.secondary());
        attachDrag(handle, row, index, count);
        row.addView(handle, new LinearLayout.LayoutParams(dp(40), -1));

        row.addView(DownloadsUi.artwork(context, queueArtwork(track), dp(6)),
                new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12), 0, dp(8), 0);
        TextView name = DownloadsUi.text(context, track.displayTitle(), 15, fg);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        if (current) name.setTypeface(Typeface.DEFAULT_BOLD);
        TextView by = DownloadsUi.text(context, track.displayArtist(), 13, DownloadsUi.secondary());
        by.setSingleLine(true);
        by.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(name);
        labels.addView(by);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        if (current) {
            ImageView playing = new ImageView(context);
            playing.setScaleType(ImageView.ScaleType.CENTER);
            DownloadsUi.setIcon(playing, "yt_outline_experimental_volume_max_vd_theme_24", fg);
            row.addView(playing, new LinearLayout.LayoutParams(dp(40), dp(40)));
        } else {
            ImageButton remove = DownloadsUi.icon(context, "yt_outline_x_mark_vd_theme_24",
                    str("morphe_music_downloads_remove_from_queue"), DownloadsUi.secondary());
            remove.setOnClickListener(v -> {
                OfflinePlaybackService.removeFromQueue(index);
                lastQueueVersion = OfflinePlaybackService.getQueueVersion();
                updateModes();
                rebuildQueue();
            });
            row.addView(remove, new LinearLayout.LayoutParams(dp(40), dp(40)));
        }
        return row;
    }

    @Nullable
    private static Bitmap queueArtwork(OfflineTrack track) {
        final String key = track.audioFile().getAbsolutePath();
        Bitmap cached = QUEUE_ARTWORK.get(key);
        if (cached != null) return cached;

        Bitmap bitmap = track.artwork(QUEUE_ARTWORK_PIXELS);
        if (bitmap != null) QUEUE_ARTWORK.put(key, bitmap);
        return bitmap;
    }

    /** The row follows the finger, the others step aside, and letting go drops it in place. */
    private void attachDrag(ImageView handle, View row, int index, int count) {
        handle.setOnTouchListener(new OnTouchListener() {
            private float startY;
            private int target;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = event.getRawY();
                        target = index;
                        dragging = true;
                        row.setTranslationZ(dp(6));
                        if (queueScroll != null) queueScroll.requestDisallowInterceptTouchEvent(true);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        final float dy = event.getRawY() - startY;
                        final int rowPx = dp(QUEUE_ROW_DP);
                        row.setTranslationY(dy);
                        final int newTarget = Math.max(0, Math.min(count - 1,
                                index + Math.round(dy / rowPx)));
                        if (newTarget != target) {
                            target = newTarget;
                            shiftRows(index, target, rowPx);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        dragging = false;
                        if (queueScroll != null) queueScroll.requestDisallowInterceptTouchEvent(false);
                        if (event.getActionMasked() == MotionEvent.ACTION_UP && target != index) {
                            OfflinePlaybackService.moveInQueue(index, target);
                            lastQueueVersion = OfflinePlaybackService.getQueueVersion();
                        }
                        rebuildQueue();
                        return true;
                    }
                    default:
                        return false;
                }
            }
        });
    }

    private void shiftRows(int from, int to, int rowPx) {
        for (int j = 0; j < queueRows.size(); j++) {
            if (j == from) continue;
            float offset = 0;
            if (from < to && j > from && j <= to) offset = -rowPx;
            else if (from > to && j < from && j >= to) offset = rowPx;
            queueRows.get(j).animate().translationY(offset).setDuration(320)
                    .setInterpolator(DownloadsMotion.SOFT).start();
        }
    }

    // ------------------------------------------------------------------ small helpers

    private LinearLayout pill(String iconName, String label) {
        final Context context = getContext();
        final int fg = DownloadsUi.foreground();

        LinearLayout pill = new LinearLayout(context);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(16), 0, dp(18), 0);
        pill.setClickable(true);
        pill.setBackground(DownloadsUi.rippleRounded(DownloadsUi.withAlpha(fg, 34), dp(20)));

        ImageView icon = new ImageView(context);
        DownloadsUi.setIcon(icon, iconName, fg);
        pill.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));

        TextView text = DownloadsUi.text(context, label, 14, fg);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setPadding(dp(8), 0, 0, 0);
        pill.addView(text, new LinearLayout.LayoutParams(-2, -1));
        return pill;
    }

    /** Long names scroll instead of being cut off, like in the stock player. */
    private static void marquee(TextView view) {
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        view.setMarqueeRepeatLimit(-1);
        view.setSelected(true);
    }

    private int dp(int value) {
        return DownloadsUi.dp(getContext(), value);
    }
}
