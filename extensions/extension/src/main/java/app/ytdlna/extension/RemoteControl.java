package app.ytdlna.extension;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.MediaMetadata;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remote control of a DLNA session another phone (or this one, earlier) started: a media session
 * like Spotify Connect's, with play/pause, seek, next/previous (renderers with a playlist) and the
 * renderer's volume on the volume keys. Part of the patch "DLNA session notifications"; runs in
 * the job's own process (":ytdlna").
 * <p>
 * It never takes audio focus, so nothing pauses it, and it pauses nothing: when the phone plays
 * something itself, the control just goes away and the renderer plays on. Commands go to the
 * renderer only when the user taps one.
 * <p>
 * Android does not let a background job start a foreground service: the job's notification has
 * plain buttons, and the first tap on one starts the media session (a service in the foreground,
 * which keeps its process alive while it controls). It reads the renderer every 5 s while the
 * screen is on, not at all while it is off, and ends when the renderer stops, the title is not
 * ours any more, the phone plays or casts itself, or the user closes it.
 */
final class RemoteControl {
    static final String ACTION = "app.ytdlna.REMOTE";
    static final String EXTRA_COMMAND = "command";
    static final String EXTRA_LOCATION = "location";
    static final String EXTRA_TITLE = "title";

    static final String PLAY = "play", PAUSE = "pause", NEXT = "next", PREVIOUS = "previous",
            CONTROL = "control", CLOSE = "close", DISMISS = "dismiss";

    private static final String PREFS = "ytdlna_remote";
    private static final String PREF_DISMISSED = "dismissed";
    private static final String CHANNEL_ID = "ytdlna_remote";
    static final int NOTIFICATION_ID = 0x59744403;
    private static final long POLL_MS = 5000;
    /** Stopped this many reads in a row: the session is over. */
    private static final int STOPPED_READS = 2;

    /** The remote control runs (this process). */
    static volatile boolean running;

    private RemoteControl() {
    }

    // region Renderer state

    /** What a renderer plays, as far as it says. */
    static final class State {
        /** PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING, NO_MEDIA_PRESENT, "" */
        String transport = "";
        String title = "", artist = "", art = "", uri = "";
        long positionMs = -1, durationMs = -1;
        /** Cast from this patch (our metadata). */
        boolean ours;

        boolean active() {
            return "PLAYING".equals(transport) || "PAUSED_PLAYBACK".equals(transport)
                    || "TRANSITIONING".equals(transport);
        }
    }

    private static final Pattern TITLE = Pattern.compile("<dc:title>(.*?)</dc:title>", Pattern.DOTALL);
    private static final Pattern ARTIST = Pattern.compile("<(?:upnp:artist|dc:creator)[^>]*>(.*?)</", Pattern.DOTALL);
    private static final Pattern ART = Pattern.compile("<upnp:albumArtURI[^>]*>(.*?)</upnp:albumArtURI>", Pattern.DOTALL);

    static State read(DlnaDevice device) throws Exception {
        State s = new State();
        String didl;
        if (device.infoControlUrl != null && device.hasOpenHomePlaylist()) {
            s.transport = OpenHome.toAvTransportState(OpenHome.transportState(device));
            String track = DlnaController.soap(device.infoControlUrl, device.infoType, "Track", "");
            didl = tag(track, "Metadata");
            s.uri = unescape(tag(track, "Uri"));
            if (device.timeControlUrl != null) {
                String time = DlnaController.soap(device.timeControlUrl, device.timeType, "Time", "");
                s.positionMs = seconds(tag(time, "Seconds"));
                s.durationMs = seconds(tag(time, "Duration"));
            }
        } else {
            s.transport = DlnaController.transportState(device);
            String media = DlnaController.soap(device.avTransportControlUrl, device.avTransportType,
                    "GetMediaInfo", "<InstanceID>0</InstanceID>");
            didl = tag(media, "CurrentURIMetaData");
            s.uri = unescape(tag(media, "CurrentURI"));
            String position = DlnaController.soap(device.avTransportControlUrl, device.avTransportType,
                    "GetPositionInfo", "<InstanceID>0</InstanceID>");
            s.positionMs = Track.parseUpnpTime(tag(position, "RelTime"));
            s.durationMs = Track.parseUpnpTime(tag(position, "TrackDuration"));
        }
        didl = unescape(didl);
        s.ours = didl.contains("id=\"ytdlna-") || didl.contains(RendererExtras.NAMESPACE);
        s.title = group(TITLE, didl);
        s.artist = group(ARTIST, didl);
        s.art = group(ART, didl);
        return s;
    }

    private static long seconds(String value) {
        try {
            return Long.parseLong(value.trim()) * 1000;
        } catch (Exception e) {
            return -1;
        }
    }

