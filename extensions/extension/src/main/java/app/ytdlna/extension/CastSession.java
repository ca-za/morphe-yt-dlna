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

    private CastSession(Context context, DlnaDevice device, String streamHost) {
        this.context = context;
        this.device = device;
        this.streamHost = streamHost == null ? "" : streamHost;
        this.musicApp = context.getPackageName().contains("music");
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
            if (stopRenderer) call("Stop", r::stop);
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
                onRenderer("Seek", r -> r.seek(position, local.isPlaying()));
            }
        }
        if (newState != lastState) {
            boolean resumeIntent = newState == PlaybackState.STATE_PLAYING
                    // Play pressed while paused: the player buffers first. Resume the renderer
                    // right away, so it does not depend on the phone's own (reduced) stream.
                    || (newState == PlaybackState.STATE_BUFFERING && lastState == PlaybackState.STATE_PAUSED);
            if (resumeIntent && lastState != -1 && lastState != PlaybackState.STATE_PLAYING
                    && !(lastState == PlaybackState.STATE_BUFFERING && newState == PlaybackState.STATE_PLAYING
                    && "PLAYING".equals(rendererState))) {
                onRenderer("Play", Renderer::resume);
            } else if (newState == PlaybackState.STATE_PAUSED && now > ignorePositionUntil) {
                // Switching titles also reports "paused" for a moment: another queue item or
                // the position jumping back to the start means a skip, not a pause.
                long queueItem = state.getActiveQueueItemId();
                boolean skipping = (lastQueueItemId != -1 && queueItem != lastQueueItemId)
                        || (state.getPosition() < 1500 && lastPosition > 5000);
                String video = StreamCapture.currentVideoId();
                if (!skipping) {
                    main.postDelayed(() -> {
                        PlaybackState current = local.state();
                        if (stopped || current == null || current.getState() != PlaybackState.STATE_PAUSED
                                || current.getActiveQueueItemId() != queueItem
                                || video == null || !video.equals(StreamCapture.currentVideoId())) return;
                        Log.d("Local pause, mirrored to the renderer");
                        onRenderer("Pause", Renderer::pause);
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

    @Override
    public void onQueue(List<LocalPlayer.QueueEntry> queue) {
        rendererThread.execute(() -> {
            Renderer r = renderer;
            if (!stopped && r != null) r.onQueue(queue);
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
                Track track = Track.from(info, local.metadata());
                LocalPlayer.QueueEntry entry = local.entry(info.videoId);
                if (track.artist.isEmpty() && entry != null) track.artist = entry.artist;
                String art = covers.forTrack(track, true);
                Log.i("Loading " + info.videoId + " (" + media.description + ") \"" + track.title
                        + "\" by \"" + track.artist + "\" on " + device);

                ignorePositionUntil = SystemClock.elapsedRealtime() + LOAD_SETTLE_MS;
                r.expectEcho(RENDERER_LOAD_SETTLE_MS);
                r.load(new Renderer.Load(info, media, track, art, local.queue()));
                loadedVideoId = info.videoId;
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
            onRenderer("Pause", Renderer::pause);
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
            main.postDelayed(this::poll, POLL_INTERVAL_MS);
        }
        main.postDelayed(this::syncPosition, SYNC_INTERVAL_MS);
    }

    private void eventLoop() {
        while (!stopped) {
            String event = NativeProxy.nativeWaitEvent(1000);
            if (event == null) continue;
            String[] parts = event.split("\n", 3);
            Renderer r = renderer;
            if (parts.length == 3 && r != null) r.handleEvent(parts[0], parts[1], parts[2]);
        }
    }

    @Override
    public void onEventsLost() {
        Log.i("Renderer event subscription lost, polling instead");
        main.postDelayed(this::poll, POLL_INTERVAL_MS);
    }

    private void poll() {
        if (stopped) return;
        rendererThread.execute(() -> {
            try {
                String state = renderer.pollState();
                if (!state.isEmpty()) main.post(() -> onTransportState(state));
            } catch (Exception e) {
                Log.d("Polling renderer failed: " + e.getMessage());
            }
        });
        main.postDelayed(this::poll, POLL_INTERVAL_MS);
    }

    @Override
    public void onTransportState(String value) {
        if (stopped || value.equals(rendererState)) return;
        String previous = rendererState;
        rendererState = value;
        Log.d("Renderer " + previous + " -> " + value);
        Renderer r = renderer;
        if (r == null || r.isEcho()) return;

        int localState = local.stateCode();
        switch (value) {
            case "PAUSED_PLAYBACK":
                if (localState == PlaybackState.STATE_PLAYING) {
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

    /** Keeps the phone at the renderer's position, since the renderer is what is heard. */
    private void syncPosition() {
        if (stopped) return;
        main.postDelayed(this::syncPosition, SYNC_INTERVAL_MS);
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
                        ignorePositionUntil = SystemClock.elapsedRealtime() + LOAD_SETTLE_MS;
                        local.seekTo(remote);
                    });
                }
            } catch (Exception e) {
                Log.d("Position sync failed: " + e.getMessage());
            }
        });
    }

    // endregion

    /** Changes the renderer volume by {@code delta} and reports the new value. */
    void adjustVolume(int delta, IntConsumer onVolume) {
        onRenderer("Volume", r -> {
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

    /** Runs a command on the renderer thread; its events are treated as echo. */
    private void onRenderer(String action, RendererCall call) {
        if (stopped) return;
        rendererThread.execute(() -> {
            Renderer r = renderer;
            if (r != null) call(action, () -> call.run(r));
        });
    }

    private interface Call {
        void run() throws Exception;
    }

    private void call(String action, Call call) {
        Renderer r = renderer;
        if (r != null) r.expectEcho();
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
