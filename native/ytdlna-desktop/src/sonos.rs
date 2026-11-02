//! Sonos rooms and groups, through Sonos' own UPnP services (ZoneGroupTopology,
//! GroupRenderingControl), as the Android app's Sonos.java and SonosRooms.java.
//!
//! Plain DLNA knows no groups: a cast goes to the group's coordinator, the other rooms play along
//! in sync (Sonos does that itself). Rooms join with `x-rincon:<coordinator>` and leave as a group
//! of their own.

use std::io;

use crate::dlna::{self, Renderer};

#[derive(Clone, Debug, PartialEq)]
pub struct Zone {
    /// "RINCON_..."
    pub uuid: String,
    pub name: String,
    /// The player's description URL.
    pub location: String,
    /// A stereo pair's second speaker, a sub, surrounds or a bridge: not a room of its own.
    pub invisible: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Group {
    pub coordinator: String,
    pub members: Vec<Zone>,
}

impl Group {
    /// The visible rooms, the coordinator first.
    pub fn rooms(&self) -> Vec<&Zone> {
        let mut rooms: Vec<&Zone> = self.members.iter().filter(|z| !z.invisible).collect();
        rooms.sort_by_key(|z| z.uuid != self.coordinator);
        rooms
    }

    pub fn contains(&self, uuid: &str) -> bool {
        self.members.iter().any(|z| z.uuid == uuid)
    }

    /// "Kitchen", "Kitchen + 1".
    pub fn label(&self) -> String {
        let rooms = self.rooms();
        match rooms.len() {
            0 => String::new(),
            1 => rooms[0].name.clone(),
            n => format!("{} + {}", rooms[0].name, n - 1),
        }
    }
}

/// "RINCON_..." of a player ("uuid:RINCON_..._MR" for its media renderer part).
pub fn uuid(r: &Renderer) -> String {
    r.udn.trim_start_matches("uuid:").trim_end_matches("_MR").to_string()
}

pub fn is(r: &Renderer) -> bool {
    r.has("ZoneGroupTopology")
}

/// The household's groups, as any of its players knows them.
pub fn topology(r: &Renderer) -> io::Result<Vec<Group>> {
    let response = r.call("ZoneGroupTopology", "GetZoneGroupState", &[])?;
    Ok(parse(&dlna::unescape(&response)))
}

/// The ZoneGroupState XML (older firmware: the same inside ZoneGroups).
pub fn parse(xml: &str) -> Vec<Group> {
    let mut groups = Vec::new();
    for part in xml.split("<ZoneGroup ").skip(1) {
        let Some((attributes, body)) = part.split_once('>') else { continue };
        let body = body.split("</ZoneGroup>").next().unwrap_or("");
        let coordinator = attribute(attributes, "Coordinator");
        let members: Vec<Zone> = body.split("<ZoneGroupMember ").skip(1).filter_map(|m| {
            let a = m.split('>').next().unwrap_or("");
            let uuid = attribute(a, "UUID");
            (!uuid.is_empty()).then(|| Zone {
                uuid,
                // The names are escaped once more inside the attribute.
                name: dlna::unescape(&attribute(a, "ZoneName")),
                location: attribute(a, "Location"),
                invisible: attribute(a, "Invisible") == "1" || attribute(a, "IsZoneBridge") == "1",
            })
        }).collect();
        if !coordinator.is_empty() && !members.is_empty() {
            groups.push(Group { coordinator, members });
        }
    }
    groups
}

fn attribute(attributes: &str, name: &str) -> String {
    let needle = format!(" {name}=\"");
    let padded = format!(" {attributes}");
    padded.find(&needle).map(|i| {
        let rest = &padded[i + needle.len()..];
        rest[..rest.find('"').unwrap_or(rest.len())].to_string()
    }).unwrap_or_default()
}

/// Adds the room `member` to the group of `coordinator_uuid`.
pub fn join(member: &Renderer, coordinator_uuid: &str) -> io::Result<()> {
    member.call("AVTransport", "SetAVTransportURI",
                &[("CurrentURI", &format!("x-rincon:{coordinator_uuid}")), ("CurrentURIMetaData", "")]).map(|_| ())
}

/// Takes the room out of its group; it is then on its own (and stops playing).
pub fn leave(member: &Renderer) -> io::Result<()> {
    member.call("AVTransport", "BecomeCoordinatorOfStandaloneGroup", &[]).map(|_| ())
}

/// The group's volume 0-100 (asked at its coordinator).
pub fn group_volume(coordinator: &Renderer) -> io::Result<u32> {
    let r = coordinator.call("GroupRenderingControl", "GetGroupVolume", &[])?;
    Ok(dlna::tag(&r, "CurrentVolume").and_then(|v| v.parse().ok()).unwrap_or(0))
}

/// Sets the group's volume; the rooms keep their balance to each other, as in the Sonos app.
pub fn set_group_volume(coordinator: &Renderer, volume: u32) -> io::Result<()> {
    // Sonos scales the rooms from a snapshot of their volumes.
    coordinator.call("GroupRenderingControl", "SnapshotGroupVolume", &[])?;
    coordinator.call("GroupRenderingControl", "SetGroupVolume", &[("DesiredVolume", &volume.min(100).to_string())]).map(|_| ())
}

#[cfg(test)]
mod tests {
    use super::*;

    const STATE: &str = r#"<ZoneGroupState><ZoneGroups><ZoneGroup Coordinator="RINCON_A" ID="RINCON_B:1"><ZoneGroupMember UUID="RINCON_A" Location="http://192.168.27.91:1400/xml/device_description.xml" ZoneName="Giulia&apos;s Zimmer" Icon=""/><ZoneGroupMember UUID="RINCON_B" Location="http://192.168.27.92:1400/xml/device_description.xml" ZoneName="Zwilli&apos;s Zimmer"/></ZoneGroup><ZoneGroup Coordinator="RINCON_C" ID="RINCON_C:2"><ZoneGroupMember UUID="RINCON_C" Location="http://c/xml" ZoneName="Küche"><Satellite UUID="RINCON_D" Location="http://d/xml" ZoneName="Küche" Invisible="1"/></ZoneGroupMember><ZoneGroupMember UUID="RINCON_E" Location="http://e/xml" ZoneName="Bridge" IsZoneBridge="1"/></ZoneGroup></ZoneGroups></ZoneGroupState>"#;

    #[test]
    fn parses_groups() {
        let groups = parse(STATE);
        assert_eq!(groups.len(), 2);
        assert_eq!(groups[0].coordinator, "RINCON_A");
        assert_eq!(groups[0].label(), "Giulia's Zimmer + 1");
        assert!(groups[0].contains("RINCON_B"));
        assert_eq!(groups[1].label(), "Küche");
        assert_eq!(groups[1].rooms().len(), 1);
    }

    #[test]
    fn coordinator_first() {
        let group = Group {
            coordinator: "B".into(),
            members: vec![
                Zone { uuid: "A".into(), name: "a".into(), location: String::new(), invisible: false },
                Zone { uuid: "B".into(), name: "b".into(), location: String::new(), invisible: false },
            ],
        };
        assert_eq!(group.label(), "b + 1");
    }
}
