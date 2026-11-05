/*
 * Custom addition: the liked songs sorted by artist.
 *
 * A first page lists the artists of whom at least one song is liked. Choosing one shows only the
 * liked songs of that artist. The songs come from the "Liked music" playlist of the account (see
 * LikedSongsStore), and are played or queued through the queue of the app itself, so playing
 * one never replaces a queue that already holds other songs.
 */

package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.preference.PreferenceFragment;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.music.patches.EnableAudioVideoSwitchPatch;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.CustomDialog;

@SuppressWarnings({"deprecation", "RedundantSuppression"})
public final class LikedArtistsFragment extends PreferenceFragment {

    private static final int ROW_CORNER_DP = 14;
    private static final int CHIP_CORNER_DP = 18;
    private static final int AVATAR_DP = 44;
    private static final int BUTTON_DP = 44;

    /** An artist, with the liked songs of that artist. */
    private static final class Artist {
        final String name;
        final List<LikedSongsStore.Song> songs = new ArrayList<>();

        Artist(String name) {
            this.name = name;
        }
    }

    @Nullable
    private static LikedArtistsFragment current;

    private final List<LikedSongsStore.Song> songs = new ArrayList<>();
    private Map<String, Artist> artists = new LinkedHashMap<>();

    /** Key of the artist whose songs are shown, or null on the list of artists. */
    @Nullable
    private String openArtist;
    private String query = "";
    private boolean sortByCount = true;
    /** Set when the page is about to close, so the back button is not taken for a step back. */
    private boolean leaving;
    private boolean loading = true;
    /** What went wrong the last time the list was asked for, or null. */
    @Nullable
    private String error;
    private Context appContext;

    private TextView title;
    private TextView summary;
    private TextView status;
    private EditText searchBar;
    private TextView sortChip;
    private LinearLayout artistActions;
    private LinearLayout list;
    private ScrollView scroll;

    // region Page

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        current = this;
        appContext = getActivity().getApplicationContext();

        FrameLayout frame = new FrameLayout(getActivity());
        frame.setBackgroundColor(background());

