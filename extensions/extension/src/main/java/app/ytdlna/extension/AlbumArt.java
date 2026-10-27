package app.ytdlna.extension;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Square album covers for YouTube Music titles, from public music databases that need no API key:
 * Deezer first, then the iTunes Search API. The cover URL is sent to the renderer as is.
 * <p>
 * Can be turned off in the Morphe settings (it sends title and artist to these services).
 */
final class AlbumArt {
    /** Morphe's settings storage and the key of our switch in it. */
    private static final String MORPHE_PREFS = "morphe_prefs";
    private static final String PREF_ENABLED = "ytdlna_album_art_lookup";

    private static final int TIMEOUT_MS = 4000;
    private static final String NOT_FOUND = "";

    /** Title + artist -> cover URL, or NOT_FOUND. */
    private static final Map<String, String> cache = new ConcurrentHashMap<>();
    private static final ExecutorService lookups = Executors.newFixedThreadPool(4);

    /** Parts of video titles that are not part of the song title. */
    private static final Pattern NOISE = Pattern.compile(
            "(?i)\\s*[(\\[][^)\\]]*(official|video|audio|lyric|visuali[sz]er|remaster|hd|4k|mv)[^)\\]]*[)\\]]");
    private static final Pattern FEATURING = Pattern.compile("(?i)\\s+(feat\\.?|ft\\.?|featuring)\\s+.*$");

    private AlbumArt() {
    }

    static boolean isEnabled(Context context) {
        try {
            return context.getSharedPreferences(MORPHE_PREFS, Context.MODE_PRIVATE)
                    .getBoolean(PREF_ENABLED, true);
        } catch (Exception e) {
            return true;
        }
    }

    /** Cover URL if already looked up, otherwise null. */
    static String cached(String title, String artist) {
        String url = cache.get(key(title, artist));
        return url == null || url.equals(NOT_FOUND) ? null : url;
    }

    /** Looks up a cover. Blocks for at most a few seconds. Returns null if none was found. */
    static String lookup(String title, String artist) {
        String key = key(title, artist);
        String cachedUrl = cache.get(key);
        if (cachedUrl != null) return cachedUrl.equals(NOT_FOUND) ? null : cachedUrl;

        String cleanTitle = clean(title);
        Candidate deezer = null;
        Candidate itunes = null;
        try {
            deezer = deezer(cleanTitle, artist);
        } catch (Exception e) {
            Log.d("Deezer lookup failed: " + e.getMessage());
        }
        if (deezer == null || deezer.score < GOOD_MATCH) {
            try {
                itunes = itunes(cleanTitle, artist);
            } catch (Exception e) {
                Log.d("iTunes lookup failed: " + e.getMessage());
            }
        }
        // A good match from either source, Deezer first; otherwise the best of what was found.
        Candidate best = deezer;
        if (itunes != null && (best == null || best.score < GOOD_MATCH && itunes.score > best.score)) best = itunes;
        String url = best == null ? null : best.url;
        cache.put(key, url == null ? NOT_FOUND : url);
        Log.d("Cover for \"" + cleanTitle + "\" / \"" + artist + "\": " + url
                + (best == null ? "" : " (" + best.source + ", \"" + best.album + "\", score " + best.score + ")"));
        return url;
    }

