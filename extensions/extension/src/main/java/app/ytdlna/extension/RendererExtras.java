package app.ytdlna.extension;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * Extra data about the cast title for renderers that ask for it: SponsorBlock segments (with
 * the user's setting per category), later chapters and captions. See docs/renderer-extras.md.
 * <p>
 * A renderer asks by listing {@link #MIME} in its ConnectionManager sink protocols. It then finds
 * a {@code <desc>} block in the title's DIDL-Lite metadata pointing to a JSON document the phone's
 * proxy serves (with an ETag, so it can be polled cheaply until {@code complete}). Renderers that
 * do not ask get the metadata exactly as before.
 */
final class RendererExtras {
    static final String MIME = "application/vnd.ytdlna.extras+json";
    static final String NAMESPACE = "urn:ytdlna-org:metadata-1";
    static final int VERSION = 1;

    /** Address of the phone's proxy as the renderer reaches it ("http://host:port"). */
    static volatile String baseUrl = "";

    /** For the tests: treat every renderer as one that asks. */
    static volatile boolean forceAnnounce;

    private RendererExtras() {
    }

    /** Whether the renderer asked for the extras (explicitly: a wildcard sink does not count). */
    static boolean wanted(DlnaDevice device) {
        return Features.rendererExtras() && (forceAnnounce || device.lists(MIME));
    }

    /** Titles whose real document is published (a placeholder must not replace it). */
    private static final java.util.Set<String> published = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<>());

    /**
     * A queued title's document before the phone plays it: empty and not complete, so the
     * renderer asks again (instead of a 404 until then). The real one replaces it once loaded.
     */
    static void publishPending(String videoId) {
        if (published.contains(videoId)) return;
        try {
            JSONObject doc = new JSONObject()
                    .put("version", VERSION)
                    .put("videoId", videoId)
                    .put("complete", false)
                    .put("segments", new JSONArray())
                    .put("chapters", new JSONArray())
                    .put("captions", new JSONArray())
                    .put("lyrics", JSONObject.NULL)
                    .put("skipBy", "phone");
            NativeProxy.nativePutText(path(videoId), MIME, doc.toString());
        } catch (JSONException e) {
            Log.e("Building the pending extras for " + videoId + " failed", e);
        }
    }

    /** The proxy stopped and forgot all documents. */
    static void reset() {
        published.clear();
    }

    static String path(String videoId) {
        return "/extras/" + videoId + ".json";
    }

    /** The {@code <desc>} block for a title's DIDL-Lite item. */
    static String didlDesc(String videoId, String url) {
        return "<desc id=\"ytdlna\" nameSpace=\"" + NAMESPACE + "\">"
                + "<ytdlna:extras xmlns:ytdlna=\"" + NAMESPACE + "\" version=\"" + VERSION + "\" videoId=\""
                + DlnaController.escape(videoId) + "\" href=\"" + DlnaController.escape(url) + "\"/>"
                + "</desc>";
    }

    /**
     * Serves the document for {@code videoId} (again, with what is known now).
     *
     * @param timedOut Morphe had its time to load the segments: the document is complete either way.
     * @return whether the document is complete.
     */
    static boolean publish(String videoId, long durationMs, boolean timedOut) {
        Object raw = SponsorBlock.raw();
        // Morphe holds the segments of one video at a time, null until it loaded them.
        boolean known = !SponsorBlock.available() || (raw != null && videoId.equals(SponsorBlock.videoId()));
        List<SponsorBlock.Segment> segments = known ? SponsorBlock.segments(raw) : java.util.Collections.emptyList();
        // In YouTube Music, also until Morphe's lyrics search for the song is done.
        MusicLyrics.Snapshot lyrics = MusicLyrics.read(videoId);
        boolean lyricsDone = !MusicLyrics.available() || (lyrics != null && lyrics.settled());
        boolean complete = (known && lyricsDone) || timedOut;
        try {
            JSONObject doc = new JSONObject()
                    .put("version", VERSION)
                    .put("videoId", videoId)
                    .put("complete", complete)
                    .put("segments", segmentsJson(segments))
                    .put("chapters", new JSONArray())
                    .put("captions", Captions.extrasJson(videoId, baseUrl, Captions.selected(videoId)))
                    .put("lyrics", lyrics != null && lyrics.hasLines() ? MusicLyrics.json(lyrics) : JSONObject.NULL)
                    // Who skips segments: so far always the phone (its seek reaches the renderer).
                    .put("skipBy", "phone");
            if (durationMs > 0) doc.put("duration", durationMs / 1000.0);
            NativeProxy.nativePutText(path(videoId), MIME, doc.toString());
            published.add(videoId);
            Log.d("Extras for " + videoId + ": " + segments.size() + " segments"
                    + (lyrics != null && lyrics.hasLines() ? ", " + lyrics.lines.size() + " lyrics lines" : "")
                    + (complete ? ", complete" : ""));
        } catch (JSONException e) {
            Log.e("Building the extras for " + videoId + " failed", e);
        }
        return complete;
    }

    /** Morphe's segments with the renderer's action: skip, skip-once, manual, show, jump. */
    private static JSONArray segmentsJson(List<SponsorBlock.Segment> segments) throws JSONException {
        JSONArray out = new JSONArray();
        for (SponsorBlock.Segment s : segments) {
            String action = action(s);
            if (action == null) continue;
            JSONObject segment = new JSONObject()
                    .put("category", s.category)
                    .put("start", s.start / 1000.0)
                    .put("action", action);
            // The highlight is a point to jump to, not a range.
            if (!"jump".equals(action)) segment.put("end", s.end / 1000.0);
            out.put(segment);
        }
        return out;
    }

    private static String action(SponsorBlock.Segment s) {
        if ("ignore".equals(s.behaviour)) return null;
        if (s.category.startsWith("poi")) return "jump";
        switch (s.behaviour) {
            case "skip":
                return "skip";
            case "skip-once":
                return "skip-once";
            case "manual-skip":
                return "manual";
            default:
                return "show";
        }
    }
}
