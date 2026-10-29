package app.ytdlna.extension;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Captions for the renderer (see docs/renderer-extras.md): the caption tracks of a video, from
 * the app's own player response, fetched from the addresses the app got and converted to SRT
 * and WebVTT for the phone's proxy.
 * <p>
 * The player response's proto classes are obfuscated: tracks are found in its bytes as the
 * messages with a timedtext address (fields: 1 address, 2 name, 3 vssId, 4 language, 5 "asr").
 */
public final class Captions {
    static final String SRT = "text/srt";
    static final String VTT = "text/vtt";

    /** Morphe's settings storage and our switch in it. */
    private static final String PREF_ENABLED = "ytdlna_captions";
    private static final int FETCH_TIMEOUT_MS = 5000;

    /** The video id of the load hook call that hands over the player response. */
    static volatile String hookedVideoId;

    /** Player response models of the last loaded videos (parsed when cast). */
    private static final Map<String, WeakReference<Object>> models = Collections.synchronizedMap(
            new LinkedHashMap<String, WeakReference<Object>>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, WeakReference<Object>> eldest) {
                    return size() > 6;
                }
            });
    /** Parsed tracks by video. */
    private static final Map<String, List<Track>> tracks = new java.util.concurrent.ConcurrentHashMap<>();
    /** The track sent with the title, by video. */
    private static final Map<String, Track> selected = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();

    static Track selected(String videoId) {
        return selected.get(videoId);
    }

    /** The lyrics as a caption track (YouTube Music, from Morphe's lyrics feature). */
    static final Track LYRICS = new Track("", "Lyrics", "lyrics", "", false);

    /** Serves timed lyrics as the video's "Lyrics" caption track. Null if there are no cues. */
    static Track putLyrics(String videoId, List<Cue> cues) {
        if (cues.isEmpty()) return null;
        fetched.put(videoId + "/" + LYRICS.vssId, cues);
        NativeProxy.nativePutText(path(videoId, LYRICS, "srt"), SRT, srt(cues));
        NativeProxy.nativePutText(path(videoId, LYRICS, "vtt"), VTT, vtt(cues));
        return LYRICS;
    }

    /** The track sent with the title, when it is not the preferred caption track. */
    static void select(String videoId, Track track) {
        selected.put(videoId, track);
    }

    /**
     * Prepares the captions of a title the renderer gets: the preferred track right away (its
     * addresses go into the title's metadata), the others in the background for the extras.
     *
     * @return the preferred track, published; null if there is none.
     */
    static Track prepare(String videoId, Runnable onMore) {
        List<Track> list = tracks(videoId);
        if (list.isEmpty()) return null;
        Track pick = preferred(list);
        if (pick != null && publish(videoId, pick)) {
            selected.put(videoId, pick);
        } else {
            pick = null;
        }
        Track sent = pick;
        worker.execute(() -> {
            boolean more = false;
            for (Track t : extraTracks(list, sent)) if (!isPublished(videoId, t)) more |= publish(videoId, t);
            if (more && onMore != null) onMore.run();
        });
        return pick;
    }

    /** At most this many tracks of a video are fetched for the extras (some have dozens). */
    private static final int EXTRA_TRACKS = 4;

    /**
     * The tracks worth fetching for the extras: the phone's language and English, written ones
     * before generated ones, a few at most.
     */
    private static List<Track> extraTracks(List<Track> list, Track pick) {
        String language = Locale.getDefault().getLanguage();
        List<Track> sorted = new ArrayList<>(list);
        sorted.sort((a, b) -> Integer.compare(rank(a, language, pick), rank(b, language, pick)));
        return sorted.subList(0, Math.min(EXTRA_TRACKS, sorted.size()));
    }

    private static int rank(Track t, String language, Track pick) {
        if (t == pick) return 0;
        boolean own = t.language.equals(language) || t.language.startsWith(language + "-");
        boolean english = t.language.equals("en") || t.language.startsWith("en-");
        return (own ? 1 : english ? 3 : 5) + (t.auto ? 1 : 0);
    }

    /** Converted cues by video and vssId; present once fetched. */
    private static final Map<String, List<Cue>> fetched = new java.util.concurrent.ConcurrentHashMap<>();

    static final class Track {
        final String url, name, vssId, language;
        final boolean auto;

        Track(String url, String name, String vssId, String language, boolean auto) {
            this.url = url;
            this.name = name;
            this.vssId = vssId;
            this.language = language;
            this.auto = auto;
        }

        /** A file name part for this track: language, "-auto" for generated ones. */
        String key() {
            String base = vssId.isEmpty() ? language : vssId.replaceAll("^a?\\.", "");
            return base.replaceAll("[^A-Za-z0-9_-]", "_") + (auto ? "-auto" : "");
        }

        @Override
        public String toString() {
            return language + (auto ? " (auto)" : "") + " \"" + name + "\"";
        }
    }

    static final class Cue {
        final long start, end;
        final String text;

        Cue(long start, long end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    private Captions() {
    }

    /** Called by the patched load method with the player response model. */
    public static void onPlayerResponse(Object model) {
        String videoId = hookedVideoId;
        if (model != null && videoId != null) models.put(videoId, new WeakReference<>(model));
    }

    static boolean enabled(Context context) {
        try {
            return context.getSharedPreferences("morphe_prefs", Context.MODE_PRIVATE).getBoolean(PREF_ENABLED, true);
        } catch (Exception e) {
            return true;
        }
    }

    /** The caption tracks of a video, empty if none or unknown. Blocks to read the response. */
    static List<Track> tracks(String videoId) {
        List<Track> known = tracks.get(videoId);
        if (known != null) return known;
        WeakReference<Object> ref = models.get(videoId);
        Object model = ref == null ? null : ref.get();
        if (model == null) return Collections.emptyList();
        List<Track> found = new ArrayList<>();
        try {
            byte[] response = findResponse(model);
            if (response != null) collect(response, 0, found);
        } catch (Throwable t) {
            Log.e("Reading the caption tracks of " + videoId + " failed", t);
        }
        tracks.put(videoId, found);
        Log.d("Caption tracks of " + videoId + ": " + found);
        return found;
    }

    /**
     * The track to show by default: one in the phone's language, a written one before a
     * generated one. Null if there is none in that language.
     */
    static Track preferred(List<Track> list) {
        String language = Locale.getDefault().getLanguage();
        Track auto = null;
        for (Track t : list) {
            if (!t.language.equals(language) && !t.language.startsWith(language + "-")) continue;
            if (!t.auto) return t;
            if (auto == null) auto = t;
        }
        return auto;
    }

    /** Fetches and converts a track (once), and serves it as SRT and WebVTT. False if it failed. */
    static boolean publish(String videoId, Track track) {
        String id = videoId + "/" + track.vssId;
        List<Cue> cues = fetched.get(id);
        if (cues == null) {
            try {
                cues = parse(fetch(track.url));
            } catch (Exception e) {
                Log.i("Captions " + track + " of " + videoId + " not fetched: " + e.getMessage());
                return false;
            }
            fetched.put(id, cues);
        }
        NativeProxy.nativePutText(path(videoId, track, "srt"), SRT, srt(cues));
        NativeProxy.nativePutText(path(videoId, track, "vtt"), VTT, vtt(cues));
        Log.d("Captions " + track + " of " + videoId + ": " + cues.size() + " cues");
        return true;
    }

    static boolean isPublished(String videoId, Track track) {
        return fetched.containsKey(videoId + "/" + track.vssId);
    }

    static String path(String videoId, Track track, String extension) {
        return "/captions/" + videoId + "." + track.key() + "." + extension;
    }

    /** The tracks for the extras document (the published ones, with their addresses). */
    static JSONArray extrasJson(String videoId, String baseUrl, Track selected) throws JSONException {
        JSONArray out = new JSONArray();
        List<Track> all = new ArrayList<>(tracks.getOrDefault(videoId, Collections.emptyList()));
        all.add(LYRICS);
        for (Track t : all) {
            if (!isPublished(videoId, t)) continue;
            out.put(new JSONObject()
                    .put("language", t.language)
                    .put("label", t.name)
                    .put("auto", t.auto)
                    .put("lyrics", t == LYRICS)
                    .put("default", t == selected)
                    .put("srt", baseUrl + path(videoId, t, "srt"))
                    .put("vtt", baseUrl + path(videoId, t, "vtt")));
        }
        return out;
    }

    // region Reading the player response

    /** The serialized proto among the model's fields that holds a timedtext address. */
    private static byte[] findResponse(Object model) throws Exception {
        for (Class<?> c = model.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().isPrimitive() || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                byte[] bytes = toBytes(f.get(model));
                if (bytes != null && contains(bytes, "/api/timedtext")) return bytes;
            }
        }
        return null;
    }

    private static byte[] toBytes(Object proto) {
        if (proto == null) return null;
        try {
            Method m = proto.getClass().getMethod("toByteArray");
            return (byte[]) m.invoke(proto);
        } catch (Exception e) {
            return null;
        }
    }

    /** Collects the messages with a timedtext address, depth-first. */
    private static void collect(byte[] message, int depth, List<Track> out) {
        if (depth > 12) return;
        List<Proto.Field> fields;
        try {
            fields = Proto.parse(message);
        } catch (IllegalArgumentException e) {
            return;
        }
        String url = Proto.string(fields, 1);
        if (url.startsWith("http") && url.contains("/api/timedtext")) {
            String language = Proto.string(fields, 4);
            String vssId = Proto.string(fields, 3);
            boolean auto = "asr".equals(Proto.string(fields, 5)) || vssId.startsWith("a.");
            out.add(new Track(url, name(fields), vssId, language, auto));
            return;
        }
        for (Proto.Field f : fields) {
            if (f.wireType == Proto.BYTES && f.data != null && f.data.length > 20 && contains(f.data, "/api/timedtext")) {
                collect(f.data, depth + 1, out);
            }
        }
    }

    /** The track's name: the text in its formatted string (field 2: {1: {1: text}} or {1: text}). */
    private static String name(List<Proto.Field> fields) {
        for (Proto.Field f : fields) {
            if (f.number != 2 || f.wireType != Proto.BYTES) continue;
            String text = firstText(f.data, 0);
            if (text != null) return text.trim();
        }
        return "";
    }

    private static String firstText(byte[] data, int depth) {
        if (depth > 4) return null;
        try {
            for (Proto.Field f : Proto.parse(data)) {
                if (f.wireType != Proto.BYTES) continue;
                if (isText(f.data)) return f.string();
                String nested = firstText(f.data, depth + 1);
                if (nested != null) return nested;
            }
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    private static boolean isText(byte[] data) {
        if (data == null || data.length == 0) return false;
        String s = new String(data, StandardCharsets.UTF_8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c < 0x20 && c != '\n' && c != '\t') || c == '�') return false;
        }
        return true;
    }

    private static boolean contains(byte[] data, String text) {
        byte[] needle = text.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + needle.length <= data.length; i++) {
            for (int j = 0; j < needle.length; j++) if (data[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }

    // endregion

    // region Fetching and converting

    private static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(FETCH_TIMEOUT_MS);
        c.setReadTimeout(FETCH_TIMEOUT_MS);
        if (!StreamProxy.userAgent.isEmpty()) c.setRequestProperty("User-Agent", StreamProxy.userAgent);
        try {
            int status = c.getResponseCode();
            if (status != 200) throw new java.io.IOException("HTTP " + status);
            try (java.io.InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    /** srv3 ({@code <p t="ms" d="ms">}) or the older format ({@code <text start="s" dur="s">}). */
    private static final Pattern SRV3 = Pattern.compile("<p\\s[^>]*?\\bt=\"(\\d+)\"[^>]*?\\bd=\"(\\d+)\"[^>]*>(.*?)</p>", Pattern.DOTALL);
    private static final Pattern LEGACY = Pattern.compile("<text\\s[^>]*?\\bstart=\"([\\d.]+)\"[^>]*?\\bdur=\"([\\d.]+)\"[^>]*>(.*?)</text>", Pattern.DOTALL);

    static List<Cue> parse(String xml) {
        List<Cue> cues = new ArrayList<>();
        Matcher m = SRV3.matcher(xml);
        while (m.find()) add(cues, Long.parseLong(m.group(1)), Long.parseLong(m.group(2)), m.group(3));
        if (cues.isEmpty()) {
            m = LEGACY.matcher(xml);
            while (m.find()) {
                add(cues, Math.round(Double.parseDouble(m.group(1)) * 1000),
                        Math.round(Double.parseDouble(m.group(2)) * 1000), m.group(3));
            }
        }
        return cues;
    }

    private static void add(List<Cue> cues, long start, long duration, String markup) {
        String text = markup.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]+>", "")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&apos;", "'").replace("&amp;", "&").trim();
        if (text.isEmpty()) return;
        cues.add(new Cue(start, start + Math.max(duration, 1), text));
    }

    static String srt(List<Cue> cues) {
        StringBuilder out = new StringBuilder();
        int n = 1;
        for (Cue c : cues) {
            out.append(n++).append('\n').append(time(c.start, ',')).append(" --> ").append(time(c.end, ','))
                    .append('\n').append(c.text).append("\n\n");
        }
        return out.toString();
    }

    static String vtt(List<Cue> cues) {
        StringBuilder out = new StringBuilder("WEBVTT\n\n");
        for (Cue c : cues) {
            out.append(time(c.start, '.')).append(" --> ").append(time(c.end, '.')).append('\n')
                    .append(c.text.replace("-->", "->")).append("\n\n");
        }
        return out.toString();
    }

    private static String time(long ms, char separator) {
        return String.format(Locale.ROOT, "%02d:%02d:%02d%c%03d",
                ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, separator, ms % 1000);
    }

    // endregion
}
