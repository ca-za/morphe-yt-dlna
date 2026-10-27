package app.ytdlna.extension;

import java.lang.reflect.Array;
import java.lang.reflect.Method;

/**
 * Lowers the YouTube player's video quality while casting and restores it afterwards.
 * <p>
 * A quality change only switches between formats the player already has, so it applies to the
 * running video right away (new videos get only the smallest tracks anyway, see LocalPlayback).
 * Uses the quality hooks of Morphe's "Video information" patch through reflection, since add-on
 * bundles can not reference Morphe code at patch time. Without that patch this does nothing.
 */
final class LocalQuality {
    private static final String VIDEO_INFORMATION = "app.morphe.extension.youtube.patches.VideoInformation";
    private static final int AUTOMATIC = -2;

    private static boolean lowered;

    private LocalQuality() {
    }

    /** On the main thread. */
    static void lower() {
        try {
            Object[] qualities = qualities();
            if (qualities == null || qualities.length < 2) {
                Log.d("Video qualities not known yet, quality not lowered");
                return;
            }
            Object lowest = null;
            int lowestResolution = Integer.MAX_VALUE;
            for (Object quality : qualities) {
                int resolution = resolution(quality);
                if (resolution != AUTOMATIC && resolution > 0 && resolution < lowestResolution) {
                    lowest = quality;
                    lowestResolution = resolution;
                }
            }
            if (lowest == null) return;
            lowered = true;
            if (call("getCurrentQuality") == lowest) return;
            change(lowest);
            Log.i("Local video quality lowered to " + lowestResolution + "p while casting");
        } catch (Throwable t) {
            Log.d("Could not lower the video quality: " + t);
        }
    }

    /** On the main thread. */
    static void restore() {
        if (!lowered) return;
        lowered = false;
        try {
            Object[] qualities = qualities();
            if (qualities == null || qualities.length == 0) return;
            // Back to automatic. The current quality Morphe reports is what automatic chose,
            // so it can not tell a manual choice apart; Morphe applies a configured default
            // quality again with the next video.
            Object automatic = null;
            for (Object quality : qualities) {
                if (resolution(quality) == AUTOMATIC) automatic = quality;
            }
            change(automatic != null ? automatic : qualities[0]);
            Log.i("Local video quality restored to automatic");
        } catch (Throwable t) {
            Log.d("Could not restore the video quality: " + t);
        }
    }

    private static Class<?> videoInformation() throws ClassNotFoundException {
        return Class.forName(VIDEO_INFORMATION, true, LocalQuality.class.getClassLoader());
    }

    private static Object call(String method) throws Exception {
        return videoInformation().getMethod(method).invoke(null);
    }

    private static Object[] qualities() throws Exception {
        Object array = call("getCurrentQualities");
        if (array == null) return null;
        Object[] qualities = new Object[Array.getLength(array)];
        for (int i = 0; i < qualities.length; i++) qualities[i] = Array.get(array, i);
        return qualities;
    }

    private static int resolution(Object quality) throws Exception {
        Method method = quality.getClass().getMethod("patch_getResolution");
        return (Integer) method.invoke(quality);
    }

    private static void change(Object quality) throws Exception {
        for (Method method : videoInformation().getMethods()) {
            if (method.getName().equals("changeQuality") && method.getParameterTypes().length == 1) {
                method.invoke(null, quality);
                return;
            }
        }
        throw new NoSuchMethodException("changeQuality");
    }
}