        LinearLayout root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background());
        root.addView(createHeader(), new LinearLayout.LayoutParams(-1, -2));

        scroll = new ScrollView(getActivity());
        list = new LinearLayout(getActivity());
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), 0, dp(8), dp(16));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));

        refreshScreen();
        loadSongs();
        return frame;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (current == this) current = null;
    }

    /** Injection point of the activity: the back button first steps back from an artist. */
    public static boolean handleBack() {
        LikedArtistsFragment fragment = current;
        return fragment != null && fragment.consumeBack();
    }

    private boolean consumeBack() {
        if (leaving || openArtist == null) return false;
        openArtist = null;
        refreshScreen();
        scroll.scrollTo(0, 0);
        return true;
    }

    private LinearLayout createHeader() {
        LinearLayout header = new LinearLayout(getActivity());
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(8), dp(16), dp(6));

        title = text("", 20, foreground());
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(title, new LinearLayout.LayoutParams(-1, -2));

        summary = text("", 13, secondary());
        summary.setSingleLine(true);
        header.addView(summary, new LinearLayout.LayoutParams(-1, -2));

        status = text("", 13, secondary());
        status.setPadding(0, dp(2), 0, 0);
        header.addView(status, new LinearLayout.LayoutParams(-1, -2));

        // Search and sort, on the list of artists.
        searchBar = CustomDialog.createSearchBar(getActivity(),
                str("morphe_music_favorites_search_hint"), text -> {
                    query = text.trim().toLowerCase(Locale.ROOT);
                    showArtists();
                });
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, -2);
        searchParams.topMargin = dp(8);
        header.addView(searchBar, searchParams);

        sortChip = chip(str("morphe_music_favorites_sort_count"));
        sortChip.setOnClickListener(v -> {
            sortByCount = !sortByCount;
            updateSortChip();
            showArtists();
        });
        updateSortChip();
        LinearLayout.LayoutParams sortParams = new LinearLayout.LayoutParams(-2, -2);
        sortParams.topMargin = dp(8);
        header.addView(sortChip, sortParams);

        // Play and queue everything, on the songs of an artist.
        artistActions = new LinearLayout(getActivity());
        artistActions.setGravity(Gravity.CENTER_VERTICAL);
        TextView playAll = chip(str("morphe_music_favorites_play_all"));
        styleChip(playAll, true);
        playAll.setOnClickListener(v -> playArtist(true));
        TextView queueAll = chip(str("morphe_music_favorites_queue_all"));
        styleChip(queueAll, false);
        queueAll.setOnClickListener(v -> playArtist(false));
        artistActions.addView(playAll, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams queueParams = new LinearLayout.LayoutParams(-2, -2);
        queueParams.setMarginStart(dp(8));
        artistActions.addView(queueAll, queueParams);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
        actionsParams.topMargin = dp(8);
        header.addView(artistActions, actionsParams);

        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(-1, dp(1));
        lineParams.topMargin = dp(8);
        header.addView(DownloadsUi.hairline(getActivity()), lineParams);
        return header;
    }

    // endregion

    // region Data

    /** Shows the copy kept on the phone at once, then asks for the up to date list. */
    private void loadSongs() {
        final Context context = appContext;
        Utils.runOnBackgroundThread(() -> {
            List<LikedSongsStore.Song> cached = LikedSongsStore.loadCache(context);
            Utils.runOnMainThread(() -> {
                if (current != this) return;
                if (songs.isEmpty() && !cached.isEmpty()) setSongs(cached);
            });

            LikedSongsStore.Result result = LikedSongsStore.fetch(context);
            Utils.runOnMainThread(() -> {
                if (current != this) return;
                loading = false;
                if (result.songs() != null) {
                    error = null;
                    setSongs(result.songs());
                } else {
                    error = result.error();
                    refreshScreen();
                }
            });
        });
    }

    private void setSongs(List<LikedSongsStore.Song> newSongs) {
        songs.clear();
        songs.addAll(newSongs);

        Map<String, Artist> grouped = new LinkedHashMap<>();
        for (LikedSongsStore.Song song : songs) {
            List<String> names = song.artists().isEmpty()
                    ? Collections.singletonList(str("morphe_music_favorites_unknown_artist"))
                    : song.artists();
            for (String name : names) {
                grouped.computeIfAbsent(name.toLowerCase(Locale.ROOT), key -> new Artist(name))
                        .songs.add(song);
            }
        }
        artists = grouped;
        if (openArtist != null && !artists.containsKey(openArtist)) openArtist = null;
        refreshScreen();
    }

    // endregion

    // region Screens

    /** Draws the header and the list for whichever of the two screens is open. */
    private void refreshScreen() {
        final Artist open = openArtist == null ? null : artists.get(openArtist);
        final boolean overview = open == null;

        searchBar.setVisibility(overview ? View.VISIBLE : View.GONE);
        sortChip.setVisibility(overview ? View.VISIBLE : View.GONE);
        artistActions.setVisibility(overview ? View.GONE : View.VISIBLE);

        if (overview) {
            title.setText(str("morphe_music_favorites_screen_title"));
            summary.setText(str("morphe_music_favorites_summary", artists.size(), songs.size()));
        } else {
            title.setText(open.name);
            summary.setText(songsCount(open.songs.size()));
        }

        String message = "";
        if (loading) {
            message = str("morphe_music_favorites_loading");
        } else if (error != null) {
            message = errorText(error);
        }
        status.setText(message);
        status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);

        if (overview) {
            showArtists();
        } else {
            showSongs(open);
        }
    }

    private String errorText(String code) {
        if (LikedSongsStore.ERROR_LOGIN.equals(code)) return str("morphe_music_favorites_error_login");
        if (LikedSongsStore.ERROR_EMPTY.equals(code)) return str("morphe_music_favorites_error_empty");
        if (code.startsWith(LikedSongsStore.ERROR_HTTP_PREFIX)) {
            return str("morphe_music_favorites_error_http",
                    code.substring(LikedSongsStore.ERROR_HTTP_PREFIX.length()));
        }
        return str("morphe_music_favorites_error_network");
    }

    private void showArtists() {
        list.removeAllViews();

        List<Map.Entry<String, Artist>> shown = new ArrayList<>();
        for (Map.Entry<String, Artist> entry : artists.entrySet()) {
            if (query.isEmpty() || entry.getKey().contains(query)) shown.add(entry);
        }
        Comparator<Map.Entry<String, Artist>> byName =
                Comparator.comparing(entry -> entry.getKey());
        if (sortByCount) {
            shown.sort(Comparator.<Map.Entry<String, Artist>>comparingInt(
                    entry -> -entry.getValue().songs.size()).thenComparing(byName));
        } else {
            shown.sort(byName);
        }

        if (shown.isEmpty()) {
            list.addView(emptyView(artists.isEmpty()));
            return;
        }

        final List<View> rows = new ArrayList<>();
        for (Map.Entry<String, Artist> entry : shown) {
            View row = artistRow(entry.getKey(), entry.getValue());
            list.addView(row, rowParams());
            rows.add(row);
        }
        DownloadsMotion.staggerIn(rows, dp(24));
    }

    private void showSongs(Artist artist) {
        list.removeAllViews();
        final List<View> rows = new ArrayList<>();
        for (LikedSongsStore.Song song : artist.songs) {
            View row = songRow(song, artist);
            list.addView(row, rowParams());
            rows.add(row);
        }
        DownloadsMotion.staggerIn(rows, dp(24));
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(3);
        params.bottomMargin = dp(3);
        return params;
    }

    private View emptyView(boolean noSongsAtAll) {
        String message;
        if (noSongsAtAll) {
            message = loading ? "" : str("morphe_music_favorites_empty");
        } else {
            message = str("morphe_music_favorites_nothing_found");
        }
        TextView empty = text(message, 16, secondary());
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(16), dp(80), dp(16), 0);
        return empty;
    }

    private View artistRow(String key, Artist artist) {
        LinearLayout row = new LinearLayout(getActivity());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setBackground(DownloadsUi.rippleRounded(
                mix(background(), foreground(), 0.07f), dp(ROW_CORNER_DP)));

        // The first letter in a round badge, since the songs carry no picture of the artist.
        TextView avatar = text(initial(artist.name), 18, foreground());
        avatar.setGravity(Gravity.CENTER);
        avatar.setTypeface(Typeface.DEFAULT_BOLD);
        avatar.setBackground(DownloadsUi.oval(DownloadsUi.withAlpha(foreground(), 30)));
        row.addView(avatar, new LinearLayout.LayoutParams(dp(AVATAR_DP), dp(AVATAR_DP)));

        LinearLayout labels = new LinearLayout(getActivity());
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(14), 0, dp(8), 0);
        TextView name = text(artist.name, 16, foreground());
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        TextView count = text(songsCount(artist.songs.size()), 13, secondary());
        count.setSingleLine(true);
        labels.addView(name);
        labels.addView(count);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        TextView chevron = text("\u203A", 26, secondary());
        chevron.setGravity(Gravity.CENTER);
        row.addView(chevron, new LinearLayout.LayoutParams(dp(28), -2));

        row.setOnClickListener(v -> {
            openArtist = key;
            refreshScreen();
            scroll.scrollTo(0, 0);
        });
        DownloadsMotion.pressable(row, 0.97f);
        return row;
    }

    private View songRow(LikedSongsStore.Song song, Artist shownArtist) {
        LinearLayout row = new LinearLayout(getActivity());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(8), dp(4), dp(8));
        row.setBackground(DownloadsUi.rippleRounded(
                mix(background(), foreground(), 0.07f), dp(ROW_CORNER_DP)));

        LinearLayout labels = new LinearLayout(getActivity());
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(song.title(), 16, foreground());
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        TextView detail = text(detail(song, shownArtist), 13, secondary());
        detail.setSingleLine(true);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(name);
        labels.addView(detail);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        // A visible 'Play next', like on the downloads page.
        ImageButton next = DownloadsUi.icon(getActivity(), "yt_outline_experimental_queue_vd_theme_24",
                str("morphe_music_downloads_play_next"), foreground());
        next.setOnClickListener(v -> startPlayback(
                Collections.singletonList(song.videoId()), false, true));
        row.addView(next, new LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)));

        row.setOnClickListener(v -> startPlayback(
                Collections.singletonList(song.videoId()), true, true));
        DownloadsMotion.pressable(row, 0.97f);
        return row;
    }

    /** The other artists of the song, then its length. */
    private String detail(LikedSongsStore.Song song, Artist shownArtist) {
        List<String> parts = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (String artist : song.artists()) {
            if (!artist.equalsIgnoreCase(shownArtist.name)) others.add(artist);
        }
        if (!others.isEmpty()) parts.add(TextUtils.join(", ", others));
        if (song.seconds() > 0) parts.add(DownloadsUi.clock(song.seconds() * 1000));
        return TextUtils.join(" \u2022 ", parts);
    }

    private static String songsCount(int count) {
        return count == 1
                ? str("morphe_music_favorites_one_song")
                : str("morphe_music_favorites_songs_count", count);
    }

    private static String initial(String name) {
        for (int i = 0; i < name.length(); i++) {
            if (Character.isLetterOrDigit(name.charAt(i))) {
                return name.substring(i, i + 1).toUpperCase(Locale.ROOT);
            }
        }
        return "?";
    }

    // endregion

    // region Playback

    private void playArtist(boolean play) {
        Artist artist = openArtist == null ? null : artists.get(openArtist);
        if (artist == null) return;

        List<String> ids = new ArrayList<>();
        for (LikedSongsStore.Song song : artist.songs) ids.add(song.videoId());
        startPlayback(ids, play, false);
    }

    /**
     * @param play     Whether the first song is to be played when that loses no queue.
     * @param playNext Whether the songs go right after the playing one, or at the end.
     */
    private void startPlayback(List<String> ids, boolean play, boolean playNext) {
        Activity activity = getActivity();
        if (activity == null) return;

        EnableAudioVideoSwitchPatch.playFromFavorites(activity, ids, play, playNext, result -> {
            try {
                if (current != this) return;
                switch (result) {
                    case EnableAudioVideoSwitchPatch.FAVORITES_STARTED -> {
                        // The player is behind this page, which is closed to show it.
                        leaving = true;
                        activity.finish();
                    }
                    case EnableAudioVideoSwitchPatch.FAVORITES_QUEUED -> Utils.showToastShort(
                            ids.size() == 1
                                    ? str("morphe_music_favorites_added_next")
                                    : str("morphe_music_favorites_added_all", ids.size()));
                    default -> Utils.showToastShort(str("morphe_music_favorites_cannot_queue"));
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Favorites playback result failure", ex);
            }
        });
    }

    // endregion

    // region Small views

    private TextView chip(String label) {
        TextView view = text(label, 14, foreground());
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setPadding(dp(14), dp(7), dp(14), dp(7));
        view.setClickable(true);
        styleChip(view, false);
        DownloadsMotion.pressable(view, 0.9f);
        return view;
    }

    private void updateSortChip() {
        sortChip.setText(str(sortByCount
                ? "morphe_music_favorites_sort_count" : "morphe_music_favorites_sort_name"));
    }

    private void styleChip(TextView chip, boolean selected) {
        // Like the chips of the app: filled with the text color when on, a pale fill when off.
        chip.setBackground(DownloadsUi.rippleRounded(selected
                ? foreground() : DownloadsUi.withAlpha(foreground(), 26), dp(CHIP_CORNER_DP)));
        chip.setTextColor(selected ? background() : foreground());
    }

    private TextView text(String value, int sp, int color) {
        return DownloadsUi.text(getActivity(), value, sp, color);
    }

    private static int mix(int base, int over, float amount) {
        return DownloadsUi.mix(base, over, amount);
    }

    private static int foreground() {
        return DownloadsUi.foreground();
    }

    private static int secondary() {
        return DownloadsUi.secondary();
    }

    private static int background() {
        return DownloadsUi.background();
    }

    private int dp(int value) {
        return DownloadsUi.dp(getActivity(), value);
    }

    // endregion
}
