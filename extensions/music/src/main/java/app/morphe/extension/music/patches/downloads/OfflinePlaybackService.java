/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.audiofx.AudioEffect;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.KeyEvent;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/** Foreground offline player with audio focus, MediaSession and lock-screen controls. */
public final class OfflinePlaybackService extends Service {
    public interface PlaybackListener {
        void onPlaybackChanged(String path, String title, boolean playing, int position, int duration);
    }

    private static final Set<PlaybackListener> listeners = new CopyOnWriteArraySet<>();
    private static volatile String currentPath = "";
    private static volatile String currentTitle = "";
    private static volatile boolean currentPlaying;
    private static volatile int currentPosition;
    private static volatile int currentDuration;
    private static volatile OfflinePlaybackService instance;

    public static final int REPEAT_OFF = 0;
    public static final int REPEAT_ALL = 1;
    public static final int REPEAT_ONE = 2;

    /** Going back after this long into a track restarts it instead of playing the previous one. */
    private static final int RESTART_THRESHOLD_MILLISECONDS = 3000;

    private static volatile boolean shuffleEnabled;
    private static volatile int repeatMode = REPEAT_OFF;

    /** Changes every time the queue is edited, so a screen can tell when to redraw it. */
    private static volatile int queueVersion;

    /** {@link SystemClock#elapsedRealtime()} when playback pauses by itself, or 0 when off. */
    private static volatile long sleepEndsAt;
    private static volatile boolean sleepAtEndOfTrack;

    public static void addListener(PlaybackListener listener) {
        listeners.add(listener);
        listener.onPlaybackChanged(currentPath, currentTitle, currentPlaying, currentPosition, currentDuration);
    }

    public static void removeListener(PlaybackListener listener) { listeners.remove(listener); }

    public static void toggle(Context context) {
        context.startService(new Intent(context, OfflinePlaybackService.class).setAction(ACTION_TOGGLE));
    }

    public static void skipNext(Context context) {
        context.startService(new Intent(context, OfflinePlaybackService.class).setAction(ACTION_NEXT));
    }
    public static void skipPrevious(Context context) {
        context.startService(new Intent(context, OfflinePlaybackService.class).setAction(ACTION_PREVIOUS));
    }

    public static boolean isActive() {
        OfflinePlaybackService service = instance;
        return service != null && service.player != null;
    }

    /**
     * True while a downloaded track is really playing (not paused, not handed over to the app's own player).
     */
    public static boolean isPlayingNow() {
        OfflinePlaybackService service = instance;
        return service != null && service.player != null && service.player.isPlaying();
    }

    public static boolean isShuffleEnabled() { return shuffleEnabled; }

    public static int getRepeatMode() { return repeatMode; }

    public static int getQueueVersion() { return queueVersion; }

    public static long getSleepEndsAt() { return sleepEndsAt; }

    public static boolean isSleepAtEndOfTrack() { return sleepAtEndOfTrack; }

    /** Turns shuffle on or off, for the queue that plays and for the ones started afterwards. */
    public static void setShuffle(boolean enabled) {
        shuffleEnabled = enabled;
        OfflinePlaybackService service = instance;
        if (service != null && service.player != null) service.applyShuffle(enabled);
    }

    /** Off, then repeat all, then repeat the current track. */
    public static void cycleRepeat() {
        repeatMode = (repeatMode + 1) % 3;
        queueVersion++;
        OfflinePlaybackService service = instance;
        if (service != null) {
            service.publishState();
            service.persist();
        }
    }

    /**
     * When the app was killed in the background, the queue is put back (paused, at the same
     * spot) the next time the Download page opens. Does nothing when a queue is already there.
     */
    public static void restoreIfNeeded(Context context) {
        if (instance != null) return;
        try {
            if (OfflineQueueStore.load(context) == null) return;
            context.startService(new Intent(context, OfflinePlaybackService.class)
                    .setAction(ACTION_RESTORE));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not restore the offline queue", ex);
        }
    }

