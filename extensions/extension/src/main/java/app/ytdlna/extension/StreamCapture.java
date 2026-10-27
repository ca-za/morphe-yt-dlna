package app.ytdlna.extension;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the streams the app loaded for recently played videos.
 * Called by the patch from the video streaming data constructor.
 */
@SuppressWarnings("unused")
public final class StreamCapture {
    private static final int MAX_ENTRIES = 20;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    /** videoId -> streams, oldest first. */
    private static final LinkedHashMap<String, StreamInfo> streams =
            new LinkedHashMap<String, StreamInfo>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, StreamInfo> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    private StreamCapture() {
    }

    /**
     * Injection point.
     *
     * @param streamingData StreamingData proto (com.google.protobuf.MessageLite).
     * @param videoDetails  VideoDetails proto (com.google.protobuf.MessageLite).
     */
    public static void onStreamingData(Object streamingData, Object videoDetails) {
        if (streamingData == null) return;
        // Protos are immutable, so they can be serialized off the player thread.
        executor.execute(() -> {
            try {
                StreamInfo info = StreamInfo.parse(toBytes(streamingData), toBytes(videoDetails));
                if (info.videoId.isEmpty() || info.formats.isEmpty()) {
                    Log.d("Ignoring streams without video id or formats: " + info.summary());
                    return;
                }
                synchronized (streams) {
                    streams.remove(info.videoId); // Re-insert as newest.
                    streams.put(info.videoId, info);
                }
                Log.i("Captured " + info.summary());
                for (StreamInfo.Format f : info.formats) {
                    Log.d("  " + f);
                }
            } catch (Throwable t) {
                Log.e("Failed to capture streams", t);
            }
        });
    }

    /** Video id of the video or track the player loaded last. */
    private static volatile String currentVideoId;

    /** Injection point: the player loads a new video. */
    public static void onVideoLoaded(String videoId) {
        if (videoId == null || videoId.isEmpty() || videoId.equals(currentVideoId)) return;
        currentVideoId = videoId;
        Log.d("Now playing " + videoId);
    }

    /** Streams of the video that is playing, or the most recently captured ones. */
    static StreamInfo current() {
        String videoId = currentVideoId;
        if (videoId != null) {
            StreamInfo info = get(videoId);
            if (info != null) return info;
        }
        List<StreamInfo> recent = recent();
        return recent.isEmpty() ? null : recent.get(0);
    }

    static StreamInfo get(String videoId) {
        synchronized (streams) {
            return streams.get(videoId);
        }
    }

    /** Most recent first. */
    static List<StreamInfo> recent() {
        List<StreamInfo> list;
        synchronized (streams) {
            list = new ArrayList<>(streams.values());
        }
        java.util.Collections.reverse(list);
        return list;
    }

    private static final Map<Class<?>, Method> toByteArrayMethods = new java.util.HashMap<>();

    private static byte[] toBytes(Object proto) throws Exception {
        if (proto == null) return null;
        Method method;
        synchronized (toByteArrayMethods) {
            method = toByteArrayMethods.get(proto.getClass());
            if (method == null) {
                // com.google.protobuf.MessageLite.toByteArray() keeps its name.
                method = proto.getClass().getMethod("toByteArray");
                toByteArrayMethods.put(proto.getClass(), method);
            }
        }
        return (byte[]) method.invoke(proto);
    }
}
