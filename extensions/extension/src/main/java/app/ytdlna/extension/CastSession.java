package app.ytdlna.extension;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * An active DLNA cast.
 * <p>
 * The app's own player keeps running muted and acts as the remote control: its media session
 * (the same one behind the notification and lock screen controls) is mirrored to the renderer.
 * New videos, play, pause and seeks are forwarded. The streams the player loads are served to
 * the renderer by the stream proxy.
 */
final class CastSession implements StreamCapture.Listener {
    /** Position differences below this are treated as normal drift, not as a seek. */
    private static final long SEEK_THRESHOLD_MS = 3000;
    /** After loading a video, position changes are ignored for this long. */
    private static final long LOAD_SETTLE_MS = 2500;
    /** Renderer state events right after our own commands are their echo, not user actions. */
    private static final long RENDERER_ECHO_MS = 1500;
    private static final long RENDERER_LOAD_SETTLE_MS = 5000;
    /** Larger differences between phone and renderer position are corrected on the phone. */
    private static final long DRIFT_THRESHOLD_MS = 4000;
    private static final long SYNC_INTERVAL_MS = 10_000;
    private static final long POLL_INTERVAL_MS = 2000;

    private static CastSession active;

    static synchronized CastSession active() {
        return active;
    }

    static synchronized void start(Context context, DlnaDevice device, StreamInfo first, String streamHost) {
        if (active != null) active.stop(false);
        active = new CastSession(context.getApplicationContext(), device, streamHost);
        active.begin(first);
    }

    static synchronized void stopActive(boolean stopRenderer) {
        if (active != null) {
            active.stop(stopRenderer);
            active = null;
        }
    }

    final DlnaDevice device;
    private final Context context;
    private final String streamHost;
    private final boolean audioOnly;
    /** All renderer calls run in order on this thread. */
    private final ExecutorService renderer = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile String baseUrl;
    private volatile String loadedVideoId;
    private volatile String loadingVideoId;
    private volatile boolean stopped;

    private MediaController controller;
    private int savedVolume = -1;
    private int attachAttempts;

    // Last local playback state, for detecting seeks and state changes.
    private int lastState = -1;
    private long lastPosition;
    private long lastUpdateTime;
    private float lastSpeed;
    private long ignorePositionUntil;

    // Renderer side.
    private volatile String avTransportToken;
    private volatile String rendererState = "";
    private volatile long ignoreRendererUntil;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            onLocalState(state);
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            if (metadata != null) {
                Log.d("Session metadata: " + metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
            }
        }

