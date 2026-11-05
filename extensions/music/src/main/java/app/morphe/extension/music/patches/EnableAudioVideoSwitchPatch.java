/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3471
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

import app.morphe.extension.music.patches.jam.QueueCommand;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

@SuppressWarnings("unused")
public final class EnableAudioVideoSwitchPatch {

    public interface QueueAccess {
        void patch_atvEnqueue(byte[] command);
        Executor patch_atvExecutor();
        int patch_atvCurrentIndex();
        Object[] patch_atvItems();
        String patch_atvVideoId(Object item);
        void patch_atvRemove(Object item);
        boolean patch_atvLocal();
    }

    private static final class Item {
        final String videoId;
        String type;
        String title;

        Item(String videoId, String type, String title) {
            this.videoId = videoId;
            this.type = type;
            this.title = title;
        }
    }

    private interface Condition {
        boolean ok();
    }

    private static final String OEMBED_URL = "https://www.youtube.com/oembed?format=json&url="
            + "https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3D";
    private static final String SEARCH_URL =
            "https://music.youtube.com/youtubei/v1/search?key=AIzaSyAOghZGza2MQSZkY_zfZ370N-PUdXEo8AI";
    private static final String USER_AGENT =
            "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 14)";

    private static final String AUDIO_TYPE = "MUSIC_VIDEO_TYPE_ATV";
    private static final String VIDEO_TYPE = "MUSIC_VIDEO_TYPE_OMV";

    private static volatile boolean busy;

    private static volatile WeakReference<QueueAccess> queue = new WeakReference<>(null);

    /** A song only counts as an audio version when its title matches at least this well. */
    private static final double AUTO_MIN_SCORE = 0.8d;
    /** Clips are named more freely than songs, so a looser match is accepted when asked for. */
    private static final double VIDEO_MIN_SCORE = 0.5d;
    private static final int MAX_REMEMBERED = 100;

    private static final int MODE_ANY = 0;
    private static final int MODE_TO_AUDIO = 1;
    private static final int MODE_TO_VIDEO = 2;

    /** How long the app's own Video button gets to work before the search takes over. */
    private static final long NATIVE_SWITCH_WAIT_MS = 1_500;
    /** A track change this soon after a tap on Video is the user's choice, not a new song. */
    private static final long USER_TAP_WINDOW_MS = 4_000;

    private static volatile long lastVideoSideDownTime = -1;
    private static volatile long lastVideoSideTapUptime = -10_000;
    /** Type of the track looked up by the last search, to tell "no clip" from "already a clip". */
    @Nullable
    private static volatile String lastResolvedCurrentType;

    private static final AtomicBoolean autoListenerRegistered = new AtomicBoolean();

    /**
     * Tracks that automatic switching must leave alone: the ones the user picked with the
     * switch, and the audio versions that were just chosen for them.
     */
    private static final Set<String> skipAuto = Collections.synchronizedSet(new LinkedHashSet<>());

