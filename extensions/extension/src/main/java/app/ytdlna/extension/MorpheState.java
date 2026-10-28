package app.ytdlna.extension;

import java.lang.reflect.Method;

/**
 * Reads the player state Morphe's own patches keep: which player is on screen (main player,
 * feed, Shorts), its state and the video it plays. Lets the cast tell the user's video apart
 * from what other players load (feed previews, Shorts, ads).
 * <p>
 * Read only, through reflection: add-on bundles can not reference Morphe code at patch time, and
 * its classes may change between Morphe versions. Every value is null when it is not available.
 */
final class MorpheState {
    private static final String YT = "app.morphe.extension.youtube.";
    private static final String MUSIC = "app.morphe.extension.music.";

    private static final Method playerType = method(YT + "shared.PlayerType", "getCurrent");
    private static final Method videoState = method(YT + "shared.VideoState", "getCurrent");
    private static final Method ytVideoId = method(YT + "patches.VideoInformation", "getVideoId");
    private static final Method ytIsShort = method(YT + "patches.VideoInformation", "lastVideoIdIsShort");
    private static final Method musicVideoId = method(MUSIC + "shared.VideoInformation", "getVideoId");
    private static final Method ytVideoTime = method(YT + "patches.VideoInformation", "getVideoTime");
    private static final Method musicVideoTime = method(MUSIC + "shared.VideoInformation", "getVideoTime");

    private MorpheState() {
    }

    /** YouTube's player on screen (WATCH_WHILE_MAXIMIZED, INLINE_MINIMAL for the feed, ...). */
    static String playerType() {
        Object value = call(playerType);
        return value == null ? null : value.toString();
    }

    /** YouTube's playback state (NEW, PLAYING, PAUSED, ENDED, ...). */
    static String videoState() {
        Object value = call(videoState);
        return value == null ? null : value.toString();
    }

    /** The video of the main player (YouTube) or the playing track (Music). */
    static String videoId() {
        Object value = call(ytVideoId != null ? ytVideoId : musicVideoId);
        return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
    }

    /** The main player's position in ms, straight from the player; -1 if not known. */
    static long videoTime() {
        Object value = call(ytVideoTime != null ? ytVideoTime : musicVideoTime);
        return value instanceof Long ? (Long) value : -1;
    }

    /** Whether the last video the main player loaded is a Short. Null if not known. */
    static Boolean isShort() {
        Object value = call(ytIsShort);
        return value instanceof Boolean ? (Boolean) value : null;
    }

    /**
     * The player in front if it is not the main player: "Shorts" or "feed" (an inline preview).
     * Null for the main player, and when Morphe's state is not available (YouTube Music).
     */
    static String sidePlayer() {
        String type = playerType();
        if (type == null) return null;
        if ("INLINE_MINIMAL".equals(type)) return "feed";
        // The Short flag alone also stays set for a moment after one is left.
        if (Boolean.TRUE.equals(isShort()) && ("NONE".equals(type) || "HIDDEN".equals(type))) return "Shorts";
        return null;
    }

    /** One line for the logs. */
    static String summary() {
        return "morphe[player=" + playerType() + " state=" + videoState() + " video=" + videoId()
                + (Boolean.TRUE.equals(isShort()) ? " short" : "") + "]";
    }

    private static Method method(String className, String name) {
        try {
            Method method = Class.forName(className).getMethod(name);
            method.setAccessible(true);
            return method;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object call(Method method) {
        if (method == null) return null;
        try {
            return method.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
