package app.ytdlna.extension;

import android.media.MediaMetadata;

/** Metadata of one video or song, as sent to the renderer. */
final class Track {
    final String videoId;
    String title = "";
    String artist = "";
    String album = "";
    /** Cover image, http(s). */
    String artUrl = "";
    long durationMs;

    Track(String videoId) {
        this.videoId = videoId;
    }

    /**
     * Builds the metadata from the captured streams and, if it describes the same title,
     * the app's media session (which has the artist, album and the square cover in Music).
     */
    static Track from(StreamInfo info, MediaMetadata metadata) {
        Track track = new Track(info.videoId);
        track.title = info.title;
        track.durationMs = info.lengthSeconds * 1000;
        track.artUrl = "https://i.ytimg.com/vi/" + info.videoId + "/hqdefault.jpg";
        if (metadata != null && sameTitle(info.title, metadata.getString(MediaMetadata.METADATA_KEY_TITLE))) {
            String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (artist != null) track.artist = artist;
            String album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
            if (album != null) track.album = album;
            long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (duration > 0) track.durationMs = duration;
            for (String key : new String[]{
                    MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                    MediaMetadata.METADATA_KEY_ART_URI,
                    MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI}) {
                String uri = metadata.getString(key);
                if (uri != null && uri.startsWith("http")) {
                    track.artUrl = uri;
                    break;
                }
            }
        }
        return track;
    }

    private static boolean sameTitle(String a, String b) {
        return a != null && b != null && (a.equalsIgnoreCase(b) || a.contains(b) || b.contains(a));
    }

    /** "H:MM:SS" as used by UPnP. */
    static String upnpTime(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return String.format(java.util.Locale.ROOT, "%d:%02d:%02d",
                seconds / 3600, (seconds / 60) % 60, seconds % 60);
    }

    /** Parses "H:MM:SS(.fff)". Returns -1 if not a time. */
    static long parseUpnpTime(String time) {
        if (time == null) return -1;
        String[] parts = time.trim().split(":");
        if (parts.length != 3) return -1;
        try {
            long hours = Long.parseLong(parts[0]);
            long minutes = Long.parseLong(parts[1]);
            double seconds = Double.parseDouble(parts[2]);
            return (long) (((hours * 60 + minutes) * 60 + seconds) * 1000);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
