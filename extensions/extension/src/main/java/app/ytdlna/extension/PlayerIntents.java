package app.ytdlna.extension;

import android.os.SystemClock;

import java.util.ArrayDeque;

/**
 * Play, pause and seek commands given to the app's player (its MedialibPlayer), with where they
 * came from: the user (player UI, media session), a Morphe patch (SponsorBlock), our own sync,
 * or the player itself (buffering, ads, SABR). Hooked by the patch.
 * <p>
 * Prototype: while casting, every command is logged with what the cast would do with it
 * ("forward" to the renderer or "ignore"). The cast itself still decides from the phone's
 * playback state; the logs show where both agree.
 */
public final class PlayerIntents {
    /** How long a command we sent through the media session is expected to arrive. */
    private static final long OWN_WINDOW_MS = 3000;
    private static final int MAX_RECENT = 40;

    /** Commands we sent to the phone and not yet seen arrive, by kind: when sent. */
    private static final java.util.Map<String, ArrayDeque<Long>> own = new java.util.HashMap<>();
    private static final ArrayDeque<org.json.JSONObject> recent = new ArrayDeque<>();
    private static volatile boolean seenSeek, seenPlay, seenPause;

    private PlayerIntents() {
    }

    // region Hooks (called by the patched MedialibPlayer)

    public static void onSeek(Object player, long positionMs, Object source) {
        seenSeek = true;
        record("seek", player, positionMs, source == null ? "?" : sourceName(source), -1);
    }

    public static void onPlay(Object player) {
        seenPlay = true;
        record("play", player, -1, null, -1);
    }

    public static void onPause(Object player, int reason) {
        seenPause = true;
        record("pause", player, -1, null, reason);
    }

    // endregion

    /** A command our cast sends to the phone's player through its media session. */
    static void expectOwn(String kind) {
        synchronized (own) {
            ArrayDeque<Long> list = own.get(kind);
            if (list == null) own.put(kind, list = new ArrayDeque<>());
            list.addLast(SystemClock.elapsedRealtime());
        }
    }

    /** Which hooks fired so far (a missing one may be a missing fingerprint, or just not used yet). */
    static String hooksSeen() {
        return "seek=" + seenSeek + " play=" + seenPlay + " pause=" + seenPause;
    }

    static org.json.JSONArray recentJson() {
        synchronized (recent) {
            return new org.json.JSONArray(new java.util.ArrayList<>(recent));
        }
    }

    private static void record(String kind, Object player, long positionMs, String source, int reason) {
        try {
            if (CastSession.active() == null) return;
            String caller = caller(kind);
            String side = MorpheState.sidePlayer();
            String decision = decide(kind, source, reason, caller, side);
            StringBuilder line = new StringBuilder("Intent ").append(kind);
            if (positionMs >= 0) line.append(' ').append(positionMs).append(" ms");
            if (source != null) line.append(" source=").append(source);
            if (reason >= 0) line.append(" reason=").append(reason);
            line.append(" by ").append(caller);
            if (side != null) line.append(" (").append(side).append(" in front)");
            line.append(" player@").append(Integer.toHexString(System.identityHashCode(player)));
            line.append(" -> ").append(decision);
            Log.d(line.toString());
            if (caller.startsWith("app") || caller.startsWith("morphe")) Log.d("  stack " + appFrames());
            org.json.JSONObject entry = new org.json.JSONObject()
                    .put("kind", kind).put("position", positionMs).put("source", source == null ? "" : source)
                    .put("reason", reason).put("caller", caller).put("decision", decision)
                    .put("at", SystemClock.elapsedRealtime());
            synchronized (recent) {
                recent.addLast(entry);
                while (recent.size() > MAX_RECENT) recent.removeFirst();
            }
        } catch (Throwable t) {
            Log.e("Intent hook failed", t);
        }
    }

    /**
     * What the cast should do with the command: "forward" it to the renderer, or "ignore" it
     * (with why). "unknown" marks cases to learn from.
     */
    /** The reason the app's generic pause() passes. */
    private static final int USER_PAUSE_REASON = 19;

