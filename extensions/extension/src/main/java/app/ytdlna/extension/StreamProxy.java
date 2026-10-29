package app.ytdlna.extension;

import android.content.Context;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What the renderer streams, served by the native (Rust) proxy on the phone.
 * <p>
 * Paths:
 * <ul>
 *   <li>{@code /v/<videoId>/manifest.mpd} - DASH manifest with separate video and audio tracks.</li>
 *   <li>{@code /v/<videoId>/master.m3u8} - the same as HLS (H.264/AAC), for renderers without DASH;
 *   {@code <formatIndex>.m3u8} are its media playlists, built by the proxy from the segment index.</li>
 *   <li>{@code /v/<videoId>/<formatIndex>.<ext>} - one format, proxied with Range support.</li>
 *   <li>{@code /v/<videoId>/live.m3u8} - a live stream as HLS; {@code L<formatIndex>/} hold the
 *   playlist and segments of each live format, built by the proxy from the newest segment.</li>
 *   <li>{@code /v/<videoId>/auto} - the media chosen for the renderer (OpenHome playlist entries).</li>
 * </ul>
 * The YouTube stream URLs are bound to the phone, so the renderer can not use them directly.
 */
final class StreamProxy {
    private static final int PREFERRED_PORT = 8720;
    private static final int MAX_VIDEO_HEIGHT = 1080;
    static final String HLS_MIME = "application/vnd.apple.mpegurl";
    /** Mime types renderers announce for HLS. */
    private static final String[] HLS_MIMES = {HLS_MIME, "application/x-mpegURL", "audio/mpegurl", "audio/x-mpegurl"};

    /** Tests: HLS even for renderers that take DASH. */
    static volatile boolean preferHls;

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

    /** User agent for upstream requests, set from the app's package info. */
    static volatile String userAgent = "";

    private static int port;

    private StreamProxy() {
    }

    /** Starts the proxy if needed and returns its port. */
    static synchronized int start(Context context) throws IOException {
        if (port > 0) return port;
        if (!NativeProxy.load(context)) throw new IOException("Stream proxy not available on this device");
        int started = NativeProxy.nativeStart(PREFERRED_PORT);
        if (started <= 0) throw new IOException("Stream proxy could not start");
        // Event subscriptions are sent by the proxy: they introduce the phone like our requests do.
        ControlPoint.init(context);
        NativeProxy.nativeSetControlPoint(ControlPoint.name(), ControlPoint.uuid(), ControlPoint.userAgent());
        port = started;
        return port;
    }

    /** Closes the proxy after a cast: between casts nothing is reachable on the network. */
    static synchronized void stop() {
        if (port <= 0) return;
        NativeProxy.nativeStop();
        RendererExtras.reset();
        port = 0;
    }

    /** Makes the streams of a video available to the renderer. */
    static void register(StreamInfo info) {
        NativeProxy.nativeSetUserAgent(userAgent);
        String prefix = "/v/" + info.videoId + "/";
        NativeProxy.nativeRemovePrefix(prefix);
        if (dash(info) != null) {
            NativeProxy.nativePutText(prefix + "manifest.mpd", "application/dash+xml", manifest(info));
        }
        if (info.isLive() && live(info) != null) {
            for (StreamInfo.Format f : liveTracks(info)) {
                NativeProxy.nativePutLiveTrack(prefix + "L" + info.formats.indexOf(f), f.url, f.container());
            }
            NativeProxy.nativePutText(prefix + "live.m3u8", HLS_MIME, liveMaster(info));
        }
        if (hls(info) != null) {
            NativeProxy.nativePutText(prefix + "master.m3u8", HLS_MIME, hlsMaster(info));
            for (StreamInfo.Format f : hlsTracks(info)) {
                String file = fileName(info, f);
                NativeProxy.nativePutHls(prefix + info.formats.indexOf(f) + ".m3u8", file, f.url,
                        f.initEnd, f.indexStart, f.indexEnd);
            }
        }
        for (StreamInfo.Format format : info.formats) {
            if (!format.hasUrl()) continue;
            NativeProxy.nativePutStream(formatPath(info, format), format.container(), format.url, format.contentLength);
        }
    }

