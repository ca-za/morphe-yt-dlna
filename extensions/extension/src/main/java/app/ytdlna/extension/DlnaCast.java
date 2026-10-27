package app.ytdlna.extension;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Cast button handling: device picker and starting playback on a renderer. */
@SuppressWarnings("unused")
public final class DlnaCast {
    private static final String PREFS = "ytdlna";
    private static final String PREF_MANUAL_DEVICES = "manual_devices";
    private static final String PREF_STREAM_HOST = "stream_host";
    private static final int SEARCH_MS = 3000;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService background = Executors.newCachedThreadPool();

    /** Set while re-dispatching a click to the original Google Cast dialog. */
    private static boolean passThroughClick;

    private enum Kind {DEVICE, SEARCHING, GOOGLE_CAST, ADD_DEVICE, STREAM_HOST, STOP}

    private static final class Entry {
        final Kind kind;
        final DlnaDevice device;
        final String label;

        Entry(Kind kind, DlnaDevice device, String label) {
            this.kind = kind;
            this.device = device;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private DlnaCast() {
    }

    /**
     * Injection point: MediaRouteButton.performClick().
     *
     * @return true if the click was handled and the original cast dialog must not open.
     */
    public static boolean onCastButtonClick(View button) {
        if (passThroughClick) {
            // Re-dispatched click for Google Cast. May pass several hooked
            // performClick methods (subclass and superclass) in one call.
            return false;
        }
        try {
            Activity activity = findActivity(button.getContext());
            if (activity == null) return false;
            showPicker(activity, button, currentStream(), null);
            return true;
        } catch (Throwable t) {
            Log.e("Could not show DLNA picker", t);
            return false;
        }
    }

    /**
     * Injection point: MediaRouteButton.setVisibility(int).
     * The app hides the button without Google Cast devices; keep it for DLNA.
     */
    public static int getCastButtonVisibility(int visibility) {
        return visibility == View.GONE ? View.VISIBLE : visibility;
    }

    /** Injection point: MediaRouteButton.setEnabled(boolean). */
    public static boolean getCastButtonEnabled(boolean enabled) {
        return true;
    }

    /**
     * Shows the device picker.
     *
     * @param castButton The original cast button, or null if not opened from it.
     * @param onDone     Called once the picker and any follow-up dialog are closed. May be null.
     */
    static void showPicker(Activity activity, View castButton, StreamInfo current, Runnable onDone) {
        boolean[] followUp = {false};
        List<Entry> devices = new ArrayList<>();
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, dialogTheme(activity));
        ArrayAdapter<Entry> adapter = new ArrayAdapter<>(builder.getContext(), android.R.layout.simple_list_item_1);
        Set<String> seen = new HashSet<>();
        boolean[] searching = {true};

        Runnable rebuild = () -> {
            adapter.clear();
            adapter.addAll(devices);
            if (searching[0]) adapter.add(new Entry(Kind.SEARCHING, null, t("Suche Geräte…", "Searching for devices…")));
            else if (devices.isEmpty()) adapter.add(new Entry(Kind.SEARCHING, null, t("Keine DLNA-Geräte gefunden", "No DLNA devices found")));
            CastSession session = CastSession.active();
            if (session != null) {
                adapter.add(new Entry(Kind.STOP, session.device, t("Wiedergabe stoppen auf ", "Stop playback on ") + session.device.friendlyName));
            }
            if (castButton != null) {
                adapter.add(new Entry(Kind.GOOGLE_CAST, null, t("Google Cast…", "Google Cast…")));
            }
            adapter.add(new Entry(Kind.ADD_DEVICE, null, t("Gerät per Adresse hinzufügen…", "Add device by address…")));
            adapter.add(new Entry(Kind.STREAM_HOST, null, t("Stream-Adresse dieses Geräts…", "This device's stream address…")));
            adapter.notifyDataSetChanged();
        };
        rebuild.run();

        String title = current == null
                ? t("DLNA: Kein Video geladen", "DLNA: No video loaded")
                : t("Streamen: ", "Cast: ") + current.title;
        AlertDialog dialog = builder
                .setTitle(title)
                .setAdapter(adapter, null)
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(d -> {
                    if (!followUp[0] && onDone != null) onDone.run();
                })
                .create();
        dialog.getListView().setOnItemClickListener((parent, view, position, id) -> {
            Entry entry = adapter.getItem(position);
            if (entry == null || entry.kind == Kind.SEARCHING) return;
            followUp[0] = entry.kind == Kind.ADD_DEVICE || entry.kind == Kind.STREAM_HOST;
            dialog.dismiss();
            switch (entry.kind) {
                case DEVICE:
                    cast(activity, entry.device, current);
                    break;
                case STOP:
                    stop(activity);
                    break;
                case GOOGLE_CAST:
                    passThroughClick = true;
                    try {
                        castButton.performClick();
                    } finally {
                        passThroughClick = false;
                    }
                    break;
                case ADD_DEVICE:
                    showAddDevice(activity, onDone);
                    break;
                case STREAM_HOST:
                    showStreamHost(activity, onDone);
                    break;
                default:
                    break;
            }
        });
        dialog.show();

        DlnaDiscovery.Listener onDevice = device -> main.post(() -> {
            String key = device.udn.isEmpty() ? device.location : device.udn;
            if (!seen.add(key)) return;
            devices.add(new Entry(Kind.DEVICE, device, device.toString()));
            rebuild.run();
        });
        background.execute(() -> {
            for (String location : prefs(activity).getStringSet(PREF_MANUAL_DEVICES, new HashSet<>())) {
                background.execute(() -> {
                    try {
                        onDevice.onDevice(DlnaDevice.load(location));
                    } catch (Exception e) {
                        Log.d("Saved device unreachable: " + location + " " + e.getMessage());
                    }
                });
            }
            DlnaDiscovery.search(activity, SEARCH_MS, onDevice);
            main.post(() -> {
                searching[0] = false;
                rebuild.run();
            });
        });
    }

