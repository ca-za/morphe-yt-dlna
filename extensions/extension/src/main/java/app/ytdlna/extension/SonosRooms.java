package app.ytdlna.extension;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The rooms dialog for a Sonos group, as in the Sonos app: which rooms play along, and each
 * room's volume. Changes apply right away; Sonos keeps the rooms in sync itself.
 */
final class SonosRooms {
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService background = Executors.newCachedThreadPool();
    /** Players by description URL, loaded once. */
    private static final Map<String, DlnaDevice> players = new ConcurrentHashMap<>();

    private SonosRooms() {
    }

    /** Remembers a player found by the search, so the dialog need not load it again. */
    static void known(DlnaDevice device) {
        if (Sonos.is(device)) players.put(device.location, device);
    }

    /**
     * @param coordinator The player leading the group (the one cast to).
     * @param onChanged   Run on the main thread when the rooms changed (to update the list).
     */
    static void show(Activity activity, DlnaDevice coordinator, Runnable onChanged) {
        Sonos.Group start = Sonos.groupOf(coordinator);
        DevicePicker sheet = new DevicePicker(activity,
                start == null ? coordinator.friendlyName : start.label(), () -> { });
        sheet.setSearching(true);
        sheet.show();

        Runnable[] load = new Runnable[1];
        load[0] = () -> background.execute(() -> {
            try {
                List<Sonos.Group> groups = Sonos.refresh(coordinator);
                String lead = Sonos.uuid(coordinator);
                Sonos.Group own = null;
                for (Sonos.Group g : groups) if (g.contains(lead)) own = g;
                List<Sonos.Zone> rooms = new ArrayList<>();
                List<DlnaDevice> devices = new ArrayList<>();
                List<Integer> volumes = new ArrayList<>();
                for (Sonos.Group g : groups) {
                    for (Sonos.Zone z : g.rooms) {
                        DlnaDevice device = player(z);
                        if (device == null) continue;
                        int volume;
                        try {
                            volume = DlnaController.volume(device);
                        } catch (Exception e) {
                            volume = -1;
                        }
                        // The leading room first.
                        int at = z.uuid.equals(lead) ? 0 : rooms.size();
                        rooms.add(at, z);
                        devices.add(at, device);
                        volumes.add(at, volume);
                    }
                }
                Sonos.Group group = own;
                main.post(() -> {
                    // The device list shows the new groups too.
                    if (onChanged != null) onChanged.run();
                    if (!sheet.isShowing()) return;
                    sheet.setSearching(false);
                    if (group != null) sheet.setTitle(group.label());
                    List<View> views = new ArrayList<>();
                    for (int i = 0; i < rooms.size(); i++) {
                        Sonos.Zone z = rooms.get(i);
                        boolean in = group != null && group.contains(z.uuid);
                        views.add(roomView(activity, sheet, z, devices.get(i), volumes.get(i), in,
                                z.uuid.equals(lead), lead, load[0]));
                    }
                    if (rooms.isEmpty()) views.add(note(activity, sheet,
                            DlnaCast.t("Keine Räume gefunden", "No rooms found")));
                    sheet.setViews(views);
                });
            } catch (Exception e) {
                Log.d("Sonos rooms: " + e);
                main.post(() -> {
                    if (!sheet.isShowing()) return;
                    sheet.setSearching(false);
                    List<View> views = new ArrayList<>();
                    views.add(note(activity, sheet, DlnaCast.t("Sonos antwortet nicht", "Sonos does not answer")));
                    sheet.setViews(views);
                });
            }
        });
        load[0].run();
    }