    /** Serves {@code media} of a video also under {@code path}. */
    static void alias(StreamInfo info, Media media, String path) {
        if (media.mimeType.equals("application/dash+xml")) {
            NativeProxy.nativePutText(path, media.mimeType, manifest(info));
            return;
        }
        if (media.path.endsWith("/live.m3u8")) {
            // Its tracks are found relative to the path, next to it.
            NativeProxy.nativePutText(path, media.mimeType, liveMaster(info));
            return;
        }
        if (media.mimeType.equals(HLS_MIME)) {
            // Its media playlists are found relative to the path, next to the master.
            NativeProxy.nativePutText(path, media.mimeType, hlsMaster(info));
            return;
        }
        String file = media.path.substring(media.path.lastIndexOf('/') + 1);
        StreamInfo.Format format = info.formats.get(Integer.parseInt(file.substring(0, file.indexOf('.'))));
        NativeProxy.nativePutStream(path, format.container(), format.url, format.contentLength);
    }

    // region Media selection

    /**
     * The best media for a renderer: audio for YouTube Music, otherwise DASH, HLS (renderers
     * without DASH) or a muxed file (at most 360p).
     */
    static Media choose(StreamInfo info, DlnaDevice device, boolean audioOnly) {
        // Live streams are served as HLS only.
        if (info.isLive()) return supportsHls(device) ? live(info) : null;
        if (audioOnly) return audio(info);
        Media media = null;
        if (preferHls && supportsHls(device)) media = hls(info);
        if (media == null && device.supports("application/dash+xml")) media = dash(info);
        if (media == null && supportsHls(device)) media = hls(info);
        return media != null ? media : muxed(info);
    }

    /** Media type of {@link #choose} for a video before its streams are known. */
    static String videoMime(DlnaDevice device) {
        if (preferHls && supportsHls(device)) return HLS_MIME;
        if (device.supports("application/dash+xml")) return "application/dash+xml";
        return supportsHls(device) ? HLS_MIME : "video/mp4";
    }

    private static boolean supportsHls(DlnaDevice device) {
        for (String mime : HLS_MIMES) {
            if (device.supports(mime)) return true;
        }
        return false;
    }

    /** A live stream as HLS from its H.264 and AAC live formats. Null if it lacks them. */
    static Media live(StreamInfo info) {
        List<StreamInfo.Format> tracks = liveTracks(info);
        if (tracks.size() < 2) return null;
        return new Media("/v/" + info.videoId + "/live.m3u8", HLS_MIME, true, "Live HLS " + tracks.get(0).height + "p");
    }

    /** Live video formats (H.264 up to 1080p), best first, then the AAC audio; empty if one is missing. */
    private static List<StreamInfo.Format> liveTracks(StreamInfo info) {
        List<StreamInfo.Format> tracks = new ArrayList<>();
        StreamInfo.Format audio = null;
        for (StreamInfo.Format f : info.formats) {
            if (!f.hasUrl() || !f.adaptive || !f.container().endsWith("mp4")) continue;
            if (f.isVideoOnly() && f.codecs().startsWith("avc1") && f.height <= MAX_VIDEO_HEIGHT) tracks.add(f);
            if (f.isAudioOnly() && f.codecs().startsWith("mp4a") && info.isPreferredAudio(f)
                    && StreamInfo.betterAudio(f, audio)) audio = f;
        }
        if (tracks.isEmpty() || audio == null) return new ArrayList<>();
        tracks.sort(Comparator.comparingInt((StreamInfo.Format f) -> f.height).reversed());
        tracks.add(audio);
        return tracks;
    }

