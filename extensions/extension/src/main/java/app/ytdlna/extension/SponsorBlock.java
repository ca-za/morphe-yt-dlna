package app.ytdlna.extension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Reads the SponsorBlock segments Morphe loaded for the playing video, with the behaviour the
 * user set per category. Read only, through reflection (like {@link MorpheState}); empty when
 * SponsorBlock is not patched in, is off, or found no segments.
 */
final class SponsorBlock {
    private static final String CONTROLLER =
            "app.morphe.extension.shared.sponsorblock.SegmentPlaybackController";

    private static final Method getSegments = method("getSegments");
    private static final Method getVideoId = method("getCurrentVideoId");

    /** One segment, as Morphe has it. Times in ms. */
    static final class Segment {
        final String category;
        /** skip, skip-once, manual-skip, seekbar-only, ignore. */
        final String behaviour;
        final boolean autoSkip;
        final long start, end;

        Segment(String category, String behaviour, boolean autoSkip, long start, long end) {
            this.category = category;
            this.behaviour = behaviour;
            this.autoSkip = autoSkip;
            this.start = start;
            this.end = end;
        }

        boolean contains(long position) {
            return position >= start && position < end;
        }

        @Override
        public String toString() {
            return category + " " + seconds(start) + "–" + seconds(end) + " s (" + behaviour + ")";
        }
    }

    private SponsorBlock() {
    }

    /** Whether Morphe's SponsorBlock is in the app at all. */
    static boolean available() {
        return getSegments != null;
    }

    /** The video the segments belong to, or null. */
    static String videoId() {
        try {
            Object value = getVideoId == null ? null : getVideoId.invoke(null);
            return value instanceof String ? (String) value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The array Morphe holds now; a new one means new segments. Null if there is none. */
    static Object raw() {
        try {
            return getSegments == null ? null : getSegments.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The segments of {@link #raw()}, sorted by start. */
    static List<Segment> segments(Object raw) {
        if (!(raw instanceof Object[])) return Collections.emptyList();
        List<Segment> result = new ArrayList<>();
        for (Object item : (Object[]) raw) {
            try {
                Object category = field(item, "category");
                Object behaviour = category == null ? null : field(category, "behaviour");
                String name = String.valueOf(category == null ? "?" : field(category, "keyValue"));
                String key = behaviour == null ? "?" : String.valueOf(field(behaviour, "morpheKeyValue"));
                boolean auto = behaviour != null && Boolean.TRUE.equals(field(behaviour, "skipAutomatically"));
                long start = ((Number) field(item, "start")).longValue();
                long end = ((Number) field(item, "end")).longValue();
                result.add(new Segment(name, key, auto, start, end));
            } catch (Throwable t) {
                // A changed Morphe class: leave the segment out.
            }
        }
        Collections.sort(result, (a, b) -> Long.compare(a.start, b.start));
        return result;
    }

    static List<Segment> segments() {
        return segments(raw());
    }

    static String seconds(long ms) {
        return String.format(Locale.US, "%.1f", ms / 1000.0);
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getField(name);
        return f.get(target);
    }

    private static Method method(String name) {
        try {
            Method m = Class.forName(CONTROLLER).getMethod(name);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }
}
