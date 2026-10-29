package app.ytdlna.extension;

import android.app.Activity;
import android.content.res.ColorStateList;
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
    /** Dominant color of {@link #art} for the background (YouTube Music), 0 if none. */
    private static int artColor;
    /** Background of the cover without art, and of YouTube's. */
    private static final int BLACK = 0xFF000000;
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
            // The previous cover and color stay until the new cover is loaded: no black flash
            // between songs.
            artUrl = newArtUrl;
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
        lockPortrait(activity, session != null);
        // Shorts or a feed preview in front play on the phone: nothing to cover.
        if (activity == null || session == null || MorpheState.sidePlayer() != null) {
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
        updatePage(activity);
    }

    // region YouTube Music: the gradient behind the whole player page

    /**
     * The player page's background view while it shows our gradient (as its foreground: the app
     * animates its background as a plain color), and the foreground it had before.
     */
    private static WeakReference<View> pageRef = new WeakReference<>(null);
    private static android.graphics.drawable.Drawable pageOriginal;
    /** The page's gradient while applied; its colors follow {@link #shownColor}. */
    private static android.graphics.drawable.GradientDrawable pageGradient;
    /** The color on screen; moves smoothly to {@link #artColor} when the song changes. */
    private static int shownColor;
    private static android.animation.ValueAnimator colorAnimation;
    private static int fadeTarget;
    private static final long COLOR_FADE_MS = 700;

    /** Fades the page's color to {@code target}; at once if nothing is shown yet. */
    private static void fadeTo(int target) {
        if (target == 0 || target == fadeTarget) return;
        fadeTarget = target;
        if (colorAnimation != null) colorAnimation.cancel();
        if (shownColor == target) return;
        if (shownColor == 0) {
            show(target);
            return;
        }
        colorAnimation = android.animation.ValueAnimator.ofArgb(shownColor, target);
        colorAnimation.setDuration(COLOR_FADE_MS);
        colorAnimation.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        colorAnimation.addUpdateListener(a -> show((Integer) a.getAnimatedValue()));
        colorAnimation.start();
    }

    private static void show(int color) {
        shownColor = color;
        if (pageGradient != null) pageGradient.setColors(stops(color));
        for (FrameLayout cover : covers.values()) cover.invalidate();
    }

    /**
     * Like Spotify: the cover's gradient behind the whole player page (its backmost view, so the
     * controls stay on top), and our cover over the video turns transparent to match.
     */
    private static void updatePage(Activity activity) {
        // Also while the next song's cover loads (the previous color stays): no flash.
        // In song mode too (no video to cover): Music's black card around a 16:9 thumbnail.
        boolean want = Features.coverPage() && Track.musicApp && (artColor != 0 || shownColor != 0);
        View page = want ? findPage(activity) : null;
        View applied = pageRef.get();
        if (applied != null && applied != page) restorePage();
        if (page == null) return;
        fadeTo(artColor);
        if (pageRef.get() == null) {
            pageOriginal = page.getForeground();
            pageRef = new WeakReference<>(page);
            pageGradient = (android.graphics.drawable.GradientDrawable) pageBackground(shownColor);
            page.setForeground(pageGradient);
        }
        clearBlack(activity);
        // Song pages swiped in are drawn before the next scan: cleared right before each frame.
        if (preDrawTree == null) {
            preDrawTree = page.getViewTreeObserver();
            preDrawTree.addOnPreDrawListener(preDraw);
        }
        for (FrameLayout cover : covers.values()) {
            android.graphics.drawable.Drawable bg = cover.getBackground();
            if (!(bg instanceof PageSlice) || ((PageSlice) bg).page != page) {
                cover.setBackground(new PageSlice(page, cover));
            }
        }
    }

    /**
     * The part of the page's gradient behind a cover (the app's player area under it is black):
     * one gradient from the top of the page to the bottom, through the cover.
     */
    private static final class PageSlice extends android.graphics.drawable.Drawable {
        final View page;
        final View cover;
        private final android.graphics.drawable.GradientDrawable gradient;
        private int color;
        private final int[] pageAt = new int[2];
        private final int[] coverAt = new int[2];

        PageSlice(View page, View cover) {
            this.page = page;
            this.cover = cover;
            this.color = shownColor;
            this.gradient = (android.graphics.drawable.GradientDrawable) pageBackground(shownColor);
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            if (color != shownColor) {
                color = shownColor;
                gradient.setColors(stops(color));
            }
            page.getLocationInWindow(pageAt);
            cover.getLocationInWindow(coverAt);
            int dx = pageAt[0] - coverAt[0];
            int dy = pageAt[1] - coverAt[1];
            gradient.setBounds(dx, dy, dx + page.getWidth(), dy + page.getHeight());
            gradient.draw(canvas);
        }

        @Override
        public void setAlpha(int alpha) {
            gradient.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter filter) {
            gradient.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.OPAQUE;
        }
    }

    private static View findPage(Activity activity) {
        int id = activity.getResources().getIdentifier("player_page_background", "id", activity.getPackageName());
        View page = id == 0 ? null : activity.findViewById(id);
        return page != null && page.isShown() ? page : null;
    }

    /**
     * The app's black areas in the player (behind a 16:9 thumbnail in song mode, and the pages
     * of the next and previous songs while swiping): see-through over our gradient, as long as
     * it shows. Their own backgrounds come back with {@link #restorePage}.
     */
    private static final java.util.WeakHashMap<View, android.graphics.drawable.Drawable> clearedBlack =
            new java.util.WeakHashMap<>();

    private static ViewTreeObserver preDrawTree;
    private static final ViewTreeObserver.OnPreDrawListener preDraw = () -> {
        Activity activity = activityRef.get();
        if (activity != null && pageRef.get() != null) clearBlack(activity);
        return true;
    };

    /** Music's own thumbnail in song mode (over our cover), hidden while our cover shows. */
    private static final java.util.WeakHashMap<View, Float> hiddenThumbnails = new java.util.WeakHashMap<>();
    /** When a cover of ours was last there: between songs the video surface is gone briefly. */
    private static long lastCoverMs;

    private static boolean coverRecently() {
        return android.os.SystemClock.uptimeMillis() - lastCoverMs < 2000;
    }

    private static void clearBlack(Activity activity) {
        if (!covers.isEmpty()) lastCoverMs = android.os.SystemClock.uptimeMillis();
        if (!coverRecently() && !hiddenThumbnails.isEmpty()) {
            // No cover of ours: Music's thumbnail shows again.
            for (java.util.Map.Entry<View, Float> e : hiddenThumbnails.entrySet()) {
                if (e.getKey() != null) e.getKey().setAlpha(e.getValue());
            }
            hiddenThumbnails.clear();
        }
        // The player (song mode: its black panel and thumbnail) and the swiped song pages.
        for (String name : new String[]{"watch_fragment_container", "player_view_pager_container"}) {
            int id = activity.getResources().getIdentifier(name, "id", activity.getPackageName());
            View view = id == 0 ? null : activity.findViewById(id);
            if (view != null) clearBlack(view);
        }
    }

    private static void clearBlack(View view) {
        if (view instanceof SurfaceView || view instanceof TextureView || covers.containsValue(view)) return;
        android.graphics.drawable.Drawable bg = view.getBackground();
        // Music's thumbnail (16:9 on black for music videos) over our cover: ours shows the
        // album cover in both modes. Only while a cover of ours is there.
        if ("player_thumbnail".equals(safeName(view)) && coverRecently()) {
            if (!hiddenThumbnails.containsKey(view)) hiddenThumbnails.put(view, view.getAlpha());
            // Only on a change: setAlpha redraws, and this runs before every frame.
            if (view.getAlpha() != 0f) view.setAlpha(0f);
            return;
        }
        // The rounded card behind the song's thumbnail: black around a 16:9 one.
        if (bg != null && "card_view_container".equals(safeName(view))) {
            if (!clearedBlack.containsKey(view)) clearedBlack.put(view, bg);
            view.setBackground(null);
            return;
        }
        // Also again when the app sets it anew (recycled song pages).
        if (isBlack(bg)) {
            if (!clearedBlack.containsKey(view)) {
                clearedBlack.put(view, bg);
            }
            view.setBackground(null);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) clearBlack(group.getChildAt(i));
        }
    }

    /** A plain opaque black (or nearly) fill. */
    private static boolean isBlack(android.graphics.drawable.Drawable bg) {
        int color;
        if (bg instanceof android.graphics.drawable.ColorDrawable) {
            color = ((android.graphics.drawable.ColorDrawable) bg).getColor();
        } else if (bg instanceof android.graphics.drawable.GradientDrawable) {
            ColorStateList c = ((android.graphics.drawable.GradientDrawable) bg).getColor();
            if (c == null) return false;
            color = c.getDefaultColor();
        } else {
            return false;
        }
        return (color >>> 24) == 0xFF && ((color >> 16) & 0xFF) < 0x20 && ((color >> 8) & 0xFF) < 0x20
                && (color & 0xFF) < 0x20;
    }

    /** Gives the player page its own background back; covers get theirs again. */
    private static void restorePage() {
        for (java.util.Map.Entry<View, android.graphics.drawable.Drawable> e : clearedBlack.entrySet()) {
            if (e.getKey() != null) e.getKey().setBackground(e.getValue());
        }
        clearedBlack.clear();
        for (java.util.Map.Entry<View, Float> e : hiddenThumbnails.entrySet()) {
            if (e.getKey() != null) e.getKey().setAlpha(e.getValue());
        }
        hiddenThumbnails.clear();
        if (preDrawTree != null) {
            if (preDrawTree.isAlive()) preDrawTree.removeOnPreDrawListener(preDraw);
            preDrawTree = null;
        }
        View page = pageRef.get();
        if (page != null) page.setForeground(pageOriginal);
        pageRef = new WeakReference<>(null);
        pageOriginal = null;
        pageGradient = null;
        if (colorAnimation != null) colorAnimation.cancel();
        // A new page (or none): its first color is set at once.
        shownColor = 0;
        fadeTarget = 0;
        for (FrameLayout cover : covers.values()) {
            cover.setBackground(background(Track.musicApp && art != null ? artColor : 0));
        }
    }

    /** The cover's color over the page, darker toward the bottom (like Spotify's player). */
    private static android.graphics.drawable.Drawable pageBackground(int color) {
        return new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, stops(color));
    }

    /** Full color in the upper part, then darker: about two thirds black at the bottom. */
    private static int[] stops(int color) {
        return new int[]{color, color, blend(color, BLACK, 0.3f), blend(color, BLACK, 0.55f),
                blend(color, BLACK, 0.7f)};
    }

    // endregion

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

        // Exactly over the video; in YouTube Music over its whole player area (the video is a
        // 16:9 strip in it, the rest black).
        View area = playerArea(surface, container);
        int[] surfaceLocation = new int[2];
        int[] containerLocation = new int[2];
        area.getLocationInWindow(surfaceLocation);
        container.getLocationInWindow(containerLocation);
        int left = surfaceLocation[0] - containerLocation[0] - container.getPaddingLeft()
                - Math.round(cover.getTranslationX());
        int top = surfaceLocation[1] - containerLocation[1] - container.getPaddingTop()
                - Math.round(cover.getTranslationY());
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) cover.getLayoutParams();
        if (params.width != area.getWidth() || params.height != area.getHeight()
                || params.leftMargin != left || params.topMargin != top) {
            params.width = area.getWidth();
            params.height = area.getHeight();
            params.leftMargin = left;
            params.topMargin = top;
            cover.setLayoutParams(params);
        }
        bind(cover, session, area.getWidth(), area.getHeight());
    }

    // region Portrait while casting

    /** Activities whose orientation we locked, with the orientation they asked for before. */
    private static final Map<Activity, Integer> lockedFrom = new WeakHashMap<>();

    /**
     * Keeps the app in portrait while casting (the video plays on the TV; landscape only
     * reshuffles the controls), also when the app asks for landscape (YouTube's fullscreen). The
     * app's own orientation comes back with the end of the cast.
     */
    private static void lockPortrait(Activity activity, boolean casting) {
        int portrait = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        if (casting && activity != null && Features.portraitLock()) {
            if (!lockedFrom.containsKey(activity)) lockedFrom.put(activity, activity.getRequestedOrientation());
            if (activity.getRequestedOrientation() != portrait) activity.setRequestedOrientation(portrait);
            return;
        }
        if (casting) return;
        for (Map.Entry<Activity, Integer> e : new ArrayList<>(lockedFrom.entrySet())) {
            Activity locked = e.getKey();
            if (locked != null && locked.getRequestedOrientation() == portrait) {
                locked.setRequestedOrientation(e.getValue());
            }
        }
        lockedFrom.clear();
    }

    // endregion

    /** The app's player view ("watch_player") around the surface, up to the container. */
    private static View playerArea(View surface, FrameLayout container) {
        for (ViewParent v = surface.getParent(); v instanceof View && v != container; v = v.getParent()) {
            if ("watch_player".equals(safeName((View) v))) return (View) v;
        }
        return surface;
    }

    private static String safeName(View view) {
        try {
            return view.getResources().getResourceEntryName(view.getId());
        } catch (Exception e) {
            return "";
        }
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
        cover.setBackgroundColor(BLACK);
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
        if (Track.musicApp) {
            // Slightly rounded corners, like an album cover in Spotify.
            float radius = 8 * container.getResources().getDisplayMetrics().density;
            image.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            image.setClipToOutline(true);
        }
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

    private static void bind(FrameLayout cover, CastSession session, int width, int height) {
        LinearLayout column = (LinearLayout) cover.getChildAt(0);
        ImageView image = (ImageView) column.getChildAt(0);
        TextView text = (TextView) column.getChildAt(1);
        float density = cover.getResources().getDisplayMetrics().density;
        boolean small = height < 120 * density;

        String label = DlnaCast.t("Wird auf ", "Playing on ") + session.device.friendlyName
                + DlnaCast.t(" abgespielt", "");
        if (!label.contentEquals(text.getText())) text.setText(label);
        text.setVisibility(small ? View.GONE : View.VISIBLE);

        int imageWidth;
        int imageHeight;
        if (art != null) {
            // The cover (YouTube: the video's thumbnail) as large as it fits, whole (not cropped),
            // above the label.
            int pad = Math.round((small ? 4 : 16) * density);
            int textHeight = 0;
            if (!small) {
                text.measure(View.MeasureSpec.makeMeasureSpec(Math.max(0, width - 2 * pad), View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                textHeight = text.getMeasuredHeight();
            }
            float availableWidth = Math.max(1, width - 2 * pad);
            float availableHeight = Math.max(1, height - 2 * pad - textHeight);
            float scale = Math.min(availableWidth / art.getWidth(), availableHeight / art.getHeight());
            imageWidth = Math.round(art.getWidth() * scale);
            imageHeight = Math.round(art.getHeight() * scale);
        } else {
            imageWidth = imageHeight = Math.round(height * (small ? 0.9f : 0.55f));
        }
        LinearLayout.LayoutParams imageParams = (LinearLayout.LayoutParams) image.getLayoutParams();
        if (imageParams.width != imageWidth || imageParams.height != imageHeight) {
            imageParams.width = imageWidth;
            imageParams.height = imageHeight;
            image.setLayoutParams(imageParams);
        }
        if (image.getTag() != art) {
            boolean replacing = image.getTag() != null && art != null;
            image.setImageBitmap(art);
            image.setTag(art);
            // Behind the page's gradient (YouTube Music) the cover keeps its slice of it.
            if (!(cover.getBackground() instanceof PageSlice)) {
                cover.setBackground(background(Track.musicApp && art != null ? artColor : 0));
            }
            if (replacing) {
                image.setAlpha(0f);
                image.animate().alpha(1f).setDuration(250).start();
            }
        }
        image.setVisibility(art == null ? View.GONE : View.VISIBLE);
    }

    /**
     * Like Spotify's player: the cover's color at the top, fading into dark gray. Black without
     * a color.
     */
    private static android.graphics.drawable.Drawable background(int color) {
        if (color == 0 || !Features.coverPage()) return new android.graphics.drawable.ColorDrawable(BLACK);
        return pageBackground(color);
    }

    private static int blend(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /**
     * The cover's dominant color: the most weighted hue among its colorful pixels (by saturation
     * and brightness), averaged; the plain average for gray covers. Darkened enough for white
     * text on it.
     */
    static int dominantColor(Bitmap bitmap) {
        Bitmap small = Bitmap.createScaledBitmap(bitmap, 32, 32, true);
        float[] weight = new float[12];
        float[][] sum = new float[12][3];
        long r = 0, g = 0, b = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < small.getHeight(); y++) {
            for (int x = 0; x < small.getWidth(); x++) {
                int c = small.getPixel(x, y);
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
                android.graphics.Color.colorToHSV(c, hsv);
                if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue;
                int bucket = Math.min(11, (int) (hsv[0] / 30));
                float w = hsv[1] * hsv[2];
                weight[bucket] += w;
                sum[bucket][0] += ((c >> 16) & 0xFF) * w;
                sum[bucket][1] += ((c >> 8) & 0xFF) * w;
                sum[bucket][2] += (c & 0xFF) * w;
            }
        }
        if (small != bitmap) small.recycle();
        int best = 0;
        for (int i = 1; i < 12; i++) if (weight[i] > weight[best]) best = i;
        int count = 32 * 32;
        int color;
        // A clear color only if enough of the cover has it.
        if (weight[best] > count * 0.04f) {
            color = android.graphics.Color.rgb(Math.round(sum[best][0] / weight[best]),
                    Math.round(sum[best][1] / weight[best]), Math.round(sum[best][2] / weight[best]));
        } else {
            color = android.graphics.Color.rgb((int) (r / count), (int) (g / count), (int) (b / count));
        }
        android.graphics.Color.colorToHSV(color, hsv);
        // Vivid, but dark enough for white text on it.
        if (hsv[1] > 0.15f) hsv[1] = Math.min(1f, hsv[1] * 1.15f);
        hsv[2] = Math.max(0.3f, Math.min(hsv[2], 0.6f));
        return android.graphics.Color.HSVToColor(hsv);
    }

    private static void remove(View surface) {
        FrameLayout cover = covers.remove(surface);
        if (cover != null && cover.getParent() instanceof ViewGroup) {
            ((ViewGroup) cover.getParent()).removeView(cover);
        }
    }

    private static void removeAll() {
        restorePage();
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
                int color = bitmap == null ? 0 : dominantColor(bitmap);
                main.post(() -> {
                    if (url.equals(artUrl)) {
                        art = bitmap;
                        artColor = color;
                        scheduleScan();
                    }
                });
            } catch (Exception e) {
                Log.d("Cover image not loaded: " + e.getMessage());
                // Not the previous song's cover for this one.
                main.post(() -> {
                    if (url.equals(artUrl)) {
                        art = null;
                        artColor = 0;
                        scheduleScan();
                    }
                });
            }
        }, "YtDlna-cover").start();
    }
}
