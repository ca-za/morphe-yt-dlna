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
    /** A title loaded further in than this starts there on the renderer too. */
    private static final long START_POSITION_MS = 3000;
    /** How long a title with only SABR streams waits for direct ones. */
    private static final long SABR_WAIT_MS = 10_000;
    /** After loading a title or correcting the phone, its position is not corrected for this long. */
    private static final long LOAD_SETTLE_MS = 2500;
    /** After loading a title, renderer events are ignored for this long. */
    private static final long RENDERER_LOAD_SETTLE_MS = 5000;
    /** Larger differences between phone and renderer position are corrected on the phone. */
    private static final long DRIFT_THRESHOLD_MS = 4000;
    private static final long SYNC_INTERVAL_MS = 10_000;
    /** The phone's position is not corrected this close to the title's end. */
    private static final long END_MARGIN_MS = 8000;
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

    private static CastSession active;

    static synchronized CastSession active() {
        return active;
    }

    static synchronized void start(Context context, DlnaDevice device, StreamInfo first, String streamHost) {
        if (active != null) active.stop(false);
        active = new CastSession(context.getApplicationContext(), device, streamHost);
        active.begin(first);
        CastUi.update();
        // The cast button stays while casting, also without renderers found in the background.
        new Handler(Looper.getMainLooper()).post(DlnaCast::onRenderersChanged);
    }

    static synchronized void stopActive(boolean stopRenderer) {
        if (active != null) {
            active.stop(stopRenderer);
            active = null;
            StreamProxy.stop();
        }
        CastUi.update();
        new Handler(Looper.getMainLooper()).post(DlnaCast::onRenderersChanged);
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
    /** The renderer's state before it started buffering (TRANSITIONING). */
    private String stateBeforeTransition = "";
    /** The side player (Shorts, feed) in front, null for the main player. Main thread. */
    private volatile String sidePlayer;
    /** A title the renderer moved on to while a side player was in front. */
    private String pendingFollow;
    /** A title whose direct streams are awaited (only SABR ones so far), and one cast without. */
    private volatile String waitingForStreams, sabrFallbackFor;
    private int rendererVolume = -1;

    /** The phone's last playback state. */
    private int lastState = -1;
    /** The phone's position is not corrected before this (a title loads, a correction settles). */
    private volatile long ignorePositionUntil;
    /** The loaded title is a live stream. */
    private volatile boolean live;
    /** When the phone last went to paused, 0 if it is not paused. */
    private volatile long localPausedSince;
    /**
     * A phone pause longer than this while the renderer plays is the player's own (a user's
     * pause would have paused the renderer): the phone follows the renderer and plays on.
     */
    private static final long SELF_PAUSE_MS = 4000;

    // SponsorBlock diagnostics: how Morphe's skips on the phone reach the TV.
    /** How often the TV position is checked against the segments (debug log switch on). */
    private static final long SB_WATCH_MS = 1000;
    /** Morphe's segment array last seen, and its segments. Main thread. */
    private Object sbRaw;
    private List<SponsorBlock.Segment> sbSegments = java.util.Collections.emptyList();
    /** The auto-skip segment the TV is in, since when and from where. Main thread. */
    private SponsorBlock.Segment tvSegment;
    private long tvEnteredAt, tvEnteredPosition;
    private volatile boolean sbWatchQueued;
    /** Commands are held back until the renderer settled after a load. */
    private volatile long rendererSettleUntil;
    /** Only the latest held back command is sent (main thread). */
    private int deferredIntent;
    /** Length of the title the extras are published for. */
    private volatile long extrasDurationMs;
    /** The extras document is complete after this, with the segments Morphe has by then. */
    private static final long EXTRAS_TIMEOUT_MS = 15_000;

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
        Log.i("SponsorBlock " + (SponsorBlock.available() ? "is in the app" : "is not in the app")
                + ", debug log " + (DlnaCast.debugLogEnabled(context) ? "on" : "off"));
        main.postDelayed(this::watchSponsorBlock, SB_WATCH_MS);
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
            if (local.reload()) main.postDelayed(this::afterReload, local.reloadRemaining() + 300);
            PlaybackState state = local.state();
            if (state != null) rememberState(state);
        });
        load(first);
    }

    /**
     * Titles the phone passes while reloading are ignored. If the user skipped meanwhile (to
     * the same neighbouring title, say), the phone stays there: load what it plays now.
     */
    private void afterReload() {
        if (stopped) return;
        String current = StreamCapture.currentVideoId();
        if (current == null || current.equals(loadedVideoId) || current.equals(loadingVideoId)) return;
        Log.i("Phone moved to " + current + " while reloading, loading it");
        onVideoLoaded(current);
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

    /**
     * The phone's playback state. Only followed and logged: what reaches the renderer are the
     * commands given to the phone's player ({@link #onIntent}), not changes of its state, which
     * also come from buffering, loading, ads and the player itself.
     */
    @Override
    public void onLocalState(PlaybackState state) {
        if (stopped) return;
        if (DlnaCast.debugLogEnabled(context)) {
            // Raw media session state next to the player's own time: shows stale positions.
            Log.d("Phone session: state " + state.getState() + " at " + state.getPosition() + " ms (updated "
                    + (SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime()) + " ms ago, speed "
                    + state.getPlaybackSpeed() + "), player at " + MorpheState.videoTime() + " ms");
        }
        sidePlayerInFront();
        rememberState(state);
    }

    /**
     * A play, pause or seek the user (or SponsorBlock) gave the phone's player, see
     * {@link PlayerIntents}: sent to the renderer, whose state the phone then follows.
     * Main thread.
     */
    void onIntent(String kind, long positionMs, String caller) {
        if (stopped || loadedVideoId == null) return;
        if (sidePlayerInFront()) return;
        long settle = rendererSettleUntil - SystemClock.elapsedRealtime();
        if (settle > 0) {
            // The renderer is still loading the title: a command now can stop it (some report
            // STOPPED). The latest command goes once it has settled.
            int generation = ++deferredIntent;
            Log.d("Phone " + kind + " while the renderer loads, sent in " + settle + " ms");
            main.postDelayed(() -> {
                if (generation == deferredIntent) onIntent(kind, positionMs, caller);
            }, settle);
            return;
        }
        switch (kind) {
            case "seek":
                // A position only means something on the cast title, and not while the phone
                // passes through another title to reload its own.
                String video = StreamCapture.currentVideoId();
                if (live || local.isReloading() || !loadedVideoId.equals(video)) {
                    Log.d("Phone seek on " + video + (local.isReloading() ? " while reloading" : "")
                            + ", not sent to the renderer");
                    return;
                }
                SponsorBlock.Segment skipped = caller.contains("SponsorBlock") ? segmentEndingAt(positionMs) : null;
                if (skipped != null) logTvAt("when the phone skipped " + skipped, skipped);
                Log.d("Phone seek to " + positionMs + " ms by " + caller + ", sent to the renderer");
                // Until the renderer got there, its old position must not pull the phone back.
                ignorePositionUntil = SystemClock.elapsedRealtime() + RENDERER_LOAD_SETTLE_MS;
                boolean forward = skipped != null || caller.contains("SponsorBlock");
                onRenderer("Seek", r -> {
                    // A skip forward the TV is already past (it came late, held back while the
                    // TV loaded) would take it back.
                    if (forward) {
                        long tv = r.position();
                        if (tv >= positionMs - 500) {
                            Log.d("TV at " + tv + " ms, already past the skip to " + positionMs + " ms: not sent");
                            return;
                        }
                    }
                    r.seek(positionMs, !"PAUSED_PLAYBACK".equals(rendererState));
                }, "PLAYING", "PAUSED_PLAYBACK");
                if (skipped != null) {
                    Log.i("SponsorBlock skip on the phone: " + skipped + ", phone now at " + positionMs
                            + " ms, sent to the TV");
                    main.postDelayed(() -> logTvAt("3 s after the phone skipped " + skipped, skipped), 3000);
                }
                break;
            case "pause":
                Log.d("Phone pause by " + caller + ", sent to the renderer");
                onRenderer("Pause", Renderer::pause, "PAUSED_PLAYBACK");
                break;
            case "play":
                Log.d("Phone play by " + caller + ", sent to the renderer");
                onRenderer("Play", Renderer::resume, "PLAYING");
                break;
            default:
                break;
        }
    }

    private void rememberState(PlaybackState state) {
        if (state.getState() != lastState) {
            Log.d("Phone state " + lastState + " -> " + state.getState() + " at " + state.getPosition() + " ms "
                    + MorpheState.summary());
        }
        if (state.getState() == PlaybackState.STATE_PAUSED) {
            if (lastState != PlaybackState.STATE_PAUSED) localPausedSince = SystemClock.elapsedRealtime();
        } else {
            localPausedSince = 0;
        }
        lastState = state.getState();
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
        if (info.isSabrOnly() && !info.videoId.equals(sabrFallbackFor)) {
            // The full streams usually follow within seconds: wait for them rather than casting
            // the 360p fallback. Without them, the fallback after all.
            if (!info.videoId.equals(waitingForStreams)) {
                waitingForStreams = info.videoId;
                Log.d("Only SABR streams for " + info.videoId + " yet, waiting for direct ones");
                main.postDelayed(() -> {
                    if (stopped || !info.videoId.equals(waitingForStreams)) return;
                    waitingForStreams = null;
                    sabrFallbackFor = info.videoId;
                    if (!info.videoId.equals(StreamCapture.currentVideoId())) return;
                    StreamInfo latest = StreamCapture.get(info.videoId);
                    Log.d("No direct streams for " + info.videoId + ", casting what there is");
                    load(latest != null ? latest : info);
                }, SABR_WAIT_MS);
            }
            return;
        }
        if (info.videoId.equals(waitingForStreams)) waitingForStreams = null;
        loadingVideoId = info.videoId;
        rendererThread.execute(() -> {
            Renderer r = renderer;
            try {
                if (stopped || r == null) return;
                StreamProxy.Media media = StreamProxy.choose(info, device, audioOnly());
                if (media == null) {
                    toast(info.isLive()
                            ? DlnaCast.t("Livestreams braucht ein Gerät mit HLS", "Live streams need a device with HLS")
                            : DlnaCast.t("Kein passendes Format für dieses Gerät", "No suitable format for this device"));
                    return;
                }
                // Live: no common timeline of phone and renderer, so positions are not synced.
                live = info.isLive();
                StreamProxy.register(info);
                // Read the queue first: its entry has the artist when the media session has none yet.
                List<LocalPlayer.QueueEntry> queue = local.queue();
                Track track = Track.from(info, local.metadata());
                LocalPlayer.QueueEntry entry = local.entry(info.videoId);
                if (track.artist.isEmpty() && entry != null) track.artist = entry.artist;
                String art = covers.forTrack(track, true);
                RendererExtras.baseUrl = r.baseUrl;
                if (Captions.enabled(context) && !audioOnly()) addCaptions(info.videoId, track, r.baseUrl);
                if (RendererExtras.wanted(device)) {
                    // Published before the load, so the renderer finds it right away.
                    track.extrasUrl = r.baseUrl + RendererExtras.path(info.videoId);
                    extrasDurationMs = track.durationMs;
                    if (!RendererExtras.publish(info.videoId, track.durationMs, false)) {
                        main.postDelayed(() -> {
                            if (!stopped && info.videoId.equals(loadedVideoId)) {
                                RendererExtras.publish(info.videoId, extrasDurationMs, true);
                            }
                        }, EXTRAS_TIMEOUT_MS);
                    }
                }
                String language = info.audioTrackName();
                Log.i("Loading " + info.videoId + " (" + media.description
                        + (language.isEmpty() ? "" : ", audio " + language) + ") \"" + track.title
                        + "\" by \"" + track.artist + "\" on " + device);

                ignorePositionUntil = SystemClock.elapsedRealtime() + LOAD_SETTLE_MS;
                r.expectEcho(RENDERER_LOAD_SETTLE_MS);
                rendererSettleUntil = SystemClock.elapsedRealtime() + RENDERER_LOAD_SETTLE_MS;
                r.load(new Renderer.Load(info, media, track, art, queue));
                loadedVideoId = info.videoId;
                // Checked once the load has settled (states before are ignored as its echo).
                main.postDelayed(this::confirmState, RENDERER_LOAD_SETTLE_MS);
                onQueue(queue);
                CastUi.onTitle(track.artist.isEmpty() ? track.title : track.title + " · " + track.artist,
                        track.artUrl);

                // Continue where the phone is.
                long position = local.positionOf(info.videoId);
                if (!live && position > START_POSITION_MS) {
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

    /**
     * Captions for renderers that take them: an extra SRT or WebVTT resource (when the renderer
     * lists the format), Samsung's caption info, and all tracks in the extras. Renderer thread.
     */
    private void addCaptions(String videoId, Track track, String baseUrl) {
        boolean srt = device.lists(Captions.SRT);
        boolean vtt = device.lists(Captions.VTT);
        boolean samsung = device.isSamsung();
        boolean extras = RendererExtras.wanted(device);
        if (!srt && !vtt && !samsung && !extras) return;
        Captions.Track pick = Captions.prepare(videoId, () -> {
            // More tracks are ready: the extras list them.
            if (extras && videoId.equals(loadedVideoId)) RendererExtras.publish(videoId, extrasDurationMs, false);
        });
        // YouTube Music: timed lyrics (Morphe's) before the video's captions, if already found.
        MusicLyrics.Snapshot lyrics = musicApp ? MusicLyrics.read(videoId) : null;
        if (lyrics != null && lyrics.hasLines()) {
            Captions.Track lyricsTrack = Captions.putLyrics(videoId, MusicLyrics.cues(lyrics));
            if (lyricsTrack != null) {
                pick = lyricsTrack;
                Captions.select(videoId, pick);
            }
        }
        if (pick == null) return;
        if (srt || samsung) track.captionSrt = baseUrl + Captions.path(videoId, pick, "srt");
        if (vtt && !srt) track.captionVtt = baseUrl + Captions.path(videoId, pick, "vtt");
        track.captionSamsung = samsung;
        Log.i("Captions " + pick + " sent with " + videoId);
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
        if ("TRANSITIONING".equals(value)) {
            if (!"TRANSITIONING".equals(previous)) stateBeforeTransition = previous;
        }
        // Coming out of buffering, the state it came from counts.
        String from = "TRANSITIONING".equals(previous) ? stateBeforeTransition : previous;
        Renderer r = renderer;
        // Before our first title, states are the renderer's initial state (sent when subscribing).
        if (r == null || r.isEcho(value) || loadedVideoId == null) return;
        // Buffering, and playing on after it (also once a title loaded), is no command on the
        // TV: a pause on the phone meanwhile must win.
        if ("TRANSITIONING".equals(value)
                || ("PLAYING".equals(value) && !"PAUSED_PLAYBACK".equals(from))) return;

        if (sidePlayerInFront()) return; // Not the Short or preview the user watches.
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
                // Some renderers report it while loading a title (and after a command then).
                if (SystemClock.elapsedRealtime() < rendererSettleUntil) {
                    Log.d("Renderer stopped while loading, not ending the cast");
                    break;
                }
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
    /**
     * Whether Shorts or a feed preview are in front of the main player. Their player is the
     * user's on the phone: it plays there with sound and picture, is not followed and does not
     * follow the renderer, which plays on. Main thread.
     */
    private boolean sidePlayerInFront() {
        String side = MorpheState.sidePlayer();
        if (side != null) {
            if (!side.equals(sidePlayer)) {
                Log.i("The " + side + " player is in front: it plays on the phone, the renderer plays on");
                sidePlayer = side;
                CastAudio.setMuted(false);
                VideoCover.update(null, null);
            }
            return true;
        }
        if (sidePlayer != null) {
            sidePlayer = null;
            // YouTube stopped the main player for the Short; it goes on from the renderer's
            // position once the user plays it (the sync moves it there).
            Log.i("Main player in front again");
            CastAudio.setMuted(true);
            VideoCover.update(null, null);
            String follow = pendingFollow;
            if (follow != null) main.post(() -> followRenderer(follow));
        }
        return false;
    }

    private void followRenderer(String videoId) {
        if (videoId.equals(StreamCapture.currentVideoId())) return;
        if (sidePlayerInFront()) {
            // Not while Shorts or a preview are in front: once the main player is back.
            pendingFollow = videoId;
            return;
        }
        pendingFollow = null;
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
        if (sidePlayerInFront()) return;
        long pausedSince = localPausedSince;
        if ("PLAYING".equals(rendererState) && local.stateCode() == PlaybackState.STATE_PAUSED
                && pausedSince > 0 && SystemClock.elapsedRealtime() - pausedSince > SELF_PAUSE_MS
                && SystemClock.elapsedRealtime() > ignorePositionUntil && !local.isReloading()) {
            // A pause by the user would have paused the renderer.
            Log.i("Phone paused on its own while the renderer plays, resuming it");
            local.play();
        }
        if ("PAUSED_PLAYBACK".equals(rendererState) && local.isPlaying()
                && SystemClock.elapsedRealtime() > ignorePositionUntil && !local.isReloading()) {
            // A play by the user would have resumed the renderer: the player resumed on its own
            // (after a reload, say). It follows the renderer.
            Log.i("Phone plays while the renderer is paused, pausing it");
            local.pause();
        }
        if (live || !"PLAYING".equals(rendererState) && !rendererState.isEmpty()) return;
        rendererThread.execute(() -> {
            try {
                if (loadedVideoId == null || !loadedVideoId.equals(StreamCapture.currentVideoId())) return;
                long remote = renderer.position();
                long phone = phonePosition();
                Log.d("Positions: TV " + remote + " ms, phone " + phone + " ms (phone " + (phone - remote) + " ms ahead)");
                if (remote < 0 || SystemClock.elapsedRealtime() < ignorePositionUntil || local.isReloading()) return;
                // Near the end the renderer moves on by itself and the phone follows; moved there,
                // the phone would end its title first and load the next one on the renderer again.
                long duration = local.duration();
                if (duration > 0 && remote > duration - END_MARGIN_MS) return;
                if (Math.abs(remote - phone) > DRIFT_THRESHOLD_MS) {
                    Log.i("Correcting phone position " + phone + " -> " + remote + " ms");
                    main.post(() -> {
                        if (stopped || !local.isPlaying()) return;
                        // A pause of the player after this seek is its own: the self-pause check
                        // resumes it, like the renderer plays on.
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

    /**
     * Where the phone's player really is: Morphe's player time for the cast title. The media
     * session's position is extrapolated from its last state, and keeps running while the player
     * hangs (as it can after a seek), which would hide that from the sync.
     */
    private long phonePosition() {
        long time = MorpheState.videoTime();
        String video = MorpheState.videoId();
        if (time >= 0 && video != null && video.equals(loadedVideoId)) return time;
        return local.position();
    }

    // region SponsorBlock diagnostics

    /** Morphe's segments for the playing video; logs them when they change. Main thread. */
    private List<SponsorBlock.Segment> sponsorSegments() {
        Object raw = SponsorBlock.raw();
        if (raw != sbRaw) {
            sbRaw = raw;
            sbSegments = SponsorBlock.segments(raw);
            StringBuilder list = new StringBuilder();
            for (SponsorBlock.Segment s : sbSegments) list.append("\n  ").append(s);
            Log.i("SponsorBlock: " + sbSegments.size() + " segments for " + SponsorBlock.videoId()
                    + " (cast plays " + loadedVideoId + ")" + list);
            // Renderers with extras get the segments as soon as Morphe has them.
            String video = loadedVideoId;
            if (video != null && video.equals(SponsorBlock.videoId()) && RendererExtras.wanted(device)) {
                RendererExtras.publish(video, extrasDurationMs, false);
            }
        }
        return sbSegments;
    }

    /** Morphe's lyrics state last published to the extras, per title. Main thread. */
    private String lyricsKey;

    /** YouTube Music: new lyrics from Morphe go to the extras (and as a caption track). */
    private void checkLyrics() {
        String video = loadedVideoId;
        if (!musicApp || video == null || !RendererExtras.wanted(device)) return;
        MusicLyrics.Snapshot s = MusicLyrics.read(video);
        String key = s == null ? null : video + "/" + s.state + "/" + System.identityHashCode(s.identity);
        if (key == null || key.equals(lyricsKey)) return;
        lyricsKey = key;
        if (s.hasLines()) Captions.putLyrics(video, MusicLyrics.cues(s));
        Log.d("Lyrics for " + video + ": " + s.state + (s.hasLines() ? ", " + s.lines.size() + " lines from "
                + s.provider + (s.synced ? ", timed" : ", untimed") : ""));
        RendererExtras.publish(video, extrasDurationMs, false);
    }

    /** The segment SponsorBlock skips when it seeks to this position (its end), or null. */
    private SponsorBlock.Segment segmentEndingAt(long positionMs) {
        for (SponsorBlock.Segment s : sponsorSegments()) {
            if (Math.abs(positionMs - s.end) <= 1500) return s;
        }
        return null;
    }

    /** Logs the TV's position next to a segment (queued after commands sent before). */
    private void logTvAt(String when, SponsorBlock.Segment segment) {
        if (stopped) return;
        rendererThread.execute(() -> {
            try {
                Renderer r = renderer;
                if (r == null) return;
                long tv = r.position();
                String where = tv < segment.start ? (segment.start - tv) + " ms before it"
                        : tv < segment.end ? (tv - segment.start) + " ms into it, " + (segment.end - tv) + " ms left"
                        : (tv - segment.end) + " ms after its end";
                Log.i("SponsorBlock: TV at " + tv + " ms " + when + ": " + where + ", state " + rendererState);
            } catch (Exception e) {
                Log.d("SponsorBlock: TV position failed: " + e.getMessage());
            }
        });
    }

    /**
     * With the debug log on: checks the TV position every second and logs when it enters and
     * leaves an auto-skip segment, and how long it played of it.
     */
    private void watchSponsorBlock() {
        if (stopped) return;
        main.postDelayed(this::watchSponsorBlock, SB_WATCH_MS);
        List<SponsorBlock.Segment> segments = sponsorSegments();
        checkLyrics();
        if (!DlnaCast.debugLogEnabled(context) || live || sbWatchQueued || segments.isEmpty()) return;
        if (!"PLAYING".equals(rendererState) && tvSegment == null) return;
        boolean anyAuto = false;
        for (SponsorBlock.Segment s : segments) anyAuto |= s.autoSkip;
        if (!anyAuto) return;
        sbWatchQueued = true;
        rendererThread.execute(() -> {
            try {
                Renderer r = renderer;
                if (r == null) return;
                long tv = r.position();
                long phone = local.position();
                main.post(() -> onTvPosition(segments, tv, phone));
            } catch (Exception e) {
                // The next check.
            } finally {
                sbWatchQueued = false;
            }
        });
    }

    private void onTvPosition(List<SponsorBlock.Segment> segments, long tv, long phone) {
        if (stopped || tv < 0) return;
        SponsorBlock.Segment in = SponsorBlock.autoSkipAt(segments, tv);
        long now = SystemClock.elapsedRealtime();
        if (tvSegment != null && in != tvSegment) {
            SponsorBlock.Segment s = tvSegment;
            String how = tv < s.start ? "jumped back before it"
                    : tv >= s.end + 3000 || now - tvEnteredAt < (s.end - tvEnteredPosition) - 3000 ? "by a seek"
                    : "played through";
            Log.i("SponsorBlock: TV left " + s + " at " + tv + " ms (" + how + "), " + (now - tvEnteredAt)
                    + " ms inside, entered at " + tvEnteredPosition + " ms");
        }
        if (in != null && in != tvSegment) {
            tvEnteredAt = now;
            tvEnteredPosition = tv;
            Log.i("SponsorBlock: TV entered " + in + " at " + tv + " ms, phone at " + phone + " ms (phone "
                    + (phone - tv) + " ms ahead)");
        }
        tvSegment = in;
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
