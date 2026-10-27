package app.ytdlna.extension;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The app's own player, used as the remote control while casting. Reached through its media
 * session (the one behind the media notification), so no player internals are patched.
 */
final class LocalPlayer {
    interface Listener {
        /** On the main thread. */
        void onLocalState(PlaybackState state);

        /** On the main thread. */
        void onQueue(List<QueueEntry> queue);
    }

    /** One entry of the app's queue. */
    static final class QueueEntry {
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

    private static final int ATTACH_ATTEMPTS = 20;
    /** Covers the switch to the neighbouring title, the switch back and every seek-back attempt. */
    private static final long RELOAD_MS = 8000;
    private static final long SEEK_TOLERANCE_MS = 3000;
    private static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern THUMBNAIL_VIDEO_ID = Pattern.compile("/vi(?:_webp)?/([A-Za-z0-9_-]{11})/");

    private final Context context;
    private final Handler main;
    private final Listener listener;
    private volatile MediaController controller;
    private int attachAttempts;
    private boolean detached;

    /** Video id -> queue entry, from the last read of the queue. */
    private final Map<String, QueueEntry> entries = new ConcurrentHashMap<>();

    // While the phone reloads a title, other titles it passes through are ignored.
    private volatile long reloadUntil;
    private volatile String reloadVideoId;
    private volatile long reloadPosition;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            if (state != null) listener.onLocalState(state);
        }

        @Override
        public void onQueueChanged(List<MediaSession.QueueItem> queue) {
            listener.onQueue(readQueue(queue));
        }

