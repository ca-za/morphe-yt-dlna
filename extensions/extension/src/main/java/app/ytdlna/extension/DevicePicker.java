package app.ytdlna.extension;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The DLNA device picker, laid out like the apps' own cast dialog: a bottom sheet with a handle,
 * the title with a spinner while searching, the devices and then the actions, each with an icon.
 */
final class DevicePicker {
    enum Icon {TV, CAST, ADD, STOP, LINK, DELETE, SHARE, ROOMS, CHECK, VOLUME, NONE}

    static final class Row {
        final Icon icon;
        final String text;
        final String subtitle;
        /** Null: shown as a note, not clickable. */
        final Runnable onClick;
        /** Non-null: a delete button at the end of the row. */
        final Runnable onDelete;
        /** Non-null: a rooms button at the end of the row (Sonos). */
        final Runnable onRooms;
        /** Non-null: the row is a volume slider instead (value 0-100, set on release). */
        final java.util.function.IntConsumer onVolume;
        final int volume;

        Row(Icon icon, String text, String subtitle, Runnable onClick) {
            this(icon, text, subtitle, onClick, null);
        }

        Row(Icon icon, String text, String subtitle, Runnable onClick, Runnable onDelete) {
            this(icon, text, subtitle, onClick, onDelete, null);
        }

        Row(Icon icon, String text, String subtitle, Runnable onClick, Runnable onDelete, Runnable onRooms) {
            this.onRooms = onRooms;
            this.onVolume = null;
            this.volume = -1;
            this.icon = icon;
            this.text = text;
            this.subtitle = subtitle;
            this.onClick = onClick;
            this.onDelete = onDelete;
        }

        private Row(String description, int volume, java.util.function.IntConsumer onVolume) {
            this.icon = Icon.VOLUME;
            this.text = description;
            this.subtitle = null;
            this.onClick = null;
            this.onDelete = null;
            this.onRooms = null;
            this.volume = volume;
            this.onVolume = onVolume;
        }

        /** A volume slider; {@code description} is read by screen readers. */
        static Row volume(String description, int volume, java.util.function.IntConsumer onVolume) {
            return new Row(description, volume, onVolume);
        }
    }

    /** Whether a slider is being dragged: the rows aren't rebuilt meanwhile. */
    boolean dragging;