    /** One room: a check (plays along), its name and state, and its volume below. */
    private static View roomView(Activity activity, DevicePicker sheet, Sonos.Zone zone, DlnaDevice device,
                                 int volume, boolean inGroup, boolean leading, String lead, Runnable onChanged) {
        LinearLayout room = new LinearLayout(activity);
        room.setOrientation(LinearLayout.VERTICAL);
        room.setPadding(sheet.dp(24), sheet.dp(8), sheet.dp(24), sheet.dp(4));

        LinearLayout top = new LinearLayout(activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setMinimumHeight(sheet.dp(48));
        ImageView check = new ImageView(activity);
        check.setImageDrawable(checkDrawable(sheet, inGroup, leading));
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(sheet.dp(24), sheet.dp(24));
        checkParams.rightMargin = sheet.dp(32);
        top.addView(check, checkParams);

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(activity);
        name.setText(zone.name);
        name.setTextColor(sheet.textColor);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        texts.addView(name);
        TextView state = new TextView(activity);
        state.setText(leading ? DlnaCast.t("Führt die Gruppe", "Leads the group")
                : inGroup ? DlnaCast.t("Spielt mit", "Plays along")
                : DlnaCast.t("Tippen zum Hinzufügen", "Tap to add"));
        state.setTextColor(sheet.secondaryColor);
        state.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        texts.addView(state);
        top.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        room.addView(top);

        // The leading room stays in: its playback is the cast.
        if (!leading) {
            top.setBackground(new RippleDrawable(ColorStateList.valueOf(sheet.dark ? 0x33FFFFFF : 0x1F000000),
                    null, new ColorDrawable(Color.WHITE)));
            top.setOnClickListener(v -> {
                v.setEnabled(false);
                v.setAlpha(0.5f);
                boolean join = !inGroup;
                background.execute(() -> {
                    try {
                        if (join) {
                            Sonos.join(device, lead);
                        } else {
                            Sonos.leave(device);
                        }
                        Log.i("Sonos: " + zone.name + (join ? " joins " : " leaves ") + lead);
                    } catch (Exception e) {
                        Log.d("Sonos room change failed: " + e);
                        main.post(() -> Toast.makeText(activity.getApplicationContext(),
                                DlnaCast.t("Raum konnte nicht geändert werden", "Could not change the room"),
                                Toast.LENGTH_SHORT).show());
                    }
                    main.post(onChanged);
                });
            });
        }

        if (volume >= 0) {
            LinearLayout volumeRow = new LinearLayout(activity);
            volumeRow.setGravity(Gravity.CENTER_VERTICAL);
            ImageView speaker = new ImageView(activity);
            speaker.setImageDrawable(DevicePicker.icon(DevicePicker.Icon.VOLUME, sheet.secondaryColor));
            LinearLayout.LayoutParams speakerParams = new LinearLayout.LayoutParams(sheet.dp(20), sheet.dp(20));
            speakerParams.leftMargin = sheet.dp(2);
            speakerParams.rightMargin = sheet.dp(18);
            volumeRow.addView(speaker, speakerParams);
            SeekBar slider = new SeekBar(activity);
            slider.setMax(100);
            slider.setProgress(volume);
            slider.setContentDescription(DlnaCast.t("Lautstärke ", "Volume ") + zone.name);
            slider.setPadding(sheet.dp(8), sheet.dp(8), sheet.dp(8), sheet.dp(8));
            // Own track and thumb in the sheet's colors (Music's theme gives old Holo sliders).
            slider.setProgressDrawable(track(sheet));
            GradientDrawable thumb = new GradientDrawable();
            thumb.setShape(GradientDrawable.OVAL);
            thumb.setColor(sheet.textColor);
            thumb.setSize(sheet.dp(16), sheet.dp(16));
            slider.setThumb(thumb);
            slider.setThumbOffset(sheet.dp(8));
            slider.setSplitTrack(false);
            slider.setMinimumHeight(sheet.dp(32));
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                    int value = bar.getProgress();
                    background.execute(() -> {
                        try {
                            DlnaController.setVolume(device, value);
                        } catch (Exception e) {
                            Log.d("Sonos room volume failed: " + e);
                        }
                    });
                }
            });
            volumeRow.addView(slider, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            room.addView(volumeRow);
        }
        return room;
    }

    /** A thin rounded track: the volume part in the text color, the rest faint. */
    private static android.graphics.drawable.Drawable track(DevicePicker sheet) {
        float radius = sheet.dp(2);
        GradientDrawable rest = new GradientDrawable();
        rest.setCornerRadius(radius);
        rest.setColor(sheet.dark ? 0x40FFFFFF : 0x26000000);
        GradientDrawable done = new GradientDrawable();
        done.setCornerRadius(radius);
        done.setColor(sheet.textColor);
        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{rest,
                        new android.graphics.drawable.ClipDrawable(done, Gravity.START,
                                android.graphics.drawable.ClipDrawable.HORIZONTAL)});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        for (int i = 0; i < 2; i++) {
            layers.setLayerHeight(i, sheet.dp(4));
            layers.setLayerGravity(i, Gravity.CENTER_VERTICAL);
        }
        return layers;
    }

    /** A rounded box, filled with a check when the room plays along. */
    private static android.graphics.drawable.Drawable checkDrawable(DevicePicker sheet, boolean checked,
                                                                    boolean muted) {
        int color = muted ? sheet.secondaryColor : sheet.textColor;
        GradientDrawable box = new GradientDrawable();
        box.setCornerRadius(sheet.dp(6));
        if (!checked) {
            box.setStroke(sheet.dp(2), sheet.secondaryColor);
            return box;
        }
        box.setColor(color);
        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{box,
                        DevicePicker.icon(DevicePicker.Icon.CHECK, sheet.surfaceColor)});
        int inset = sheet.dp(3);
        layers.setLayerInset(1, inset, inset, inset, inset);
        return layers;
    }

    private static View note(Activity activity, DevicePicker sheet, String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(sheet.secondaryColor);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        view.setPadding(sheet.dp(24), sheet.dp(12), sheet.dp(24), sheet.dp(12));
        return view;
    }

    private static DlnaDevice player(Sonos.Zone zone) {
        if (zone.location.isEmpty()) return null;
        DlnaDevice device = players.get(zone.location);
        if (device != null) return device;
        try {
            device = DlnaDevice.load(zone.location);
            players.put(zone.location, device);
            return device;
        } catch (Exception e) {
            Log.d("Sonos player " + zone.name + " not reachable: " + e);
            return null;
        }
    }
}
