package app.ytdlna.extension;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sonos rooms and groups, through Sonos' own UPnP services (ZoneGroupTopology,
 * GroupRenderingControl). Plain DLNA does not know groups: a cast goes to the group's
 * coordinator, the other rooms play along in sync (Sonos does that itself).
 * <p>
 * Only for renderers with ZoneGroupTopology; nothing here touches other renderers.
 */
final class Sonos {
    /** Group topology is read again after this long. */
    private static final long TOPOLOGY_MAX_AGE_MS = 10_000;

    static final class Zone {
        final String uuid;
        final String name;
        final String location;
        /** A stereo pair's second speaker, a sub or surround: not a room of its own. */
        final boolean invisible;

        Zone(String uuid, String name, String location, boolean invisible) {
            this.uuid = uuid;
            this.name = name;
            this.location = location;
            this.invisible = invisible;
        }
    }

    static final class Group {
        final String coordinator;
        /** Visible rooms, the coordinator first. */
        final List<Zone> rooms;
        /** All members, including invisible ones. */
        final List<Zone> members;

        Group(String coordinator, List<Zone> members) {
            this.coordinator = coordinator;
            this.members = members;
            List<Zone> rooms = new ArrayList<>();
            for (Zone z : members) {
                if (z.invisible) continue;
                if (z.uuid.equals(coordinator)) rooms.add(0, z);
                else rooms.add(z);
            }
            this.rooms = rooms;
        }

        boolean contains(String uuid) {
            for (Zone z : members) if (z.uuid.equals(uuid)) return true;
            return false;
        }

        /** "Kitchen", "Kitchen + 1". */
        String label() {
            if (rooms.isEmpty()) return "";
            return rooms.size() == 1 ? rooms.get(0).name : rooms.get(0).name + " + " + (rooms.size() - 1);
        }
    }

    private static final class Topology {
        final long time;
        final List<Group> groups;

        Topology(long time, List<Group> groups) {
            this.time = time;
            this.groups = groups;
        }
    }

    /** Per household, keyed by any player's topology URL. */
    private static final Map<String, Topology> topologies = new ConcurrentHashMap<>();

    private Sonos() {
    }

    static boolean is(DlnaDevice device) {
        return device.zoneGroupTopologyUrl != null;
    }

    /** "RINCON_…" of a device ("uuid:RINCON_…" in its description). */
    static String uuid(DlnaDevice device) {
        String udn = device.udn;
        return udn.startsWith("uuid:") ? udn.substring(5) : udn;
    }

    // region Topology

    /** The groups as last read, or null if not read yet (or too old). Does not block. */
    static List<Group> cached(DlnaDevice device) {
        Topology t = topologies.get(key(device));
        return t == null || System.currentTimeMillis() - t.time > TOPOLOGY_MAX_AGE_MS ? null : t.groups;
    }

    /** The groups as last read, however old; null if never read. Does not block. */
    static List<Group> lastKnown(DlnaDevice device) {
        Topology t = topologies.get(key(device));
        return t == null ? null : t.groups;
    }

    /** Reads the groups from the player (network). */
    static List<Group> refresh(DlnaDevice device) throws IOException {
        String response = DlnaController.soap(device.zoneGroupTopologyUrl, device.zoneGroupTopologyType,
                "GetZoneGroupState", "");
        List<Group> groups = parse(unescape(response));
        Topology t = new Topology(System.currentTimeMillis(), groups);
        // Every player of the household answers the same: share it with all of them.
        for (Group g : groups) {
            for (Zone z : g.members) {
                if (!z.location.isEmpty()) topologies.put(locationKey(z.location), t);
            }
        }
        topologies.put(key(device), t);
        return groups;
    }

    /** The device's group, from {@link #lastKnown}; null if unknown. */
    static Group groupOf(DlnaDevice device) {
        List<Group> groups = lastKnown(device);
        if (groups == null) return null;
        String uuid = uuid(device);
        for (Group g : groups) if (g.contains(uuid)) return g;
        return null;
    }

    static Zone zone(List<Group> groups, String uuid) {
        if (groups == null) return null;
        for (Group g : groups) for (Zone z : g.members) if (z.uuid.equals(uuid)) return z;
        return null;
    }

    private static String key(DlnaDevice device) {
        return locationKey(device.location);
    }

