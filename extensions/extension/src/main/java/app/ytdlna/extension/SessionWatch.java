package app.ytdlna.extension;

import android.app.ActivityManager;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds DLNA sessions in the Wi-Fi in the background (patch "DLNA session notifications",
 * YouTube Music): a renderer that plays a title cast from this patch (our metadata) shows as
 * "Playing on <renderer>: <title>" in a notification. See docs/remote-sessions.md.
 * <p>
 * Battery: no service runs and nothing listens. A job runs when the phone joins an unmetered
 * network and every 20 minutes or so on one; it searches for 2.5 s and ends. It runs in its own
 * process (":ytdlna"), where the patched app class skips the app's own start-up: only this code
 * runs there, nothing of YouTube Music or Morphe.
 */
public final class SessionWatch {
    private static final String PROCESS_SUFFIX = ":ytdlna";
    private static final int JOB_PERIODIC = 0x59744401;
    private static final int JOB_NOW = 0x59744402;
    private static final long PERIOD_MS = 20 * 60_000L;
    private static final long FLEX_MS = 10 * 60_000L;
    private static final int SEARCH_MS = 2500;
    private static final String CHANNEL_ID = "ytdlna_sessions";
    private static final int NOTIFICATION_ID = 0x59744403;
    /** Exists while this phone casts (written by the app's own process). */
    private static final String CASTING_FILE = "ytdlna-casting";

    private static Boolean ownProcess;

    private SessionWatch() {
    }

    // region Process

    /**
     * Whether this is the job's own process. Called by the patched app class first thing: there,
     * the app's own start-up is skipped. Cheap, as it runs at every start of every process.
     */
    public static boolean isOwnProcess() {
        Boolean own = ownProcess;
        if (own == null) {
            String name = processName();
            ownProcess = own = name != null && name.endsWith(PROCESS_SUFFIX);
        }
        return own;
    }

    private static String processName() {
        if (Build.VERSION.SDK_INT >= 28) return Application.getProcessName();
        try (FileInputStream in = new FileInputStream("/proc/self/cmdline")) {
            byte[] buffer = new byte[256];
            int n = in.read(buffer);
            int end = 0;
            while (end < Math.max(n, 0) && buffer[end] != 0) end++;
            return new String(buffer, 0, end, StandardCharsets.US_ASCII);
        } catch (Exception e) {
            return null;
        }
    }

    // endregion

    // region Scheduling (app process)

    /**
     * Schedules the periodic job and the Wi-Fi trigger, if the patch added the job to the app
     * (this code is in every build). Called when the app starts; cheap if already scheduled.
     */
    static void schedule(Context context) {
        try {
            Context app = context.getApplicationContext();
            ComponentName job = new ComponentName(app, Job.class);
            app.getPackageManager().getServiceInfo(job, 0);
            // The app just started: no cast runs (a flag left by a stopped app is stale).
            setCasting(app, false);
            JobScheduler scheduler = app.getSystemService(JobScheduler.class);
            if (scheduler == null) return;
            if (scheduler.getPendingJob(JOB_PERIODIC) == null) {
                scheduler.schedule(new JobInfo.Builder(JOB_PERIODIC, job)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                        .setPeriodic(PERIOD_MS, FLEX_MS)
                        .build());
                Log.i("Session search scheduled every " + PERIOD_MS / 60_000 + " min on Wi-Fi");
            }
            registerNetworkTrigger(app);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            // The patch is not applied: nothing to do.
        } catch (Throwable t) {
            Log.d("Session search not scheduled: " + t);
        }
    }

