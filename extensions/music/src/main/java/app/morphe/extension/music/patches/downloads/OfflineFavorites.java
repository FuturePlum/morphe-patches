/*
 * Custom addition: favorite downloads, kept in one small file next to the audio files.
 */

package app.morphe.extension.music.patches.downloads;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

import app.morphe.extension.shared.Logger;

final class OfflineFavorites {

    private static final String FILE_NAME = "favorites.txt";

    private OfflineFavorites() {
    }

    static synchronized Set<String> load(File directory) {
        Set<String> ids = new LinkedHashSet<>();
        File file = new File(directory, FILE_NAME);
        if (!file.isFile()) return ids;

        try {
            for (String line : OfflineStorage.readText(file).split("\n")) {
                if (!line.isBlank()) ids.add(line.trim());
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read favorites", ex);
        }
        return ids;
    }

    /** @return Whether the track is a favorite after the change. */
    static synchronized boolean toggle(File directory, String videoId) {
        Set<String> ids = load(directory);
        final boolean added = ids.add(videoId);
        if (!added) ids.remove(videoId);

        try {
            OfflineStorage.writeText(new File(directory, FILE_NAME), String.join("\n", ids));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not save favorites", ex);
        }
        return added;
    }
}
