package app.ytdlna.extension;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Logcat helper. Filter with: adb logcat -s YtDlna
 * <p>
 * The last lines are also kept in memory, so they can be shared from the device picker without a
 * computer (see {@link #text()}).
 */
final class Log {
    private static final String TAG = "YtDlna";
    private static final int MAX_LINES = 3000;
    /** Shared as intent text: stays well below the binder limit. */
    private static final int MAX_SHARE_CHARS = 400_000;
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private Log() {
    }

    static void d(String message) {
        android.util.Log.d(TAG, message);
        keep("D", message);
    }

    static void i(String message) {
        android.util.Log.i(TAG, message);
        keep("I", message);
    }

    static void e(String message, Throwable error) {
        android.util.Log.e(TAG, message, error);
        keep("E", message + (error == null ? "" : ": " + error));
    }

    private static void keep(String level, String message) {
        synchronized (lines) {
            String line = TIME.format(new Date()) + " " + level + " " + message;
            lines.addLast(line);
            while (lines.size() > MAX_LINES) lines.removeFirst();
        }
    }

    /** The kept lines, newest last; the oldest are dropped if they would not fit in a share. */
    static String text() {
        synchronized (lines) {
            StringBuilder out = new StringBuilder();
            java.util.Iterator<String> it = lines.descendingIterator();
            java.util.ArrayList<String> picked = new java.util.ArrayList<>();
            int size = 0;
            while (it.hasNext()) {
                String line = it.next();
                if (size + line.length() + 1 > MAX_SHARE_CHARS) break;
                picked.add(line);
                size += line.length() + 1;
            }
            for (int i = picked.size() - 1; i >= 0; i--) out.append(picked.get(i)).append('\n');
            return out.toString();
        }
    }
}
