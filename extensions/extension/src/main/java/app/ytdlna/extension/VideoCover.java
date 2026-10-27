package app.ytdlna.extension;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Covers the app's video while casting, like YouTube does: the video area shows the cover and
 * "Playing on ..." instead of the (muted) local video. The cover is placed right above the video
 * surface inside its nearest plain FrameLayout, so the player controls stay on top and usable.
 */
final class VideoCover {
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** Video surface -> its cover. */
    private static final Map<View, FrameLayout> covers = new WeakHashMap<>();
    private static WeakReference<Activity> activityRef = new WeakReference<>(null);
    private static ViewTreeObserver.OnGlobalLayoutListener layoutListener;

    private static String artUrl = "";
    private static Bitmap art;
    private static String title = "";

    private static final Runnable scan = VideoCover::scan;

    private VideoCover() {
    }

    /** Follows layout changes of {@code activity} while casting. */
    static void attach(Activity activity) {
        Activity previous = activityRef.get();
        if (previous == activity) {
            scheduleScan();
            return;
        }
        if (previous != null && layoutListener != null) {
            previous.getWindow().getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
        }
        activityRef = new WeakReference<>(activity);
        layoutListener = VideoCover::scheduleScan;
        activity.getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        scheduleScan();
    }

    /** The cast started, stopped or plays another title. */
    static void update(String newTitle, String newArtUrl) {
        if (newTitle != null) title = newTitle;
        if (newArtUrl != null && !newArtUrl.equals(artUrl)) {
            artUrl = newArtUrl;
            art = null;
            loadArt(newArtUrl);
        }
        scheduleScan();
    }

    private static void scheduleScan() {
        main.removeCallbacks(scan);
        main.postDelayed(scan, 100);
    }

    private static void scan() {
        Activity activity = activityRef.get();
        CastSession session = CastSession.active();
        if (activity == null || session == null) {
            removeAll();
            return;
        }
        List<View> surfaces = new ArrayList<>();
        findSurfaces(activity.getWindow().getDecorView(), surfaces);
        // Drop covers of surfaces that are gone.
        for (View surface : new ArrayList<>(covers.keySet())) {
            if (!surfaces.contains(surface)) remove(surface);
        }
        for (View surface : surfaces) place(surface, session);
    }

    private static void findSurfaces(View view, List<View> out) {
        if ((view instanceof SurfaceView || view instanceof TextureView)
                && view.isShown() && view.getWidth() > 0 && view.getHeight() > 0) {
            out.add(view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) findSurfaces(group.getChildAt(i), out);
        }
    }

    private static void place(View surface, CastSession session) {
        FrameLayout cover = covers.get(surface);
        // The nearest ancestor that lays out extra children like a FrameLayout does.
        View child = surface;
        ViewParent parent = surface.getParent();
        while (parent instanceof ViewGroup && !isPlainFrameLayout((ViewGroup) parent)) {
            child = (View) parent;
            parent = parent.getParent();
        }
        if (!(parent instanceof FrameLayout)) return;
        FrameLayout container = (FrameLayout) parent;

        if (cover != null && cover.getParent() != container) {
            remove(surface);
            cover = null;
        }
        if (cover == null) {
            cover = createCover(container);
            covers.put(surface, cover);
            container.addView(cover, container.indexOfChild(child) + 1,
                    new FrameLayout.LayoutParams(surface.getWidth(), surface.getHeight(), Gravity.TOP | Gravity.START));
        }

        // Exactly over the video.
        int[] surfaceLocation = new int[2];
        int[] containerLocation = new int[2];
        surface.getLocationInWindow(surfaceLocation);
        container.getLocationInWindow(containerLocation);
        int left = surfaceLocation[0] - containerLocation[0] - container.getPaddingLeft()
                - Math.round(cover.getTranslationX());
        int top = surfaceLocation[1] - containerLocation[1] - container.getPaddingTop()
                - Math.round(cover.getTranslationY());
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) cover.getLayoutParams();
        if (params.width != surface.getWidth() || params.height != surface.getHeight()
                || params.leftMargin != left || params.topMargin != top) {
            params.width = surface.getWidth();
            params.height = surface.getHeight();
            params.leftMargin = left;
            params.topMargin = top;
            cover.setLayoutParams(params);
        }
        bind(cover, session, surface.getHeight());
    }

    /** FrameLayout or a subclass that keeps its child layout. */
    private static boolean isPlainFrameLayout(ViewGroup group) {
        if (!(group instanceof FrameLayout)) return false;
        for (Class<?> c = group.getClass(); c != FrameLayout.class && c != null; c = c.getSuperclass()) {
            try {
                c.getDeclaredMethod("onLayout", boolean.class, int.class, int.class, int.class, int.class);
                return false;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return true;
    }

    private static FrameLayout createCover(ViewGroup container) {
        FrameLayout cover = new FrameLayout(container.getContext());
        cover.setBackgroundColor(0xFF000000);
        cover.setClickable(false);
        cover.setFocusable(false);
        cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        LinearLayout column = new LinearLayout(container.getContext());
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        cover.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView image = new ImageView(container.getContext());
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        column.addView(image, new LinearLayout.LayoutParams(0, 0));

        TextView text = new TextView(container.getContext());
        text.setTextColor(0xFFFFFFFF);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        text.setGravity(Gravity.CENTER);
        text.setMaxLines(2);
        int pad = Math.round(8 * container.getResources().getDisplayMetrics().density);
        text.setPadding(pad, pad, pad, 0);
        column.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return cover;
    }

    private static void bind(FrameLayout cover, CastSession session, int height) {
        LinearLayout column = (LinearLayout) cover.getChildAt(0);
        ImageView image = (ImageView) column.getChildAt(0);
        TextView text = (TextView) column.getChildAt(1);
        float density = cover.getResources().getDisplayMetrics().density;
        boolean small = height < 120 * density;

        int size = Math.round(height * (small ? 0.9f : 0.55f));
        LinearLayout.LayoutParams imageParams = (LinearLayout.LayoutParams) image.getLayoutParams();
        if (imageParams.width != size) {
            imageParams.width = size;
            imageParams.height = size;
            image.setLayoutParams(imageParams);
        }
        if (image.getTag() != art) {
            image.setImageBitmap(art);
            image.setTag(art);
        }
        image.setVisibility(art == null ? View.GONE : View.VISIBLE);

        String label = DlnaCast.t("Wird auf ", "Playing on ") + session.device.friendlyName
                + DlnaCast.t(" abgespielt", "");
        if (!label.contentEquals(text.getText())) text.setText(label);
        text.setVisibility(small ? View.GONE : View.VISIBLE);
    }

    private static void remove(View surface) {
        FrameLayout cover = covers.remove(surface);
        if (cover != null && cover.getParent() instanceof ViewGroup) {
            ((ViewGroup) cover.getParent()).removeView(cover);
        }
    }

    private static void removeAll() {
        for (View surface : new ArrayList<>(covers.keySet())) remove(surface);
    }

    private static void loadArt(String url) {
        new Thread(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                Bitmap bitmap;
                try (InputStream in = connection.getInputStream()) {
                    bitmap = BitmapFactory.decodeStream(in);
                } finally {
                    connection.disconnect();
                }
                main.post(() -> {
                    if (url.equals(artUrl)) {
                        art = bitmap;
                        scheduleScan();
                    }
                });
            } catch (Exception e) {
                Log.d("Cover image not loaded: " + e.getMessage());
            }
        }, "YtDlna-cover").start();
    }
}
