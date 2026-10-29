package app.ytdlna.extension;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
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
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 24);
        list.setPadding(pad, dp(activity, 8), pad, 0);
        ProgressBar spinner = new ProgressBar(activity);
        list.addView(spinner);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(list);

        Sonos.Group start = Sonos.groupOf(coordinator);
        AlertDialog dialog = new AlertDialog.Builder(activity, DlnaCast.dialogTheme(activity))
                .setTitle(start == null ? coordinator.friendlyName : start.label())
                .setView(scroll)
                .setPositiveButton(DlnaCast.t("Fertig", "Done"), null)
                .create();
        dialog.show();

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
                    if (!dialog.isShowing()) return;
                    if (group != null) dialog.setTitle(group.label());
                    list.removeAllViews();
                    for (int i = 0; i < rooms.size(); i++) {
                        Sonos.Zone z = rooms.get(i);
                        boolean in = group != null && group.contains(z.uuid);
                        list.addView(roomView(activity, z, devices.get(i), volumes.get(i), in,
                                z.uuid.equals(lead), lead, load[0]));
                    }
                    if (rooms.isEmpty()) {
                        TextView none = new TextView(activity);
                        none.setText(DlnaCast.t("Keine Räume gefunden", "No rooms found"));
                        list.addView(none);
                    }
                });
            } catch (Exception e) {
                Log.d("Sonos rooms: " + e);
                main.post(() -> {
                    if (!dialog.isShowing()) return;
                    list.removeAllViews();
                    TextView error = new TextView(activity);
                    error.setText(DlnaCast.t("Sonos antwortet nicht", "Sonos does not answer"));
                    list.addView(error);
                });
            }
        });
        load[0].run();
    }

    private static View roomView(Activity activity, Sonos.Zone zone, DlnaDevice device, int volume,
                                 boolean inGroup, boolean leading, String lead, Runnable onChanged) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(activity, 4), 0, dp(activity, 8));
        CheckBox check = new CheckBox(activity);
        check.setText(zone.name);
        check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        // The dialog's theme, not the app's (whose text may be white on the light dialog).
        boolean dark = (activity.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        check.setTextColor(leading ? (dark ? 0xFFAAAAAA : 0xFF606060) : (dark ? 0xFFFFFFFF : 0xDE000000));
        check.setChecked(inGroup);
        // The group is the leading room's: it stays in (its playback is the cast).
        check.setEnabled(!leading);
        check.setOnCheckedChangeListener((button, checked) -> {
            button.setEnabled(false);
            background.execute(() -> {
                try {
                    if (checked) {
                        Sonos.join(device, lead);
                    } else {
                        Sonos.leave(device);
                    }
                    Log.i("Sonos: " + zone.name + (checked ? " joins " : " leaves ") + lead);
                } catch (Exception e) {
                    Log.d("Sonos room change failed: " + e);
                    main.post(() -> Toast.makeText(activity.getApplicationContext(),
                            DlnaCast.t("Raum konnte nicht geändert werden", "Could not change the room"),
                            Toast.LENGTH_SHORT).show());
                }
                main.post(onChanged);
            });
        });
        row.addView(check);
        if (volume >= 0) {
            SeekBar slider = new SeekBar(activity);
            slider.setMax(100);
            slider.setProgress(volume);
            slider.setContentDescription(DlnaCast.t("Lautstärke ", "Volume ") + zone.name);
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
            row.addView(slider);
        }
        return row;
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

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
