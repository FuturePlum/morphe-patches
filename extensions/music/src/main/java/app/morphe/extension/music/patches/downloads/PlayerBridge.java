/*
 * Custom addition: moves the list of tracks from one player to the other, so changing player
 * does not lose what was queued. A track keeps its place in the list and the spot reached.
 */

package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.music.patches.EnableAudioVideoSwitchPatch;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

public final class PlayerBridge {

    /** A very long queue is cut, so moving it never takes minutes. */
    private static final int MAX_TRACKS = 60;
    private static final long PLAYER_START_TIMEOUT_MILLISECONDS = 6_000;

    /** A move is already running, so a second swipe does nothing. */
    private static final AtomicBoolean busy = new AtomicBoolean();

    /**
     * The list of the offline player goes to the app's own player: the track playing and the ones
     * after it, at the spot reached. The offline player pauses. Must be called on the main thread.
     */
    public static void toOnline(Context context) {
        if (!busy.compareAndSet(false, true)) return;
        PlayerRelay.cancel();
        try {
            final ArrayList<String> queue = OfflinePlaybackService.queueSnapshot();
            final int index = OfflinePlaybackService.queueCurrentIndex();
            final int position = OfflinePlaybackService.currentPositionMilliseconds();

            final List<String> ids = new ArrayList<>();
            if (index >= 0) {
                for (int i = index; i < queue.size() && ids.size() < MAX_TRACKS; i++) {
                    ids.add(LocalDownloadManager.videoIdOf(queue.get(i)));
                }
            }

            // Nothing may play behind the app's player.
            if (OfflinePlaybackService.isPlayingNow()) OfflinePlaybackService.toggle(context);

            if (ids.isEmpty()) {
                busy.set(false);
                return;
            }
            EnableAudioVideoSwitchPatch.startQueue(context, ids, position, started -> {
                busy.set(false);
                if (!started) showFailure();
            });
        } catch (Exception ex) {
            busy.set(false);
            Logger.printException(() -> "toOnline failure", ex);
        }
    }

    /**
     * The list of the app's own player goes to the offline player, which starts at the same
     * spot. A track that is not downloaded is streamed, so those need a connection.
     * Must be called on the main thread.
     */
    public static void toOffline(Context context) {
        if (!busy.compareAndSet(false, true)) return;
        PlayerRelay.cancel();
        Utils.runOnBackgroundThread(() -> {
            try {
                EnableAudioVideoSwitchPatch.QueueSnapshot snapshot =
                        EnableAudioVideoSwitchPatch.snapshotQueue();
                if (snapshot == null) {
                    showFailure();
                    return;
                }

                final List<String> ids = snapshot.videoIds.size() > MAX_TRACKS
                        ? snapshot.videoIds.subList(0, MAX_TRACKS) : snapshot.videoIds;

                // The first track starts as soon as it is found, the others join behind it.
                final String firstPath = LocalDownloadManager.playablePath(ids.get(0));
                if (firstPath == null) {
                    showFailure();
                    return;
                }
                final ArrayList<String> first = new ArrayList<>();
                first.add(firstPath);
                final int position = (int) Math.min(Integer.MAX_VALUE, snapshot.positionMilliseconds);
                Utils.runOnMainThread(() -> OfflinePlaybackService.startQueue(context, first, 0, position));

                final long deadline = SystemClock.uptimeMillis() + PLAYER_START_TIMEOUT_MILLISECONDS;
                while (!OfflinePlaybackService.isActive() && SystemClock.uptimeMillis() < deadline) {
                    Thread.sleep(100);
                }
                if (!OfflinePlaybackService.isActive()) return;

                for (int i = 1; i < ids.size(); i++) {
                    final String path = LocalDownloadManager.playablePath(ids.get(i));
                    // A track that cannot be found is skipped, the rest still follows.
                    if (path != null) Utils.runOnMainThread(() -> OfflinePlaybackService.playLast(path));
                }
            } catch (Exception ex) {
                Logger.printException(() -> "toOffline failure", ex);
            } finally {
                busy.set(false);
            }
        });
    }

    private static void showFailure() {
        Utils.runOnMainThread(() -> Utils.showToastShort(str("morphe_music_switch_player_failed")));
    }

    private PlayerBridge() {}
}
