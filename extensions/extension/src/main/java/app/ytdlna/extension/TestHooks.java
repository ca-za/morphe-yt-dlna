package app.ytdlna.extension;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Remote control for the automated regression tests (tests/regression).
 * <p>
 * Only active in builds patched with the option "testHooks"; the patch then changes
 * {@link #ENABLED} to true. Commands arrive as broadcasts:
 * <pre>
 * adb shell am broadcast -a app.ytdlna.TEST -p &lt;package&gt; --es cmd status --es id 42
 * </pre>
 * Every command answers with one logcat line, tag YtDlnaTest: {@code RESULT <id> <json>}.
 */
final class TestHooks {
    /** Replaced by the patch with true when the "testHooks" option is set. */
    private static final boolean ENABLED = false;
    private static final String ACTION = "app.ytdlna.TEST";

    private static boolean registered;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private TestHooks() {
    }

    /** The resumed activity from the app's ActivityThread (the scanner may start after its resume). */
    @SuppressWarnings("PrivateApi")
    private static android.app.Activity resumedActivity() {
        try {
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Object thread = threadClass.getMethod("currentActivityThread").invoke(null);
            java.lang.reflect.Field activitiesField = threadClass.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);
            for (Object record : ((java.util.Map<?, ?>) activitiesField.get(thread)).values()) {
                java.lang.reflect.Field paused = record.getClass().getDeclaredField("paused");
                paused.setAccessible(true);
                if (paused.getBoolean(record)) continue;
                java.lang.reflect.Field activity = record.getClass().getDeclaredField("activity");
                activity.setAccessible(true);
                return (android.app.Activity) activity.get(record);
            }
        } catch (Exception e) {
            Log.d("No resumed activity: " + e);
        }
        return null;
    }

    private static void collectCastButtons(android.view.View view, org.json.JSONArray out) throws org.json.JSONException {
        String name = view.getClass().getName();
        if (name.endsWith("MediaRouteButton") || name.endsWith("MdxEntryPointButton")
                || view.getClass().getSuperclass() != null && view.getClass().getSuperclass().getName().endsWith("MediaRouteButton")) {
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            String id = "";
            try {
                if (view.getId() != android.view.View.NO_ID) id = view.getResources().getResourceEntryName(view.getId());
            } catch (Exception ignored) {
                // No resource name.
            }
            // Faded out (YouTube's player controls) counts as hidden for the eye.
            float alpha = 1;
            for (android.view.ViewParent p = view.getParent(); p instanceof android.view.View; p = p.getParent()) {
                alpha *= ((android.view.View) p).getAlpha();
            }
            out.put(new org.json.JSONObject().put("class", view.getClass().getSimpleName()).put("id", id)
                    .put("shown", view.isShown()).put("enabled", view.isEnabled())
                    .put("alpha", alpha * view.getAlpha())
                    .put("x", location[0]).put("y", location[1])
                    .put("width", view.getWidth()).put("height", view.getHeight()));
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectCastButtons(group.getChildAt(i), out);
        }
    }

    static boolean isEnabled() {
        return ENABLED;
    }

    /** Tests of plain AVTransport renderers: OpenHome of the test renderer is not used. */
    private static volatile boolean avTransportOnly;

    static boolean avTransportOnly() {
        // isEnabled(), not ENABLED: the patch switches the method on, the constant is inlined.
        return isEnabled() && avTransportOnly;
    }

    /** Registers the command receiver once, if enabled. */
    static synchronized void register(Context context) {
        if (registered || !isEnabled() || context == null) return;
        registered = true;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                String id = intent.getStringExtra("id");
                String cmd = intent.getStringExtra("cmd");
                main.post(() -> run(c.getApplicationContext(), id == null ? "-" : id, cmd, intent));
            }
        };
        IntentFilter filter = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            context.getApplicationContext().registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.getApplicationContext().registerReceiver(receiver, filter);
        }
        android.util.Log.i("YtDlnaTest", "READY");
    }

    private static void run(Context context, String id, String cmd, Intent intent) {
        JSONObject result = new JSONObject();
        try {
            result.put("cmd", cmd);
            switch (cmd == null ? "" : cmd) {
                case "setup": {
                    // Saved renderers ("|" between several) and stream address, as the dialogs
                    // would store them.
                    String device = intent.getStringExtra("device");
                    String host = intent.getStringExtra("host");
                    android.content.SharedPreferences prefs =
                            context.getSharedPreferences("ytdlna", Context.MODE_PRIVATE);
                    android.content.SharedPreferences.Editor editor = prefs.edit();
                    if (device != null) {
                        Set<String> devices = new HashSet<>(java.util.Arrays.asList(device.split("\\|")));
                        editor.putStringSet("manual_devices", devices);
                    }
                    if (host != null) editor.putString("stream_host", host);
                    if (intent.hasExtra("showCastButton")) {
                        // Morphe's "Hide cast button" (YouTube, YouTube Music), on by default. It
                        // is read at the app start: "restart" tells the tests to start it again.
                        android.content.SharedPreferences morphe =
                                context.getSharedPreferences("morphe_prefs", Context.MODE_PRIVATE);
                        boolean hide = !Boolean.parseBoolean(intent.getStringExtra("showCastButton"));
                        boolean changed = false;
                        for (String key : new String[]{"morphe_hide_cast_button", "morphe_music_hide_cast_button"}) {
                            if (morphe.getBoolean(key, true) != hide) changed = true;
                        }
                        morphe.edit().putBoolean("morphe_hide_cast_button", hide)
                                .putBoolean("morphe_music_hide_cast_button", hide).commit();
                        result.put("restart", changed);
                    }
                    if (intent.hasExtra("googleCastLink")) {
                        context.getSharedPreferences("morphe_prefs", Context.MODE_PRIVATE).edit()
                                .putBoolean("ytdlna_google_cast_link",
                                        Boolean.parseBoolean(intent.getStringExtra("googleCastLink"))).commit();
                    }
                    if (intent.hasExtra("preferHls")) {
                        StreamProxy.preferHls = Boolean.parseBoolean(intent.getStringExtra("preferHls"));
                    }
                    if (intent.hasExtra("avTransportOnly")) {
                        avTransportOnly = Boolean.parseBoolean(intent.getStringExtra("avTransportOnly"));
                    }
                    if (intent.hasExtra("albumArt")) {
                        context.getSharedPreferences("morphe_prefs", Context.MODE_PRIVATE).edit()
                                .putBoolean("ytdlna_album_art_lookup",
                                        Boolean.parseBoolean(intent.getStringExtra("albumArt"))).apply();
                    }
                    editor.apply();
                    result.put("ok", true);
                    break;
                }
                case "castButtons": {
                    // Cast buttons of the visible activity: shown (with all parents) and where.
                    org.json.JSONArray buttons = new org.json.JSONArray();
                    android.app.Activity activity = RendererScanner.currentActivity();
                    if (activity == null) activity = resumedActivity();
                    if (activity != null) collectCastButtons(activity.getWindow().getDecorView(), buttons);
                    result.put("buttons", buttons);
                    result.put("ok", true);
                    break;
                }
                case "rescan":
                    // Searches now; the result shows in "status" (renderers) after about 3 s.
                    RendererScanner.scanNow();
                    result.put("ok", true);
                    break;
                case "stop":
                    CastSession.stopActive(true);
                    result.put("ok", true);
                    break;
                case "local": {
                    // Commands to the app's player, through its media session like a user would.
                    LocalPlayer player = new LocalPlayer(context, main, new LocalPlayer.Listener() {
                        @Override
                        public void onLocalState(PlaybackState state) {
                        }

                        @Override
                        public void onQueue(java.util.List<LocalPlayer.QueueEntry> queue) {
                        }
                    });
                    String action = intent.getStringExtra("action");
                    player.attach(() -> {
                        switch (action == null ? "" : action) {
                            case "play": player.play(); break;
                            case "pause": player.pause(); break;
                            case "next": player.next(); break;
                            case "seek": player.seekTo(Long.parseLong(intent.getStringExtra("ms"))); break;
                            default: break;
                        }
                        player.detach();
                    });
                    result.put("ok", true);
                    break;
                }
                case "status":
                default:
                    result.put("ok", true);
                    status(context, result);
                    break;
            }
        } catch (Exception e) {
            try {
                result.put("ok", false).put("error", String.valueOf(e));
            } catch (Exception ignored) {
            }
        }
        android.util.Log.i("YtDlnaTest", "RESULT " + id + " " + result);
    }

    private static void status(Context context, JSONObject out) throws Exception {
        out.put("package", context.getPackageName());
        out.put("currentVideoId", StreamCapture.currentVideoId());
        out.put("musicAudioMode", MusicMode.isAudio());
        out.put("muted", CastAudio.isMuted());
        CastSession session = CastSession.active();
        out.put("casting", session != null);
        out.put("renderers", RendererScanner.devices().size());
        out.put("googleCast", DlnaCast.googleCastAvailable);
        if (session != null) out.put("session", session.status());
        // State of the app's player, from its media session.
        LocalPlayer player = new LocalPlayer(context, main, new LocalPlayer.Listener() {
            @Override
            public void onLocalState(PlaybackState state) {
            }

            @Override
            public void onQueue(java.util.List<LocalPlayer.QueueEntry> queue) {
            }
        });
        final JSONObject local = new JSONObject();
        player.attachNow();
        PlaybackState state = player.state();
        local.put("state", state == null ? -1 : state.getState());
        local.put("position", player.position());
        local.put("queue", player.queue().size());
        player.detach();
        out.put("local", local);
    }
}