    public static ArrayList<String> queueSnapshot() {
        OfflinePlaybackService service = instance;
        return service == null ? new ArrayList<>() : new ArrayList<>(service.queue);
    }

    /** @return Index of the playing track in {@link #queueSnapshot()}, or -1. */
    public static int queueCurrentIndex() {
        OfflinePlaybackService service = instance;
        return service == null ? -1 : service.queueIndex;
    }

    public static void jumpTo(int index) {
        OfflinePlaybackService service = instance;
        if (service != null) service.jumpToIndex(index);
    }

    public static void moveInQueue(int from, int to) {
        OfflinePlaybackService service = instance;
        if (service != null) service.moveQueueItem(from, to);
    }

    public static void removeFromQueue(int index) {
        OfflinePlaybackService service = instance;
        if (service != null) service.removeQueueItem(index);
    }

    /** Pauses by itself after the given time. */
    public static void setSleepTimer(int minutes) {
        OfflinePlaybackService service = instance;
        if (service != null) service.armSleepTimer(minutes);
    }

    /** Stops by itself once the track playing now is over. */
    public static void setSleepAtEndOfTrack() {
        OfflinePlaybackService service = instance;
        if (service != null) service.armSleepAtEndOfTrack();
    }

    public static void cancelSleepTimer() {
        OfflinePlaybackService service = instance;
        if (service != null) {
            service.clearSleepTimer();
        } else {
            sleepEndsAt = 0;
            sleepAtEndOfTrack = false;
        }
    }

    /**
     * Puts a track right after the one playing, like 'Play next' in the stock player.
     *
     * @return The queue as it is now, or null when nothing is playing, in which case the
     *         caller has to start the track instead.
     */
    @Nullable
    public static ArrayList<String> playNext(@Nullable String trackPath) {
        OfflinePlaybackService service = instance;
        if (service == null || service.player == null || trackPath == null
                || !new File(trackPath).isFile()) {
            return null;
        }
        service.queueNext(trackPath);
        return new ArrayList<>(service.queue);
    }

    /**
     * Puts a track at the end of the queue.
     *
     * @return The queue as it is now, or null when nothing is playing.
     */
    @Nullable
    public static ArrayList<String> playLast(@Nullable String trackPath) {
        OfflinePlaybackService service = instance;
        if (service == null || service.player == null || trackPath == null
                || !new File(trackPath).isFile()) {
            return null;
        }
        service.queueLast(trackPath);
        return new ArrayList<>(service.queue);
    }

    /**
     * Inserts a track after the current one. A track that is already in the queue is moved,
     * so a path never shows up twice and the current position stays easy to find.
     *
     * @return The index of the current track, which moves when earlier copies are removed.
     */
    static int insertNext(List<String> queue, int currentIndex, String added) {
        if (currentIndex < 0 || currentIndex >= queue.size()) return currentIndex;
        if (added.equals(queue.get(currentIndex))) return currentIndex;

        for (int i = queue.size() - 1; i >= 0; i--) {
            if (i != currentIndex && added.equals(queue.get(i))) {
                queue.remove(i);
                if (i < currentIndex) currentIndex--;
            }
        }
        queue.add(currentIndex + 1, added);
        return currentIndex;
    }

    /** @return Where the track playing now is, in milliseconds. */
    public static int currentPositionMilliseconds() {
        return currentPosition;
    }

    /**
     * Starts a whole queue at the given track and spot, replacing the one that was there.
     * Used when the queue of the app's own player moves over to this player.
     */
    public static void startQueue(Context context, ArrayList<String> paths, int index,
                                  int startPositionMilliseconds) {
        if (paths.isEmpty() || index < 0 || index >= paths.size()) return;
        OfflineTrack track = OfflineTrack.load(new File(paths.get(index)));
        context.startService(new Intent(context, OfflinePlaybackService.class)
                .setAction(ACTION_PLAY_FILE)
                .putExtra(EXTRA_PATH, track.audioFile().getAbsolutePath())
                .putStringArrayListExtra(EXTRA_QUEUE, paths)
                .putExtra(EXTRA_QUEUE_INDEX, index)
                .putExtra(EXTRA_TITLE, track.displayTitle())
                .putExtra(EXTRA_ARTIST, track.displayArtist())
                .putExtra(EXTRA_ARTWORK_PATH, track.artworkFile().getAbsolutePath())
                .putExtra(EXTRA_START_POSITION, Math.max(0, startPositionMilliseconds)));
    }

