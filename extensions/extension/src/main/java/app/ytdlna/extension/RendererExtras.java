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

    /** For the tests: treat every renderer as one that asks. */
    static volatile boolean forceAnnounce;

    private RendererExtras() {
    }

    /** Whether the renderer asked for the extras (explicitly: a wildcard sink does not count). */
    static boolean wanted(DlnaDevice device) {
        if (forceAnnounce) return true;
        String sink = device.sinkProtocols;
        return sink != null && sink.toLowerCase(java.util.Locale.ROOT).contains(":" + MIME + ":");
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
        boolean complete = known || timedOut;
        try {
            JSONObject doc = new JSONObject()
                    .put("version", VERSION)
                    .put("videoId", videoId)
                    .put("complete", complete)
                    .put("segments", segmentsJson(segments))
                    .put("chapters", new JSONArray())
                    .put("captions", new JSONArray())
                    // Who skips segments: so far always the phone (its seek reaches the renderer).
                    .put("skipBy", "phone");
            if (durationMs > 0) doc.put("duration", durationMs / 1000.0);
            NativeProxy.nativePutText(path(videoId), MIME, doc.toString());
            Log.d("Extras for " + videoId + ": " + segments.size() + " segments" + (complete ? ", complete" : ""));
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
