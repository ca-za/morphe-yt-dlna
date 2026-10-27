package app.ytdlna.extension;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** OpenHome Product, Playlist and Time actions. */
final class OpenHome {
    private OpenHome() {
    }

    /** Switches the renderer to its playlist source. */
    static void selectPlaylistSource(DlnaDevice device) throws IOException {
        if (device.productControlUrl == null) return;
        DlnaController.soap(device.productControlUrl, device.productType, "SetSourceIndexByName",
                "<Value>Playlist</Value>");
    }

    static void deleteAll(DlnaDevice device) throws IOException {
        playlist(device, "DeleteAll", "");
    }

    /** Inserts a track after {@code afterId} (0 = at the start). Returns its id. */
    static int insert(DlnaDevice device, int afterId, String uri, String metadata) throws IOException {
        String response = playlist(device, "Insert", "<AfterId>" + afterId + "</AfterId>"
                + "<Uri>" + DlnaController.escape(uri) + "</Uri>"
                + "<Metadata>" + DlnaController.escape(metadata) + "</Metadata>");
        Matcher m = Pattern.compile("<NewId>(\\d+)</NewId>").matcher(response);
        if (!m.find()) throw new IOException("Insert returned no id");
        return Integer.parseInt(m.group(1));
    }

    static void seekId(DlnaDevice device, int id) throws IOException {
        playlist(device, "SeekId", "<Value>" + id + "</Value>");
    }

    static void play(DlnaDevice device) throws IOException {
        playlist(device, "Play", "");
    }

    static void pause(DlnaDevice device) throws IOException {
        playlist(device, "Pause", "");
    }

    static void stop(DlnaDevice device) throws IOException {
        playlist(device, "Stop", "");
    }

    static void seekSeconds(DlnaDevice device, long positionMs) throws IOException {
        playlist(device, "SeekSecondAbsolute", "<Value>" + Math.max(0, positionMs / 1000) + "</Value>");
    }

    /** Current position in ms from the Time service, or -1 if not available. */
    static long position(DlnaDevice device) throws IOException {
        if (device.timeControlUrl == null) return -1;
        String response = DlnaController.soap(device.timeControlUrl, device.timeType, "Time", "");
        Matcher m = Pattern.compile("<Seconds>(\\d+)</Seconds>").matcher(response);
        return m.find() ? Long.parseLong(m.group(1)) * 1000 : -1;
    }

    /** Maps an OpenHome transport state to the AVTransport names. */
    static String toAvTransportState(String state) {
        switch (state) {
            case "Playing":
                return "PLAYING";
            case "Paused":
                return "PAUSED_PLAYBACK";
            case "Stopped":
                return "STOPPED";
            case "Buffering":
                return "TRANSITIONING";
            default:
                return state;
        }
    }

    private static String playlist(DlnaDevice device, String action, String arguments) throws IOException {
        return DlnaController.soap(device.playlistControlUrl, device.playlistType, action, arguments);
    }
}