    private static String group(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? unescape(m.group(1)).trim() : "";
    }

    static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + "(?:\\s[^>]*)?>(.*?)</" + name + ">", Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1) : "";
    }

    static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&#39;", "'").replace("&amp;", "&");
    }

    static boolean hasPlaylist(DlnaDevice device) {
        return device.hasOpenHomePlaylist();
    }

    // endregion

    // region Commands (only on the user's tap)

    static void play(DlnaDevice device) throws Exception {
        if (device.hasOpenHomePlaylist()) OpenHome.play(device);
        else DlnaController.resume(device);
    }

    static void pause(DlnaDevice device) throws Exception {
        if (device.hasOpenHomePlaylist()) OpenHome.pause(device);
        else DlnaController.pause(device);
    }

    static void seek(DlnaDevice device, long ms) throws Exception {
        if (device.hasOpenHomePlaylist()) OpenHome.seekSeconds(device, ms);
        else DlnaController.seek(device, ms);
    }

    static void skip(DlnaDevice device, boolean next) throws Exception {
        if (!device.hasOpenHomePlaylist()) return;
        DlnaController.soap(device.playlistControlUrl, device.playlistType, next ? "Next" : "Previous", "");
    }

    // endregion

    // region Dismissed sessions

    /** The user swiped the notice of this title away: not shown again for it. */
    static void dismiss(Context context, String location, String title) {
        prefs(context).edit().putString(PREF_DISMISSED, location + "\n" + title).apply();
    }

    static boolean dismissed(Context context, String location, String title) {
        return (location + "\n" + title).equals(prefs(context).getString(PREF_DISMISSED, ""));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // endregion

    /** Whether the phone plays something itself (any app): then the remote control steps aside. */
    static boolean playingHere(Context context) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) return false;
        if (audio.isMusicActive()) return true;
        // isMusicActive misses players at volume 0: the active media players themselves.
        try {
            java.lang.reflect.Method isActive = AudioPlaybackConfiguration.class.getMethod("isActive");
            for (AudioPlaybackConfiguration c : audio.getActivePlaybackConfigurations()) {
                int usage = c.getAudioAttributes().getUsage();
                boolean media = usage == android.media.AudioAttributes.USAGE_MEDIA
                        || usage == android.media.AudioAttributes.USAGE_GAME;
                if (media && Boolean.TRUE.equals(isActive.invoke(c))) return true;
            }
        } catch (Exception ignored) {
            // No isActive on this Android version: isMusicActive only.
        }
        return false;
    }

    static PendingIntent intent(Context context, String command, String location, String title) {
        Intent intent = new Intent(context, Receiver.class).setAction(ACTION + "." + command)
                .putExtra(EXTRA_COMMAND, command).putExtra(EXTRA_LOCATION, location).putExtra(EXTRA_TITLE, title);
        return PendingIntent.getBroadcast(context, command.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static String t(String german, String english) {
        return Locale.getDefault().getLanguage().equals("de") ? german : english;
    }

    static void channel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, t("DLNA-Fernsteuerung", "DLNA remote control"),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(t("Wiedergabe auf DLNA-Geräten im WLAN steuern",
                "Control playback on DLNA devices in the Wi-Fi"));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    static String channelId() {
        return CHANNEL_ID;
    }

    /** The notification buttons: starts the remote control (allowed: the user tapped). */
    public static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String command = intent.getStringExtra(EXTRA_COMMAND);
            String location = intent.getStringExtra(EXTRA_LOCATION);
            String title = intent.getStringExtra(EXTRA_TITLE);
            if (command == null || location == null) return;
            if (DISMISS.equals(command)) {
                dismiss(context, location, title == null ? "" : title);
                return;
            }
            Intent start = new Intent(context, Remote.class).putExtras(intent);
            try {
                context.startForegroundService(start);
            } catch (Exception e) {
                Log.d("Remote control not started: " + e);
            }
        }
    }

    /** The media session, in the foreground while it controls a renderer. */
    public static final class Remote extends Service {
        private HandlerThread thread;
        private Handler worker;
        private final Handler main = new Handler(Looper.getMainLooper());
        private MediaSession session;
        private DlnaDevice device;
        private String location = "";
        private State last;
        private int stoppedReads;
        private String artFor = "";
        private Bitmap art;
        private int volume = -1;
        private boolean polling;
        private BroadcastReceiver screen;
        private final Runnable pollTask = this::poll;
        private AudioManager.AudioPlaybackCallback playback;

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }

        @Override
        public void onCreate() {
            super.onCreate();
            running = true;
            thread = new HandlerThread("YtDlna-remote");
            thread.start();
            worker = new Handler(thread.getLooper());
            channel(this);
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            // In the foreground at once (Android's rule for a started foreground service).
            foreground(placeholder());
            String command = intent == null ? null : intent.getStringExtra(EXTRA_COMMAND);
            String target = intent == null ? null : intent.getStringExtra(EXTRA_LOCATION);
            if (CLOSE.equals(command)) {
                close(false);
                return START_NOT_STICKY;
            }
            if (target == null) {
                close(false);
                return START_NOT_STICKY;
            }
            worker.post(() -> start(target, command));
            return START_NOT_STICKY;
        }

        private void start(String target, String command) {
            try {
                if (device == null || !target.equals(location)) {
                    device = DlnaDevice.load(target);
                    location = target;
                    volume = -1;
                }
                if (PLAY.equals(command)) play(device);
                else if (PAUSE.equals(command)) pause(device);
                else if (NEXT.equals(command)) skip(device, true);
                else if (PREVIOUS.equals(command)) skip(device, false);
                main.post(this::openSession);
                poll();
            } catch (Exception e) {
                Log.d("Remote control of " + target + " failed: " + e);
                main.post(() -> close(false));
            }
        }

        private void openSession() {
            if (session != null) return;
            session = new MediaSession(this, "YtDlnaRemote");
            session.setCallback(new MediaSession.Callback() {
                @Override
                public void onPlay() {
                    command(() -> play(device));
                }

                @Override
                public void onPause() {
                    command(() -> pause(device));
                }

                @Override
                public void onSeekTo(long pos) {
                    command(() -> seek(device, pos));
                }

                @Override
                public void onSkipToNext() {
                    command(() -> skip(device, true));
                }

                @Override
                public void onSkipToPrevious() {
                    command(() -> skip(device, false));
                }

                @Override
                public void onStop() {
                    // Stop the control, not the renderer.
                    close(false);
                }
            }, main);
            // The renderer's volume on the volume keys; no audio focus, nothing played here.
            session.setPlaybackToRemote(new VolumeProvider(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, 100,
                    Math.max(0, volume)) {
                @Override
                public void onSetVolumeTo(int value) {
                    setVolume(value);
                }

                @Override
                public void onAdjustVolume(int direction) {
                    if (direction != 0) setVolume(Math.max(0, volume) + direction * 3);
                }
            });
            Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (launch != null) {
                session.setSessionActivity(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE));
            }
            session.setActive(true);
            watchScreen();
            watchLocalPlayback();
        }

        private void setVolume(int value) {
            int target = Math.max(0, Math.min(100, value));
            command(() -> {
                DlnaController.setVolume(device, target);
                volume = target;
                main.post(() -> {
                    if (session != null && session.getController().getPlaybackInfo() != null) {
                        updateVolume();
                    }
                });
            });
        }

        private void updateVolume() {
            session.setPlaybackToRemote(new VolumeProvider(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, 100,
                    Math.max(0, volume)) {
                @Override
                public void onSetVolumeTo(int value) {
                    setVolume(value);
                }

                @Override
                public void onAdjustVolume(int direction) {
                    if (direction != 0) setVolume(Math.max(0, volume) + direction * 3);
                }
            });
        }

        private interface Call {
            void run() throws Exception;
        }

        /** A command the user gave; the state is read again right after. */
        private void command(Call call) {
            worker.post(() -> {
                try {
                    call.run();
                } catch (Exception e) {
                    Log.d("Remote command failed: " + e);
                }
                worker.removeCallbacks(pollTask);
                worker.postDelayed(pollTask, 600);
            });
        }

        /** Reads the renderer and updates the session; again in 5 s while the screen is on. */
        private void poll() {
            worker.removeCallbacks(pollTask);
            if (device == null) return;
            if (SessionWatch.castingHereNow(this) || playingHere(this)) {
                // The phone casts or plays itself: step aside, the renderer plays on.
                main.post(() -> close(false));
                return;
            }
            State s;
            try {
                s = read(device);
                if (volume < 0) volume = DlnaController.volume(device);
            } catch (Exception e) {
                Log.d("Remote state not read: " + e.getMessage());
                s = null;
            }
            if (s != null) {
                if (!s.ours || !s.active()) {
                    if (++stoppedReads >= STOPPED_READS) {
                        main.post(() -> close(false));
                        return;
                    }
                } else {
                    stoppedReads = 0;
                    loadArt(s.art);
                    State state = s;
                    main.post(() -> show(state));
                }
            }
            PowerManager power = getSystemService(PowerManager.class);
            polling = power == null || power.isInteractive();
            if (polling) worker.postDelayed(pollTask, POLL_MS);
        }

        private void loadArt(String url) {
            if (url.isEmpty() || url.equals(artFor)) return;
            artFor = url;
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(4000);
                connection.setReadTimeout(4000);
                try (InputStream in = connection.getInputStream()) {
                    art = BitmapFactory.decodeStream(in);
                } finally {
                    connection.disconnect();
                }
            } catch (Exception e) {
                art = null;
            }
        }

        private void show(State s) {
            if (session == null) return;
            last = s;
            MediaMetadata.Builder meta = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist.isEmpty() ? device.friendlyName : s.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, t("Auf ", "On ") + device.friendlyName);
            if (s.durationMs > 0) meta.putLong(MediaMetadata.METADATA_KEY_DURATION, s.durationMs);
            if (art != null) meta.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
            session.setMetadata(meta.build());
            boolean playing = !"PAUSED_PLAYBACK".equals(s.transport);
            long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP;
            if (hasPlaylist(device)) actions |= PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(actions)
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            Math.max(0, s.positionMs), playing ? 1f : 0f)
                    .build());
            if (volume >= 0) updateVolume();
            foreground(notification(s, playing));
        }

        private Notification placeholder() {
            return new Notification.Builder(this, channelId())
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle(t("DLNA-Fernsteuerung", "DLNA remote control"))
                    .setOnlyAlertOnce(true)
                    .build();
        }

        private Notification notification(State s, boolean playing) {
            Notification.Builder b = new Notification.Builder(this, channelId())
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle(s.title)
                    .setContentText(t("Auf ", "On ") + device.friendlyName)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setVisibility(Notification.VISIBILITY_PUBLIC);
            if (art != null) b.setLargeIcon(art);
            if (session != null) b.setContentIntent(session.getController().getSessionActivity());
            int[] compact;
            if (hasPlaylist(device)) {
                b.addAction(action(android.R.drawable.ic_media_previous, t("Zurück", "Previous"), PREVIOUS));
            }
            b.addAction(playing
                    ? action(android.R.drawable.ic_media_pause, t("Pause", "Pause"), PAUSE)
                    : action(android.R.drawable.ic_media_play, t("Abspielen", "Play"), PLAY));
            if (hasPlaylist(device)) {
                b.addAction(action(android.R.drawable.ic_media_next, t("Weiter", "Next"), NEXT));
            }
            b.addAction(action(android.R.drawable.ic_menu_close_clear_cancel, t("Schließen", "Close"), CLOSE));
            compact = hasPlaylist(device) ? new int[]{0, 1, 2} : new int[]{0};
            Notification.MediaStyle style = new Notification.MediaStyle().setShowActionsInCompactView(compact);
            if (session != null) style.setMediaSession(session.getSessionToken());
            b.setStyle(style);
            return b.build();
        }

        private Notification.Action action(int icon, String title, String command) {
            Intent intent = new Intent(this, Remote.class).setAction(ACTION + "." + command)
                    .putExtra(EXTRA_COMMAND, command).putExtra(EXTRA_LOCATION, location);
            PendingIntent pending = PendingIntent.getForegroundService(this, command.hashCode(), intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            return new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon),
                    title, pending).build();
        }

        private void foreground(Notification notification) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(NOTIFICATION_ID, notification);
                }
            } catch (Exception e) {
                Log.d("Remote control not in the foreground: " + e);
                stopSelf();
            }
        }

        /** Reading pauses while the screen is off and resumes when it comes on. */
        private void watchScreen() {
            if (screen != null) return;
            screen = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (Intent.ACTION_SCREEN_ON.equals(intent.getAction()) && !polling) worker.post(pollTask);
                }
            };
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screen, filter);
            }
        }

        /** Something starts playing on the phone: step aside at once (the renderer plays on). */
        private void watchLocalPlayback() {
            AudioManager audio = getSystemService(AudioManager.class);
            if (audio == null || playback != null) return;
            playback = new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    if (playingHere(Remote.this)) {
                        Log.i("Remote control: the phone plays itself, stepping aside");
                        close(false);
                    }
                }
            };
            audio.registerAudioPlaybackCallback(playback, main);
        }

        /** Ends the control (never a command to the renderer). */
        private void close(boolean keepNotice) {
            stopForeground(keepNotice ? STOP_FOREGROUND_DETACH : STOP_FOREGROUND_REMOVE);
            stopSelf();
        }

        @Override
        public void onDestroy() {
            running = false;
            worker.removeCallbacksAndMessages(null);
            thread.quitSafely();
            if (screen != null) unregisterReceiver(screen);
            AudioManager audio = getSystemService(AudioManager.class);
            if (audio != null && playback != null) audio.unregisterAudioPlaybackCallback(playback);
            if (session != null) {
                session.setActive(false);
                session.release();
                session = null;
            }
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.cancel(NOTIFICATION_ID);
            super.onDestroy();
        }
    }
}
