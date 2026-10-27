package app.ytdlna.extension;

import android.media.AudioTrack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Silences the app's own audio output while casting, without touching the device volume.
 * <p>
 * The patch routes the app's calls to {@link AudioTrack#play()}, {@link AudioTrack#setVolume(float)}
 * and {@link AudioTrack#setStereoVolume(float, float)} through this class, which remembers the
 * volume the app wants and applies 0 instead while a cast is active.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class CastAudio {
    /** Track -> volume the app set. */
    private static final Map<AudioTrack, Float> volumes = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile boolean muted;

    private CastAudio() {
    }

    /** Injection point: replaces {@code AudioTrack.play()}. */
    public static void play(AudioTrack track) {
        synchronized (volumes) {
            if (!volumes.containsKey(track)) volumes.put(track, 1f);
        }
        if (muted) apply(track, 0f);
        track.play();
    }

    /** Injection point: replaces {@code AudioTrack.setVolume(float)}. */
    public static int setVolume(AudioTrack track, float volume) {
        volumes.put(track, volume);
        return track.setVolume(muted ? 0f : volume);
    }

    /** Injection point: replaces {@code AudioTrack.setStereoVolume(float, float)}. */
    public static int setStereoVolume(AudioTrack track, float left, float right) {
        volumes.put(track, Math.max(left, right));
        return muted ? track.setStereoVolume(0f, 0f) : track.setStereoVolume(left, right);
    }

    static boolean isMuted() {
        return muted;
    }

    static void setMuted(boolean mute) {
        if (muted == mute) return;
        muted = mute;
        List<Map.Entry<AudioTrack, Float>> tracks;
        synchronized (volumes) {
            tracks = new ArrayList<>(volumes.entrySet());
        }
        for (Map.Entry<AudioTrack, Float> entry : tracks) {
            apply(entry.getKey(), mute ? 0f : entry.getValue());
        }
        Log.d((mute ? "Muted " : "Unmuted ") + tracks.size() + " app audio tracks");
    }

    private static void apply(AudioTrack track, float volume) {
        try {
            track.setVolume(volume);
        } catch (Exception e) {
            // Released tracks throw; they play nothing anyway.
        }
    }
}
