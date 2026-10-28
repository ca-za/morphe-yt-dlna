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

    /** Notified about new streams and video changes. */
    interface Listener {
        /** Called on a background thread. */
        void onStreams(StreamInfo info);

        /** Called on the main thread. */
        void onVideoLoaded(String videoId);
    }

    private static final List<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    static void addListener(Listener listener) {
        listeners.add(listener);
    }

    static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    static String currentVideoId() {
        return currentVideoId;
    }

    private StreamCapture() {
    }

    /**
     * Injection point.
     *
     * @param streamingData StreamingData proto (com.google.protobuf.MessageLite).
     * @param videoDetails  VideoDetails proto (com.google.protobuf.MessageLite).
     */
    /**
     * Injection point, at the start of the video streaming data constructor.
     *
     * @param streamingData StreamingData proto (com.google.protobuf.MessageLite).
     * @param videoDetails  VideoDetails proto (com.google.protobuf.MessageLite).
     * @return The streaming data the app's player gets: while casting only the smallest audio
     *         track, otherwise {@code streamingData} unchanged.
     */
    public static Object onStreamingData(Object streamingData, Object videoDetails) {
        if (streamingData == null) return null;
        if (TestHooks.isEnabled()) TestHooks.register(appContext());
        byte[] bytes;
        try {
            bytes = toBytes(streamingData);
        } catch (Throwable t) {
            Log.e("Could not read streaming data", t);
            return streamingData;
        }
        executor.execute(() -> capture(bytes, videoDetails));

        // Shorts or a feed preview play on the phone, with all their streams.
        if (CastSession.active() == null || MorpheState.sidePlayer() != null) return streamingData;
        try {
            byte[] audioOnly = LocalPlayback.audioOnly(bytes);
            if (audioOnly == null) return streamingData;
            Object replacement = LocalPlayback.toProto(streamingData, audioOnly);
            Log.d("Local player gets the smallest tracks only (" + bytes.length + " -> " + audioOnly.length + " bytes)");
            return replacement;
        } catch (Throwable t) {
            Log.e("Could not reduce local playback", t);
            return streamingData;
        }
    }

    private static void capture(byte[] streamingData, Object videoDetails) {
        try {
            StreamInfo info = StreamInfo.parse(streamingData, toBytes(videoDetails));
            if (info.videoId.isEmpty() || info.formats.isEmpty()) {
                Log.d("Ignoring streams without video id or formats: " + info.summary());
                return;
            }
            synchronized (streams) {
                streams.remove(info.videoId); // Re-insert as newest.
                streams.put(info.videoId, info);
            }
            Log.i("Captured " + info.summary() + " " + MorpheState.summary());
            for (StreamInfo.Format f : info.formats) {
                Log.d("  " + f);
            }
            for (Listener listener : listeners) {
                try {
                    listener.onStreams(info);
                } catch (Throwable t) {
                    Log.e("Stream listener failed", t);
                }
            }
        } catch (Throwable t) {
            Log.e("Failed to capture streams", t);
        }
    }

    /** Video id of the video or track the player loaded last. */
    private static volatile String currentVideoId;

    /** Injection point: the player loads a new video. */
    public static void onVideoLoaded(String videoId) {
        Captions.hookedVideoId = videoId;
        if (videoId == null || videoId.isEmpty() || videoId.equals(currentVideoId)) return;
        // While casting, what Shorts or the feed preview load is not the user's video: the
        // renderer keeps playing the main player's video.
        String side = CastSession.active() != null ? MorpheState.sidePlayer() : null;
        if (side != null) {
            Log.d("Ignoring " + videoId + " of the " + side + " player " + MorpheState.summary());
            return;
        }
        currentVideoId = videoId;
        Log.d("Now playing " + videoId + " " + MorpheState.summary());
        for (Listener listener : listeners) {
            try {
                listener.onVideoLoaded(videoId);
            } catch (Throwable t) {
                Log.e("Video listener failed", t);
            }
        }
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

    @SuppressWarnings("PrivateApi")
    private static android.content.Context appContext() {
        try {
            return (android.content.Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
        } catch (Exception e) {
            return null;
        }
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
