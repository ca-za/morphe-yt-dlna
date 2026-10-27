package app.ytdlna.extension;

/** YouTube Music's song/video switch. */
@SuppressWarnings("unused")
public final class MusicMode {
    interface Listener {
        void onMusicModeChanged(boolean audio);
    }

    /** The app starts in song mode (ATV_PREFERRED) until it sets something else. */
    private static volatile boolean audio = true;
    private static volatile Listener listener;

    private MusicMode() {
    }

    /** Song mode: the app plays audio only. */
    static boolean isAudio() {
        return audio;
    }

    static void setListener(Listener newListener) {
        listener = newListener;
    }

    /**
     * Injection point: the app's song/video mode store is set.
     *
     * @param mode The mode enum: ATV_PREFERRED, OMV_PREFERRED, DONT_PLAY_VIDEO_OVERRIDE,
     *             ATV_PREFERRED_USER_TRIGGERED or OMV_PREFERRED_USER_TRIGGERED.
     */
    public static void onModeChanged(Object mode) {
        if (!(mode instanceof Enum)) return;
        String name = ((Enum<?>) mode).name();
        boolean newAudio = !name.startsWith("OMV");
        if (newAudio == audio) return;
        audio = newAudio;
        Log.i("Music mode " + name + (newAudio ? " (song)" : " (video)"));
        Listener current = listener;
        if (current != null) current.onMusicModeChanged(newAudio);
    }
}
