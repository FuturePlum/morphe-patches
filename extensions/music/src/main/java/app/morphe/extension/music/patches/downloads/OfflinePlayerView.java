/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;

/**
 * The big offline player, drawn over the whole offline page like the one of the stock app:
 * large cover, title, seek bar, the five transport buttons, and a "Up next" sheet that slides up
 * with the whole queue (tap to jump, hold the handle to reorder, hold a row for more actions).
 * <p>
 * Swiping down closes the sheet, then the player. Only offline features are here: there is no
 * like, comment or video switch, since none of them means anything for a file on the phone.
 */
@SuppressLint("ClickableViewAccessibility")
@SuppressWarnings({"deprecation", "RedundantSuppression"})
final class OfflinePlayerView extends FrameLayout implements OfflinePlaybackService.PlaybackListener {

    /** What the screen that hosts the player has to do for it. */
    interface Host {
        /** The player starts to leave: the screen can bring its own bars back. */
        void onClosing();

        /** The player is gone from the screen and must be removed. */
        void onClosed();

        /** Show the page of this artist in the library. */
        void onShowArtist(String artistName);

        /** A favorite was added or removed. */
        void onFavoritesChanged();

        /** The user wants to delete the download of this track. */
        void onDeleteRequested(OfflineTrack track);
    }

    private static final int SIDE_MARGIN_DP = 24;
    private static final int TOP_BAR_DP = 56;
    private static final int ICON_BUTTON_DP = 48;
    private static final int PLAY_BUTTON_DP = 72;
    private static final int SKIP_BUTTON_DP = 64;
    private static final int ART_CORNER_DP = 10;
    private static final int THUMB_CORNER_DP = 4;
    private static final int ROW_CORNER_DP = 8;
    private static final int MENU_WIDTH_DP = 260;
    private static final int MENU_CORNER_DP = 12;
    private static final int PILL_HEIGHT_DP = 40;
    private static final int PILL_CORNER_DP = 20;
    private static final int SHEET_CORNER_DP = 16;

    private static final int SEEK_BAR_HEIGHT_DP = 4;
    private static final int SEEK_BAR_TOUCH_DP = 28;
    private static final int SEEK_THUMB_DP = 14;

    private static final int ART_PIXELS = 1080;
    private static final int THUMB_PIXELS = 256;

    private static final long SLIDE_MILLISECONDS = 260;
    private static final long DISMISS_MILLISECONDS = 200;
    private static final long TINT_MILLISECONDS = 450;

    /** Alpha of a toggle that is off, or of a button that cannot be used. */
    private static final float OFF_ALPHA = 0.55f;
    private static final float DISABLED_ALPHA = 0.35f;
    private static final float SECONDARY_DIM = 0.72f;

    /** The player closes when it was dragged this far down (share of its height), or thrown. */
    private static final float DISMISS_DISTANCE = 0.25f;
    private static final float DISMISS_VELOCITY_DP = 900;

    private static final LruCache<String, Bitmap> bigArtCache = new LruCache<>(3);
    private static final LruCache<String, Bitmap> thumbCache = new LruCache<>(100);
    private static final LruCache<String, Integer> tintCache = new LruCache<>(60);
    private static final ExecutorService loader = Executors.newSingleThreadExecutor();

    private final Host host;
    private final File musicRoot;
    private final int touchSlop;

