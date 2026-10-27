package app.ytdlna.extension;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * While casting, the app's own player only gets the smallest audio track: it keeps running as
 * the remote control (timeline, queue, autoplay) without downloading or decoding any video.
 * The renderer still gets every format, from the copy {@link StreamCapture} keeps.
 */
final class LocalPlayback {
    private static final int SD_FORMATS = 2;
    private static final int SD_ADAPTIVE_FORMATS = 3;
    private static final int F_MIME_TYPE = 5;
    private static final int F_BITRATE = 6;

    private LocalPlayback() {
    }

    /** Streaming data without video and with only the smallest audio track. Null if not applicable. */
    static byte[] audioOnly(byte[] streamingData) {
        List<Proto.Field> fields = Proto.parse(streamingData);
        Proto.Field smallestAudio = null;
        long smallestBitrate = Long.MAX_VALUE;
        boolean hasVideo = false;
        for (Proto.Field field : fields) {
            if (field.number == SD_FORMATS) {
                hasVideo = true;
            } else if (field.number == SD_ADAPTIVE_FORMATS && field.wireType == Proto.BYTES) {
                List<Proto.Field> format = Proto.parse(field.data);
                String mime = Proto.string(format, F_MIME_TYPE);
                if (mime.startsWith("video/")) {
                    hasVideo = true;
                } else if (mime.startsWith("audio/")) {
                    long bitrate = Proto.varint(format, F_BITRATE);
                    if (bitrate > 0 && bitrate < smallestBitrate) {
                        smallestBitrate = bitrate;
                        smallestAudio = field;
                    }
                }
            }
        }
        if (smallestAudio == null) return null; // Nothing to play then; leave it alone.
        ByteArrayOutputStream out = new ByteArrayOutputStream(streamingData.length);
        int kept = 0;
        for (Proto.Field field : fields) {
            boolean format = field.number == SD_FORMATS || field.number == SD_ADAPTIVE_FORMATS;
            if (format && field != smallestAudio) continue;
            out.write(streamingData, field.start, field.end - field.start);
            if (format) kept++;
        }
        if (!hasVideo && kept == countFormats(fields)) return null; // Already minimal.
        return out.toByteArray();
    }

    private static int countFormats(List<Proto.Field> fields) {
        int count = 0;
        for (Proto.Field field : fields) {
            if (field.number == SD_FORMATS || field.number == SD_ADAPTIVE_FORMATS) count++;
        }
        return count;
    }

    private static Method parseFrom;
    private static Object registry;

    /** Builds a StreamingData proto of the app (same class as {@code original}) from bytes. */
    static synchronized Object toProto(Object original, byte[] bytes) throws Exception {
        if (parseFrom == null) {
            Class<?> registryClass = Class.forName("com.google.protobuf.ExtensionRegistryLite",
                    false, original.getClass().getClassLoader());
            registry = registryClass.getMethod("getGeneratedRegistry").invoke(null);
            parseFrom = original.getClass().getMethod("parseFrom", ByteBuffer.class, registryClass);
        }
        return parseFrom.invoke(null, ByteBuffer.wrap(bytes), registry);
    }
}