    /**
     * A volume slider in the sheet's colors (the app's theme may give old Holo sliders),
     * {@code onSet} called when it's released.
     */
    android.widget.SeekBar slider(int value, String description, java.util.function.IntConsumer onSet) {
        android.widget.SeekBar slider = new android.widget.SeekBar(activity);
        slider.setMax(100);
        slider.setProgress(value);
        slider.setContentDescription(description);
        slider.setPadding(dp(8), dp(8), dp(8), dp(8));
        float radius = dp(2);
        android.graphics.drawable.GradientDrawable rest = new android.graphics.drawable.GradientDrawable();
        rest.setCornerRadius(radius);
        rest.setColor(dark ? 0x40FFFFFF : 0x26000000);
        android.graphics.drawable.GradientDrawable done = new android.graphics.drawable.GradientDrawable();
        done.setCornerRadius(radius);
        done.setColor(textColor);
        android.graphics.drawable.LayerDrawable track = new android.graphics.drawable.LayerDrawable(
                new Drawable[]{rest, new android.graphics.drawable.ClipDrawable(done, Gravity.START,
                        android.graphics.drawable.ClipDrawable.HORIZONTAL)});
        track.setId(0, android.R.id.background);
        track.setId(1, android.R.id.progress);
        for (int i = 0; i < 2; i++) {
            track.setLayerHeight(i, dp(4));
            track.setLayerGravity(i, Gravity.CENTER_VERTICAL);
        }
        slider.setProgressDrawable(track);
        android.graphics.drawable.GradientDrawable thumb = new android.graphics.drawable.GradientDrawable();
        thumb.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        thumb.setColor(textColor);
        thumb.setSize(dp(16), dp(16));
        slider.setThumb(thumb);
        slider.setThumbOffset(dp(8));
        slider.setSplitTrack(false);
        slider.setMinimumHeight(dp(32));
        slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar bar) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar bar) {
                dragging = false;
                onSet.accept(bar.getProgress());
            }
        });
        return slider;
    }

    private final Activity activity;
    private final Dialog dialog;
    private final LinearLayout rows;
    private final ProgressBar spinner;
    private final TextView titleView;
    final boolean dark;
    final int textColor;
    final int secondaryColor;
    /** The sheet's background. */
    final int surfaceColor;

    DevicePicker(Activity activity, String title, Runnable onDismiss) {
        this.activity = activity;
        dark = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        textColor = dark ? Color.WHITE : 0xFF0F0F0F;
        secondaryColor = dark ? 0xFFAAAAAA : 0xFF606060;
        surfaceColor = dark ? 0xFF282828 : Color.WHITE;

        LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(surfaceColor);
        background.setCornerRadius(dp(28));
        sheet.setBackground(background);
        sheet.setPadding(0, 0, 0, dp(12));

        View handle = new View(activity);
        GradientDrawable handleShape = new GradientDrawable();
        handleShape.setColor(dark ? 0xFF5F5F5F : 0xFFCCCCCC);
        handleShape.setCornerRadius(dp(2));
        handle.setBackground(handleShape);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(32), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.topMargin = dp(16);
        sheet.addView(handle, handleParams);

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(24), dp(24), dp(24), dp(12));
        titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(textColor);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        titleView.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        header.addView(titleView);
        spinner = new ProgressBar(activity);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(secondaryColor));
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(dp(20), dp(20));
        spinnerParams.leftMargin = dp(16);
        header.addView(spinner, spinnerParams);
        sheet.addView(header);

        rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(rows);
        sheet.addView(scroll);

        // Floating a little above the bottom edge, like the apps' sheet.
        FrameLayout root = new FrameLayout(activity);
        root.setPadding(dp(8), 0, dp(8), dp(8));
        root.addView(sheet);

        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(d -> onDismiss.run());
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setWindowAnimations(android.R.style.Animation_InputMethod);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.5f);
        }
    }

    void setSearching(boolean searching) {
        spinner.setVisibility(searching ? View.VISIBLE : View.GONE);
    }

    void setTitle(String title) {
        titleView.setText(title);
    }

    /** Own content instead of rows (for dialogs in the same look). */
    void setViews(List<View> list) {
        rows.removeAllViews();
        for (View view : list) rows.addView(view);
    }

    boolean isShowing() {
        return dialog.isShowing();
    }

    static Drawable icon(Icon icon, int color) {
        return new IconDrawable(icon, color);
    }

    void setRows(List<Row> list) {
        rows.removeAllViews();
        for (Row row : list) rows.addView(rowView(row));
    }

    void show() {
        dialog.show();
    }

    void dismiss() {
        dialog.dismiss();
    }

    private View rowView(Row row) {
        if (row.onVolume != null) {
            // Under the connected device: speaker icon and slider, indented like a row's text.
            LinearLayout view = new LinearLayout(activity);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setMinimumHeight(dp(48));
            view.setPadding(dp(24), 0, dp(24), dp(8));
            ImageView icon = new ImageView(activity);
            icon.setImageDrawable(new IconDrawable(Icon.VOLUME, secondaryColor));
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(24), dp(24));
            iconParams.rightMargin = dp(24);
            view.addView(icon, iconParams);
            view.addView(slider(row.volume, row.text, row.onVolume),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            return view;
        }
        LinearLayout view = new LinearLayout(activity);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setMinimumHeight(dp(56));
        view.setPadding(dp(24), dp(8), dp(24), dp(8));
        if (row.onClick != null) {
            view.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x33FFFFFF : 0x1F000000), null,
                    new ColorDrawable(Color.WHITE)));
            view.setOnClickListener(v -> row.onClick.run());
        }
        if (row.icon != Icon.NONE) {
            ImageView icon = new ImageView(activity);
            icon.setImageDrawable(new IconDrawable(row.icon, row.onClick == null ? secondaryColor : textColor));
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(24), dp(24));
            iconParams.rightMargin = dp(32);
            view.addView(icon, iconParams);
        }
        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView text = new TextView(activity);
        text.setText(row.text);
        text.setTextColor(row.onClick == null ? secondaryColor : textColor);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        texts.addView(text);
        if (row.subtitle != null && !row.subtitle.isEmpty()) {
            TextView subtitle = new TextView(activity);
            subtitle.setText(row.subtitle);
            subtitle.setTextColor(secondaryColor);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            texts.addView(subtitle);
        }
        view.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (row.onRooms != null) {
            ImageView rooms = new ImageView(activity);
            rooms.setImageDrawable(new IconDrawable(Icon.ROOMS, textColor));
            rooms.setContentDescription(DlnaCast.t("Räume", "Rooms"));
            rooms.setPadding(dp(12), dp(12), dp(12), dp(12));
            rooms.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x33FFFFFF : 0x1F000000), null, null));
            rooms.setOnClickListener(v -> row.onRooms.run());
            LinearLayout.LayoutParams roomsParams = new LinearLayout.LayoutParams(dp(48), dp(48));
            roomsParams.rightMargin = row.onDelete == null ? -dp(12) : 0;
            view.addView(rooms, roomsParams);
        }
        if (row.onDelete != null) {
            ImageView delete = new ImageView(activity);
            delete.setImageDrawable(new IconDrawable(Icon.DELETE, secondaryColor));
            delete.setContentDescription(DlnaCast.t("Entfernen", "Remove"));
            delete.setPadding(dp(12), dp(12), dp(12), dp(12));
            delete.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x33FFFFFF : 0x1F000000), null, null));
            delete.setOnClickListener(v -> row.onDelete.run());
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dp(48), dp(48));
            deleteParams.rightMargin = -dp(12);
            view.addView(delete, deleteParams);
        }
        return view;
    }

    int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    // region Icons

    /** Material icon outlines (24 x 24), drawn from their path data. */
    private static String pathData(Icon icon) {
        switch (icon) {
            case TV:
                return "M21 3H3c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h5v2h8v-2h5c1.1 0 1.99-.9 1.99-2L23 5c0-1.1-.9-2-2-2z"
                        + "m0 14H3V5h18v12z";
            case CAST:
                return "M21 3H3c-1.1 0-2 .9-2 2v3h2V5h18v14h-7v2h7c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2z"
                        + "M1 18v3h3c0-1.66-1.34-3-3-3zm0-4v2c2.76 0 5 2.24 5 5h2c0-3.87-3.13-7-7-7z"
                        + "m0-4v2c4.97 0 9 4.03 9 9h2c0-6.08-4.93-11-11-11z";
            case ADD:
                return "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z";
            case STOP:
                return "M6 6h12v12H6z";
            case DELETE:
                return "M16 9v10H8V9h8m-1.5-6h-5l-1 1H5v2h14V4h-3.5l-1-1z"
                        + "M18 7H6v12c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7z";
            case ROOMS:
                // Material "tune".
                return "M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7z"
                        + "m14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z";
            case CHECK:
                return "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z";
            case VOLUME:
                return "M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02z"
                        + "M14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77"
                        + "s-2.99-7.86-7-8.77z";
            case SHARE:
                return "M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7"
                        + "l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3"
                        + "c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3"
                        + "c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92"
                        + "s2.92-1.31 2.92-2.92-1.31-2.92-2.92-2.92z";
            case LINK:
                return "M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7"
                        + "c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1"
                        + "s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z";
            default:
                return "";
        }
    }

    private static final class IconDrawable extends Drawable {
        private final Path path;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path scaled = new Path();
        private final android.graphics.Matrix matrix = new android.graphics.Matrix();

        IconDrawable(Icon icon, int color) {
            path = parse(pathData(icon));
            path.setFillType(Path.FillType.EVEN_ODD);
            paint.setColor(color);
        }

        @Override
        public void draw(Canvas canvas) {
            android.graphics.Rect bounds = getBounds();
            matrix.setScale(bounds.width() / 24f, bounds.height() / 24f);
            matrix.postTranslate(bounds.left, bounds.top);
            path.transform(matrix, scaled);
            scaled.setFillType(Path.FillType.EVEN_ODD);
            canvas.drawPath(scaled, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private static final Pattern TOKEN = Pattern.compile("[MmLlHhVvCcSsZz]|-?(?:\\d+\\.?\\d*|\\.\\d+)");

    /** SVG path data with the commands Material icons use here (no arcs). */
    static Path parse(String data) {
        List<String> tokens = new ArrayList<>();
        Matcher m = TOKEN.matcher(data);
        while (m.find()) tokens.add(m.group());
        Path path = new Path();
        float x = 0, y = 0, startX = 0, startY = 0, lastControlX = 0, lastControlY = 0;
        char command = 'M';
        char previous = ' ';
        int i = 0;
        while (i < tokens.size()) {
            String token = tokens.get(i);
            if (Character.isLetter(token.charAt(0))) {
                command = token.charAt(0);
                i++;
                if (command == 'Z' || command == 'z') {
                    path.close();
                    x = startX;
                    y = startY;
                    previous = command;
                    continue;
                }
            }
            boolean relative = Character.isLowerCase(command);
            float dx = relative ? x : 0, dy = relative ? y : 0;
            switch (Character.toUpperCase(command)) {
                case 'M':
                    x = num(tokens, i++) + dx;
                    y = num(tokens, i++) + dy;
                    path.moveTo(x, y);
                    startX = x;
                    startY = y;
                    // Further pairs are lines.
                    command = relative ? 'l' : 'L';
                    break;
                case 'L':
                    x = num(tokens, i++) + dx;
                    y = num(tokens, i++) + dy;
                    path.lineTo(x, y);
                    break;
                case 'H':
                    x = num(tokens, i++) + dx;
                    path.lineTo(x, y);
                    break;
                case 'V':
                    y = num(tokens, i++) + dy;
                    path.lineTo(x, y);
                    break;
                case 'C': {
                    float x1 = num(tokens, i++) + dx, y1 = num(tokens, i++) + dy;
                    float x2 = num(tokens, i++) + dx, y2 = num(tokens, i++) + dy;
                    x = num(tokens, i++) + dx;
                    y = num(tokens, i++) + dy;
                    path.cubicTo(x1, y1, x2, y2, x, y);
                    lastControlX = x2;
                    lastControlY = y2;
                    break;
                }
                case 'S': {
                    // First control point: the last one mirrored, after another curve.
                    boolean afterCurve = "CcSs".indexOf(previous) >= 0;
                    float x1 = afterCurve ? 2 * x - lastControlX : x, y1 = afterCurve ? 2 * y - lastControlY : y;
                    float x2 = num(tokens, i++) + dx, y2 = num(tokens, i++) + dy;
                    x = num(tokens, i++) + dx;
                    y = num(tokens, i++) + dy;
                    path.cubicTo(x1, y1, x2, y2, x, y);
                    lastControlX = x2;
                    lastControlY = y2;
                    break;
                }
                default:
                    i++;
                    break;
            }
            previous = command;
        }
        return path;
    }

    private static float num(List<String> tokens, int i) {
        return i < tokens.size() ? Float.parseFloat(tokens.get(i)) : 0;
    }

    // endregion
}
