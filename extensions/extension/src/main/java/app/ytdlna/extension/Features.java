package app.ytdlna.extension;

/**
 * Optional parts of DLNA casting, each switched on by its own patch (see OptionalPatches.kt):
 * the patch replaces the method's constant with true. Without the patch the part stays off and
 * casting works as before.
 */
public final class Features {
    /** A compile-time constant: each method below reads it with a single const/4. */
    private static final boolean OFF = false;

    private Features() {
    }

    /** "DLNA cover page" (YouTube Music): the cover's colors over the player page, like Spotify. */
    static boolean coverPage() {
        return OFF;
    }

    /** "DLNA portrait lock": the phone stays upright while casting. */
    static boolean portraitLock() {
        return OFF;
    }

    /** "DLNA Sonos rooms": Sonos groups and rooms, and renderers that feed Sonos rooms. */
    static boolean rooms() {
        return OFF;
    }

    /** "DLNA renderer extras": captions, lyrics and SponsorBlock segments for renderers. */
    static boolean rendererExtras() {
        return OFF;
    }
}
