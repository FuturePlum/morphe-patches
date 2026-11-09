/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.artist;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.List;

import app.morphe.extension.music.patches.artist.ArtistRequest.Artist;
import app.morphe.extension.music.patches.downloads.Txt;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * "Go to artist" of a song menu only opens one artist. When the song has several, this asks
 * which one to open first.
 *
 * The command is never consumed: the app still opens its own artist, and the picker appears on
 * top. Choosing that same artist (or closing the picker) changes nothing; choosing another one
 * opens its page.
 */
public final class ArtistPicker {

    private static final String ELEMENTS_SENDER_VIEW =
            "com.google.android.libraries.youtube.rendering.elements.sender_view";
    private static final int IGNORE_DOUBLE_CLICK_DURATION_MS = 1500;

    private static final Handler handler = new Handler(Looper.getMainLooper());

    private static boolean registered;
    private static long lastPickerTime;
    @Nullable
    private static AlertDialog dialog;

    private ArtistPicker() {
    }

    /** Fetches the artists of every song as it starts, so the picker can open at once. */
    private static synchronized void ensureRegistered() {
        if (registered) return;
        registered = true;

        VideoInformation.addVideoIdListener(ArtistRequest::fetchIfNeeded);
        // A song may already be playing when the first command shows up.
        ArtistRequest.fetchIfNeeded(VideoInformation.getVideoId());
    }

    /**
     * Cheap check done for every command: is the song that is playing known to have several
     * artists? Only then is the command looked at.
     */
    public static boolean isArmed() {
        ensureRegistered();

        String videoId = VideoInformation.getVideoId();
        if (videoId.isEmpty()) return false;

        List<Artist> artists = ArtistRequest.getCached(videoId);
        if (artists == null) {
            // Not asked yet, or the first try failed a while ago.
            ArtistRequest.fetchIfNeeded(videoId);
            return false;
        }
        return artists.size() >= 2;
    }

    /**
     * Injection point (through the command resolver). Called on the main thread.
     *
     * @param command    The command that was clicked.
     * @param senderView The view that was clicked, null for menu entries.
     */
    public static void onCommand(@Nullable byte[] command, @Nullable Object senderView) {
        if (command == null) return;
        // Menu entries have no sender view. Menus drawn as elements are in a dialog.
        if (senderView != null && !isViewInsideDialog(senderView)) return;

        List<Artist> artists = ArtistRequest.getCached(VideoInformation.getVideoId());
        if (artists == null || artists.size() < 2) return;

        String text = new String(command, StandardCharsets.ISO_8859_1);
        // An album, a playlist or a channel page is not what this is about.
        if (text.contains("MUSIC_PAGE_TYPE_") && !text.contains("MUSIC_PAGE_TYPE_ARTIST")
                && !text.contains("MUSIC_PAGE_TYPE_USER_CHANNEL")) {
            return;
        }

        // The artist the app is about to open.
        Artist appTarget = null;
        for (Artist artist : artists) {
            if (text.contains(artist.browseId())) {
                appTarget = artist;
                break;
            }
        }
        if (appTarget == null) return;

        // One tap can resolve the same command twice.
        final long now = System.currentTimeMillis();
        if (now - lastPickerTime < IGNORE_DOUBLE_CLICK_DURATION_MS) return;
        lastPickerTime = now;

        Logger.printDebug(() -> "Go to artist with " + artists.size() + " artists");
        final Artist target = appTarget;
        // After the click is fully handled, so the menu can close first.
        handler.post(() -> showPicker(artists, target));
    }

    private static void showPicker(@NonNull List<Artist> artists, @NonNull Artist appTarget) {
        try {
            Activity activity = Utils.getActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

            if (dialog != null) dialog.dismiss();

            String[] names = new String[artists.size()];
            for (int i = 0; i < names.length; i++) {
                names[i] = artists.get(i).name();
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(
                    activity, android.R.style.Theme_DeviceDefault_Dialog_Alert);
            builder.setTitle(Txt.t("Aller vers quel artiste ?", "Go to which artist?"));
            builder.setItems(names, (d, which) -> {
                Artist chosen = artists.get(which);
                // The app is already opening this one.
                if (!chosen.browseId().equals(appTarget.browseId())) openArtist(activity, chosen);
            });
            builder.setNegativeButton(Txt.t("Annuler", "Cancel"), null);
            builder.setOnDismissListener(d -> dialog = null);
            dialog = builder.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showPicker failure", ex);
        }
    }

    private static void openArtist(@NonNull Activity activity, @NonNull Artist artist) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://music.youtube.com/channel/" + artist.browseId()));
            // Stays inside this app, and does not ask which app should open the link.
            intent.setPackage(activity.getPackageName());
            activity.startActivity(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "openArtist failure", ex);
            Utils.showToastShort(Txt.t("Impossible d’ouvrir cet artiste", "Could not open this artist"));
        }
    }

    /** True if the view is in a dialog or a bottom sheet rather than in the main screen. */
    private static boolean isViewInsideDialog(@NonNull Object viewObj) {
        if (viewObj instanceof View view) {
            Activity activity = Utils.getActivity();
            if (activity != null) {
                return view.getRootView() != activity.getWindow().getDecorView();
            }
        }
        return false;
    }
}
