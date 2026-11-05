/*
 * Custom addition: 'Play next' that works across the two players.
 *
 * A title of the other kind (downloaded while the app plays, not downloaded while the offline
 * player plays) is not played at once and does not change the player. It waits, and when the
 * track playing ends by itself the music is handed to the other player. When the waiting titles
 * are over, the first player carries on with its own list.
 *
 * Handing over to the offline player uses the downloaded file. Handing over to the app's player
 * puts the titles right after the one playing in its own queue.
 */

package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.music.patches.EnableAudioVideoSwitchPatch;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

public final class PlayerRelay {

    private static final long POLL_MILLISECONDS = 500;
    /** A track change this close to the end of the previous track is the track ending by itself. */
    private static final long NATURAL_END_WINDOW_MILLISECONDS = 8_000;
    /** The app's player stopped this close to the end of the last track of its queue. */
    private static final long STALLED_END_WINDOW_MILLISECONDS = 2_500;
    private static final int STALLED_TICKS = 3;
    /** The last waiting title of the app's player is over when this little of it remains. */
    private static final long LAST_TRACK_END_WINDOW_MILLISECONDS = 1_200;
    private static final long OFFLINE_START_TIMEOUT_MILLISECONDS = 6_000;
    private static final long ONLINE_START_TIMEOUT_MILLISECONDS = 20_000;
    private static final long RESUME_DELAY_MILLISECONDS = 700;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();

    /** Downloaded titles waiting for the app's player to finish its track. */
    private static final List<String> offlineGuests = new ArrayList<>();
    /** Titles (ids) not downloaded, waiting for the offline player to finish its track. */
    private static final List<String> onlineGuests = new ArrayList<>();

    /** The offline player plays the waiting titles, the app's player is paused behind it. */
    private static boolean offlineGuestActive;
    /** The offline queue was started by this class, so it does not cancel the relay. */
    private static boolean guestStartPending;

    /** The app's player plays the waiting titles, and the offline list waits behind it. */
    private static boolean onlineGuestActive;
    private static boolean offlineHostWaiting;
    private static List<String> onlineGuestIds = Collections.emptyList();
    private static boolean onlineGuestSeen;
    private static long onlineGuestStartedAt;
    private static int onlineStalledTicks;

    // What the app's player was doing at the last look.
    private static String lastId = "";
    private static long lastTime = -1;
    private static long lastLength;
    private static int stalledTicks;

    private static boolean listenerRegistered;
    private static boolean polling;

    // region Entry points

    /**
     * A downloaded title is asked to play next while the app's own player plays.
     *
     * @return Whether it was kept for later. False means the caller does what it always did.
     */
    public static boolean queueOfflineTrack(Context context, String path) {
        synchronized (LOCK) {
            if (OfflinePlaybackService.isPlayingNow() || onlineGuestActive) return false;
            if (!isMusicActive(context) || !new File(path).isFile()) return false;
            if (!offlineGuests.contains(path)) offlineGuests.add(path);
        }
        ensurePolling();
        Utils.showToastShort(str("morphe_music_relay_offline_added"));
        return true;
    }

    /** Same as {@link #queueOfflineTrack}, for a video id that may be downloaded. */
    public static boolean offerDownloaded(String videoId) {
        File local = LocalDownloadManager.localFileFor(videoId);
        Context context = Utils.getContext();
        return local != null && context != null && queueOfflineTrack(context, local.getAbsolutePath());
    }

    /** A title that is not downloaded is asked to play next while the offline player plays. */
    public static void queueOnlineTrack(String videoId) {
        synchronized (LOCK) {
            if (!onlineGuests.contains(videoId)) onlineGuests.add(videoId);
        }
        Utils.showToastShort(str("morphe_music_relay_online_added"));
    }

    public static boolean hasOnlineGuests() {
        synchronized (LOCK) {
            return !onlineGuests.isEmpty();
        }
    }

