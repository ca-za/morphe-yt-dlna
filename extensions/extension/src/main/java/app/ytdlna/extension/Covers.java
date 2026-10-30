package app.ytdlna.extension;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * Cover and song details for the renderer: in YouTube Music the album cover, album, year and label
 * from a music database (if enabled and found), otherwise the YouTube thumbnail. The cover is
 * linked directly in the metadata.
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
     * Cover URL for {@code track}, null if none. Sets {@code track.artUrl} to it and fills in what
     * the music database knows and the app does not: album, length, year and label (the latter two
     * for titles not looked up now only if {@link #prefetch} found them).
     *
     * @param lookup Look the title up now; otherwise only an already found match is used.
     */
    String forTrack(Track track, boolean lookup) {
        if (lookupEnabled()) {
            AlbumArt.Match match = lookup ? AlbumArt.lookup(track.title, track.artist)
                    : AlbumArt.cached(track.title, track.artist);
            if (match != null) {
                track.artUrl = match.url;
                if (match.isSure()) {
                    if (track.album.isEmpty()) track.album = match.album;
                    // An estimate until the app has loaded the title's streams.
                    if (track.durationMs <= 0) track.durationMs = match.durationMs;
                    String[] details = lookup ? AlbumArt.details(match) : AlbumArt.cachedDetails(match);
                    if (track.year.isEmpty()) track.year = details[0];
                    if (track.label.isEmpty()) track.label = details[1];
                }
            }
        }
        return track.artUrl.isEmpty() ? null : track.artUrl;
    }

    /** Looks up covers, year and label of several tracks in parallel, so later calls find them cached. */
    void prefetch(List<Track> tracks) {
        if (!lookupEnabled() || tracks.isEmpty()) return;
        List<String[]> items = new ArrayList<>();
        for (Track track : tracks) items.add(new String[]{track.title, track.artist});
        AlbumArt.prefetch(items, 4000);
    }
}
