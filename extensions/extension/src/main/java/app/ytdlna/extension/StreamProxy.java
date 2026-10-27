package app.ytdlna.extension;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTP server on the phone that the renderer streams from.
 * <p>
 * Routes:
 * <ul>
 *   <li>{@code /v/<videoId>/manifest.mpd} - DASH manifest with separate video and audio tracks.</li>
 *   <li>{@code /v/<videoId>/<formatIndex>.<ext>} - one format, proxied with Range support.</li>
 * </ul>
 * The YouTube stream URLs are bound to the phone, so the renderer can not use them directly.
 */
final class StreamProxy {
    private static final int PREFERRED_PORT = 8720;
    private static final int MAX_VIDEO_HEIGHT = 1080;
    private static final String DLNA_FEATURES =
            "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000";

    /** What to send to the renderer. */
    static final class Media {
        final String path;
        final String mimeType;
        final boolean video;
        final String description;

        Media(String path, String mimeType, boolean video, String description) {
            this.path = path;
            this.mimeType = mimeType;
            this.video = video;
            this.description = description;
        }
    }

    private static StreamProxy instance;

    /** User agent for upstream requests, set from the app's package info. */
    static volatile String userAgent = "com.google.android.youtube/21.42.241 (Linux; U; Android 15) gzip";

    private final ServerSocket server;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    private StreamProxy(ServerSocket server) {
        this.server = server;
        Thread acceptThread = new Thread(this::acceptLoop, "YtDlna-proxy");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /** Port of the running native proxy, or 0 if the Java proxy is used. */
    private static int nativePort;

    /**
     * Starts the proxy if needed and returns its port. Uses the Rust proxy when its
     * library is available, otherwise the Java implementation in this class.
     */
    static synchronized int start(android.content.Context context) throws IOException {
        if (nativePort > 0) return nativePort;
        if (instance == null && NativeProxy.load(context)) {
            int port = NativeProxy.nativeStart(PREFERRED_PORT);
            if (port > 0) {
                nativePort = port;
                Log.i("Using native stream proxy on port " + port);
                return port;
            }
        }
        return get().port();
    }

    /**
     * Makes the streams of a video available. The Java proxy reads them from
     * {@link StreamCapture} on demand, the native proxy needs them registered.
     */
    static void register(StreamInfo info) {
        if (nativePort <= 0) return;
        NativeProxy.nativeSetUserAgent(userAgent);
        String prefix = "/v/" + info.videoId + "/";
        NativeProxy.nativeRemovePrefix(prefix);
        if (dash(info) != null) {
            NativeProxy.nativePutText(prefix + "manifest.mpd", "application/dash+xml", manifest(info));
        }
        for (StreamInfo.Format format : info.formats) {
            if (!format.hasUrl()) continue;
            NativeProxy.nativePutStream(formatPath(info, format), format.container(), format.url, format.contentLength);
        }
    }

    private static synchronized StreamProxy get() throws IOException {
        if (instance == null) {
            ServerSocket socket;
            try {
                socket = new ServerSocket(PREFERRED_PORT);
            } catch (IOException e) {
                socket = new ServerSocket(0);
            }
            instance = new StreamProxy(socket);
            Log.i("Stream proxy listening on port " + socket.getLocalPort());
        }
        return instance;
    }

    int port() {
        return server.getLocalPort();
    }

    // region Media selection

    /** The best media for a renderer: audio for YouTube Music, otherwise DASH or a muxed file. */
    static Media choose(StreamInfo info, DlnaDevice device, boolean audioOnly) {
        if (audioOnly) return audio(info);
        Media media = device.supports("application/dash+xml") ? dash(info) : null;
        return media != null ? media : muxed(info);
    }

    /**
     * Makes the cover available to the renderer. Through the native proxy as plain http,
     * since not every renderer can load https images, otherwise the original https URL.
     */
    static String registerArt(Track track, String baseUrl) {
        if (track.artUrl.isEmpty()) return null;
        if (nativePort <= 0) return track.artUrl;
        String path = "/art/" + track.videoId + ".jpg";
        NativeProxy.nativePutStream(path, "image/jpeg", track.artUrl, 0);
        return baseUrl + path;
    }

    /** DASH manifest with H.264/AAC (or VP9/Opus) tracks. Null if the video has no usable tracks. */
    static Media dash(StreamInfo info) {
        if (videoTracks(info, "video/mp4").isEmpty() && videoTracks(info, "video/webm").isEmpty()) return null;
        return new Media("/v/" + info.videoId + "/manifest.mpd", "application/dash+xml", true, "DASH");
    }

    /** A single file with audio and video, if the video still has one. */
    static Media muxed(StreamInfo info) {
        StreamInfo.Format best = null;
        for (StreamInfo.Format f : info.formats) {
            if (!f.adaptive && f.hasUrl() && f.mimeType.startsWith("video/")
                    && (best == null || f.height > best.height)) {
                best = f;
            }
        }
        if (best == null) return null;
        return new Media(formatPath(info, best), best.container(), true, best.height + "p");
    }

    /** Audio only, preferring AAC in MP4 which nearly every renderer plays. */
    static Media audio(StreamInfo info) {
        StreamInfo.Format best = bestAudio(info, "audio/mp4");
        if (best == null) best = bestAudio(info, "audio/webm");
        if (best == null) return null;
        return new Media(formatPath(info, best), best.container(), false, best.codecs());
    }

    private static String formatPath(StreamInfo info, StreamInfo.Format format) {
        String ext = format.container().endsWith("webm") ? "webm" : (format.isAudioOnly() ? "m4a" : "mp4");
        return "/v/" + info.videoId + "/" + info.formats.indexOf(format) + "." + ext;
    }

    private static List<StreamInfo.Format> videoTracks(StreamInfo info, String container) {
        List<StreamInfo.Format> tracks = new ArrayList<>();
        for (StreamInfo.Format f : info.formats) {
            if (!f.isVideoOnly() || !f.hasUrl() || f.indexEnd <= 0 || f.height > MAX_VIDEO_HEIGHT) continue;
            if (!f.container().equals(container)) continue;
            String codecs = f.codecs();
            // AV1 is still poorly supported by TV hardware decoders.
            if (codecs.startsWith("av01")) continue;
            tracks.add(f);
        }
        tracks.sort(Comparator.comparingInt((StreamInfo.Format f) -> f.height)
                .thenComparingLong(f -> f.bitrate).reversed());
        return tracks;
    }

    private static StreamInfo.Format bestAudio(StreamInfo info, String container) {
        StreamInfo.Format best = null;
        for (StreamInfo.Format f : info.formats) {
            if (!f.isAudioOnly() || !f.hasUrl() || !f.container().equals(container)) continue;
            // Skip surround (ac-3, ec-3) and keep stereo AAC/Opus.
            String codecs = f.codecs();
            if (!(codecs.startsWith("mp4a") || codecs.startsWith("opus"))) continue;
            if (best == null || f.bitrate > best.bitrate) best = f;
        }
        return best;
    }

    static String manifest(StreamInfo info) {
        String container = videoTracks(info, "video/mp4").isEmpty() ? "webm" : "mp4";
        List<StreamInfo.Format> video = videoTracks(info, "video/" + container);
        StreamInfo.Format audio = bestAudio(info, "audio/" + container);

        StringBuilder mpd = new StringBuilder();
        mpd.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" ")
                .append("profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" type=\"static\" ")
                .append("minBufferTime=\"PT2S\" mediaPresentationDuration=\"PT")
                .append(info.lengthSeconds).append("S\">\n")
                .append("<Period>\n");
        appendAdaptationSet(mpd, info, video, "video/" + container);
        if (audio != null) {
            List<StreamInfo.Format> audioList = new ArrayList<>();
            audioList.add(audio);
            appendAdaptationSet(mpd, info, audioList, "audio/" + container);
        }
        mpd.append("</Period>\n</MPD>\n");
        return mpd.toString();
    }

