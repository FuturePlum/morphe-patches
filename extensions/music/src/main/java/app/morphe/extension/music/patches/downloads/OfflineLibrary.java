/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import android.content.Context;
import android.os.Environment;

import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import app.morphe.extension.shared.Logger;

/**
 * Everything the offline screens need to know about the downloaded tracks: the list, the
 * favorites, the sort orders, the artists, and the import / export of the whole library.
 */
final class OfflineLibrary {

    /** Sort orders. The first three keep the names saved by older versions of the screen. */
    enum Sort {
        ARTIST, TITLE, SIZE, RECENT, OLDEST, ALBUM, DURATION
    }

    /** One artist and the tracks of this artist. */
    record Artist(String name, List<OfflineTrack> tracks) {
    }

    private static final String FAVORITES_FILE = "favorites.json";

    /** Only plain file names of a download are accepted when importing, so no path can escape. */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-]{1,20}\\.(webm|m4a|json|jpg)");
    private static final Pattern AUDIO_NAME = Pattern.compile("[A-Za-z0-9_-]{1,20}\\.(webm|m4a)");

    private static final int MAX_FAVORITES_BYTES = 1024 * 1024;

    private static Set<String> favorites;

    private OfflineLibrary() {
    }

    static File root(Context context) {
        return new File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Morphe");
    }

    static boolean isAudio(File file) {
        return file.isFile() && (file.getName().endsWith(".webm") || file.getName().endsWith(".m4a"));
    }

    /** Reads every downloaded track from the disk. */
    static List<OfflineTrack> loadTracks(File root) {
        List<OfflineTrack> tracks = new ArrayList<>();
        File[] files = root.listFiles(OfflineLibrary::isAudio);
        if (files == null) return tracks;

        for (File file : files) {
            tracks.add(OfflineTrack.load(file));
        }
        return tracks;
    }

    /** Paths of every downloaded audio file. Quick: no sidecar file is read. */
    static List<String> audioPaths(File root) {
        List<String> paths = new ArrayList<>();
        File[] files = root.listFiles(OfflineLibrary::isAudio);
        if (files == null) return paths;

        for (File file : files) paths.add(file.getAbsolutePath());
        return paths;
    }

    // ---------------------------------------------------------------- Sorting

    static Sort savedSort(String saved) {
        try {
            return Sort.valueOf(saved);
        } catch (Exception ignored) {
            return Sort.ARTIST;
        }
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    static Comparator<OfflineTrack> comparator(Sort sort) {
        Comparator<OfflineTrack> byTitle = Comparator.comparing(track -> lower(track.displayTitle()));
        Comparator<OfflineTrack> byArtist = Comparator.comparing(track -> lower(track.displayArtist()));

        return switch (sort) {
            case TITLE -> byTitle;
            case SIZE -> Comparator.comparingLong(
                    (OfflineTrack track) -> track.audioFile().length()).reversed();
            case RECENT -> Comparator.comparingLong(
                    (OfflineTrack track) -> track.audioFile().lastModified()).reversed();
            case OLDEST -> Comparator.comparingLong(
                    (OfflineTrack track) -> track.audioFile().lastModified());
            case ALBUM -> Comparator.comparing((OfflineTrack track) -> lower(track.album()))
                    .thenComparing(byArtist).thenComparing(byTitle);
            case DURATION -> Comparator.comparingInt(
                    (OfflineTrack track) -> track.durationSeconds()).reversed();
            default -> byArtist.thenComparing(byTitle);
        };
    }

    // ---------------------------------------------------------------- Artists

    /** Artists sorted by name, each with the tracks in the order they were given. */
    static List<Artist> groupByArtist(List<OfflineTrack> tracks) {
        Map<String, List<OfflineTrack>> groups = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (OfflineTrack track : tracks) {
            String key = lower(track.displayArtist());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(track);
            names.putIfAbsent(key, track.displayArtist());
        }

        List<Artist> artists = new ArrayList<>();
        for (Map.Entry<String, List<OfflineTrack>> entry : groups.entrySet()) {
            artists.add(new Artist(names.get(entry.getKey()), entry.getValue()));
        }
        artists.sort(Comparator.comparing(artist -> lower(artist.name())));
        return artists;
    }

    // -------------------------------------------------------------- Favorites

    private static Set<String> favorites(File root) {
        if (favorites != null) return favorites;

        favorites = new LinkedHashSet<>();
        File file = new File(root, FAVORITES_FILE);
        if (!file.isFile()) return favorites;
        try {
            JSONArray array = new JSONArray(OfflineStorage.readText(file));
            for (int i = 0; i < array.length(); i++) {
                favorites.add(array.getString(i));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read the favorites", ex);
        }
        return favorites;
    }

    private static void saveFavorites(File root) {
        try {
            if (!root.isDirectory() && !root.mkdirs()) return;
            JSONArray array = new JSONArray();
            for (String videoId : favorites(root)) array.put(videoId);
            OfflineStorage.writeText(new File(root, FAVORITES_FILE), array.toString());
        } catch (Exception ex) {
            Logger.printException(() -> "Could not save the favorites", ex);
        }
    }

    static synchronized boolean isFavorite(File root, String videoId) {
        return favorites(root).contains(videoId);
    }

    /** @return Whether the track is a favorite after the change. */
    static synchronized boolean toggleFavorite(File root, String videoId) {
        Set<String> set = favorites(root);
        final boolean nowFavorite = set.add(videoId);
        if (!nowFavorite) set.remove(videoId);
        saveFavorites(root);
        return nowFavorite;
    }

    static synchronized void forgetFavorite(File root, String videoId) {
        if (favorites(root).remove(videoId)) saveFavorites(root);
    }

    static synchronized int favoriteCount(File root) {
        return favorites(root).size();
    }

    // -------------------------------------------------------- Export / import

    /**
     * Writes every track, its details, its cover and the favorites into one zip file.
     * Audio is already compressed, so nothing is gained by compressing it again.
     *
     * @return The number of tracks written.
     */
    static int exportAll(File root, OutputStream destination) throws IOException {
        File[] files = root.listFiles(file -> file.isFile()
                && (SAFE_NAME.matcher(file.getName()).matches()
                || FAVORITES_FILE.equals(file.getName())));
        if (files == null) files = new File[0];

        int tracks = 0;
        try (ZipOutputStream zip = new ZipOutputStream(destination)) {
            zip.setLevel(Deflater.NO_COMPRESSION);
            byte[] buffer = new byte[64 * 1024];
            for (File file : files) {
                zip.putNextEntry(new ZipEntry(file.getName()));
                try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) zip.write(buffer, 0, read);
                }
                zip.closeEntry();
                if (AUDIO_NAME.matcher(file.getName()).matches()) tracks++;
            }
        }
        return tracks;
    }

    /**
     * Reads a zip made by {@link #exportAll}. Tracks that are already on the phone are left
     * alone, and the favorites are merged with the existing ones.
     *
     * @return The number of new tracks.
     */
    static int importAll(File root, InputStream source) throws IOException {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create " + root);

        int added = 0;
        try (ZipInputStream zip = new ZipInputStream(source)) {
            byte[] buffer = new byte[64 * 1024];
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) continue;

                if (FAVORITES_FILE.equals(name)) {
                    mergeFavorites(root, zip);
                    continue;
                }
                // A name with a folder in it, or one that is not a download, is ignored.
                if (!SAFE_NAME.matcher(name).matches()) continue;

                File target = new File(root, name);
                if (target.exists()) continue;

                File partial = new File(root, name + ".part");
                try (FileOutputStream output = new FileOutputStream(partial)) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) output.write(buffer, 0, read);
                }
                if (!partial.renameTo(target)) {
                    OfflineStorage.delete(partial);
                    continue;
                }
                if (AUDIO_NAME.matcher(name).matches()) added++;
            }
        }
        return added;
    }

    private static void mergeFavorites(File root, InputStream input) throws IOException {
        java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = input.read(chunk)) != -1) {
            data.write(chunk, 0, read);
            if (data.size() > MAX_FAVORITES_BYTES) return;
        }
        try {
            //noinspection CharsetObjectCanBeUsed
            JSONArray array = new JSONArray(data.toString("UTF-8"));
            synchronized (OfflineLibrary.class) {
                Set<String> set = favorites(root);
                for (int i = 0; i < array.length(); i++) {
                    String videoId = array.getString(i);
                    if (videoId.matches("[A-Za-z0-9_-]{1,20}")) set.add(videoId);
                }
                saveFavorites(root);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not merge the imported favorites", ex);
        }
    }
}