    private final GradientDrawable mainBackground = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0, 0});
    private final GradientDrawable sheetBackground = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0, 0});
    private int tint = neutralTint();
    @Nullable
    private ValueAnimator tintAnimator;

    // Player.
    private LinearLayout player;
    private TextView sourceLabel;
    private ImageView artView;
    private TextView titleView;
    private TextView artistView;
    private ImageView favoriteIcon;
    private TextView favoriteLabel;
    private SeekBar seek;
    private TextView elapsedView;
    private TextView durationView;
    private ImageButton shuffleButton;
    private ImageButton previousButton;
    private ImageButton playButton;
    private ImageButton nextButton;
    private ImageButton repeatButton;

    // Queue sheet.
    private FrameLayout queueSheet;
    private ImageView sheetArt;
    private TextView sheetTitle;
    private TextView sheetArtist;
    private ImageButton sheetPlay;
    private TextView sheetSource;
    private TextView sheetCount;
    private LinearLayout clearPill;
    private LinearLayout addAllPill;
    private QueueList queueList;
    private QueueAdapter queueAdapter;

    private List<String> queuePaths = new ArrayList<>();
    private final Map<String, OfflineTrack> trackCache = new HashMap<>();

    @Nullable
    private OfflineTrack track;
    private String currentPath = "";
    private boolean playing;
    private boolean showingPause;
    private boolean sheetShowingPause;
    private boolean userSeeking;
    private boolean queueOpen;
    private boolean closing;
    private boolean closed;
    private boolean listening;
    private RepeatIcon lastRepeat = RepeatIcon.NONE;

    private final Runnable queueListener = this::onQueueChanged;

    /** Remembers which repeat icon is drawn, so it is only replaced when the mode changes. */
    private enum RepeatIcon {
        NONE, OFF, ALL, ONE
    }

    OfflinePlayerView(Context context, Host host) {
        super(context);
        this.host = host;
        this.musicRoot = OfflineLibrary.root(context);
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        setClickable(true);
        setFocusable(true);
        setBackground(mainBackground);
        applyTint(tint, false);

        buildPlayer();
        buildQueueSheet();

        // Anything on the player that nothing else uses can be dragged down to close it.
        setOnTouchListener(new DragDismiss(this, null,
                () -> {
                    closing = true;
                    host.onClosing();
                }, this::finishClose, touchSlop, dp(1) * DISMISS_VELOCITY_DP));
    }

    // ============================================================ Life cycle

    /** Slides the player in. Call once, after it has been added to the screen. */
    void show() {
        setTranslationY(getResources().getDisplayMetrics().heightPixels);
        post(() -> animate().translationY(0).setDuration(SLIDE_MILLISECONDS)
                .setInterpolator(new DecelerateInterpolator()).start());
    }

    /**
     * Back button: closes the queue sheet first, then the player.
     */
    void onBack() {
        if (queueOpen) closeQueue();
        else dismiss();
    }

    private void dismiss() {
        if (closing || closed) return;
        closing = true;
        host.onClosing();
        animate().cancel();
        animate().translationY(getHeight() > 0 ? getHeight()
                        : getResources().getDisplayMetrics().heightPixels)
                .setDuration(DISMISS_MILLISECONDS).setInterpolator(new AccelerateInterpolator())
                .withEndAction(this::finishClose).start();
    }

    private void finishClose() {
        if (closed) return;
        closed = true;
        stopListening();
        if (tintAnimator != null) tintAnimator.cancel();
        host.onClosed();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startListening();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopListening();
        super.onDetachedFromWindow();
    }

    /** Nothing needs to refresh while the app is not on the screen. */
    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) startListening();
        else stopListening();
    }

    private void startListening() {
        if (listening || closed) return;
        listening = true;
        OfflineQueue.addListener(queueListener);
        // Reports the current state right away.
        OfflinePlaybackService.addListener(this);
        onQueueChanged();
    }

    private void stopListening() {
        if (!listening) return;
        listening = false;
        OfflinePlaybackService.removeListener(this);
        OfflineQueue.removeListener(queueListener);
    }

    // ============================================================ Player layout

    private void buildPlayer() {
        player = new LinearLayout(getContext());
        player.setOrientation(LinearLayout.VERTICAL);
        addView(player, new LayoutParams(-1, -1));

        player.addView(createTopBar(), new LinearLayout.LayoutParams(-1, dp(TOP_BAR_DP)));

        // The cover takes whatever height is left, and stays a square that fits in it.
        FitSquare artHost = new FitSquare(getContext());
        artHost.setPadding(dp(SIDE_MARGIN_DP - 4), dp(8), dp(SIDE_MARGIN_DP - 4), dp(8));
        artView = new ImageView(getContext());
        artView.setBackgroundColor(Utils.adjustColorBrightness(foreground(), 0.2f));
        roundCorners(artView, ART_CORNER_DP);
        showPlaceholder(artView, 64);
        artHost.addView(artView);
        player.addView(artHost, new LinearLayout.LayoutParams(-1, 0, 1));

        player.addView(createInfo(), new LinearLayout.LayoutParams(-1, -2));
        player.addView(createPills(), new LinearLayout.LayoutParams(-1, -2));
        player.addView(createSeekZone(), new LinearLayout.LayoutParams(-1, -2));
        player.addView(createControls(), new LinearLayout.LayoutParams(-1, -2));
        player.addView(createQueueZone(), new LinearLayout.LayoutParams(-1, -2));
    }

    private View createTopBar() {
        FrameLayout bar = new FrameLayout(getContext());
        bar.setPadding(dp(4), 0, dp(4), 0);

        ImageButton close = iconButton(OfflineIcons.Icon.CHEVRON_DOWN, dp(28),
                Txt.t("Réduire", "Minimize"));
        close.setOnClickListener(v -> dismiss());
        bar.addView(close, new LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP),
                Gravity.START | Gravity.CENTER_VERTICAL));

        LinearLayout center = new LinearLayout(getContext());
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        TextView caption = text(Txt.t("LECTURE DEPUIS", "PLAYING FROM"), 11, secondary());
        caption.setLetterSpacing(0.08f);
        caption.setGravity(Gravity.CENTER);
        center.addView(caption, new LinearLayout.LayoutParams(-1, -2));
        sourceLabel = text("", 14, foreground());
        sourceLabel.setTypeface(Typeface.DEFAULT_BOLD);
        sourceLabel.setGravity(Gravity.CENTER);
        sourceLabel.setSingleLine(true);
        sourceLabel.setEllipsize(TextUtils.TruncateAt.END);
        center.addView(sourceLabel, new LinearLayout.LayoutParams(-1, -2));
        LayoutParams centerParams = new LayoutParams(-1, -2, Gravity.CENTER);
        centerParams.setMarginStart(dp(ICON_BUTTON_DP + 4));
        centerParams.setMarginEnd(dp(ICON_BUTTON_DP + 4));
        bar.addView(center, centerParams);

        ImageButton more = iconButton(OfflineIcons.Icon.MORE, dp(24),
                Txt.t("Plus d’options", "More options"));
        more.setOnClickListener(v -> showPlayerMenu(more));
        bar.addView(more, new LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP),
                Gravity.END | Gravity.CENTER_VERTICAL));
        return bar;
    }

    private View createInfo() {
        LinearLayout info = new LinearLayout(getContext());
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(SIDE_MARGIN_DP), dp(14), dp(SIDE_MARGIN_DP), 0);

        titleView = text("", 24, foreground());
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(titleView, new LinearLayout.LayoutParams(-1, -2));

        artistView = text("", 16, secondary());
        artistView.setSingleLine(true);
        artistView.setEllipsize(TextUtils.TruncateAt.END);
        artistView.setPadding(0, dp(2), 0, 0);
        info.addView(artistView, new LinearLayout.LayoutParams(-1, -2));
        return info;
    }

    /** The only actions that make sense for a file on the phone. */
    private View createPills() {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(SIDE_MARGIN_DP), dp(14), dp(SIDE_MARGIN_DP), 0);

        LinearLayout favorite = pill();
        favoriteIcon = new ImageView(getContext());
        favorite.addView(favoriteIcon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        favoriteLabel = text(Txt.t("Favori", "Favorite"), 14, foreground());
        favoriteLabel.setTypeface(Typeface.DEFAULT_BOLD);
        favoriteLabel.setPadding(dp(8), 0, 0, 0);
        favorite.addView(favoriteLabel, new LinearLayout.LayoutParams(-2, -1));
        favorite.setOnClickListener(v -> toggleFavorite());
        LinearLayout.LayoutParams favoriteParams = new LinearLayout.LayoutParams(-2, dp(PILL_HEIGHT_DP));
        favoriteParams.setMarginEnd(dp(8));
        row.addView(favorite, favoriteParams);

        LinearLayout artist = pill();
        ImageView artistIcon = new ImageView(getContext());
        artistIcon.setImageDrawable(OfflineIcons.get(OfflineIcons.Icon.PERSON, dp(20), foreground()));
        artist.addView(artistIcon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView artistText = text(Txt.t("Artiste", "Artist"), 14, foreground());
        artistText.setTypeface(Typeface.DEFAULT_BOLD);
        artistText.setPadding(dp(8), 0, 0, 0);
        artist.addView(artistText, new LinearLayout.LayoutParams(-2, -1));
        artist.setOnClickListener(v -> showArtist());
        row.addView(artist, new LinearLayout.LayoutParams(-2, dp(PILL_HEIGHT_DP)));
        return row;
    }

    private LinearLayout pill() {
        LinearLayout pill = new LinearLayout(getContext());
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(14), 0, dp(18), 0);
        pill.setBackground(pillBackground(subtle(), PILL_CORNER_DP));
        pill.setClickable(true);
        pill.setFocusable(true);
        return pill;
    }

    private View createSeekZone() {
        LinearLayout zone = new LinearLayout(getContext());
        zone.setOrientation(LinearLayout.VERTICAL);
        zone.setPadding(0, dp(12), 0, 0);

        seek = new SeekBar(getContext());
        seek.setPadding(dp(SIDE_MARGIN_DP), 0, dp(SIDE_MARGIN_DP), 0);
        seek.setProgressDrawable(createSeekBarDrawable());
        // The stock thumb draws a ripple far wider than the bar, so it only shows while dragging.
        seek.setThumb(null);
        seek.setThumbOffset(0);
        seek.setBackground(null);
        seek.setSplitTrack(false);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                userSeeking = true;
                bar.setThumb(createThumb());
                bar.setThumbOffset(dp(SEEK_THUMB_DP) / 2);
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                userSeeking = false;
                bar.setThumb(null);
                OfflinePlaybackService.seekTo(bar.getProgress());
            }

            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser) elapsedView.setText(formatTime(value));
            }
        });
        zone.addView(seek, new LinearLayout.LayoutParams(-1, dp(SEEK_BAR_TOUCH_DP)));

        LinearLayout times = new LinearLayout(getContext());
        times.setPadding(dp(SIDE_MARGIN_DP), 0, dp(SIDE_MARGIN_DP), 0);
        elapsedView = text("0:00", 12, secondary());
        durationView = text("0:00", 12, secondary());
        durationView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        times.addView(elapsedView, new LinearLayout.LayoutParams(0, -2, 1));
        times.addView(durationView, new LinearLayout.LayoutParams(0, -2, 1));
        zone.addView(times, new LinearLayout.LayoutParams(-1, -2));
        return zone;
    }

    private View createControls() {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(SIDE_MARGIN_DP - 12), dp(6), dp(SIDE_MARGIN_DP - 12), dp(6));

        shuffleButton = iconButton(OfflineIcons.Icon.SHUFFLE, dp(24), Txt.t("Aléatoire", "Shuffle"));
        shuffleButton.setOnClickListener(v -> OfflineQueue.setShuffle(!OfflineQueue.isShuffled()));

        previousButton = iconButton(OfflineIcons.Icon.PREVIOUS, dp(36), Txt.t("Précédent", "Previous"));
        previousButton.setOnClickListener(v -> OfflinePlaybackService.skipPrevious(getContext()));

        playButton = new ImageButton(getContext());
        playButton.setScaleType(ImageView.ScaleType.CENTER);
        playButton.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor()),
                circle(foreground()), new ShapeDrawable(new OvalShape())));
        playButton.setContentDescription(Txt.t("Lecture / pause", "Play / pause"));
        playButton.setOnClickListener(v -> OfflinePlaybackService.toggle(getContext()));
        setPlayIcon(false);

        nextButton = iconButton(OfflineIcons.Icon.NEXT, dp(36), Txt.t("Suivant", "Next"));
        nextButton.setOnClickListener(v -> OfflinePlaybackService.skipNext(getContext()));

        repeatButton = iconButton(OfflineIcons.Icon.REPEAT, dp(24), Txt.t("Répéter", "Repeat"));
        repeatButton.setOnClickListener(v -> OfflineQueue.cycleRepeat());

        row.addView(shuffleButton, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));
        row.addView(spacer(), new LinearLayout.LayoutParams(0, 1, 1));
        row.addView(previousButton, new LinearLayout.LayoutParams(dp(SKIP_BUTTON_DP), dp(SKIP_BUTTON_DP)));
        row.addView(spacer(), new LinearLayout.LayoutParams(0, 1, 1));
        row.addView(playButton, new LinearLayout.LayoutParams(dp(PLAY_BUTTON_DP), dp(PLAY_BUTTON_DP)));
        row.addView(spacer(), new LinearLayout.LayoutParams(0, 1, 1));
        row.addView(nextButton, new LinearLayout.LayoutParams(dp(SKIP_BUTTON_DP), dp(SKIP_BUTTON_DP)));
        row.addView(spacer(), new LinearLayout.LayoutParams(0, 1, 1));
        row.addView(repeatButton, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));
        return row;
    }

    /** The handle at the bottom: a tap or a swipe up opens the queue. */
    private View createQueueZone() {
        LinearLayout zone = new LinearLayout(getContext());
        zone.setOrientation(LinearLayout.VERTICAL);
        zone.setGravity(Gravity.CENTER_HORIZONTAL);
        zone.setPadding(0, dp(10), 0, dp(14));

        View handle = new View(getContext());
        handle.setBackground(CustomDialog.createRoundedBackground(2, subtleStrong()));
        zone.addView(handle, new LinearLayout.LayoutParams(dp(40), dp(4)));

        TextView label = text(Txt.t("À suivre", "Up next"), 14, foreground());
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(12), 0, dp(4));
        zone.addView(label, new LinearLayout.LayoutParams(-2, -2));

        final float[] downY = new float[1];
        zone.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> downY[0] = event.getRawY();
                case MotionEvent.ACTION_UP -> {
                    final float dy = event.getRawY() - downY[0];
                    // A tap, or a swipe up.
                    if (dy < -touchSlop || Math.abs(dy) < touchSlop) openQueue();
                }
                default -> {
                }
            }
            return true;
        });
        return zone;
    }

    // ============================================================ Queue sheet layout

    private void buildQueueSheet() {
        queueSheet = new FrameLayout(getContext());
        queueSheet.setBackground(sheetBackground);
        // Swallows every touch it does not use, so nothing reaches the player under it.
        queueSheet.setClickable(true);
        queueSheet.setVisibility(GONE);
        addView(queueSheet, new LayoutParams(-1, -1));

        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(LinearLayout.VERTICAL);
        queueSheet.addView(column, new LayoutParams(-1, -1));

        View miniBar = createSheetMiniBar();
        miniBar.setOnTouchListener(sheetDismiss(this::closeQueue));
        column.addView(miniBar, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout panel = new LinearLayout(getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable panelBackground = new GradientDrawable();
        panelBackground.setColor(ThemeUtils.getDialogBackgroundColor());
        final float radius = dp(SHEET_CORNER_DP);
        panelBackground.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        panel.setBackground(panelBackground);
        column.addView(panel, new LinearLayout.LayoutParams(-1, 0, 1));

        View panelHeader = createSheetHeader();
        panelHeader.setOnTouchListener(sheetDismiss(null));
        panel.addView(panelHeader, new LinearLayout.LayoutParams(-1, -2));

        queueList = new QueueList(getContext());
        queueList.setDivider(null);
        queueList.setDividerHeight(0);
        queueList.setSelector(new ColorDrawable(Color.TRANSPARENT));
        queueList.setVerticalScrollBarEnabled(false);
        queueList.setClipToPadding(false);
        queueList.setPadding(dp(8), 0, dp(8), dp(12));
        queueAdapter = new QueueAdapter();
        queueList.setAdapter(queueAdapter);
        panel.addView(queueList, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    /** Dragging the top of the sheet down puts it away; a tap on it does too when asked. */
    private DragDismiss sheetDismiss(@Nullable Runnable onTap) {
        return new DragDismiss(queueSheet, onTap,
                () -> queueOpen = false,
                () -> {
                    queueSheet.setVisibility(GONE);
                    queueSheet.setTranslationY(0);
                }, touchSlop, dp(1) * DISMISS_VELOCITY_DP);
    }

    /** The playing track again, above the sheet, like the stock app: tapping it puts the queue away. */
    private View createSheetMiniBar() {
        LinearLayout bar = new LinearLayout(getContext());
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(14), dp(8), dp(14));

        sheetArt = new ImageView(getContext());
        sheetArt.setBackgroundColor(Utils.adjustColorBrightness(foreground(), 0.2f));
        roundCorners(sheetArt, THUMB_CORNER_DP);
        showPlaceholder(sheetArt, 24);
        bar.addView(sheetArt, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout labels = new LinearLayout(getContext());
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.setPadding(dp(16), 0, dp(8), 0);
        sheetTitle = text("", 16, foreground());
        sheetTitle.setTypeface(Typeface.DEFAULT_BOLD);
        sheetTitle.setSingleLine(true);
        sheetTitle.setEllipsize(TextUtils.TruncateAt.END);
        sheetArtist = text("", 14, secondary());
        sheetArtist.setSingleLine(true);
        sheetArtist.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(sheetTitle);
        labels.addView(sheetArtist);
        bar.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        sheetPlay = iconButton(OfflineIcons.Icon.PLAY, dp(28), Txt.t("Lecture / pause", "Play / pause"));
        sheetPlay.setOnClickListener(v -> OfflinePlaybackService.toggle(getContext()));
        bar.addView(sheetPlay, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));
        return bar;
    }

    private View createSheetHeader() {
        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(LinearLayout.VERTICAL);

        View handle = new View(getContext());
        handle.setBackground(CustomDialog.createRoundedBackground(2, subtleStrong()));
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(40), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.topMargin = dp(10);
        header.addView(handle, handleParams);

        LinearLayout line = new LinearLayout(getContext());
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(20), dp(14), dp(16), dp(4));

        LinearLayout labels = new LinearLayout(getContext());
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView caption = text(Txt.t("Lecture depuis", "Playing from"), 14, secondary());
        labels.addView(caption);
        sheetSource = text("", 20, foreground());
        sheetSource.setTypeface(Typeface.DEFAULT_BOLD);
        sheetSource.setSingleLine(true);
        sheetSource.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(sheetSource);
        line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        clearPill = pill();
        ImageView clearIcon = new ImageView(getContext());
        clearIcon.setImageDrawable(OfflineIcons.get(OfflineIcons.Icon.TRASH, dp(20), foreground()));
        clearPill.addView(clearIcon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView clearText = text(Txt.t("Vider la suite", "Clear next"), 14, foreground());
        clearText.setTypeface(Typeface.DEFAULT_BOLD);
        clearText.setPadding(dp(8), 0, 0, 0);
        clearPill.addView(clearText, new LinearLayout.LayoutParams(-2, -1));
        clearPill.setOnClickListener(v -> clearUpcoming());
        line.addView(clearPill, new LinearLayout.LayoutParams(-2, dp(PILL_HEIGHT_DP)));
        header.addView(line, new LinearLayout.LayoutParams(-1, -2));

        // One tap adds the whole library to the end of the queue, in random order.
        LinearLayout addLine = new LinearLayout(getContext());
        addLine.setGravity(Gravity.CENTER_VERTICAL);
        addLine.setPadding(dp(20), dp(6), dp(16), dp(2));
        addAllPill = pill();
        ImageView addIcon = new ImageView(getContext());
        addIcon.setImageDrawable(OfflineIcons.get(OfflineIcons.Icon.SHUFFLE, dp(20), foreground()));
        addAllPill.addView(addIcon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView addText = text(Txt.t("Tout ajouter (aléatoire)", "Add all (shuffled)"), 14, foreground());
        addText.setTypeface(Typeface.DEFAULT_BOLD);
        addText.setPadding(dp(8), 0, 0, 0);
        addAllPill.addView(addText, new LinearLayout.LayoutParams(-2, -1));
        addAllPill.setOnClickListener(v -> addLibraryShuffled());
        addLine.addView(addAllPill, new LinearLayout.LayoutParams(-2, dp(PILL_HEIGHT_DP)));
        header.addView(addLine, new LinearLayout.LayoutParams(-1, -2));

        sheetCount = text("", 13, secondary());
        sheetCount.setPadding(dp(20), 0, dp(16), dp(10));
        header.addView(sheetCount, new LinearLayout.LayoutParams(-1, -2));
        return header;
    }

    // ============================================================ Queue sheet behavior

    private void openQueue() {
        if (queueOpen || closing) return;
        queueOpen = true;
        queueSheet.animate().cancel();
        refreshQueueRows();
        queueSheet.setVisibility(VISIBLE);
        queueSheet.setTranslationY(getHeight() > 0 ? getHeight()
                : getResources().getDisplayMetrics().heightPixels);
        queueSheet.animate().translationY(0).setDuration(SLIDE_MILLISECONDS)
                .setInterpolator(new DecelerateInterpolator()).start();
        // The track that plays is the first thing seen, with the one before it for context.
        queueList.post(() -> queueList.setSelectionFromTop(
                Math.max(0, OfflineQueue.getIndex() - 1), 0));
    }

    private void closeQueue() {
        if (!queueOpen) return;
        queueOpen = false;
        queueSheet.animate().cancel();
        queueSheet.animate().translationY(queueSheet.getHeight() > 0 ? queueSheet.getHeight()
                        : getResources().getDisplayMetrics().heightPixels)
                .setDuration(DISMISS_MILLISECONDS).setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    queueSheet.setVisibility(GONE);
                    queueSheet.setTranslationY(0);
                }).start();
    }

    /** Puts every downloaded track at the end of the queue, in random order. */
    private void addLibraryShuffled() {
        if (OfflineQueue.size() == 0) return;
        final int added = OfflineQueue.addAllShuffled(OfflineLibrary.audioPaths(musicRoot));
        if (added == 0) {
            Utils.showToastShort(Txt.t("Tout est déjà dans la file", "Everything is already in the queue"));
        } else {
            Utils.showToastShort(Txt.t(
                    added + (added > 1 ? " titres ajoutés à la file" : " titre ajouté à la file"),
                    added + (added > 1 ? " songs added to the queue" : " song added to the queue")));
        }
    }

    private void clearUpcoming() {
        if (OfflineQueue.getIndex() + 1 >= OfflineQueue.size()) return;
        OfflineQueue.clearUpcoming();
        Utils.showToastShort(Txt.t("Suite de la file vidée", "Up next cleared"));
    }

    private void onQueueChanged() {
        refreshQueueRows();
        updateModeButtons();
        updateNavButtons();
        updateSourceTexts();
    }

    /** Reads the queue again, keeping the rows that are on the screen. */
    private void refreshQueueRows() {
        queuePaths = OfflineQueue.snapshot();
        if (queueAdapter != null) queueAdapter.notifyDataSetChanged();

        final int index = OfflineQueue.getIndex();
        final int upcoming = Math.max(0, queuePaths.size() - Math.max(0, index) - 1);
        if (sheetCount != null) {
            sheetCount.setText(queuePaths.isEmpty() ? "" : Txt.t(
                    "Titre " + (index + 1) + " sur " + queuePaths.size()
                            + " • " + upcoming + " à suivre",
                    "Song " + (index + 1) + " of " + queuePaths.size()
                            + " • " + upcoming + " up next"));
        }
        if (clearPill != null) {
            clearPill.setEnabled(upcoming > 0);
            clearPill.setAlpha(upcoming > 0 ? 1f : DISABLED_ALPHA);
        }
    }

    private OfflineTrack trackFor(String path) {
        OfflineTrack cached = trackCache.get(path);
        if (cached == null) {
            cached = OfflineTrack.load(new File(path));
            trackCache.put(path, cached);
        }
        return cached;
    }

    private void playAt(int position) {
        if (position == OfflineQueue.getIndex()) {
            OfflinePlaybackService.toggle(getContext());
            return;
        }
        OfflineQueue.jumpTo(position);
        OfflinePlaybackService.playCurrent(getContext());
    }

    // ============================================================ Playback state

    @Override
    public void onPlaybackChanged(String path, String title, boolean playing,
                                  int position, int duration) {
        post(() -> applyPlayback(path, title, playing, position, duration));
    }

    private void applyPlayback(String path, String title, boolean playing, int position, int duration) {
        if (closed || closing) return;

        // The service stopped: nothing is left to show.
        if (title.isEmpty()) {
            dismiss();
            return;
        }

        if (!path.equals(currentPath)) {
            currentPath = path;
            track = trackFor(path);
            showTrack(track);
            updateNavButtons();
            refreshQueueRows();
        }

        this.playing = playing;
        setPlayIcon(playing);
        setSheetPlayIcon(playing);

        seek.setMax(Math.max(1, duration));
        durationView.setText(formatTime(duration));
        if (!userSeeking) {
            seek.setProgress(position);
            elapsedView.setText(formatTime(position));
        }
    }

    private void showTrack(OfflineTrack shown) {
        titleView.setText(shown.displayTitle());
        artistView.setText(shown.displayArtist());
        sheetTitle.setText(shown.displayTitle());
        sheetArtist.setText(shown.displayArtist());
        updateFavoriteVisuals();
        updateSourceTexts();
        bindBigArtwork(shown);
        bindThumb(sheetArt, shown, 24);
    }

    private void updateSourceTexts() {
        String source = OfflineQueue.getSource();
        if (source.isEmpty()) source = Txt.t("Mes titres hors ligne", "My offline songs");
        if (sourceLabel != null) sourceLabel.setText(source);
        if (sheetSource != null) sheetSource.setText(source);
    }

    private void updateNavButtons() {
        if (nextButton == null || previousButton == null) return;
        setEnabledLook(nextButton, OfflineQueue.hasNext());
        // Previous restarts the track when nothing comes before it.
        setEnabledLook(previousButton, !currentPath.isEmpty());
    }

    private void updateModeButtons() {
        if (shuffleButton == null || repeatButton == null) return;
        shuffleButton.setAlpha(OfflineQueue.isShuffled() ? 1f : OFF_ALPHA);

        final OfflineQueue.Repeat repeat = OfflineQueue.getRepeat();
        final RepeatIcon shown = switch (repeat) {
            case ONE -> RepeatIcon.ONE;
            case ALL -> RepeatIcon.ALL;
            default -> RepeatIcon.OFF;
        };
        if (shown != lastRepeat) {
            lastRepeat = shown;
            repeatButton.setImageDrawable(OfflineIcons.get(
                    repeat == OfflineQueue.Repeat.ONE
                            ? OfflineIcons.Icon.REPEAT_ONE : OfflineIcons.Icon.REPEAT,
                    dp(24), foreground()));
        }
        repeatButton.setAlpha(repeat == OfflineQueue.Repeat.OFF ? OFF_ALPHA : 1f);
    }

    private void setPlayIcon(boolean pause) {
        if (playButton == null) return;
        if (pause == showingPause && playButton.getDrawable() != null) return;
        showingPause = pause;
        playButton.setImageDrawable(OfflineIcons.get(
                pause ? OfflineIcons.Icon.PAUSE : OfflineIcons.Icon.PLAY, dp(36), background()));
    }

    private void setSheetPlayIcon(boolean pause) {
        if (sheetPlay == null) return;
        if (pause == sheetShowingPause && sheetPlay.getDrawable() != null) return;
        sheetShowingPause = pause;
        sheetPlay.setImageDrawable(OfflineIcons.get(
                pause ? OfflineIcons.Icon.PAUSE : OfflineIcons.Icon.PLAY, dp(28), foreground()));
    }

    private void setEnabledLook(ImageButton button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : DISABLED_ALPHA);
    }

    // ============================================================ Favorites, artist, menus

    private void updateFavoriteVisuals() {
        if (track == null || favoriteIcon == null) return;
        final boolean favorite = OfflineLibrary.isFavorite(musicRoot, track.videoId());
        favoriteIcon.setImageDrawable(OfflineIcons.get(favorite
                ? OfflineIcons.Icon.HEART_FILLED : OfflineIcons.Icon.HEART, dp(20), foreground()));
        favoriteLabel.setText(favorite
                ? Txt.t("Dans mes favoris", "In favorites")
                : Txt.t("Favori", "Favorite"));
    }

    private void toggleFavorite() {
        if (track == null) return;
        toggleFavorite(track);
    }

    private void toggleFavorite(OfflineTrack target) {
        final boolean now = OfflineLibrary.toggleFavorite(musicRoot, target.videoId());
        Utils.showToastShort(now
                ? Txt.t("Ajouté aux favoris", "Added to favorites")
                : Txt.t("Retiré des favoris", "Removed from favorites"));
        updateFavoriteVisuals();
        host.onFavoritesChanged();
    }

    private void showArtist() {
        if (track == null) return;
        host.onShowArtist(track.displayArtist());
        dismiss();
    }

    private void showPlayerMenu(View anchor) {
        final OfflineTrack shown = track;
        if (shown == null) return;
        final boolean favorite = OfflineLibrary.isFavorite(musicRoot, shown.videoId());
        final boolean hasUpcoming = OfflineQueue.getIndex() + 1 < OfflineQueue.size();

        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        items.add(favorite ? Txt.t("Retirer des favoris", "Remove from favorites")
                : Txt.t("Ajouter aux favoris", "Add to favorites"));
        actions.add(() -> toggleFavorite(shown));
        items.add(Txt.t("Voir l’artiste", "Go to artist"));
        actions.add(this::showArtist);
        if (hasUpcoming) {
            items.add(Txt.t("Vider la suite", "Clear what comes next"));
            actions.add(this::clearUpcoming);
        }
        items.add(Txt.t("Supprimer le téléchargement", "Delete download"));
        actions.add(() -> host.onDeleteRequested(shown));

        showMenu(anchor, items.toArray(new String[0]), position -> actions.get(position).run());
    }

    private void showRowMenu(View anchor, int position, OfflineTrack target) {
        final boolean current = position == OfflineQueue.getIndex();
        final boolean favorite = OfflineLibrary.isFavorite(musicRoot, target.videoId());

        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (!current) {
            items.add(Txt.t("Lire ensuite", "Play next"));
            actions.add(() -> {
                OfflineQueue.moveToNext(position);
                Utils.showToastShort(Txt.t("Sera lu ensuite", "Will play next"));
            });
        }
        items.add(favorite ? Txt.t("Retirer des favoris", "Remove from favorites")
                : Txt.t("Ajouter aux favoris", "Add to favorites"));
        actions.add(() -> toggleFavorite(target));
        if (!current) {
            items.add(Txt.t("Retirer de la file", "Remove from queue"));
            actions.add(() -> OfflineQueue.remove(position));
        }
        showMenu(anchor, items.toArray(new String[0]), index -> actions.get(index).run());
    }

    private void showMenu(View anchor, String[] items, IntConsumer onPick) {
        ListPopupWindow popup = new ListPopupWindow(getContext());
        popup.setAnchorView(anchor);
        popup.setDropDownGravity(Gravity.END);
        popup.setWidth(dp(MENU_WIDTH_DP));
        popup.setModal(true);
        popup.setBackgroundDrawable(menuBackground());
        popup.setListSelector(new RippleDrawable(ColorStateList.valueOf(rippleColor()), null,
                CustomDialog.createRoundedBackground(MENU_CORNER_DP, Color.WHITE)));
        popup.setAdapter(new ArrayAdapter<>(getContext(), 0, items) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView view = text(getItem(position), 15, foreground());
                view.setPadding(dp(20), dp(14), dp(20), dp(14));
                return view;
            }
        });
        popup.setOnItemClickListener((parent, view, position, id) -> {
            popup.dismiss();
            onPick.accept(position);
        });
        popup.show();
    }

    private Drawable menuBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(ThemeUtils.getDialogBackgroundColor());
        background.setCornerRadius(dp(MENU_CORNER_DP));
        background.setStroke(dp(1), rippleColor());
        return background;
    }

    // ============================================================ Artwork and colors

    private void showPlaceholder(ImageView view, int sizeDp) {
        view.setImageDrawable(OfflineIcons.get(OfflineIcons.Icon.NOTE, dp(sizeDp), secondary()));
        view.setScaleType(ImageView.ScaleType.CENTER);
    }

    private void showArtwork(ImageView view, Bitmap bitmap) {
        view.setImageBitmap(bitmap);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
    }

    /** The large cover, and the dark tint of the background taken from it. */
    private void bindBigArtwork(OfflineTrack shown) {
        final String key = shown.videoId();
        artView.setTag(key);

        Bitmap cached = bigArtCache.get(key);
        Integer cachedTint = tintCache.get(key);
        if (cached != null) {
            showArtwork(artView, cached);
            applyTint(cachedTint != null ? cachedTint : neutralTint(), true);
            return;
        }
        showPlaceholder(artView, 64);
        applyTint(neutralTint(), true);
        loader.execute(() -> {
            if (!key.equals(artView.getTag())) return;
            Bitmap bitmap = shown.artwork(ART_PIXELS);
            if (bitmap == null) return;

            final int color = tintOf(bitmap);
            bigArtCache.put(key, bitmap);
            tintCache.put(key, color);
            post(() -> {
                if (!key.equals(artView.getTag())) return;
                showArtwork(artView, bitmap);
                applyTint(color, true);
            });
        });
    }

    /** Small cover of a row or of the bar of the sheet, read in the background. */
    private void bindThumb(ImageView view, OfflineTrack shown, int placeholderDp) {
        final String key = shown.videoId();
        view.setTag(key);

        Bitmap cached = thumbCache.get(key);
        if (cached != null) {
            showArtwork(view, cached);
            return;
        }
        showPlaceholder(view, placeholderDp);
        loader.execute(() -> {
            if (!key.equals(view.getTag())) return;
            Bitmap bitmap = shown.artwork(THUMB_PIXELS);
            if (bitmap == null) return;

            thumbCache.put(key, bitmap);
            view.post(() -> {
                if (key.equals(view.getTag())) showArtwork(view, bitmap);
            });
        });
    }

    /** A dark version of the main color of the cover, so the page takes its mood. */
    private static int tintOf(Bitmap bitmap) {
        try {
            Bitmap one = Bitmap.createScaledBitmap(bitmap, 1, 1, true);
            final int pixel = one.getPixel(0, 0);
            if (one != bitmap) one.recycle();

            float[] hsv = new float[3];
            Color.colorToHSV(pixel, hsv);
            hsv[1] = Math.min(hsv[1], 0.75f);
            hsv[2] = 0.30f;
            return Color.HSVToColor(hsv);
        } catch (Exception ex) {
            return neutralTint();
        }
    }

    private static int neutralTint() {
        return Color.rgb(46, 46, 49);
    }

    private void applyTint(int target, boolean animate) {
        if (tintAnimator != null) tintAnimator.cancel();
        if (!animate) {
            tint = target;
            paintBackgrounds(target);
            return;
        }
        final int from = tint;
        tintAnimator = ValueAnimator.ofArgb(from, target);
        tintAnimator.setDuration(TINT_MILLISECONDS);
        tintAnimator.addUpdateListener(animator -> {
            tint = (int) animator.getAnimatedValue();
            paintBackgrounds(tint);
        });
        tintAnimator.start();
    }

    private void paintBackgrounds(int top) {
        final int bottom = background();
        mainBackground.setColors(new int[]{top, bottom});
        sheetBackground.setColors(new int[]{top, bottom});
        mainBackground.invalidateSelf();
        sheetBackground.invalidateSelf();
    }

    // ============================================================ Queue list

    private final class QueueAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return queuePaths.size();
        }

        @Override
        public Object getItem(int position) {
            return queuePaths.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            QueueRow row = convertView instanceof QueueRow existing
                    ? existing : new QueueRow(getContext());
            row.bind(position, queuePaths.get(position));
            return row;
        }
    }

    private final class QueueRow extends LinearLayout {
        private final FrameLayout artCell;
        private final ImageView art;
        private final ImageView bars;
        private final TextView title;
        private final TextView subtitle;
        private final ImageButton handle;

        QueueRow(Context context) {
            super(context);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(8), dp(7), dp(0), dp(7));

            artCell = new FrameLayout(context);
            art = new ImageView(context);
            art.setBackgroundColor(Utils.adjustColorBrightness(foreground(), 0.2f));
            artCell.addView(art, new FrameLayout.LayoutParams(-1, -1));
            bars = new ImageView(context);
            bars.setBackgroundColor(Color.argb(120, 0, 0, 0));
            bars.setScaleType(ImageView.ScaleType.CENTER);
            bars.setImageDrawable(OfflineIcons.get(OfflineIcons.Icon.BARS, dp(28), Color.WHITE));
            bars.setVisibility(GONE);
            artCell.addView(bars, new FrameLayout.LayoutParams(-1, -1));
            roundCorners(artCell, THUMB_CORNER_DP);
            addView(artCell, new LinearLayout.LayoutParams(dp(56), dp(56)));

            LinearLayout labels = new LinearLayout(context);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(dp(14), 0, dp(8), 0);
            title = text("", 16, foreground());
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            subtitle = text("", 13, secondary());
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(title);
            labels.addView(subtitle);
            addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

            handle = iconButton(OfflineIcons.Icon.HANDLE, dp(24), Txt.t("Déplacer", "Move"));
            handle.setFocusable(false);
            handle.setAlpha(0.8f);
            handle.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() != MotionEvent.ACTION_DOWN) return false;
                queueList.startDrag(this, event.getRawY());
                return true;
            });
            addView(handle, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));
        }

        void bind(int position, String path) {
            final OfflineTrack row = trackFor(path);
            final boolean current = position == OfflineQueue.getIndex();

            bindThumb(art, row, 24);
            bars.setVisibility(current ? VISIBLE : GONE);
            title.setText(row.displayTitle());
            subtitle.setText(subtitleOf(row));

            // The row that is being dragged is drawn by the list, under the finger.
            setVisibility(position == queueList.dragPosition ? INVISIBLE : VISIBLE);
            setBackground(rowBackground(current));
            setOnClickListener(v -> playAt(position));
            setOnLongClickListener(v -> {
                showRowMenu(this, position, row);
                return true;
            });
        }
    }

    private String subtitleOf(OfflineTrack row) {
        StringBuilder text = new StringBuilder(row.displayArtist());
        final int seconds = row.durationSeconds();
        if (seconds > 0) text.append(" • ").append(formatTime(seconds * 1000));
        return text.toString();
    }

    /** The row of the track that plays is lifted out of the sheet. */
    private Drawable rowBackground(boolean playing) {
        Drawable card = playing
                ? CustomDialog.createRoundedBackground(ROW_CORNER_DP, Color.argb(34,
                Color.red(foreground()), Color.green(foreground()), Color.blue(foreground())))
                : null;
        return new RippleDrawable(ColorStateList.valueOf(rippleColor()), card,
                CustomDialog.createRoundedBackground(ROW_CORNER_DP, Color.WHITE));
    }

    /**
     * The list of the queue. Holding the handle of a row lifts the row, which then follows the
     * finger; each time it passes over another row they trade places in the queue.
     */
    private final class QueueList extends ListView {
        int dragPosition = -1;
        private Bitmap ghost;
        private float ghostTop;
        private float grabOffset;
        private float lastY;

        private final Runnable autoScroll = new Runnable() {
            @Override
            public void run() {
                if (!isDragging() || ghost == null) return;
                final int edge = dp(72);
                final int step = dp(10);
                if (ghostTop < edge) scrollListBy(-step);
                else if (ghostTop + ghost.getHeight() > getHeight() - edge) scrollListBy(step);
                else return;
                // The rows moved under the finger, so the row it is over may have changed.
                dragMove(lastY);
            }
        };

        QueueList(Context context) {
            super(context);
        }

        boolean isDragging() {
            return dragPosition >= 0;
        }

        private float listY(MotionEvent event) {
            int[] location = new int[2];
            getLocationOnScreen(location);
            return event.getRawY() - location[1];
        }

        void startDrag(View row, float rawY) {
            final int position = getPositionForView(row);
            if (position == INVALID_POSITION || row.getWidth() <= 0 || row.getHeight() <= 0) return;

            Bitmap snapshot = Bitmap.createBitmap(row.getWidth(), row.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(snapshot);
            // The rows are see-through, and the one that is lifted must hide what it passes over.
            canvas.drawColor(ThemeUtils.getDialogBackgroundColor());
            row.draw(canvas);

            int[] location = new int[2];
            getLocationOnScreen(location);
            ghost = snapshot;
            dragPosition = position;
            grabOffset = (rawY - location[1]) - row.getTop();
            ghostTop = row.getTop();
            lastY = rawY - location[1];

            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            queueAdapter.notifyDataSetChanged();
            invalidate();
        }

        private void dragMove(float y) {
            if (!isDragging() || ghost == null) return;
            lastY = y;

            final float maxTop = Math.max(0, getHeight() - ghost.getHeight());
            ghostTop = Math.max(0, Math.min(maxTop, y - grabOffset));

            final int center = (int) (ghostTop + ghost.getHeight() / 2f);
            final int target = pointToPosition(getWidth() / 2, center);
            if (target != INVALID_POSITION && target != dragPosition) {
                OfflineQueue.move(dragPosition, target);
                dragPosition = target;
            }
            invalidate();

            removeCallbacks(autoScroll);
            final int edge = dp(72);
            if (ghostTop < edge || ghostTop + ghost.getHeight() > getHeight() - edge) {
                postOnAnimation(autoScroll);
            }
        }

        private void endDrag() {
            removeCallbacks(autoScroll);
            dragPosition = -1;
            ghost = null;

            // Gives the list the cancel it never got, so its own touch state starts clean.
            MotionEvent cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0, 0, 0);
            super.onInterceptTouchEvent(cancel);
            cancel.recycle();

            queueAdapter.notifyDataSetChanged();
            invalidate();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            if (isDragging()) return true;
            return super.onInterceptTouchEvent(event);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (!isDragging()) return super.onTouchEvent(event);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE -> dragMove(listY(event));
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag();
                default -> {
                }
            }
            return true;
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            if (ghost != null) canvas.drawBitmap(ghost, 0, ghostTop, null);
        }
    }

    // ============================================================ Gestures

    /**
     * Lets a view be dragged down to put it away. Used by the player itself, and by the top of
     * the queue sheet.
     */
    private static final class DragDismiss implements OnTouchListener {
        private final View target;
        @Nullable
        private final Runnable onTap;
        private final Runnable onStart;
        private final Runnable onEnd;
        private final int slop;
        private final float flingVelocity;

        private float downY;
        private boolean dragging;
        @Nullable
        private VelocityTracker tracker;

        DragDismiss(View target, @Nullable Runnable onTap, Runnable onStart, Runnable onEnd,
                    int slop, float flingVelocity) {
            this.target = target;
            this.onTap = onTap;
            this.onStart = onStart;
            this.onEnd = onEnd;
            this.slop = slop;
            this.flingVelocity = flingVelocity;
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    downY = event.getRawY();
                    dragging = false;
                    if (tracker != null) tracker.recycle();
                    tracker = VelocityTracker.obtain();
                    tracker.addMovement(event);
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    if (tracker == null) return false;
                    tracker.addMovement(event);
                    final float dy = event.getRawY() - downY;
                    if (!dragging && dy > slop) {
                        dragging = true;
                        if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging) target.setTranslationY(Math.max(0, dy - slop));
                    return true;
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (tracker == null) return false;
                    tracker.addMovement(event);
                    tracker.computeCurrentVelocity(1000);
                    final float velocity = tracker.getYVelocity();
                    tracker.recycle();
                    tracker = null;

                    final boolean wasDragging = dragging;
                    dragging = false;
                    if (!wasDragging) {
                        if (event.getActionMasked() == MotionEvent.ACTION_UP && onTap != null) onTap.run();
                        return true;
                    }
                    final boolean away = event.getActionMasked() == MotionEvent.ACTION_UP
                            && (target.getTranslationY() > target.getHeight() * DISMISS_DISTANCE
                            || velocity > flingVelocity);
                    if (away) {
                        onStart.run();
                        target.animate().translationY(target.getHeight()).setDuration(DISMISS_MILLISECONDS)
                                .setInterpolator(new AccelerateInterpolator())
                                .withEndAction(onEnd).start();
                    } else {
                        target.animate().translationY(0).setDuration(180)
                                .setInterpolator(new DecelerateInterpolator()).start();
                    }
                    return true;
                }
                default -> {
                    return true;
                }
            }
        }
    }

    /** Takes all the room it is given, and shows its child as the largest square that fits. */
    private static final class FitSquare extends FrameLayout {
        FitSquare(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            final int width = MeasureSpec.getSize(widthSpec);
            final int height = MeasureSpec.getSize(heightSpec);
            final int side = Math.max(0, Math.min(width - getPaddingLeft() - getPaddingRight(),
                    height - getPaddingTop() - getPaddingBottom()));
            setMeasuredDimension(width, height);
            for (int i = 0; i < getChildCount(); i++) {
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY));
            }
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                final int childWidth = child.getMeasuredWidth();
                final int childHeight = child.getMeasuredHeight();
                final int childLeft = (right - left - childWidth) / 2;
                final int childTop = (bottom - top - childHeight) / 2;
                child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight);
            }
        }
    }

    // ============================================================ Seek bar

    private Drawable createThumb() {
        ShapeDrawable thumb = new ShapeDrawable(new OvalShape());
        thumb.setIntrinsicWidth(dp(SEEK_THUMB_DP));
        thumb.setIntrinsicHeight(dp(SEEK_THUMB_DP));
        thumb.getPaint().setColor(foreground());
        return thumb;
    }

    /**
     * The thickness comes from the insets of the drawable, because {@code setMinHeight} and
     * {@code setMaxHeight} are only available from API 29.
     */
    private Drawable createSeekBarDrawable() {
        Drawable track = CustomDialog.createRoundedBackground(SEEK_BAR_HEIGHT_DP,
                Color.argb(80, Color.red(foreground()), Color.green(foreground()), Color.blue(foreground())));
        Drawable played = CustomDialog.createRoundedBackground(SEEK_BAR_HEIGHT_DP, foreground());

        LayerDrawable layers = new LayerDrawable(new Drawable[]{
                track, new ClipDrawable(played, Gravity.START, ClipDrawable.HORIZONTAL)});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);

        final int inset = (dp(SEEK_BAR_TOUCH_DP) - dp(SEEK_BAR_HEIGHT_DP)) / 2;
        layers.setLayerInset(0, 0, inset, 0, inset);
        layers.setLayerInset(1, 0, inset, 0, inset);
        return layers;
    }

    // ============================================================ Helpers

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private View spacer() {
        return new View(getContext());
    }

    private ImageButton iconButton(OfflineIcons.Icon icon, int iconSizePx, String description) {
        ImageButton button = new ImageButton(getContext());
        button.setImageDrawable(OfflineIcons.get(icon, iconSizePx, foreground()));
        button.setContentDescription(description);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor()),
                null, new ShapeDrawable(new OvalShape())));
        return button;
    }

    private Drawable circle(int color) {
        GradientDrawable oval = new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        oval.setColor(color);
        return oval;
    }

    private Drawable pillBackground(int fill, int cornerDp) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor()),
                CustomDialog.createRoundedBackground(cornerDp, fill),
                CustomDialog.createRoundedBackground(cornerDp, Color.WHITE));
    }

    private void roundCorners(View view, int radiusDp) {
        final float radius = dp(radiusDp);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View outlined, Outline outline) {
                outline.setRoundRect(0, 0, outlined.getWidth(), outlined.getHeight(), radius);
            }
        });
        view.setClipToOutline(true);
    }

    private static int rippleColor() {
        return withAlpha(foreground(), 60);
    }

    /** A faint fill of the foreground color, for the buttons that are not the main one. */
    private static int subtle() {
        return withAlpha(foreground(), 36);
    }

    private static int subtleStrong() {
        return withAlpha(foreground(), 90);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int foreground() {
        return ThemeUtils.getAppForegroundColor();
    }

    private static int secondary() {
        return Utils.adjustColorBrightness(foreground(), SECONDARY_DIM);
    }

    private static int background() {
        return ThemeUtils.getAppBackgroundColor();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

    private static String formatTime(int milliseconds) {
        final int seconds = Math.max(0, milliseconds / 1000);
        if (seconds < 3600) {
            return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        }
        return String.format(Locale.ROOT, "%d:%02d:%02d",
                seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }
}
