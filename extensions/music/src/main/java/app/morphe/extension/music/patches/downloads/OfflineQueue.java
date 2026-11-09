/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Play order of the offline player: the track being played, the ones played before it and
 * the ones that come next ("À suivre"). The service plays it, the screens edit it.
 * Everything is touched on the main thread or from the service, which runs on the same thread.
 */
final class OfflineQueue {

    enum Repeat {
        OFF, ALL, ONE;

        Repeat next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private static final List<String> items = new ArrayList<>();
    /** The order the queue was given in, so that turning shuffle off can restore it. */
    private static List<String> sourceOrder = new ArrayList<>();
    private static int index = -1;
    private static boolean shuffle;
    private static Repeat repeat = Repeat.OFF;
    /** Where the music comes from, shown by the player ("Mes favoris", the name of an artist...). */
    private static String source = "";
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private OfflineQueue() {
    }

    static void addListener(Runnable listener) {
        listeners.add(listener);
    }

    static void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private static void changed() {
        for (Runnable listener : listeners) listener.run();
    }

    /**
     * @param paths      Audio file paths in the order they are listed.
     * @param startIndex Track to start with.
     * @param shuffled   Whether to start in shuffled order.
     */
    static void set(List<String> paths, int startIndex, boolean shuffled) {
        set(paths, startIndex, shuffled, "");
    }

    /** Same as above, and remembers the name of the list the tracks come from. */
    static void set(List<String> paths, int startIndex, boolean shuffled, String sourceName) {
        source = sourceName == null ? "" : sourceName;
        items.clear();
        items.addAll(paths);
        sourceOrder = new ArrayList<>(paths);
        index = paths.isEmpty() ? -1 : Math.max(0, Math.min(startIndex, paths.size() - 1));
        shuffle = shuffled;
        if (shuffled) shuffleUpcoming(true);
        changed();
    }

    /** Keeps the track at the front, and mixes everything else. */
    private static void shuffleUpcoming(boolean includePast) {
        if (items.isEmpty() || index < 0) return;
        String current = items.get(index);
        List<String> rest = new ArrayList<>(items);
        if (includePast) {
            rest.remove(index);
            Collections.shuffle(rest);
            items.clear();
            items.add(current);
            items.addAll(rest);
            index = 0;
        } else {
            List<String> upcoming = new ArrayList<>(items.subList(index + 1, items.size()));
            Collections.shuffle(upcoming);
            for (int i = 0; i < upcoming.size(); i++) items.set(index + 1 + i, upcoming.get(i));
        }
    }

    static boolean isShuffled() {
        return shuffle;
    }

    static void setShuffle(boolean enabled) {
        if (shuffle == enabled) return;
        shuffle = enabled;
        if (enabled) {
            shuffleUpcoming(false);
        } else if (index >= 0 && index < items.size()) {
            // Everything after the current track goes back to its original order.
            String current = items.get(index);
            int sourceIndex = sourceOrder.indexOf(current);
            List<String> restored = new ArrayList<>();
            for (int i = sourceIndex + 1; i < sourceOrder.size(); i++) {
                String path = sourceOrder.get(i);
                if (items.indexOf(path) > index) restored.add(path);
            }
            for (int i = items.size() - 1; i > index; i--) {
                if (!restored.contains(items.get(i))) restored.add(items.get(i));
                items.remove(i);
            }
            items.addAll(restored);
        }
        changed();
    }

    static Repeat getRepeat() {
        return repeat;
    }

    static void cycleRepeat() {
        repeat = repeat.next();
        changed();
    }

    static List<String> snapshot() {
        return new ArrayList<>(items);
    }

    static int getIndex() {
        return index;
    }

    static int size() {
        return items.size();
    }