    private static void appendAdaptationSet(StringBuilder mpd, StreamInfo info,
                                            List<StreamInfo.Format> formats, String mimeType) {
        mpd.append(" <AdaptationSet mimeType=\"").append(mimeType)
                .append("\" subsegmentAlignment=\"true\" subsegmentStartsWithSAP=\"1\">\n");
        for (StreamInfo.Format f : formats) {
            mpd.append("  <Representation id=\"").append(info.formats.indexOf(f))
                    .append("\" codecs=\"").append(f.codecs())
                    .append("\" bandwidth=\"").append(f.bitrate).append('"');
            if (f.width > 0) mpd.append(" width=\"").append(f.width).append("\" height=\"").append(f.height).append('"');
            if (f.isAudioOnly()) mpd.append(" audioSamplingRate=\"44100\"");
            mpd.append(">\n");
            if (f.isAudioOnly()) {
                mpd.append("   <AudioChannelConfiguration schemeIdUri=")
                        .append("\"urn:mpeg:dash:23003:3:audio_channel_configuration:2011\" value=\"2\"/>\n");
            }
            // Relative to the manifest URL, so the renderer uses whatever address reached us.
            String path = formatPath(info, f);
            mpd.append("   <BaseURL>").append(path.substring(path.lastIndexOf('/') + 1)).append("</BaseURL>\n")
                    .append("   <SegmentBase indexRange=\"").append(f.indexStart).append('-').append(f.indexEnd)
                    .append("\"><Initialization range=\"").append(f.initStart).append('-').append(f.initEnd)
                    .append("\"/></SegmentBase>\n")
                    .append("  </Representation>\n");
        }
        mpd.append(" </AdaptationSet>\n");
    }

    // endregion

