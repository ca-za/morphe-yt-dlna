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
                    // Saved renderer and stream address, as the dialogs would store them.
                    String device = intent.getStringExtra("device");
                    String host = intent.getStringExtra("host");
                    android.content.SharedPreferences prefs =
                            context.getSharedPreferences("ytdlna", Context.MODE_PRIVATE);
                    android.content.SharedPreferences.Editor editor = prefs.edit();
                    if (device != null) {
                        Set<String> devices = new HashSet<>();
                        devices.add(device);
                        editor.putStringSet("manual_devices", devices);
                    }
                    if (host != null) editor.putString("stream_host", host);
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