    private static void cast(Activity activity, DlnaDevice device, StreamInfo info) {
        if (info == null) {
            toast(activity, t("Erst ein Video starten", "Start a video first"));
            return;
        }
        Context app = activity.getApplicationContext();
        StreamProxy.userAgent = userAgent(app);
        toast(activity, t("Verbinde mit ", "Connecting to ") + device.friendlyName + "…");
        CastUi.install(activity);
        CastSession.start(app, device, info, prefs(app).getString(PREF_STREAM_HOST, ""));
    }

    private static void stop(Activity activity) {
        CastSession.stopActive(true);
        toast(activity, t("Gestoppt", "Stopped"));
    }

    private static void showAddDevice(Activity activity, Runnable onDone) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, dialogTheme(activity));
        EditText input = new EditText(builder.getContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("http://192.168.1.20:49152/description.xml");
        builder
                .setTitle(t("Adresse der Gerätebeschreibung", "Device description URL"))
                .setView(padded(builder.getContext(), input, t(
                        "Die URL der UPnP-Beschreibung (description.xml) des Empfängers.",
                        "The UPnP description (description.xml) URL of the renderer.")))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String location = input.getText().toString().trim();
                    if (location.isEmpty()) return;
                    if (!location.startsWith("http://")) location = "http://" + location;
                    String finalLocation = location;
                    background.execute(() -> {
                        try {
                            DlnaDevice device = DlnaDevice.load(finalLocation);
                            SharedPreferences prefs = prefs(activity);
                            Set<String> saved = new HashSet<>(prefs.getStringSet(PREF_MANUAL_DEVICES, new HashSet<>()));
                            saved.add(finalLocation);
                            prefs.edit().putStringSet(PREF_MANUAL_DEVICES, saved).apply();
                            toast(activity, t("Hinzugefügt: ", "Added: ") + device.friendlyName);
                        } catch (Exception e) {
                            toast(activity, t("Gerät nicht erreichbar: ", "Device unreachable: ") + e.getMessage());
                        }
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(d -> {
                    if (onDone != null) onDone.run();
                })
                .show();
    }

    private static void showStreamHost(Activity activity, Runnable onDone) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, dialogTheme(activity));
        EditText input = new EditText(builder.getContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(prefs(activity).getString(PREF_STREAM_HOST, ""));
        input.setHint(t("automatisch", "automatic"));
        builder
                .setTitle(t("Stream-Adresse", "Stream address"))
                .setView(padded(builder.getContext(), input, t(
                        "Adresse, unter der der Empfänger dieses Gerät erreicht. Leer lassen für automatisch.",
                        "Address the renderer uses to reach this device. Leave empty for automatic.")))
                .setPositiveButton(android.R.string.ok, (d, w) ->
                        prefs(activity).edit().putString(PREF_STREAM_HOST, input.getText().toString().trim()).apply())
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(d -> {
                    if (onDone != null) onDone.run();
                })
                .show();
    }

    // region Helpers

    /** The video that was loaded most recently. */
    private static StreamInfo currentStream() {
        return StreamCapture.current();
    }

    private static boolean isMusicApp(Context context) {
        return context.getPackageName().contains("music");
    }

    private static String userAgent(Context context) {
        String original = isMusicApp(context) ? "com.google.android.apps.youtube.music" : "com.google.android.youtube";
        String version = "";
        try {
            version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        return original + "/" + version + " (Linux; U; Android " + Build.VERSION.RELEASE + ") gzip";
    }

    private static Activity findActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int dialogTheme(Context context) {
        int night = context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return night == android.content.res.Configuration.UI_MODE_NIGHT_YES
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
    }

    private static View padded(Context activity, EditText input, String help) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, 0);
        TextView text = new TextView(activity);
        text.setText(help);
        layout.addView(text);
        layout.addView(input);
        return layout;
    }

    private static void toast(Context context, String message) {
        main.post(() -> Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_LONG).show());
    }

    static String t(String german, String english) {
        return Locale.getDefault().getLanguage().equals("de") ? german : english;
    }

    // endregion
}
