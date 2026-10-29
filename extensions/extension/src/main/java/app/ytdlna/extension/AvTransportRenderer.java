package app.ytdlna.extension;

import android.os.Handler;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Plain UPnP AV renderer: one title at a time through AVTransport, and the next title of the
 * app's queue through SetNextAVTransportURI if the renderer supports it ("Up next", no gap).
 */
final class AvTransportRenderer extends Renderer {
    private final Covers covers;
    private volatile String token;

    /** Title the renderer plays (our last load, or the next one it moved on to). */
    private volatile String currentVideoId;
    private volatile List<LocalPlayer.QueueEntry> queue;
    /** Announced next title and its address, null if none. */
    private volatile String nextVideoId;
    private volatile String nextUri;
    /** Title the renderer moved on to by itself; the phone's load of it must not restart it. */
    private volatile String movedOnTo;
    /** Titles announced as next since our last load: their streams are served when known. */
    private final Set<String> announced = ConcurrentHashMap.newKeySet();
    /** The renderer refused SetNextAVTransportURI: not tried again. */
    private volatile boolean nextUnsupported;

    AvTransportRenderer(DlnaDevice device, String baseUrl, Listener listener, Handler main,
                        BooleanSupplier audioOnly, Covers covers) {
        super(device, baseUrl, listener, main, audioOnly);
        this.covers = covers;
    }

    // region Events

    @Override
    boolean subscribe() {
        if (device.avTransportEventUrl != null) token = NativeProxy.nativeSubscribe(device.avTransportEventUrl, baseUrl);
        return token != null;
    }

    @Override
    void unsubscribe() {
        String t = token;
        token = null;
        if (t != null) NativeProxy.nativeUnsubscribe(t);
    }

    @Override
    boolean handleEvent(String eventToken, String name, String value) {
        if (eventToken.isEmpty()) {
            // From the proxy: the announced next title was requested before its streams are known.
            if (!name.equals("NeedVideo") || !announced.contains(value)) return false;
            main.post(() -> onWantsNext(value));
            return true;
        }
        if (!eventToken.equals(token)) return false;
        switch (name) {
            case "SubscriptionLost":
                main.post(listener::onEventsLost);
                break;
            case "TransportState":
                main.post(() -> listener.onTransportState(value));
                break;
            case "CurrentTrackURI":
            case "AVTransportURI":
                main.post(() -> onUri(value));
                break;
            default:
                break;
        }
        return true;
    }

    /** The renderer moved on to the announced next title by itself: the phone follows. */
    private void onUri(String uri) {
        String next = nextVideoId;
        if (next == null || !uri.equals(nextUri) || next.equals(currentVideoId)) return;
        Log.d("Renderer moved on to the next title " + next);
        currentVideoId = next;
        movedOnTo = next;
        nextVideoId = null;
        nextUri = null;
        listener.onRendererTitle(next);
    }

    private void onWantsNext(String videoId) {
        // Before the switch it only preloads; the phone follows once the renderer plays it.
        if (videoId.equals(currentVideoId)) listener.onRendererWantsTitle(videoId);
        else preload(videoId);
    }

    @Override
    String pollState() throws Exception {
        return DlnaController.transportState(device);
    }

    // endregion

    // region Titles

    @Override
    void load(Load load) throws Exception {
        queue = load.queue;
        if (load.info.videoId.equals(movedOnTo)) {
            // The phone followed the renderer: it plays this title already from its next address.
            movedOnTo = null;
            registerAuto(load.info);
            announceNext();
            return;
        }
        movedOnTo = null;
        String url = baseUrl + load.media.path;
        currentVideoId = load.info.videoId;
        // A new title replaces the announced next one on the renderer.
        nextVideoId = null;
        nextUri = null;
        announced.clear();
        DlnaController.play(device, url, load.media.mimeType, load.track, load.art, load.media.video);
        announceNext();
    }

    @Override
    boolean onQueue(List<LocalPlayer.QueueEntry> newQueue) {
        queue = newQueue;
        announceNext();
        return false;
    }

    @Override
    void onStreams(StreamInfo info) {
        // Preloaded by the app: ready before the renderer asks for it.
        if (announced.contains(info.videoId)) registerAuto(info);
    }

    /** Announces the title after the current one in the app's queue, if it changed. */
    private void announceNext() {
        List<LocalPlayer.QueueEntry> entries = queue;
        String current = currentVideoId;
        if (nextUnsupported || entries == null || current == null) return;
        int playing = LocalPlayer.indexOfPlaying(entries, current);
        LocalPlayer.QueueEntry next = playing >= 0 && playing + 1 < entries.size() ? entries.get(playing + 1) : null;
        if (next == null || next.videoId.equals(nextVideoId)) return;
        StreamInfo captured = StreamCapture.get(next.videoId);
        if (captured != null) registerAuto(captured);
        Track track = Track.fromQueue(next);
        // Its extras are served once the phone plays it; until then the renderer gets a 404.
        if (RendererExtras.wanted(device)) {
            track.extrasUrl = baseUrl + RendererExtras.path(track.videoId);
            RendererExtras.publishPending(track.videoId);
        }
        // Its cover and length from the music database (one lookup, after the current title plays).
        covers.prefetch(java.util.Collections.singletonList(track));
        String art = covers.forTrack(track, false);
        String uri = autoUri(next.videoId);
        try {
            DlnaController.setNext(device, uri,
                    DlnaController.didl(uri, autoMime(next.videoId), track, art, !audioOnly.getAsBoolean()));
            nextVideoId = next.videoId;
            nextUri = uri;
            announced.add(next.videoId);
            Log.d("Next title on the renderer: " + next.videoId);
        } catch (Exception e) {
            nextUnsupported = true;
            Log.i("Renderer does not take a next title: " + e.getMessage());
        }
    }

    // endregion

    // region Transport

    @Override
    void resume() throws Exception {
        DlnaController.resume(device);
    }

    @Override
    void pause() throws Exception {
        DlnaController.pause(device);
    }

    @Override
    void stop() throws Exception {
        DlnaController.stop(device);
    }

    @Override
    void seek(long positionMs, boolean playing) throws Exception {
        DlnaController.seek(device, positionMs);
    }

    @Override
    long position() throws Exception {
        return DlnaController.position(device);
    }

    // endregion
}