    /** The user stopped, or moved to the other player by hand: nothing is handed over anymore. */
    public static void cancel() {
        synchronized (LOCK) {
            offlineGuests.clear();
            onlineGuests.clear();
            offlineGuestActive = false;
            guestStartPending = false;
            onlineGuestActive = false;
            offlineHostWaiting = false;
        }
    }

    // endregion

    // region Offline player side

    /** Called when the offline player starts a queue: one that the user chose ends the relay. */
    static void offlineQueueStarted() {
        synchronized (LOCK) {
            if (guestStartPending) {
                guestStartPending = false;
                return;
            }
            offlineGuestActive = false;
            offlineGuests.clear();
        }
    }

    /** The offline queue is over. @return Whether the app's player has to carry on. */
    static boolean offlineQueueFinished() {
        synchronized (LOCK) {
            boolean back = offlineGuestActive;
            offlineGuestActive = false;
            return back;
        }
    }

    /** The app's player is paused where it was, and plays again. */
    static void resumeOnline(Context context) {
        handler.postDelayed(() -> EnableAudioVideoSwitchPatch.resumeNative(context),
                RESUME_DELAY_MILLISECONDS);
    }

    /**
     * The offline player let go after a track, because titles wait for the app's player.
     *
     * @param offlineRemains Whether the offline list still has tracks, which then wait paused.
     */
    static void startOnlineGuests(Context context, boolean offlineRemains) {
        final List<String> ids;
        synchronized (LOCK) {
            ids = new ArrayList<>(onlineGuests);
            onlineGuests.clear();
            offlineHostWaiting = offlineRemains;
            onlineGuestIds = ids;
            onlineGuestActive = true;
            onlineGuestSeen = false;
            onlineStalledTicks = 0;
            onlineGuestStartedAt = System.currentTimeMillis();
        }
        EnableAudioVideoSwitchPatch.playNextInNative(context, ids, started -> {
            if (started) {
                ensurePolling();
                return;
            }
            Utils.showToastShort(str("morphe_music_relay_failed"));
            finishOnlineGuests(context);
        });
    }

    // endregion

    // region Watching the app's player

    private static void ensurePolling() {
        synchronized (LOCK) {
            if (!listenerRegistered) {
                listenerRegistered = true;
                VideoInformation.addVideoIdListener(id -> handler.post(() -> onNativeVideoId(id)));
            }
            if (polling) return;
            polling = true;
        }
        handler.postDelayed(PlayerRelay::tick, POLL_MILLISECONDS);
    }

    private static void tick() {
        boolean again;
        try {
            again = look();
        } catch (Exception ex) {
            Logger.printException(() -> "PlayerRelay tick failure", ex);
            again = false;
        }
        synchronized (LOCK) {
            polling = again;
        }
        if (again) handler.postDelayed(PlayerRelay::tick, POLL_MILLISECONDS);
    }

    /** @return Whether there is still something to watch. */
    private static boolean look() {
        Context context = Utils.getContext();
        if (context == null) return true;

        final String id = VideoInformation.getVideoId();
        final long time = VideoInformation.getVideoTime();
        final long length = VideoInformation.getVideoLength();
        final boolean musicActive = isMusicActive(context);

        boolean handOver = false;
        synchronized (LOCK) {
            if (onlineGuestActive) return lookAtOnlineGuests(context, id, time, length, musicActive);
            if (offlineGuests.isEmpty()) return false;

            if (OfflinePlaybackService.isPlayingNow()) {
                // The user went to the offline player by hand: the titles join its list.
                for (String path : offlineGuests) OfflinePlaybackService.playLast(path);
                offlineGuests.clear();
                return false;
            }

            if (!id.isEmpty() && length > 0 && time >= 0) {
                lastId = id;
                lastTime = time;
                lastLength = length;
                // The last track of the queue ended and nothing follows: the player just stops.
                if (!musicActive && length - time <= STALLED_END_WINDOW_MILLISECONDS) {
                    handOver = ++stalledTicks >= STALLED_TICKS;
                } else {
                    stalledTicks = 0;
                }
            }
        }
        if (handOver) handOverToOffline(context);
        return true;
    }

