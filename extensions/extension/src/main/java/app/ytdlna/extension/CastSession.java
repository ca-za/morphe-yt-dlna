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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
final class CastSession implements StreamCapture.Listener, MusicMode.Listener {
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
        CastUi.update();
    }

    static synchronized void stopActive(boolean stopRenderer) {
        if (active != null) {
            active.stop(stopRenderer);
            active = null;
        }
        CastUi.update();
    }

    final DlnaDevice device;
    private final Context context;
    private final String streamHost;
    private final boolean musicApp;
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
    private long lastQueueItemId = -1;
    /** A local pause is only forwarded if it is still there after this delay. */
    private static final long PAUSE_DEBOUNCE_MS = 1200;
    private long ignorePositionUntil;

    // Renderer side.
    /** OpenHome playlist mode: the whole queue is on the renderer. Otherwise single AVTransport URIs. */
    private volatile boolean openHome;
    private volatile String avTransportToken;
    private volatile String playlistToken;
    /** Renderer playlist: video id <-> OpenHome track id. Only used on the renderer thread. */
    private final Map<String, Integer> trackIdByVideo = new HashMap<>();
    private final Map<Integer, String> videoByTrackId = new HashMap<>();
    /** App queue: video id -> queue item id, for skipping the phone to a renderer track. */
    private final Map<String, Long> queueIdByVideo = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, QueueEntry> queueEntryByVideo = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile int rendererTrackId;
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
                Log.d("Session metadata: " + metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                        + " video=" + metadata.getLong("com.google.android.youtube.MEDIA_METADATA_VIDEO_WIDTH_PX")
                        + "x" + metadata.getLong("com.google.android.youtube.MEDIA_METADATA_VIDEO_HEIGHT_PX"));
            }
        }

        @Override
        public void onQueueChanged(List<MediaSession.QueueItem> queue) {
            if (openHome) {
                List<QueueEntry> entries = readQueue(queue);
                renderer.execute(() -> syncPlaylist(entries));
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
        this.musicApp = context.getPackageName().contains("music");
    }

    /** YouTube Music in song mode: audio only. Video mode and YouTube: video. */
    private boolean audioOnly() {
        return musicApp && MusicMode.isAudio();
    }

    private void begin(StreamInfo first) {
        Log.i("Cast session started on " + device);
        StreamCapture.addListener(this);
        if (musicApp) MusicMode.setListener(this);
        renderer.execute(() -> {
            try {
                DlnaController.loadSinkProtocols(device);
                int port = StreamProxy.start(context);
                String host = streamHost.isEmpty() ? StreamProxy.localAddressFor(device) : streamHost;
                baseUrl = "http://" + host + ":" + port;
                openHome = device.hasOpenHomePlaylist() && StreamProxy.isNative();
                Log.i("Using " + (openHome ? "OpenHome playlist" : "AVTransport") + " on " + device);
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
        if (musicApp) MusicMode.setListener(null);
        for (String token : new String[]{avTransportToken, playlistToken}) {
            if (token != null) new Thread(() -> NativeProxy.nativeUnsubscribe(token), "YtDlna-unsubscribe").start();
        }
        avTransportToken = null;
        playlistToken = null;
        main.post(() -> {
            if (controller != null) controller.unregisterCallback(callback);
            restoreLocalVolume();
        });
        if (stopRenderer) {
            renderer.execute(() -> {
                try {
                    rendererStop();
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
        if (openHome && loadedVideoId != null) {
            List<QueueEntry> entries = readQueue(controller.getQueue());
            renderer.execute(() -> syncPlaylist(entries));
        }
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
                renderer.execute(() -> call("Seek", () -> rendererSeek(position)));
            }
        }
        if (newState != lastState) {
            if (newState == PlaybackState.STATE_PLAYING && lastState != -1) {
                renderer.execute(() -> call("Play", this::rendererResume));
            } else if (newState == PlaybackState.STATE_PAUSED && now > ignorePositionUntil) {
                // Switching videos also reports "paused" for a moment: another queue item
                // or the position jumping back to the start means a skip, not a pause.
                long queueItem = state.getActiveQueueItemId();
                boolean skipping = (lastQueueItemId != -1 && queueItem != lastQueueItemId)
                        || (state.getPosition() < 1500 && lastPosition > 5000);
                String video = StreamCapture.currentVideoId();
                if (!skipping) {
                    main.postDelayed(() -> {
                        MediaController c = controller;
                        PlaybackState current = c == null ? null : c.getPlaybackState();
                        if (stopped || current == null || current.getState() != PlaybackState.STATE_PAUSED
                                || current.getActiveQueueItemId() != queueItem
                                || video == null || !video.equals(StreamCapture.currentVideoId())) return;
                        renderer.execute(() -> call("Pause", this::rendererPause));
                    }, PAUSE_DEBOUNCE_MS);
                }
            }
        }
        rememberState(state);
    }

    private void rememberState(PlaybackState state) {
        lastState = state.getState();
        lastPosition = state.getPosition();
        lastUpdateTime = state.getLastPositionUpdateTime();
        lastSpeed = state.getPlaybackSpeed();
        lastQueueItemId = state.getActiveQueueItemId();
    }

    // endregion

    // region Renderer events

    /** Subscribes to renderer events through the native proxy, or polls the renderer. */
    private void startRendererEvents() {
        if (openHome) {
            if (device.playlistEventUrl != null) {
                playlistToken = NativeProxy.nativeSubscribe(device.playlistEventUrl, baseUrl);
            }
        } else if (StreamProxy.isNative() && device.avTransportEventUrl != null) {
            avTransportToken = NativeProxy.nativeSubscribe(device.avTransportEventUrl, baseUrl);
        }
        if (avTransportToken != null || playlistToken != null) {
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
            if (parts.length < 3) continue;
            String token = parts[0];
            String name = parts[1];
            String value = parts[2];
            if (token.isEmpty()) {
                // From the proxy itself.
                if (name.equals("NeedVideo")) main.post(() -> onRendererWantsVideo(value));
                continue;
            }
            if (token.equals(playlistToken)) {
                if (name.equals("TransportState")) {
                    String state = OpenHome.toAvTransportState(value);
                    main.post(() -> onRendererEvent("TransportState", state));
                } else if (name.equals("Id")) {
                    main.post(() -> onRendererTrack(value));
                }
                continue;
            }
            if (!token.equals(avTransportToken)) continue;
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
                String state = openHome ? "" : DlnaController.transportState(device);
                if (state.isEmpty()) return;
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
                long remote = rendererPosition();
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

    // region Song/video mode

    /** YouTube Music switched between song and video: reload the title in the other format. */
    @Override
    public void onMusicModeChanged(boolean audio) {
        main.post(() -> {
            String videoId = loadedVideoId;
            StreamInfo info = videoId == null ? null : StreamCapture.get(videoId);
            if (stopped || info == null) return;
            Log.i("Switching the renderer to " + (audio ? "audio" : "video"));
            // Reload as a new title: the playlist is rebuilt with the new format and the
            // renderer continues at the phone's position.
            loadedVideoId = null;
            load(info);
        });
    }

    // endregion

    // region Streams

    @Override
    public void onStreams(StreamInfo info) {
        if (info.videoId.equals(StreamCapture.currentVideoId())) {
            load(info);
        } else if (openHome && queueIdByVideo.containsKey(info.videoId)) {
            // Preloaded by the app (usually the next title): ready before the renderer asks.
            renderer.execute(() -> registerAuto(info));
        }
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
                StreamProxy.Media media = StreamProxy.choose(info, device, audioOnly());
                if (media == null) {
                    toast(DlnaCast.t("Kein passendes Format für dieses Gerät", "No suitable format for this device"));
                    return;
                }
                StreamProxy.register(info);
                MediaController c = controller;
                Track track = Track.from(info, c == null ? null : c.getMetadata());
                QueueEntry entry = queueEntryByVideo.get(info.videoId);
                if (track.artist.isEmpty() && entry != null) track.artist = entry.artist;
                String art = coverFor(track, true);
                String url = baseUrl + media.path;
                Log.i("Loading " + info.videoId + " (" + media.description + ") \"" + track.title
                        + "\" by \"" + track.artist + "\" on " + device + ": " + url);

                ignorePositionUntil = SystemClock.elapsedRealtime() + LOAD_SETTLE_MS;
                ignoreRendererUntil = SystemClock.elapsedRealtime() + RENDERER_LOAD_SETTLE_MS;
                if (openHome) {
                    registerAuto(info);
                    if (loadedVideoId == null) startPlaylist(track, art);
                    int trackId = ensureTrack(info.videoId, track, art);
                    if (trackId != rendererTrackId) {
                        OpenHome.seekId(device, trackId);
                        rendererTrackId = trackId;
                    }
                    OpenHome.play(device);
                } else {
                    DlnaController.play(device, url, media.mimeType, track, art, media.video);
                }
                loadedVideoId = info.videoId;
                CastUi.onTitle(track.artist.isEmpty() ? track.title : track.title + " \u00B7 " + track.artist);

                // Continue where the phone is.
                long position = localPosition();
                if (position > SEEK_THRESHOLD_MS) rendererSeek(position);
                // The phone reports "paused" for a moment while switching videos, so only
                // mirror a pause that is still there once the switch has settled.
                main.postDelayed(() -> {
                    MediaController now = controller;
                    PlaybackState state = now == null ? null : now.getPlaybackState();
                    if (!stopped && state != null && state.getState() == PlaybackState.STATE_PAUSED
                            && info.videoId.equals(loadedVideoId)) {
                        renderer.execute(() -> call("Pause", this::rendererPause));
                    }
                }, LOAD_SETTLE_MS);
            } catch (Exception e) {
                Log.e("Loading " + info.videoId + " failed", e);
                toast(DlnaCast.t("Streamen fehlgeschlagen: ", "Cast failed: ") + e.getMessage());
            } finally {
                if (info.videoId.equals(loadingVideoId)) loadingVideoId = null;
            }
        });
    }

    // endregion

    // region OpenHome playlist

    /** One entry of the app's queue. */
    private static final class QueueEntry {
        final long queueId;
        final String videoId;
        final String title;
        final String artist;
        final String iconUrl;

        QueueEntry(long queueId, String videoId, String title, String artist, String iconUrl) {
            this.queueId = queueId;
            this.videoId = videoId;
            this.title = title;
            this.artist = artist;
            this.iconUrl = iconUrl;
        }
    }

    private static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern THUMBNAIL_VIDEO_ID = Pattern.compile("/vi(?:_webp)?/([A-Za-z0-9_-]{11})/");

    /** Reads the app's queue. The video id is the media id, or part of the thumbnail URL. */
    private List<QueueEntry> readQueue(List<MediaSession.QueueItem> queue) {
        List<QueueEntry> entries = new ArrayList<>();
        if (queue == null) return entries;
        for (MediaSession.QueueItem item : queue) {
            android.media.MediaDescription description = item.getDescription();
            String mediaId = description.getMediaId();
            String icon = description.getIconUri() == null ? "" : description.getIconUri().toString();
            String videoId = null;
            if (mediaId != null && VIDEO_ID.matcher(mediaId).matches()) {
                videoId = mediaId;
            } else {
                Matcher m = THUMBNAIL_VIDEO_ID.matcher(icon);
                if (m.find()) videoId = m.group(1);
            }
            if (videoId == null) continue;
            entries.add(new QueueEntry(item.getQueueId(), videoId,
                    description.getTitle() == null ? "" : description.getTitle().toString(),
                    description.getSubtitle() == null ? "" : description.getSubtitle().toString(),
                    icon));
            queueIdByVideo.put(videoId, item.getQueueId());
            queueEntryByVideo.put(videoId, entries.get(entries.size() - 1));
        }
        return entries;
    }

    /** Clears the renderer playlist and fills it with the app's queue. */
    private void startPlaylist(Track current, String currentArt) throws Exception {
        OpenHome.selectPlaylistSource(device);
        OpenHome.deleteAll(device);
        trackIdByVideo.clear();
        videoByTrackId.clear();
        rendererTrackId = 0;
        MediaController c = controller;
        List<QueueEntry> entries = readQueue(c == null ? null : c.getQueue());
        if (entries.isEmpty()) {
            ensureTrack(current.videoId, current, currentArt);
        } else {
            syncPlaylist(entries);
        }
        Log.i("Renderer playlist has " + trackIdByVideo.size() + " tracks");
    }

    /** Adds queue entries that are not on the renderer yet, each after its predecessor. */
    private void syncPlaylist(List<QueueEntry> entries) {
        if (stopped || !openHome || loadedVideoId == null && trackIdByVideo.isEmpty() && entries.isEmpty()) return;
        if (albumArtLookup()) {
            // Look up the covers of the new entries in parallel before adding them.
            List<String[]> missing = new ArrayList<>();
            for (QueueEntry entry : entries) {
                if (!trackIdByVideo.containsKey(entry.videoId)) missing.add(new String[]{entry.title, entry.artist});
            }
            AlbumArt.prefetch(missing, 4000);
        }
        int after = 0;
        for (QueueEntry entry : entries) {
            Integer existing = trackIdByVideo.get(entry.videoId);
            if (existing == null) {
                Track track = new Track(entry.videoId);
                track.title = entry.title;
                track.artist = entry.artist;
                track.artUrl = entry.iconUrl.startsWith("http") ? entry.iconUrl
                        : "https://i.ytimg.com/vi/" + entry.videoId + "/hqdefault.jpg";
                StreamInfo captured = StreamCapture.get(entry.videoId);
                if (captured != null) registerAuto(captured);
                try {
                    existing = insertTrack(after, track, coverFor(track, false));
                } catch (Exception e) {
                    Log.i("Adding " + entry.videoId + " to the renderer playlist failed: " + e.getMessage());
                    continue;
                }
            }
            after = existing;
        }
    }

    /** Track id of a video on the renderer, inserting it after the current track if needed. */
    private int ensureTrack(String videoId, Track track, String art) throws Exception {
        Integer id = trackIdByVideo.get(videoId);
        if (id != null) return id;
        return insertTrack(rendererTrackId, track, art);
    }

    private int insertTrack(int afterId, Track track, String art) throws Exception {
        String uri = baseUrl + "/v/" + track.videoId + "/auto";
        boolean audioOnly = audioOnly();
        String mime = audioOnly ? "audio/mp4"
                : device.supports("application/dash+xml") ? "application/dash+xml" : "video/mp4";
        int id = OpenHome.insert(device, afterId, uri, DlnaController.didl(uri, mime, track, art, !audioOnly));
        trackIdByVideo.put(track.videoId, id);
        videoByTrackId.put(id, track.videoId);
        return id;
    }

    /** Makes {@code /v/<videoId>/auto} serve the video's media, as used by playlist entries. */
    private void registerAuto(StreamInfo info) {
        if (!StreamProxy.isNative() || baseUrl == null) return;
        StreamProxy.Media media = StreamProxy.choose(info, device, audioOnly());
        if (media == null) return;
        StreamProxy.register(info);
        StreamProxy.alias(info, media, "/v/" + info.videoId + "/auto");
    }

    /** The renderer moved to another playlist track (by itself or by its remote). */
    private void onRendererTrack(String value) {
        int trackId;
        try {
            trackId = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (trackId == rendererTrackId) return;
        if (SystemClock.elapsedRealtime() < ignoreRendererUntil) {
            // Echo of our own SeekId or a late event from before it.
            Log.d("Ignoring renderer track " + trackId + " right after our own change");
            return;
        }
        rendererTrackId = trackId;
        String videoId = videoByTrackId.get(trackId);
        Log.d("Renderer track " + trackId + " = " + videoId);
        if (videoId != null) followRenderer(videoId);
    }

    /** The renderer requested a playlist track whose streams are not loaded yet. */
    private void onRendererWantsVideo(String videoId) {
        String current = videoByTrackId.get(rendererTrackId);
        if (current != null && !current.equals(videoId)) {
            // Preloading the next track; the phone follows once the renderer switches to it.
            Log.d("Renderer preloads " + videoId);
            return;
        }
        followRenderer(videoId);
    }

    /** Moves the phone to the video the renderer plays, so the app loads its streams. */
    private void followRenderer(String videoId) {
        if (videoId.equals(StreamCapture.currentVideoId())) return;
        Long queueId = queueIdByVideo.get(videoId);
        MediaController c = controller;
        if (queueId == null || c == null) return;
        Log.i("Phone follows the renderer to " + videoId);
        ignorePositionUntil = SystemClock.elapsedRealtime() + LOAD_SETTLE_MS;
        c.getTransportControls().skipToQueueItem(queueId);
    }

    // endregion

    // region Cover

    private boolean albumArtLookup() {
        return musicApp && AlbumArt.isEnabled(context);
    }

    /**
     * Cover for the renderer: the album cover from a music database (linked directly) if enabled
     * and found, otherwise the YouTube thumbnail through the proxy.
     *
     * @param lookup Look the cover up now; otherwise only an already found cover is used.
     */
    private String coverFor(Track track, boolean lookup) {
        if (albumArtLookup()) {
            String cover = lookup ? AlbumArt.lookup(track.title, track.artist)
                    : AlbumArt.cached(track.title, track.artist);
            if (cover != null) {
                track.artUrl = cover;
                return cover;
            }
        }
        return StreamProxy.registerArt(track, baseUrl);
    }

    // endregion

    // region Volume

    private int rendererVolume = -1;

    /** Changes the renderer volume by {@code delta} and reports the new value. */
    void adjustVolume(int delta, java.util.function.IntConsumer onVolume) {
        if (stopped) return;
        renderer.execute(() -> {
            try {
                if (rendererVolume < 0) rendererVolume = DlnaController.volume(device);
                if (rendererVolume < 0) return;
                rendererVolume = Math.max(0, Math.min(100, rendererVolume + delta));
                DlnaController.setVolume(device, rendererVolume);
                onVolume.accept(rendererVolume);
            } catch (Exception e) {
                Log.i("Volume change failed: " + e.getMessage());
            }
        });
    }

    // endregion

    // region Renderer transport

    private void rendererResume() throws Exception {
        if (openHome) OpenHome.play(device);
        else DlnaController.resume(device);
    }

    private void rendererPause() throws Exception {
        if (openHome) OpenHome.pause(device);
        else DlnaController.pause(device);
    }

    private void rendererStop() throws Exception {
        if (openHome) OpenHome.stop(device);
        else DlnaController.stop(device);
    }

    private void rendererSeek(long positionMs) throws Exception {
        if (openHome) OpenHome.seekSeconds(device, positionMs);
        else DlnaController.seek(device, positionMs);
    }

    private long rendererPosition() throws Exception {
        return openHome ? OpenHome.position(device) : DlnaController.position(device);
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
