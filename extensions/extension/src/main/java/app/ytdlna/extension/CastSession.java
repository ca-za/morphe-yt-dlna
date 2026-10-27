package app.ytdlna.extension;

import android.content.Context;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

/**
 * An active DLNA cast: keeps the app's player ({@link LocalPlayer}) and the TV ({@link Renderer})
 * in step.
 * <p>
 * The app's player keeps running, muted and with the smallest streams, as the remote control.
 * New titles, play, pause and seeks on the phone go to the TV; pause, play, stop and title changes
 * on the TV come back to the phone. The TV streams through the native proxy.
 */
final class CastSession implements StreamCapture.Listener, MusicMode.Listener,
        LocalPlayer.Listener, Renderer.Listener {
    /** Position differences below this are treated as normal drift, not as a seek. */
    private static final long SEEK_THRESHOLD_MS = 3000;
    /** After loading a title, position changes on the phone are ignored for this long. */
    private static final long LOAD_SETTLE_MS = 2500;
    /** After loading a title, renderer events are ignored for this long. */
    private static final long RENDERER_LOAD_SETTLE_MS = 5000;
    /** A local pause is only forwarded if it is still there after this delay. */
    private static final long PAUSE_DEBOUNCE_MS = 1200;
    /** Larger differences between phone and renderer position are corrected on the phone. */
    private static final long DRIFT_THRESHOLD_MS = 4000;
    /** After a change on the TV, the phone finishing buffering does not resume the TV. */
    private static final long RENDERER_PAUSE_WINS_MS = 10_000;
    private static final long SYNC_INTERVAL_MS = 10_000;
    /** Without renderer events, polling is the only way to notice changes on the TV. */
    private static final long POLL_INTERVAL_MS = 2000;
    /**
     * With events, the state is only checked after our own commands: some renderers skip events
     * after quick state changes (a TV pause right after our play). Checking only then, plus with
     * the position sync, keeps the phone idle otherwise.
     */
    private static final long[] CONFIRM_POLL_MS = {1500, 4000};
    /** Renderer events are not available (or lost): polling is the only source. */
    private volatile boolean pollOnly;
    /** The polling loop runs (main thread only). */
    private boolean polling;
    /** Newer commands replace the pending checks of older ones (main thread only). */
    private int confirmGeneration;
    /** When the last renderer state (event or poll) was handled. */
    private volatile long lastStateAt;
    /** When the renderer last changed its state on its own (its remote, not our command). */
    private volatile long rendererChangedAt;

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
    private final Covers covers;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** All renderer calls run in order on this thread. */
    private final ExecutorService rendererThread = Executors.newSingleThreadExecutor();
    private final LocalPlayer local;

    private volatile Renderer renderer;
    private volatile String loadedVideoId;
    private volatile String loadingVideoId;
    private volatile boolean stopped;
    private volatile String rendererState = "";
    private int rendererVolume = -1;

    // Last local playback state, for telling seeks, pauses and skips apart.
    private int lastState = -1;
    private long lastPosition;
    private long lastUpdateTime;
    private float lastSpeed;
    private long lastQueueItemId = -1;
    private volatile long ignorePositionUntil;
    /** Pauses before this are the player's reaction to our correction seek, not the user's. */
    private volatile long seekPauseUntil;
    /** A correction seek is settling (main thread only). */
    private boolean correcting;
    /** A player pausing because of a seek does so right away. */
    private static final long SEEK_PAUSE_MS = 700;
    /** When the phone last went to paused, 0 if it is not paused. */
    private volatile long localPausedSince;
    /** A phone pause longer than this without the renderer pausing was not made by the user. */
    private static final long SELF_PAUSE_MS = PAUSE_DEBOUNCE_MS + 3000;

    private CastSession(Context context, DlnaDevice device, String streamHost) {
        this.context = context;
        this.device = device;
        this.streamHost = streamHost == null ? "" : streamHost;
        this.musicApp = context.getPackageName().contains("music");
        Track.musicApp = musicApp;
        this.covers = new Covers(context, musicApp);
        this.local = new LocalPlayer(context, main, this);
    }

    /** YouTube Music in song mode: audio only. Video mode and YouTube: video. */
    private boolean audioOnly() {
        return musicApp && MusicMode.isAudio();
    }

    private void begin(StreamInfo first) {
        Log.i("Cast session started on " + device);
        StreamCapture.addListener(this);
        if (musicApp) {
            MusicMode.setListener(this);
        } else {
            // YouTube has no queue to reload the running video with: lower its quality instead.
            main.post(LocalQuality::lower);
            main.postDelayed(() -> {
                if (!stopped) LocalQuality.lower();
            }, 3000);
        }
        // Nothing plays on the phone while casting.
        CastAudio.setMuted(true);
        rendererThread.execute(() -> {
            try {
                DlnaController.loadSinkProtocols(device);
                int port = StreamProxy.start(context);
                String host = streamHost.isEmpty() ? StreamProxy.localAddressFor(device) : streamHost;
                renderer = Renderer.create(device, "http://" + host + ":" + port, this, main, this::audioOnly, covers);
                Log.i("Using " + renderer.getClass().getSimpleName() + " on " + device);
                startEvents();
            } catch (Exception e) {
                Log.e("Could not start casting", e);
                toast(DlnaCast.t("Streamen fehlgeschlagen: ", "Cast failed: ") + e.getMessage());
                main.post(() -> stopActive(false));
            }
        });
        // Attach right away (on the main thread already), so the reload and its position are
        // known before the first title is loaded on the renderer.
        local.attach(() -> {
            // The running title still plays with all streams: reload it with the reduced ones.
            local.reload();
            PlaybackState state = local.state();
            if (state != null) rememberState(state);
        });
        load(first);
    }

    void stop(boolean stopRenderer) {
        if (stopped) return;
        stopped = true;
        Log.i("Cast session stopped");
        StreamCapture.removeListener(this);
        if (musicApp) MusicMode.setListener(null);
        local.detach();
        main.post(() -> {
            if (!musicApp) LocalQuality.restore();
            // Back to the full streams on the phone; unmute once the reload passed the
            // neighbouring title.
            boolean reloading = local.reload();
            main.postDelayed(() -> {
                if (CastSession.active() == null) CastAudio.setMuted(false);
            }, reloading ? 2500 : 0);
        });
        rendererThread.execute(() -> {
            Renderer r = renderer;
            if (r == null) return;
            r.unsubscribe();
            if (stopRenderer) call("Stop", r::stop, "STOPPED");
        });
        rendererThread.shutdown();
    }

    // region Phone -> renderer

    @Override
    public void onLocalState(PlaybackState state) {
        if (stopped) return;
        if (local.isReloading()) {
            // The phone is reloading its title; nothing of this is a user action.
            rememberState(state);
            return;
        }
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
                onRenderer("Seek", r -> r.seek(position, local.isPlaying()), "PLAYING", "PAUSED_PLAYBACK");
            }
        }
        if (newState != lastState) {
            boolean resumeIntent = newState == PlaybackState.STATE_PLAYING
                    // Play pressed while paused: the player buffers first. Resume the renderer
                    // right away, so it does not depend on the phone's own (reduced) stream.
                    || (newState == PlaybackState.STATE_BUFFERING && lastState == PlaybackState.STATE_PAUSED);
            // The end of buffering is no play by the user: not when the renderer plays anyway,
            // and not right after a pause on the TV (it would undo that pause).
            boolean bufferingEnded = lastState == PlaybackState.STATE_BUFFERING
                    && newState == PlaybackState.STATE_PLAYING
                    && ("PLAYING".equals(rendererState)
                    || now - rendererChangedAt < RENDERER_PAUSE_WINS_MS);
            if (resumeIntent && lastState != -1 && lastState != PlaybackState.STATE_PLAYING && !bufferingEnded) {
                onRenderer("Play", Renderer::resume, "PLAYING");
            } else if (newState == PlaybackState.STATE_PAUSED && !(correcting && now <= seekPauseUntil)) {
                // Switching titles also reports "paused" for a moment: another queue item or
                // the position jumping back to the start means a skip, not a pause.
                long queueItem = state.getActiveQueueItemId();
                boolean skipping = (lastQueueItemId != -1 && queueItem != lastQueueItemId)
                        || (state.getPosition() < 1500 && lastPosition > 5000);
                String video = StreamCapture.currentVideoId();
                if (!skipping) {
                    // While a title loads or settles, pauses come and go: check once it settled.
                    long delay = Math.max(PAUSE_DEBOUNCE_MS, ignorePositionUntil - now + PAUSE_DEBOUNCE_MS);
                    main.postDelayed(() -> {
                        PlaybackState current = local.state();
                        // The TV changed its state since: its command is newer and wins.
                        if (stopped || local.isReloading() || "PAUSED_PLAYBACK".equals(rendererState)
                                || rendererChangedAt > now
                                || current == null || current.getState() != PlaybackState.STATE_PAUSED
                                || current.getActiveQueueItemId() != queueItem
                                || video == null || !video.equals(StreamCapture.currentVideoId())) return;
                        Log.d("Local pause, mirrored to the renderer");
                        onRenderer("Pause", Renderer::pause, "PAUSED_PLAYBACK");
                    }, delay);
                }
            }
        }
        rememberState(state);
    }

    private void rememberState(PlaybackState state) {
        if (state.getState() != lastState) {
            Log.d("Phone state " + lastState + " -> " + state.getState() + " at " + state.getPosition() + " ms");
        }
        if (state.getState() == PlaybackState.STATE_PAUSED) {
            if (lastState != PlaybackState.STATE_PAUSED) localPausedSince = SystemClock.elapsedRealtime();
        } else {
            localPausedSince = 0;
        }
        lastState = state.getState();
        lastPosition = state.getPosition();
        lastUpdateTime = state.getLastPositionUpdateTime();
        lastSpeed = state.getPlaybackSpeed();
        lastQueueItemId = state.getActiveQueueItemId();
    }

    @Override
    public void onQueue(List<LocalPlayer.QueueEntry> queue) {
        if (stopped) return;
        rendererThread.execute(() -> {
            Renderer r = renderer;
            // A few entries at a time; commands queued meanwhile run in between.
            if (!stopped && r != null && r.onQueue(queue)) onQueue(queue);
        });
    }

    // endregion

    // region Titles

    @Override
    public void onStreams(StreamInfo info) {
        if (local.isPassingThrough(info.videoId)) return;
        if (info.videoId.equals(StreamCapture.currentVideoId())) {
            load(info);
        } else {
            rendererThread.execute(() -> {
                Renderer r = renderer;
                if (!stopped && r != null) r.onStreams(info);
            });
        }
    }

    @Override
    public void onVideoLoaded(String videoId) {
        if (local.isPassingThrough(videoId)) return;
        ignorePositionUntil = Math.max(ignorePositionUntil, SystemClock.elapsedRealtime() + LOAD_SETTLE_MS);
        StreamInfo info = StreamCapture.get(videoId);
        // Otherwise loaded once the streams are captured.
        if (info != null) load(info);
    }

    /** YouTube Music switched between song and video: reload the title in the other format. */
    @Override
    public void onMusicModeChanged(boolean audio) {
        main.post(() -> {
            String videoId = loadedVideoId;
            StreamInfo info = videoId == null ? null : StreamCapture.get(videoId);
            if (stopped || info == null) return;
            Log.i("Switching the renderer to " + (audio ? "audio" : "video"));
            rendererThread.execute(() -> {
                // The playlist entries carry their format, so the playlist is built again.
                if (renderer instanceof OpenHomeRenderer) ((OpenHomeRenderer) renderer).restartPlaylist();
            });
            loadedVideoId = null;
            load(info);
        });
    }

    private void load(StreamInfo info) {
        if (info == null || stopped) return;
        if (info.videoId.equals(loadedVideoId) || info.videoId.equals(loadingVideoId)) return;
        loadingVideoId = info.videoId;
        rendererThread.execute(() -> {
            Renderer r = renderer;
            try {
                if (stopped || r == null) return;
                StreamProxy.Media media = StreamProxy.choose(info, device, audioOnly());
                if (media == null) {
                    toast(DlnaCast.t("Kein passendes Format für dieses Gerät", "No suitable format for this device"));
                    return;
                }
                StreamProxy.register(info);
                // Read the queue first: its entry has the artist when the media session has none yet.
                List<LocalPlayer.QueueEntry> queue = local.queue();
                Track track = Track.from(info, local.metadata());
                LocalPlayer.QueueEntry entry = local.entry(info.videoId);
                if (track.artist.isEmpty() && entry != null) track.artist = entry.artist;
                String art = covers.forTrack(track, true);
                Log.i("Loading " + info.videoId + " (" + media.description + ") \"" + track.title
                        + "\" by \"" + track.artist + "\" on " + device);

                ignorePositionUntil = SystemClock.elapsedRealtime() + LOAD_SETTLE_MS;
                r.expectEcho(RENDERER_LOAD_SETTLE_MS);
                r.load(new Renderer.Load(info, media, track, art, queue));
                loadedVideoId = info.videoId;
                // Checked once the load has settled (states before are ignored as its echo).
                main.postDelayed(this::confirmState, RENDERER_LOAD_SETTLE_MS);
                onQueue(queue);
                CastUi.onTitle(track.artist.isEmpty() ? track.title : track.title + " · " + track.artist,
                        track.artUrl);

                // Continue where the phone is.
                long position = local.positionOf(info.videoId);
                if (position > SEEK_THRESHOLD_MS) {
                    r.seek(position, local.stateCode() == PlaybackState.STATE_NONE || local.isPlaying());
                }
                // The phone reports "paused" for a moment while switching titles, so only
                // mirror a pause that is still there once the switch has settled.
                main.postDelayed(() -> mirrorPauseAfterLoad(info.videoId), LOAD_SETTLE_MS);
            } catch (Exception e) {
                Log.e("Loading " + info.videoId + " failed", e);
                toast(DlnaCast.t("Streamen fehlgeschlagen: ", "Cast failed: ") + e.getMessage());
            } finally {
                if (info.videoId.equals(loadingVideoId)) loadingVideoId = null;
            }
        });
    }

    /** A pause that is still there on the phone after loading a title (and any reload) is applied. */
    private void mirrorPauseAfterLoad(String videoId) {
        if (stopped || !videoId.equals(loadedVideoId)) return;
        long wait = local.reloadRemaining();
        if (wait > 0) {
            main.postDelayed(() -> mirrorPauseAfterLoad(videoId), wait + 500);
            return;
        }
        if (local.stateCode() == PlaybackState.STATE_PAUSED) {
            Log.d("Phone paused after loading " + videoId + ", pausing the renderer");
            onRenderer("Pause", Renderer::pause, "PAUSED_PLAYBACK");
        }
    }

    // endregion

    // region Renderer -> phone

    private void startEvents() {
        if (renderer.subscribe()) {
            Thread thread = new Thread(this::eventLoop, "YtDlna-events");
            thread.setDaemon(true);
            thread.start();
        } else {
            Log.i("Renderer events not available, polling the renderer");
            pollOnly = true;
            startPolling();
        }
        main.postDelayed(this::syncPosition, SYNC_INTERVAL_MS);
    }

    private void eventLoop() {
        while (!stopped) {
            try {
                String event = NativeProxy.nativeWaitEvent(1000);
                if (event == null) continue;
                String[] parts = event.split("\n", 3);
                Renderer r = renderer;
                if (parts.length < 3 || r == null) continue;
                boolean handled = r.handleEvent(parts[0], parts[1], parts[2]);
                if (!parts[1].equals("IdArray")) {
                    Log.d("Event " + parts[1] + "=" + parts[2] + (handled ? "" : " (not for this session)"));
                }
            } catch (Throwable t) {
                // Keep listening; one bad event must not end the renderer -> phone direction.
                Log.e("Handling a renderer event failed", t);
            }
        }
    }

    @Override
    public void onEventsLost() {
        Log.i("Renderer event subscription lost, polling instead");
        pollOnly = true;
        main.post(this::startPolling);
    }

    private void startPolling() {
        if (polling || stopped) return;
        polling = true;
        main.postDelayed(this::pollLoop, POLL_INTERVAL_MS);
    }

    private void pollLoop() {
        if (stopped) return;
        pollState();
        main.postDelayed(this::pollLoop, POLL_INTERVAL_MS);
    }

    /** After a command of ours: checks that the renderer's events did not miss a change. */
    private void confirmState() {
        if (pollOnly || stopped) return;
        int generation = ++confirmGeneration;
        for (long delay : CONFIRM_POLL_MS) {
            main.postDelayed(() -> {
                if (generation == confirmGeneration) pollState();
            }, delay);
        }
    }

    /** Asks the renderer for its transport state once. */
    private void pollState() {
        if (stopped) return;
        long started = SystemClock.elapsedRealtime();
        rendererThread.execute(() -> {
            try {
                String state = renderer.pollState();
                main.post(() -> {
                    // An event in the meantime is newer than the polled state.
                    if (state.isEmpty() || lastStateAt > started) return;
                    if (!pollOnly && !state.equals(rendererState)) {
                        Log.d("Polled state " + state + " differs from the events (" + rendererState + ")");
                    }
                    onTransportState(state);
                });
            } catch (Exception e) {
                Log.d("Polling renderer failed: " + e.getMessage());
            }
        });
    }

    @Override
    public void onTransportState(String value) {
        lastStateAt = SystemClock.elapsedRealtime();
        if (stopped || value.equals(rendererState)) return;
        String previous = rendererState;
        rendererState = value;
        Log.d("Renderer " + previous + " -> " + value);
        Renderer r = renderer;
        // Before our first title, states are the renderer's initial state (sent when subscribing).
        if (r == null || r.isEcho(value) || loadedVideoId == null) return;
        rendererChangedAt = SystemClock.elapsedRealtime();

        int localState = local.stateCode();
        switch (value) {
            case "PAUSED_PLAYBACK":
                // Also while the phone buffers: it would play on afterwards.
                if (localState == PlaybackState.STATE_PLAYING || localState == PlaybackState.STATE_BUFFERING
                        || localState == PlaybackState.STATE_CONNECTING) {
                    Log.i("Paused on the renderer");
                    local.pause();
                }
                break;
            case "PLAYING":
                if (localState == PlaybackState.STATE_PAUSED) {
                    Log.i("Resumed on the renderer");
                    local.play();
                }
                break;
            case "STOPPED":
                long duration = local.duration();
                if (duration <= 0 || duration - local.position() > 5000) {
                    // Stopped on the TV, not the end of the title: end the cast like YouTube does.
                    Log.i("Stopped on the renderer, ending the cast");
                    local.pause();
                    toast(DlnaCast.t("Wiedergabe auf ", "Playback on ") + device.friendlyName
                            + DlnaCast.t(" beendet", " ended"));
                    stopActive(false);
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void onRendererTitle(String videoId) {
        followRenderer(videoId);
    }

    @Override
    public void onRendererWantsTitle(String videoId) {
        followRenderer(videoId);
    }

    /** Moves the phone to the title the renderer plays, so the app loads its streams. */
    private void followRenderer(String videoId) {
        if (videoId.equals(StreamCapture.currentVideoId())) return;
        ignorePositionUntil = SystemClock.elapsedRealtime() + LOAD_SETTLE_MS;
        if (local.skipTo(videoId)) Log.i("Phone follows the renderer to " + videoId);
    }

    /**
     * Keeps the phone at the renderer's position and play state, since the renderer is what is
     * heard: the app can pause on its own (for example after a seek), which no user did.
     */
    private void syncPosition() {
        if (stopped) return;
        main.postDelayed(this::syncPosition, SYNC_INTERVAL_MS);
        // A rare check next to the events, for changes a renderer did not report.
        if (!pollOnly) pollState();
        long pausedSince = localPausedSince;
        if ("PLAYING".equals(rendererState) && local.stateCode() == PlaybackState.STATE_PAUSED
                && pausedSince > 0 && SystemClock.elapsedRealtime() - pausedSince > SELF_PAUSE_MS
                && SystemClock.elapsedRealtime() > ignorePositionUntil && !local.isReloading()) {
            // A pause by the user would have paused the renderer within the debounce time.
            Log.i("Phone paused on its own while the renderer plays, resuming it");
            local.play();
        }
        if (!"PLAYING".equals(rendererState) && !rendererState.isEmpty()) return;
        rendererThread.execute(() -> {
            try {
                if (loadedVideoId == null || !loadedVideoId.equals(StreamCapture.currentVideoId())) return;
                long remote = renderer.position();
                long phone = local.position();
                if (remote < 0 || SystemClock.elapsedRealtime() < ignorePositionUntil || local.isReloading()) return;
                if (Math.abs(remote - phone) > DRIFT_THRESHOLD_MS) {
                    Log.i("Correcting phone position " + phone + " -> " + remote + " ms");
                    main.post(() -> {
                        // Paused in the meantime: that pause wins, it is mirrored to the renderer.
                        if (stopped || !local.isPlaying()) return;
                        long seekAt = SystemClock.elapsedRealtime();
                        ignorePositionUntil = seekAt + LOAD_SETTLE_MS;
                        seekPauseUntil = seekAt + SEEK_PAUSE_MS;
                        correcting = true;
                        local.seekTo(remote);
                        // Some players pause right after a seek; keep it playing like the renderer.
                        // A later pause is the user's and stays.
                        main.postDelayed(() -> {
                            correcting = false;
                            long pauseStart = localPausedSince;
                            if (!stopped && local.stateCode() == PlaybackState.STATE_PAUSED
                                    && pauseStart > 0 && pauseStart <= seekAt + SEEK_PAUSE_MS) {
                                local.play();
                            }
                        }, 1500);
                    });
                }
            } catch (Exception e) {
                Log.d("Position sync failed: " + e.getMessage());
            }
        });
    }

    // endregion

    /** State for the regression tests. */
    org.json.JSONObject status() throws org.json.JSONException {
        Renderer r = renderer;
        return new org.json.JSONObject()
                .put("device", device.friendlyName)
                .put("renderer", r == null ? "" : r.getClass().getSimpleName())
                .put("loadedVideoId", loadedVideoId == null ? "" : loadedVideoId)
                .put("rendererState", rendererState)
                .put("audioOnly", audioOnly())
                .put("reloading", local.isReloading());
    }

    /** Changes the renderer volume by {@code delta} and reports the new value. */
    void adjustVolume(int delta, IntConsumer onVolume) {
        // Volume does not change the transport state: no echo to expect.
        onRenderer("Volume", null, r -> {
            if (rendererVolume < 0) rendererVolume = r.volume();
            if (rendererVolume < 0) return;
            rendererVolume = Math.max(0, Math.min(100, rendererVolume + delta));
            r.setVolume(rendererVolume);
            onVolume.accept(rendererVolume);
        });
    }

    private interface RendererCall {
        void run(Renderer renderer) throws Exception;
    }

    /**
     * Runs a command on the renderer thread. Renderer events that report {@code echoStates}
     * shortly after are treated as its echo.
     */
    private void onRenderer(String action, RendererCall call, String... echoStates) {
        onRenderer(action, echoStates, call);
    }

    private void onRenderer(String action, String[] echoStates, RendererCall call) {
        if (stopped) return;
        rendererThread.execute(() -> {
            Renderer r = renderer;
            if (r != null) call(action, () -> call.run(r), echoStates);
        });
    }

    private interface Call {
        void run() throws Exception;
    }

    private void call(String action, Call call, String... echoStates) {
        Renderer r = renderer;
        if (r != null && echoStates != null) r.expectEcho(echoStates);
        try {
            call.run();
        } catch (Exception e) {
            Log.i(action + " on renderer failed: " + e.getMessage());
        }
        // Transport commands (not volume): check the result in case the renderer skips its event.
        if (echoStates != null) main.post(this::confirmState);
    }

    private void toast(String message) {
        main.post(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }
}
