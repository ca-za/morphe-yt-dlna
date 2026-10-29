package app.ytdlna.extension;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The rooms dialog, in the device list's look: which rooms play along, and each room's volume.
 * Changes apply right away. The rooms come from a {@link Source}: a Sonos group, or a renderer
 * that feeds Sonos rooms itself (see {@link RendererZones}).
 */
final class RoomsSheet {
    private static final Handler main = new Handler(Looper.getMainLooper());
    static final ExecutorService background = Executors.newCachedThreadPool();

    /** One room as the dialog shows it. */
    static final class Room {
        final String id;
        final String name;
        /** Plays along. */
        final boolean in;
        /** Can not be taken out or added (the leading room, a room busy elsewhere). */
        final boolean fixed;
        /** Below the name: "Leads the group", "Plays along", ... */
        final String state;
        /** 0-100, or -1 without a volume. */
        final int volume;

        Room(String id, String name, boolean in, boolean fixed, String state, int volume) {
            this.id = id;
            this.name = name;
            this.in = in;
            this.fixed = fixed;
            this.state = state;
            this.volume = volume;
        }
    }

    /** What the dialog shows and changes. Its methods run on a background thread. */
    interface Source {
        /** The rooms now (network); null if nothing changed since the last call. */
        List<Room> load() throws Exception;

        /** The title above the rooms ("Kitchen + 1"), after a load. */
        String title();

        /** A line above the rooms (sync state), or null. */
        String note();

        void setIn(Room room, boolean in) throws Exception;

        void setVolume(Room room, int volume) throws Exception;

        /** How often the dialog asks again while open; 0: only after a change. */
        long pollMs();

        /** The message for a failed change. */
        default String errorText(Exception e) {
            return DlnaCast.t("Raum konnte nicht geändert werden", "Could not change the room");
        }
    }

    private RoomsSheet() {
    }

    /**
     * @param onChanged Run on the main thread after each load (the device list updates its row).
     */
    static void show(Activity activity, String title, Source source, Runnable onChanged) {
        DevicePicker sheet = new DevicePicker(activity, title, () -> { });
        sheet.setSearching(true);
        sheet.show();
        // A slider being dragged is not rebuilt under the finger.
        boolean[] dragging = {false};
        // The rooms as last loaded: a tap acts on these, not on what a row showed when drawn.
        java.util.Map<String, Room> latest = new java.util.concurrent.ConcurrentHashMap<>();
        // One change at a time: further taps wait until the rooms are read again.
        boolean[] changing = {false};
        Runnable[] load = new Runnable[1];
        Runnable poll = () -> {
            if (sheet.isShowing()) load[0].run();
        };
        load[0] = () -> background.execute(() -> {
            try {
                List<Room> rooms = source.load();
                if (rooms != null) {
                    latest.clear();
                    for (Room r : rooms) latest.put(r.id, r);
                }
                main.post(() -> {
                    // A change counts as done once the rooms come back changed.
                    if (rooms != null) changing[0] = false;
                    if (onChanged != null && rooms != null) onChanged.run();
                    if (!sheet.isShowing()) return;
                    sheet.setSearching(false);
                    if (rooms != null && !dragging[0]) {
                        sheet.setTitle(source.title());
                        List<View> views = new ArrayList<>();
                        String note = source.note();
                        if (note != null) views.add(note(activity, sheet, note, 13));
                        for (Room room : rooms) {
                            views.add(roomView(activity, sheet, source, room, load[0], dragging, latest, changing));
                        }
                        if (rooms.isEmpty()) views.add(note(activity, sheet,
                                DlnaCast.t("Keine Räume gefunden", "No rooms found"), 16));
                        sheet.setViews(views);
                    }
                    if (source.pollMs() > 0) {
                        main.removeCallbacks(poll);
                        main.postDelayed(poll, source.pollMs());
                    }
                });
            } catch (Exception e) {
                Log.d("Rooms: " + e);
                main.post(() -> {
                    changing[0] = false;
                    if (!sheet.isShowing()) return;
                    sheet.setSearching(false);
                    List<View> views = new ArrayList<>();
                    views.add(note(activity, sheet, DlnaCast.t("Das Gerät antwortet nicht",
                            "The device does not answer"), 16));
                    sheet.setViews(views);
                });
            }
        });
        load[0].run();
    }

    /** One room: a check (plays along), its name and state, and its volume below. */
    private static View roomView(Activity activity, DevicePicker sheet, Source source, Room r,
                                 Runnable reload, boolean[] dragging, java.util.Map<String, Room> latest,
                                 boolean[] changing) {
        LinearLayout room = new LinearLayout(activity);
        room.setOrientation(LinearLayout.VERTICAL);
        room.setPadding(sheet.dp(24), sheet.dp(8), sheet.dp(24), sheet.dp(4));

        LinearLayout top = new LinearLayout(activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setMinimumHeight(sheet.dp(48));
        ImageView check = new ImageView(activity);
        check.setImageDrawable(checkDrawable(sheet, r.in, r.fixed));
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(sheet.dp(24), sheet.dp(24));
        checkParams.rightMargin = sheet.dp(32);
        top.addView(check, checkParams);

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(activity);
        name.setText(r.name);
        name.setTextColor(sheet.textColor);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        texts.addView(name);
        TextView state = new TextView(activity);
        state.setText(r.state);
        state.setTextColor(sheet.secondaryColor);
        state.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        texts.addView(state);
        top.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        room.addView(top);

        if (!r.fixed) {
            top.setBackground(new RippleDrawable(ColorStateList.valueOf(sheet.dark ? 0x33FFFFFF : 0x1F000000),
                    null, new ColorDrawable(Color.WHITE)));
            top.setOnClickListener(v -> {
                if (changing[0]) return;
                Room now = latest.get(r.id);
                if (now == null || now.fixed) return;
                changing[0] = true;
                // At the latest after a few seconds (the device may not report a change).
                main.postDelayed(() -> changing[0] = false, 5000);
                v.setEnabled(false);
                v.setAlpha(0.5f);
                boolean join = !now.in;
                background.execute(() -> {
                    try {
                        source.setIn(r, join);
                        Log.i("Rooms: " + r.name + (join ? " added" : " removed"));
                    } catch (Exception e) {
                        Log.d("Room change failed: " + e);
                        String text = source.errorText(e);
                        main.post(() -> Toast.makeText(activity.getApplicationContext(), text,
                                Toast.LENGTH_SHORT).show());
                    }
                    main.post(reload);
                });
            });
        }

        if (r.volume >= 0) {
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
            slider.setProgress(r.volume);
            slider.setContentDescription(DlnaCast.t("Lautstärke ", "Volume ") + r.name);
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
                    dragging[0] = true;
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                    dragging[0] = false;
                    int value = bar.getProgress();
                    background.execute(() -> {
                        try {
                            source.setVolume(r, value);
                        } catch (Exception e) {
                            Log.d("Room volume failed: " + e);
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

    private static View note(Activity activity, DevicePicker sheet, String text, int sp) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(sheet.secondaryColor);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setPadding(sheet.dp(24), sheet.dp(4), sheet.dp(24), sheet.dp(8));
        return view;
    }
}