    /** Wakes the job's process when the phone joins an unmetered network (also right away once). */
    private static void registerNetworkTrigger(Context app) {
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        Intent intent = new Intent(app, NetworkReceiver.class);
        PendingIntent pending = PendingIntent.getBroadcast(app, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        // The same PendingIntent replaces an earlier registration.
        cm.registerNetworkCallback(new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                .build(), pending);
    }

    /** This phone casts (or stopped): no notification about its own session. App process. */
    static void setCasting(Context context, boolean casting) {
        try {
            File file = new File(context.getApplicationContext().getFilesDir(), CASTING_FILE);
            if (casting) {
                file.createNewFile();
            } else {
                file.delete();
            }
        } catch (Exception ignored) {
            // The notification may then show this phone's own session: no harm.
        }
    }

    // endregion

    // region Job (own process)

    /** The search; runs in the job's own process. */
    public static final class Job extends JobService {
        @Override
        public boolean onStartJob(JobParameters params) {
            Thread thread = new Thread(() -> {
                try {
                    check(this);
                } catch (Throwable t) {
                    Log.d("Session search failed: " + t);
                } finally {
                    jobFinished(params, false);
                }
            }, "YtDlna-sessions");
            thread.start();
            return true;
        }

        @Override
        public boolean onStopJob(JobParameters params) {
            return false;
        }
    }

    /** The Wi-Fi trigger: runs the search soon, as a job (on an unmetered network). */
    public static final class NetworkReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                JobScheduler scheduler = context.getSystemService(JobScheduler.class);
                if (scheduler == null) return;
                scheduler.schedule(new JobInfo.Builder(JOB_NOW, new ComponentName(context, Job.class))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                        // The network settles first (DHCP, renderers answering).
                        .setMinimumLatency(5000)
                        .build());
            } catch (Throwable t) {
                Log.d("Session search not started: " + t);
            }
        }
    }

    private static final class Session {
        final String renderer;
        final String title;

        Session(String renderer, String title) {
            this.renderer = renderer;
            this.title = title;
        }
    }

    /** Searches the renderers, and shows (or removes) the notification. */
    static void check(Context context) {
        long started = SystemClock.elapsedRealtime();
        long cpuStart = android.os.Process.getElapsedCpuTime();
        if (castingHere(context)) {
            Log.d("Session search: this phone casts, skipped");
            cancel(context);
            return;
        }
        List<Session> sessions = new ArrayList<>();
        java.util.Set<String> checked = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        DlnaDiscovery.Listener listener = device -> {
            if (!checked.add(device.location)) return;
            Session session = session(device);
            if (session != null) {
                synchronized (sessions) {
                    sessions.add(session);
                }
            }
        };
        DlnaDiscovery.search(context, SEARCH_MS, listener);
        // Renderers added by address (also where multicast does not get through).
        for (String location : manualDevices(context)) {
            if (checked.contains(location)) continue;
            try {
                listener.onDevice(DlnaDevice.load(location));
            } catch (Exception e) {
                Log.d("Session check of " + location + " failed: " + e.getMessage());
            }
        }
        if (sessions.isEmpty()) {
            cancel(context);
        } else {
            notify(context, sessions);
        }
        Log.i("Session search: " + sessions.size() + " sessions, " + (SystemClock.elapsedRealtime() - started)
                + " ms, " + (android.os.Process.getElapsedCpuTime() - cpuStart) + " ms CPU in process "
                + processName());
    }

    /** The renderers the user added by address (stored by the app, see DlnaCast). */
    private static java.util.Set<String> manualDevices(Context context) {
        try {
            return new java.util.HashSet<>(context.getSharedPreferences("ytdlna", Context.MODE_PRIVATE)
                    .getStringSet("manual_devices", java.util.Collections.emptySet()));
        } catch (Exception e) {
            return java.util.Collections.emptySet();
        }
    }

    /** This phone casts: the app's process runs and wrote the flag. */
    private static boolean castingHere(Context context) {
        File file = new File(context.getApplicationContext().getFilesDir(), CASTING_FILE);
        if (!file.exists()) return false;
        // A flag left by an app that was stopped while casting does not count.
        ActivityManager am = context.getSystemService(ActivityManager.class);
        if (am == null || am.getRunningAppProcesses() == null) return false;
        String main = context.getPackageName();
        for (ActivityManager.RunningAppProcessInfo process : am.getRunningAppProcesses()) {
            if (main.equals(process.processName)) return true;
        }
        file.delete();
        return false;
    }

    private static final Pattern TITLE = Pattern.compile("<dc:title>(.*?)</dc:title>", Pattern.DOTALL);

    /** The renderer's current title if it plays (or pauses) one cast from this patch. */
    private static Session session(DlnaDevice device) {
        try {
            String state;
            String didl;
            if (device.infoControlUrl != null && device.hasOpenHomePlaylist()) {
                state = OpenHome.toAvTransportState(OpenHome.transportState(device));
                didl = tag(DlnaController.soap(device.infoControlUrl, device.infoType, "Track", ""), "Metadata");
            } else if (device.avTransportControlUrl != null) {
                state = DlnaController.transportState(device);
                didl = tag(DlnaController.soap(device.avTransportControlUrl, device.avTransportType, "GetMediaInfo",
                        "<InstanceID>0</InstanceID>"), "CurrentURIMetaData");
            } else {
                return null;
            }
            didl = unescape(didl);
            boolean ours = didl.contains("id=\"ytdlna-") || didl.contains(RendererExtras.NAMESPACE);
            boolean active = "PLAYING".equals(state) || "PAUSED_PLAYBACK".equals(state) || "TRANSITIONING".equals(state);
            if (!ours || !active) return null;
            Matcher m = TITLE.matcher(didl);
            String title = m.find() ? unescape(m.group(1)).trim() : "";
            return new Session(device.friendlyName, title);
        } catch (Exception e) {
            Log.d("Session check of " + device + " failed: " + e.getMessage());
            return null;
        }
    }

    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + "(?:\\s[^>]*)?>(.*?)</" + name + ">", Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1) : "";
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&#39;", "'").replace("&amp;", "&");
    }

    // endregion

    // region Notification

    private static String t(String german, String english) {
        return Locale.getDefault().getLanguage().equals("de") ? german : english;
    }

    private static void notify(Context context, List<Session> sessions) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, t("DLNA-Sessions", "DLNA sessions"),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(t("Wiedergaben auf DLNA-Geräten im WLAN",
                    "Playback on DLNA devices in the Wi-Fi"));
            manager.createNotificationChannel(channel);
        }
        Session first = sessions.get(0);
        String title = t("Läuft auf ", "Playing on ") + first.renderer;
        StringBuilder text = new StringBuilder(first.title);
        for (int i = 1; i < sessions.size(); i++) {
            Session s = sessions.get(i);
            text.append('\n').append(s.renderer).append(": ").append(s.title);
        }
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        PendingIntent open = launch == null ? null : PendingIntent.getActivity(context, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(first.title)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build();
        manager.notify(NOTIFICATION_ID, notification);
    }

    private static void cancel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
    }

    // endregion
}
