package app.ytdlna.extension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sends UPnP AVTransport / ConnectionManager SOAP actions to a renderer. */
final class DlnaController {
    private DlnaController() {
    }

    /** Loads the formats the renderer accepts. Failures leave it unknown. */
    static void loadSinkProtocols(DlnaDevice device) {
        if (device.sinkProtocols != null || device.connectionManagerControlUrl == null) return;
        try {
            String response = soap(device.connectionManagerControlUrl, device.connectionManagerType,
                    "GetProtocolInfo", "");
            Matcher m = Pattern.compile("<Sink>(.*?)</Sink>", Pattern.DOTALL).matcher(response);
            device.sinkProtocols = m.find() ? unescape(m.group(1)) : "";
            Log.d(device + " sink protocols: " + device.sinkProtocols);
        } catch (Exception e) {
            device.sinkProtocols = "";
            Log.d("GetProtocolInfo failed for " + device + ": " + e.getMessage());
        }
    }

    static void play(DlnaDevice device, String uri, String mimeType, String title, boolean video)
            throws IOException {
        Track track = new Track("");
        track.title = title;
        play(device, uri, mimeType, track, null, video);
    }

    /** Loads a track with full metadata and starts it. {@code artUri} may be null. */
    static void play(DlnaDevice device, String uri, String mimeType, Track track, String artUri, boolean video)
            throws IOException {
        String metadata = didl(uri, mimeType, track, artUri, video);
        // Some renderers refuse a new URI while playing.
        try {
            stop(device);
        } catch (IOException ignored) {
        }
        avTransport(device, "SetAVTransportURI",
                "<CurrentURI>" + escape(uri) + "</CurrentURI>"
                        + "<CurrentURIMetaData>" + escape(metadata) + "</CurrentURIMetaData>");
        avTransport(device, "Play", "<Speed>1</Speed>");
    }

    static void stop(DlnaDevice device) throws IOException {
        avTransport(device, "Stop", "");
    }

    static void resume(DlnaDevice device) throws IOException {
        avTransport(device, "Play", "<Speed>1</Speed>");
    }

    static void pause(DlnaDevice device) throws IOException {
        avTransport(device, "Pause", "");
    }

    static void seek(DlnaDevice device, long positionMs) throws IOException {
        avTransport(device, "Seek", "<Unit>REL_TIME</Unit><Target>" + Track.upnpTime(positionMs) + "</Target>");
    }

    /** Current position in ms, or -1 if unknown. */
    static long position(DlnaDevice device) throws IOException {
        String response = soap(device.avTransportControlUrl, device.avTransportType, "GetPositionInfo",
                "<InstanceID>0</InstanceID>");
        Matcher m = Pattern.compile("<RelTime>(.*?)</RelTime>").matcher(response);
        return m.find() ? Track.parseUpnpTime(m.group(1)) : -1;
    }

    /** "PLAYING", "PAUSED_PLAYBACK", "STOPPED", "TRANSITIONING", ... or "" if unknown. */
    static String transportState(DlnaDevice device) throws IOException {
        String response = soap(device.avTransportControlUrl, device.avTransportType, "GetTransportInfo",
                "<InstanceID>0</InstanceID>");
        Matcher m = Pattern.compile("<CurrentTransportState>(.*?)</CurrentTransportState>").matcher(response);
        return m.find() ? m.group(1) : "";
    }

    private static void avTransport(DlnaDevice device, String action, String arguments) throws IOException {
        soap(device.avTransportControlUrl, device.avTransportType, action,
                "<InstanceID>0</InstanceID>" + arguments);
    }

    private static String soap(String controlUrl, String serviceType, String action, String arguments)
            throws IOException {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + action + " xmlns:u=\"" + serviceType + "\">"
                + arguments
                + "</u:" + action + "></s:Body></s:Envelope>";
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "text/xml; charset=\"utf-8\"");
        headers.put("SOAPACTION", "\"" + serviceType + "#" + action + "\"");
        LanHttp.Response response = LanHttp.request("POST", controlUrl, headers,
                body.getBytes(StandardCharsets.UTF_8));
        String text = response.text();
        if (response.status != 200) {
            throw new IOException(action + " failed: HTTP " + response.status + " " + upnpError(text));
        }
        Log.d(action + " OK");
        return text;
    }

    static String didl(String uri, String mimeType, Track track, String artUri, boolean video) {
        String upnpClass = video ? "object.item.videoItem" : "object.item.audioItem.musicTrack";
        StringBuilder item = new StringBuilder();
        item.append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
                .append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
                .append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" ")
                .append("xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">")
                .append("<item id=\"ytdlna-").append(escape(track.videoId))
                .append("\" parentID=\"0\" restricted=\"1\">")
                .append("<dc:title>").append(escape(track.title)).append("</dc:title>");
        if (!track.artist.isEmpty()) {
            item.append("<dc:creator>").append(escape(track.artist)).append("</dc:creator>")
                    .append("<upnp:artist>").append(escape(track.artist)).append("</upnp:artist>");
        }
        if (!track.album.isEmpty()) {
            item.append("<upnp:album>").append(escape(track.album)).append("</upnp:album>");
        }
        if (artUri != null && !artUri.isEmpty()) {
            item.append("<upnp:albumArtURI>").append(escape(artUri)).append("</upnp:albumArtURI>");
        }
        item.append("<upnp:class>").append(upnpClass).append("</upnp:class>")
                .append("<res protocolInfo=\"http-get:*:").append(mimeType).append(":*\"");
        if (track.durationMs > 0) {
            item.append(" duration=\"").append(Track.upnpTime(track.durationMs)).append(".000\"");
        }
        item.append(">").append(escape(uri)).append("</res></item></DIDL-Lite>");
        return item.toString();
    }

    private static String upnpError(String response) {
        Matcher code = Pattern.compile("<errorCode>(.*?)</errorCode>").matcher(response);
        Matcher description = Pattern.compile("<errorDescription>(.*?)</errorDescription>").matcher(response);
        return (code.find() ? code.group(1) : "") + " " + (description.find() ? description.group(1) : "");
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }
}