    // region Server

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                pool.execute(() -> handle(client));
            } catch (IOException e) {
                Log.e("Proxy accept failed", e);
            }
        }
    }

    private void handle(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(30_000);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            String requestLine = LanHttp.readLine(in);
            Map<String, String> headers = LanHttp.readHeaders(in);
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase(Locale.ROOT);
            String path = parts[1];
            int query = path.indexOf('?');
            if (query >= 0) path = path.substring(0, query);
            Log.d("Proxy " + socket.getInetAddress().getHostAddress() + ": " + method + " " + path
                    + (headers.containsKey("range") ? " range=" + headers.get("range") : ""));

            String[] segments = path.split("/");
            // ["", "v", videoId, file]
            if (segments.length != 4 || !segments[1].equals("v")) {
                sendText(out, 404, "text/plain", "Not found", method);
                return;
            }
            StreamInfo info = StreamCapture.get(segments[2]);
            if (info == null) {
                sendText(out, 404, "text/plain", "Unknown video", method);
                return;
            }
            String file = segments[3];
            if (file.equals("manifest.mpd")) {
                sendText(out, 200, "application/dash+xml", manifest(info), method);
                return;
            }
            int dot = file.indexOf('.');
            int index;
            try {
                index = Integer.parseInt(dot < 0 ? file : file.substring(0, dot));
            } catch (NumberFormatException e) {
                index = -1;
            }
            if (index < 0 || index >= info.formats.size()) {
                sendText(out, 404, "text/plain", "Unknown format", method);
                return;
            }
            proxy(info.formats.get(index), method, headers.get("range"), out);
        } catch (IOException e) {
            // Renderers often close connections early when seeking.
            Log.d("Proxy connection ended: " + e.getMessage());
        } catch (Throwable t) {
            Log.e("Proxy request failed", t);
        }
    }

    private static void proxy(StreamInfo.Format format, String method, String range, OutputStream out)
            throws IOException {
        if (method.equals("HEAD")) {
            // Answer from what we know, without touching YouTube.
            StringBuilder head = statusLine(200)
                    .append("Content-Type: ").append(format.container()).append("\r\n")
                    .append("Accept-Ranges: bytes\r\n");
            if (format.contentLength > 0) head.append("Content-Length: ").append(format.contentLength).append("\r\n");
            finishHead(head, out);
            return;
        }

        HttpURLConnection upstream = (HttpURLConnection) new URL(format.url).openConnection();
        upstream.setConnectTimeout(10_000);
        upstream.setReadTimeout(30_000);
        upstream.setRequestProperty("User-Agent", userAgent);
        upstream.setRequestProperty("Accept-Encoding", "identity");
        if (range != null) upstream.setRequestProperty("Range", range);
        try {
            int status = upstream.getResponseCode();
            if (status != 200 && status != 206) {
                Log.i("Upstream HTTP " + status + " for itag " + format.itag);
                StringBuilder head = statusLine(status == 416 ? 416 : 502);
                head.append("Content-Length: 0\r\n");
                finishHead(head, out);
                return;
            }
            StringBuilder head = statusLine(status)
                    .append("Content-Type: ").append(format.container()).append("\r\n")
                    .append("Accept-Ranges: bytes\r\n");
            String length = upstream.getHeaderField("Content-Length");
            if (length != null) head.append("Content-Length: ").append(length).append("\r\n");
            String contentRange = upstream.getHeaderField("Content-Range");
            if (contentRange != null) head.append("Content-Range: ").append(contentRange).append("\r\n");
            finishHead(head, out);

            byte[] buf = new byte[64 * 1024];
            try (InputStream body = upstream.getInputStream()) {
                int n;
                while ((n = body.read(buf)) != -1) out.write(buf, 0, n);
            }
            out.flush();
        } finally {
            upstream.disconnect();
        }
    }

    private static void sendText(OutputStream out, int status, String type, String text, String method)
            throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = statusLine(status)
                .append("Content-Type: ").append(type).append("; charset=utf-8\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n");
        finishHead(head, out);
        if (!method.equals("HEAD")) out.write(body);
        out.flush();
    }

    private static StringBuilder statusLine(int status) {
        String reason;
        switch (status) {
            case 200: reason = "OK"; break;
            case 206: reason = "Partial Content"; break;
            case 404: reason = "Not Found"; break;
            case 416: reason = "Range Not Satisfiable"; break;
            default: reason = "Bad Gateway"; break;
        }
        return new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
    }

    private static void finishHead(StringBuilder head, OutputStream out) throws IOException {
        head.append("transferMode.dlna.org: Streaming\r\n")
                .append("contentFeatures.dlna.org: ").append(DLNA_FEATURES).append("\r\n")
                .append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
    }

    // endregion

    /** The phone's address as seen from the renderer's network. */
    static String localAddressFor(DlnaDevice device) {
        try (java.net.DatagramSocket probe = new java.net.DatagramSocket()) {
            // UDP connect sends nothing, it only picks the route.
            probe.connect(InetAddress.getByName(device.host()), device.port());
            return probe.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            Log.e("Could not determine local address", e);
            return "127.0.0.1";
        }
    }
}
