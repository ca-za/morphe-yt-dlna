package app.ytdlna.extension;

import java.util.ArrayList;
import java.util.List;

/** Streams of one video, decoded from the app's StreamingData and VideoDetails protos. */
final class StreamInfo {

    static final class Format {
        int itag;
        String url = "";
        String mimeType = "";
        long bitrate;
        int width;
        int height;
        /** Frames per second of a video format (YouTube gives whole numbers: 24, 25, 30, 50, 60); 0 if unknown. */
        int fps;
        long initStart = -1, initEnd = -1;
        long indexStart = -1, indexEnd = -1;
        long contentLength;
        boolean adaptive;
        /** Audio track of a video with several (languages): its id ("de.3", "en-US.4"), name. */
        String audioTrackId = "", audioTrackName = "";
        /** The track YouTube plays by default (the original, usually). */
        boolean audioDefault;
        /** A version with compressed dynamic range ("stable volume"). */
        boolean drc;
        /**
         * Loudness against YouTube's reference (dB; positive = louder, YouTube turns it down by
         * that much), and the perceived loudness (LUFS-like, about 14 below). NaN if not given.
         */
        float loudnessDb = Float.NaN, perceptualLoudnessDb = Float.NaN;

        boolean isAudioOnly() {
            return mimeType.startsWith("audio/");
        }

        boolean isVideoOnly() {
            return adaptive && mimeType.startsWith("video/");
        }

        boolean hasUrl() {
            return url.startsWith("http");
        }

        /** "video/mp4; codecs=\"avc1.640028\"" -> "video/mp4" */
        String container() {
            int i = mimeType.indexOf(';');
            return (i < 0 ? mimeType : mimeType.substring(0, i)).trim();
        }

        /** "video/mp4; codecs=\"avc1.640028\"" -> "avc1.640028" */
        String codecs() {
            int i = mimeType.indexOf("codecs=\"");
            if (i < 0) return "";
            int end = mimeType.indexOf('"', i + 8);
            return end < 0 ? "" : mimeType.substring(i + 8, end);
        }

        @Override
        public String toString() {
            return "itag=" + itag + " " + mimeType + " " + width + "x" + height + (fps > 0 ? "@" + fps : "")
                    + " br=" + bitrate + " len=" + contentLength
                    + " init=" + initStart + "-" + initEnd + " index=" + indexStart + "-" + indexEnd
                    + (hasUrl() ? " url" : " NO-URL")
                    + (audioTrackId.isEmpty() ? "" : " track=" + audioTrackId + (audioDefault ? " default" : ""))
                    + (drc ? " drc" : "");
        }
    }

    // Format: audio track {4 name, 5 id, 6 default} and the DRC flag.
    static final int F_AUDIO_TRACK = 28;
    static final int AT_NAME = 4;
    static final int AT_ID = 5;
    static final int AT_DEFAULT = 6;
    private static final int F_DRC = 49;
    private static final int F_LOUDNESS_DB = 47;
    private static final int F_PERCEPTUAL_LOUDNESS_DB = 53;

    // StreamingData field numbers.
    private static final int SD_FORMATS = 2;
    private static final int SD_ADAPTIVE_FORMATS = 3;

    // Format field numbers.
    private static final int F_ITAG = 1;
    private static final int F_URL = 2;
    private static final int F_MIME_TYPE = 5;
    private static final int F_BITRATE = 6;
    private static final int F_WIDTH = 7;
    private static final int F_HEIGHT = 8;
    private static final int F_INIT_RANGE = 9;
    private static final int F_INDEX_RANGE = 10;
    private static final int F_CONTENT_LENGTH = 12;
    private static final int F_FPS = 25;
    // Range field numbers.
    private static final int R_START = 3;
    private static final int R_END = 4;

    /** StreamingData: HLS playlist (hls_variant) of live streams. */
    private static final int SD_HLS_MANIFEST = 5;

    // VideoDetails field numbers.
    private static final int VD_VIDEO_ID = 1;
    private static final int VD_TITLE = 15;
    private static final int VD_LENGTH_SECONDS = 16;
    private static final int VD_THUMBNAILS = 25;
    private static final int VD_CHANNEL = 33;
    // Thumbnail list: entries with url, width, height.
    private static final int THUMB_ENTRY = 1;
    private static final int THUMB_URL = 1;
    private static final int THUMB_WIDTH = 2;