        @Override
        public void onSessionDestroyed() {
            Log.i("App media session destroyed");
            controller = null;
        }
    };

    private CastSession(Context context, DlnaDevice device, String streamHost) {
        this.context = context;
        this.device = device;
        this.streamHost = streamHost == null ? "" : streamHost;
        this.audioOnly = context.getPackageName().contains("music");
    }

    private void begin(StreamInfo first) {
        Log.i("Cast session started on " + device);
        StreamCapture.addListener(this);
        renderer.execute(() -> {
            try {
                DlnaController.loadSinkProtocols(device);
                int port = StreamProxy.start(context);
                String host = streamHost.isEmpty() ? StreamProxy.localAddressFor(device) : streamHost;
                baseUrl = "http://" + host + ":" + port;
                startRendererEvents();
            } catch (Exception e) {
                Log.e("Could not start stream proxy", e);
                toast(DlnaCast.t("Streamen fehlgeschlagen: ", "Cast failed: ") + e.getMessage());
                main.post(() -> stopActive(false));
            }
        });
        main.post(this::attachController);
        load(first);
    }

    void stop(boolean stopRenderer) {
        if (stopped) return;
        stopped = true;
        Log.i("Cast session stopped");
        StreamCapture.removeListener(this);
        String token = avTransportToken;
        if (token != null) {
            avTransportToken = null;
            new Thread(() -> NativeProxy.nativeUnsubscribe(token), "YtDlna-unsubscribe").start();
        }
        main.post(() -> {
            if (controller != null) controller.unregisterCallback(callback);
            restoreLocalVolume();
        });
        if (stopRenderer) {
            renderer.execute(() -> {
                try {
                    DlnaController.stop(device);
                } catch (Exception e) {
                    Log.d("Stop failed: " + e.getMessage());
                }
            });
        }
        renderer.shutdown();
    }

    // region Local player

    /** Finds the app's media session through its own media notification. */
    private void attachController() {
        if (stopped || controller != null) return;
        MediaSession.Token token = findSessionToken();
        if (token == null) {
            if (++attachAttempts < 20) {
                main.postDelayed(this::attachController, 1000);
            } else {
                Log.i("App media session not found, local controls are not mirrored");
            }
            return;
        }
        controller = new MediaController(context, token);
        controller.registerCallback(callback, main);
        muteLocal();
        logSession();
        PlaybackState state = controller.getPlaybackState();
        if (state != null) rememberState(state);
    }

    private MediaSession.Token findSessionToken() {
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            for (StatusBarNotification notification : manager.getActiveNotifications()) {
                Object token = notification.getNotification().extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);
                if (token instanceof MediaSession.Token) return (MediaSession.Token) token;
            }
        } catch (Exception e) {
            Log.e("Could not read media notification", e);
        }
        return null;
    }

    private void logSession() {
        MediaMetadata metadata = controller.getMetadata();
        if (metadata != null) {
            StringBuilder keys = new StringBuilder();
            for (String key : metadata.keySet()) {
                Object value = key.contains("BITMAP") || key.contains("ART") && !key.endsWith("URI")
                        ? "<bitmap>" : metadata.getText(key);
                keys.append("\n  ").append(key).append(" = ").append(value);
            }
            Log.d("Session metadata:" + keys);
        }
        List<MediaSession.QueueItem> queue = controller.getQueue();
        if (queue != null) {
            Log.d("Session queue: " + queue.size() + " items");
            for (int i = 0; i < Math.min(queue.size(), 5); i++) {
                MediaSession.QueueItem item = queue.get(i);
                Log.d("  #" + item.getQueueId() + " " + item.getDescription().getMediaId()
                        + " " + item.getDescription().getTitle() + " / " + item.getDescription().getSubtitle()
                        + " icon=" + item.getDescription().getIconUri());
            }
        }
    }

    private void muteLocal() {
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null || savedVolume >= 0) return;
        savedVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
    }

    private void restoreLocalVolume() {
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null || savedVolume < 0) return;
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0);
        savedVolume = -1;
    }

    /** Current local position in ms, extrapolated while playing. */
    private long localPosition() {
        MediaController c = controller;
        PlaybackState state = c == null ? null : c.getPlaybackState();
        if (state == null) return 0;
        long position = state.getPosition();
        if (state.getState() == PlaybackState.STATE_PLAYING) {
            position += (long) ((SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime())
                    * state.getPlaybackSpeed());
        }
        return Math.max(0, position);
    }

    private void onLocalState(PlaybackState state) {
        if (state == null || stopped) return;
        int newState = state.getState();
        long now = SystemClock.elapsedRealtime();

        if (lastState != -1 && now > ignorePositionUntil && loadedVideoId != null
                && loadedVideoId.equals(StreamCapture.currentVideoId())) {
            long expected = lastPosition;
            if (lastState == PlaybackState.STATE_PLAYING) {
                expected += (long) ((state.getLastPositionUpdateTime() - lastUpdateTime) * lastSpeed);
            }
            long position = state.getPosition();
            if (newState != PlaybackState.STATE_BUFFERING && Math.abs(position - expected) > SEEK_THRESHOLD_MS) {
                Log.d("Local seek to " + position + " ms");
                renderer.execute(() -> call("Seek", () -> DlnaController.seek(device, position)));
            }
        }
        if (newState != lastState) {
            if (newState == PlaybackState.STATE_PLAYING && lastState != -1) {
                renderer.execute(() -> call("Play", () -> DlnaController.resume(device)));
            } else if (newState == PlaybackState.STATE_PAUSED) {
                renderer.execute(() -> call("Pause", () -> DlnaController.pause(device)));
            }
        }
        rememberState(state);
    }

    private void rememberState(PlaybackState state) {
        lastState = state.getState();
        lastPosition = state.getPosition();
        lastUpdateTime = state.getLastPositionUpdateTime();
        lastSpeed = state.getPlaybackSpeed();
    }

    // endregion

    // region Renderer events

    /** Subscribes to renderer events through the native proxy, or polls the renderer. */
    private void startRendererEvents() {
        if (StreamProxy.isNative() && device.avTransportEventUrl != null) {
            avTransportToken = NativeProxy.nativeSubscribe(device.avTransportEventUrl, baseUrl);
        }
        if (avTransportToken != null) {
            Thread thread = new Thread(this::eventLoop, "YtDlna-events");
            thread.setDaemon(true);
            thread.start();
        } else {
            Log.i("Renderer events not available, polling the renderer");
            main.postDelayed(this::poll, POLL_INTERVAL_MS);
        }
        main.postDelayed(this::syncPosition, SYNC_INTERVAL_MS);
    }

    private void eventLoop() {
        while (!stopped) {
            String event = NativeProxy.nativeWaitEvent(1000);
            if (event == null) continue;
            String[] parts = event.split("\n", 3);
            if (parts.length < 3 || !parts[0].equals(avTransportToken)) continue;
            String name = parts[1];
            String value = parts[2];
            if (name.equals("SubscriptionLost")) {
                Log.i("Renderer event subscription lost, polling instead");
                main.postDelayed(this::poll, POLL_INTERVAL_MS);
                return;
            }
            main.post(() -> onRendererEvent(name, value));
        }
    }

    private void poll() {
        if (stopped) return;
        renderer.execute(() -> {
            try {
                String state = DlnaController.transportState(device);
                main.post(() -> onRendererEvent("TransportState", state));
            } catch (Exception e) {
                Log.d("Polling renderer failed: " + e.getMessage());
            }
        });
        main.postDelayed(this::poll, POLL_INTERVAL_MS);
    }

    /** Renderer state change, on the main thread. */
    private void onRendererEvent(String name, String value) {
        if (stopped || !name.equals("TransportState") || value.equals(rendererState)) return;
        String previous = rendererState;
        rendererState = value;
        Log.d("Renderer " + previous + " -> " + value);
        if (SystemClock.elapsedRealtime() < ignoreRendererUntil) return;

        MediaController c = controller;
        PlaybackState local = c == null ? null : c.getPlaybackState();
        int localState = local == null ? PlaybackState.STATE_NONE : local.getState();
        switch (value) {
            case "PAUSED_PLAYBACK":
                if (localState == PlaybackState.STATE_PLAYING) {
                    Log.i("Paused on the renderer");
                    c.getTransportControls().pause();
                }
                break;
            case "PLAYING":
                if (localState == PlaybackState.STATE_PAUSED) {
                    Log.i("Resumed on the renderer");
                    c.getTransportControls().play();
                }
                break;
            case "STOPPED":
                long remaining = local == null || c.getMetadata() == null ? Long.MAX_VALUE
                        : c.getMetadata().getLong(MediaMetadata.METADATA_KEY_DURATION) - localPosition();
                if (remaining > 5000) {
                    // Stopped on the TV, not the end of the video: end the cast like YouTube does.
                    Log.i("Stopped on the renderer, ending the cast");
                    if (c != null) c.getTransportControls().pause();
                    toast(DlnaCast.t("Wiedergabe auf ", "Playback on ") + device.friendlyName
                            + DlnaCast.t(" beendet", " ended"));
                    stopActive(false);
                }
                break;
            default:
                break;
        }
    }

    /** Keeps the phone at the renderer's position, since the renderer is what is heard. */
    private void syncPosition() {
        if (stopped) return;
        main.postDelayed(this::syncPosition, SYNC_INTERVAL_MS);
        if (!"PLAYING".equals(rendererState) && !rendererState.isEmpty()) return;
        renderer.execute(() -> {
            try {
                if (loadedVideoId == null || !loadedVideoId.equals(StreamCapture.currentVideoId())) return;
                long remote = DlnaController.position(device);
                long local = localPosition();
                if (remote < 0 || SystemClock.elapsedRealtime() < ignorePositionUntil) return;
                if (Math.abs(remote - local) > DRIFT_THRESHOLD_MS) {
                    Log.i("Correcting phone position " + local + " -> " + remote + " ms");
                    main.post(() -> {
                        MediaController c = controller;
                        if (c == null) return;
                        ignorePositionUntil = SystemClock.elapsedRealtime() + LOAD_SETTLE_MS;
                        c.getTransportControls().seekTo(remote);
                    });
                }
            } catch (Exception e) {
                Log.d("Position sync failed: " + e.getMessage());
            }
        });
    }

    // endregion

    // region Streams

    @Override
    public void onStreams(StreamInfo info) {
        if (info.videoId.equals(StreamCapture.currentVideoId())) load(info);
    }

    @Override
    public void onVideoLoaded(String videoId) {
        ignorePositionUntil = SystemClock.elapsedRealtime() + LOAD_SETTLE_MS;
        StreamInfo info = StreamCapture.get(videoId);
        // Otherwise loaded once the streams are captured.
        if (info != null) load(info);
    }

    private void load(StreamInfo info) {
        if (info == null || stopped) return;
        if (info.videoId.equals(loadedVideoId) || info.videoId.equals(loadingVideoId)) return;
        loadingVideoId = info.videoId;
        renderer.execute(() -> {
            try {
                if (stopped || baseUrl == null) return;
                StreamProxy.Media media = StreamProxy.choose(info, device, audioOnly);
                if (media == null) {
                    toast(DlnaCast.t("Kein passendes Format für dieses Gerät", "No suitable format for this device"));
                    return;
                }
                StreamProxy.register(info);
                MediaController c = controller;
                Track track = Track.from(info, c == null ? null : c.getMetadata());
                String art = StreamProxy.registerArt(track, baseUrl);
                String url = baseUrl + media.path;
                Log.i("Loading " + info.videoId + " (" + media.description + ") \"" + track.title
                        + "\" by \"" + track.artist + "\" on " + device + ": " + url);

                ignorePositionUntil = SystemClock.elapsedRealtime() + LOAD_SETTLE_MS;
                ignoreRendererUntil = SystemClock.elapsedRealtime() + RENDERER_LOAD_SETTLE_MS;
                DlnaController.play(device, url, media.mimeType, track, art, media.video);
                loadedVideoId = info.videoId;

                // Continue where the phone is.
                long position = localPosition();
                if (position > SEEK_THRESHOLD_MS) DlnaController.seek(device, position);
                if (c != null && c.getPlaybackState() != null
                        && c.getPlaybackState().getState() == PlaybackState.STATE_PAUSED) {
                    DlnaController.pause(device);
                }
            } catch (Exception e) {
                Log.e("Loading " + info.videoId + " failed", e);
                toast(DlnaCast.t("Streamen fehlgeschlagen: ", "Cast failed: ") + e.getMessage());
            } finally {
                if (info.videoId.equals(loadingVideoId)) loadingVideoId = null;
            }
        });
    }

    // endregion

    private interface RendererCall {
        void run() throws Exception;
    }

    private void call(String action, RendererCall call) {
        if (stopped) return;
        ignoreRendererUntil = SystemClock.elapsedRealtime() + RENDERER_ECHO_MS;
        try {
            call.run();
        } catch (Exception e) {
            Log.i(action + " on renderer failed: " + e.getMessage());
        }
    }

    private void toast(String message) {
        main.post(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }
}
