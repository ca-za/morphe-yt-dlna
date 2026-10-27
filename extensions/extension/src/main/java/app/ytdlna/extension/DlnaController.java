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
        String metadata = didl(uri, mimeType, title, video);
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

    private static String didl(String uri, String mimeType, String title, boolean video) {
        String upnpClass = video ? "object.item.videoItem" : "object.item.audioItem.musicTrack";
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
                + "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                + "<item id=\"0\" parentID=\"-1\" restricted=\"1\">"
                + "<dc:title>" + escape(title) + "</dc:title>"
                + "<upnp:class>" + upnpClass + "</upnp:class>"
                + "<res protocolInfo=\"http-get:*:" + mimeType + ":*\">" + escape(uri) + "</res>"
                + "</item></DIDL-Lite>";
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
