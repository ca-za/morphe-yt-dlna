package app.ytdlna.extension;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * How the phone introduces itself to renderers, with the UPnP control point headers
 * (UDA 2.0): the device name as the user set it, a stable id per installation and the app in
 * the user agent. A renderer can show "Playing from <device> · YouTube Music".
 */
final class ControlPoint {
    private static final String PREFS = "ytdlna";
    private static final String PREF_UUID = "control_point_uuid";

    private static volatile String name = Build.MODEL;
    private static volatile String uuid = "";
    private static volatile String userAgent = "Android/" + Build.VERSION.RELEASE + " UPnP/1.1 YtDlna/1.0";
    private static boolean initialized;

    private ControlPoint() {
    }

    static synchronized void init(Context context) {
        if (initialized || context == null) return;
        initialized = true;
        Context app = context.getApplicationContext();
        name = deviceName(app);
        uuid = installationId(app);
        userAgent = "Android/" + Build.VERSION.RELEASE + " UPnP/1.1 " + appToken(app) + " YtDlna/1.0";
        Log.d("Control point \"" + name + "\" (" + uuid + "), " + userAgent);
    }

    static String name() {
        return name;
    }

    static String uuid() {
        return uuid;
    }

    static String userAgent() {
        return userAgent;
    }

    /** Headers for every request to a renderer. */
    static Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", userAgent);
        headers.put("CPFN.UPNP.ORG", name);
        if (!uuid.isEmpty()) headers.put("CPUUID.UPNP.ORG", uuid);
        return headers;
    }

    /** The name from Settings > About phone, as Bluetooth and hotspot show it. */
    private static String deviceName(Context context) {
        String[][] sources = {{"global", "device_name"}, {"secure", "bluetooth_name"}};
        for (String[] source : sources) {
            try {
                String value = source[0].equals("global")
                        ? Settings.Global.getString(context.getContentResolver(), source[1])
                        : Settings.Secure.getString(context.getContentResolver(), source[1]);
                if (value != null && !value.trim().isEmpty()) return clean(value);
            } catch (Exception ignored) {
                // Not readable on this device; try the next source.
            }
        }
        return clean(Build.MODEL);
    }

    /**
     * HTTP headers carry ASCII only: other characters are sent percent-encoded as UTF-8
     * ("Living Room Phone ü" -> "Living Room Phone %C3%BC"), like '%' itself. Plain names stay readable.
     */
    private static String clean(String value) {
        String text = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        StringBuilder out = new StringBuilder();
        for (byte b : text.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c >= 0x20 && c < 0x7f && c != '%') out.append((char) c);
            else out.append('%').append(String.format(java.util.Locale.ROOT, "%02X", c));
        }
        return out.toString();
    }

    private static String installationId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = prefs.getString(PREF_UUID, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(PREF_UUID, id).apply();
        }
        return id;
    }

    /** "YouTubeMusic/9.42.52" or "YouTube/21.42.241": product token of the app. */
    private static String appToken(Context context) {
        String product = context.getPackageName().contains("music") ? "YouTubeMusic" : "YouTube";
        try {
            String version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            if (version != null && !version.isEmpty()) return product + "/" + version.replace(' ', '-');
        } catch (Exception ignored) {
            // No version: the product alone.
        }
        return product;
    }
}
