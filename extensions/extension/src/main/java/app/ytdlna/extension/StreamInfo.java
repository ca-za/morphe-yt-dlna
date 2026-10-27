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
        long initStart = -1, initEnd = -1;
        long indexStart = -1, indexEnd = -1;
        long contentLength;
        boolean adaptive;

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
            return "itag=" + itag + " " + mimeType + " " + width + "x" + height
                    + " br=" + bitrate + " len=" + contentLength
                    + " init=" + initStart + "-" + initEnd + " index=" + indexStart + "-" + indexEnd
                    + (hasUrl() ? " url" : " NO-URL");
        }
    }

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
    // Range field numbers.
    private static final int R_START = 3;
    private static final int R_END = 4;

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
    /** Largest thumbnail of the video, "" if none. */
    String thumbnailUrl = "";
    final List<Format> formats = new ArrayList<>();
    final long capturedAt = System.currentTimeMillis();

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
            }
        }
        return info;
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
        format.contentLength = Proto.varint(fields, F_CONTENT_LENGTH);
        Proto.Field init = Proto.first(fields, F_INIT_RANGE);
        if (init != null && init.wireType == Proto.BYTES) {
            List<Proto.Field> r = Proto.parse(init.data);
            format.initStart = Proto.varint(r, R_START);
            format.initEnd = Proto.varint(r, R_END);
        }
        Proto.Field index = Proto.first(fields, F_INDEX_RANGE);
        if (index != null && index.wireType == Proto.BYTES) {
            List<Proto.Field> r = Proto.parse(index.data);
            format.indexStart = Proto.varint(r, R_START);
            format.indexEnd = Proto.varint(r, R_END);
        }
        return format;
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
                + " formats=" + formats.size() + " withUrl=" + withUrl
                + " audioWithUrl=" + audio + " videoWithUrl=" + video;
    }
}
