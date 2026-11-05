/*
 * Custom addition: lists every track of an album or playlist, so the Download button
 * of an album menu can save the whole album inside the app.
 */

package app.morphe.extension.music.patches.downloads;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.album.PlaylistRequest;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.Route;
import app.morphe.extension.shared.spoof.ClientType;

/** Blocking fetch, must not be called from the main thread. */
final class PlaylistTracksRequest {

    /** An album with the tracks of the playlist it plays, and the name to file the tracks under. */
    record Result(String title, List<PlaylistRequest.Song> songs) {
    }

    private static final String YT_API_URL = "https://youtubei.googleapis.com/youtubei/v1/";
    private static final ClientType CLIENT = ClientType.ANDROID_VR_SABR;
    private static final int TIMEOUT_MILLISECONDS = 10_000;
    private static final int MAX_ATTEMPTS = 3;

    private static final Route.CompiledRoute NEXT_ROUTE = new Route(Route.Method.POST,
            "next?fields=contents.singleColumnWatchNextResults.playlist.playlist&prettyPrint=false")
            .compile();
    private static final Route.CompiledRoute BROWSE_ROUTE = new Route(Route.Method.POST,
            "browse?prettyPrint=false").compile();

    private static final Pattern AUDIO_PLAYLIST = Pattern.compile("OLAK5uy_[A-Za-z0-9_-]{20,}");
    private static final Pattern ALBUM_PREFIX = Pattern.compile("^Album[\\s\\-–—•:·]*");

    private PlaylistTracksRequest() {
    }

    /**
     * @param id A playlist id, or the browse id of an album (MPREb_...).
     */
    static Result fetch(String id) {
        String playlistId = id;
        if (id.startsWith("MPREb_")) {
            playlistId = resolveAlbumPlaylist(id);
            if (playlistId == null) {
                Logger.printDebug(() -> "Could not find the playlist of album " + id);
                return new Result("", Collections.emptyList());
            }
        }
        final String finalId = playlistId;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                JSONObject json = post(NEXT_ROUTE, body(null, finalId));
                if (json != null) {
                    Result result = parse(json);
                    if (!result.songs().isEmpty()) return result;
                }
                Thread.sleep(500L * attempt);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                Logger.printException(() -> "Could not fetch tracks of " + finalId, ex);
            }
        }
        return new Result("", Collections.emptyList());
    }

    @Nullable
    private static String resolveAlbumPlaylist(String browseId) {
        try {
            JSONObject json = post(BROWSE_ROUTE, body(browseId, null));
            if (json == null) return null;
            Matcher matcher = AUDIO_PLAYLIST.matcher(json.toString());
            return matcher.find() ? matcher.group() : null;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not browse album " + browseId, ex);
            return null;
        }
    }

    @Nullable
    private static JSONObject post(Route.CompiledRoute route, byte[] body) throws Exception {
        HttpURLConnection connection = Requester.getConnectionFromCompiledRoute(YT_API_URL, route);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent", CLIENT.userAgent);
        connection.setRequestProperty("X-YouTube-Client-Name", String.valueOf(CLIENT.id));
        connection.setRequestProperty("X-YouTube-Client-Version", CLIENT.clientVersion);
        connection.setRequestProperty("X-GOOG-API-FORMAT-VERSION", "2");
        connection.setUseCaches(false);
        connection.setDoOutput(true);
        connection.setConnectTimeout(TIMEOUT_MILLISECONDS);
        connection.setReadTimeout(TIMEOUT_MILLISECONDS);
        connection.setFixedLengthStreamingMode(body.length);
        connection.getOutputStream().write(body);

        if (connection.getResponseCode() != Requester.HTTP_STATUS_CODE_SUCCESS) {
            final int code = connection.getResponseCode();
            Logger.printInfo(() -> "InnerTube request failed: " + code);
            connection.disconnect();
            return null;
        }
        return Requester.parseJSONObjectAndDisconnect(connection);
    }

    private static byte[] body(@Nullable String browseId, @Nullable String playlistId) throws Exception {
        TimeZone zone = TimeZone.getDefault();
        JSONObject client = new JSONObject()
                .put("deviceMake", CLIENT.deviceMake)
                .put("deviceModel", CLIENT.deviceModel)
                .put("clientName", CLIENT.clientName)
                .put("clientVersion", CLIENT.clientVersion)
                .put("osName", CLIENT.osName)
                .put("osVersion", CLIENT.osVersion)
                .put("androidSdkVersion", CLIENT.androidSdkVersion)
                // English, so the "Album" title prefix can be stripped reliably.
                .put("hl", "en")
                .put("gl", Requester.getAppLocale().getCountry())
                .put("timeZone", zone.getID())
                .put("utcOffsetMinutes", String.valueOf(zone.getOffset(new Date().getTime()) / 60000));

        JSONObject json = new JSONObject()
                .put("context", new JSONObject().put("client", client))
                .put("contentCheckOk", true)
                .put("racyCheckOk", true);
        if (browseId != null) json.put("browseId", browseId);
        if (playlistId != null) json.put("playlistId", playlistId);
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    @NonNull
    private static Result parse(JSONObject json) {
        JSONObject playlist = json.optJSONObject("contents");
        playlist = playlist == null ? null : playlist.optJSONObject("singleColumnWatchNextResults");
        playlist = playlist == null ? null : playlist.optJSONObject("playlist");
        playlist = playlist == null ? null : playlist.optJSONObject("playlist");
        if (playlist == null) return new Result("", Collections.emptyList());

        String title = ALBUM_PREFIX.matcher(playlist.optString("title", "")).replaceFirst("").trim();

        List<PlaylistRequest.Song> songs = new ArrayList<>();
        JSONArray contents = playlist.optJSONArray("contents");
        for (int i = 0; contents != null && i < contents.length(); i++) {
            JSONObject renderer = contents.optJSONObject(i) == null
                    ? null : contents.optJSONObject(i).optJSONObject("playlistPanelVideoRenderer");
            if (renderer == null) continue;

            String videoId = renderer.optString("videoId", "");
            if (videoId.isEmpty()) {
                JSONObject nav = renderer.optJSONObject("navigationEndpoint");
                JSONObject watch = nav == null ? null : nav.optJSONObject("watchEndpoint");
                videoId = watch == null ? "" : watch.optString("videoId", "");
            }
            if (videoId.isEmpty()) continue;

            songs.add(new PlaylistRequest.Song(videoId, firstRun(renderer, "title"),
                    firstRun(renderer, "longBylineText"), duration(firstRun(renderer, "lengthText"))));
        }
        return new Result(title, songs);
    }

    private static String firstRun(JSONObject renderer, String name) {
        JSONObject text = renderer.optJSONObject(name);
        JSONArray runs = text == null ? null : text.optJSONArray("runs");
        JSONObject first = runs == null ? null : runs.optJSONObject(0);
        return first == null ? "" : first.optString("text", "");
    }

    private static int duration(String text) {
        int seconds = 0;
        for (String part : text.split(":")) {
            try {
                seconds = seconds * 60 + Integer.parseInt(part.trim());
            } catch (NumberFormatException ex) {
                return 0;
            }
        }
        return seconds;
    }
}
