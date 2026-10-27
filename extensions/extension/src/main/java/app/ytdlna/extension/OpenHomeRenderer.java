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
    /** Titles the renderer refused: not tried again, so filling the playlist ends. */
    private final java.util.Set<String> refused = ConcurrentHashMap.newKeySet();
    private volatile int currentTrackId;
    /** Track before our last SeekId: late events can still report it. */
    private volatile int previousTrackId;
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
        if (!videoByTrackId.containsKey(trackId)) {
            // Not one of our entries, e.g. left over from an earlier session before ours is set up.
            Log.d("Ignoring renderer track " + trackId + " (not in our playlist)");
            return;
        }
        if (isEcho() && trackId == previousTrackId) {
            // A late event from before our own SeekId. Any other track is a change on the TV.
            Log.d("Ignoring renderer track " + trackId + " from before our own change");
            return;
        }
        currentTrackId = trackId;
        String videoId = videoByTrackId.get(trackId);
        Log.d("Renderer track " + trackId + " = " + videoId);
        listener.onRendererTitle(videoId);
    }

    private void onWantsVideo(String videoId) {
        if (!trackIdByVideo.containsKey(videoId)) {
            Log.d("Ignoring request for " + videoId + " (not in our playlist)");
            return;
        }
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
            previousTrackId = currentTrackId;
            currentTrackId = trackId;
            OpenHome.seekId(device, trackId);
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

    /**
     * Clears the renderer playlist and starts it with the title to play. The rest of the queue
     * follows through {@link #onQueue}, while the title already plays.
     */
    private void startPlaylist(Load load) throws Exception {
        OpenHome.selectPlaylistSource(device);
        OpenHome.deleteAll(device);
        trackIdByVideo.clear();
        videoByTrackId.clear();
        refused.clear();
        currentTrackId = 0;
        playlistStarted = true;
        insert(0, load.track, load.art);
    }

    /** Inserts per call; one insert takes up to half a second on some renderers. */
    private static final int INSERTS_PER_CALL = 3;

    /** Adds queue entries that are not on the renderer yet, each after its predecessor. */
    @Override
    boolean onQueue(List<LocalPlayer.QueueEntry> queue) {
        if (!playlistStarted) return false;
        List<Track> missing = new ArrayList<>();
        for (LocalPlayer.QueueEntry entry : queue) {
            if (!trackIdByVideo.containsKey(entry.videoId) && !refused.contains(entry.videoId)) {
                missing.add(track(entry));
            }
        }
        if (missing.isEmpty()) return false;
        covers.prefetch(missing);
        // The titles after the playing one first (the next one is needed soonest), then those before.
        int playing = -1;
        String playingVideo = videoByTrackId.get(currentTrackId);
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).videoId.equals(playingVideo)) playing = i;
        }
        List<Integer> order = new ArrayList<>();
        for (int i = playing + 1; i < queue.size(); i++) order.add(i);
        for (int i = 0; i < Math.max(playing, 0); i++) order.add(i);
        int inserts = 0;
        for (int i : order) {
            LocalPlayer.QueueEntry entry = queue.get(i);
            if (trackIdByVideo.containsKey(entry.videoId) || refused.contains(entry.videoId)) continue;
            if (inserts++ == INSERTS_PER_CALL) return true;
            // After its predecessor in the queue (inserted already by this order), or first.
            Integer after = i == 0 ? Integer.valueOf(0) : trackIdByVideo.get(queue.get(i - 1).videoId);
            StreamInfo captured = StreamCapture.get(entry.videoId);
            if (captured != null) registerAuto(captured);
            Track track = track(entry);
            try {
                insert(after == null ? 0 : after, track, covers.forTrack(track, false));
            } catch (Exception e) {
                Log.i("Adding " + entry.videoId + " to the renderer playlist failed: " + e.getMessage());
                refused.add(entry.videoId);
            }
        }
        Log.i("Renderer playlist has " + trackIdByVideo.size() + " tracks");
        return false;
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
