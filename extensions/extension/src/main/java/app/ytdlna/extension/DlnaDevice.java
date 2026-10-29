package app.ytdlna.extension;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.IOException;
import java.io.StringReader;
import java.net.URL;

/** A DLNA / UPnP media renderer, read from its device description XML. */
final class DlnaDevice {
    private static final String AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:";
    private static final String CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:";

    final String location;
    String friendlyName = "";
    /** From the device description, for maker-specific features (Samsung captions). */
    String manufacturer = "";
    /** Model, for the device list ("SYMFONISK Bookshelf"); may be empty. */
    String modelName = "";
    /** Sonos: the room the user named in the Sonos app ("Kitchen"). */
    private String roomName = "";
    String udn = "";
    String avTransportType = AV_TRANSPORT + "1";
    String avTransportControlUrl;
    String connectionManagerType = CONNECTION_MANAGER + "1";
    String connectionManagerControlUrl;
    String avTransportEventUrl;
    String renderingControlType;
    String renderingControlUrl;

    // OpenHome services, if the renderer has them.
    String playlistType;
    String playlistControlUrl;
    String playlistEventUrl;
    String productType;
    String productControlUrl;
    String timeType;
    String timeControlUrl;
    String infoType;
    String infoControlUrl;

    boolean hasOpenHomePlaylist() {
        return playlistControlUrl != null;
    }

    /** Lazily loaded ConnectionManager sink protocols. Null if not yet queried. */
    volatile String sinkProtocols;

    private DlnaDevice(String location) {
        this.location = location;
    }

    String host() {
        try {
            return new URL(location).getHost();
        } catch (IOException e) {
            return "";
        }
    }

    int port() {
        try {
            URL u = new URL(location);
            return u.getPort() == -1 ? 80 : u.getPort();
        } catch (IOException e) {
            return 80;
        }
    }

    /** Whether the renderer lists this media type itself (a wildcard does not count). */
    boolean lists(String mimeType) {
        String sink = sinkProtocols;
        return sink != null && sink.toLowerCase(java.util.Locale.ROOT)
                .contains(":" + mimeType.toLowerCase(java.util.Locale.ROOT) + ":");
    }

    boolean isSamsung() {
        return manufacturer.toLowerCase(java.util.Locale.ROOT).contains("samsung");
    }

    boolean supports(String mimeType) {
        String sink = sinkProtocols;
        // Unknown: assume yes and let the renderer decide.
        if (sink == null || sink.isEmpty()) return true;
        // Media types are case-insensitive ("application/x-mpegURL" = "application/x-mpegurl").
        String lower = sink.toLowerCase(java.util.Locale.ROOT);
        return lower.contains(":" + mimeType.toLowerCase(java.util.Locale.ROOT) + ":") || lower.contains(":*:*");
    }

    /** Downloads and parses the device description. */
    static DlnaDevice load(String location) throws Exception {
        LanHttp.Response response = LanHttp.get(location);
        if (response.status != 200) throw new IOException("HTTP " + response.status + " for " + location);
        DlnaDevice device = new DlnaDevice(location);
        device.parse(response.text());
        if (device.avTransportControlUrl == null) {
            throw new IOException("Not a media renderer (no AVTransport): " + location);
        }
        return device;
    }

    private void parse(String xml) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(new StringReader(xml));