    /** Host and port: the description path is the same on every player. */
    private static String locationKey(String location) {
        try {
            java.net.URL u = new java.net.URL(location);
            return u.getHost() + ":" + (u.getPort() == -1 ? 80 : u.getPort());
        } catch (IOException e) {
            return location;
        }
    }

    private static final Pattern GROUP = Pattern.compile("<ZoneGroup\\b([^>]*)>(.*?)</ZoneGroup>", Pattern.DOTALL);
    private static final Pattern MEMBER = Pattern.compile("<ZoneGroupMember\\b([^>]*?)/?>");

    /** The ZoneGroupState XML (older firmware: the same inside ZoneGroups). */
    static List<Group> parse(String xml) {
        List<Group> groups = new ArrayList<>();
        Matcher g = GROUP.matcher(xml);
        while (g.find()) {
            String coordinator = attribute(g.group(1), "Coordinator");
            List<Zone> members = new ArrayList<>();
            Matcher m = MEMBER.matcher(g.group(2));
            while (m.find()) {
                String a = m.group(1);
                String uuid = attribute(a, "UUID");
                if (uuid.isEmpty()) continue;
                members.add(new Zone(uuid, xmlText(attribute(a, "ZoneName")), attribute(a, "Location"),
                        "1".equals(attribute(a, "Invisible")) || "1".equals(attribute(a, "IsZoneBridge"))));
            }
            if (!coordinator.isEmpty() && !members.isEmpty()) groups.add(new Group(coordinator, members));
        }
        return Collections.unmodifiableList(groups);
    }

    private static String attribute(String attributes, String name) {
        Matcher m = Pattern.compile("\\b" + name + "=\"([^\"]*)\"").matcher(attributes);
        return m.find() ? m.group(1) : "";
    }

    /** The SOAP answer carries the state XML escaped once; the attributes inside again. */
    private static String unescape(String text) {
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    private static String xmlText(String value) {
        return value.replace("&apos;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    // endregion

    // region Rooms

    /** Adds the room {@code member} to the group of {@code coordinatorUuid}. */
    static void join(DlnaDevice member, String coordinatorUuid) throws IOException {
        DlnaController.soap(member.avTransportControlUrl, member.avTransportType, "SetAVTransportURI",
                "<InstanceID>0</InstanceID><CurrentURI>x-rincon:" + coordinatorUuid
                        + "</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>");
        forget(member);
    }

    /** Takes the room out of its group; it is then on its own (and stops playing). */
    static void leave(DlnaDevice member) throws IOException {
        DlnaController.soap(member.avTransportControlUrl, member.avTransportType,
                "BecomeCoordinatorOfStandaloneGroup", "<InstanceID>0</InstanceID>");
        forget(member);
    }

    /** The topology changed: read it again next time. */
    private static void forget(DlnaDevice device) {
        Topology t = topologies.get(key(device));
        if (t == null) return;
        for (Map.Entry<String, Topology> e : topologies.entrySet()) {
            if (e.getValue() == t) topologies.remove(e.getKey());
        }
    }

    // endregion

    // region Volume

    /** Whether the device leads a group of more than one room (then the volume is the group's). */
    static boolean leadsGroup(DlnaDevice device) {
        if (!is(device) || device.groupRenderingControlUrl == null) return false;
        Group g = groupOf(device);
        return g != null && g.coordinator.equals(uuid(device)) && g.rooms.size() > 1;
    }

    /** Group volume 0-100 (on the coordinator), or -1. */
    static int groupVolume(DlnaDevice coordinator) throws IOException {
        String response = DlnaController.soap(coordinator.groupRenderingControlUrl,
                coordinator.groupRenderingControlType, "GetGroupVolume", "<InstanceID>0</InstanceID>");
        Matcher m = Pattern.compile("<CurrentVolume>(\\d+)</CurrentVolume>").matcher(response);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** Sets the group volume; the rooms keep their balance to each other, as in the Sonos app. */
    static void setGroupVolume(DlnaDevice coordinator, int volume) throws IOException {
        // Sonos scales the rooms from a snapshot of their volumes.
        DlnaController.soap(coordinator.groupRenderingControlUrl, coordinator.groupRenderingControlType,
                "SnapshotGroupVolume", "<InstanceID>0</InstanceID>");
        DlnaController.soap(coordinator.groupRenderingControlUrl, coordinator.groupRenderingControlType,
                "SetGroupVolume", "<InstanceID>0</InstanceID><DesiredVolume>"
                        + Math.max(0, Math.min(100, volume)) + "</DesiredVolume>");
    }

    // endregion
}
