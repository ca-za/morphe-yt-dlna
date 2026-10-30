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
        /** The lines, timed on the video (see {@link #toVideoMs}). */
        final List<Line> lines;
        /** Morphe's lyrics object: a new one means new lyrics. */
        final Object identity;
        /** How the song's times were moved onto the video: changes when Morphe's mapping does. */
        final String mapping;

        Snapshot(String videoId, String state, String provider, boolean synced, List<Line> lines, Object identity,
                 String mapping) {
            this.videoId = videoId;
            this.state = state;
            this.provider = provider;
            this.synced = synced;
            this.lines = lines;
            this.identity = identity;
            this.mapping = mapping;
        }

        /** Morphe is done looking for this song (found or not). */
        boolean settled() {
            return "LOADED".equals(state) || "NOT_FOUND".equals(state) || "ERROR".equals(state);
        }

        boolean hasLines() {
            return "LOADED".equals(state) && !lines.isEmpty();
        }
    }

    private static final String SETTINGS = "app.morphe.extension.music.settings.Settings";

    private static Method getInstance, toVideoTime, temporaryOffset;
    private static Field stateField, lyricsField, videoField;
    /** Morphe's lyrics offset setting (an IntegerSetting), null if it has none. */
    private static Object offsetSetting;
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
            // Optional: how Morphe times the lyrics on the video (newer Morphe versions).
            toVideoTime = optionalMethod(manager, "toVideoTimeMs", long.class);
            temporaryOffset = optionalMethod(manager, "getTemporaryOffsetMs");
            try {
                offsetSetting = field(Class.forName(SETTINGS), "LYRICS_OFFSET_MS").get(null);
            } catch (Throwable ignored) {
                offsetSetting = null;
            }
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

    private static Method optionalMethod(Class<?> c, String name, Class<?>... params) {
        try {
            Method m = c.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Morphe's lyrics run on the song's time. For a music video (with an intro, skits or an outro
     * that the song doesn't have) Morphe's lyrics view maps the video's position onto the song with
     * the video's SponsorBlock segments, minus the user's lyrics offsets. This is the reverse: the
     * video position at which Morphe shows a line that starts at {@code songMs}, so the renderer
     * shows it at the same moment as the phone. Unchanged if Morphe has no such mapping.
     */
    private static long toVideoMs(Object manager, long songMs) {
        if (songMs < 0) return songMs;
        long ms = songMs + offsetMs(manager);
        if (toVideoTime != null) {
            try {
                ms = ((Number) toVideoTime.invoke(manager, ms)).longValue();
            } catch (Throwable ignored) {
            }
        }
        return Math.max(0, ms);
    }

    /** Morphe's lyrics offsets: the setting and the one set by swiping the lyrics. */
    private static long offsetMs(Object manager) {
        long offset = 0;
        try {
            if (offsetSetting != null) offset += ((Number) call(offsetSetting, "get")).longValue();
        } catch (Throwable ignored) {
        }
        try {
            if (temporaryOffset != null) offset += ((Number) temporaryOffset.invoke(manager)).longValue();
        } catch (Throwable ignored) {
        }
        return offset;
    }

    /** Timed lyrics still on the song's time that Morphe may yet move onto the video. */
    static boolean mayMove(Snapshot s) {
        return toVideoTime != null && s.synced && s.hasLines() && s.mapping.isEmpty();
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
                return new Snapshot(videoId, stateName, "", false, Collections.emptyList(), lyrics, "");
            }
            boolean synced = Boolean.TRUE.equals(call(lyrics, "synced"));
            String provider = String.valueOf(call(lyrics, "providerName"));
            List<Line> lines = new ArrayList<>();
            Object list = call(lyrics, "lines");
            if (list instanceof List) {
                for (Object l : (List<?>) list) {
                    long start = toVideoMs(manager, ((Number) call(l, "startTimeMs")).longValue());
                    long end = toVideoMs(manager, ((Number) call(l, "endTimeMs")).longValue());
                    Line line = new Line(start, end, String.valueOf(call(l, "text")).trim());
                    Object words = call(l, "words");
                    if (words instanceof List) {
                        for (Object w : (List<?>) words) {
                            line.wordTimes.add(new long[]{
                                    toVideoMs(manager, ((Number) call(w, "startMs")).longValue()),
                                    toVideoMs(manager, ((Number) call(w, "endMs")).longValue())});
                            line.words.add(String.valueOf(call(w, "text")));
                        }
                    }
                    lines.add(line);
                }
            }
            return new Snapshot(videoId, stateName, provider, synced, lines, lyrics, mapping(manager, synced, list, lines));
        } catch (Throwable t) {
            Log.d("Reading Morphe's lyrics failed: " + t);
            return null;
        }
    }

    /** The shift of each line, if the lyrics moved on the video (for the log and change checks). */
    private static String mapping(Object manager, boolean synced, Object raw, List<Line> lines) throws Exception {
        if (!synced || !(raw instanceof List) || lines.isEmpty()) return "";
        List<?> songLines = (List<?>) raw;
        StringBuilder out = new StringBuilder();
        long last = 0;
        for (int i = 0; i < lines.size() && i < songLines.size(); i++) {
            long song = ((Number) call(songLines.get(i), "startTimeMs")).longValue();
            if (song < 0) continue;
            long shift = lines.get(i).start - song;
            if (shift != last) out.append(out.length() == 0 ? "" : ", ").append("+").append(shift / 1000.0)
                    .append(" s from ").append(song / 1000.0).append(" s");
            last = shift;
        }
        return out.toString();
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
