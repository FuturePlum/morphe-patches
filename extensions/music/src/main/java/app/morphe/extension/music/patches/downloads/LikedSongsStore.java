/*
 * Custom addition: the liked songs of the account, for the favorites by artist page.
 *
 * The list is the "Liked music" playlist of the account. It is asked to YouTube with the sign-in
 * headers the app itself sent in its own requests (see AuthUtils), so nothing is typed in by the
 * person. A copy is kept in a small file, so the page opens at once and works without network.
 *
 * The answer is read without relying on where things sit in it: every song row is looked for
 * anywhere in the answer, and so is the token that asks for the next part of the list.
 */

package app.morphe.extension.music.patches.downloads;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.innertube.utils.AuthUtils;

/** Blocking calls, which must not be made from the main thread. */
final class LikedSongsStore {

    /** One liked song. The artists are the names shown for it, in order. */
    record Song(String videoId, String title, List<String> artists, int seconds) {
    }

    /**
     * @param songs The liked songs, or null when they could not be read.
     * @param error What went wrong when {@code songs} is null: "login", "network", "empty",
     *              or "http:" followed by the status code.
     */
    record Result(@Nullable List<Song> songs, @Nullable String error) {
    }

    static final String ERROR_LOGIN = "login";
    static final String ERROR_NETWORK = "network";
    static final String ERROR_EMPTY = "empty";
    static final String ERROR_HTTP_PREFIX = "http:";

    private static final String CACHE_FILE = "morphe_liked_songs.json";
    private static final String LIKED_PLAYLIST_BROWSE_ID = "VLLM";
    /** Key of the Android client of YouTube Music, as used by the other requests of this patch. */
    private static final String API_URL = "https://music.youtube.com/youtubei/v1/browse"
            + "?prettyPrint=false&key=AIzaSyAOghZGza2MQSZkY_zfZ370N-PUdXEo8AI";
    /** The web client of YouTube Music, as used by the lyrics requests of Morphe. */
    private static final String WEB_CLIENT_VERSION = "1.20260914.01.00";
    private static final String WEB_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    private static final int TIMEOUT_MILLISECONDS = 15_000;
    /** About a hundred songs come in each part, so this holds thousands of songs. */
    private static final int MAX_PARTS = 80;

    private LikedSongsStore() {
    }

    // region Cache

