package app.ytdlna.extension;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * Cover for the renderer: in YouTube Music the album cover from a music database (if enabled and
 * found), otherwise the YouTube thumbnail. Both are linked directly in the metadata.
 */
final class Covers {
    private final Context context;
    private final boolean musicApp;

    Covers(Context context, boolean musicApp) {
        this.context = context;
        this.musicApp = musicApp;
    }

    private boolean lookupEnabled() {
        return musicApp && AlbumArt.isEnabled(context);
    }

    /**
     * Cover URL for {@code track}, null if none. Sets {@code track.artUrl} to it.
     *
     * @param lookup Look the album cover up now; otherwise only an already found one is used.
     */
    String forTrack(Track track, boolean lookup) {
        if (lookupEnabled()) {
            String cover = lookup ? AlbumArt.lookup(track.title, track.artist)
                    : AlbumArt.cached(track.title, track.artist);
            if (cover != null) track.artUrl = cover;
        }
        return track.artUrl.isEmpty() ? null : track.artUrl;
    }

    /** Looks up the album covers of several tracks in parallel, so later calls find them cached. */
    void prefetch(List<Track> tracks) {
        if (!lookupEnabled() || tracks.isEmpty()) return;
        List<String[]> items = new ArrayList<>();
        for (Track track : tracks) items.add(new String[]{track.title, track.artist});
        AlbumArt.prefetch(items, 4000);
    }
}