        String base = location;
        String serviceType = null;
        String controlUrl = null;
        String eventUrl = null;
        int deviceDepth = 0;
        StringBuilder text = new StringBuilder();

        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            switch (event) {
                case XmlPullParser.START_TAG:
                    text.setLength(0);
                    String start = localName(parser.getName());
                    if (start.equals("device")) deviceDepth++;
                    if (start.equals("service")) {
                        serviceType = null;
                        controlUrl = null;
                        eventUrl = null;
                    }
                    break;
                case XmlPullParser.TEXT:
                    text.append(parser.getText());
                    break;
                case XmlPullParser.END_TAG:
                    String end = localName(parser.getName());
                    String value = text.toString().trim();
                    switch (end) {
                        case "URLBase":
                            if (!value.isEmpty()) base = value;
                            break;
                        case "friendlyName":
                            if (deviceDepth == 1 && friendlyName.isEmpty()) friendlyName = value;
                            break;
                        case "modelName":
                            if (deviceDepth == 1 && modelName.isEmpty()) modelName = value;
                            break;
                        case "roomName":
                            if (deviceDepth == 1 && roomName.isEmpty()) roomName = value;
                            break;
                        case "manufacturer":
                            if (deviceDepth == 1 && manufacturer.isEmpty()) manufacturer = value;
                            break;
                        case "UDN":
                            if (deviceDepth == 1 && udn.isEmpty()) udn = value;
                            break;
                        case "serviceType":
                            serviceType = value;
                            break;
                        case "controlURL":
                            controlUrl = value;
                            break;
                        case "eventSubURL":
                            eventUrl = value;
                            break;
                        case "service":
                            if (serviceType != null && controlUrl != null) {
                                String absolute = new URL(new URL(base), controlUrl).toString();
                                String absoluteEvent = eventUrl == null || eventUrl.isEmpty()
                                        ? null : new URL(new URL(base), eventUrl).toString();
                                // First match wins (root device before embedded devices).
                                if (serviceType.startsWith(AV_TRANSPORT) && avTransportControlUrl == null) {
                                    avTransportType = serviceType;
                                    avTransportControlUrl = absolute;
                                    avTransportEventUrl = absoluteEvent;
                                } else if (serviceType.startsWith("urn:schemas-upnp-org:service:RenderingControl:")
                                        && renderingControlUrl == null) {
                                    renderingControlType = serviceType;
                                    renderingControlUrl = absolute;
                                } else if (serviceType.startsWith("urn:av-openhome-org:service:Playlist:")
                                        && playlistControlUrl == null) {
                                    playlistType = serviceType;
                                    playlistControlUrl = absolute;
                                    playlistEventUrl = absoluteEvent;
                                } else if (serviceType.startsWith("urn:av-openhome-org:service:Product:")
                                        && productControlUrl == null) {
                                    productType = serviceType;
                                    productControlUrl = absolute;
                                } else if (serviceType.startsWith("urn:av-openhome-org:service:Info:")
                                        && infoControlUrl == null) {
                                    infoType = serviceType;
                                    infoControlUrl = absolute;
                                } else if (serviceType.startsWith("urn:av-openhome-org:service:Time:")
                                        && timeControlUrl == null) {
                                    timeType = serviceType;
                                    timeControlUrl = absolute;
                                } else if (serviceType.startsWith(CONNECTION_MANAGER)
                                        && connectionManagerControlUrl == null) {
                                    connectionManagerType = serviceType;
                                    connectionManagerControlUrl = absolute;
                                }
                            }
                            break;
                        case "device":
                            deviceDepth--;
                            break;
                        default:
                            break;
                    }
                    text.setLength(0);
                    break;
                default:
                    break;
            }
        }
        friendlyName = displayName(friendlyName, roomName);
        if (friendlyName.isEmpty()) friendlyName = host();
    }

    /** "192.168.1.5 - Sonos One - RINCON_…" style names: the address and the ID are noise. */
    private static final java.util.regex.Pattern ADDRESS_PREFIX =
            java.util.regex.Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}\\s+-\\s+");
    private static final java.util.regex.Pattern ID_SUFFIX =
            java.util.regex.Pattern.compile("\\s+-\\s+RINCON_[0-9A-Fa-f]+.*$");

    /**
     * The name to show, as BubbleUPnP does: the room for Sonos (its friendly name is
     * "address - model - ID"), else the friendly name without an address prefix or Sonos ID.
     */
    static String displayName(String friendlyName, String roomName) {
        if (roomName != null && !roomName.trim().isEmpty()) return roomName.trim();
        String name = ID_SUFFIX.matcher(friendlyName).replaceFirst("");
        name = ADDRESS_PREFIX.matcher(name).replaceFirst("");
        return name.trim();
    }

    /** The model, if it says more than the name (for the subtitle in the device list). */
    String model() {
        return modelName.isEmpty() || friendlyName.contains(modelName) ? "" : modelName;
    }

    private static String localName(String name) {
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    @Override
    public String toString() {
        return friendlyName + " (" + host() + ")";
    }
}
