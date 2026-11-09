/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.StringRef.str;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
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
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceFragment;
import android.util.LruCache;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;

/**
 * The offline library: tabs for the songs, the favorites and the artists, sort orders, a shuffle
 * button, import / export, and a compact player like the one of the stock app.
 */
@SuppressWarnings("deprecation")
public final class LocalDownloadsFragment extends PreferenceFragment
        implements OfflinePlaybackService.PlaybackListener {

    /** Set by the mini player of the app: the screen opens straight on the big player. */
    public static final String EXTRA_OPEN_PLAYER = "morphe_open_offline_player";

    private static final int REQUEST_EXPORT = 7101;
    private static final int REQUEST_IMPORT = 7102;

    private static final int SEEK_BAR_HEIGHT_DP = 3;

    /** The bar is drawn thin but stays easy to hit. */
    private static final int SEEK_BAR_TOUCH_DP = 18;
    private static final int SEEK_THUMB_DP = 12;

    private static final int SEARCH_BAR_HEIGHT_DP = 46;
    private static final long SEARCH_ANIMATION_MILLISECONDS = 180;

    /** Dimming the foreground gives a readable secondary on any background. */
    private static final float SECONDARY_DIM = 0.72f;

    /** How much the background of the playing row is lifted out of the surrounding one. */
    private static final float PLAYING_ROW_BRIGHTNESS = 1.35f;

    private static final int ROW_CORNER_DP = 8;

    /** Rows are 56dp, so a sample of the stored cover is already more than enough. */
    private static final int ROW_ARTWORK_PIXELS = 256;

    private static final int ARTWORK_CORNER_DP = 4;

    private static final int MENU_WIDTH_DP = 260;
    private static final int MENU_CORNER_DP = 12;

    private static final int PILL_HEIGHT_DP = 40;
    private static final int PILL_CORNER_DP = 20;
    private static final int CHIP_HEIGHT_DP = 36;

    /** Icon buttons are square, otherwise their round ripple is drawn as a stretched oval. */
    private static final int ICON_BUTTON_DP = 48;
    private static final int HEADER_ICON_DP = 40;

    private static final OfflineLibrary.Sort[] SORT_CHOICES = {
            OfflineLibrary.Sort.RECENT, OfflineLibrary.Sort.OLDEST, OfflineLibrary.Sort.TITLE,
            OfflineLibrary.Sort.ARTIST, OfflineLibrary.Sort.ALBUM, OfflineLibrary.Sort.DURATION,
            OfflineLibrary.Sort.SIZE,
    };

    /** Covers are decoded off the main thread, one at a time, and remembered while scrolling. */
    private static final LruCache<String, Bitmap> artworkCache = new LruCache<>(80);
    private static final ExecutorService artworkLoader = Executors.newSingleThreadExecutor();

    /** The screen that is open, so the back button can close its search or its artist page. */
    @Nullable
    private static LocalDownloadsFragment active;

    private enum Tab {
        TRACKS, FAVORITES, ARTISTS
    }

    private File musicRoot;
    private Tab tab = Tab.TRACKS;

    /** Name of the artist whose page is open, or null for the list of artists. */
    @Nullable
    private String openedArtist;
    private String query = "";

    /** Every downloaded track, read once per refresh and already sorted. */
    private List<OfflineTrack> allTracks = new ArrayList<>();

    /** What the list shows: tracks, or artists on the artists tab. */
    private List<Object> rows = new ArrayList<>();

    /** The tracks the Play and Shuffle buttons start, and the ones a row tap plays from. */
    private List<OfflineTrack> shownTracks = new ArrayList<>();

    private final List<TextView> tabChips = new ArrayList<>();
    private ListView listView;
    private RowsAdapter adapter;
    private TextView summary;
    private TextView emptyView;
    private EditText searchBar;
    private LinearLayout artistHeader;
    private TextView artistTitle;

    private ImageView miniArtwork;
    private TextView miniTitle;
    private TextView miniArtist;
    private ImageButton miniPlay;
    private ImageButton miniPrevious;
    private ImageButton miniNext;
    private SeekBar miniSeek;
    private LinearLayout miniPlayer;

    private boolean showingPause;
    private String playingPath = "";
    private boolean userSeeking;

    private final Runnable queueListener = this::updateNavButtons;

    /** Holds the page and, on top of it, the big player when it is open. */
    private FrameLayout screen;
    @Nullable
    private OfflinePlayerView playerView;
    /** The screen was opened on the big player: closing the player leaves the screen too. */
    private boolean leaveAfterPlayer;

    // ------------------------------------------------------------------ Screen

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        musicRoot = OfflineLibrary.root(getActivity());

        LinearLayout root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background());

        root.addView(createTabs(), new LinearLayout.LayoutParams(-1, -2));
        // The header stays out of the list, so typing a query does not rebuild the search bar.
        root.addView(createHeader(), new LinearLayout.LayoutParams(-1, -2));

        FrameLayout listHost = new FrameLayout(getActivity());
        listView = new ListView(getActivity());
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setSelector(new ColorDrawable(Color.TRANSPARENT));
        listView.setVerticalScrollBarEnabled(false);
        listView.setClipToPadding(false);
        listView.setPadding(dp(8), 0, dp(8), dp(10));
        adapter = new RowsAdapter();
        listView.setAdapter(adapter);
        listHost.addView(listView, new FrameLayout.LayoutParams(-1, -1));

        emptyView = text("", 16, secondary());
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(32), dp(80), dp(32), 0);
        emptyView.setVisibility(View.GONE);
        listHost.addView(emptyView, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        root.addView(listHost, new LinearLayout.LayoutParams(-1, 0, 1));

        reload();

        miniPlayer = createMiniPlayer();
        miniPlayer.setVisibility(View.GONE);
        root.addView(miniPlayer, new LinearLayout.LayoutParams(-1, -2));

        screen = new FrameLayout(getActivity());
        screen.addView(root, new FrameLayout.LayoutParams(-1, -1));
        return screen;
    }

    @Override
    public void onDestroyView() {
        removePlayer();
        super.onDestroyView();
    }

    @Override
    public void onStart() {
        super.onStart();
        active = this;
        OfflineQueue.addListener(queueListener);
        OfflinePlaybackService.addListener(this);

        Activity host = getActivity();
        if (host != null && host.getIntent() != null
                && host.getIntent().getBooleanExtra(EXTRA_OPEN_PLAYER, false)) {
            host.getIntent().removeExtra(EXTRA_OPEN_PLAYER);
            leaveAfterPlayer = true;
            if (screen != null) screen.post(this::openPlayer);
        }
    }

    @Override
    public void onStop() {
        OfflinePlaybackService.removeListener(this);
        OfflineQueue.removeListener(queueListener);
        if (active == this) active = null;
        super.onStop();
    }

    /**
     * Injection point of the back button: closes the search field, then the page of an artist.
     *
     * @return Whether the back action was used up by the screen.
     */
    public static boolean handleBack() {
        LocalDownloadsFragment fragment = active;
        return fragment != null && fragment.consumeBack();
    }

    private boolean consumeBack() {
        // The big player is on top of everything: it goes first.
        if (playerView != null) {
            playerView.onBack();
            return true;
        }
        if (searchBar != null && searchBar.getVisibility() == View.VISIBLE) {
            toggleSearch();
            return true;
        }
        if (openedArtist != null) {
            closeArtist();
            return true;
        }
        return false;
    }

    // -------------------------------------------------------------------- Tabs

    private View createTabs() {
        HorizontalScrollView scroll = new HorizontalScrollView(getActivity());
        scroll.setHorizontalScrollBarEnabled(false);

        LinearLayout row = new LinearLayout(getActivity());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(8), dp(16), dp(4));

        String[] labels = {
                Txt.t("Titres", "Songs"),
                Txt.t("Favoris", "Favorites"),
                Txt.t("Artistes", "Artists"),
        };
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            final Tab target = tabs[i];
            TextView chip = text(labels[i], 14, foreground());
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setPadding(dp(16), 0, dp(16), 0);
            chip.setOnClickListener(v -> selectTab(target));

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(CHIP_HEIGHT_DP));
            params.setMarginEnd(dp(8));
            row.addView(chip, params);
            tabChips.add(chip);
        }
        scroll.addView(row);
        updateTabChips();
        return scroll;
    }

    private void updateTabChips() {
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabChips.size(); i++) {
            final boolean selected = tabs[i] == tab;
            TextView chip = tabChips.get(i);
            chip.setTextColor(selected ? background() : foreground());
            chip.setBackground(pillBackground(selected ? foreground() : subtle(), PILL_CORNER_DP));
        }
    }

    private void selectTab(Tab target) {
        if (target == tab && openedArtist == null) return;
        tab = target;
        openedArtist = null;
        artistHeader.setVisibility(View.GONE);
        updateTabChips();
        applyFilter();
        listView.setSelection(0);
    }

    private void openArtist(String name) {
        openedArtist = name;
        artistTitle.setText(name);
        artistHeader.setVisibility(View.VISIBLE);
        applyFilter();
        listView.setSelection(0);
    }

    private void closeArtist() {
        openedArtist = null;
        artistHeader.setVisibility(View.GONE);
        applyFilter();
    }

    // ------------------------------------------------------------------ Header

    /** The buttons, the search bar and the summary sit above the list, so a redraw keeps them. */
    private LinearLayout createHeader() {
        LinearLayout header = new LinearLayout(getActivity());
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(4), dp(16), dp(2));

        // Hidden until asked for, the way the settings screen reveals its own search.
        searchBar = CustomDialog.createSearchBar(getActivity(),
                str("morphe_music_downloads_search_hint"), value -> {
                    query = value.trim().toLowerCase(Locale.ROOT);
                    applyFilter();
                });
        searchBar.setVisibility(View.GONE);
        searchBar.setAlpha(0f);
        header.addView(searchBar, new LinearLayout.LayoutParams(-1, 0));

        LinearLayout line = new LinearLayout(getActivity());
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(8), 0, 0);

        LinearLayout shuffle = pillButton(OfflineIcons.Icon.SHUFFLE, Txt.t("Aléatoire", "Shuffle"), true);
        shuffle.setOnClickListener(v -> playShown(true));
        LinearLayout.LayoutParams shuffleParams = new LinearLayout.LayoutParams(-2, dp(PILL_HEIGHT_DP));
        shuffleParams.setMarginEnd(dp(8));
        line.addView(shuffle, shuffleParams);

        LinearLayout play = pillButton(OfflineIcons.Icon.PLAY, Txt.t("Lire", "Play"), false);
        play.setOnClickListener(v -> playShown(false));
        line.addView(play, new LinearLayout.LayoutParams(-2, dp(PILL_HEIGHT_DP)));

        View spacer = new View(getActivity());
        line.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));

        ImageButton search = iconButton(OfflineIcons.Icon.SEARCH, str("morphe_music_downloads_search_hint"));
        search.setOnClickListener(v -> toggleSearch());
        line.addView(search, new LinearLayout.LayoutParams(dp(HEADER_ICON_DP), dp(HEADER_ICON_DP)));

        ImageButton sort = iconButton(OfflineIcons.Icon.SORT, str("morphe_music_downloads_sort"));
        sort.setOnClickListener(v -> showSortMenu(sort));
        line.addView(sort, new LinearLayout.LayoutParams(dp(HEADER_ICON_DP), dp(HEADER_ICON_DP)));

        ImageButton more = iconButton(OfflineIcons.Icon.MORE, Txt.t("Plus d’options", "More options"));
        more.setOnClickListener(v -> showMoreMenu(more));
        line.addView(more, new LinearLayout.LayoutParams(dp(HEADER_ICON_DP), dp(HEADER_ICON_DP)));
        header.addView(line, new LinearLayout.LayoutParams(-1, -2));

        // Page of an artist: a way back and the name, only shown while it is open.
        artistHeader = new LinearLayout(getActivity());
        artistHeader.setGravity(Gravity.CENTER_VERTICAL);
        artistHeader.setPadding(0, dp(6), 0, 0);
        artistHeader.setVisibility(View.GONE);

        ImageButton back = iconButton(OfflineIcons.Icon.CHEVRON_LEFT, Txt.t("Retour", "Back"));
        back.setOnClickListener(v -> closeArtist());
        artistHeader.addView(back, new LinearLayout.LayoutParams(dp(HEADER_ICON_DP), dp(HEADER_ICON_DP)));

        artistTitle = text("", 20, foreground());
        artistTitle.setTypeface(Typeface.DEFAULT_BOLD);
        artistTitle.setSingleLine(true);
        artistTitle.setPadding(dp(4), 0, 0, 0);
        artistHeader.addView(artistTitle, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(artistHeader, new LinearLayout.LayoutParams(-1, -2));

        summary = text("", 13, secondary());
        summary.setPadding(dp(2), dp(8), 0, dp(4));
        header.addView(summary, new LinearLayout.LayoutParams(-1, -2));
        return header;
    }

    private LinearLayout pillButton(OfflineIcons.Icon icon, String label, boolean primary) {
        final int ink = primary ? background() : foreground();

        LinearLayout pill = new LinearLayout(getActivity());
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(14), 0, dp(18), 0);
        pill.setBackground(pillBackground(primary ? foreground() : subtle(), PILL_CORNER_DP));
        pill.setClickable(true);
        pill.setFocusable(true);

        ImageView image = new ImageView(getActivity());
        image.setImageDrawable(OfflineIcons.get(icon, dp(20), ink));
        pill.addView(image, new LinearLayout.LayoutParams(dp(20), dp(20)));

        TextView name = text(label, 14, ink);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setPadding(dp(8), 0, 0, 0);
        pill.addView(name, new LinearLayout.LayoutParams(-2, -1));
        return pill;
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
        animator.setDuration(SEARCH_ANIMATION_MILLISECONDS);
        animator.addUpdateListener(value -> {
            searchBar.getLayoutParams().height = (int) value.getAnimatedValue();
            searchBar.requestLayout();
        });
        animator.start();
    }

    // -------------------------------------------------------------------- Data

    /** Reads the library from the disk, sorted the way the user chose. */
    private void reload() {
        allTracks = OfflineLibrary.loadTracks(musicRoot);
        allTracks.sort(OfflineLibrary.comparator(
                OfflineLibrary.savedSort(Settings.DOWNLOADS_SORT.get())));
        applyFilter();
    }

    private boolean matchesQuery(OfflineTrack track) {
        if (query.isEmpty()) return true;
        return track.displayTitle().toLowerCase(Locale.ROOT).contains(query)
                || track.displayArtist().toLowerCase(Locale.ROOT).contains(query)
                || track.album().toLowerCase(Locale.ROOT).contains(query);
    }

    /** Builds the rows for the current tab, the open artist and the search. */
    private void applyFilter() {
        List<OfflineTrack> filtered = new ArrayList<>();
        for (OfflineTrack track : allTracks) {
            if (!matchesQuery(track)) continue;
            if (tab == Tab.FAVORITES && !OfflineLibrary.isFavorite(musicRoot, track.videoId())) continue;
            if (tab == Tab.ARTISTS && openedArtist != null
                    && !track.displayArtist().equalsIgnoreCase(openedArtist)) {
                continue;
            }
            filtered.add(track);
        }

        shownTracks = filtered;
        rows = new ArrayList<>();
        if (tab == Tab.ARTISTS && openedArtist == null) {
            rows.addAll(OfflineLibrary.groupByArtist(filtered));
        } else {
            rows.addAll(filtered);
        }

        updateSummary(filtered);
        updateEmptyView();
        adapter.notifyDataSetChanged();
    }

    private void updateSummary(List<OfflineTrack> filtered) {
        final int count = filtered.size();
        if (tab == Tab.ARTISTS && openedArtist == null) {
            final int artists = rows.size();
            summary.setText(Txt.t(artists + " artistes", artists + " artists"));
        } else if (tab == Tab.FAVORITES) {
            summary.setText(Txt.t(count + " favoris", count + " favorites"));
        } else if (tab == Tab.ARTISTS) {
            summary.setText(Txt.t(count + " titres", count + " songs"));
        } else {
            long totalBytes = 0;
            for (OfflineTrack track : filtered) totalBytes += track.audioFile().length();
            summary.setText(str("morphe_music_downloads_summary", count, formatSize(totalBytes)));
        }
    }

    private void updateEmptyView() {
        if (!rows.isEmpty()) {
            emptyView.setVisibility(View.GONE);
            return;
        }
        final String message;
        if (allTracks.isEmpty()) {
            message = Txt.t("Aucun titre hors ligne.\nUtilise le bouton Download d’un titre, d’un album ou d’une playlist.",
                    "No offline songs.\nUse the Download button of a song, an album or a playlist.");
        } else if (!query.isEmpty()) {
            message = str("morphe_music_downloads_nothing_found");
        } else if (tab == Tab.FAVORITES) {
            message = Txt.t("Pas encore de favoris.\nOuvre le menu ⋮ d’un titre et choisis « Ajouter aux favoris ».",
                    "No favorites yet.\nOpen the ⋮ menu of a song and choose “Add to favorites”.");
        } else {
            message = str("morphe_music_downloads_nothing_found");
        }
        emptyView.setText(message);
        emptyView.setVisibility(View.VISIBLE);
    }

    // -------------------------------------------------------------------- Rows

    private final class RowsAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position) instanceof OfflineTrack ? 0 : 1;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Object item = rows.get(position);
            if (item instanceof OfflineTrack track) {
                TrackRow row = convertView instanceof TrackRow existing
                        ? existing : new TrackRow(getActivity());
                row.bind(track);
                return row;
            }
            ArtistRow row = convertView instanceof ArtistRow existing
                    ? existing : new ArtistRow(getActivity());
            row.bind((OfflineLibrary.Artist) item);
            return row;
        }
    }

    private final class TrackRow extends LinearLayout {
        private final ImageView artwork;
        private final TextView title;
        private final TextView subtitle;
        private final ImageButton menu;

        TrackRow(Context context) {
            super(context);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(8), dp(7), dp(8), dp(7));

            artwork = artworkView();
            addView(artwork, new LinearLayout.LayoutParams(dp(56), dp(56)));

            LinearLayout labels = new LinearLayout(context);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(dp(14), 0, dp(8), 0);
            title = text("", 16, foreground());
            title.setSingleLine(true);
            subtitle = text("", 13, secondary());
            subtitle.setSingleLine(true);
            labels.addView(title);
            labels.addView(subtitle);
            addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

            menu = iconButton(OfflineIcons.Icon.MORE, "");
            // A focusable button inside a list row would get in the way of the row itself.
            menu.setFocusable(false);
            addView(menu, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));
        }

        void bind(OfflineTrack track) {
            bindArtwork(artwork, track);
            title.setText(track.displayTitle());
            subtitle.setText(subtitleOf(track));
            menu.setContentDescription(str("morphe_music_downloads_actions_for", track.displayTitle()));
            menu.setOnClickListener(v -> showTrackMenu(menu, track));

            setBackground(rowBackground(track.audioFile().getAbsolutePath().equals(playingPath)));
            setOnClickListener(v -> playFrom(track));
        }
    }

    private final class ArtistRow extends LinearLayout {
        private final ImageView avatar;
        private final TextView name;
        private final TextView count;

        ArtistRow(Context context) {
            super(context);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(8), dp(7), dp(8), dp(7));

            avatar = artworkView();
            circle(avatar);
            addView(avatar, new LinearLayout.LayoutParams(dp(56), dp(56)));

            LinearLayout labels = new LinearLayout(context);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(dp(14), 0, dp(8), 0);
            name = text("", 16, foreground());
            name.setSingleLine(true);
            count = text("", 13, secondary());
            count.setSingleLine(true);
            labels.addView(name);
            labels.addView(count);
            addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        }

        void bind(OfflineLibrary.Artist artist) {
            bindArtwork(avatar, artist.tracks().get(0));
            name.setText(artist.name());
            final int songs = artist.tracks().size();
            count.setText(Txt.t(songs + (songs > 1 ? " titres" : " titre"),
                    songs + (songs > 1 ? " songs" : " song")));

            setBackground(rowBackground(false));
            setOnClickListener(v -> openArtist(artist.name()));
        }
    }

    private String subtitleOf(OfflineTrack track) {
        StringBuilder text = new StringBuilder();
        if (OfflineLibrary.isFavorite(musicRoot, track.videoId())) text.append("♥ ");
        text.append(track.displayArtist());

        String duration = formatDuration(track.durationSeconds());
        if (!duration.isEmpty()) text.append(" • ").append(duration);
        if (OfflineLibrary.savedSort(Settings.DOWNLOADS_SORT.get()) == OfflineLibrary.Sort.SIZE) {
            text.append(" • ").append(formatSize(track.audioFile().length())).append(" MB");
        }
        return text.toString();
    }

    /** Lifts the card of the track being played out of the background. */
    private Drawable rowBackground(boolean playing) {
        Drawable card = playing
                ? CustomDialog.createRoundedBackground(ROW_CORNER_DP, Utils.adjustColorBrightness(
                        ThemeUtils.getAppBackgroundColor(), PLAYING_ROW_BRIGHTNESS))
                : null;
        // The ripple is masked to the card, so it does not spill into the neighboring rows.
        return new RippleDrawable(ColorStateList.valueOf(rippleColor()), card,
                CustomDialog.createRoundedBackground(ROW_CORNER_DP, Color.WHITE));
    }

    // ----------------------------------------------------------------- Artwork

    private ImageView artworkView() {
        ImageView view = new ImageView(getActivity());
        view.setBackgroundColor(Utils.adjustColorBrightness(foreground(), 0.2f));
        roundCorners(view);
        showPlaceholder(view);
        return view;
    }

    private void showPlaceholder(ImageView view) {
        view.setImageDrawable(OfflineIcons.get(OfflineIcons.Icon.NOTE, dp(24), secondary()));
        view.setScaleType(ImageView.ScaleType.CENTER);
    }

    private void showArtwork(ImageView view, Bitmap bitmap) {
        view.setImageBitmap(bitmap);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
    }

    /** The cover is read in the background, and only shown if the row still wants it. */
    private void bindArtwork(ImageView view, OfflineTrack track) {
        final String key = track.videoId();
        view.setTag(key);

        Bitmap cached = artworkCache.get(key);
        if (cached != null) {
            showArtwork(view, cached);
            return;
        }
        showPlaceholder(view);
        artworkLoader.execute(() -> {
            if (!key.equals(view.getTag())) return;
            Bitmap bitmap = track.artwork(ROW_ARTWORK_PIXELS);
            if (bitmap == null) return;

            artworkCache.put(key, bitmap);
            view.post(() -> {
                if (key.equals(view.getTag())) showArtwork(view, bitmap);
            });
        });
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

    private void circle(View view) {
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View outlined, Outline outline) {
                outline.setOval(0, 0, outlined.getWidth(), outlined.getHeight());
            }
        });
        view.setClipToOutline(true);
    }

    // ----------------------------------------------------------------- Playing

    /** Plays the tracks the list shows, starting with the one that was tapped. */
    private void playFrom(OfflineTrack track) {
        play(shownTracks, Math.max(0, shownTracks.indexOf(track)), false);
    }

    private void playShown(boolean shuffled) {
        if (shownTracks.isEmpty()) return;
        final int start = shuffled ? new Random().nextInt(shownTracks.size()) : 0;
        play(shownTracks, start, shuffled);
    }

    /**
     * The queue is given to the player directly, since the intent of a service is size limited
     * and a large library would not fit in it.
     */
    private void play(List<OfflineTrack> list, int startIndex, boolean shuffled) {
        try {
            ArrayList<String> paths = new ArrayList<>();
            for (OfflineTrack track : list) paths.add(track.audioFile().getAbsolutePath());
            OfflineQueue.set(paths, startIndex, shuffled, sourceName());
            OfflinePlaybackService.playCurrent(getActivity());
        } catch (Exception ex) {
            Logger.printException(() -> "Offline playback failed", ex);
            Utils.showToastShort(str("morphe_music_downloads_play_failed"));
        }
    }

    /** Name of the list the music is played from, shown by the big player. */
    private String sourceName() {
        if (tab == Tab.FAVORITES) return Txt.t("Mes favoris", "My favorites");
        if (tab == Tab.ARTISTS && openedArtist != null) return openedArtist;
        return Txt.t("Mes titres hors ligne", "My offline songs");
    }

    /** "Lire ensuite": the track goes right after the one playing, or starts when nothing plays. */
    private void playNext(OfflineTrack track) {
        final String path = track.audioFile().getAbsolutePath();
        if (playingPath.isEmpty() || OfflineQueue.size() == 0) {
            play(java.util.Collections.singletonList(track), 0, false);
            return;
        }
        OfflineQueue.playNext(path);
        Utils.showToastShort(Txt.t("Sera lu ensuite", "Will play next"));
    }

    private void addToQueue(OfflineTrack track) {
        final String path = track.audioFile().getAbsolutePath();
        if (playingPath.isEmpty() || OfflineQueue.size() == 0) {
            play(java.util.Collections.singletonList(track), 0, false);
            return;
        }
        OfflineQueue.addToEnd(path);
        Utils.showToastShort(Txt.t("Ajouté à la file", "Added to the queue"));
    }

    private void toggleFavorite(OfflineTrack track) {
        final boolean now = OfflineLibrary.toggleFavorite(musicRoot, track.videoId());
        Utils.showToastShort(now
                ? Txt.t("Ajouté aux favoris", "Added to favorites")
                : Txt.t("Retiré des favoris", "Removed from favorites"));
        applyFilter();
    }

    // ------------------------------------------------------------------- Menus

    private void showSortMenu(View anchor) {
        String[] items = {
                Txt.t("Ajoutés récemment", "Recently added"),
                Txt.t("Plus anciens d’abord", "Oldest first"),
                Txt.t("Titre (A → Z)", "Title (A → Z)"),
                Txt.t("Artiste", "Artist"),
                Txt.t("Album", "Album"),
                Txt.t("Durée (plus longs d’abord)", "Duration (longest first)"),
                Txt.t("Taille (plus gros d’abord)", "Size (largest first)"),
        };
        OfflineLibrary.Sort saved = OfflineLibrary.savedSort(Settings.DOWNLOADS_SORT.get());
        showMenu(anchor, items, Arrays.asList(SORT_CHOICES).indexOf(saved), position -> {
            Settings.DOWNLOADS_SORT.save(SORT_CHOICES[position].name());
            reload();
        });
    }

    private void showMoreMenu(View anchor) {
        String[] items = {
                Txt.t("Importer des musiques", "Import music"),
                Txt.t("Exporter mes musiques", "Export my music"),
                str("morphe_music_downloads_delete_all"),
        };
        showMenu(anchor, items, -1, position -> {
            if (position == 0) startImport();
            else if (position == 1) startExport();
            else confirmDeleteAll();
        });
    }

    private void showTrackMenu(View anchor, OfflineTrack track) {
        final boolean favorite = OfflineLibrary.isFavorite(musicRoot, track.videoId());
        String[] items = {
                Txt.t("Lire ensuite", "Play next"),
                Txt.t("Ajouter à la file", "Add to queue"),
                favorite ? Txt.t("Retirer des favoris", "Remove from favorites")
                        : Txt.t("Ajouter aux favoris", "Add to favorites"),
                str("morphe_music_downloads_delete_track"),
        };
        showMenu(anchor, items, -1, position -> {
            if (position == 0) playNext(track);
            else if (position == 1) addToQueue(track);
            else if (position == 2) toggleFavorite(track);
            else confirmDelete(track);
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

    // ------------------------------------------------------------------ Delete

    private void confirmDelete(OfflineTrack track) {
        confirm(str("morphe_music_downloads_delete_track_title"),
                str("morphe_music_downloads_delete_track_message", track.displayTitle()),
                () -> deleteTrack(track));
    }

    private void confirmDeleteAll() {
        if (allTracks.isEmpty()) return;
        confirm(str("morphe_music_downloads_delete_all_title"),
                str("morphe_music_downloads_delete_all_message"), this::deleteAll);
    }

    private void confirm(String title, String message, Runnable onConfirm) {
        CustomDialog.create(getActivity(), title, message, null,
                str("morphe_music_downloads_delete"), onConfirm, () -> {
                }, null, null, false).first.show();
    }

    private void deleteAll() {
        for (OfflineTrack track : new ArrayList<>(allTracks)) {
            OfflineStorage.delete(track.audioFile());
            deleteSidecars(track.videoId());
            OfflineLibrary.forgetFavorite(musicRoot, track.videoId());
        }
        final boolean wasPlaying = !playingPath.isEmpty();
        OfflineQueue.clear();
        if (wasPlaying) OfflinePlaybackService.stop(getActivity());
        reload();
        Utils.showToastShort(str("morphe_music_downloads_all_deleted"));
    }

    private void deleteSidecars(String videoId) {
        OfflineStorage.delete(new File(musicRoot, videoId + ".json"));
        OfflineStorage.delete(new File(musicRoot, videoId + ".jpg"));
        OfflineStorage.delete(new File(musicRoot, videoId + ".webm.part"));
        OfflineStorage.delete(new File(musicRoot, videoId + ".m4a.part"));
    }

    private void deleteTrack(OfflineTrack track) {
        final String path = track.audioFile().getAbsolutePath();
        final boolean wasPlaying = path.equals(playingPath);
        final boolean deleted = track.audioFile().delete();
        deleteSidecars(track.videoId());

        if (!deleted) {
            Utils.showToastShort(str("morphe_music_downloads_track_delete_failed"));
            return;
        }
        OfflineLibrary.forgetFavorite(musicRoot, track.videoId());
        OfflineQueue.forget(path);
        artworkCache.remove(track.videoId());

        if (wasPlaying) {
            if (OfflineQueue.current() == null) OfflinePlaybackService.stop(getActivity());
            else OfflinePlaybackService.playCurrent(getActivity());
        }
        reload();
        Utils.showToastShort(str("morphe_music_downloads_track_deleted"));
    }

    // --------------------------------------------------------- Import / export

    private void startExport() {
        if (allTracks.isEmpty()) {
            Utils.showToastShort(Txt.t("Rien à exporter", "Nothing to export"));
            return;
        }
        String date = new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date());
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, "musiques-hors-ligne-" + date + ".zip");
        startActivityForResult(intent, REQUEST_EXPORT);
    }

    private void startImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;

        if (requestCode == REQUEST_EXPORT) runExport(data.getData());
        else if (requestCode == REQUEST_IMPORT) runImport(data.getData());
    }

    private void runExport(Uri destination) {
        final Activity activity = getActivity();
        if (activity == null) return;

        Utils.showToastShort(Txt.t("Export en cours…", "Exporting…"));
        Utils.runOnBackgroundThread(() -> {
            try (OutputStream output = activity.getContentResolver().openOutputStream(destination)) {
                if (output == null) throw new IOException("No output stream");
                final int count = OfflineLibrary.exportAll(musicRoot, output);
                Utils.showToastShort(Txt.t(count + " titres exportés", count + " songs exported"));
            } catch (Exception ex) {
                Logger.printException(() -> "Export failure", ex);
                Utils.showToastShort(Txt.t("L’export a échoué", "The export failed"));
            }
        });
    }

    private void runImport(Uri source) {
        final Activity activity = getActivity();
        if (activity == null) return;

        Utils.showToastShort(Txt.t("Import en cours…", "Importing…"));
        Utils.runOnBackgroundThread(() -> {
            try (InputStream input = activity.getContentResolver().openInputStream(source)) {
                if (input == null) throw new IOException("No input stream");
                final int count = OfflineLibrary.importAll(musicRoot, input);
                Utils.showToastShort(Txt.t(count + " nouveaux titres importés",
                        count + " new songs imported"));
            } catch (Exception ex) {
                Logger.printException(() -> "Import failure", ex);
                Utils.showToastShort(Txt.t("L’import a échoué", "The import failed"));
            }
            activity.runOnUiThread(() -> {
                if (isAdded()) reload();
            });
        });
    }

    // ------------------------------------------------------------- Big player

    private void openPlayer() {
        if (playerView != null || playingPath.isEmpty() || getActivity() == null || screen == null) return;

        OfflinePlayerView view = new OfflinePlayerView(getActivity(), new OfflinePlayerView.Host() {
            @Override
            public void onClosing() {
                setToolbarVisible(true);
            }

            @Override
            public void onClosed() {
                removePlayer();
                if (leaveAfterPlayer) {
                    leaveAfterPlayer = false;
                    Activity host = getActivity();
                    if (host != null) host.finish();
                }
            }

            @Override
            public void onShowArtist(String artistName) {
                // The page behind the player changes while it slides away.
                leaveAfterPlayer = false;
                selectTab(Tab.ARTISTS);
                openArtist(artistName);
            }

            @Override
            public void onFavoritesChanged() {
                if (isAdded()) applyFilter();
            }

            @Override
            public void onDeleteRequested(OfflineTrack track) {
                confirmDelete(track);
            }
        });
        playerView = view;
        // The player covers the whole page, so the bar of the page goes away while it is open.
        setToolbarVisible(false);
        screen.addView(view, new FrameLayout.LayoutParams(-1, -1));
        view.show();
    }

    private void removePlayer() {
        OfflinePlayerView view = playerView;
        playerView = null;
        if (view != null && screen != null) screen.removeView(view);
        setToolbarVisible(true);
    }

    /** The toolbar of the page, the one with the title, hidden under the big player. */
    private void setToolbarVisible(boolean visible) {
        Activity activity = getActivity();
        if (activity == null) return;
        try {
            final int id = ResourceUtils.getIdentifierOrThrow(ResourceType.ID, "morphe_toolbar_parent");
            View toolbar = activity.findViewById(id);
            if (toolbar != null) toolbar.setVisibility(visible ? View.VISIBLE : View.GONE);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not show or hide the toolbar", ex);
        }
    }

    // ------------------------------------------------------------- Mini player

    private LinearLayout createMiniPlayer() {
        // Swiped sideways or down, the bar closes and the offline player stops.
        LinearLayout outer = new SwipeDismissLayout(getActivity(), () -> {
            Activity host = getActivity();
            if (host != null) OfflinePlaybackService.stop(host);
        });
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(ThemeUtils.getDialogBackgroundColor());

        // The surface color of a theme can sit very close to its background, so the bar is
        // separated by a rule rather than by the fill alone.
        View divider = new View(getActivity());
        divider.setBackgroundColor(rippleColor());
        outer.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        // Lifts the bar off the gesture area, so the seek bar is not on the screen edge.
        outer.setPadding(0, 0, 0, dp(12));

        LinearLayout line = new LinearLayout(getActivity());
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(16), 0, 0, 0);
        // A tap anywhere on the bar, except on its buttons, opens the big player.
        line.setClickable(true);
        line.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor()), null,
                new ColorDrawable(Color.WHITE)));
        line.setOnClickListener(v -> openPlayer());

        miniArtwork = artworkView();
        line.addView(miniArtwork, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout labels = new LinearLayout(getActivity());
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.setPadding(dp(16), 0, dp(4), 0);

        miniTitle = text("", 14, foreground());
        miniTitle.setSingleLine(true);
        miniTitle.setTypeface(Typeface.DEFAULT_BOLD);

        miniArtist = text("", 14, secondary());
        miniArtist.setSingleLine(true);

        labels.addView(miniTitle);
        labels.addView(miniArtist);
        line.addView(labels, new LinearLayout.LayoutParams(0, dp(56), 1));

        miniPrevious = iconButton(OfflineIcons.Icon.PREVIOUS, str("morphe_music_downloads_previous"));
        miniPrevious.setOnClickListener(v -> OfflinePlaybackService.skipPrevious(getActivity()));
        line.addView(miniPrevious, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        miniPlay = iconButton(OfflineIcons.Icon.PLAY, str("morphe_music_downloads_play_pause"));
        miniPlay.setOnClickListener(v -> OfflinePlaybackService.toggle(getActivity()));
        line.addView(miniPlay, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        miniNext = iconButton(OfflineIcons.Icon.NEXT, str("morphe_music_downloads_next"));
        miniNext.setOnClickListener(v -> OfflinePlaybackService.skipNext(getActivity()));
        line.addView(miniNext, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        outer.addView(line, new LinearLayout.LayoutParams(-1, dp(60)));
        outer.addView(createSeekBar(), new LinearLayout.LayoutParams(-1, dp(SEEK_BAR_TOUCH_DP)));
        return outer;
    }

    private SeekBar createSeekBar() {
        miniSeek = new SeekBar(getActivity());
        // The bar keeps the gutters of the list, so it reads as a control rather than an edge.
        miniSeek.setPadding(dp(16), 0, dp(16), 0);
        miniSeek.setProgressDrawable(createSeekBarDrawable());
        // The stock thumb draws a ripple far wider than the bar, so it only appears while dragging.
        miniSeek.setThumb(null);
        miniSeek.setThumbOffset(0);
        miniSeek.setBackground(null);
        miniSeek.setSplitTrack(false);
        miniSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
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
            }
        });
        return miniSeek;
    }

    private Drawable createThumb() {
        ShapeDrawable thumb = new ShapeDrawable(new OvalShape());
        thumb.setIntrinsicWidth(dp(SEEK_THUMB_DP));
        thumb.setIntrinsicHeight(dp(SEEK_THUMB_DP));
        thumb.getPaint().setColor(ThemeUtils.getAppForegroundColor());
        return thumb;
    }

    /**
     * The thickness comes from the drawable because {@code setMinHeight} and
     * {@code setMaxHeight} are only available from API 29.
     */
    private Drawable createSeekBarDrawable() {
        final int foreground = ThemeUtils.getAppForegroundColor();

        // Dimming the foreground works on any background, unlike lightening a black one.
        Drawable track = roundedBar(Utils.adjustColorBrightness(foreground, 0.3f));
        Drawable played = roundedBar(foreground);

        LayerDrawable layers = new LayerDrawable(new Drawable[]{
                track, new ClipDrawable(played, Gravity.START, ClipDrawable.HORIZONTAL)});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);

        // A progress drawable is stretched to the view, and the thickness can only be capped
        // with setMaxHeight from API 29, so the band is inset to the height it should have.
        final int inset = (dp(SEEK_BAR_TOUCH_DP) - dp(SEEK_BAR_HEIGHT_DP)) / 2;
        layers.setLayerInset(0, 0, inset, 0, inset);
        layers.setLayerInset(1, 0, inset, 0, inset);
        return layers;
    }

    private Drawable roundedBar(int color) {
        return CustomDialog.createRoundedBackground(SEEK_BAR_HEIGHT_DP, color);
    }

    @Override
    public void onPlaybackChanged(String path, String title, boolean playing,
                                  int position, int duration) {
        if (getActivity() == null) return;

        getActivity().runOnUiThread(() -> {
            if (miniPlayer == null) return;
            miniPlayer.setVisibility(title.isEmpty() ? View.GONE : View.VISIBLE);

            // Decoding the cover on every tick would read the disk twice a second.
            if (!path.equals(playingPath)) {
                playingPath = path;
                OfflineTrack track = findByPath(path);
                if (track != null) applyTrackVisuals(track);
                else miniTitle.setText(title);

                updateNavButtons();
                adapter.notifyDataSetChanged();
            }

            updatePlayIcon(playing);
            miniSeek.setMax(Math.max(1, duration));
            if (!userSeeking) miniSeek.setProgress(position, true);
        });
    }

    @Nullable
    private OfflineTrack findByPath(String path) {
        if (path.isEmpty()) return null;
        for (OfflineTrack track : allTracks) {
            if (track.audioFile().getAbsolutePath().equals(path)) return track;
        }
        return null;
    }

    private void updateNavButtons() {
        if (miniNext == null || miniPrevious == null) return;
        setButtonEnabled(miniNext, OfflineQueue.hasNext());
        // Previous restarts the track when there is nothing before it.
        setButtonEnabled(miniPrevious, !playingPath.isEmpty());
    }

    /** This runs on every playback tick, so the icon is only replaced when the state flips. */
    private void updatePlayIcon(boolean playing) {
        if (playing == showingPause) return;
        showingPause = playing;
        miniPlay.setImageDrawable(OfflineIcons.get(
                playing ? OfflineIcons.Icon.PAUSE : OfflineIcons.Icon.PLAY, dp(24), foreground()));
    }

    private void applyTrackVisuals(OfflineTrack track) {
        miniTitle.setText(track.displayTitle());
        miniArtist.setText(track.displayArtist());
        bindArtwork(miniArtwork, track);
    }

    private void setButtonEnabled(ImageButton button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : .35f);
    }

    // ----------------------------------------------------------------- Helpers

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(getActivity());
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private ImageButton iconButton(OfflineIcons.Icon icon, String description) {
        ImageButton button = new ImageButton(getActivity());
        button.setImageDrawable(OfflineIcons.get(icon, dp(24), foreground()));
        button.setContentDescription(description);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor()),
                null, new ShapeDrawable(new OvalShape())));
        return button;
    }

    private Drawable pillBackground(int fill, int cornerDp) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor()),
                CustomDialog.createRoundedBackground(cornerDp, fill),
                CustomDialog.createRoundedBackground(cornerDp, Color.WHITE));
    }

    private static int rippleColor() {
        final int foreground = foreground();
        return Color.argb(60, Color.red(foreground),
                Color.green(foreground), Color.blue(foreground));
    }

    /** A faint fill of the foreground color, for the buttons that are not the main one. */
    private static int subtle() {
        final int foreground = foreground();
        return Color.argb(36, Color.red(foreground),
                Color.green(foreground), Color.blue(foreground));
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

    private static String formatSize(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / 1048576.0);
    }
}