    public static void seekTo(int position) {
        OfflinePlaybackService service = instance;
        if (service != null && service.playerReady()) {
            service.player.seekTo(Math.max(0, Math.min(position, service.player.getDuration())));
            service.publishState();
        }
    }
    public static final String ACTION_PLAY_FILE = "app.morphe.action.PLAY_OFFLINE_FILE";
    /** Brings the queue saved before Android killed the app back, paused where it was. */
    public static final String ACTION_RESTORE = "app.morphe.action.RESTORE_OFFLINE_QUEUE";
    public static final String ACTION_TOGGLE = "app.morphe.action.TOGGLE_OFFLINE_PLAYBACK";
    public static final String ACTION_STOP = "app.morphe.action.STOP_OFFLINE_PLAYBACK";
    public static final String ACTION_NEXT = "app.morphe.action.NEXT_OFFLINE_TRACK";
    public static final String ACTION_PREVIOUS = "app.morphe.action.PREVIOUS_OFFLINE_TRACK";
    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_QUEUE = "queue";
    public static final String EXTRA_QUEUE_INDEX = "queue_index";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_ARTIST = "artist";
    public static final String EXTRA_ARTWORK_PATH = "artwork_path";
    public static final String EXTRA_START_POSITION = "start_position";

    private static final String CHANNEL_ID = "morphe_offline_playback";
    private static final int NOTIFICATION_ID = 8841;