    /** The app started another track. When the one before ended by itself, the offline player takes over. */
    private static void onNativeVideoId(String newId) {
        Context context = Utils.getContext();
        if (context == null) return;
        boolean handOver = false;
        synchronized (LOCK) {
            final String previous = lastId;
            final boolean natural = !previous.isEmpty() && !previous.equals(newId)
                    && lastLength > 0 && lastLength - lastTime <= NATURAL_END_WINDOW_MILLISECONDS;
            lastId = newId;
            lastTime = -1;
            lastLength = 0;
            stalledTicks = 0;
            if (onlineGuestActive || offlineGuests.isEmpty()
                    || OfflinePlaybackService.isPlayingNow()) {
                return;
            }
            handOver = natural;
        }
        if (handOver) handOverToOffline(context);
    }

    private static void handOverToOffline(Context context) {
        final ArrayList<String> paths = new ArrayList<>();
        synchronized (LOCK) {
            for (String path : offlineGuests) {
                if (new File(path).isFile()) paths.add(path);
            }
            offlineGuests.clear();
            stalledTicks = 0;
            if (paths.isEmpty()) return;
            offlineGuestActive = true;
            guestStartPending = true;
        }
        Logger.printDebug(() -> "Relay: the offline player takes over with " + paths.size() + " track(s)");
        OfflinePlaybackService.startQueue(context, paths, 0, 0);

        // Android may refuse to start the player while the app is in the background.
        handler.postDelayed(() -> {
            synchronized (LOCK) {
                if (OfflinePlaybackService.isActive() || !guestStartPending) return;
                guestStartPending = false;
                offlineGuestActive = false;
            }
            Utils.showToastShort(str("morphe_music_relay_failed"));
        }, OFFLINE_START_TIMEOUT_MILLISECONDS);
    }

    /** Must be called with the lock held. @return Whether there is still something to watch. */
    private static boolean lookAtOnlineGuests(Context context, String id, long time, long length,
                                              boolean musicActive) {
        if (onlineGuestIds.contains(id)) {
            onlineGuestSeen = true;
            final boolean last = id.equals(onlineGuestIds.get(onlineGuestIds.size() - 1));
            if (last && length > 0 && time >= 0) {
                final long remaining = length - time;
                if (remaining <= LAST_TRACK_END_WINDOW_MILLISECONDS) {
                    handler.post(() -> finishOnlineGuests(context));
                    return false;
                }
                // The app's player stopped near the end of the last waiting title.
                if (!musicActive && remaining <= STALLED_END_WINDOW_MILLISECONDS
                        && ++onlineStalledTicks >= STALLED_TICKS) {
                    handler.post(() -> finishOnlineGuests(context));
                    return false;
                }
                if (musicActive) onlineStalledTicks = 0;
            }
            return true;
        }
        // Another track than the waiting ones plays: they are over (or the user chose another one).
        if (onlineGuestSeen) {
            handler.post(() -> finishOnlineGuests(context));
            return false;
        }
        // The app's player never reached the waiting titles.
        if (System.currentTimeMillis() - onlineGuestStartedAt > ONLINE_START_TIMEOUT_MILLISECONDS) {
            handler.post(() -> finishOnlineGuests(context));
            return false;
        }
        return true;
    }

    /** The waiting titles of the app's player are over: the offline list carries on, if any. */
    private static void finishOnlineGuests(Context context) {
        final boolean resumeOffline;
        synchronized (LOCK) {
            onlineGuestActive = false;
            resumeOffline = offlineHostWaiting;
            offlineHostWaiting = false;
        }
        if (resumeOffline) OfflinePlaybackService.toggle(context);
    }

    // endregion

    private static boolean isMusicActive(Context context) {
        return context.getSystemService(Context.AUDIO_SERVICE) instanceof AudioManager audioManager
                && audioManager.isMusicActive();
    }

    private PlayerRelay() {}
}
