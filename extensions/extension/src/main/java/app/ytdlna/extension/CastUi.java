package app.ytdlna.extension;

import android.app.Activity;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.Window;
import android.widget.Toast;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/**
 * Cast UI while a DLNA cast is active:
 * <ul>
 *   <li>a notification "Playing on ..." with a stop action, like YouTube's cast notification,</li>
 *   <li>the video area covered by the cast screen ({@link VideoCover}),</li>
 *   <li>the volume keys controlling the TV.</li>
 * </ul>
 */
final class CastUi implements Application.ActivityLifecycleCallbacks {
    private static final int VOLUME_STEP = 5;
    private static final String CHANNEL_ID = "ytdlna_cast";
    private static final int NOTIFICATION_ID = 0x7d1a;
    private static final String ACTION_STOP = "app.ytdlna.action.STOP_CAST";

    private static CastUi instance;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Context appContext;
    private String nowPlaying = "";
    private Toast volumeToast;

    private CastUi() {
    }

    /** Registers for activity changes once and attaches to {@code activity}. */
    static void install(Activity activity) {
        if (instance == null) {
            instance = new CastUi();
            instance.appContext = activity.getApplicationContext();
            activity.getApplication().registerActivityLifecycleCallbacks(instance);
            instance.registerStopReceiver();
        }
        instance.attach(activity);
    }

    /** Updates the notification and cast screen after a cast started or stopped. */
    static void update() {
        if (instance != null) main.post(() -> {
            instance.updateNotification();
            VideoCover.update(null, null);
        });
    }

    /** A new title plays on the renderer. */
    static void onTitle(String title, String artUrl) {
        if (instance != null) main.post(() -> {
            VideoCover.update(title, artUrl);
            instance.nowPlaying = title == null ? "" : title;
            instance.updateNotification();
        });
    }

    private void attach(Activity activity) {
        wrapWindowCallback(activity);
        VideoCover.attach(activity);
    }

    // region Notification

    private void registerStopReceiver() {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                CastSession.stopActive(true);
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_STOP);
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            appContext.registerReceiver(receiver, filter);
        }
    }

    private void updateNotification() {
        NotificationManager manager = appContext.getSystemService(NotificationManager.class);
        if (manager == null) return;
        CastSession session = CastSession.active();
        if (session == null) {
            manager.cancel(NOTIFICATION_ID);
            nowPlaying = "";
            return;
        }
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    DlnaCast.t("DLNA-Streaming", "DLNA casting"), NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
        }
        Intent stop = new Intent(ACTION_STOP).setPackage(appContext.getPackageName());
        PendingIntent stopIntent = PendingIntent.getBroadcast(appContext, 0, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent open = appContext.getPackageManager().getLaunchIntentForPackage(appContext.getPackageName());
        PendingIntent openIntent = open == null ? null : PendingIntent.getActivity(appContext, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(DlnaCast.t("Wird auf ", "Playing on ") + session.device.friendlyName
                        + DlnaCast.t(" abgespielt", ""))
                .setContentText(nowPlaying)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null,
                        DlnaCast.t("Stoppen", "Stop"), stopIntent).build())
                .build();
        try {
            manager.notify(NOTIFICATION_ID, notification);
        } catch (SecurityException e) {
            Log.d("Notification not allowed: " + e.getMessage());
        }
    }

    // endregion

    // region Volume keys

    /** Routes volume keys to the TV while casting, keeping everything else as it was. */
    private void wrapWindowCallback(Activity activity) {
        Window window = activity.getWindow();
        Window.Callback original = window.getCallback();
        if (original == null || Proxy.isProxyClass(original.getClass())) return;
        Window.Callback wrapped = (Window.Callback) Proxy.newProxyInstance(
                Window.Callback.class.getClassLoader(),
                new Class<?>[]{Window.Callback.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("dispatchKeyEvent") && args != null
                            && args[0] instanceof KeyEvent && handleVolumeKey((KeyEvent) args[0])) {
                        return true;
                    }
                    try {
                        return method.invoke(original, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        window.setCallback(wrapped);
    }

    private boolean handleVolumeKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        CastSession session = CastSession.active();
        if (session == null) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            session.adjustVolume(code == KeyEvent.KEYCODE_VOLUME_UP ? VOLUME_STEP : -VOLUME_STEP, volume -> main.post(() -> {
                // One toast, updated while the key is pressed repeatedly.
                if (volumeToast != null) volumeToast.cancel();
                volumeToast = Toast.makeText(appContext, session.device.friendlyName + ": "
                        + DlnaCast.t("Lautstärke ", "Volume ") + volume, Toast.LENGTH_SHORT);
                volumeToast.show();
            }));
        }
        return true;
    }

    // endregion

    @Override
    public void onActivityResumed(Activity activity) {
        attach(activity);
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }
}
