package app.ytdlna.extension;

import android.app.Activity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A renderer that feeds Sonos rooms itself (service urn:ytdlna-org:service:Zones:1): it plays
 * the cast and streams the same sound, in sync, to the rooms the user adds. The cast still goes
 * to the renderer; this only adds or removes rooms and sets their volume. See
 * docs/renderer-guide.md.
 */
final class RendererZones implements RoomsSheet.Source {
    static final String SERVICE = "urn:ytdlna-org:service:Zones:";

    static final class Zone {
        final String id, name, group;
        final boolean coordinator, active, busy, mute;
        final int volume;

        Zone(JSONObject o) {
            id = o.optString("id");
            name = o.optString("name", id);
            group = o.optString("group");
            coordinator = o.optBoolean("coordinator");
            active = o.optBoolean("active");
            busy = o.optBoolean("busy");
            mute = o.optBoolean("mute");
            volume = o.optInt("volume", -1);
        }
    }

    static final class Snapshot {
        final long version;
        final List<Zone> zones;
        /** idle, measuring, locked, lost; "" if not told. */
        final String sync;
        /** Why the rooms last stopped playing along by themselves: stopped, taken, unreachable, length; "". */
        final String lastEnd;

        Snapshot(long version, List<Zone> zones, String sync, String lastEnd) {
            this.version = version;
            this.zones = zones;
            this.sync = sync;
            this.lastEnd = lastEnd;
        }

        int active() {
            int n = 0;
            for (Zone z : zones) if (z.active) n++;
            return n;
        }
    }

    /** Last state per renderer (description URL). */
    private static final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();

    private final DlnaDevice device;

    private RendererZones(DlnaDevice device) {
        this.device = device;
    }

    static boolean has(DlnaDevice device) {
        return Features.rooms() && device.zonesUrl != null;
    }

    static Snapshot cached(DlnaDevice device) {
        return snapshots.get(device.location);
    }

    /** "Living room TV + 2" while rooms play along. */
    static String label(DlnaDevice device) {
        Snapshot s = cached(device);
        int active = s == null ? 0 : s.active();
        return active == 0 ? device.friendlyName : device.friendlyName + " + " + active;
    }

    /** Whether the renderer leads rooms now (then the volume keys set the rooms' volume). */
    static boolean leadsRooms(DlnaDevice device) {
        Snapshot s = cached(device);
        return has(device) && device.groupRenderingControlUrl != null && s != null && s.active() > 0;
    }

    /**
     * Whether a Sonos group (by its coordinator) is a renderer's room group: then it is not a
     * cast target of its own, and its rooms are the renderer's to move.
     */
    static boolean ownsGroup(String sonosCoordinator) {
        for (Snapshot s : snapshots.values()) {
            for (Zone z : s.zones) {
                if (z.active && (sonosCoordinator.equals(z.group) || sonosCoordinator.equals(z.id))) return true;
            }
        }
        return false;
    }

    private static final Pattern ZONES = Pattern.compile("<Zones>(.*?)</Zones>", Pattern.DOTALL);
    private static final Pattern VERSION = Pattern.compile("<Version>(\\d+)</Version>");

    /** Asks the renderer (cheap: it answers from a cache). Null if nothing changed. */
    static Snapshot refresh(DlnaDevice device) throws Exception {
        Snapshot old = cached(device);
        String response = DlnaController.soap(device.zonesUrl, device.zonesType, "GetZones",
                "<SinceVersion>" + (old == null ? 0 : old.version) + "</SinceVersion>");
        Matcher m = ZONES.matcher(response);
        String json = m.find() ? unescape(m.group(1)).trim() : "";
        if (json.isEmpty()) {
            if (old != null) return null;
            json = "{}";
        }
        Snapshot s = parse(json);
        snapshots.put(device.location, s);
        return s;
    }

