package app.ytdlna.extension;

/** Logcat helper. Filter with: adb logcat -s YtDlna */
final class Log {
    private static final String TAG = "YtDlna";

    private Log() {
    }

    static void d(String message) {
        android.util.Log.d(TAG, message);
    }

    static void i(String message) {
        android.util.Log.i(TAG, message);
    }

    static void e(String message, Throwable error) {
        android.util.Log.e(TAG, message, error);
    }
}
