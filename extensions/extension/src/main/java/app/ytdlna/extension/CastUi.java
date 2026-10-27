package app.ytdlna.extension;

import android.app.Activity;
import android.app.Application;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/**
 * Cast UI while a DLNA cast is active, in every screen of the app:
 * a "Playing on ..." bar (tap: device picker) and the volume keys controlling the TV.
 */
final class CastUi implements Application.ActivityLifecycleCallbacks {
    private static final String BANNER_TAG = "ytdlna-cast-banner";
    private static final int VOLUME_STEP = 5;

    private static CastUi instance;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private WeakReference<Activity> current = new WeakReference<>(null);
    private String volumeText = "";
    private final Runnable clearVolumeText = () -> {
        volumeText = "";
        refresh();
    };

    private CastUi() {
    }

    /** Registers for activity changes once and attaches to {@code activity}. */
    static void install(Activity activity) {
        if (instance == null) {
            instance = new CastUi();
            activity.getApplication().registerActivityLifecycleCallbacks(instance);
        }
        instance.attach(activity);
    }

    /** Updates the bar after a cast started or stopped. */
    static void update() {
        if (instance != null) main.post(instance::refresh);
    }

    private void attach(Activity activity) {
        current = new WeakReference<>(activity);
        wrapWindowCallback(activity);
        refresh();
    }

    private void refresh() {
        Activity activity = current.get();
        if (activity == null || activity.isFinishing()) return;
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        View existing = decor.findViewWithTag(BANNER_TAG);
        CastSession session = CastSession.active();
        if (session == null) {
            if (existing != null) decor.removeView(existing);
            return;
        }
        TextView banner = existing instanceof TextView ? (TextView) existing : createBanner(activity, decor);
        String text = "▶  " + DlnaCast.t("Wird auf ", "Playing on ") + session.device.friendlyName
                + DlnaCast.t(" abgespielt", "");
        if (!volumeText.isEmpty()) text += "  ·  " + volumeText;
        banner.setText(text);
    }

    private TextView createBanner(Activity activity, ViewGroup decor) {
        float density = activity.getResources().getDisplayMetrics().density;
        TextView banner = new TextView(activity);
        banner.setTag(BANNER_TAG);
        banner.setTextColor(0xFFFFFFFF);
        banner.setTextSize(13);
        banner.setSingleLine(true);
        int horizontal = (int) (14 * density);
        int vertical = (int) (6 * density);
        banner.setPadding(horizontal, vertical, horizontal, vertical);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xE6202124);
        background.setCornerRadius(16 * density);
        banner.setBackground(background);
        banner.setElevation(8 * density);
        banner.setOnClickListener(v -> DlnaCast.showPicker(activity, null, StreamCapture.current(), null));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        params.topMargin = statusBarHeight(decor, density) + (int) (4 * density);
        decor.addView(banner, params);
        return banner;
    }

    private static int statusBarHeight(View decor, float density) {
        WindowInsets insets = decor.getRootWindowInsets();
        if (insets != null && Build.VERSION.SDK_INT >= 30) {
            return insets.getInsets(WindowInsets.Type.statusBars()).top;
        }
        return (int) (24 * density);
    }

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
                volumeText = DlnaCast.t("Lautstärke ", "Volume ") + volume;
                refresh();
                main.removeCallbacks(clearVolumeText);
                main.postDelayed(clearVolumeText, 2500);
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