    /** Master playlist of a live stream: one variant per video format, all with the same audio. */
    static String liveMaster(StreamInfo info) {
        List<StreamInfo.Format> tracks = liveTracks(info);
        StreamInfo.Format audio = tracks.remove(tracks.size() - 1);
        StringBuilder m3u8 = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n");
        m3u8.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"Audio\",DEFAULT=YES,AUTOSELECT=YES,URI=\"L")
                .append(info.formats.indexOf(audio)).append("/index.m3u8\"\n");
        for (StreamInfo.Format video : tracks) {
            m3u8.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(video.bitrate + audio.bitrate)
                    .append(",CODECS=\"").append(video.codecs()).append(',').append(audio.codecs()).append('"')
                    .append(",RESOLUTION=").append(video.width).append('x').append(video.height)
                    .append(",AUDIO=\"audio\"\n")
                    .append('L').append(info.formats.indexOf(video)).append("/index.m3u8\n");
        }
        return m3u8.toString();
    }

    /** HLS with H.264 video (up to 1080p) and AAC audio. Null if the video lacks them. */
    static Media hls(StreamInfo info) {
        List<StreamInfo.Format> video = hlsTracks(info);
        if (video.size() < 2 || !video.get(video.size() - 1).isAudioOnly()) return null;
        return new Media("/v/" + info.videoId + "/master.m3u8", HLS_MIME, true, "HLS " + video.get(0).height + "p");
    }

    /** Video tracks for HLS, best first, then the audio track; empty if one of them is missing. */
    private static List<StreamInfo.Format> hlsTracks(StreamInfo info) {
        List<StreamInfo.Format> tracks = new ArrayList<>();
        // HLS has no VP9: H.264 only.
        for (StreamInfo.Format f : videoTracks(info, "video/mp4")) {
            if (f.codecs().startsWith("avc1") && f.initEnd > 0) tracks.add(f);
        }
        StreamInfo.Format audio = bestAudio(info, "audio/mp4");
        if (tracks.isEmpty() || audio == null || !audio.codecs().startsWith("mp4a") || audio.indexEnd <= 0) {
            return new ArrayList<>();
        }
        tracks.add(audio);
        return tracks;
    }

    /** Master playlist: one variant per video track, all with the same audio. */
    static String hlsMaster(StreamInfo info) {
        List<StreamInfo.Format> tracks = hlsTracks(info);
        StreamInfo.Format audio = tracks.remove(tracks.size() - 1);
        StringBuilder m3u8 = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n");
        m3u8.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"Audio\",DEFAULT=YES,AUTOSELECT=YES,URI=\"")
                .append(info.formats.indexOf(audio)).append(".m3u8\"\n");
        for (StreamInfo.Format video : tracks) {
            m3u8.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(video.bitrate + audio.bitrate)
                    .append(",CODECS=\"").append(video.codecs()).append(',').append(audio.codecs()).append('"')
                    .append(",RESOLUTION=").append(video.width).append('x').append(video.height)
                    .append(",AUDIO=\"audio\"\n")
                    .append(info.formats.indexOf(video)).append(".m3u8\n");
        }
        return m3u8.toString();
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
        return "/v/" + info.videoId + "/" + fileName(info, format);
    }

    private static String fileName(StreamInfo info, StreamInfo.Format format) {
        String ext = format.container().endsWith("webm") ? "webm" : (format.isAudioOnly() ? "m4a" : "mp4");
        return info.formats.indexOf(format) + "." + ext;
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

    /** The audio the renderer gets (as in the DASH manifest and the audio-only stream). */
    static StreamInfo.Format castAudio(StreamInfo info) {
        StreamInfo.Format best = bestAudio(info, "audio/mp4");
        return best != null ? best : bestAudio(info, "audio/webm");
    }

    private static StreamInfo.Format bestAudio(StreamInfo info, String container) {
        StreamInfo.Format best = null;
        for (StreamInfo.Format f : info.formats) {
            if (!f.isAudioOnly() || !f.hasUrl() || !f.container().equals(container)) continue;
            // Of a video with several languages, the track YouTube plays.
            if (!info.isPreferredAudio(f)) continue;
            // Skip surround (ac-3, ec-3) and keep stereo AAC/Opus.
            String codecs = f.codecs();
            if (!(codecs.startsWith("mp4a") || codecs.startsWith("opus"))) continue;
            if (StreamInfo.betterAudio(f, best)) best = f;
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

    /** The phone's address as seen from the renderer's network. */
    static String localAddressFor(DlnaDevice device) {
        try (DatagramSocket probe = new DatagramSocket()) {
            // UDP connect sends nothing, it only picks the route.
            probe.connect(InetAddress.getByName(device.host()), device.port());
            return probe.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            Log.e("Could not determine local address", e);
            return "127.0.0.1";
        }
    }
}
