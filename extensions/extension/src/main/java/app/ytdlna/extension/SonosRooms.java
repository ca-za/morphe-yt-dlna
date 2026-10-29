package app.ytdlna.extension;

import android.app.Activity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The rooms of a Sonos group, as in the Sonos app: Sonos keeps the rooms in sync itself. Rooms
 * join with x-rincon:&lt;coordinator&gt; and leave as a group of their own.
 */
final class SonosRooms implements RoomsSheet.Source {
    /** Players by description URL, loaded once. */
    private static final Map<String, DlnaDevice> players = new ConcurrentHashMap<>();

    private final DlnaDevice coordinator;
    private final Map<String, DlnaDevice> devices = new ConcurrentHashMap<>();
    private String title;

    private SonosRooms(DlnaDevice coordinator) {
        this.coordinator = coordinator;
        Sonos.Group start = Sonos.groupOf(coordinator);
        title = start == null ? coordinator.friendlyName : start.label();
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
        SonosRooms source = new SonosRooms(coordinator);
        RoomsSheet.show(activity, source.title, source, onChanged);
    }

    @Override
    public List<RoomsSheet.Room> load() throws Exception {
        List<Sonos.Group> groups = Sonos.refresh(coordinator);
        String lead = Sonos.uuid(coordinator);
        Sonos.Group own = null;
        for (Sonos.Group g : groups) if (g.contains(lead)) own = g;
        if (own != null) title = own.label();
        List<RoomsSheet.Room> rooms = new ArrayList<>();
        for (Sonos.Group g : groups) {
            // Rooms another renderer feeds (its own group): not ours to move.
            if (RendererZones.ownsGroup(g.coordinator)) continue;
            for (Sonos.Zone z : g.rooms) {
                DlnaDevice device = player(z);
                if (device == null) continue;
                devices.put(z.uuid, device);
                int volume;
                try {
                    volume = DlnaController.volume(device);
                } catch (Exception e) {
                    volume = -1;
                }
                boolean leading = z.uuid.equals(lead);
                boolean in = own != null && own.contains(z.uuid);
                String state = leading ? DlnaCast.t("Führt die Gruppe", "Leads the group")
                        : in ? DlnaCast.t("Spielt mit", "Plays along")
                        : DlnaCast.t("Tippen zum Hinzufügen", "Tap to add");
                // The leading room first; it stays in (its playback is the cast).
                rooms.add(leading ? 0 : rooms.size(), new RoomsSheet.Room(z.uuid, z.name, in, leading, state, volume));
            }
        }
        return rooms;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public String note() {
        return null;
    }

    @Override
    public void setIn(RoomsSheet.Room room, boolean in) throws Exception {
        DlnaDevice device = devices.get(room.id);
        if (device == null) throw new IllegalStateException("No player for " + room.name);
        if (in) {
            Sonos.join(device, Sonos.uuid(coordinator));
        } else {
            Sonos.leave(device);
        }
    }

    @Override
    public void setVolume(RoomsSheet.Room room, int volume) throws Exception {
        DlnaDevice device = devices.get(room.id);
        if (device != null) DlnaController.setVolume(device, volume);
    }

    @Override
    public long pollMs() {
        return 0;
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