    String videoId = "";
    String title = "";
    long lengthSeconds;
    /** Name of the uploading channel. */
    String channel = "";
    /** YouTube's HLS playlist of a live stream, "" for other videos. */
    String hlsManifestUrl = "";
    /** Largest thumbnail of the video, "" if none. */
    String thumbnailUrl = "";
    final List<Format> formats = new ArrayList<>();

    private static String largestThumbnail(Proto.Field list) {
        if (list == null || list.wireType != Proto.BYTES) return "";
        String best = "";
        long bestWidth = -1;
        try {
            for (Proto.Field entry : Proto.parse(list.data)) {
                if (entry.number != THUMB_ENTRY || entry.wireType != Proto.BYTES) continue;
                List<Proto.Field> fields = Proto.parse(entry.data);
                String url = Proto.string(fields, THUMB_URL);
                long width = Proto.varint(fields, THUMB_WIDTH);
                if (url.startsWith("http") && width > bestWidth) {
                    // Many TVs show JPEG only: YouTube has every WebP thumbnail as JPEG too.
                    if (url.contains("/vi_webp/")) {
                        url = url.replace("/vi_webp/", "/vi/").replaceAll("\\.webp(\\?|$)", ".jpg$1");
                    }
                    best = url;
                    bestWidth = width;
                }
            }
        } catch (IllegalArgumentException e) {
            // Unexpected layout: no thumbnail, the default one is used.
        }
        return best;
    }

    static StreamInfo parse(byte[] streamingData, byte[] videoDetails) {
        StreamInfo info = new StreamInfo();
        if (videoDetails != null) {
            List<Proto.Field> vd = Proto.parse(videoDetails);
            info.videoId = Proto.string(vd, VD_VIDEO_ID);
            info.title = Proto.string(vd, VD_TITLE);
            info.lengthSeconds = Proto.varint(vd, VD_LENGTH_SECONDS);
            info.channel = Proto.string(vd, VD_CHANNEL);
            info.thumbnailUrl = largestThumbnail(Proto.first(vd, VD_THUMBNAILS));
        }
        for (Proto.Field f : Proto.parse(streamingData)) {
            if ((f.number == SD_FORMATS || f.number == SD_ADAPTIVE_FORMATS) && f.wireType == Proto.BYTES) {
                info.formats.add(parseFormat(f.data, f.number == SD_ADAPTIVE_FORMATS));
            } else if (f.number == SD_HLS_MANIFEST && f.wireType == Proto.BYTES) {
                info.hlsManifestUrl = f.string();
            }
        }
        return info;
    }

    private static float float32(Proto.Field f) {
        if (f == null || f.wireType != Proto.FIXED32 || f.data == null) return Float.NaN;
        return java.nio.ByteBuffer.wrap(f.data).order(java.nio.ByteOrder.LITTLE_ENDIAN).getFloat();
    }

    private static Format parseFormat(byte[] data, boolean adaptive) {
        List<Proto.Field> fields = Proto.parse(data);
        Format format = new Format();
        format.adaptive = adaptive;
        format.itag = (int) Proto.varint(fields, F_ITAG);
        format.url = Proto.string(fields, F_URL);
        format.mimeType = Proto.string(fields, F_MIME_TYPE);
        format.bitrate = Proto.varint(fields, F_BITRATE);
        format.width = (int) Proto.varint(fields, F_WIDTH);
        format.height = (int) Proto.varint(fields, F_HEIGHT);
        if (format.mimeType.startsWith("video/")) format.fps = (int) Proto.varint(fields, F_FPS);
        format.contentLength = Proto.varint(fields, F_CONTENT_LENGTH);
        Proto.Field init = Proto.first(fields, F_INIT_RANGE);
        if (init != null && init.wireType == Proto.BYTES) {
            List<Proto.Field> r = Proto.parse(init.data);
            format.initStart = Proto.varint(r, R_START);
            format.initEnd = Proto.varint(r, R_END);
        }
        Proto.Field track = Proto.first(fields, F_AUDIO_TRACK);
        if (track != null && track.wireType == Proto.BYTES) {
            try {
                List<Proto.Field> t = Proto.parse(track.data);
                format.audioTrackName = Proto.string(t, AT_NAME);
                format.audioTrackId = Proto.string(t, AT_ID);
                format.audioDefault = Proto.varint(t, AT_DEFAULT) != 0;
            } catch (IllegalArgumentException ignored) {
                // Not the message expected: no track information.
            }
        }
        format.drc = Proto.varint(fields, F_DRC) != 0;
        format.loudnessDb = float32(Proto.first(fields, F_LOUDNESS_DB));
        format.perceptualLoudnessDb = float32(Proto.first(fields, F_PERCEPTUAL_LOUDNESS_DB));
        Proto.Field index = Proto.first(fields, F_INDEX_RANGE);
        if (index != null && index.wireType == Proto.BYTES) {
            List<Proto.Field> r = Proto.parse(index.data);
            format.indexStart = Proto.varint(r, R_START);
            format.indexEnd = Proto.varint(r, R_END);
        }
        return format;
    }