    @Nullable
    static String current() {
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    static boolean hasPrevious() {
        return index > 0 || (repeat == Repeat.ALL && items.size() > 1);
    }

    static boolean hasNext() {
        return index + 1 < items.size() || (repeat == Repeat.ALL && items.size() > 1);
    }

    /** Puts a track right after the current one, which is what "Lire ensuite" does. */
    static void playNext(String path) {
        if (items.isEmpty()) {
            set(Collections.singletonList(path), 0, false);
            return;
        }
        int existing = items.indexOf(path);
        if (existing >= 0 && existing != index) {
            items.remove(existing);
            if (existing < index) index--;
        }
        if (existing == index && existing >= 0) return;
        items.add(index + 1, path);
        if (!sourceOrder.contains(path)) sourceOrder.add(path);
        changed();
    }

    static void addToEnd(String path) {
        if (items.isEmpty()) {
            set(Collections.singletonList(path), 0, false);
            return;
        }
        if (items.contains(path)) return;
        items.add(path);
        if (!sourceOrder.contains(path)) sourceOrder.add(path);
        changed();
    }

    /**
     * Adds many tracks at the end of the queue, in random order. A track that is already in the
     * queue (coming up, playing or already played) is left alone, so nothing plays twice.
     *
     * @return How many tracks were added.
     */
    static int addAllShuffled(List<String> paths) {
        if (items.isEmpty() || paths.isEmpty()) return 0;

        Set<String> present = new HashSet<>(items);
        List<String> fresh = new ArrayList<>();
        for (String path : paths) {
            if (present.add(path)) fresh.add(path);
        }
        if (fresh.isEmpty()) return 0;

        Collections.shuffle(fresh);
        items.addAll(fresh);
        Set<String> known = new HashSet<>(sourceOrder);
        for (String path : fresh) {
            if (known.add(path)) sourceOrder.add(path);
        }
        changed();
        return fresh.size();
    }

    /** @return Whether the removed track was the one playing. */
    static boolean remove(int position) {
        if (position < 0 || position >= items.size()) return false;
        items.remove(position);
        boolean wasCurrent = position == index;
        if (position < index) index--;
        if (index >= items.size()) index = items.size() - 1;
        changed();
        return wasCurrent;
    }

    static String getSource() {
        return source;
    }

    /**
     * Moves a track to another place of the queue. The track that plays keeps playing, so
     * the index follows it.
     */
    static void move(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= items.size() || to >= items.size()) return;
        String moved = items.remove(from);
        items.add(to, moved);
        if (index == from) {
            index = to;
        } else if (from < index && to >= index) {
            index--;
        } else if (from > index && to <= index) {
            index++;
        }
        changed();
    }

    /** Puts the track of this position right after the one that plays. */
    static void moveToNext(int position) {
        if (position < 0 || position >= items.size() || position == index) return;
        move(position, position < index ? index : index + 1);
    }

    /** Removes everything that comes after the track that plays. */
    static void clearUpcoming() {
        if (index < 0) return;
        while (items.size() > index + 1) items.remove(items.size() - 1);
        changed();
    }

    static void jumpTo(int position) {
        if (position < 0 || position >= items.size()) return;
        index = position;
        changed();
    }

    /** Called when a track ends by itself. @return Whether there is a next track to play. */
    static boolean advanceAutomatically() {
        if (items.isEmpty()) return false;
        if (repeat == Repeat.ONE) return true;
        return step(1);
    }

    /** Skip button. @return Whether there is a track to play. */
    static boolean skip(int offset) {
        if (items.isEmpty()) return false;
        return step(offset);
    }

    private static boolean step(int offset) {
        int target = index + offset;
        if (target < 0 || target >= items.size()) {
            if (repeat != Repeat.ALL || items.size() < 2) return false;
            target = (target + items.size()) % items.size();
        }
        index = target;
        changed();
        return true;
    }

    /** Drops a track that no longer exists on the device. */
    static void forget(String path) {
        int position = items.indexOf(path);
        if (position >= 0) remove(position);
        sourceOrder.remove(path);
    }

    static void clear() {
        items.clear();
        sourceOrder.clear();
        index = -1;
        source = "";
        changed();
    }
}
