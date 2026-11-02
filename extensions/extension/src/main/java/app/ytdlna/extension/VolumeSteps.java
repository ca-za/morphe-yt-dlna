package app.ytdlna.extension;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renderers whose volume is a receiver's (HDMI-CEC): only presses up, down and mute, no value.
 * The renderer tells it with this service in its description (and then has no volume in
 * RenderingControl or OpenHome); see the renderer's docs/volume-steps.md.
 */
final class VolumeSteps {
    static final String SERVICE = "urn:ytdlna-org:service:VolumeSteps:";
    /** Presses per call; more go out in several calls. */
    static final int MAX_STEPS = 10;

    /** GetTarget per renderer (description URL): "hdmi-cec", or "" if not told. */
    private static final Map<String, String> targets = new ConcurrentHashMap<>();

    private VolumeSteps() {
    }

    static boolean has(DlnaDevice device) {
        return device.volumeStepsUrl != null;
    }

    /** {@code steps} presses up (positive) or down (negative), in calls of at most 10. */
    static void step(DlnaDevice device, int steps) throws IOException {
        int left = steps;
        while (left != 0) {
            int now = Math.max(-MAX_STEPS, Math.min(MAX_STEPS, left));
            call(device, now > 0 ? "Up" : "Down", "<Steps>" + Math.abs(now) + "</Steps>");
            left -= now;
        }
    }

    /** One press on mute: switches it, nobody knows to what. */
    static void toggleMute(DlnaDevice device) throws IOException {
        call(device, "ToggleMute", "");
    }

    /** Where the steps go, for the label: "hdmi-cec" (a receiver) or "". Cached. */
    static String target(DlnaDevice device) {
        String known = targets.get(device.location);
        if (known != null) return known;
        String target = "";
        try {
            Matcher m = Pattern.compile("<Target>(.*?)</Target>").matcher(call(device, "GetTarget", ""));
            if (m.find()) target = m.group(1);
        } catch (IOException e) {
            Log.d("VolumeSteps GetTarget failed: " + e.getMessage());
        }
        targets.put(device.location, target);
        return target;
    }

    /** "Volume on the receiver" or "Volume". */
    static String label(DlnaDevice device) {
        return "hdmi-cec".equals(targets.get(device.location))
                ? DlnaCast.t("Lautstärke am Receiver", "Volume on the receiver")
                : DlnaCast.t("Lautstärke", "Volume");
    }

    /** The service is gone (HTTP 404): the renderer changed how it takes the volume. */
    static boolean gone(Exception e) {
        String message = e.getMessage();
        return message != null && message.contains("HTTP 404");
    }

    private static String call(DlnaDevice device, String action, String arguments) throws IOException {
        String url = device.volumeStepsUrl;
        if (url == null) throw new IOException("HTTP 404: no VolumeSteps");
        return DlnaController.soap(url, device.volumeStepsType, action, arguments);
    }
}