    private String preferredTrack;
    private boolean preferredTrackKnown;

    /**
     * The audio track to play of a video with several: the one YouTube plays by default, else
     * the one in the phone's language, else any. Null if the video has only one.
     */
    synchronized String preferredAudioTrack() {
        if (preferredTrackKnown) return preferredTrack;
        preferredTrackKnown = true;
        String language = java.util.Locale.getDefault().getLanguage();
        String inLanguage = null;
        for (Format f : formats) {
            if (!f.isAudioOnly() || f.audioTrackId.isEmpty()) continue;
            if (f.audioDefault) return preferredTrack = f.audioTrackId;
            String code = f.audioTrackId.split("[.-]")[0];
            if (inLanguage == null && code.equals(language)) inLanguage = f.audioTrackId;
        }
        return preferredTrack = inLanguage;
    }

    /** Whether an audio format belongs to the track to play (always, for a single track). */
    boolean isPreferredAudio(Format f) {
        String track = preferredAudioTrack();
        return track == null || track.equals(f.audioTrackId);
    }

    /** Whether {@code f} is the better audio choice than {@code best}: full dynamics, then bitrate. */
    static boolean betterAudio(Format f, Format best) {
        if (best == null) return true;
        if (best.drc != f.drc) return best.drc;
        return f.bitrate > best.bitrate;
    }

    /** Name of the audio track played, empty for a single track. */
    String audioTrackName() {
        String track = preferredAudioTrack();
        if (track == null) return "";
        for (Format f : formats) if (track.equals(f.audioTrackId)) return f.audioTrackName;
        return track;
    }

    /** A live stream: it has an HLS playlist and no fixed length. */
    boolean isLive() {
        return hlsManifestUrl.startsWith("https://") && lengthSeconds == 0;
    }

    /**
     * Only SABR streams: no separate audio or video track has a direct URL (at most the muxed
     * 360p one). With "Spoof video streams" the app often gets these first and the full ones a
     * moment later.
     */
    /**
     * A song's own title in YouTube Music ("art track"): its picture is only the cover (square
     * video formats). Its music video, if there is one, is another title.
     */
    boolean isArtTrack() {
        boolean video = false;
        for (Format f : formats) {
            if (!f.isVideoOnly() || f.height <= 0) continue;
            video = true;
            if (f.width != f.height) return false;
        }
        return video;
    }

    boolean isSabrOnly() {
        if (isLive()) return false;
        for (Format f : formats) {
            if (f.hasUrl() && (f.isAudioOnly() || f.isVideoOnly())) return false;
        }
        return true;
    }

    String summary() {
        int withUrl = 0, audio = 0, video = 0;
        for (Format f : formats) {
            if (!f.hasUrl()) continue;
            withUrl++;
            if (f.isAudioOnly()) audio++;
            else if (f.isVideoOnly()) video++;
        }
        return "videoId=" + videoId + " title=\"" + title + "\" channel=\"" + channel + "\" length=" + lengthSeconds + "s"
                + (isLive() ? " live" : "")
                + " formats=" + formats.size() + " withUrl=" + withUrl
                + " audioWithUrl=" + audio + " videoWithUrl=" + video;
    }
}
