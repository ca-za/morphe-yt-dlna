package app.ytdlna.extension;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * JNI bridge to the Rust stream proxy.
 * <p>
 * The patch puts libytdlna.so into the APK assets (assets/ytdlna/&lt;abi&gt;/), because the app
 * installs without extracting native libraries and those would have to be stored uncompressed.
 * The library is copied to the code cache once per app version and loaded from there.
 */
final class NativeProxy {
    private static final String LIBRARY = "libytdlna.so";

    private static Boolean loaded;

    private NativeProxy() {
    }

    /** Loads the library once. False if it is missing or can not be loaded on this device. */
    static synchronized boolean load(Context context) {
        if (loaded == null) {
            try {
                System.load(extract(context.getApplicationContext()).getAbsolutePath());
                loaded = true;
            } catch (Throwable t) {
                Log.e("Native stream proxy not available, using the Java proxy", t);
                loaded = false;
            }
        }
        return loaded;
    }

    private static File extract(Context context) throws IOException {
        AssetManager assets = context.getAssets();
        for (String abi : Build.SUPPORTED_ABIS) {
            String assetPath = "ytdlna/" + abi + "/" + LIBRARY;
            InputStream in;
            try {
                in = assets.open(assetPath);
            } catch (IOException missing) {
                continue;
            }
            long version;
            try {
                version = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
            } catch (Exception e) {
                version = 0;
            }
            // One file per app install/update, so an updated library is always picked up.
            File dir = new File(context.getCodeCacheDir(), "ytdlna/" + abi + "-" + version);
            File library = new File(dir, LIBRARY);
            try (InputStream source = in) {
                if (!library.isFile()) {
                    deleteRecursively(new File(context.getCodeCacheDir(), "ytdlna"));
                    if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("Can not create " + dir);
                    File temp = new File(dir, LIBRARY + ".tmp");
                    try (OutputStream out = new FileOutputStream(temp)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = source.read(buf)) != -1) out.write(buf, 0, n);
                    }
                    // Android 14+ only loads dynamically loaded code from read-only files.
                    if (!temp.setReadOnly() || !temp.renameTo(library)) {
                        throw new IOException("Can not install " + library);
                    }
                    Log.i("Extracted native proxy for " + abi);
                }
            }
            return library;
        }
        throw new IOException("No native proxy for ABIs of this device");
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    /** @return the port, or -1 on failure. */
    static native int nativeStart(int preferredPort);

    static native void nativeSetUserAgent(String userAgent);

    static native void nativeSetDebug(boolean enabled);

    static native void nativePutText(String path, String mimeType, String body);

    static native void nativePutStream(String path, String mimeType, String url, long contentLength);

    static native void nativeRemovePrefix(String prefix);

    /** Subscribes to renderer events. Returns the subscription token, or null. */
    static native String nativeSubscribe(String eventUrl, String callbackBase);

    static native void nativeUnsubscribe(String token);

    /** Next renderer event as "token\nname\nvalue", or null after the timeout. */
    static native String nativeWaitEvent(int timeoutMs);
}