    static Snapshot parse(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        List<Zone> zones = new ArrayList<>();
        JSONArray list = o.optJSONArray("zones");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) zones.add(new Zone(list.getJSONObject(i)));
        }
        JSONObject sync = o.optJSONObject("sync");
        return new Snapshot(o.optLong("version"), Collections.unmodifiableList(zones),
                sync == null ? "" : sync.optString("state"), o.optString("lastEnd"));
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    static void show(Activity activity, DlnaDevice device, Runnable onChanged) {
        RoomsSheet.show(activity, label(device), new RendererZones(device), onChanged);
    }

    // region Rooms dialog

    private boolean first = true;
    /** Rooms muted in the last snapshot: the slider unmutes them. */
    private final java.util.Set<String> muted = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Override
    public List<RoomsSheet.Room> load() throws Exception {
        Snapshot s = refresh(device);
        if (s == null && !first) return null;
        first = false;
        if (s == null) s = cached(device);
        List<RoomsSheet.Room> rooms = new ArrayList<>();
        muted.clear();
        for (Zone z : s.zones) {
            if (z.mute) muted.add(z.id);
            String state = z.active ? DlnaCast.t("Spielt mit", "Plays along")
                    : z.busy ? DlnaCast.t("Spielt gerade etwas anderes", "Playing something else")
                    : DlnaCast.t("Tippen zum Hinzufügen", "Tap to add");
            if (z.mute) state += DlnaCast.t(" · stumm", " · muted");
            // A room busy with another source is not taken away from it.
            rooms.add(new RoomsSheet.Room(z.id, z.name, z.active, !z.active && z.busy, state, z.volume));
        }
        return rooms;
    }

    @Override
    public String title() {
        return label(device);
    }

    @Override
    public String note() {
        Snapshot s = cached(device);
        if (s == null) return null;
        if (s.active() == 0) {
            // Why the rooms stopped playing along last time, if they did by themselves.
            switch (s.lastEnd) {
                case "stopped":
                    return DlnaCast.t("Die Räume haben zuletzt aufgehört zu spielen",
                            "The rooms stopped playing last time");
                case "taken":
                    return DlnaCast.t("Die Räume wurden zuletzt von einer anderen Quelle übernommen",
                            "Another source took the rooms over last time");
                case "unreachable":
                    return DlnaCast.t("Die Räume haben den Fernseher zuletzt nicht erreicht",
                            "The rooms could not reach the TV last time");
                case "":
                    return null;
                default:
                    return DlnaCast.t("Die Räume haben zuletzt aufgehört mitzuspielen",
                            "The rooms stopped playing along last time");
            }
        }
        switch (s.sync) {
            case "measuring":
                return DlnaCast.t("Räume werden synchronisiert…", "Syncing the rooms…");
            case "locked":
                return DlnaCast.t("Räume spielen synchron zum Fernseher", "Rooms play in sync with the TV");
            case "lost":
                return DlnaCast.t("Synchronisation unterbrochen", "Sync lost");
            default:
                return null;
        }
    }

    @Override
    public void setIn(RoomsSheet.Room room, boolean in) throws Exception {
        DlnaController.soap(device.zonesUrl, device.zonesType, "SetZoneActive",
                "<ZoneId>" + escape(room.id) + "</ZoneId><Active>" + (in ? 1 : 0) + "</Active>");
    }

    @Override
    public void setVolume(RoomsSheet.Room room, int volume) throws Exception {
        // Moving the slider means "I want to hear it".
        if (muted.remove(room.id)) {
            DlnaController.soap(device.zonesUrl, device.zonesType, "SetZoneMute",
                    "<ZoneId>" + escape(room.id) + "</ZoneId><Mute>0</Mute>");
        }
        DlnaController.soap(device.zonesUrl, device.zonesType, "SetZoneVolume",
                "<ZoneId>" + escape(room.id) + "</ZoneId><Volume>" + Math.max(0, Math.min(100, volume)) + "</Volume>");
    }

    @Override
    public long pollMs() {
        return 2500;
    }

    private static final Pattern FAULT = Pattern.compile("HTTP \\d+ (\\d+)");

    /** The renderer's UPnP error code in a failed call, or -1. */
    static int faultCode(Exception e) {
        Matcher m = FAULT.matcher(String.valueOf(e.getMessage()));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    @Override
    public String errorText(Exception e) {
        switch (faultCode(e)) {
            case 701:
                return DlnaCast.t("Diesen Raum gibt es nicht mehr", "This room is gone");
            case 702:
                return DlnaCast.t("Der Raum spielt gerade etwas anderes", "The room is playing something else");
            case 704:
                return DlnaCast.t("Der Lautsprecher antwortet nicht", "The speaker does not answer");
            default:
                return RoomsSheet.Source.super.errorText(e);
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // endregion
}
