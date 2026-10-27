package app.ytdlna.extension;

import android.os.Handler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * OpenHome renderer: the app's whole queue is the renderer's playlist, with titles, artists and
 * covers. Entries point to {@code /v/<videoId>/auto}; for titles whose streams the app has not
 * loaded yet, the native proxy holds the request and asks for them (see onRendererWantsTitle).
 */
final class OpenHomeRenderer extends Renderer {
    private final Covers covers;
    private volatile String token;

    /** Renderer playlist: video id <-> OpenHome track id. */
    private final Map<String, Integer> trackIdByVideo = new ConcurrentHashMap<>();
    private final Map<Integer, String> videoByTrackId = new ConcurrentHashMap<>();
    private volatile int currentTrackId;
    private volatile boolean playlistStarted;

    OpenHomeRenderer(DlnaDevice device, String baseUrl, Listener listener, Handler main,
                     BooleanSupplier audioOnly, Covers covers) {
        super(device, baseUrl, listener, main, audioOnly);
        this.covers = covers;
    }

    // region Events

    @Override
    boolean subscribe() {
        if (device.playlistEventUrl != null) token = NativeProxy.nativeSubscribe(device.playlistEventUrl, baseUrl);
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
            // From the proxy itself: a playlist entry without streams was requested.
            if (!name.equals("NeedVideo")) return false;
            main.post(() -> onWantsVideo(value));
            return true;
        }
        if (!eventToken.equals(token)) return false;
        switch (name) {
            case "SubscriptionLost":
                main.post(listener::onEventsLost);
                break;
            case "TransportState":
                String state = OpenHome.toAvTransportState(value);
                main.post(() -> listener.onTransportState(state));
                break;
            case "Id":
                main.post(() -> onTrack(value));
                break;
            default:
                break;
        }
        return true;
    }

    @Override
    String pollState() throws Exception {
        return OpenHome.toAvTransportState(OpenHome.transportState(device));
    }

    /** The renderer moved to another playlist track (by itself or by its remote). */
    private void onTrack(String value) {
        int trackId;
        try {
            trackId = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (trackId == currentTrackId) return;
        if (isEcho()) {
            // Echo of our own SeekId or a late event from before it.
            Log.d("Ignoring renderer track " + trackId + " right after our own change");
            return;
        }
        currentTrackId = trackId;
        String videoId = videoByTrackId.get(trackId);
        Log.d("Renderer track " + trackId + " = " + videoId);
        if (videoId != null) listener.onRendererTitle(videoId);
    }

    private void onWantsVideo(String videoId) {
        String current = videoByTrackId.get(currentTrackId);
        if (current != null && !current.equals(videoId)) {
            // Preloading the next track; the phone follows once the renderer switches to it.
            Log.d("Renderer preloads " + videoId);
            return;
        }
        listener.onRendererWantsTitle(videoId);
    }

    // endregion

    // region Playlist

    @Override
    void load(Load load) throws Exception {
        registerAuto(load.info);
        if (!playlistStarted) startPlaylist(load);
        int trackId = trackIdByVideo.containsKey(load.info.videoId)
                ? trackIdByVideo.get(load.info.videoId)
                : insert(currentTrackId, load.track, load.art);
        if (trackId != currentTrackId) {
            OpenHome.seekId(device, trackId);
            currentTrackId = trackId;
        }
        OpenHome.play(device);
    }

    /**
     * Reload with the current format, after a song/video switch: the entries' format comes from
     * their metadata, so the playlist is built again.
     */
    void restartPlaylist() {
        playlistStarted = false;
    }

    /** Clears the renderer playlist and fills it with the app's queue. */
    private void startPlaylist(Load load) throws Exception {
        OpenHome.selectPlaylistSource(device);
        OpenHome.deleteAll(device);
        trackIdByVideo.clear();
        videoByTrackId.clear();
        currentTrackId = 0;
        playlistStarted = true;
        if (load.queue.isEmpty()) {
            insert(0, load.track, load.art);
        } else {
            onQueue(load.queue);
        }
        Log.i("Renderer playlist has " + trackIdByVideo.size() + " tracks");
    }

    /** Adds queue entries that are not on the renderer yet, each after its predecessor. */
    @Override
    void onQueue(List<LocalPlayer.QueueEntry> queue) {
        if (!playlistStarted) return;
        List<Track> missing = new ArrayList<>();
        for (LocalPlayer.QueueEntry entry : queue) {
            if (!trackIdByVideo.containsKey(entry.videoId)) missing.add(track(entry));
        }
        covers.prefetch(missing);
        int after = 0;
        for (LocalPlayer.QueueEntry entry : queue) {
            Integer existing = trackIdByVideo.get(entry.videoId);
            if (existing == null) {
                StreamInfo captured = StreamCapture.get(entry.videoId);
                if (captured != null) registerAuto(captured);
                Track track = track(entry);
                try {
                    existing = insert(after, track, covers.forTrack(track, false));
                } catch (Exception e) {
                    Log.i("Adding " + entry.videoId + " to the renderer playlist failed: " + e.getMessage());
                    continue;
                }
            }
            after = existing;
        }
    }

    @Override
    void onStreams(StreamInfo info) {
        // Preloaded by the app (usually the next title): ready before the renderer asks.
        if (trackIdByVideo.containsKey(info.videoId)) registerAuto(info);
    }

    private static Track track(LocalPlayer.QueueEntry entry) {
        Track track = new Track(entry.videoId);
        track.title = entry.title;
        track.artist = entry.artist;
        track.artUrl = entry.iconUrl.startsWith("http") ? entry.iconUrl
                : "https://i.ytimg.com/vi/" + entry.videoId + "/hqdefault.jpg";
        return track;
    }

    private int insert(int afterId, Track track, String art) throws Exception {
        String uri = baseUrl + "/v/" + track.videoId + "/auto";
        boolean audio = audioOnly.getAsBoolean();
        String mime = audio ? "audio/mp4"
                : device.supports("application/dash+xml") ? "application/dash+xml" : "video/mp4";
        int id = OpenHome.insert(device, afterId, uri, DlnaController.didl(uri, mime, track, art, !audio));
        trackIdByVideo.put(track.videoId, id);
        videoByTrackId.put(id, track.videoId);
        return id;
    }

    /** Makes {@code /v/<videoId>/auto} serve the video's media. */
    private void registerAuto(StreamInfo info) {
        StreamProxy.Media media = StreamProxy.choose(info, device, audioOnly.getAsBoolean());
        if (media == null) return;
        StreamProxy.register(info);
        StreamProxy.alias(info, media, "/v/" + info.videoId + "/auto");
    }

    // endregion

    // region Transport

    @Override
    void resume() throws Exception {
        OpenHome.play(device);
    }

    @Override
    void pause() throws Exception {
        OpenHome.pause(device);
    }

    @Override
    void stop() throws Exception {
        OpenHome.stop(device);
    }

    @Override
    void seek(long positionMs, boolean playing) throws Exception {
        OpenHome.seekSeconds(device, positionMs);
        // Some renderers stay paused after an OpenHome seek.
        if (playing) OpenHome.play(device);
    }

    @Override
    long position() throws Exception {
        return OpenHome.position(device);
    }

    // endregion
}
