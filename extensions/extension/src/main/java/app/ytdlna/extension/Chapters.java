package app.ytdlna.extension;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A YouTube video's chapters, for the renderer's extras: read from Morphe's chapter hook (part of
 * its seekbar patches), which holds the markers of the video the player plays. Read only, through
 * reflection, like {@link SponsorBlock}; empty without that hook or without chapters.
 */
final class Chapters {
    private static final String HOOK = "app.morphe.extension.youtube.patches.ChaptersHookPatch";

    static final class Chapter {
        final String title;
        final long start, end;

        Chapter(String title, long start, long end) {
            this.title = title;
            this.start = start;
            this.end = end;
        }
    }

    private static Method markers, startOf, endOf, titleOf;
    private static boolean resolved;
    /** Chapters per video, as last read (the hook only holds the playing video's). */
    private static final Map<String, List<Chapter>> known = new ConcurrentHashMap<>();
    /** The marker array last read: a new one means new chapters. */
    private static volatile Object lastRaw;

    private Chapters() {
    }

    private static synchronized boolean resolve() {
        if (resolved) return markers != null;
        resolved = true;
        try {
            Class<?> hook = Class.forName(HOOK);
            Class<?> marker = Class.forName(HOOK + "$TimelineMarker");
            markers = hook.getMethod("getChapterMarkers");
            startOf = marker.getMethod("patch_getStartMillis");
            endOf = marker.getMethod("patch_getEndMillis");
            titleOf = marker.getMethod("patch_getTitle");
            return true;
        } catch (Throwable t) {
            markers = null;
            return false;
        }
    }

    /**
     * Reads the playing video's chapters if they are {@code videoId}'s and changed since the last
     * read. @return whether {@code videoId} got new chapters.
     */
    static boolean refresh(String videoId) {
        if (videoId == null || Track.musicApp || !resolve()) return false;
        if (!videoId.equals(MorpheState.videoId())) return false;
        try {
            Object raw = markers.invoke(null);
            if (raw == lastRaw) return false;
            lastRaw = raw;
            List<Chapter> list = new ArrayList<>();
            if (raw instanceof Object[]) {
                for (Object m : (Object[]) raw) {
                    Object title = titleOf.invoke(m);
                    list.add(new Chapter(title == null ? "" : title.toString().trim(),
                            ((Number) startOf.invoke(m)).longValue(), ((Number) endOf.invoke(m)).longValue()));
                }
            }
            known.put(videoId, Collections.unmodifiableList(list));
            Log.d("Chapters for " + videoId + ": " + list.size());
            return true;
        } catch (Throwable t) {
            Log.d("Reading Morphe's chapters failed: " + t);
            return false;
        }
    }

    static List<Chapter> of(String videoId) {
        List<Chapter> list = videoId == null ? null : known.get(videoId);
        return list == null ? Collections.emptyList() : list;
    }

    /** For the extras: [{"title", "start", "end"}] in seconds. */
    static JSONArray json(String videoId) throws JSONException {
        JSONArray out = new JSONArray();
        for (Chapter c : of(videoId)) {
            JSONObject chapter = new JSONObject().put("title", c.title).put("start", c.start / 1000.0);
            if (c.end > c.start) chapter.put("end", c.end / 1000.0);
            out.put(chapter);
        }
        return out;
    }
}