    private MediaPlayer player;
    private boolean prepared;
    private MediaSession session;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private int audioEffectSessionId;
    private String path = "";
    private String publishedMetadataPath = "";
    private String title = "YouTube Music";
    private String artist = "YouTube Music";
    private Bitmap artwork;
    private ArrayList<String> queue = new ArrayList<>();
    /** The queue before it was shuffled, so turning shuffle off can bring the order back. */
    private ArrayList<String> originalOrder = new ArrayList<>();
    private int queueIndex = -1;
    /** Where the track was when the queue was last saved, used while the player is not ready. */
    private int savedPositionMilliseconds;
    private int persistTicks;
    private boolean foregroundStarted;
    private final Runnable sleepRunnable = new Runnable() {
        @Override public void run() {
            sleepEndsAt = 0;
            pause();
        }
    };
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressTicker = new Runnable() {
        @Override public void run() {
            publishState();
            // The spot in the track is saved every ten seconds, not on every tick.
            if (playerReady() && player.isPlaying() && ++persistTicks >= 20) {
                persistTicks = 0;
                persist();
            }
            if (player != null) progressHandler.postDelayed(this, 500);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createFocusRequest();
        createChannel();
        session = new MediaSession(this, "MorpheOfflinePlayback");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { playQueueOffset(1); }
            @Override public void onSkipToPrevious() { playQueueOffset(-1); }
            @Override public void onStop() { stopPlayback(true); }
            @Override public void onSeekTo(long pos) {
                if (playerReady()) player.seekTo((int) Math.min(Integer.MAX_VALUE, pos));
                publishState();
            }
        });
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) session.setSessionActivity(PendingIntent.getActivity(this, 3, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_PLAY_FILE.equals(action)) {
            ArrayList<String> suppliedQueue = intent.getStringArrayListExtra(EXTRA_QUEUE);
            if (suppliedQueue != null) {
                PlayerRelay.offlineQueueStarted();
                queue = suppliedQueue;
                queueIndex = intent.getIntExtra(EXTRA_QUEUE_INDEX, -1);
                originalOrder = new ArrayList<>(queue);
                if (shuffleEnabled) shuffleKeepingCurrent();
                queueVersion++;
            }
            playFile(intent.getStringExtra(EXTRA_PATH), intent.getStringExtra(EXTRA_TITLE),
                    intent.getStringExtra(EXTRA_ARTIST), intent.getStringExtra(EXTRA_ARTWORK_PATH),
                    true, Math.max(0, intent.getIntExtra(EXTRA_START_POSITION, 0)));
        } else if (ACTION_RESTORE.equals(action)) {
            if (player == null) restoreFromStore(false);
        } else if (ACTION_TOGGLE.equals(action)) {
            if (player == null) restoreFromStore(true);
            else if (playerReady() && player.isPlaying()) pause();
            else resume();
        } else if (ACTION_STOP.equals(action)) {
            PlayerRelay.cancel();
            stopPlayback(true);
        } else if (ACTION_NEXT.equals(action)) {
            playQueueOffset(1);
        } else if (ACTION_PREVIOUS.equals(action)) {
            playQueueOffset(-1);
        }
        return START_NOT_STICKY;
    }

    /** Puts the queue saved earlier back. The track is ready to play, it starts only on request. */
    private void restoreFromStore(boolean autoStart) {
        OfflineQueueStore.State state = OfflineQueueStore.load(this);
        if (state == null) {
            if (!autoStart) stopSelf();
            return;
        }
        shuffleEnabled = state.shuffle;
        repeatMode = state.repeat;
        queue = state.queue;
        originalOrder = state.originalOrder;
        queueIndex = state.index;
        queueVersion++;

        OfflineTrack track = OfflineTrack.load(new File(queue.get(queueIndex)));
        playFile(track.audioFile().getAbsolutePath(), track.displayTitle(), track.displayArtist(),
                track.artworkFile().getAbsolutePath(), autoStart, state.positionMilliseconds);
    }

    /** Saves the queue and the spot in the track, so both come back if the app is killed. */
    private void persist() {
        if (queue.isEmpty() || queueIndex < 0 || queueIndex >= queue.size()) return;
        if (playerReady()) savedPositionMilliseconds = player.getCurrentPosition();
        OfflineQueueStore.save(this, queue, originalOrder, queueIndex, shuffleEnabled,
                repeatMode, savedPositionMilliseconds);
    }

    private void enterForeground() {
        try {
            startForeground(NOTIFICATION_ID, notification());
            foregroundStarted = true;
        } catch (Exception ex) {
            // Android refuses this when the app is in the background and nothing asked for it.
            Logger.printException(() -> "Could not start the playback notification", ex);
        }
    }

    private void playFile(@Nullable String path, @Nullable String requestedTitle,
                          @Nullable String requestedArtist, @Nullable String artworkPath,
                          boolean autoStart, int startPosition) {
        if (path == null || !new File(path).isFile()) return;
        this.path = path;
        savedPositionMilliseconds = startPosition;
        persist();
        title = requestedTitle == null ? new File(path).getName() : requestedTitle;
        artist = requestedArtist == null || requestedArtist.isBlank() ? "YouTube Music" : requestedArtist;
        // A track saved without a cover has no file, and decoding it would log an error a second.
        File artworkFile = artworkPath == null ? null : new File(artworkPath);
        artwork = artworkFile != null && artworkFile.isFile()
                ? BitmapFactory.decodeFile(artworkPath)
                : null;
        releasePlayer();
        try {
            if (autoStart) {
                pauseOtherMedia();
                if (audioFocusDenied()) return;
            }
            player = new MediaPlayer();
            player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            final boolean streamed = LocalDownloadManager.isStreamPath(path);
            if (!streamed) player.setDataSource(path);
            // Skipping quickly leaves the previous player mid prepare. Its callbacks are
            // cleared on release, and each one still checks that it belongs to the current
            // track, so a discarded player can never tear down the one that replaced it.
            player.setOnPreparedListener(mp -> {
                if (mp != player) return;
                prepared = true;
                openAudioEffectSession(mp);
                if (startPosition > 0) mp.seekTo(Math.min(startPosition, mp.getDuration()));
                if (autoStart) mp.start();
                publishState();
                if (autoStart) enterForeground();
                progressHandler.removeCallbacks(progressTicker);
                progressHandler.post(progressTicker);
            });
            player.setOnCompletionListener(mp -> {
                if (mp != player) return;
                if (sleepAtEndOfTrack) {
                    sleepAtEndOfTrack = false;
                    // The next start picks up with the following track, from its beginning.
                    if (queueIndex + 1 < queue.size()) queueIndex++;
                    savedPositionMilliseconds = 0;
                    queueVersion++;
                    OfflineQueueStore.save(this, queue, originalOrder, queueIndex,
                            shuffleEnabled, repeatMode, 0);
                    stopPlayback(false);
                } else if (PlayerRelay.hasOnlineGuests()) {
                    handOverToOnline();
                } else if (repeatMode == REPEAT_ONE) {
                    mp.seekTo(0);
                    mp.start();
                    publishState();
                } else if (queueIndex >= 0 && queueIndex + 1 < queue.size()) {
                    playQueueOffset(1);
                } else if (repeatMode == REPEAT_ALL && !queue.isEmpty()) {
                    playQueueOffset(1);
                } else {
                    final boolean back = PlayerRelay.offlineQueueFinished();
                    final Context context = getApplicationContext();
                    stopPlayback(true);
                    if (back) PlayerRelay.resumeOnline(context);
                }
            });
            player.setOnErrorListener((mp, what, extra) -> {
                if (mp != player) return true;
                Logger.printException(() -> "Offline player error: " + what + "/" + extra);
                if (streamed && skipUnplayable()) return true;
                stopPlayback(false);
                return true;
            });
            if (streamed) startStreaming(player, path); else player.prepareAsync();
            if (autoStart) enterForeground();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not start offline playback", ex);
            stopPlayback(false);
        }
    }

    /**
     * A track that is streamed has no file: its address is found first, in the background,
     * then the player is pointed at it.
     */
    private void startStreaming(MediaPlayer target, String streamPath) {
        final String name = new File(streamPath).getName();
        final String videoId = name.endsWith(LocalDownloadManager.STREAM_EXTENSION)
                ? name.substring(0, name.length() - LocalDownloadManager.STREAM_EXTENSION.length())
                : name;
        Utils.submitOnBackgroundThread(() -> {
            final String url = LocalDownloadManager.resolveStreamUrl(videoId);
            Utils.runOnMainThread(() -> {
                // The track was skipped, or the player replaced, while its address was searched.
                if (target != player) return;
                try {
                    if (url == null) throw new IOException("No stream address for " + videoId);
                    Map<String, String> headers = new HashMap<>();
                    headers.put("User-Agent", LocalDownloadManager.userAgent());
                    headers.put("Referer", "https://music.youtube.com/");
                    target.setDataSource(this, Uri.parse(url), headers);
                    target.prepareAsync();
                } catch (Exception ex) {
                    Logger.printException(() -> "Could not stream " + videoId, ex);
                    if (!skipUnplayable()) stopPlayback(false);
                }
            });
            return null;
        });
    }

    /**
     * A streamed track that cannot play (no connection, removed video) is passed over,
     * so one bad track does not end the whole queue.
     *
     * @return Whether another track took over.
     */
    private boolean skipUnplayable() {
        Utils.showToastShort(str("morphe_music_stream_skipped"));
        if (queueIndex >= 0 && queueIndex + 1 < queue.size()) {
            playQueueOffset(1);
            return true;
        }
        return false;
    }

    /**
     * Titles wait for the app's player: this player lets go after the track, and what remains
     * of its list waits where it stands, until those titles are over.
     */
    private void handOverToOnline() {
        final boolean remains;
        if (repeatMode == REPEAT_ONE) {
            remains = true;
        } else if (queueIndex >= 0 && queueIndex + 1 < queue.size()) {
            queueIndex++;
            remains = true;
        } else if (repeatMode == REPEAT_ALL && !queue.isEmpty()) {
            queueIndex = 0;
            remains = true;
        } else {
            remains = false;
        }
        savedPositionMilliseconds = 0;
        queueVersion++;
        if (remains) {
            OfflineQueueStore.save(this, queue, originalOrder, queueIndex,
                    shuffleEnabled, repeatMode, 0);
        } else {
            PlayerRelay.offlineQueueFinished();
        }
        final Context context = getApplicationContext();
        stopPlayback(!remains);
        PlayerRelay.startOnlineGuests(context, remains);
    }

    private void queueLast(String added) {
        // A track that was started without a queue is its own queue.
        if (queueIndex < 0 || queueIndex >= queue.size() || !queue.get(queueIndex).equals(path)) {
            queue = new ArrayList<>();
            queue.add(path);
            queueIndex = 0;
        }
        if (!queue.contains(added)) {
            queue.add(added);
            originalOrder.remove(added);
            originalOrder.add(added);
        }
        queueVersion++;
        publishState();
        persist();
    }

    private void queueNext(String added) {
        // A track that was started without a queue is its own queue.
        if (queueIndex < 0 || queueIndex >= queue.size() || !queue.get(queueIndex).equals(path)) {
            queue = new ArrayList<>();
            queue.add(path);
            queueIndex = 0;
        }
        queueIndex = insertNext(queue, queueIndex, added);

        // Turning shuffle off must not send the track that was queued next to the end.
        int currentOriginal = originalOrder.indexOf(path);
        originalOrder.remove(added);
        originalOrder.add(currentOriginal < 0 ? originalOrder.size() : currentOriginal + 1, added);
        queueVersion++;
        publishState();
        persist();
    }

    private void playQueueOffset(int offset) {
        // Like the stock player, going back late in a track starts it over.
        if (offset < 0 && playerReady()
                && player.getCurrentPosition() > RESTART_THRESHOLD_MILLISECONDS) {
            player.seekTo(0);
            publishState();
            return;
        }

        int target = queueIndex + offset;
        if (target >= queue.size()) {
            if (repeatMode != REPEAT_ALL || queue.isEmpty()) return;
            target = 0;
        } else if (target < 0) {
            if (repeatMode == REPEAT_ALL && !queue.isEmpty()) {
                target = queue.size() - 1;
            } else {
                if (playerReady()) player.seekTo(0);
                publishState();
                return;
            }
        }
        queueIndex = target;
        queueVersion++;
        playCurrent();
    }

    private void playCurrent() {
        if (queueIndex < 0 || queueIndex >= queue.size()) return;
        OfflineTrack track = OfflineTrack.load(new File(queue.get(queueIndex)));
        playFile(track.audioFile().getAbsolutePath(), track.displayTitle(), track.displayArtist(),
                track.artworkFile().getAbsolutePath(), true, 0);
    }

    private void jumpToIndex(int index) {
        if (index < 0 || index >= queue.size()) return;
        queueIndex = index;
        queueVersion++;
        playCurrent();
    }

    private void moveQueueItem(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= queue.size() || to >= queue.size()) return;
        String moved = queue.remove(from);
        queue.add(to, moved);
        if (queueIndex == from) queueIndex = to;
        else if (from < queueIndex && to >= queueIndex) queueIndex--;
        else if (from > queueIndex && to <= queueIndex) queueIndex++;
        queueVersion++;
        publishState();
        persist();
    }

    private void removeQueueItem(int index) {
        // The track playing is not removed, stopping it is the job of the other buttons.
        if (index < 0 || index >= queue.size() || index == queueIndex) return;
        String removed = queue.remove(index);
        originalOrder.remove(removed);
        if (index < queueIndex) queueIndex--;
        queueVersion++;
        publishState();
        persist();
    }

    /** A new queue starts with the chosen track, followed by the others in a random order. */
    private void shuffleKeepingCurrent() {
        if (queueIndex < 0 || queueIndex >= queue.size()) return;
        String current = queue.get(queueIndex);
        ArrayList<String> rest = new ArrayList<>(queue);
        rest.remove(queueIndex);
        Collections.shuffle(rest);
        queue = new ArrayList<>();
        queue.add(current);
        queue.addAll(rest);
        queueIndex = 0;
    }

    /** Shuffle only touches what is still to come, the tracks already played stay behind. */
    private void applyShuffle(boolean enabled) {
        if (queueIndex >= 0 && queueIndex < queue.size()) {
            ArrayList<String> upcoming = new ArrayList<>(queue.subList(queueIndex + 1, queue.size()));
            if (enabled) {
                originalOrder = new ArrayList<>(queue);
                Collections.shuffle(upcoming);
            } else {
                final List<String> original = originalOrder;
                upcoming.sort(Comparator.comparingInt(path -> {
                    int position = original.indexOf(path);
                    return position < 0 ? Integer.MAX_VALUE : position;
                }));
            }
            ArrayList<String> merged = new ArrayList<>(queue.subList(0, queueIndex + 1));
            merged.addAll(upcoming);
            queue = merged;
        }
        queueVersion++;
        publishState();
        persist();
    }

    private void armSleepTimer(int minutes) {
        clearSleepTimer();
        final long delay = minutes * 60_000L;
        sleepEndsAt = SystemClock.elapsedRealtime() + delay;
        progressHandler.postDelayed(sleepRunnable, delay);
        publishState();
    }

    private void armSleepAtEndOfTrack() {
        clearSleepTimer();
        sleepAtEndOfTrack = true;
        publishState();
    }

    private void clearSleepTimer() {
        progressHandler.removeCallbacks(sleepRunnable);
        sleepEndsAt = 0;
        sleepAtEndOfTrack = false;
        publishState();
    }

    private void resume() {
        if (!playerReady()) return;
        if (audioFocusDenied()) return;
        player.start();
        publishState();
        // After a restore the service has no notification yet.
        if (foregroundStarted) notifyChanged(); else enterForeground();
    }

    private void pause() {
        if (!playerReady() || !player.isPlaying()) return;
        player.pause();
        publishState();
        notifyChanged();
        persist();
    }

    /** @param forget Whether the saved queue goes too, or stays for the next time. */
    private void stopPlayback(boolean forget) {
        if (forget) OfflineQueueStore.clear(this);
        foregroundStarted = false;
        releasePlayer();
        progressHandler.removeCallbacks(progressTicker);
        if (audioManager != null && focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
        publishedMetadataPath = "";
        if (session != null) {
            session.setPlaybackState(new PlaybackState.Builder().setState(
                    PlaybackState.STATE_STOPPED, 0, 0).build());
        }
        progressHandler.removeCallbacks(sleepRunnable);
        sleepEndsAt = 0;
        sleepAtEndOfTrack = false;

        // Screens would otherwise keep showing a track that is no longer playing.
        currentPath = "";
        currentTitle = "";
        currentPlaying = false;
        currentPosition = 0;
        currentDuration = 0;
        for (PlaybackListener listener : listeners) {
            listener.onPlaybackChanged("", "", false, 0, 0);
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /**
     * Asking a player that is still preparing about its state is an invalid operation, which it
     * answers with an error that stops playback, so nothing may reach it before it is ready.
     */
    private boolean playerReady() {
        return prepared && player != null;
    }

    private void openAudioEffectSession(MediaPlayer mediaPlayer) {
        int sessionId = mediaPlayer.getAudioSessionId();
        if (sessionId <= 0) return;

        audioEffectSessionId = sessionId;
        sendBroadcast(new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName())
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC));
    }

    private void closeAudioEffectSession() {
        int sessionId = audioEffectSessionId;
        if (sessionId <= 0) return;

        audioEffectSessionId = 0;
        sendBroadcast(new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName()));
    }

    private void releasePlayer() {
        prepared = false;
        progressHandler.removeCallbacks(progressTicker);
        closeAudioEffectSession();

        MediaPlayer released = player;
        if (released == null) return;

        // Cleared first, otherwise releasing a player that is still preparing reports an error
        // that would stop whatever is playing by then.
        player = null;
        released.setOnPreparedListener(null);
        released.setOnCompletionListener(null);
        released.setOnErrorListener(null);
        released.reset();
        released.release();
    }

    private void createFocusRequest() {
        if (audioManager == null) return;
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(change -> {
                    if (change == AudioManager.AUDIOFOCUS_LOSS ||
                            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                        pause();
                    }
                })
                .build();
    }

    /**
     * @return Whether the system refused audio focus, so playback must not start.
     */
    private boolean audioFocusDenied() {
        return audioManager == null || focusRequest == null
                || audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    /** Pause the currently routed system player before this session becomes active. */
    private void pauseOtherMedia() {
        if (audioManager == null || !audioManager.isMusicActive()) return;
        KeyEvent down = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE);
        KeyEvent up = new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE);
        audioManager.dispatchMediaKeyEvent(down);
        audioManager.dispatchMediaKeyEvent(up);
    }

    private void publishState() {
        MediaSession currentSession = session;
        if (currentSession == null) return;

        final boolean playing = playerReady() && player.isPlaying();
        final long position = playerReady() ? player.getCurrentPosition() : 0;
        currentSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE |
                        PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP |
                        PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_SKIP_TO_NEXT |
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        position, playing ? 1f : 0f)
                .build());
        currentPath = path == null ? "" : path;
        currentTitle = title;
        currentPlaying = playing;
        currentPosition = (int) position;
        currentDuration = playerReady() ? player.getDuration() : 0;
        for (PlaybackListener listener : listeners) {
            listener.onPlaybackChanged(currentPath, currentTitle, currentPlaying, currentPosition, currentDuration);
        }
        // Rebuilding the metadata copies the artwork, so only do it when the track changed.
        if (!currentPath.equals(publishedMetadataPath)) {
            publishedMetadataPath = currentPath;
            MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, currentDuration);
            if (artwork != null) {
                metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
                        .putBitmap(MediaMetadata.METADATA_KEY_ART, artwork)
                        .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, artwork);
            }
            currentSession.setMetadata(metadata.build());
        }
    }

    @SuppressLint("WrongConstant")
    private Notification notification() {
        final boolean playing = playerReady() && player.isPlaying();
        PendingIntent toggle = serviceIntent(ACTION_TOGGLE, 1);
        PendingIntent stop = serviceIntent(ACTION_STOP, 2);
        PendingIntent previous = serviceIntent(ACTION_PREVIOUS, 4);
        PendingIntent next = serviceIntent(ACTION_NEXT, 5);
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent content = PendingIntent.getActivity(this, 3, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_headset)
                .setContentTitle(title)
                .setContentText(artist)
                .setLargeIcon(artwork)
                .setContentIntent(content)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(true)
                .addAction(action(android.R.drawable.ic_media_previous,
                        str("morphe_music_downloads_previous"), previous))
                .addAction(action(
                        playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        str(playing ? "morphe_music_downloads_pause" : "morphe_music_downloads_play"),
                        toggle))
                .addAction(action(android.R.drawable.ic_media_next,
                        str("morphe_music_downloads_next"), next))
                .addAction(action(android.R.drawable.ic_menu_close_clear_cancel,
                        str("morphe_music_downloads_close"), stop))
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .build();
    }

    private Notification.Action action(int iconResource, String title, PendingIntent intent) {
        return new Notification.Action.Builder(
                Icon.createWithResource(this, iconResource), title, intent).build();
    }

    private PendingIntent serviceIntent(String action, int code) {
        Intent intent = new Intent(this, OfflinePlaybackService.class).setAction(action);
        //noinspection WrongConstant
        return PendingIntent.getService(this, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @SuppressLint("NotificationPermission") // The patch declares POST_NOTIFICATIONS.
    private void notifyChanged() {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification());
    }

    private void createChannel() {
        //noinspection WrongConstant
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                str("morphe_music_downloads_playback_channel"), NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    @Override public void onDestroy() {
        progressHandler.removeCallbacks(progressTicker);
        progressHandler.removeCallbacks(sleepRunnable);
        releasePlayer();
        artwork = null;
        if (audioManager != null && focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
        if (session != null) { session.release(); session = null; }
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
