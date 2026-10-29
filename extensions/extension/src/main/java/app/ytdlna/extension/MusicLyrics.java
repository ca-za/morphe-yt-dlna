package app.ytdlna.extension;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The lyrics Morphe's lyrics feature found for the playing song (YouTube Music), for the
 * renderer: in the extras document, and as a caption track when they are timed.
 * <p>
 * Read only, through reflection (like {@link SponsorBlock}): null when the feature is not
 * patched in, is off, or has nothing for the song.
 */
final class MusicLyrics {
    private static final String MANAGER = "app.morphe.extension.music.patches.lyrics.LyricsManager";
    /** Unsynced lines last on screen this long in the caption track. */
    private static final long LAST_LINE_MS = 5000;

    static final class Line {
        final long start, end;
        final String text;
        /** Word timings {start, end} with their texts, empty if the provider has none. */
        final List<long[]> wordTimes = new ArrayList<>();
        final List<String> words = new ArrayList<>();

        Line(long start, long end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    /** Morphe's lyrics state for one video. */
    static final class Snapshot {
        final String videoId;
        /** IDLE, LOADING, LOADED, NOT_FOUND, ERROR. */
        final String state;
        final String provider;
        final boolean synced;
        final List<Line> lines;
        /** Morphe's lyrics object: a new one means new lyrics. */
        final Object identity;

        Snapshot(String videoId, String state, String provider, boolean synced, List<Line> lines, Object identity) {
            this.videoId = videoId;
            this.state = state;
            this.provider = provider;
            this.synced = synced;
            this.lines = lines;
            this.identity = identity;
        }

        /** Morphe is done looking for this song (found or not). */
        boolean settled() {
            return "LOADED".equals(state) || "NOT_FOUND".equals(state) || "ERROR".equals(state);
        }

        boolean hasLines() {
            return "LOADED".equals(state) && !lines.isEmpty();
        }
    }

    private static Method getInstance;
    private static Field stateField, lyricsField, videoField;
    private static boolean resolved;

    private MusicLyrics() {
    }

    private static synchronized boolean resolve() {
        if (resolved) return getInstance != null;
        resolved = true;
        try {
            Class<?> manager = Class.forName(MANAGER);
            getInstance = manager.getMethod("getInstance");
            stateField = field(manager, "state");
            lyricsField = field(manager, "currentLyrics");
            videoField = field(manager, "loadedForVideoId");
            return true;
        } catch (Throwable t) {
            getInstance = null;
            return false;
        }
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** Whether Morphe's lyrics feature is in the app. */
    static boolean available() {
        return Features.rendererExtras() && Track.musicApp && resolve();
    }

    /** Morphe's lyrics for {@code videoId}, or null if it has none for that video (yet). */
    static Snapshot read(String videoId) {
        if (videoId == null || !available()) return null;
        try {
            Object manager = getInstance.invoke(null);
            if (manager == null) return null;
            Object state = stateField.get(manager);
            Object lyrics = lyricsField.get(manager);
            Object loadedFor = videoField.get(manager);
            if (!videoId.equals(loadedFor)) return null;
            String stateName = state == null ? "IDLE" : state.toString();
            if (lyrics == null || !"LOADED".equals(stateName)) {
                return new Snapshot(videoId, stateName, "", false, Collections.emptyList(), lyrics);
            }
            boolean synced = Boolean.TRUE.equals(call(lyrics, "synced"));
            String provider = String.valueOf(call(lyrics, "providerName"));
            List<Line> lines = new ArrayList<>();
            Object list = call(lyrics, "lines");
            if (list instanceof List) {
                for (Object l : (List<?>) list) {
                    long start = ((Number) call(l, "startTimeMs")).longValue();
                    long end = ((Number) call(l, "endTimeMs")).longValue();
                    Line line = new Line(start, end, String.valueOf(call(l, "text")).trim());
                    Object words = call(l, "words");
                    if (words instanceof List) {
                        for (Object w : (List<?>) words) {
                            line.wordTimes.add(new long[]{((Number) call(w, "startMs")).longValue(),
                                    ((Number) call(w, "endMs")).longValue()});
                            line.words.add(String.valueOf(call(w, "text")));
                        }
                    }
                    lines.add(line);
                }
            }
            return new Snapshot(videoId, stateName, provider, synced, lines, lyrics);
        } catch (Throwable t) {
            Log.d("Reading Morphe's lyrics failed: " + t);
            return null;
        }
    }

    private static Object call(Object target, String name) throws Exception {
        return target.getClass().getMethod(name).invoke(target);
    }

    /** The lyrics for the extras document. */
    static JSONObject json(Snapshot s) throws JSONException {
        JSONArray lines = new JSONArray();
        for (int i = 0; i < s.lines.size(); i++) {
            Line l = s.lines.get(i);
            JSONObject line = new JSONObject().put("text", l.text);
            if (s.synced && l.start >= 0) {
                line.put("start", l.start / 1000.0).put("end", end(s.lines, i) / 1000.0);
                if (!l.words.isEmpty() && l.wordTimes.get(0)[0] >= 0) {
                    JSONArray words = new JSONArray();
                    for (int w = 0; w < l.words.size(); w++) {
                        words.put(new JSONObject().put("text", l.words.get(w))
                                .put("start", l.wordTimes.get(w)[0] / 1000.0)
                                .put("end", l.wordTimes.get(w)[1] / 1000.0));
                    }
                    line.put("words", words);
                }
            }
            lines.put(line);
        }
        return new JSONObject().put("provider", s.provider).put("synced", s.synced).put("lines", lines);
    }

    /** Timed lyrics as caption cues; empty if they are not timed. */
    static List<Captions.Cue> cues(Snapshot s) {
        List<Captions.Cue> cues = new ArrayList<>();
        if (!s.synced) return cues;
        for (int i = 0; i < s.lines.size(); i++) {
            Line l = s.lines.get(i);
            if (l.start < 0 || l.text.isEmpty()) continue;
            cues.add(new Captions.Cue(l.start, end(s.lines, i), l.text));
        }
        return cues;
    }

    /** A line's end: its own, else the next line's start (bounded), else a few seconds. */
    private static long end(List<Line> lines, int i) {
        Line l = lines.get(i);
        if (l.end > l.start) return l.end;
        if (i + 1 < lines.size() && lines.get(i + 1).start > l.start) {
            return Math.min(lines.get(i + 1).start, l.start + 10_000);
        }
        return l.start + LAST_LINE_MS;
    }
}
