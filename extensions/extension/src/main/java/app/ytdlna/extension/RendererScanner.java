package app.ytdlna.extension;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps track of the renderers on the network while the app is in the foreground, so the cast
 * button shows only when there is something to cast to.
 * <p>
 * Searches when the app comes to the foreground and then once a minute (3 s each); nothing runs
 * while the app is in the background or the screen is off. A renderer found by the SSDP search
 * counts as gone after two searches without an answer (UDP may lose one); saved renderers are
 * checked directly.
 */
final class RendererScanner {
    private static final long INTERVAL_MS = 60_000;
    private static final int SEARCH_MS = 3000;
    /** A renderer is gone after missing this many searches in a row. */
    private static final int MISSES_UNTIL_GONE = 2;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService background = Executors.newSingleThreadExecutor();

    /** Available renderers by key (UDN or location), with their missed searches. */
    private static final Map<String, DlnaDevice> available = new LinkedHashMap<>();
    private static final Map<String, Integer> misses = new LinkedHashMap<>();

    /** One instance: a method reference made at each call would not remove the posted one. */
    private static final Runnable SCAN = RendererScanner::scan;
    /** Renderers found by the SSDP search (the others were added by address). */
    private static final java.util.Set<String> searched = new java.util.HashSet<>();

    private static Context app;
    private static java.lang.ref.WeakReference<Activity> current = new java.lang.ref.WeakReference<>(null);

    /** The activity in the foreground, null if none (tests). */
    static Activity currentActivity() {
        return resumed > 0 ? current.get() : null;
    }
    private static int resumed;
    private static boolean scanning;
    private static long lastScan;

    private RendererScanner() {
    }

    /** Starts following the app's activities. Main thread; repeated calls do nothing. */
    static void init() {
        if (app != null) return;
        app = application();
        if (app == null) return;
        ((Application) app).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity activity) {
                current = new java.lang.ref.WeakReference<>(activity);
                if (resumed++ == 0) startScanning();
            }

            @Override
            public void onActivityPaused(Activity activity) {
                if (resumed > 0 && --resumed == 0) stopScanning();
            }

            @Override
            public void onActivityCreated(Activity activity, Bundle state) {
            }

            @Override
            public void onActivityStarted(Activity activity) {
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle state) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
            }
        });
        // Started while an activity is already in the foreground: its resume has passed.
        android.app.ActivityManager.RunningAppProcessInfo process = new android.app.ActivityManager.RunningAppProcessInfo();
        android.app.ActivityManager.getMyMemoryState(process);
        if (process.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
            resumed = 1;
            startScanning();
        }
    }

    @SuppressWarnings("PrivateApi")
    private static Context application() {
        try {
            return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Renderers found, in the order they were found. */
    static synchronized List<DlnaDevice> devices() {
        return new ArrayList<>(available.values());
    }

    static synchronized boolean hasDevices() {
        return !available.isEmpty();
    }

    /** Searches now (tests, or after adding a renderer). */
    static void scanNow() {
        main.post(() -> {
            main.removeCallbacks(SCAN);
            scan();
        });
    }

    private static void startScanning() {
        scanning = true;
        main.removeCallbacks(SCAN);
        // Coming back within the interval: keep the last result until the next regular search.
        long wait = Math.max(0, lastScan + INTERVAL_MS / 2 - SystemClock.elapsedRealtime());
        main.postDelayed(SCAN, wait);
    }

    private static void stopScanning() {
        scanning = false;
        main.removeCallbacks(SCAN);
    }

    private static void scan() {
        lastScan = SystemClock.elapsedRealtime();
        if (scanning) main.postDelayed(SCAN, INTERVAL_MS);
        Context context = app;
        if (context == null) return;
        background.execute(() -> {
            // This thread only: the search calls back on it.
            Map<String, DlnaDevice> found = new LinkedHashMap<>();
            for (String location : DlnaCast.savedDevices(context)) {
                try {
                    DlnaDevice device = DlnaDevice.load(location);
                    found.put(key(device), device);
                } catch (Exception e) {
                    Log.d("Saved renderer unreachable: " + location);
                }
            }
            Map<String, DlnaDevice> viaSearch = new LinkedHashMap<>();
            DlnaDiscovery.search(context, SEARCH_MS, device -> viaSearch.put(key(device), device));
            found.putAll(viaSearch);
            update(found, viaSearch.keySet());
        });
    }

    private static void update(Map<String, DlnaDevice> found, java.util.Set<String> viaSearch) {
        boolean changed;
        synchronized (RendererScanner.class) {
            boolean before = !available.isEmpty();
            for (Map.Entry<String, DlnaDevice> entry : found.entrySet()) {
                available.put(entry.getKey(), entry.getValue());
                misses.put(entry.getKey(), 0);
            }
            searched.addAll(viaSearch);
            for (String key : new ArrayList<>(available.keySet())) {
                if (found.containsKey(key)) continue;
                int missed = misses.getOrDefault(key, 0) + 1;
                // Only renderers found by the search may miss one (UDP); the others were
                // checked directly.
                if (missed >= MISSES_UNTIL_GONE || !searched.contains(key)) {
                    Log.i("Renderer gone: " + available.get(key));
                    available.remove(key);
                    misses.remove(key);
                    searched.remove(key);
                } else {
                    misses.put(key, missed);
                }
            }
            changed = before != !available.isEmpty();
        }
        if (changed) main.post(DlnaCast::onRenderersChanged);
    }

    /** Whether the SSDP search found this renderer (not only its saved address). */
    static synchronized boolean wasSearched(DlnaDevice device) {
        return searched.contains(key(device));
    }

    /** A saved address was removed: drop its renderer unless the search finds it too. */
    static void forget(String location) {
        boolean changed;
        synchronized (RendererScanner.class) {
            boolean before = !available.isEmpty();
            for (String key : new ArrayList<>(available.keySet())) {
                if (searched.contains(key) || !location.equals(available.get(key).location)) continue;
                available.remove(key);
                misses.remove(key);
            }
            changed = before != !available.isEmpty();
        }
        if (changed) main.post(DlnaCast::onRenderersChanged);
    }

    private static String key(DlnaDevice device) {
        return device.udn.isEmpty() ? device.location : device.udn;
    }
}