    @NonNull
    static synchronized List<Song> loadCache(Context context) {
        List<Song> songs = new ArrayList<>();
        File file = new File(context.getFilesDir(), CACHE_FILE);
        if (!file.isFile()) return songs;

        try (InputStream input = new java.io.FileInputStream(file)) {
            JSONArray array = new JSONArray(new String(readAll(input), StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;

                List<String> artists = new ArrayList<>();
                JSONArray names = item.optJSONArray("a");
                for (int j = 0; names != null && j < names.length(); j++) {
                    String name = names.optString(j, "").trim();
                    if (!name.isEmpty()) artists.add(name);
                }
                String id = item.optString("v", "");
                if (id.isEmpty()) continue;
                songs.add(new Song(id, item.optString("t", ""), artists, item.optInt("d", 0)));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read the liked songs copy", ex);
            songs.clear();
        }
        return songs;
    }

    private static synchronized void saveCache(Context context, List<Song> songs) {
        try {
            JSONArray array = new JSONArray();
            for (Song song : songs) {
                JSONObject item = new JSONObject();
                item.put("v", song.videoId());
                item.put("t", song.title());
                item.put("a", new JSONArray(song.artists()));
                item.put("d", song.seconds());
                array.put(item);
            }
            File file = new File(context.getFilesDir(), CACHE_FILE);
            File temporary = new File(context.getFilesDir(), CACHE_FILE + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(array.toString().getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            if (!temporary.renameTo(file)) {
                Logger.printDebug(() -> "Could not move the liked songs copy into place");
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not save the liked songs copy", ex);
        }
    }

    // endregion

    // region Request

    /** How the app presents itself to YouTube. */
    private record Client(String name, String id, String version, String userAgent, boolean android) {
    }

    /** Asks YouTube for the whole liked music playlist, and keeps a copy when it worked. */
    static Result fetch(Context context) {
        if (AuthUtils.getAuthorization().isEmpty()) return new Result(null, ERROR_LOGIN);

        // The app itself first, since it is the one the sign-in belongs to. If YouTube does not
        // answer with songs, the web client is tried too, as other requests of Morphe use it.
        Client android = new Client("ANDROID_MUSIC", "21", appVersion(context),
                "com.google.android.apps.youtube.music/" + appVersion(context)
                        + " (Linux; U; Android " + Build.VERSION.RELEASE + ") gzip", true);
        Result first = fetchWith(context, android);
        if (first.songs() != null) return first;

        Client web = new Client("WEB_REMIX", "67", WEB_CLIENT_VERSION, WEB_USER_AGENT, false);
        Result second = fetchWith(context, web);
        return second.songs() != null ? second : first;
    }

    private static Result fetchWith(Context context, Client client) {
        try {
            List<Song> songs = new ArrayList<>();
            String token = null;
            String previousToken = null;
            for (int part = 0; part < MAX_PARTS; part++) {
                JSONObject json = post(client, token == null
                        ? body(client, LIKED_PLAYLIST_BROWSE_ID, null)
                        : body(client, null, token));

                List<JSONObject> rows = new ArrayList<>();
                collectRows(json, rows);
                for (JSONObject row : rows) {
                    Song song = parse(row);
                    if (song != null) songs.add(song);
                }

                previousToken = token;
                token = findContinuation(json);
                if (token == null || token.equals(previousToken)) break;
            }

            if (songs.isEmpty()) return new Result(null, ERROR_EMPTY);
            saveCache(context, songs);
            return new Result(songs, null);
        } catch (HttpFailure failure) {
            Logger.printInfo(() -> client.name() + " liked songs request failed: " + failure.code);
            return new Result(null, ERROR_HTTP_PREFIX + failure.code);
        } catch (IOException ex) {
            Logger.printInfo(() -> client.name() + " liked songs request failed: " + ex);
            return new Result(null, ERROR_NETWORK);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read the liked songs", ex);
            return new Result(null, ERROR_NETWORK);
        }
    }

    private static final class HttpFailure extends IOException {
        final int code;

        HttpFailure(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    private static byte[] body(Client client, @Nullable String browseId,
                               @Nullable String continuation) throws JSONException {
        Locale locale = Locale.getDefault();
        JSONObject clientJson = new JSONObject()
                .put("clientName", client.name())
                .put("clientVersion", client.version())
                .put("hl", locale.getLanguage())
                .put("gl", locale.getCountry().isEmpty() ? "US" : locale.getCountry());
        if (client.android()) {
            clientJson.put("osName", "Android")
                    .put("osVersion", Build.VERSION.RELEASE)
                    .put("androidSdkVersion", Build.VERSION.SDK_INT);
        }

        JSONObject json = new JSONObject()
                .put("context", new JSONObject().put("client", clientJson));
        if (browseId != null) json.put("browseId", browseId);
        if (continuation != null) json.put("continuation", continuation);
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String appVersion(Context context) {
        try {
            String version = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            if (version != null && !version.isEmpty()) return version;
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not read the app version", ex);
        }
        return "9.30.53";
    }

    private static JSONObject post(Client client, byte[] body) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(API_URL).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(TIMEOUT_MILLISECONDS);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("User-Agent", client.userAgent());
            connection.setRequestProperty("X-YouTube-Client-Name", client.id());
            connection.setRequestProperty("X-YouTube-Client-Version", client.version());
            if (!client.android()) {
                connection.setRequestProperty("Origin", "https://music.youtube.com");
                connection.setRequestProperty("Cookie", "SOCS=CAI");
            }
            for (Map.Entry<String, String> header : AuthUtils.getRequestHeader().entrySet()) {
                if (!header.getValue().isEmpty()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
            }
            connection.setFixedLengthStreamingMode(body.length);
            connection.getOutputStream().write(body);

            final int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new HttpFailure(code);

            try (InputStream input = connection.getInputStream()) {
                return new JSONObject(new String(readAll(input), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    // endregion

    // region Reading the answer

    /** Finds the song rows wherever they are in the answer. */
    private static void collectRows(Object node, List<JSONObject> out) {
        if (node instanceof JSONObject object) {
            JSONObject row = object.optJSONObject("musicResponsiveListItemRenderer");
            if (row != null) {
                out.add(row);
                return;
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) collectRows(object.opt(keys.next()), out);
        } else if (node instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) collectRows(array.opt(i), out);
        }
    }

    /** @return The token that asks for the next part of the list, or null at the end. */
    @Nullable
    private static String findContinuation(Object node) {
        if (node instanceof JSONObject object) {
            JSONObject command = object.optJSONObject("continuationCommand");
            if (command != null && !command.optString("token", "").isEmpty()) {
                return command.optString("token");
            }
            JSONObject data = object.optJSONObject("nextContinuationData");
            if (data != null && !data.optString("continuation", "").isEmpty()) {
                return data.optString("continuation");
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String found = findContinuation(object.opt(keys.next()));
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) {
                String found = findContinuation(array.opt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** @return The first value of that key anywhere inside the node, or an empty string. */
    private static String firstString(@Nullable Object node, String key) {
        if (node instanceof JSONObject object) {
            String value = object.optString(key, "");
            if (!value.isEmpty() && object.opt(key) instanceof String) return value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String found = firstString(object.opt(keys.next()), key);
                if (!found.isEmpty()) return found;
            }
        } else if (node instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) {
                String found = firstString(array.opt(i), key);
                if (!found.isEmpty()) return found;
            }
        }
        return "";
    }

    @Nullable
    private static Song parse(JSONObject row) {
        // A greyed out row is a song that is not available any more.
        if (row.optString("musicItemRendererDisplayPolicy", "").contains("GREY_OUT")) return null;

        String videoId = firstString(row.optJSONObject("playlistItemData"), "videoId");
        if (videoId.isEmpty()) videoId = firstString(row, "videoId");
        if (!isVideoId(videoId)) return null;

        JSONArray columns = row.optJSONArray("flexColumns");
        JSONArray titleRuns = runs(columns, 0);
        String title = titleRuns == null || titleRuns.length() == 0
                ? "" : titleRuns.optJSONObject(0).optString("text", "").trim();
        if (title.isEmpty()) return null;

        List<String> artists = new ArrayList<>();
        JSONArray bylineRuns = runs(columns, 1);
        String firstText = "";
        for (int i = 0; bylineRuns != null && i < bylineRuns.length(); i++) {
            JSONObject run = bylineRuns.optJSONObject(i);
            if (run == null) continue;
            String text = run.optString("text", "").trim();
            if (firstText.isEmpty() && isName(text)) firstText = text;

            JSONObject endpoint = run.optJSONObject("navigationEndpoint");
            JSONObject browse = endpoint == null ? null : endpoint.optJSONObject("browseEndpoint");
            String browseId = browse == null ? "" : browse.optString("browseId", "");
            if (browseId.startsWith("UC") && isName(text) && !artists.contains(text)) {
                artists.add(text);
            }
        }
        // Songs that were not released by an artist channel show their names without a link.
        if (artists.isEmpty() && !firstText.isEmpty()) artists.add(firstText);

        int seconds = 0;
        JSONArray fixed = row.optJSONArray("fixedColumns");
        JSONObject fixedColumn = fixed == null ? null : fixed.optJSONObject(0);
        JSONObject fixedRenderer = fixedColumn == null
                ? null : fixedColumn.optJSONObject("musicResponsiveListItemFixedColumnRenderer");
        JSONObject fixedText = fixedRenderer == null ? null : fixedRenderer.optJSONObject("text");
        JSONArray fixedRuns = fixedText == null ? null : fixedText.optJSONArray("runs");
        if (fixedRuns != null && fixedRuns.length() > 0) {
            seconds = duration(fixedRuns.optJSONObject(0).optString("text", ""));
        }
        return new Song(videoId, title, artists, seconds);
    }

    /** A run that is a name, and not one of the dots and commas between the names. */
    private static boolean isName(String text) {
        if (text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetterOrDigit(text.charAt(i))) return true;
        }
        return false;
    }

    @Nullable
    private static JSONArray runs(@Nullable JSONArray columns, int index) {
        JSONObject column = columns == null ? null : columns.optJSONObject(index);
        JSONObject renderer = column == null
                ? null : column.optJSONObject("musicResponsiveListItemFlexColumnRenderer");
        JSONObject text = renderer == null ? null : renderer.optJSONObject("text");
        return text == null ? null : text.optJSONArray("runs");
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

    private static boolean isVideoId(String value) {
        if (value.length() != 11) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!valid) return false;
        }
        return true;
    }

    // endregion
}
