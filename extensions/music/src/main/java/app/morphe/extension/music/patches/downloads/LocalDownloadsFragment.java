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
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceFragment;
import android.view.Gravity;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntConsumer;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.search.BaseSearchViewController;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;

/** Local catalogue with a compact player matching the stock bottom player of YouTube Music. */
@SuppressWarnings("deprecation")
public final class LocalDownloadsFragment extends PreferenceFragment
        implements OfflinePlaybackService.PlaybackListener, OfflineFullPlayer.Host {

    private static final int SEEK_BAR_HEIGHT_DP = 3;

    /** The bar is drawn thin but stays easy to hit. */
    private static final int SEEK_BAR_TOUCH_DP = 18;
    private static final int SEEK_THUMB_DP = 12;

    private static final int SEARCH_BAR_HEIGHT_DP = 46;
    private static final long SEARCH_ANIMATION_MILLISECONDS = 180;

    /** Dimming the foreground gives a readable secondary on any background. */
    private static final float SECONDARY_DIM = 0.72f;

    private static final int ROW_CORNER_DP = 14;

    /** How far a row is dragged to the right before letting go queues it. */
    private static final int SWIPE_THRESHOLD_DP = 96;

    /** Rows are 56dp, so a sample of the stored cover is already more than enough. */
    private static final int ROW_ARTWORK_PIXELS = 256;

    private static final int ARTWORK_CORNER_DP = 8;
    private static final int GROUP_ARTWORK_PIXELS = 384;
    private static final int MINI_PROGRESS_DP = 3;
    /** The mini player floats above the list, with a gap around it. */
    private static final int MINI_CORNER_DP = 18;
    private static final int MINI_MARGIN_DP = 10;
    private static final int MINI_SWIPE_DP = 80;
    private static final int MINI_PULL_DP = 56;

    private static final int MENU_WIDTH_DP = 220;
    private static final int MENU_CORNER_DP = 16;

    /** Icon buttons are square, otherwise their round ripple is drawn as a stretched oval. */
    private static final int ICON_BUTTON_DP = 48;
    /** A little narrower than the others, so the title keeps room next to three buttons. */
    private static final int PLAY_NEXT_BUTTON_DP = 42;

    private static final int HERO_COVER_DP = 60;
    private static final int HERO_ARTWORK_PIXELS = 128;
    private static final int CHIP_CORNER_DP = 18;
    private static final int PLAY_BUTTON_DP = 56;
    private static final int MINI_BUTTON_DP = 40;
    private static final int MINI_PLAY_DP = 44;

    private File musicRoot;
    private ImageView miniArtwork;
    private TextView miniTitle;
    private TextView miniArtist;
    private ImageButton miniPlay;
    private ImageButton miniNext;
    private ImageButton miniPrevious;
    private ProgressBar miniProgress;
    private LinearLayout miniPlayer;
    private LinearLayout songsList;

    /** Cover mosaic, count and weight of what the screen lists. */
    private FrameLayout heroCover;
    private TextView heroTitle;
    private String heroSignature = "";

    /** The bars on the cover of the playing row, if that row is on screen. */
    private DownloadsWidgets.EqualizerView playingEqualizer;

    /** Color of the mini player, which follows the cover of the track. */
    private int miniTint;
    private GradientDrawable miniBackground;
    private ValueAnimator miniTintAnimator;
    /** The list rises into place after a change of tab, sort or group, not on every keystroke. */
    private boolean animateList = true;
    private final Map<String, View> rowByPath = new java.util.HashMap<>();

    private boolean showingPause;

    /**
     * Every downloaded track, read once per refresh instead of on every list or playback update.
     */
    private List<OfflineTrack> tracks = new ArrayList<>();

    /** Each row, kept so the playing one can be marked without redrawing the list. */
    private final List<View> rowViews = new ArrayList<>();

    private List<String> queuePaths = new ArrayList<>();
    private String playingPath = "";
    private String query = "";
    private TextView summary;
    private EditText searchBar;

    /** Ids of the favorite downloads, read together with the catalogue. */
    private Set<String> favorites = new HashSet<>();
    /** Order handed to the player, which differs from the list when playing shuffled. */
    private List<String> activeQueue = new ArrayList<>();
    private ImageButton miniHeart;
    private TextView sortChip;
    private TextView tabFavorites;

    private OfflineFullPlayer fullPlayer;
    private LinearLayout actionRow;
    private TextView tabSongs;
    private TextView tabAlbums;
    private TextView tabArtists;
    private int lastQueueVersion = -1;

    /** The screen on display, so the back button of the activity can reach it. */
    private static LocalDownloadsFragment current;

    /** What the catalogue is grouped by. */
    private enum GroupMode {SONGS, FAVORITES, ALBUMS, ARTISTS}

    private GroupMode groupMode = GroupMode.SONGS;

    /** The album or artist whose tracks are listed, or null on the overview. */
    @Nullable
    private String openGroup;

    private static final int FAVORITE_COLOR = Color.parseColor("#FF4E6A");

    /** How the catalogue is ordered, remembered between visits. */
    private enum SortOrder {
        RECENT, ARTIST, TITLE, ALBUM, DURATION, SIZE;

        static SortOrder saved() {
            try {
                return valueOf(Settings.DOWNLOADS_SORT.get());
            } catch (Exception ignored) {
                return ARTIST;
            }
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        musicRoot = new File(getActivity().getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Morphe");
        current = this;

        FrameLayout frame = new FrameLayout(getActivity());
        frame.setBackgroundColor(background());

        LinearLayout root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background());

        // The header stays out of the list, so typing a query does not rebuild the search bar.
        root.addView(createHeader(), new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(getActivity());
        songsList = new LinearLayout(getActivity());
        songsList.setOrientation(LinearLayout.VERTICAL);
        songsList.setPadding(dp(8), 0, dp(8), dp(10));
        scroll.addView(songsList);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        showTracks();

        miniPlayer = createMiniPlayer();
        miniPlayer.setVisibility(View.GONE);
        LinearLayout.LayoutParams miniParams = new LinearLayout.LayoutParams(-1, -2);
        miniParams.setMargins(dp(MINI_MARGIN_DP), dp(4), dp(MINI_MARGIN_DP), dp(MINI_MARGIN_DP));
        root.addView(miniPlayer, miniParams);
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));

        // The full player lies over everything, including the toolbar once it is open.
        fullPlayer = new OfflineFullPlayer(getActivity(), this);
        frame.addView(fullPlayer, new FrameLayout.LayoutParams(-1, -1));
        return frame;
    }

    @Override
    public void onStart() {
        super.onStart();
        OfflinePlaybackService.addListener(this);
        // If Android killed the app, the queue comes back here, paused where it was.
        OfflinePlaybackService.restoreIfNeeded(getActivity());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (current == this) current = null;
        if (getActivity() != null && getActivity().isFinishing()) {
            app.morphe.extension.music.patches.DownloadsPatch.onLocalDownloadsClosed();
        }
    }

    @Override
    public void onStop() {
        OfflinePlaybackService.removeListener(this);
        super.onStop();
    }

    /**
     * Reads the catalogue from disk. Every other method works off this snapshot.
     */
    private void loadTracks() {
        tracks = new ArrayList<>();
        favorites = OfflineFavorites.load(musicRoot);
        File[] files = musicRoot.listFiles(file -> file.isFile()
                && (file.getName().endsWith(".webm") || file.getName().endsWith(".m4a")));
        if (files == null) return;

        for (File file : files) {
            tracks.add(OfflineTrack.load(file));
        }
        tracks.sort(comparator());
    }

    private Comparator<OfflineTrack> comparator() {
        Comparator<OfflineTrack> byTitle =
                Comparator.comparing(track -> track.displayTitle().toLowerCase(Locale.ROOT));

        Comparator<OfflineTrack> order = switch (SortOrder.saved()) {
            case RECENT -> Comparator.comparingLong(
                    (OfflineTrack track) -> track.audioFile().lastModified()).reversed();
            case ALBUM -> Comparator.comparing(
                    (OfflineTrack track) -> track.album().toLowerCase(Locale.ROOT)).thenComparing(byTitle);
            case DURATION -> Comparator.comparingInt(OfflineTrack::durationSeconds).reversed();
            case TITLE -> byTitle;
            case SIZE -> Comparator.comparingLong(
                    (OfflineTrack track) -> track.audioFile().length()).reversed();
            default -> Comparator.comparing(
                    (OfflineTrack track) -> track.displayArtist().toLowerCase(Locale.ROOT))
                    .thenComparing(byTitle);
        };

        if (Settings.DOWNLOADS_SORT_REVERSED.get()) order = order.reversed();
        if (Settings.DOWNLOADS_FAVORITES_FIRST.get()) {
            // False sorts before true, so the tracks that are favorites come first.
            Comparator<OfflineTrack> notFavorite = Comparator.comparing(
                    (OfflineTrack track) -> !favorites.contains(track.videoId()));
            order = notFavorite.thenComparing(order);
        }
        return order;
    }

    private boolean matchesQuery(OfflineTrack track) {
        if (groupMode == GroupMode.FAVORITES && !favorites.contains(track.videoId())) return false;
        if (query.isEmpty()) return true;
        return track.displayTitle().toLowerCase(Locale.ROOT).contains(query)
                || track.displayArtist().toLowerCase(Locale.ROOT).contains(query);
    }

    /**
     * The search bar and the summary sit above the list, so they survive a redraw of the rows.
     */
    private LinearLayout createHeader() {
        LinearLayout header = new LinearLayout(getActivity());
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(8), dp(16), dp(6));

        // Hidden until asked for, the way the settings screen reveals its own search.
        searchBar = CustomDialog.createSearchBar(getActivity(),
                str("morphe_music_downloads_search_hint"), text -> {
                    query = text.trim().toLowerCase(Locale.ROOT);
                    showTracks();
                });
        searchBar.setVisibility(View.GONE);
        searchBar.setAlpha(0f);
        header.addView(searchBar, new LinearLayout.LayoutParams(-1, 0));

        LinearLayout line = new LinearLayout(getActivity());
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(8), 0, dp(4));

        heroCover = new FrameLayout(getActivity());
        DownloadsUi.roundCorners(heroCover, dp(ARTWORK_CORNER_DP + 4));
        line.addView(heroCover, new LinearLayout.LayoutParams(dp(HERO_COVER_DP), dp(HERO_COVER_DP)));

        LinearLayout heroLabels = new LinearLayout(getActivity());
        heroLabels.setOrientation(LinearLayout.VERTICAL);
        heroLabels.setPadding(dp(14), 0, dp(4), 0);

        heroTitle = text("", 20, foreground());
        heroTitle.setTypeface(Typeface.DEFAULT_BOLD);
        heroTitle.setSingleLine(true);
        heroLabels.addView(heroTitle);

        summary = text("", 13, secondary());
        summary.setSingleLine(true);
        summary.setEllipsize(TextUtils.TruncateAt.END);
        heroLabels.addView(summary);
        line.addView(heroLabels, new LinearLayout.LayoutParams(0, -2, 1));

        ImageButton search = icon(BaseSearchViewController.getSearchIconDrawable(),
                str("morphe_music_downloads_search_hint"));
        search.setOnClickListener(v -> toggleSearch());
        line.addView(search, new LinearLayout.LayoutParams(dp(40), dp(40)));

        ImageButton sort = icon("yt_outline_experimental_sort_vd_theme_24",
                str("morphe_music_downloads_sort"));
        sort.setOnClickListener(v -> showSortMenu(sort));
        line.addView(sort, new LinearLayout.LayoutParams(dp(40), dp(40)));

        ImageButton deleteAll = icon("yt_outline_experimental_trash_can_vd_theme_24",
                str("morphe_music_downloads_delete_all"));
        deleteAll.setOnClickListener(v -> confirmDeleteAll());
        line.addView(deleteAll, new LinearLayout.LayoutParams(dp(40), dp(40)));

        header.addView(line, new LinearLayout.LayoutParams(-1, -2));
        header.addView(createTabs(), new LinearLayout.LayoutParams(-1, -2));
        actionRow = createActionRow();
        header.addView(actionRow, new LinearLayout.LayoutParams(-1, -2));

        // A thin line between the controls and the list.
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(-1, dp(1));
        lineParams.topMargin = dp(8);
        header.addView(DownloadsUi.hairline(getActivity()), lineParams);
        return header;
    }

    /** Filter chips and the shuffle button, like the chips the app shows above its own lists. */
    private LinearLayout createActionRow() {
        LinearLayout row = new LinearLayout(getActivity());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(4));

        // Tells how the list is ordered, and opens the same menu as the sort button.
        sortChip = chip("");
        sortChip.setSingleLine(true);
        sortChip.setEllipsize(TextUtils.TruncateAt.END);
        sortChip.setOnClickListener(v -> showSortMenu(sortChip));
        row.addView(sortChip);

        View spacer = new View(getActivity());
        row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));

        ImageButton shuffle = icon("yt_outline_experimental_shuffle_vd_theme_24",
                str("morphe_music_downloads_shuffle"));
        shuffle.setBackground(DownloadsUi.circleRipple(
                DownloadsWidgets.ring(DownloadsUi.withAlpha(foreground(), 80), dp(1))));
        shuffle.setOnClickListener(v -> playShuffled());
        row.addView(shuffle, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        // The big round play button of the playlist pages of the app.
        ImageButton play = icon("yt_fill_experimental_play_vd_theme_24",
                str("morphe_music_downloads_play"));
        play.setColorFilter(background());
        play.setScaleType(ImageView.ScaleType.FIT_CENTER);
        play.setPadding(dp(14), dp(14), dp(14), dp(14));
        play.setBackground(DownloadsUi.circleRipple(DownloadsUi.oval(foreground())));
        play.setOnClickListener(v -> playAll());
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(PLAY_BUTTON_DP), dp(PLAY_BUTTON_DP));
        playParams.leftMargin = dp(10);
        row.addView(play, playParams);

        updateSortChip();
        return row;
    }

    private TextView chip(String label) {
        TextView view = text(label, 14, foreground());
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setPadding(dp(14), dp(7), dp(14), dp(7));
        view.setClickable(true);
        DownloadsMotion.pressable(view, 0.9f);
        return view;
    }

    private void updateSortChip() {
        String label = str(sortKey(SortOrder.saved()));
        if (Settings.DOWNLOADS_SORT_REVERSED.get()) {
            label += " \u2022 " + str("morphe_music_downloads_sort_reversed");
        }
        sortChip.setText(label);
        styleChip(sortChip, false);
    }

    private static String sortKey(SortOrder order) {
        return switch (order) {
            case RECENT -> "morphe_music_downloads_sort_recent";
            case ARTIST -> "morphe_music_downloads_sort_artist";
            case TITLE -> "morphe_music_downloads_sort_title";
            case ALBUM -> "morphe_music_downloads_sort_album";
            case DURATION -> "morphe_music_downloads_sort_duration";
            case SIZE -> "morphe_music_downloads_sort_size";
        };
    }

    /** A menu entry that is a switch: it ends with a check mark while it is on. */
    private static String checked(boolean on, String label) {
        return on ? label + "  \u2713" : label;
    }

    private void styleChip(TextView chip, boolean selected) {
        // Like the chips of the app: filled with the text color when on, a pale fill when off.
        chip.setBackground(DownloadsUi.rippleRounded(selected
                ? foreground() : DownloadsUi.withAlpha(foreground(), 26), dp(CHIP_CORNER_DP)));
        chip.setTextColor(selected ? background() : foreground());
    }

    /** Plays everything the list currently shows, in a random order. */
    /** Plays what the list shows, in a random order, starting from a random track. */
    private void playShuffled() {
        if (queuePaths.isEmpty()) return;
        OfflinePlaybackService.setShuffle(true);
        startQueue(new ArrayList<>(queuePaths), (int) (Math.random() * queuePaths.size()));
    }

    /** Plays what the list shows from its first track, in order. */
    private void playAll() {
        if (queuePaths.isEmpty()) return;
        OfflinePlaybackService.setShuffle(false);
        startQueue(new ArrayList<>(queuePaths), 0);
    }

    /** Opens the search field, or closes it and drops the query. */
    private void toggleSearch() {
        final boolean opening = searchBar.getVisibility() != View.VISIBLE;
        InputMethodManager keyboard = (InputMethodManager)
                getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);

        if (opening) {
            searchBar.setVisibility(View.VISIBLE);
            slideSearchBar(dp(SEARCH_BAR_HEIGHT_DP));
            searchBar.animate().alpha(1f).setDuration(SEARCH_ANIMATION_MILLISECONDS).start();

            searchBar.requestFocus();
            if (keyboard != null) keyboard.showSoftInput(searchBar, InputMethodManager.SHOW_IMPLICIT);
            return;
        }

        if (keyboard != null) keyboard.hideSoftInputFromWindow(searchBar.getWindowToken(), 0);
        searchBar.setText("");
        searchBar.clearFocus();

        slideSearchBar(0);
        searchBar.animate().alpha(0f).setDuration(SEARCH_ANIMATION_MILLISECONDS)
                .withEndAction(() -> searchBar.setVisibility(View.GONE)).start();
    }

    /** The height is animated by hand, since a collapsing child otherwise jumps the list. */
    private void slideSearchBar(int target) {
        ValueAnimator animator = ValueAnimator.ofInt(searchBar.getHeight(), target);
        animator.setDuration(SEARCH_ANIMATION_MILLISECONDS + 120);
        animator.setInterpolator(DownloadsMotion.ENTER);
        animator.addUpdateListener(value -> {
            searchBar.getLayoutParams().height = (int) value.getAnimatedValue();
            searchBar.requestLayout();
        });
        animator.start();
    }

    private void showSortMenu(View anchor) {
        final SortOrder[] orders = SortOrder.values();
        final int reverseItem = orders.length;
        final int favoritesFirstItem = orders.length + 1;

        String[] items = new String[orders.length + 2];
        for (int i = 0; i < orders.length; i++) {
            items[i] = str(sortKey(orders[i]));
        }
        items[reverseItem] = checked(Settings.DOWNLOADS_SORT_REVERSED.get(),
                str("morphe_music_downloads_sort_reverse"));
        items[favoritesFirstItem] = checked(Settings.DOWNLOADS_FAVORITES_FIRST.get(),
                str("morphe_music_downloads_sort_favorites_first"));

        showMenu(anchor, items, SortOrder.saved().ordinal(), position -> {
            if (position == reverseItem) {
                Settings.DOWNLOADS_SORT_REVERSED.save(!Settings.DOWNLOADS_SORT_REVERSED.get());
            } else if (position == favoritesFirstItem) {
                Settings.DOWNLOADS_FAVORITES_FIRST.save(!Settings.DOWNLOADS_FAVORITES_FIRST.get());
            } else {
                // A new criterion starts from its usual direction.
                if (orders[position] != SortOrder.saved()) Settings.DOWNLOADS_SORT_REVERSED.save(false);
                Settings.DOWNLOADS_SORT.save(orders[position].name());
            }
            animateList = true;
            showTracks();
        });
    }

    @Nullable
    private OfflineTrack findByPath(String path) {
        if (path.isEmpty()) return null;
        for (OfflineTrack track : tracks) {
            if (track.audioFile().getAbsolutePath().equals(path)) return track;
        }
        // A track streamed into the queue has no row in the list, but it has its own metadata.
        if (LocalDownloadManager.isStreamPath(path) && new File(path).isFile()) {
            return OfflineTrack.load(new File(path));
        }
        return null;
    }

    private void showTracks() {
        loadTracks();
        songsList.removeAllViews();
        rowViews.clear();
        rowByPath.clear();
        playingEqualizer = null;
        queuePaths = new ArrayList<>();
        final boolean animate = animateList;
        animateList = false;
        updateChrome();
        updateTabs();
        updateSortChip();
        updateHero();

        // Albums and artists first show a grid, and a tap on one lists its tracks.
        if (isOverview()) {
            showGroups(animate);
            return;
        }
        final List<View> entering = new ArrayList<>();
        if (openGroup != null) {
            View header = groupHeader();
            songsList.addView(header);
            entering.add(header);
        }

        for (OfflineTrack track : tracks) {
            if (!matchesQuery(track)) continue;
            if (openGroup != null && !groupKey(track).equalsIgnoreCase(openGroup)) continue;
            final String path = track.audioFile().getAbsolutePath();
            queuePaths.add(path);
            View item = swipeable(track, songRow(track));
            songsList.addView(item);
            rowByPath.put(path, item);
            entering.add(item);
        }

        if (queuePaths.isEmpty()) {
            View empty = emptyView();
            songsList.addView(empty);
            if (animate) DownloadsMotion.popIn(empty, 0.9f);
            return;
        }
        markPlayingRow();
        if (animate) DownloadsMotion.staggerIn(entering, dp(26));
    }

    /** The cover mosaic, the number of tracks and their weight, for what the screen is listing. */
    private void updateHero() {
        List<OfflineTrack> scope = new ArrayList<>();
        for (OfflineTrack track : tracks) {
            if (groupMode == GroupMode.FAVORITES && !favorites.contains(track.videoId())) continue;
            if (openGroup == null || groupKey(track).equalsIgnoreCase(openGroup)) scope.add(track);
        }

        long totalBytes = 0;
        long totalSeconds = 0;
        for (OfflineTrack track : scope) {
            totalBytes += track.audioFile().length();
            totalSeconds += Math.max(0, track.durationSeconds());
        }
        heroTitle.setText(str("morphe_music_downloads_track_count", scope.size()));
        summary.setText(scope.isEmpty() ? "" : str("morphe_music_downloads_hero_subtitle",
                formatSize(totalBytes), formatTotal(totalSeconds)));
        updateHeroCover(scope);
    }

    private void updateHeroCover(List<OfflineTrack> scope) {
        List<OfflineTrack> withCover = new ArrayList<>();
        StringBuilder signature = new StringBuilder();
        for (OfflineTrack track : scope) {
            if (!track.artworkFile().isFile()) continue;
            withCover.add(track);
            signature.append(track.audioFile().getAbsolutePath()).append('|');
            if (withCover.size() == 4) break;
        }
        // Typing in the search bar redraws the list, but the covers stay the same.
        if (signature.toString().equals(heroSignature) && heroCover.getChildCount() > 0) return;
        heroSignature = signature.toString();

        List<Bitmap> covers = new ArrayList<>();
        for (OfflineTrack track : withCover) {
            Bitmap cover = track.artwork(HERO_ARTWORK_PIXELS);
            if (cover != null) covers.add(cover);
        }

        heroCover.removeAllViews();
        heroCover.addView(covers.isEmpty()
                        ? DownloadsUi.artwork(getActivity(), null, 0)
                        : DownloadsWidgets.mosaic(getActivity(), covers),
                new FrameLayout.LayoutParams(-1, -1));
    }

    private View emptyView() {
        final boolean noFavorites = groupMode == GroupMode.FAVORITES && query.isEmpty() && !tracks.isEmpty();
        TextView empty = text(str(tracks.isEmpty()
                ? "morphe_music_downloads_empty"
                : noFavorites ? "morphe_music_downloads_no_favorites"
                : "morphe_music_downloads_nothing_found"), 16, secondary());
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dp(80), 0, 0);
        return empty;
    }

    // ------------------------------------------------------------------ albums and artists

    private View createTabs() {
        LinearLayout row = new LinearLayout(getActivity());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(2));

        tabSongs = chip(str("morphe_music_downloads_tab_songs"));
        tabSongs.setOnClickListener(v -> setGroupMode(GroupMode.SONGS));
        row.addView(tabSongs);

        tabFavorites = chip(str("morphe_music_downloads_filter_favorites"));
        tabFavorites.setOnClickListener(v -> setGroupMode(GroupMode.FAVORITES));
        LinearLayout.LayoutParams favoritesParams = new LinearLayout.LayoutParams(-2, -2);
        favoritesParams.leftMargin = dp(8);
        row.addView(tabFavorites, favoritesParams);

        tabAlbums = chip(str("morphe_music_downloads_tab_albums"));
        tabAlbums.setOnClickListener(v -> setGroupMode(GroupMode.ALBUMS));
        LinearLayout.LayoutParams albumParams = new LinearLayout.LayoutParams(-2, -2);
        albumParams.leftMargin = dp(8);
        row.addView(tabAlbums, albumParams);

        tabArtists = chip(str("morphe_music_downloads_tab_artists"));
        tabArtists.setOnClickListener(v -> setGroupMode(GroupMode.ARTISTS));
        LinearLayout.LayoutParams artistParams = new LinearLayout.LayoutParams(-2, -2);
        artistParams.leftMargin = dp(8);
        row.addView(tabArtists, artistParams);

        updateTabs();

        // Four chips do not always fit on a narrow screen, so the row can be scrolled.
        HorizontalScrollView scroll = new HorizontalScrollView(getActivity());
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row);
        return scroll;
    }

    private void updateTabs() {
        styleChip(tabSongs, groupMode == GroupMode.SONGS);
        styleChip(tabFavorites, groupMode == GroupMode.FAVORITES);
        tabFavorites.setText(str("morphe_music_downloads_filter_favorites") + " " + countFavorites());
        styleChip(tabAlbums, groupMode == GroupMode.ALBUMS);
        styleChip(tabArtists, groupMode == GroupMode.ARTISTS);
    }

    private void setGroupMode(GroupMode mode) {
        if (mode == groupMode && openGroup == null) return;
        groupMode = mode;
        openGroup = null;
        animateList = true;
        updateTabs();
        showTracks();
    }

    /** The play and filter buttons only make sense above a list of tracks. */
    private void updateChrome() {
        if (actionRow == null) return;
        actionRow.setVisibility(isOverview() ? View.GONE : View.VISIBLE);
    }

    /** The grid of albums or artists, before one of them is opened. */
    private boolean isOverview() {
        return (groupMode == GroupMode.ALBUMS || groupMode == GroupMode.ARTISTS) && openGroup == null;
    }

    private int countFavorites() {
        int count = 0;
        for (OfflineTrack track : tracks) {
            if (favorites.contains(track.videoId())) count++;
        }
        return count;
    }

    private String groupKey(OfflineTrack track) {
        if (groupMode == GroupMode.ALBUMS) {
            return track.album().isBlank()
                    ? str("morphe_music_downloads_unknown_album") : track.album().trim();
        }
        return track.displayArtist().trim();
    }

    private void showGroups(boolean animate) {
        Map<String, List<OfflineTrack>> groups = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (OfflineTrack track : tracks) {
            if (!matchesQuery(track)) continue;
            groups.computeIfAbsent(groupKey(track), key -> new ArrayList<>()).add(track);
        }
        if (groups.isEmpty()) {
            songsList.addView(emptyView());
            return;
        }

        LinearLayout line = null;
        int column = 0;
        final List<View> cards = new ArrayList<>();
        for (Map.Entry<String, List<OfflineTrack>> entry : groups.entrySet()) {
            if (column == 0) {
                line = new LinearLayout(getActivity());
                songsList.addView(line, new LinearLayout.LayoutParams(-1, -2));
            }
            View card = groupCard(entry.getKey(), entry.getValue());
            line.addView(card, new LinearLayout.LayoutParams(0, -2, 1));
            cards.add(card);
            column = (column + 1) % 2;
        }
        if (animate) DownloadsMotion.staggerIn(cards, dp(30));
        // An odd last card keeps the width of the others.
        if (column == 1) line.addView(new View(getActivity()), new LinearLayout.LayoutParams(0, 1, 1));
    }

    private View groupCard(String key, List<OfflineTrack> group) {
        final boolean artists = groupMode == GroupMode.ARTISTS;

        LinearLayout card = new LinearLayout(getActivity());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(8), dp(8), dp(8), dp(10));
        card.setBackground(DownloadsUi.rippleCard(DownloadsUi.withAlpha(foreground(), 14),
                DownloadsUi.withAlpha(foreground(), 34), dp(1), dp(ROW_CORNER_DP)));
        DownloadsMotion.pressable(card, 0.95f);
        card.setOnClickListener(v -> {
            openGroup = key;
            animateList = true;
            showTracks();
        });

        Bitmap cover = null;
        for (OfflineTrack track : group) {
            cover = track.artwork(GROUP_ARTWORK_PIXELS);
            if (cover != null) break;
        }
        DownloadsUi.SquareLayout square = new DownloadsUi.SquareLayout(getActivity());
        // Artists are shown in a circle, like on the artist pages of the app.
        square.addView(DownloadsUi.artwork(getActivity(), cover, artists ? 0 : dp(8)),
                new FrameLayout.LayoutParams(-1, -1));
        if (artists) DownloadsUi.makeRound(square);
        else DownloadsUi.roundCorners(square, dp(8));
        card.addView(square, new LinearLayout.LayoutParams(-1, -2));

        TextView name = text(key, 14, foreground());
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setGravity(artists ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
        name.setPadding(0, dp(8), 0, 0);
        card.addView(name, new LinearLayout.LayoutParams(-1, -2));

        TextView count = text(str("morphe_music_downloads_track_count", group.size()), 13, secondary());
        count.setSingleLine(true);
        count.setGravity(artists ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
        card.addView(count, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    /** The name of the opened album or artist, with a way back to the overview. */
    private View groupHeader() {
        LinearLayout row = new LinearLayout(getActivity());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), dp(8), dp(6));

        ImageButton back = icon("yt_outline_experimental_arrow_left_vd_theme_24",
                str("morphe_music_downloads_back"));
        back.setOnClickListener(v -> closeGroup());
        row.addView(back, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        TextView name = text(openGroup == null ? "" : openGroup, 20, foreground());
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setPadding(dp(8), 0, 0, 0);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private void closeGroup() {
        openGroup = null;
        animateList = true;
        showTracks();
    }

    // ------------------------------------------------------------------ back button, toolbar

    /**
     * Injection point of the activity: lets the downloads screen use the back button before
     * the activity closes, to close the player, a sheet or an opened album.
     *
     * @return Whether the back button was used up.
     */
    public static boolean handleBack() {
        LocalDownloadsFragment fragment = current;
        return fragment != null && fragment.consumeBack();
    }

    private boolean consumeBack() {
        if (fullPlayer != null && fullPlayer.handleBack()) return true;
        if (openGroup != null) {
            closeGroup();
            return true;
        }
        return false;
    }

    @Nullable
    @Override
    public OfflineTrack findTrack(String path) {
        return findByPath(path);
    }

    @Override
    public boolean isFavorite(OfflineTrack track) {
        return favorites.contains(track.videoId());
    }

    @Override
    public void toggleFavorite(OfflineTrack track) {
        toggleFavorite(track, null);
    }

    /** The toolbar is hidden while the player is open, so the player can use the whole screen. */
    @Override
    public void onShowingChanged(boolean showing) {
        View view = getView();
        if (view == null || !(view.getParent() instanceof ViewGroup container)) return;
        if (!(container.getParent() instanceof ViewGroup layout) || layout.getChildCount() < 2) return;
        layout.getChildAt(0).setVisibility(showing ? View.GONE : View.VISIBLE);
    }

    private View songRow(OfflineTrack track) {
        LinearLayout row = new LinearLayout(getActivity());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(7), dp(8), dp(7));
        rowViews.add(row);

        // The bars are only shown on the cover of the track that is playing.
        FrameLayout cover = new FrameLayout(getActivity());
        cover.addView(artworkView(track.artwork(ROW_ARTWORK_PIXELS)),
                new FrameLayout.LayoutParams(-1, -1));
        DownloadsWidgets.EqualizerView equalizer =
                new DownloadsWidgets.EqualizerView(getActivity(), foreground());
        equalizer.setVisibility(View.GONE);
        cover.addView(equalizer, new FrameLayout.LayoutParams(-1, -1));
        DownloadsUi.roundCorners(cover, dp(ARTWORK_CORNER_DP));
        row.setTag(equalizer);
        row.addView(cover, new LinearLayout.LayoutParams(dp(56), dp(56)));

        LinearLayout labels = new LinearLayout(getActivity());
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(14), 0, dp(8), 0);

        TextView name = text(track.displayTitle(), 16, foreground());
        name.setSingleLine(true);

        TextView detail = text(str("morphe_music_downloads_track_subtitle",
                track.displayArtist(), formatDuration(track.durationSeconds()),
                formatSize(track.audioFile().length())), 13, secondary());
        detail.setSingleLine(true);

        labels.addView(name);
        labels.addView(detail);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        ImageButton heart = heartButton(favorites.contains(track.videoId()));
        heart.setOnClickListener(v -> toggleFavorite(track, heart));
        row.addView(heart, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        // A visible 'Play next', so the swipe and the menu are not the only ways to get it.
        ImageButton next = icon("yt_outline_experimental_queue_vd_theme_24",
                str("morphe_music_downloads_play_next"));
        next.setOnClickListener(v -> playNext(track));
        row.addView(next, new LinearLayout.LayoutParams(dp(PLAY_NEXT_BUTTON_DP), dp(ICON_BUTTON_DP)));

        ImageButton menu = icon("yt_outline_experimental_overflow_vertical_vd_theme_24",
                str("morphe_music_downloads_actions_for", track.displayTitle()));
        menu.setOnClickListener(v -> showTrackMenu(menu, track));
        row.addView(menu, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        row.setOnClickListener(v -> play(track));
        DownloadsMotion.pressable(row, 0.97f);
        return row;
    }

    /** Dragging a row to the right queues it after the track playing, like in the stock app. */
    private View swipeable(OfflineTrack track, View row) {
        final int page = background();
        return new SwipeRowLayout(getActivity(), row, page,
                mix(page, foreground(), 0.12f), mix(page, foreground(), 0.26f),
                foreground(), dp(ROW_CORNER_DP), dp(SWIPE_THRESHOLD_DP),
                str("morphe_music_downloads_play_next"), () -> playNext(track));
    }

    private void playNext(OfflineTrack track) {
        final String path = track.audioFile().getAbsolutePath();

        ArrayList<String> queue = OfflinePlaybackService.playNext(path);
        if (queue == null) {
            // Nothing is playing, so there is no track to come after: it simply starts.
            startQueue(new ArrayList<>(Collections.singletonList(path)), 0);
            return;
        }
        // The track playing cannot come after itself, the queue did not change.
        if (path.equals(playingPath)) return;

        activeQueue = queue;
        updateSkipButtons(playingPath);
        Utils.showToastShort(str("morphe_music_downloads_play_next_added", track.displayTitle()));
    }

    /** Next is only usable when the queue has a track after the playing one, or repeats. */
    private void updateSkipButtons(String path) {
        final int index = OfflinePlaybackService.queueCurrentIndex();
        final int size = OfflinePlaybackService.queueSnapshot().size();
        final boolean repeatAll = OfflinePlaybackService.getRepeatMode()
                == OfflinePlaybackService.REPEAT_ALL;
        setButtonEnabled(miniNext, (index >= 0 && index + 1 < size) || (repeatAll && size > 0));
    }

    /** @param amount Share of {@code over} in the result, from 0 to 1. */
    private static int mix(int base, int over, float amount) {
        return Color.rgb(
                Math.round(Color.red(base) * (1 - amount) + Color.red(over) * amount),
                Math.round(Color.green(base) * (1 - amount) + Color.green(over) * amount),
                Math.round(Color.blue(base) * (1 - amount) + Color.blue(over) * amount));
    }

    private ImageButton heartButton(boolean favorite) {
        ImageButton heart = icon("yt_outline_experimental_heart_vd_theme_24",
                str("morphe_music_downloads_favorite"));
        setHeart(heart, favorite);
        heart.setStateListAnimator(null);
        return heart;
    }

    private void setHeart(ImageButton heart, boolean favorite) {
        DownloadsUi.setIcon(heart, favorite
                        ? "yt_fill_experimental_heart_vd_theme_24"
                        : "yt_outline_experimental_heart_vd_theme_24",
                favorite ? FAVORITE_COLOR : secondary());
    }

    private void toggleFavorite(OfflineTrack track, @Nullable ImageButton heart) {
        final boolean favorite = OfflineFavorites.toggle(musicRoot, track.videoId());
        if (favorite) favorites.add(track.videoId());
        else favorites.remove(track.videoId());

        if (heart != null) {
            setHeart(heart, favorite);
            DownloadsMotion.popIn(heart, favorite ? 0.35f : 0.75f);
        }
        if (track.audioFile().getAbsolutePath().equals(playingPath)) {
            setHeart(miniHeart, favorite);
            if (heart == null) DownloadsMotion.popIn(miniHeart, favorite ? 0.35f : 0.75f);
            if (fullPlayer != null) fullPlayer.refreshHeart();
        }
        // Leaving a track in the Favorites list after it was removed from there would be confusing.
        updateTabs();
        if (groupMode == GroupMode.FAVORITES && !favorite) showTracks();
    }

    /** Shows the bars on the cover of the track being played, like the app does. */
    private void markPlayingRow() {
        int playingIndex = queuePaths.indexOf(playingPath);
        playingEqualizer = null;
        for (int i = 0; i < rowViews.size(); i++) {
            View row = rowViews.get(i);
            row.setBackground(rowBackground(i == playingIndex));
            if (row.getTag() instanceof DownloadsWidgets.EqualizerView equalizer) {
                final boolean current = i == playingIndex;
                equalizer.setVisibility(current ? View.VISIBLE : View.GONE);
                equalizer.setPlaying(current && showingPause);
                if (current) playingEqualizer = equalizer;
            }
        }
    }

    /** The ripple is masked to the card, so it does not spill into the neighboring rows. */
    private Drawable rowBackground(boolean playing) {
        // The track playing is outlined softly, so it can be found at a glance.
        if (playing) {
            return DownloadsUi.rippleCard(DownloadsUi.withAlpha(foreground(), 22),
                    DownloadsUi.withAlpha(foreground(), 44), dp(1), dp(ROW_CORNER_DP));
        }
        return new RippleDrawable(ColorStateList.valueOf(rippleColor()), null,
                CustomDialog.createRoundedBackground(ROW_CORNER_DP, Color.WHITE));
    }

    private static int rippleColor() {
        final int foreground = foreground();
        return Color.argb(60, Color.red(foreground),
                Color.green(foreground), Color.blue(foreground));
    }

    /**
     * The bar of the stock app: a thin progress line on top, the cover, the title and the
     * buttons. A tap or a swipe up opens the full player, a swipe sideways changes track.
     */
    @SuppressLint("ClickableViewAccessibility")
    private LinearLayout createMiniPlayer() {
        final int slop = ViewConfiguration.get(getActivity()).getScaledTouchSlop();
        // Pulling the bar up takes the touch away from its buttons, and the full player
        // follows the finger up, like the stock one.
        LinearLayout outer = new LinearLayout(getActivity()) {
            private float downX;
            private float downY;
            private boolean pulling;

            @Override
            public boolean onInterceptTouchEvent(MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        pulling = false;
                        return false;
                    case MotionEvent.ACTION_MOVE: {
                        final float up = downY - event.getRawY();
                        if (up > slop && up > Math.abs(event.getRawX() - downX) * 1.2f) pulling = true;
                        return pulling;
                    }
                    default:
                        return false;
                }
            }

            @Override
            public boolean onTouchEvent(MotionEvent event) {
                if (!pulling || fullPlayer == null) return false;
                final float up = downY - event.getRawY();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_MOVE:
                        fullPlayer.dragOpen(up);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        fullPlayer.settleOpen(event.getActionMasked() == MotionEvent.ACTION_UP
                                && up > dp(MINI_PULL_DP));
                        pulling = false;
                        return true;
                    default:
                        return true;
                }
            }
        };
        outer.setOrientation(LinearLayout.VERTICAL);
        // A little lighter than the page, so the bar reads as a surface of its own.
        miniTint = mix(background(), foreground(), 0.09f);
        miniBackground = DownloadsUi.fill(miniTint, dp(MINI_CORNER_DP));
        miniBackground.setStroke(dp(1), DownloadsUi.withAlpha(foreground(), 36));
        outer.setBackground(miniBackground);
        DownloadsUi.roundCorners(outer, dp(MINI_CORNER_DP));
        outer.setElevation(dp(8));

        miniProgress = new ProgressBar(getActivity(), null, android.R.attr.progressBarStyleHorizontal);
        miniProgress.setProgressDrawable(DownloadsUi.roundedProgress(
                DownloadsUi.withAlpha(foreground(), 50), foreground(), dp(MINI_PROGRESS_DP)));
        miniProgress.setMax(1);
        LinearLayout.LayoutParams progressParams =
                new LinearLayout.LayoutParams(-1, dp(MINI_PROGRESS_DP));
        progressParams.setMargins(dp(16), dp(8), dp(16), 0);
        outer.addView(miniProgress, progressParams);

        LinearLayout line = new LinearLayout(getActivity());
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(12), dp(8), dp(4), dp(8));

        miniArtwork = artworkView(null);
        line.addView(miniArtwork, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout labels = new LinearLayout(getActivity());
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.setPadding(dp(12), 0, dp(4), 0);

        miniTitle = text("", 14, foreground());
        miniTitle.setSingleLine(true);
        miniTitle.setEllipsize(TextUtils.TruncateAt.END);
        miniTitle.setTypeface(Typeface.DEFAULT_BOLD);

        miniArtist = text("", 13, secondary());
        miniArtist.setSingleLine(true);
        miniArtist.setEllipsize(TextUtils.TruncateAt.END);

        labels.addView(miniTitle);
        labels.addView(miniArtist);
        line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        miniHeart = heartButton(false);
        miniHeart.setOnClickListener(v -> {
            OfflineTrack playing = findByPath(playingPath);
            if (playing != null) toggleFavorite(playing, null);
        });
        line.addView(miniHeart, new LinearLayout.LayoutParams(dp(MINI_BUTTON_DP), dp(MINI_BUTTON_DP)));

        miniPrevious = icon("yt_fill_experimental_skip_previous_vd_theme_24",
                str("morphe_music_downloads_previous"));
        miniPrevious.setOnClickListener(v -> {
            OfflinePlaybackService.skipPrevious(getActivity());
            DownloadsMotion.nudge(miniPrevious, -dp(10));
        });
        line.addView(miniPrevious, new LinearLayout.LayoutParams(dp(MINI_BUTTON_DP), dp(MINI_BUTTON_DP)));

        miniPlay = icon("yt_fill_experimental_play_vd_theme_24",
                str("morphe_music_downloads_play_pause"));
        // The morph drawable of the app is 48dp, so whatever the button shows is scaled down to
        // the 24dp of the icons beside it.
        miniPlay.setScaleType(ImageView.ScaleType.FIT_CENTER);
        miniPlay.setPadding(dp(10), dp(10), dp(10), dp(10));
        miniPlay.setOnClickListener(v -> OfflinePlaybackService.toggle(getActivity()));
        line.addView(miniPlay, new LinearLayout.LayoutParams(dp(MINI_PLAY_DP), dp(MINI_PLAY_DP)));

        miniNext = icon("yt_fill_experimental_skip_next_vd_theme_24",
                str("morphe_music_downloads_next"));
        miniNext.setOnClickListener(v -> {
            OfflinePlaybackService.skipNext(getActivity());
            DownloadsMotion.nudge(miniNext, dp(10));
        });
        line.addView(miniNext, new LinearLayout.LayoutParams(dp(MINI_BUTTON_DP), dp(MINI_BUTTON_DP)));

        line.setOnTouchListener(createMiniSwipeListener());
        outer.addView(line, new LinearLayout.LayoutParams(-1, -2));
        return outer;
    }

    /** Touches on the cover or the title, which the buttons do not use up. */
    private View.OnTouchListener createMiniSwipeListener() {
        final int slop = ViewConfiguration.get(getActivity()).getScaledTouchSlop();
        return new View.OnTouchListener() {
            private float downX;
            private float downY;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_UP: {
                        final float dx = event.getRawX() - downX;
                        final float dy = event.getRawY() - downY;
                        if (Math.abs(dx) > dp(MINI_SWIPE_DP) && Math.abs(dx) > Math.abs(dy)) {
                            if (dx < 0) OfflinePlaybackService.skipNext(getActivity());
                            else OfflinePlaybackService.skipPrevious(getActivity());
                        } else if (Math.abs(dx) < slop && Math.abs(dy) < slop) {
                            openFullPlayer();
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return false;
                }
            }
        };
    }

    private void openFullPlayer() {
        if (fullPlayer != null && miniPlayer.getVisibility() == View.VISIBLE) fullPlayer.show();
    }

    private void showTrackMenu(View anchor, OfflineTrack track) {
        String[] items = {
                str("morphe_music_downloads_play"),
                str("morphe_music_downloads_play_next"),
                str("morphe_music_downloads_favorite"),
                str("morphe_music_downloads_delete_track"),
        };
        showMenu(anchor, items, -1, position -> {
            if (position == 3) confirmDelete(track);
            else if (position == 2) {
                toggleFavorite(track, null);
                showTracks();
            } else if (position == 1) playNext(track);
            else play(track);
        });
    }

    /**
     * @param selected Index drawn as the active choice, or -1 when the items are plain actions.
     */
    private void showMenu(View anchor, String[] items, int selected, IntConsumer onPick) {
        ListPopupWindow popup = new ListPopupWindow(getActivity());
        popup.setAnchorView(anchor);
        popup.setDropDownGravity(Gravity.END);
        popup.setWidth(dp(MENU_WIDTH_DP));
        popup.setModal(true);
        popup.setBackgroundDrawable(menuBackground());
        // The list draws its own square selector, which would bleed past the rounded background.
        popup.setListSelector(new RippleDrawable(ColorStateList.valueOf(rippleColor()), null,
                CustomDialog.createRoundedBackground(MENU_CORNER_DP, Color.WHITE)));
        popup.setAdapter(new ArrayAdapter<>(getActivity(), 0, items) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView view = text(getItem(position), 15,
                        selected < 0 || position == selected ? foreground() : secondary());
                view.setPadding(dp(20), dp(14), dp(20), dp(14));
                return view;
            }
        });
        popup.setOnItemClickListener((parent, view, position, id) -> {
            popup.dismiss();
            onPick.accept(position);
        });
        popup.show();
        DownloadsMotion.animatePopup(popup, anchor);
    }

    /**
     * The surface color of a theme can sit very close to its background, so the menu is outlined
     * rather than left to rely on the two being far enough apart.
     */
    private Drawable menuBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(ThemeUtils.getDialogBackgroundColor());
        background.setCornerRadius(dp(MENU_CORNER_DP));
        background.setStroke(dp(1), rippleColor());
        return background;
    }

    private void confirmDelete(OfflineTrack track) {
        confirm(str("morphe_music_downloads_delete_track_title"),
                str("morphe_music_downloads_delete_track_message", track.displayTitle()),
                () -> deleteTrack(track));
    }

    private void confirmDeleteAll() {
        if (tracks.isEmpty()) return;
        confirm(str("morphe_music_downloads_delete_all_title"),
                str("morphe_music_downloads_delete_all_message"), this::deleteAll);
    }

    private void confirm(String title, String message, Runnable onConfirm) {
        CustomDialog.create(getActivity(), title, message, null,
                str("morphe_music_downloads_delete"), onConfirm, () -> {
                }, null, null, false).first.show();
    }

    private void deleteAll() {
        for (OfflineTrack track : new ArrayList<>(tracks)) {
            OfflineStorage.delete(track.audioFile());
            deleteSidecars(track.videoId());
        }
        OfflineStorage.delete(new File(musicRoot, "favorites.txt"));
        showTracks();
        Utils.showToastShort(str("morphe_music_downloads_all_deleted"));
    }

    private void deleteSidecars(String videoId) {
        OfflineStorage.delete(new File(musicRoot, videoId + ".json"));
        OfflineStorage.delete(new File(musicRoot, videoId + ".jpg"));
        OfflineStorage.delete(new File(musicRoot, videoId + ".webm.part"));
        OfflineStorage.delete(new File(musicRoot, videoId + ".m4a.part"));
    }

    private void deleteTrack(OfflineTrack track) {
        if (favorites.contains(track.videoId())) OfflineFavorites.toggle(musicRoot, track.videoId());
        final boolean deleted = track.audioFile().delete();
        deleteSidecars(track.videoId());

        if (!deleted) {
            Utils.showToastShort(str("morphe_music_downloads_track_delete_failed"));
            return;
        }
        final View item = rowByPath.get(track.audioFile().getAbsolutePath());
        if (item != null && item.getParent() == songsList) {
            DownloadsMotion.collapse(item, this::showTracks);
        } else {
            showTracks();
        }
        Utils.showToastShort(str("morphe_music_downloads_track_deleted"));
    }

    private void play(OfflineTrack track) {
        startQueue(new ArrayList<>(queuePaths),
                Math.max(0, queuePaths.indexOf(track.audioFile().getAbsolutePath())));
    }

    private void startQueue(List<String> paths, int queueIndex) {
        OfflineTrack track = findByPath(paths.get(queueIndex));
        if (track == null) return;

        try {
            ArrayList<String> queue = new ArrayList<>(paths);
            activeQueue = queue;

            Intent intent = new Intent(getActivity(), OfflinePlaybackService.class)
                    .setAction(OfflinePlaybackService.ACTION_PLAY_FILE)
                    .putExtra(OfflinePlaybackService.EXTRA_PATH, track.audioFile().getAbsolutePath())
                    .putExtra(OfflinePlaybackService.EXTRA_TITLE, track.displayTitle())
                    .putExtra(OfflinePlaybackService.EXTRA_ARTIST, track.displayArtist())
                    .putExtra(OfflinePlaybackService.EXTRA_ARTWORK_PATH,
                            track.artworkFile().getAbsolutePath())
                    .putStringArrayListExtra(OfflinePlaybackService.EXTRA_QUEUE, queue)
                    .putExtra(OfflinePlaybackService.EXTRA_QUEUE_INDEX, queueIndex);
            getActivity().startForegroundService(intent);

            applyTrackVisuals(track);
        } catch (Exception ex) {
            Logger.printException(() -> "Offline playback failed: " + track.audioFile(), ex);
            Utils.showToastShort(str("morphe_music_downloads_play_failed"));
        }
    }

    @Override
    public void onPlaybackChanged(String path, String title, boolean playing,
                                  int position, int duration) {
        if (getActivity() == null) return;

        getActivity().runOnUiThread(() -> {
            if (miniPlayer == null) return;
            final boolean wasVisible = miniPlayer.getVisibility() == View.VISIBLE;
            miniPlayer.setVisibility(title.isEmpty() ? View.GONE : View.VISIBLE);
            if (!wasVisible && !title.isEmpty()) DownloadsMotion.riseFromEdge(miniPlayer, dp(72));

            // Decoding the cover on every tick would read the disk twice a second.
            if (!path.equals(playingPath)) {
                playingPath = path;
                OfflineTrack track = findByPath(path);
                if (track != null) applyTrackVisuals(track);
                else miniTitle.setText(title);

                updateSkipButtons(path);
                setHeart(miniHeart, track != null && favorites.contains(track.videoId()));
                markPlayingRow();
            }

            // Shuffle, repeat and edits of the queue change what 'next' does.
            final int version = OfflinePlaybackService.getQueueVersion();
            if (version != lastQueueVersion) {
                lastQueueVersion = version;
                updateSkipButtons(path);
            }

            // This runs on every playback tick, so the icon only morphs when the state
            // actually flips while the bar is on screen to see it happen.
            updatePlayIcon(playing, wasVisible && playing != showingPause);
            if (playingEqualizer != null) playingEqualizer.setPlaying(playing);
            miniProgress.setMax(Math.max(1, duration));
            miniProgress.setProgress(position);

            if (fullPlayer != null) fullPlayer.onPlaybackChanged(path, title, playing, position, duration);
        });
    }

    private void updatePlayIcon(boolean playing, boolean morph) {
        showingPause = playing;
        if (morph && startIconMorph(playing)) return;

        miniPlay.setImageDrawable(ResourceUtils.getDrawable(playing
                ? "yt_fill_experimental_pause_vd_theme_24"
                : "yt_fill_experimental_play_vd_theme_24"));
    }

    /**
     * The app ships the drawable its own player uses to morph between the two icons.
     */
    private boolean startIconMorph(boolean playing) {
        String name = playing
                ? "player_play_pause_vector_transition"
                : "player_pause_play_vector_transition";

        int identifier = ResourceUtils.getDrawableIdentifier(name + "_delhi");
        if (identifier == 0) identifier = ResourceUtils.getDrawableIdentifier(name);

        Activity activity = getActivity();
        if (identifier == 0 || activity == null) return false;

        // It fills itself with a theme attribute of the player, so it has to be read with the
        // theme of this screen, which defines that attribute as white for the icon tint to work.
        Drawable drawable = activity.getDrawable(identifier);
        if (!(drawable instanceof AnimatedVectorDrawable morph)) return false;

        miniPlay.setImageDrawable(morph);
        morph.start();
        return true;
    }

    private void applyTrackVisuals(OfflineTrack track) {
        miniTitle.setText(track.displayTitle());
        miniArtist.setText(track.displayArtist());

        Bitmap bitmap = track.artwork(ROW_ARTWORK_PIXELS);
        tintMiniPlayer(bitmap);
        if (bitmap == null) return;

        miniArtwork.clearColorFilter();
        miniArtwork.setPadding(0, 0, 0, 0);
        miniArtwork.setImageBitmap(bitmap);
        miniArtwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
    }

    /** The bar takes a dark tone of the cover, like the mini player of the app does. */
    private void tintMiniPlayer(@Nullable Bitmap cover) {
        final int target = cover == null
                ? mix(background(), foreground(), 0.09f)
                : mix(DownloadsUi.dominantColor(cover), Color.BLACK, 0.25f);

        if (miniTintAnimator != null) miniTintAnimator.cancel();
        final int from = miniTint;
        miniTint = target;
        miniTintAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), from, target);
        miniTintAnimator.setDuration(300);
        miniTintAnimator.addUpdateListener(a -> miniBackground.setColor((int) a.getAnimatedValue()));
        miniTintAnimator.start();
    }

    private void setButtonEnabled(ImageButton button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : .35f);
    }

    private ImageView artworkView(@Nullable Bitmap bitmap) {
        ImageView view = new ImageView(getActivity());
        view.setBackgroundColor(Utils.adjustColorBrightness(foreground(), 0.2f));
        roundCorners(view);

        if (bitmap != null) {
            view.setImageBitmap(bitmap);
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else {
            view.setImageDrawable(
                    ResourceUtils.getDrawable("yt_fill_experimental_play_circle_vd_theme_24"));
            view.setColorFilter(foreground());
            view.setPadding(dp(16), dp(16), dp(16), dp(16));
        }
        return view;
    }

    /** Matches the softly rounded covers the app uses on the home feed. */
    private void roundCorners(View view) {
        final float radius = dp(ARTWORK_CORNER_DP);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View outlined, Outline outline) {
                outline.setRoundRect(0, 0, outlined.getWidth(), outlined.getHeight(), radius);
            }
        });
        view.setClipToOutline(true);
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(getActivity());
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private ImageButton icon(String drawable, String description) {
        ImageButton button = icon(ResourceUtils.getDrawable(drawable), description);
        // Icons of the app carry no color of their own.
        button.setColorFilter(foreground());
        return button;
    }

    /** For drawables that already carry the colors of Morphe. */
    private ImageButton icon(Drawable drawable, String description) {
        ImageButton button = new ImageButton(getActivity());
        button.setImageDrawable(drawable);
        button.setContentDescription(description);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));

        button.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor()),
                null, new ShapeDrawable(new OvalShape())));
        DownloadsMotion.pressable(button, 0.82f);
        return button;
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

    private static String formatDuration(int seconds) {
        if (seconds <= 0) return "";
        if (seconds < 3600) {
            return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        }
        return String.format(Locale.ROOT, "%d:%02d:%02d",
                seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    /** The length of a whole list: 48 min, or 3 h 20. */
    private static String formatTotal(long seconds) {
        final long minutes = Math.round(seconds / 60.0);
        if (minutes < 60) return minutes + " min";
        return String.format(Locale.ROOT, "%d h %02d", minutes / 60, minutes % 60);
    }

    private static String formatSize(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / 1048576.0);
    }
}