    /** Looks up several titles in parallel, waiting at most {@code timeoutMs} in total. */
    static void prefetch(List<String[]> titlesAndArtists, long timeoutMs) {
        List<Callable<String>> tasks = new ArrayList<>();
        for (String[] item : titlesAndArtists) {
            if (!cache.containsKey(key(item[0], item[1]))) tasks.add(() -> lookup(item[0], item[1]));
        }
        if (tasks.isEmpty()) return;
        try {
            lookups.invokeAll(tasks, timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // region Sources

    /** Score from which a match is taken without asking the next source. */
    private static final int GOOD_MATCH = 2;

    /** Album names of compilations, whose covers are not the song's album cover. */
    private static final Pattern COMPILATION = Pattern.compile(
            "(?i)\\b(hits|best of|greatest|collection|decades?|anthology|essential|now that|compilation"
                    + "|various|party|summer|top \\d+|\\d0s|\\d0er|playlist|karaoke|tribute|workout)\\b");

    private static final class Candidate {
        final String url;
        final String album;
        final int score;
        final String source;

        Candidate(String url, String album, int score, String source) {
            this.url = url;
            this.album = album;
            this.score = score;
            this.source = source;
        }
    }

    /** Rates a search result: the same title scores, compilations and karaoke versions do not. */
    private static int score(String title, String foundTitle, String album, boolean variousArtists) {
        int score = 0;
        String t = normalize(title);
        String f = normalize(foundTitle);
        if (t.equals(f)) score += 2;
        else if (f.startsWith(t) || t.startsWith(f)) score += 1;
        if (COMPILATION.matcher(album).find() || variousArtists) score -= 3;
        if (normalize(album).equals(t)) score += 1; // The single.
        return score;
    }

    /** Deezer search: https://developers.deezer.com/api/search, 1000x1000 covers. */
    private static Candidate deezer(String title, String artist) throws Exception {
        String query = artist.isEmpty()
                ? title
                : "artist:\"" + artist + "\" track:\"" + title + "\"";
        JSONArray data = getJson("https://api.deezer.com/search?limit=10&q=" + encode(query)).optJSONArray("data");
        if ((data == null || data.length() == 0) && !artist.isEmpty()) {
            // Strict search found nothing, try a plain one.
            data = getJson("https://api.deezer.com/search?limit=10&q=" + encode(artist + " " + title))
                    .optJSONArray("data");
        }
        if (data == null) return null;
        Candidate best = null;
        for (int i = 0; i < data.length(); i++) {
            JSONObject track = data.getJSONObject(i);
            JSONObject trackArtist = track.optJSONObject("artist");
            if (!artist.isEmpty() && (trackArtist == null || !similar(artist, trackArtist.optString("name")))) continue;
            JSONObject album = track.optJSONObject("album");
            if (album == null || !album.optString("cover_xl").startsWith("http")) continue;
            int score = score(title, track.optString("title"), album.optString("title"), false);
            if (best == null || score > best.score) {
                best = new Candidate(album.optString("cover_xl"), album.optString("title"), score, "Deezer");
            }
        }
        return best;
    }

    /** iTunes Search API: https://performance-partners.apple.com/search-api, any size up to 3000x3000. */
    private static Candidate itunes(String title, String artist) throws Exception {
        JSONArray results = getJson("https://itunes.apple.com/search?media=music&entity=song&limit=10&term="
                + encode((artist + " " + title).trim())).optJSONArray("results");
        if (results == null) return null;
        Candidate best = null;
        for (int i = 0; i < results.length(); i++) {
            JSONObject song = results.getJSONObject(i);
            if (!artist.isEmpty() && !similar(artist, song.optString("artistName"))) continue;
            String cover = song.optString("artworkUrl100");
            if (!cover.startsWith("http")) continue;
            String collectionArtist = song.optString("collectionArtistName");
            boolean various = !collectionArtist.isEmpty() && !similar(artist, collectionArtist);
            int score = score(title, song.optString("trackName"), song.optString("collectionName"), various);
            if (best == null || score > best.score) {
                best = new Candidate(cover.replace("100x100bb", "1200x1200bb"),
                        song.optString("collectionName"), score, "iTunes");
            }
        }
        return best;
    }

    // endregion

    // region Helpers

    private static JSONObject getJson(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new Exception("HTTP " + status);
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                return new JSONObject(out.toString("UTF-8"));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String clean(String title) {
        String cleaned = NOISE.matcher(title).replaceAll("");
        cleaned = FEATURING.matcher(cleaned).replaceAll("");
        return cleaned.trim();
    }

    /** Artist names match loosely: "Rick Astley" ~ "rick astley", "Bronski Beat" ~ "Bronski Beat & Marc Almond". */
    private static boolean similar(String a, String b) {
        String x = normalize(a);
        String y = normalize(b);
        return !x.isEmpty() && !y.isEmpty() && (x.contains(y) || y.contains(x));
    }

    private static String normalize(String s) {
        String decomposed = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String key(String title, String artist) {
        return normalize(title) + "|" + normalize(artist);
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }

    // endregion
}
