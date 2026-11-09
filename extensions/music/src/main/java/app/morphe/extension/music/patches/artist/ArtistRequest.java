/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.artist;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.Route;

/**
 * Finds every artist of a song, with the id of each artist page.
 *
 * The song menu of YouTube Music only goes to one artist, so the names and ids of all of them
 * are fetched ahead of time (as soon as a song starts) and kept for the artist picker.
 */
public final class ArtistRequest {

    /** An artist of a song: the name to show and the id of the artist page. */
    public record Artist(String name, String browseId) {
    }

    private static final String API_URL = "https://music.youtube.com/youtubei/v1/";

    // Public key of the YouTube Music web client. Only used if the request without it is refused.
    private static final String WEB_CLIENT_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30";

    private static final Route.CompiledRoute NEXT_ROUTE =
            new Route(Route.Method.POST, "next?prettyPrint=false").compile();
    private static final Route.CompiledRoute NEXT_ROUTE_WITH_KEY =
            new Route(Route.Method.POST, "next?prettyPrint=false&key=" + WEB_CLIENT_KEY).compile();

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:130.0) Gecko/20100101 Firefox/130.0";

    private static final int CONNECTION_TIMEOUT_MILLISECONDS = 8 * 1000;
    private static final int NUMBER_OF_SONGS_TO_CACHE = 12;
    /** A failed song is not asked again at once, so a missing network is not hammered. */
    private static final long FAILURE_COOLDOWN_MILLISECONDS = 30 * 1000L;

    @SuppressWarnings("FieldMayBeFinal")
    private static final Map<String, List<Artist>> cache =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<Artist>> eldest) {
                    return size() > NUMBER_OF_SONGS_TO_CACHE;
                }
            };
    // All three below are guarded by synchronized (cache).
    private static final Set<String> inFlight = new HashSet<>();
    private static final Map<String, Long> failedAt = new HashMap<>();

    private ArtistRequest() {
    }

    /**
     * @return The artists of the song, in the order the song lists them, or null if they
     *         are not known (yet).
     */
    @Nullable
    public static List<Artist> getCached(@NonNull String videoId) {
        synchronized (cache) {
            return cache.get(videoId);
        }
    }

    /** Starts looking for the artists of the song, unless known already or being fetched. */
    public static void fetchIfNeeded(@NonNull String videoId) {
        if (videoId.isEmpty()) return;

        synchronized (cache) {
            if (cache.containsKey(videoId) || inFlight.contains(videoId)) return;
            Long failed = failedAt.get(videoId);
            if (failed != null && System.currentTimeMillis() - failed < FAILURE_COOLDOWN_MILLISECONDS) {
                return;
            }
            inFlight.add(videoId);
        }

        Utils.runOnBackgroundThread(() -> {
            List<Artist> artists = null;
            try {
                artists = fetch(videoId);
            } catch (Exception ex) {
                Logger.printException(() -> "Artist fetch failure", ex);
            }
            synchronized (cache) {
                inFlight.remove(videoId);
                if (artists != null) {
                    cache.put(videoId, artists);
                    failedAt.remove(videoId);
                } else {
                    failedAt.put(videoId, System.currentTimeMillis());
                }
            }
        });
    }

    @Nullable
    private static List<Artist> fetch(@NonNull String videoId) {
        // The second attempt adds the public key of the web client.
        for (int attempt = 0; attempt < 2; attempt++) {
            JSONObject json = sendRequest(videoId, attempt == 1);
            if (json != null) {
                List<Artist> artists = parse(json, videoId);
                Logger.printDebug(() -> "Artists of " + videoId + ": " + artists);
                return artists;
            }
        }
        return null;
    }

    @Nullable
    private static JSONObject sendRequest(@NonNull String videoId, boolean withKey) {
        try {
            final String clientVersion = webClientVersion();
            HttpURLConnection connection = Requester.getConnectionFromCompiledRoute(
                    API_URL, withKey ? NEXT_ROUTE_WITH_KEY : NEXT_ROUTE);

            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Origin", "https://music.youtube.com");
            connection.setRequestProperty("Referer", "https://music.youtube.com/");
            connection.setRequestProperty("X-YouTube-Client-Name", "67");
            connection.setRequestProperty("X-YouTube-Client-Version", clientVersion);
            // Skips the consent page that some European countries get.
            connection.setRequestProperty("Cookie", "SOCS=CAI");

            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(CONNECTION_TIMEOUT_MILLISECONDS);

            byte[] body = buildRequestBody(videoId, clientVersion);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }

            final int responseCode = connection.getResponseCode();
            if (responseCode == Requester.HTTP_STATUS_CODE_SUCCESS) {
                return Requester.parseJSONObject(connection);
            }
            Logger.printInfo(() -> "Artist request failed: " + responseCode);
        } catch (IOException ex) {
            Logger.printInfo(() -> "Artist request network error", ex);
        } catch (Exception ex) {
            Logger.printException(() -> "Artist request failed", ex);
        }
        return null;
    }

    /** The web client wants a recent version, which is built from yesterday's date. */
    private static String webClientVersion() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        long yesterday = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        return "1." + format.format(yesterday) + ".01.00";
    }

    private static byte[] buildRequestBody(@NonNull String videoId, @NonNull String clientVersion) {
        JSONObject body = new JSONObject();
        try {
            JSONObject client = new JSONObject();
            client.put("clientName", "WEB_REMIX");
            client.put("clientVersion", clientVersion);
            // The names of the artists are the same in every language.
            client.put("hl", "en");
            client.put("gl", Requester.getAppLocale().getCountry());

            JSONObject context = new JSONObject();
            context.put("client", client);

            body.put("context", context);
            body.put("videoId", videoId);
            body.put("isAudioOnly", true);
        } catch (JSONException ex) {
            Logger.printException(() -> "Failed to build the artist request", ex);
        }
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Nullable
    private static JSONObject child(@Nullable JSONObject parent, @NonNull String key) {
        return parent == null ? null : parent.optJSONObject(key);
    }

    /** The song row of the queue, which is sometimes wrapped to offer the video version too. */
    @Nullable
    private static JSONObject songRenderer(@Nullable JSONObject item) {
        JSONObject renderer = child(item, "playlistPanelVideoRenderer");
        if (renderer != null) return renderer;

        JSONObject wrapper = child(item, "playlistPanelVideoWrapperRenderer");
        return child(child(wrapper, "primaryRenderer"), "playlistPanelVideoRenderer");
    }

    @NonNull
    private static List<Artist> parse(@NonNull JSONObject root, @NonNull String videoId) {
        JSONObject tabbed = child(child(child(root, "contents"),
                "singleColumnMusicWatchNextResultsRenderer"), "tabbedRenderer");
        JSONObject watchNext = child(tabbed, "watchNextTabbedResultsRenderer");
        JSONArray tabs = watchNext == null ? null : watchNext.optJSONArray("tabs");
        JSONObject firstTab = tabs == null ? null : tabs.optJSONObject(0);

        JSONObject queue = child(child(child(child(child(firstTab, "tabRenderer"), "content"),
                "musicQueueRenderer"), "content"), "playlistPanelRenderer");
        JSONArray contents = queue == null ? null : queue.optJSONArray("contents");
        if (contents == null) return Collections.emptyList();

        // The first row is the song that was asked for.
        JSONObject song = null;
        for (int i = 0; i < contents.length(); i++) {
            JSONObject renderer = songRenderer(contents.optJSONObject(i));
            if (renderer == null) continue;

            if (song == null) song = renderer;
            if (videoId.equals(renderer.optString("videoId", ""))) {
                song = renderer;
                break;
            }
        }
        if (song == null) return Collections.emptyList();

        List<Artist> artists = readArtists(song.optJSONObject("longBylineText"));
        if (artists.isEmpty()) artists = readArtists(song.optJSONObject("shortBylineText"));
        return Collections.unmodifiableList(artists);
    }

    /** Every run of the line under the title that links to an artist page. */
    @NonNull
    private static List<Artist> readArtists(@Nullable JSONObject text) {
        List<Artist> artists = new ArrayList<>();
        JSONArray runs = text == null ? null : text.optJSONArray("runs");
        if (runs == null) return artists;

        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            JSONObject browse = child(child(run, "navigationEndpoint"), "browseEndpoint");
            if (browse == null) continue;

            String browseId = browse.optString("browseId", "");
            if (!browseId.startsWith("UC")) continue;

            // The album is in the same line, and is not an artist.
            JSONObject config = child(child(browse, "browseEndpointContextSupportedConfigs"),
                    "browseEndpointContextMusicConfig");
            String pageType = config == null ? "" : config.optString("pageType", "");
            if (!pageType.isEmpty() && !pageType.contains("ARTIST") && !pageType.contains("USER_CHANNEL")) {
                continue;
            }

            String name = run.optString("text", "").trim();
            if (name.isEmpty() || containsId(artists, browseId)) continue;
            artists.add(new Artist(name, browseId));
        }
        return artists;
    }

    private static boolean containsId(@NonNull List<Artist> artists, @NonNull String browseId) {
        for (Artist artist : artists) {
            if (artist.browseId().equals(browseId)) return true;
        }
        return false;
    }
}