    private static String decide(String kind, String source, int reason, String caller, String side) {
        if (caller.startsWith("ours")) return "ignore (ours)";
        if (side != null) return "ignore (" + side + ")";
        if ("seek".equals(kind)) {
            if (source != null && isInternalSeek(source)) return "ignore (internal seek)";
            if (source != null && !"UNKNOWN".equals(source)) return "forward (user)";
            // Patches seek without a source, and so does YouTube's media session.
            if (caller.startsWith("morphe")) return "forward (" + caller + ")";
            if (caller.startsWith("session") || caller.startsWith("ui")) return "forward (user)";
            return "unknown";
        }
        if ("pause".equals(kind)) {
            // The app's generic pause() (player button, media session, voice, ...) gives reason 19;
            // other reasons are the player's own (loading a title, audio focus, ads, timers).
            if (reason != USER_PAUSE_REASON) return "ignore (pause reason " + reason + ")";
            if (caller.startsWith("morphe")) return "ignore (" + caller + ")";
            return "forward (user)";
        }
        // Play has no reason: the user's come through the media session or a view.
        if (caller.startsWith("session") || caller.startsWith("ui")) return "forward (user)";
        if (caller.startsWith("morphe")) return "ignore (" + caller + ")";
        return "ignore (app)";
    }

    /** Seeks the player makes itself: buffering, ads, live edge, SABR, start of playback. */
    static boolean isInternalSeek(String source) {
        String[] internal = {"SABR_", "SSDAI", "SSAP", "INTERNAL", "START_PLAYBACK", "CLEAR_BUFFER",
                "RESUME_AFTER_ADS", "SKIP_AD", "MIDROLLS", "NON_USER", "AUTOMATIC_", "LOAD_VIDEO_FRAME",
                "ONESIE", "PEG_TO_LIVE", "SEEK_TO_HEAD", "LOOPING_RANGE", "IOS_", "TVHTML5_", "H5_",
                "RESUME_CLIP_PREVIOUS_POSITION", "FINE_SCRUBBER_CANCELLED"};
        for (String part : internal) if (source.contains(part)) return true;
        return false;
    }

    private static String sourceName(Object source) {
        String name = source instanceof Enum ? ((Enum<?>) source).name() : String.valueOf(source);
        return name.startsWith("SEEK_SOURCE_") ? name.substring("SEEK_SOURCE_".length()) : name;
    }

    /** Who called: our cast, a Morphe patch, the media session, the player UI, or the app. */
    private static String caller(String kind) {
        String morphe = null, ours = null;
        boolean session = false, ui = false;
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String c = frame.getClassName();
            if (c.startsWith("app.ytdlna.extension.") && !c.endsWith(".PlayerIntents")) {
                if (ours == null) ours = c.substring(c.lastIndexOf('.') + 1) + "." + frame.getMethodName();
            } else if (c.startsWith("app.morphe.extension.")) {
                // The patch behind the call, not the shared helpers it calls through.
                String name = c.substring(c.lastIndexOf('.') + 1);
                if (morphe == null && !name.startsWith("VideoInformation") && !name.startsWith("Utils")) morphe = name;
            } else if (c.startsWith("android.media.session.MediaSession")) {
                session = true;
            } else if ((c.equals("android.view.View") && frame.getMethodName().equals("performClick"))
                    || c.equals("android.view.GestureDetector")) {
                ui = true;
            }
        }
        if (ours != null) return "ours:" + ours;
        if (morphe != null) return "morphe:" + morphe;
        if (session) return takeOwn(kind) ? "ours:session" : "session";
        if (ui) return "ui";
        return "app";
    }

    /** Consumes a command we sent of this kind, if one is due. */
    private static boolean takeOwn(String kind) {
        long now = SystemClock.elapsedRealtime();
        synchronized (own) {
            ArrayDeque<Long> list = own.get(kind);
            if (list == null) return false;
            while (!list.isEmpty() && now - list.peekFirst() > OWN_WINDOW_MS) list.removeFirst();
            if (list.isEmpty()) return false;
            list.removeFirst();
            return true;
        }
    }

    /** The app's (obfuscated) frames above the hook, to learn who sends a command. */
    private static String appFrames() {
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String c = frame.getClassName();
            if (c.startsWith("java.") || c.startsWith("dalvik.") || c.startsWith("app.ytdlna.")) continue;
            if (n++ >= 8) break;
            out.append(c).append('.').append(frame.getMethodName()).append(' ');
        }
        return out.toString();
    }
}
