package app.ytdlna.extension;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** SSDP discovery of DLNA media renderers. */
final class DlnaDiscovery {
    interface Listener {
        /** Called on a background thread for each new renderer. */
        void onDevice(DlnaDevice device);
    }

    private static final String SSDP_ADDRESS = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final String SEARCH_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1";
    /** Descriptions by location: repeated searches do not fetch them again. */
    private static final Map<String, DlnaDevice> descriptions = new ConcurrentHashMap<>();

    private DlnaDiscovery() {
    }

    /** Blocks for about {@code durationMs} while searching. */
    static void search(Context context, int durationMs, Listener listener) {
        ControlPoint.init(context);
        WifiManager.MulticastLock lock = null;
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                lock = wifi.createMulticastLock("YtDlna");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (Throwable t) {
            Log.e("Could not acquire multicast lock", t);
        }

        Set<String> seen = new HashSet<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            String request = "M-SEARCH * HTTP/1.1\r\n"
                    + "HOST: " + SSDP_ADDRESS + ":" + SSDP_PORT + "\r\n"
                    + "MAN: \"ssdp:discover\"\r\n"
                    + "MX: 2\r\n"
                    + "ST: " + SEARCH_TARGET + "\r\n"
                    + "USER-AGENT: " + ControlPoint.userAgent() + "\r\n"
                    + "CPFN.UPNP.ORG: " + ControlPoint.name() + "\r\n"
                    + (ControlPoint.uuid().isEmpty() ? "" : "CPUUID.UPNP.ORG: " + ControlPoint.uuid() + "\r\n")
                    + "\r\n";
            byte[] data = request.getBytes(StandardCharsets.US_ASCII);
            DatagramPacket packet = new DatagramPacket(data, data.length,
                    InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT);
            // UDP is lossy, so send twice.
            socket.send(packet);
            socket.send(packet);

            long deadline = System.currentTimeMillis() + durationMs;
            byte[] buf = new byte[4096];
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                socket.setSoTimeout((int) remaining);
                DatagramPacket response = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(response);
                } catch (SocketTimeoutException e) {
                    break;
                }
                String location = header(new String(response.getData(), 0, response.getLength(),
                        StandardCharsets.UTF_8), "location");
                if (location == null || !seen.add(location)) continue;
                try {
                    DlnaDevice device = descriptions.get(location);
                    if (device == null) {
                        device = DlnaDevice.load(location);
                        descriptions.put(location, device);
                        Log.i("Found renderer " + device + " at " + location);
                    }
                    listener.onDevice(device);
                } catch (Exception e) {
                    Log.d("Skipping " + location + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.e("SSDP search failed", e);
        } finally {
            if (lock != null) lock.release();
        }
    }

    private static String header(String message, String name) {
        for (String line : message.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().toLowerCase(Locale.ROOT).equals(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }
}
