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
        if (friendlyName.isEmpty()) friendlyName = host();
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