        @Override
        public void onSessionDestroyed() {
            Log.i("App media session destroyed");
            controller = null;
        }
    };

    LocalPlayer(Context context, Handler main, Listener listener) {
        this.context = context;
        this.main = main;
        this.listener = listener;
    }

    /**
     * Finds the app's media session through its own media notification, retrying while the
     * notification is not there yet. Runs {@code onAttached} on the main thread once found.
     */
    void attach(Runnable onAttached) {
        if (detached || controller != null) return;
        MediaSession.Token token = findSessionToken();
        if (token == null) {
            if (++attachAttempts < ATTACH_ATTEMPTS) {
                main.postDelayed(() -> attach(onAttached), 1000);
            } else {
                Log.i("App media session not found, local controls are not mirrored");
            }
            return;
        }
        MediaController c = new MediaController(context, token);
        c.registerCallback(callback, main);
        controller = c;
        onAttached.run();
    }

    /** Stops listening. The controller stays usable for a final {@link #reload()}. */
    void detach() {
        detached = true;
        MediaController c = controller;
        if (c != null) c.unregisterCallback(callback);
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

    // region State

    PlaybackState state() {
        MediaController c = controller;
        return c == null ? null : c.getPlaybackState();
    }

    int stateCode() {
        PlaybackState state = state();
        return state == null ? PlaybackState.STATE_NONE : state.getState();
    }

    boolean isPlaying() {
        return stateCode() == PlaybackState.STATE_PLAYING;
    }

    MediaMetadata metadata() {
        MediaController c = controller;
        return c == null ? null : c.getMetadata();
    }

    /** Current position in ms, extrapolated while playing. */
    long position() {
        PlaybackState state = state();
        if (state == null) return 0;
        long position = state.getPosition();
        if (state.getState() == PlaybackState.STATE_PLAYING) {
            position += (long) ((SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime())
                    * state.getPlaybackSpeed());
        }
        return Math.max(0, position);
    }

    /** Duration of the current title in ms, or 0 if unknown. */
    long duration() {
        MediaMetadata metadata = metadata();
        return metadata == null ? 0 : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
    }

    // endregion

    // region Commands

    void play() {
        MediaController c = controller;
        if (c != null) c.getTransportControls().play();
    }

    void pause() {
        MediaController c = controller;
        if (c != null) c.getTransportControls().pause();
    }

    void seekTo(long positionMs) {
        MediaController c = controller;
        if (c != null) c.getTransportControls().seekTo(positionMs);
    }

    /** Plays the queue entry of {@code videoId}. False if it is not in the queue. */
    boolean skipTo(String videoId) {
        QueueEntry entry = entries.get(videoId);
        MediaController c = controller;
        if (entry == null || c == null) return false;
        c.getTransportControls().skipToQueueItem(entry.queueId);
        return true;
    }

    // endregion

    // region Queue

    /** The app's queue, read now. */
    List<QueueEntry> queue() {
        MediaController c = controller;
        return readQueue(c == null ? null : c.getQueue());
    }

    QueueEntry entry(String videoId) {
        return entries.get(videoId);
    }

    /** The video id is the media id, or part of the thumbnail URL. */
    private List<QueueEntry> readQueue(List<MediaSession.QueueItem> queue) {
        List<QueueEntry> result = new ArrayList<>();
        if (queue == null) return result;
        for (MediaSession.QueueItem item : queue) {
            MediaDescription description = item.getDescription();
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
            QueueEntry entry = new QueueEntry(item.getQueueId(), videoId,
                    description.getTitle() == null ? "" : description.getTitle().toString(),
                    description.getSubtitle() == null ? "" : description.getSubtitle().toString(),
                    icon);
            result.add(entry);
            entries.put(videoId, entry);
        }
        return result;
    }

    // endregion

    // region Reload

    /**
     * Makes the app load the current title again, so its player gets new streaming data
     * (reduced while casting, all formats after). The app reuses the streaming data when the
     * same title is selected again, so this briefly selects a neighbouring queue item and comes
     * back, then continues at the same position. Does nothing without a queue.
     *
     * @return true if a reload was started.
     */
    boolean reload() {
        MediaController c = controller;
        PlaybackState state = c == null ? null : c.getPlaybackState();
        List<MediaSession.QueueItem> queue = c == null ? null : c.getQueue();
        String videoId = StreamCapture.currentVideoId();
        if (state == null || queue == null || queue.size() < 2 || videoId == null) {
            Log.d("Can not reload the local title (no queue)");
            return false;
        }
        long current = state.getActiveQueueItemId();
        int index = -1;
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).getQueueId() == current) index = i;
        }
        if (index < 0) return false;
        long neighbour = queue.get(index + 1 < queue.size() ? index + 1 : index - 1).getQueueId();
        long position = position();
        boolean paused = state.getState() == PlaybackState.STATE_PAUSED;
        Log.d("Reloading the local title " + videoId + " at " + position + " ms");

        reloadVideoId = videoId;
        reloadPosition = position;
        reloadUntil = SystemClock.elapsedRealtime() + RELOAD_MS;
        MediaController.TransportControls controls = c.getTransportControls();
        controls.skipToQueueItem(neighbour);
        main.postDelayed(() -> controls.skipToQueueItem(current), 700);
        // Seek back once the title is loaded again; retried, since loading takes a moment.
        for (long delay : new long[]{2200, 3500, 5000}) {
            main.postDelayed(() -> {
                if (!videoId.equals(StreamCapture.currentVideoId())) return;
                if (Math.abs(position() - position) > SEEK_TOLERANCE_MS) controls.seekTo(position);
                if (paused) controls.pause();
            }, delay);
        }
        return true;
    }

    boolean isReloading() {
        return SystemClock.elapsedRealtime() < reloadUntil;
    }

    /** Ms until the reload is done, 0 if none runs. */
    long reloadRemaining() {
        return Math.max(0, reloadUntil - SystemClock.elapsedRealtime());
    }

    /** A title the phone only passes through while reloading another one. */
    boolean isPassingThrough(String videoId) {
        return isReloading() && videoId != null && !videoId.equals(reloadVideoId);
    }

    /**
     * Position of {@code videoId}: while it is being reloaded the player's position is not
     * reliable, so the one from before the reload.
     */
    long positionOf(String videoId) {
        return isReloading() && videoId.equals(reloadVideoId) ? reloadPosition : position();
    }

    // endregion
}