    /** Counterpart found for a track, or an empty string when it has no audio version. */
    private static final Map<String, String> audioCounterparts = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_REMEMBERED * 2;
                }
            });

    private EnableAudioVideoSwitchPatch() {
    }

    public static void capture(QueueAccess access) {
        queue = new WeakReference<>(access);
        registerAutoListener();
    }

    private static void registerAutoListener() {
        if (!autoListenerRegistered.compareAndSet(false, true)) return;
        VideoInformation.addVideoIdListener(EnableAudioVideoSwitchPatch::onNewVideoId);
    }

    private static void rememberSkip(String videoId) {
        synchronized (skipAuto) {
            skipAuto.add(videoId);
            while (skipAuto.size() > MAX_REMEMBERED) {
                Iterator<String> oldest = skipAuto.iterator();
                oldest.next();
                oldest.remove();
            }
        }
    }

    /**
     * Called whenever the app starts another video. When the new track is a music video and has
     * an audio version, that version is queued and played in its place, so the real cover is
     * shown and the video is not played.
     */
    private static void onNewVideoId(String videoId) {
        try {
            if (!Settings.ENABLE_AUDIO_VIDEO_SWITCH.get() || !Settings.AUTO_AUDIO_VERSION.get()) return;
            if (!isVideoId(videoId)) return;
            if (SystemClock.uptimeMillis() - lastVideoSideTapUptime < USER_TAP_WINDOW_MS) {
                // The user just asked for the video, so it is left as it is.
                rememberSkip(videoId);
                return;
            }
            if (skipAuto.contains(videoId)) return;

            Utils.runOnBackgroundThread(() -> autoSwitchToAudio(videoId));
        } catch (Exception ex) {
            Logger.printDebug(() -> "onNewVideoId failure", ex);
        }
    }

    /**
     * A tap on the Video side. When the song has no video of its own the app's button is greyed
     * out and does nothing, so after a short wait the clip is searched for, and played in place
     * of the audio, when the track is still the same.
     */
    private static void onVideoSideTouched(MotionEvent event) {
        try {
            final long downTime = event.getDownTime();
            // Several views of the pill can report the same touch.
            if (downTime == lastVideoSideDownTime) return;
            lastVideoSideDownTime = downTime;
            lastVideoSideTapUptime = SystemClock.uptimeMillis();

            if (!Settings.VIDEO_FALLBACK.get()) return;
            final String idAtTap = VideoInformation.getVideoId();
            if (!isVideoId(idAtTap)) return;

            Utils.runOnMainThreadDelayed(
                    () -> Utils.runOnBackgroundThread(() -> videoFallback(idAtTap)),
                    NATIVE_SWITCH_WAIT_MS);
        } catch (Exception ex) {
            Logger.printDebug(() -> "onVideoSideTouched failure", ex);
        }
    }

    private static void videoFallback(String idAtTap) {
        if (busy) return;
        busy = true;
        try {
            if (!Settings.ENABLE_AUDIO_VIDEO_SWITCH.get() || !Settings.VIDEO_FALLBACK.get()) return;
            // The app's own button changed the track, so there is nothing left to do.
            if (!idAtTap.equals(VideoInformation.getVideoId())) return;

            QueueAccess access = queue.get();
            if (access == null) return;
            String currentId = currentQueueVideoId(access);
            if (!idAtTap.equals(currentId)) return;

            String targetId = resolveCounterpart(currentId, MODE_TO_VIDEO);
            if (targetId == null || !isVideoId(targetId) || targetId.equals(currentId)) {
                // Nothing is said when the song already is the clip.
                if (AUDIO_TYPE.equals(lastResolvedCurrentType)) {
                    Utils.showToastShort(str("morphe_music_video_not_found"));
                }
                return;
            }
            // The user may have skipped to another track while the search ran.
            if (!currentId.equals(VideoInformation.getVideoId())) return;

            Context context = Utils.getContext();
            if (context == null) return;

            rememberSkip(targetId);
            rememberSkip(currentId);
            if (!switchInQueue(context, access, currentId, targetId)) {
                if (queueHasOtherItems(access)) {
                    Utils.showToastShort(str("morphe_music_audio_video_switch_failed"));
                } else {
                    openWatch(context, targetId);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "videoFallback failure", ex);
        } finally {
            busy = false;
        }
    }

    private static void autoSwitchToAudio(String videoId) {
        if (busy) return;
        busy = true;
        try {
            QueueAccess access = queue.get();
            if (access == null) return;

            // The app announces the video before its queue moves to it.
            if (!await(() -> videoId.equals(videoAt(access, access.patch_atvCurrentIndex())), 3_000)) {
                Logger.printDebug(() -> "auto audio: queue is not on " + videoId);
                return;
            }

            String targetId = audioCounterparts.get(videoId);
            if (targetId == null) {
                targetId = resolveCounterpart(videoId, MODE_TO_AUDIO);
                audioCounterparts.put(videoId, targetId == null ? "" : targetId);
            }
            if (targetId == null || targetId.isEmpty() || !isVideoId(targetId)
                    || targetId.equals(videoId)) {
                return;
            }

            // The user may have skipped to another track while the lookup ran.
            if (!videoId.equals(videoAt(access, access.patch_atvCurrentIndex()))
                    || skipAuto.contains(videoId)) {
                return;
            }

            rememberSkip(targetId);
            Context context = Utils.getContext();
            if (context == null) return;

            // Never falls back to opening a watch page, which would replace the whole queue.
            if (!switchInQueue(context, access, videoId, targetId)) {
                Logger.printDebug(() -> "auto audio: kept the video for " + videoId);
            }
        } catch (Exception ex) {
            // Offline or blocked lookups are expected, so they are not shown to the user.
            Logger.printDebug(() -> "autoSwitchToAudio failure", ex);
        } finally {
            busy = false;
        }
    }

    public static void installAudioVideoSwitchInterceptor(View pill) {
        try {
            if (pill == null || !Settings.ENABLE_AUDIO_VIDEO_SWITCH.get()) return;
            Context context = pill.getContext();
            pill.post(() -> attachInterceptor(pill, pill, context));
        } catch (Exception ex) {
            Logger.printException(() -> "installAudioVideoSwitchInterceptor failed", ex);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private static void attachInterceptor(View pill, View view, Context context) {
        try {
            if (view == null) return;
            final boolean[] claimed = {false};
            view.setOnTouchListener((target, event) -> {
                if (!Settings.ENABLE_AUDIO_VIDEO_SWITCH.get()) {
                    claimed[0] = false;
                    return false;
                }
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        claimed[0] = isLeftHalf(pill, event);
                        if (!claimed[0]) onVideoSideTouched(event);
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        claimed[0] = false;
                        break;
                    default:
                        break;
                }
                if (!claimed[0]) return false;
                if (event.getActionMasked() == MotionEvent.ACTION_UP) openCounterpart(context);
                return true;
            });
            if (view instanceof ViewGroup group) {
                for (int i = 0, childCount = group.getChildCount(); i < childCount; i++) {
                    attachInterceptor(pill, group.getChildAt(i), context);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "attachInterceptor failed", ex);
        }
    }

    private static boolean isLeftHalf(View pill, MotionEvent event) {
        int[] location = new int[2];
        pill.getLocationOnScreen(location);
        return event.getRawX() - location[0] < pill.getWidth() / 2f;
    }

    private static void openCounterpart(Context context) {
        if (busy) return;
        busy = true;

        Utils.runOnBackgroundThread(() -> {
            try {
                QueueAccess access = queue.get();
                String currentId = currentQueueVideoId(access);
                if (!isVideoId(currentId)) {
                    Logger.printDebug(() -> "audio/video switch: no current video");
                    return;
                }
                String targetId = resolveCounterpart(currentId);
                if (targetId == null || !isVideoId(targetId) || targetId.equals(currentId)) {
                    Logger.printDebug(
                            () -> "audio/video switch: no counterpart for " + currentId);
                    return;
                }
                // What the user picks is not changed back by the automatic audio version.
                rememberSkip(targetId);
                rememberSkip(currentId);
                if (!switchInQueue(context, access, currentId, targetId)) {
                    // Opening the counterpart as a new watch page replaces the whole queue, so
                    // it is only a fallback when there is no queue left to lose.
                    if (queueHasOtherItems(access)) {
                        Logger.printDebug(() -> "audio/video switch: failed, keeping the queue");
                        Utils.showToastShort(str("morphe_music_audio_video_switch_failed"));
                    } else {
                        openWatch(context, targetId);
                    }
                }
            } catch (Exception ex) {
                Logger.printException(() -> "openCounterpart failure", ex);
            } finally {
                busy = false;
            }
        });
    }

    /** @return Whether the queue holds more than the playing track, so a rebuild would lose it. */
    private static boolean queueHasOtherItems(@Nullable QueueAccess access) {
        if (access == null) return true; // The queue cannot be inspected, so it is protected.
        try {
            Object[] items = access.patch_atvItems();
            return items != null && items.length > 1;
        } catch (Exception ex) {
            Logger.printException(() -> "queueHasOtherItems failure", ex);
            return true;
        }
    }

    /** @return Position of the video in the queue, or -1. */
    private static int indexOfVideo(QueueAccess access, String videoId) {
        Object[] items = access.patch_atvItems();
        if (items == null) return -1;
        for (int i = 0; i < items.length; i++) {
            if (videoId.equals(access.patch_atvVideoId(items[i]))) return i;
        }
        return -1;
    }

    /**
     * The queue's own current item is authoritative for switching; the watch-page videoId may lag
     * behind after an in-queue track change. Falls back to the watch page when no queue exists.
     */
    private static String currentQueueVideoId(QueueAccess access) {
        if (access != null) {
            try {
                String id = videoAt(access, access.patch_atvCurrentIndex());
                if (id != null) return id;
            } catch (Exception ex) {
                Logger.printException(() -> "currentQueueVideoId failure", ex);
            }
        }
        return VideoInformation.getVideoId();
    }

    /**
     * Returns false before playback advanced, so the caller can fall back to a watch deep link.
     */
    private static boolean switchInQueue(
            Context context,
            @Nullable QueueAccess access,
            String currentId,
            String targetId
    ) {
        if (access == null) return false;
        try {
            if (!access.patch_atvLocal()) {
                Logger.printDebug(() -> "audio/video switch: queue is not local");
                return false;
            }
            Executor executor = access.patch_atvExecutor();
            if (executor == null) return false;

            byte[] command = QueueCommand.encode(targetId, true);
            int[] queuedIndex = new int[] {-1};
            runOnQueueExecutor(executor, () -> {
                Object[] items = access.patch_atvItems();
                int index = access.patch_atvCurrentIndex();
                if (items == null || index < 0 || index >= items.length) return;
                if (!currentId.equals(access.patch_atvVideoId(items[index]))) {
                    Logger.printDebug(
                            () -> "audio/video switch: current queue item is not " + currentId);
                    return;
                }
                access.patch_atvEnqueue(command);
                queuedIndex[0] = index;
            });
            final int index = queuedIndex[0];
            if (index < 0) return false;

            if (!await(() -> indexOfVideo(access, targetId) >= 0, 2_000)) {
                Logger.printDebug(() -> "audio/video switch: counterpart was not queued");
                return false;
            }

            // With shuffle on, the next track is not the one that was just added, so the
            // switch is judged by what plays rather than by where it was put.
            dispatchMediaKeyEvent(context);
            if (!await(() -> targetId.equals(videoAt(access, access.patch_atvCurrentIndex())), 3_000)) {
                Logger.printDebug(
                        () -> "audio/video switch: playback did not advance to " + targetId);
                // Leaves the queue as it was, without the extra copy of the counterpart.
                removeVideo(access, targetId, currentId);
                return false;
            }

            removePrevious(access, currentId);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "switchInQueue failure", ex);
            return false;
        }
    }

    /** Removes a copy of the video that was added by a switch that did not finish. */
    private static void removeVideo(QueueAccess access, String videoId, String playingId) {
        try {
            Executor executor = access.patch_atvExecutor();
            if (executor == null) return;
            runOnQueueExecutor(executor, () -> {
                Object[] items = access.patch_atvItems();
                if (items == null) return;
                int playing = access.patch_atvCurrentIndex();
                for (int i = 0; i < items.length; i++) {
                    if (i != playing && videoId.equals(access.patch_atvVideoId(items[i]))) {
                        access.patch_atvRemove(items[i]);
                        return;
                    }
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "removeVideo failure", ex);
        }
    }

    private static void removePrevious(QueueAccess access, String currentId) {
        try {
            Executor executor = access.patch_atvExecutor();
            if (executor == null) return;
            runOnQueueExecutor(executor, () -> {
                Object[] items = access.patch_atvItems();
                int index = access.patch_atvCurrentIndex();
                Object previous = null;
                if (items != null) {
                    if (index - 1 >= 0 && index - 1 < items.length
                            && currentId.equals(access.patch_atvVideoId(items[index - 1]))) {
                        previous = items[index - 1];
                    } else {
                        for (Object item : items) {
                            if (currentId.equals(access.patch_atvVideoId(item))) {
                                previous = item;
                                break;
                            }
                        }
                    }
                }
                if (previous != null) {
                    access.patch_atvRemove(previous);
                } else {
                    Logger.printDebug(
                            () -> "audio/video switch: previous queue item not found");
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "removePrevious failure", ex);
        }
    }

    private static String videoAt(QueueAccess access, int index) {
        Object[] items = access.patch_atvItems();
        if (items == null || index < 0 || index >= items.length) return null;
        return access.patch_atvVideoId(items[index]);
    }

    /** Native queue mutations only ever run on YouTube Music's own queue executor. */
    private static void runOnQueueExecutor(Executor executor, Runnable task)
            throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        executor.execute(() -> {
            try {
                task.run();
            } catch (Exception ex) {
                Logger.printException(() -> "audio/video switch: queue task failed", ex);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) {
            Logger.printDebug(() -> "audio/video switch: queue task timed out");
        }
    }

    @SuppressWarnings({"BooleanMethodIsAlwaysInverted", "BusyWait"})
    private static boolean await(Condition condition, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (true) {
            try {
                if (condition.ok()) return true;
            } catch (Exception ex) {
                Logger.printException(() -> "audio/video switch: queue poll failed", ex);
                return false;
            }
            if (SystemClock.uptimeMillis() >= deadline) return false;
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * Dispatches a media key event via AudioManager. This is the same mechanism used by Bluetooth
     * headsets and does not require any special permissions. Both ACTION_DOWN and ACTION_UP are
     * sent, as some players ignore events without a matching up event.
     */
    private static void dispatchMediaKeyEvent(Context context) {
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    private static void dispatchMediaKey(Context context, int keyCode) {
        if (context.getSystemService(Context.AUDIO_SERVICE) instanceof AudioManager audioManager) {
            try {
                long now = SystemClock.uptimeMillis();
                audioManager.dispatchMediaKeyEvent(
                        new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
                audioManager.dispatchMediaKeyEvent(
                        new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
            } catch (Exception ex) {
                Logger.printException(() -> "dispatchMediaKeyEvent failure", ex);
            }
        }
    }

    private static void openWatch(Context context, String videoId) {
        Utils.runOnMainThread(() -> {
            try {
                Intent intent = new Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://music.youtube.com/watch?v=" + videoId)
                );
                intent.setPackage(context.getPackageName());
                if (!(context instanceof Activity)) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                context.startActivity(intent);
                Logger.printDebug(() -> "audio/video switch: opened " + videoId);
            } catch (Exception ex) {
                Logger.printException(() -> "openWatch failure", ex);
            }
        });
    }

    @Nullable
    private static String resolveCounterpart(String currentId) throws Exception {
        return resolveCounterpart(currentId, MODE_ANY);
    }

    /**
     * @param mode {@link #MODE_TO_AUDIO} only answers for a music video, and only with a close
     *             match, because nobody is there to check the result when it is done
     *             automatically. {@link #MODE_TO_VIDEO} only answers for an audio track.
     */
    @Nullable
    private static String resolveCounterpart(String currentId, int mode) throws Exception {
        lastResolvedCurrentType = null;
        final boolean onlyToAudio = mode == MODE_TO_AUDIO;
        final boolean onlyToVideo = mode == MODE_TO_VIDEO;
        JSONObject metadata = requestJson(OEMBED_URL + currentId, null);

        String title = metadata.optString("title", "");
        String author = metadata.optString("author_name", "");
        if (title.trim().isEmpty()) return null;
        // Topic channels only hold audio tracks, so there is nothing to look for.
        if (onlyToAudio && author.trim().endsWith("- Topic")) return null;

        JSONObject response = requestJson(SEARCH_URL, buildQuery(title, author));

        Map<String, Item> items = new LinkedHashMap<>();
        collectItems(response, "", items);

        String currentType = null;
        for (Item item : items.values()) {
            if (!currentId.equals(item.videoId) || item.type.isEmpty()) continue;
            currentType = item.type;
            break;
        }
        if (!AUDIO_TYPE.equals(currentType) && !VIDEO_TYPE.equals(currentType)) {
            if (author.trim().endsWith("- Topic")) currentType = AUDIO_TYPE;
        }
        if (!AUDIO_TYPE.equals(currentType) && !VIDEO_TYPE.equals(currentType)) return null;
        lastResolvedCurrentType = currentType;
        if (onlyToAudio && !VIDEO_TYPE.equals(currentType)) return null;
        if (onlyToVideo && !AUDIO_TYPE.equals(currentType)) return null;

        String wantedType = AUDIO_TYPE.equals(currentType) ? VIDEO_TYPE : AUDIO_TYPE;
        String resolvedType = currentType;
        Item best = null;
        double bestScore = -1.0d;
        for (Item item : items.values()) {
            if (!wantedType.equals(item.type) || currentId.equals(item.videoId)) continue;
            double itemScore = score(title, item.title);
            if (itemScore > bestScore) {
                bestScore = itemScore;
                best = item;
            }
        }
        if (onlyToAudio && bestScore < AUTO_MIN_SCORE) best = null;
        if (onlyToVideo && bestScore < VIDEO_MIN_SCORE) best = null;
        String resolved = best == null ? null : best.videoId;
        final double resolvedScore = bestScore;
        Logger.printDebug(() -> "audio/video switch: " + currentId + " (" + resolvedType
                + ") -> " + resolved + " score: " + resolvedScore);
        return resolved;
    }

    private static String buildQuery(String title, String author) {
        StringBuilder query = new StringBuilder(stripDecorations(title).trim());
        String channel = stripDecorations(author)
                .replaceFirst("(?i)\\s*-\\s*Topic\\s*$", "")
                .replaceFirst("(?i)\\s*VEVO\\s*$", "")
                .trim();
        if (!channel.isEmpty() && !channel.equalsIgnoreCase(query.toString())) {
            //noinspection SizeReplaceableByIsEmpty
            if (query.length() > 0) query.append(' ');
            query.append(channel);
        }
        return query.toString().replaceAll("\\s+", " ").trim();
    }

    private static String stripDecorations(String value) {
        if (value == null) return "";
        return value.replaceAll("\\s*[(\\[].*?[)\\]]", " ");
    }

    private static double score(String expected, String actual) {
        String left = normalize(expected);
        String right = normalize(actual);
        if (left.isEmpty() || right.isEmpty()) return 0.0d;
        return normalizeScore(left, right);
    }

    private static double normalizeScore(String left, String right) {
        if (left.contains(right) || right.contains(left)) return 1.0d;
        Set<String> a = new LinkedHashSet<>(Arrays.asList(left.split(" ")));
        Set<String> b = new LinkedHashSet<>(Arrays.asList(right.split(" ")));
        if (a.isEmpty() || b.isEmpty()) return 0.0d;
        int common = 0;
        for (String token : a) {
            if (b.contains(token)) common++;
        }
        return (double) common / Math.min(a.size(), b.size());
    }

    private static String normalize(String value) {
        String text = stripDecorations(value).toLowerCase(Locale.ROOT);
        text = text.replaceAll("[^a-z0-9 ]+", " ");
        return text.replaceAll("\\s+", " ").trim();
    }

    private static void collectItems(Object node, String nearestTitle, Map<String, Item> out) {
        if (node instanceof JSONObject object) {
            String ownTitle = directTitle(object);
            if (!ownTitle.isEmpty()) nearestTitle = ownTitle;

            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = object.opt(key);
                if ("watchEndpoint".equals(key) && value instanceof JSONObject endpoint) {
                    addEndpoint(endpoint, nearestTitle, out);
                } else {
                    collectItems(value, nearestTitle, out);
                }
            }
        } else if (node instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) {
                collectItems(array.opt(i), nearestTitle, out);
            }
        }
    }

    private static void addEndpoint(JSONObject endpoint, String nearestTitle, Map<String, Item> out) {
        String videoId = endpoint.optString("videoId", "");
        if (!isVideoId(videoId)) return;
        String type = musicVideoType(endpoint);

        Item existing = out.get(videoId);
        if (existing == null) {
            out.put(videoId, new Item(videoId, type, nearestTitle));
            return;
        }
        if (existing.type.isEmpty()) existing.type = type;
        if (existing.title.isEmpty()) existing.title = nearestTitle;
    }

    private static String musicVideoType(JSONObject endpoint) {
        JSONObject configs = endpoint.optJSONObject("watchEndpointMusicSupportedConfigs");
        if (configs == null) return "";
        JSONObject musicConfig = configs.optJSONObject("watchEndpointMusicConfig");
        return musicConfig == null ? "" : musicConfig.optString("musicVideoType", "");
    }

    private static String directTitle(JSONObject object) {
        String[] keys = {"title", "flexColumns", "primaryText", "secondaryText"};
        for (String key : keys) {
            if (!object.has(key)) continue;
            String text = flatten(object.opt(key), 0);
            if (!text.isEmpty()) return text;
        }
        return "";
    }

    private static String flatten(Object value, int depth) {
        if (depth > 8) return "";
        if (value instanceof String string) return string;
        if (value instanceof JSONObject object) {
            if (object.has("simpleText")) return object.optString("simpleText", "");
            for (String key : new String[]{"runs", "flexColumns", "text"}) {
                if (!object.has(key)) continue;
                String text = flatten(object.opt(key), depth + 1);
                if (!text.isEmpty()) return text;
            }
            return "";
        }
        if (value instanceof JSONArray array) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0, length = array.length(); i < length; i++) {
                String text = flatten(array.opt(i), depth + 1);
                if (text.isEmpty()) continue;
                //noinspection SizeReplaceableByIsEmpty
                if (builder.length() > 0) builder.append(' ');
                builder.append(text);
            }
            return builder.toString();
        }
        return "";
    }

    /** What the app's own player has queued, from the track playing now on. */
    public static final class QueueSnapshot {
        public final List<String> videoIds;
        public final long positionMilliseconds;

        QueueSnapshot(List<String> videoIds, long positionMilliseconds) {
            this.videoIds = videoIds;
            this.positionMilliseconds = positionMilliseconds;
        }
    }

    /**
     * Reads the queue of the app's own player, without changing it. Call it off the main thread.
     *
     * @return The playing track and the ones after it, or null when nothing is playing or the
     *         queue is shared (a Jam).
     */
    @Nullable
    public static QueueSnapshot snapshotQueue() {
        try {
            final String current = VideoInformation.getVideoId();
            final long position = Math.max(0, VideoInformation.getVideoTime());
            final List<String> ids = new ArrayList<>();

            QueueAccess access = queue.get();
            if (access != null) {
                if (!access.patch_atvLocal()) return null;
                Object[] items = access.patch_atvItems();
                final int index = access.patch_atvCurrentIndex();
                if (items != null && index >= 0 && index < items.length) {
                    for (int i = index; i < items.length; i++) {
                        String id = access.patch_atvVideoId(items[i]);
                        if (isVideoId(id) && !ids.contains(id)) ids.add(id);
                    }
                }
            }
            // The queue could not be read: the track playing is still carried over.
            if (ids.isEmpty() && current != null && isVideoId(current)) ids.add(current);
            return ids.isEmpty() ? null : new QueueSnapshot(ids, position);
        } catch (Exception ex) {
            Logger.printException(() -> "snapshotQueue failure", ex);
            return null;
        }
    }

    /**
     * Puts a whole list in the app's own player, replacing what it had queued, and goes back to
     * the spot reached. Runs in the background and returns at once.
     *
     * @param onDone Called on the main thread; true when the first track started.
     */
    public static void startQueue(Context context, List<String> videoIds, long positionMilliseconds,
                                  java.util.function.Consumer<Boolean> onDone) {
        final List<String> ids = new ArrayList<>();
        for (String id : videoIds) {
            if (isVideoId(id) && !ids.contains(id)) ids.add(id);
        }
        if (ids.isEmpty()) {
            Utils.runOnMainThread(() -> onDone.accept(false));
            return;
        }

        Utils.runOnBackgroundThread(() -> {
            boolean started = false;
            try {
                final String first = ids.get(0);
                // The track was chosen by the user: no audio version may replace it.
                rememberSkip(first);
                openWatch(context, first);

                started = await(() -> {
                    QueueAccess fresh = queue.get();
                    return fresh != null && first.equals(videoAt(fresh, fresh.patch_atvCurrentIndex()));
                }, 10_000);

                QueueAccess fresh = queue.get();
                if (started && fresh != null && ids.size() > 1) {
                    appendToQueue(fresh, ids.subList(1, ids.size()), true);
                }

                if (started && positionMilliseconds > 2_000) {
                    await(() -> first.equals(VideoInformation.getVideoId())
                            && VideoInformation.getVideoLength() > 0, 8_000);
                    VideoInformation.seekTo(positionMilliseconds);
                    // The first seek can come before the player is ready for it.
                    Thread.sleep(800);
                    if (Math.abs(VideoInformation.getVideoTime() - positionMilliseconds) > 3_000) {
                        VideoInformation.seekTo(positionMilliseconds);
                    }
                }
            } catch (Exception ex) {
                Logger.printException(() -> "startQueue failure", ex);
            }
            final boolean result = started;
            Utils.runOnMainThread(() -> onDone.accept(result));
        });
    }

    /** The app's player, paused behind the offline player, plays again. */
    public static void resumeNative(Context context) {
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY);
    }

    /**
     * Puts titles right after the track the app's player has paused on and moves on to the first
     * one. Without a usable queue of its own, the app's player starts them as a new list.
     *
     * @param onDone Called on the main thread; true when the first title started.
     */
    public static void playNextInNative(Context context, List<String> videoIds,
                                        java.util.function.Consumer<Boolean> onDone) {
        final List<String> ids = new ArrayList<>();
        for (String id : videoIds) {
            if (isVideoId(id) && !ids.contains(id)) ids.add(id);
        }
        if (ids.isEmpty()) {
            Utils.runOnMainThread(() -> onDone.accept(false));
            return;
        }

        Utils.runOnBackgroundThread(() -> {
            boolean started = false;
            try {
                final String first = ids.get(0);
                for (String id : ids) rememberSkip(id);

                QueueAccess access = queue.get();
                if (access != null && access.patch_atvLocal()) {
                    Object[] items = access.patch_atvItems();
                    if (items != null && items.length > 0 && access.patch_atvCurrentIndex() >= 0) {
                        appendToQueue(access, ids, true);
                        if (await(() -> indexOfVideo(access, first) >= 0, 2_000)) {
                            dispatchMediaKeyEvent(context);
                            started = await(() -> first.equals(
                                    videoAt(access, access.patch_atvCurrentIndex())), 3_000);
                        }
                    }
                }

                if (!started) {
                    // No queue to add to: the titles start as a list of their own.
                    openWatch(context, first);
                    started = await(() -> {
                        QueueAccess fresh = queue.get();
                        return fresh != null
                                && first.equals(videoAt(fresh, fresh.patch_atvCurrentIndex()));
                    }, 10_000);
                    QueueAccess fresh = queue.get();
                    if (started && fresh != null && ids.size() > 1) {
                        appendToQueue(fresh, ids.subList(1, ids.size()), true);
                    }
                }
            } catch (Exception ex) {
                Logger.printException(() -> "playNextInNative failure", ex);
            }
            final boolean result = started;
            Utils.runOnMainThread(() -> onDone.accept(result));
        });
    }

    /** The favorites page opened a watch page, so it can close to show the player. */
    public static final int FAVORITES_STARTED = 0;
    /** The songs were added to the queue that was playing. */
    public static final int FAVORITES_QUEUED = 1;
    /** Nothing was done, because the queue could not be reached safely. */
    public static final int FAVORITES_FAILED = 2;

    /**
     * Plays or queues songs picked on the favorites page.
     * <p>
     * A watch page replaces the whole queue, so it is only opened when nothing is queued, or when
     * a single track is playing and the person asked to play. Otherwise the songs are added to
     * the queue the app already has.
     *
     * @param play     True to play the first song if that does not lose a queue, false to only queue.
     * @param playNext True to put the songs right after the playing one, false at the end.
     * @param onDone   Called on the main thread with one of the FAVORITES_ results.
     */
    public static void playFromFavorites(Context context, List<String> videoIds, boolean play,
                                         boolean playNext, IntConsumer onDone) {
        final List<String> ids = new ArrayList<>();
        for (String id : videoIds) {
            if (isVideoId(id) && !ids.contains(id)) ids.add(id);
        }
        if (ids.isEmpty()) {
            onDone.accept(FAVORITES_FAILED);
            return;
        }

        Utils.runOnBackgroundThread(() -> {
            int result = FAVORITES_FAILED;
            try {
                result = favoritesPlayback(context, ids, play, playNext);
            } catch (Exception ex) {
                Logger.printException(() -> "playFromFavorites failure", ex);
            }
            final int finalResult = result;
            Utils.runOnMainThread(() -> onDone.accept(finalResult));
        });
    }

    private static int favoritesPlayback(Context context, List<String> ids, boolean play,
                                         boolean playNext) throws Exception {
        QueueAccess access = queue.get();
        int count = 0;
        if (access != null) {
            if (!access.patch_atvLocal()) return FAVORITES_FAILED; // A shared (Jam) queue.
            Object[] items = access.patch_atvItems();
            count = items == null ? 0 : items.length;
        }

        final String current = VideoInformation.getVideoId();
        final boolean nothingPlays = access == null
                ? current == null || current.isEmpty() : count == 0;
        // Only one track is queued: replacing it loses nothing but the song that was playing.
        final boolean playingAlone = access != null && count == 1;
        // The queue cannot be looked at while something plays, so it is left alone.
        if (access == null && !nothingPlays) return FAVORITES_FAILED;

        if (nothingPlays || (play && playingAlone)) {
            final String first = ids.get(0);
            openWatch(context, first);
            if (ids.size() == 1) return FAVORITES_STARTED;

            final boolean started = await(() -> {
                QueueAccess fresh = queue.get();
                return fresh != null && first.equals(videoAt(fresh, fresh.patch_atvCurrentIndex()));
            }, 10_000);
            QueueAccess fresh = queue.get();
            if (started && fresh != null) {
                appendToQueue(fresh, ids.subList(1, ids.size()), false);
            } else {
                Logger.printDebug(() -> "favorites: the new queue was not reached, the rest is skipped");
            }
            return FAVORITES_STARTED;
        }

        appendToQueue(access, ids, playNext);
        return FAVORITES_QUEUED;
    }

    private static void appendToQueue(QueueAccess access, List<String> ids, boolean playNext)
            throws InterruptedException {
        Executor executor = access.patch_atvExecutor();
        if (executor == null) return;

        // Each song put next lands right after the playing one, so the last goes in first.
        final List<String> order = new ArrayList<>(ids);
        if (playNext) Collections.reverse(order);
        runOnQueueExecutor(executor, () -> {
            for (String id : order) {
                access.patch_atvEnqueue(QueueCommand.encode(id, playNext));
            }
        });
    }

    private static JSONObject requestJson(String url, String searchQuery) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);

        if (searchQuery == null) {
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        } else {
            connection.setRequestProperty("User-Agent", USER_AGENT);
            byte[] body = new JSONObject()
                    .put("query", searchQuery)
                    .put("context", new JSONObject()
                            .put("client", new JSONObject()
                                    .put("clientName", "ANDROID_MUSIC")
                                    .put("clientVersion", "7.27.52")
                                    .put("androidSdkVersion", 34)
                                    .put("hl", "en")
                                    .put("gl", "US")))
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
        }

        final int status = connection.getResponseCode();
        if (status < 400) {
            return Requester.parseJSONObject(connection);
        }
        return new JSONObject(Requester.parseErrorString(connection));
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean isVideoId(String value) {
        final int length = value.length();
        if (length != 11) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '-';
            if (!valid) return false;
        }
        return true;
    }
}
