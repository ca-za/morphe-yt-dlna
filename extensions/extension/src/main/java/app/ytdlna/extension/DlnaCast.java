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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;
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

    /** The apps' cast button controllers and the name of their update method (see the patch). */
    private static final Set<Object> castButtonControllers =
            java.util.Collections.newSetFromMap(new WeakHashMap<>());
    private static volatile String castButtonUpdate;
    /** The app found Google Cast devices (its own answer, before DLNA renderers are added). */
    static volatile boolean googleCastAvailable;

    /** Controllers asked to update their buttons once, whatever they show now. */
    private static final Set<Object> refreshPending =
            java.util.Collections.newSetFromMap(new WeakHashMap<>());
    /** The controller whose update runs now (main thread). */
    private static Object updating;
    private static boolean lastAvailable;

    /** Injection point: start of the cast button controller's update. */
    public static void setCastButtonController(Object controller) {
        synchronized (castButtonControllers) {
            castButtonControllers.add(controller);
        }
        updating = controller;
        RendererScanner.init();
    }

    private static boolean dlnaAvailable() {
        return RendererScanner.hasDevices() || CastSession.active() != null;
    }

    /** Injection point: the name of the controller's update method. */
    /** Injection point: after every {@code new MediaSession(...)} of the app. */
    public static void onMediaSession(Object session) {
        LocalPlayer.onMediaSession(session);
    }

    public static void setCastButtonUpdate(String name) {
        castButtonUpdate = name;
    }

    /**
     * Injection point: the controller's "is a Google Cast device available" answer. The cast
     * buttons also show for DLNA renderers and while casting.
     */
    public static boolean isCastAvailable(boolean googleCast) {
        googleCastAvailable = googleCast;
        lastAvailable = googleCast || dlnaAvailable();
        return lastAvailable;
    }

    /**
     * Injection point: whether the controller shows its buttons now. Reported different once
     * after renderers came or went, so it updates its buttons and the layout around them.
     */
    public static boolean castButtonsShown(boolean shown) {
        synchronized (castButtonControllers) {
            if (updating != null && refreshPending.remove(updating)) return !lastAvailable;
        }
        return shown;
    }

    /**
     * Renderers appeared or are gone, or a cast started or ended: the app's controllers update
     * their buttons (and the layout around them) themselves. Main thread.
     */
    static void onRenderersChanged() {
        String name = castButtonUpdate;
        List<Object> controllers;
        synchronized (castButtonControllers) {
            controllers = new ArrayList<>(castButtonControllers);
            refreshPending.addAll(controllers);
        }
        if (name == null) return;
        for (Object controller : controllers) {
            try {
                java.lang.reflect.Method update = controller.getClass().getDeclaredMethod(name);
                update.setAccessible(true);
                update.invoke(controller);
            } catch (Exception e) {
                Log.e("Could not update the cast buttons", e);
            }
        }
    }

    /** Morphe setting: "Google Cast…" in the DLNA picker, leading to the app's own cast dialog. */
    private static final String PREF_GOOGLE_CAST_LINK = "ytdlna_google_cast_link";

    static boolean googleCastLinkEnabled(Context context) {
        try {
            return context.getSharedPreferences("morphe_prefs", Context.MODE_PRIVATE)
                    .getBoolean(PREF_GOOGLE_CAST_LINK, false);
        } catch (Exception e) {
            return false;
        }
    }

    private static final String PREF_DEBUG_LOG = "ytdlna_debug_log";

    /** Debug log switch: shows "Share debug log" in the picker and turns on extra checks. */
    static boolean debugLogEnabled(Context context) {
        try {
            return context.getSharedPreferences("morphe_prefs", Context.MODE_PRIVATE)
                    .getBoolean(PREF_DEBUG_LOG, false);
        } catch (Exception e) {
            return false;
        }
    }

    /** Shares the kept log lines as text, with a header about the app and the cast. */
    private static void shareDebugLog(Activity activity) {
        StringBuilder text = new StringBuilder("YT DLNA debug log\n");
        try {
            android.content.pm.PackageInfo info =
                    activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            text.append(activity.getPackageName()).append(' ').append(info.versionName).append('\n');
        } catch (Exception ignored) {
            text.append(activity.getPackageName()).append('\n');
        }
        text.append("Android ").append(Build.VERSION.RELEASE).append(", ").append(Build.MANUFACTURER)
                .append(' ').append(Build.MODEL).append('\n');
        CastSession session = CastSession.active();
        text.append("Cast: ").append(session == null ? "none" : session.device.toString()).append('\n');
        text.append(MorpheState.summary()).append('\n');
        text.append("SponsorBlock ").append(SponsorBlock.available() ? "in the app" : "not in the app")
                .append(", video ").append(SponsorBlock.videoId()).append('\n');
        for (SponsorBlock.Segment segment : SponsorBlock.segments()) text.append("  ").append(segment).append('\n');
        text.append('\n').append(Log.text());
        android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_SUBJECT, "YT DLNA debug log")
                .putExtra(android.content.Intent.EXTRA_TEXT, text.toString());
        try {
            activity.startActivity(android.content.Intent.createChooser(send, t("Debug-Log teilen", "Share debug log")));
        } catch (Exception e) {
            Log.e("Could not share the debug log", e);
            toast(activity, t("Teilen fehlgeschlagen", "Sharing failed"));
        }
    }

    /** The click on the app's cast button that the picker took over, passed on to the app. */
    private static void openGoogleCast(View castButton) {
        passThroughClick = true;
        try {
            castButton.performClick();
        } finally {
            passThroughClick = false;
        }
    }

    private static void openGoogleCastFromApp() {
        Activity activity = RendererScanner.currentActivity();
        View button = activity == null ? null : findCastButton(activity.getWindow().getDecorView());
        if (button == null) button = registeredCastButton();
        if (button != null) {
            openGoogleCast(button);
        } else if (activity != null) {
            toast(activity, t("Google Cast: im Player auf das Cast-Symbol tippen",
                    "Google Cast: tap the cast icon in the player"));
        }
    }

    /**
     * A cast button the app's controllers keep (they register every button, also one of a
     * player whose controls were not shown yet). Attached ones first.
     */
    private static View registeredCastButton() {
        List<Object> controllers;
        synchronized (castButtonControllers) {
            controllers = new ArrayList<>(castButtonControllers);
        }
        View detached = null;
        for (Object controller : controllers) {
            for (java.lang.reflect.Field field : controller.getClass().getDeclaredFields()) {
                if (!java.util.Collection.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(controller);
                    if (!(value instanceof java.util.Collection)) continue;
                    for (Object item : new ArrayList<>((java.util.Collection<?>) value)) {
                        if (!(item instanceof View)) continue;
                        if (((View) item).isAttachedToWindow()) return (View) item;
                        if (detached == null) detached = (View) item;
                    }
                } catch (Exception ignored) {
                    // Not readable: the next field.
                }
            }
        }
        return detached;
    }

    /** A cast button of the app (MediaRouteButton or YouTube's newer entry point button). */
    private static View findCastButton(View view) {
        for (Class<?> c = view.getClass(); c != null && c != View.class; c = c.getSuperclass()) {
            String name = c.getName();
            if (name.endsWith("MediaRouteButton") || name.endsWith("MdxEntryPointButton")) {
                // Its dialog needs a window.
                return view.isAttachedToWindow() ? view : null;
            }
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findCastButton(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Renderers the user added by address. */
    static Set<String> savedDevices(Context context) {
        return new HashSet<>(prefs(context).getStringSet(PREF_MANUAL_DEVICES, new HashSet<>()));
    }

    /**
     * Shows the device picker.
     *
     * @param castButton The original cast button, or null if not opened from it.
     * @param onDone     Called once the picker and any follow-up dialog are closed. May be null.
     */
    static void showPicker(Activity activity, View castButton, StreamInfo current, Runnable onDone) {
        boolean[] followUp = {false};
        List<DlnaDevice> devices = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean[] searching = {true};
        DevicePicker[] picker = new DevicePicker[1];
        picker[0] = new DevicePicker(activity, t("Gerät auswählen", "Select a device"), () -> {
            if (!followUp[0] && onDone != null) onDone.run();
        });

        // Closes the picker, then runs the chosen action.
        java.util.function.Function<Runnable, Runnable> choose = action -> () -> {
            picker[0].dismiss();
            action.run();
        };
        // Renderers added by address: which ones answered, and the key each one is listed under.
        Set<String> manual = savedDevices(activity);
        Set<String> unreachable = new HashSet<>();
        java.util.Map<String, String> manualByKey = new java.util.HashMap<>();
        // Keys found by a search, so they stay listed when their saved address is removed.
        Set<String> discovered = new HashSet<>();
        Runnable[] rebuildRef = new Runnable[1];
        java.util.function.Consumer<String> remove = location -> {
            String name = location;
            for (DlnaDevice device : devices) {
                if (location.equals(manualLocation(device, manual, manualByKey))) name = device.friendlyName;
            }
            new AlertDialog.Builder(activity, dialogTheme(activity))
                    .setTitle(t("Gerät entfernen?", "Remove device?"))
                    .setMessage(t("„" + name + "“ wird aus den manuell hinzugefügten Geräten entfernt.\n\n",
                            "\"" + name + "\" is removed from the devices added by address.\n\n") + location)
                    .setPositiveButton(t("Entfernen", "Remove"), (d, w) -> {
                        forgetDevice(activity, location);
                        manual.remove(location);
                        unreachable.remove(location);
                        for (java.util.Iterator<DlnaDevice> it = devices.iterator(); it.hasNext(); ) {
                            DlnaDevice device = it.next();
                            String key = deviceKey(device);
                            if (!location.equals(device.location) && !location.equals(manualByKey.get(key))) continue;
                            manualByKey.remove(key);
                            if (!discovered.contains(key)) {
                                it.remove();
                                seen.remove(key);
                            }
                        }
                        rebuildRef[0].run();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        };
        Runnable rebuild = () -> {
            List<DevicePicker.Row> rows = new ArrayList<>();
            CastSession session = CastSession.active();
            for (DlnaDevice device : devices) {
                // Sonos: one row per group, for the room leading it; hidden speakers left out.
                String name = device.friendlyName;
                Runnable rooms = null;
                Sonos.Group group = Sonos.is(device) ? Sonos.groupOf(device) : null;
                // A renderer's room group (it streams to these Sonos): reached through the
                // renderer, a direct cast would take the rooms from it.
                if (group != null && RendererZones.ownsGroup(group.coordinator)) continue;
                if (RendererZones.has(device)) {
                    name = RendererZones.label(device);
                    rooms = () -> RendererZones.show(activity, device, rebuildRef[0]);
                }
                if (group != null) {
                    String uuid = Sonos.uuid(device);
                    Sonos.Zone zone = Sonos.zone(Sonos.lastKnown(device), uuid);
                    if (zone != null && zone.invisible) continue;
                    boolean leads = group.coordinator.equals(uuid);
                    if (!leads && listed(devices, group.coordinator)) continue;
                    if (leads) name = group.label();
                    // Rooms are changed for the group's leading room.
                    DlnaDevice lead = leads ? device : null;
                    if (lead != null) rooms = () -> SonosRooms.show(activity, lead, rebuildRef[0]);
                }
                boolean connected = session != null && session.device.location.equals(device.location);
                String location = manualLocation(device, manual, manualByKey);
                String subtitle = connected ? t("Verbunden", "Connected")
                        : "DLNA · " + (device.model().isEmpty() ? "" : device.model() + " · ")
                        + (location != null ? t("manuell · ", "manual · ") : "") + device.host();
                rows.add(new DevicePicker.Row(DevicePicker.Icon.TV, name, subtitle,
                        choose.apply(() -> cast(activity, device, current)),
                        location == null ? null : () -> remove.accept(location), rooms));
            }
            for (String location : unreachable) {
                rows.add(new DevicePicker.Row(DevicePicker.Icon.TV, hostOf(location),
                        t("Manuell · nicht erreichbar", "Manual · unreachable"), null,
                        () -> remove.accept(location)));
            }
            if (!searching[0] && devices.isEmpty()) {
                rows.add(new DevicePicker.Row(DevicePicker.Icon.NONE,
                        t("Keine DLNA-Geräte gefunden", "No DLNA devices found"), null, null));
            }
            if (session != null) {
                rows.add(new DevicePicker.Row(DevicePicker.Icon.STOP,
                        t("Wiedergabe stoppen auf ", "Stop playback on ") + session.device.friendlyName, null,
                        choose.apply(() -> stop(activity))));
            }
            if (googleCastLinkEnabled(activity)) {
                rows.add(new DevicePicker.Row(DevicePicker.Icon.CAST, t("Google Cast…", "Google Cast…"), null,
                        choose.apply(() -> {
                            if (castButton != null) {
                                openGoogleCast(castButton);
                            } else {
                                // Opened from the share menu: once it is closed, the app's own
                                // cast button opens its Google Cast dialog.
                                main.postDelayed(DlnaCast::openGoogleCastFromApp, 400);
                            }
                        })));
            }
            rows.add(new DevicePicker.Row(DevicePicker.Icon.ADD,
                    t("Gerät per Adresse hinzufügen", "Add a device by address"), null, () -> {
                        followUp[0] = true;
                        picker[0].dismiss();
                        showAddDevice(activity, onDone);
                    }));
            rows.add(new DevicePicker.Row(DevicePicker.Icon.LINK,
                    t("Stream-Adresse dieses Geräts", "This device's stream address"), null, () -> {
                        followUp[0] = true;
                        picker[0].dismiss();
                        showStreamHost(activity, onDone);
                    }));
            if (debugLogEnabled(activity)) {
                rows.add(new DevicePicker.Row(DevicePicker.Icon.SHARE,
                        t("Debug-Log teilen", "Share debug log"), null,
                        choose.apply(() -> shareDebugLog(activity))));
            }
            picker[0].setSearching(searching[0]);
            picker[0].setRows(rows);
        };
        rebuildRef[0] = rebuild;
        rebuild.run();
        picker[0].show();

        DlnaDiscovery.Listener onDevice = device -> main.post(() -> {
            if (!seen.add(deviceKey(device))) return;
            devices.add(device);
            rebuild.run();
            if (RendererZones.has(device) && RendererZones.cached(device) == null) {
                background.execute(() -> {
                    try {
                        RendererZones.refresh(device);
                        main.post(rebuild);
                    } catch (Exception e) {
                        Log.d("Renderer rooms not read: " + e);
                    }
                });
            }
            if (Sonos.is(device)) {
                SonosRooms.known(device);
                // Groups: read once per household (the answer covers all its players).
                if (Sonos.cached(device) == null) {
                    background.execute(() -> {
                        try {
                            Sonos.refresh(device);
                            main.post(rebuild);
                        } catch (Exception e) {
                            Log.d("Sonos groups not read: " + e);
                        }
                    });
                }
            }
        });
        DlnaDiscovery.Listener onSearched = device -> {
            main.post(() -> discovered.add(deviceKey(device)));
            onDevice.onDevice(device);
        };
        // Renderers found in the background are listed right away; the search below adds new ones.
        for (DlnaDevice device : RendererScanner.devices()) {
            if (RendererScanner.wasSearched(device)) discovered.add(deviceKey(device));
            onDevice.onDevice(device);
        }
        background.execute(() -> {
            for (String location : new ArrayList<>(manual)) {
                background.execute(() -> {
                    try {
                        DlnaDevice device = DlnaDevice.load(location);
                        main.post(() -> {
                            if (!manual.contains(location)) return;
                            manualByKey.put(deviceKey(device), location);
                            rebuild.run();
                        });
                        onDevice.onDevice(device);
                    } catch (Exception e) {
                        Log.d("Saved device unreachable: " + location + " " + e.getMessage());
                        main.post(() -> {
                            if (!manual.contains(location)) return;
                            unreachable.add(location);
                            rebuild.run();
                        });
                    }
                });
            }
            DlnaDiscovery.search(activity, SEARCH_MS, onSearched);
            main.post(() -> {
                searching[0] = false;
                rebuild.run();
            });
        });
    }

    /** Whether a Sonos player with this UUID is in the list. */
    private static boolean listed(List<DlnaDevice> devices, String uuid) {
        for (DlnaDevice d : devices) if (Sonos.is(d) && Sonos.uuid(d).equals(uuid)) return true;
        return false;
    }

    private static String deviceKey(DlnaDevice device) {
        return device.udn.isEmpty() ? device.location : device.udn;
    }

    /** The saved address a listed renderer was added by, null if it was only found by a search. */
    private static String manualLocation(DlnaDevice device, Set<String> manual, java.util.Map<String, String> byKey) {
        if (manual.contains(device.location)) return device.location;
        return byKey.get(deviceKey(device));
    }

    private static String hostOf(String location) {
        try {
            java.net.URL url = new java.net.URL(location);
            return url.getPort() < 0 ? url.getHost() : url.getHost() + ":" + url.getPort();
        } catch (Exception e) {
            return location;
        }
    }

    /** Removes a renderer added by address. */
    private static void forgetDevice(Context context, String location) {
        SharedPreferences prefs = prefs(context);
        Set<String> saved = new HashSet<>(prefs.getStringSet(PREF_MANUAL_DEVICES, new HashSet<>()));
        saved.remove(location);
        prefs.edit().putStringSet(PREF_MANUAL_DEVICES, saved).apply();
        Log.i("Removed saved device " + location);
        RendererScanner.forget(location);
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
                            RendererScanner.scanNow();
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

    static int dialogTheme(Context context) {
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

    /**
     * YouTube's player controls stay shown while casting: the hide timer is skipped. Called by
     * the patched app.
     */
    public static boolean keepPlayerControls() {
        return !Track.musicApp && CastSession.active() != null;
    }

        static String t(String german, String english) {
        return Locale.getDefault().getLanguage().equals("de") ? german : english;
    }

    // endregion
}
