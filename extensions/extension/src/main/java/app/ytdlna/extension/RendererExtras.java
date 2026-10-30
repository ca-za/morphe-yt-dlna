package app.ytdlna.extension;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * Extra data about the cast title for renderers that ask for it: SponsorBlock segments (with
 * the user's setting per category), chapters, captions and lyrics. See docs/renderer-extras.md.
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
    /** The renderer's service to take over skipping (docs/renderer-extras.md, section 4). */
    static final String SERVICE = "urn:ytdlna-org:service:CastExtras:";

    /** The renderer confirmed it skips segments itself: the phone no longer sends Morphe's skips. */
    static volatile boolean rendererSkips;

    /**
     * Asks the renderer to skip segments itself (it forgets that when its app restarts, so this
     * goes with every title). Only its confirmation hands skipping over; without it the phone
     * keeps skipping, so a failure never leaves the segments unskipped. Not on the main thread.
     */
    static boolean takeSkipping(DlnaDevice device) {
        boolean skips = false;
        if (wanted(device) && device.castExtrasUrl != null) {
            try {
                String answer = DlnaController.soap(device.castExtrasUrl, device.castExtrasType, "SetSkipping",
                        "<Enabled>1</Enabled>");
                skips = answer.matches("(?s).*<Enabled>\\s*(1|true)\\s*</Enabled>.*");
            } catch (Exception e) {
                Log.d("SetSkipping failed, the phone skips: " + e.getMessage());
            }
        }
        if (skips != rendererSkips) Log.i(skips ? "The renderer skips segments itself" : "The phone skips segments");
        rendererSkips = skips;
        return skips;
    }

    static boolean wanted(DlnaDevice device) {
        return Features.rendererExtras() && (forceAnnounce || device.lists(MIME));
    }

    /** Titles whose document counts as complete with what Morphe had in time. */
    private static final java.util.Set<String> timedOutVideos = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<>());

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
                    .put("skipBy", rendererSkips ? "renderer" : "phone");
            NativeProxy.nativePutText(path(videoId), MIME, doc.toString());
        } catch (JSONException e) {
            Log.e("Building the pending extras for " + videoId + " failed", e);
        }
    }

    /** The proxy stopped and forgot all documents. */
    static void reset() {
        published.clear();
        timedOutVideos.clear();
        rendererSkips = false;
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
        // Timed lyrics not (yet) moved onto the video may still be, when Morphe's segments for
        // that arrive: renderers keep following the document until then.
        if (lyricsDone && lyrics != null && MusicLyrics.mayMove(lyrics)) lyricsDone = false;
        if (timedOut) timedOutVideos.add(videoId);
        boolean complete = (known && lyricsDone) || timedOutVideos.contains(videoId);
        try {
            JSONObject doc = new JSONObject()
                    .put("version", VERSION)
                    .put("videoId", videoId)
                    .put("complete", complete)
                    .put("segments", segmentsJson(segments))
                    .put("chapters", Chapters.json(videoId))
                    .put("captions", Captions.extrasJson(videoId, baseUrl, Captions.selected(videoId)))
                    .put("lyrics", lyrics != null && lyrics.hasLines() ? MusicLyrics.json(lyrics) : JSONObject.NULL)
                    // Who skips segments: the renderer once it confirmed, else the phone (its seek).
                    .put("skipBy", rendererSkips ? "renderer" : "phone");
            if (durationMs > 0) doc.put("duration", durationMs / 1000.0);
            JSONObject audio = audioJson(videoId);
            if (audio != null) doc.put("audio", audio);
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

    /**
     * The loudness of the audio the renderer gets, so it can play titles at an even level like
     * the app does: YouTube turns a title down by loudnessDb when that is positive (never up).
     */
    private static JSONObject audioJson(String videoId) throws JSONException {
        StreamInfo info = StreamCapture.get(videoId);
        StreamInfo.Format audio = info == null ? null : StreamProxy.castAudio(info);
        if ((audio == null || Float.isNaN(audio.loudnessDb)) && info != null) {
            // Formats without a direct address carry the loudness too (all about the same).
            for (StreamInfo.Format f : info.formats) {
                if (f.isAudioOnly() && info.isPreferredAudio(f) && !Float.isNaN(f.loudnessDb)) {
                    audio = f;
                    break;
                }
            }
        }
        if (audio == null || Float.isNaN(audio.loudnessDb)) return null;
        JSONObject out = new JSONObject().put("loudnessDb", round(audio.loudnessDb));
        if (!Float.isNaN(audio.perceptualLoudnessDb)) out.put("perceptualLoudnessDb", round(audio.perceptualLoudnessDb));
        return out.put("targetLufs", -14);
    }

    private static double round(float v) {
        return Math.round(v * 100) / 100.0;
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
