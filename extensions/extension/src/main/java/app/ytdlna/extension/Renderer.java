package app.ytdlna.extension;

import android.os.Handler;
import android.os.SystemClock;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The TV side of a cast: commands, events and, with OpenHome, the playlist.
 * Commands run on the session's renderer thread; listener calls arrive on the main thread.
 */
abstract class Renderer {
    interface Listener {
        /** Transport state in AVTransport terms: PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING. */
        void onTransportState(String state);

        /** The renderer switched to another title on its own (its remote, end of a track). */
        void onRendererTitle(String videoId);

        /** The renderer asks for a title whose streams are not loaded yet. */
        void onRendererWantsTitle(String videoId);

        /** Events stopped; the session polls instead. */
        void onEventsLost();
    }

    /** What the session hands over to load a title. */
    static final class Load {
        final StreamInfo info;
        final StreamProxy.Media media;
        final Track track;
        final String art;
        /** The app's queue, for renderers with a playlist. */
        final List<LocalPlayer.QueueEntry> queue;

        Load(StreamInfo info, StreamProxy.Media media, Track track, String art, List<LocalPlayer.QueueEntry> queue) {
            this.info = info;
            this.media = media;
            this.track = track;
            this.art = art;
            this.queue = queue;
        }
    }

    /** Renderer events right after our own commands are their echo, not user actions. */
    private static final long ECHO_MS = 1500;

    final DlnaDevice device;
    final String baseUrl;
    final Listener listener;
    final Handler main;
    /** YouTube Music in song mode. Can change while casting. */
    final BooleanSupplier audioOnly;
    private volatile long ignoreUntil;
    /** States our last command leads to; null: any state (loading a title). */
    private volatile java.util.Set<String> expectedStates;

    Renderer(DlnaDevice device, String baseUrl, Listener listener, Handler main, BooleanSupplier audioOnly) {
        this.device = device;
        this.baseUrl = baseUrl;
        this.listener = listener;
        this.main = main;
        this.audioOnly = audioOnly;
    }

    /** OpenHome with the whole queue as playlist if the renderer has it, otherwise AVTransport. */
    static Renderer create(DlnaDevice device, String baseUrl, Listener listener, Handler main,
                           BooleanSupplier audioOnly, Covers covers) {
        return device.hasOpenHomePlaylist() && !TestHooks.avTransportOnly()
                ? new OpenHomeRenderer(device, baseUrl, listener, main, audioOnly, covers)
                : new AvTransportRenderer(device, baseUrl, listener, main, audioOnly, covers);
    }

    /**
     * Within {@code ms}, transport states in {@code states} (and TRANSITIONING on the way there)
     * are the echo of our own command; other states are real changes on the TV.
     * Without states, every event is treated as echo (loading a title).
     */
    void expectEcho(long ms, String... states) {
        expectedStates = states.length == 0 ? null : new java.util.HashSet<>(java.util.Arrays.asList(states));
        ignoreUntil = SystemClock.elapsedRealtime() + ms;
    }

    void expectEcho(String... states) {
        expectEcho(ECHO_MS, states);
    }

    /** A track change within the echo time (any kind). */
    boolean isEcho() {
        return SystemClock.elapsedRealtime() < ignoreUntil;
    }

    /** {@code state} is the echo of our last command. */
    boolean isEcho(String state) {
        if (!isEcho()) return false;
        java.util.Set<String> states = expectedStates;
        return states == null || states.contains(state) || state.equals("TRANSITIONING");
    }

    // region Events

    /** Subscribes to the renderer's events. False if it has none. */
    abstract boolean subscribe();

    abstract void unsubscribe();

    /** Handles one event of the native proxy. False if it is not for this renderer. */
    abstract boolean handleEvent(String token, String name, String value);

    /** Current transport state for polling, "" if unknown. */
    abstract String pollState() throws Exception;

    // endregion

    // region Commands

    abstract void load(Load load) throws Exception;

    abstract void resume() throws Exception;

    abstract void pause() throws Exception;

    abstract void stop() throws Exception;

    /** @param playing The phone is playing; renderers that pause on seeks are resumed. */
    abstract void seek(long positionMs, boolean playing) throws Exception;

    /** Position in ms, or -1 if unknown. */
    abstract long position() throws Exception;

    int volume() throws Exception {
        return DlnaController.volume(device);
    }

    void setVolume(int volume) throws Exception {
        DlnaController.setVolume(device, volume);
    }

    /**
     * The app's queue changed. Renderers with a playlist add a few entries per call, so other
     * commands are not held up.
     *
     * @return true if entries are left: call again.
     */
    boolean onQueue(List<LocalPlayer.QueueEntry> queue) {
        return false;
    }

    // region Titles before their streams

    /**
     * Address for a title whose streams the app may not have loaded yet. The native proxy holds
     * a request to it and asks for the title (NeedVideo) until {@link #registerAuto} serves it.
     */
    String autoUri(String videoId) {
        return baseUrl + autoPath(videoId);
    }

    private static String autoPath(String videoId) {
        return "/v/" + videoId + "/auto";
    }

    /** Media type of {@link #autoUri} addresses, as the streams will be served. */
    String autoMime() {
        if (audioOnly.getAsBoolean()) return "audio/mp4";
        return device.supports("application/dash+xml") ? "application/dash+xml" : "video/mp4";
    }

    /** Makes {@link #autoUri} serve the video's media. */
    void registerAuto(StreamInfo info) {
        StreamProxy.Media media = StreamProxy.choose(info, device, audioOnly.getAsBoolean());
        if (media == null) return;
        StreamProxy.register(info);
        StreamProxy.alias(info, media, autoPath(info.videoId));
    }

    // endregion

    /** The app loaded streams of a title that is not playing (usually preloading the next one). */
    void onStreams(StreamInfo info) {
    }

    // endregion
}
