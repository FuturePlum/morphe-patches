/*
 * Custom addition: the offline queue is written to the app's preferences, so it comes back
 * (paused, at the same spot) when Android has killed the app in the background.
 */

package app.morphe.extension.music.patches.downloads;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;

final class OfflineQueueStore {

    private static final String FILE = "morphe_offline_queue";
    private static final String SEPARATOR = "\n";

    /** What was playing when the queue was last saved. */
    static final class State {
        final ArrayList<String> queue;
        final ArrayList<String> originalOrder;
        final int index;
        final boolean shuffle;
        final int repeat;
        final int positionMilliseconds;

        State(ArrayList<String> queue, ArrayList<String> originalOrder, int index,
              boolean shuffle, int repeat, int positionMilliseconds) {
            this.queue = queue;
            this.originalOrder = originalOrder;
            this.index = index;
            this.shuffle = shuffle;
            this.repeat = repeat;
            this.positionMilliseconds = positionMilliseconds;
        }
    }

    static void save(Context context, List<String> queue, List<String> originalOrder, int index,
                     boolean shuffle, int repeat, int positionMilliseconds) {
        try {
            if (queue.isEmpty() || index < 0 || index >= queue.size()) {
                clear(context);
                return;
            }
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                    .putString("queue", String.join(SEPARATOR, queue))
                    .putString("original", String.join(SEPARATOR, originalOrder))
                    .putInt("index", index)
                    .putBoolean("shuffle", shuffle)
                    .putInt("repeat", repeat)
                    .putInt("position", Math.max(0, positionMilliseconds))
                    .apply();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not save the offline queue", ex);
        }
    }

    static void clear(Context context) {
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not clear the offline queue", ex);
        }
    }

    /**
     * @return The saved queue without the tracks that were deleted since, or null when there is
     *         nothing left to play.
     */
    @Nullable
    static State load(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            String saved = prefs.getString("queue", "");
            if (saved == null || saved.isEmpty()) return null;

            final int savedIndex = prefs.getInt("index", -1);
            String[] paths = saved.split(SEPARATOR);

            ArrayList<String> queue = new ArrayList<>();
            int index = -1;
            int firstAfter = -1;
            boolean currentLost = false;
            for (int i = 0; i < paths.length; i++) {
                if (!new File(paths[i]).isFile()) {
                    if (i == savedIndex) currentLost = true;
                    continue;
                }
                if (i == savedIndex) index = queue.size();
                else if (i > savedIndex && firstAfter < 0) firstAfter = queue.size();
                queue.add(paths[i]);
            }
            if (queue.isEmpty()) return null;
            if (index < 0) index = firstAfter >= 0 ? firstAfter : queue.size() - 1;

            ArrayList<String> original = new ArrayList<>();
            String savedOriginal = prefs.getString("original", "");
            if (savedOriginal != null && !savedOriginal.isEmpty()) {
                for (String path : savedOriginal.split(SEPARATOR)) {
                    if (new File(path).isFile()) original.add(path);
                }
            }
            if (original.isEmpty()) original = new ArrayList<>(queue);

            // When the track that was playing no longer exists, the next one starts from zero.
            final int position = currentLost ? 0 : prefs.getInt("position", 0);
            return new State(queue, original, index, prefs.getBoolean("shuffle", false),
                    prefs.getInt("repeat", 0), position);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read the offline queue", ex);
            return null;
        }
    }

    private OfflineQueueStore() {}
}
