//! Finding renderers (SSDP), reading their services and calling them (SOAP).

use std::collections::BTreeSet;
use std::io;
use std::net::{IpAddr, UdpSocket};
use std::time::{Duration, Instant};

const SSDP: &str = "239.255.255.250:1900";

/// Description URLs of the media renderers that answer within `wait`.
pub fn discover(wait: Duration) -> io::Result<Vec<String>> {
    let socket = UdpSocket::bind("0.0.0.0:0")?;
    socket.set_multicast_ttl_v4(2)?;
    socket.set_read_timeout(Some(Duration::from_millis(300)))?;
    let search = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n\
                  ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n";
    // UDP gets lost: ask again after a moment.
    socket.send_to(search.as_bytes(), SSDP)?;
    let mut found = BTreeSet::new();
    let mut buffer = [0u8; 4096];
    let started = Instant::now();
    let mut searches = 1;
    while started.elapsed() < wait {
        if searches < 3 && started.elapsed() >= Duration::from_millis(800 * searches) {
            let _ = socket.send_to(search.as_bytes(), SSDP);
            searches += 1;
        }
        let Ok((n, _)) = socket.recv_from(&mut buffer) else { continue };
        let answer = String::from_utf8_lossy(&buffer[..n]);
        for line in answer.lines() {
            if let Some((key, value)) = line.split_once(':') {
                if key.eq_ignore_ascii_case("location") {
                    found.insert(value.trim().to_string());
                }
            }
        }
    }
    Ok(found.into_iter().collect())
}

#[derive(Clone, Debug)]
pub struct Service {
    /// "urn:schemas-upnp-org:service:AVTransport:1", "urn:av-openhome-org:service:Playlist:1", ...
    pub kind: String,
    pub control_url: String,
}

#[derive(Clone)]
pub struct Renderer {
    pub description_url: String,
    pub name: String,
    pub manufacturer: String,
    /// "uuid:...", for Sonos "uuid:RINCON_..."
    pub udn: String,
    pub host: String,
    services: Vec<Service>,
    /// The formats the renderer plays (ConnectionManager's sink protocols), if it told.
    sink: Option<String>,
}

impl Renderer {
    pub fn open(description_url: &str) -> io::Result<Renderer> {
        let xml = ureq::get(description_url)
            .timeout(Duration::from_secs(5))
            .call()
            .map_err(io::Error::other)?
            .into_string()?;
        let base = description_url.splitn(4, '/').take(3).collect::<Vec<_>>().join("/");
        let host = base.rsplit('/').next().unwrap_or("").rsplit_once(':').map_or("", |h| h.0).to_string();
        let absolute = |control: String| {
            if control.starts_with("http") { control } else { format!("{base}/{}", control.trim_start_matches('/')) }
        };
        // The services of the (sub)device with the AVTransport: a sibling device's services
        // (Sonos: the media server's ConnectionManager) are about something else.
        let parts: Vec<&str> = xml.split("<serviceList>").collect();
        for index in 1..parts.len() {
            let list = parts[index].split("</serviceList>").next().unwrap_or("");
            let service_of = |service: &str| Service {
                kind: tag(service, "serviceType").unwrap_or_default(),
                control_url: absolute(tag(service, "controlURL").unwrap_or_default()),
            };
            let mut services: Vec<Service> = list.split("<service>").skip(1).map(service_of).collect();
            if !services.iter().any(|s| s.kind.contains(":service:AVTransport:")) {
                continue;
            }
            // Sonos: the group topology belongs to the player around the renderer.
            if let Some(topology) = xml.split("<service>").skip(1).map(service_of)
                .find(|s| s.kind.contains(":service:ZoneGroupTopology:")) {
                services.push(topology);
            }
            // The device element this service list belongs to: its name and UDN come before it.
            let device = parts[index - 1].rsplit("<device>").next().unwrap_or("");
            let mut renderer = Renderer {
                description_url: description_url.to_string(),
                name: tag(device, "friendlyName").or_else(|| tag(&xml, "friendlyName")).unwrap_or_default(),
                manufacturer: tag(device, "manufacturer").or_else(|| tag(&xml, "manufacturer")).unwrap_or_default(),
                udn: tag(device, "UDN").or_else(|| tag(&xml, "UDN")).unwrap_or_default(),
                host,
                services,
                sink: None,
            };
            renderer.sink = renderer.call("ConnectionManager", "GetProtocolInfo", &[])
                .ok().and_then(|r| tag(&r, "Sink"));
            return Ok(renderer);
        }
        Err(io::Error::other(format!("{description_url} has no AVTransport service")))
    }

    pub fn service(&self, name: &str) -> Option<&Service> {
        let needle = format!(":service:{name}:");
        self.services.iter().find(|s| s.kind.contains(&needle))
    }

    pub fn has(&self, name: &str) -> bool {
        self.service(name).is_some()
    }

    /// Calls `action` of the service `name` ("AVTransport", "Playlist", ...). UPnP AV services
    /// (AVTransport, RenderingControl, GroupRenderingControl) get InstanceID 0 first.
    pub fn call(&self, name: &str, action: &str, args: &[(&str, &str)]) -> io::Result<String> {
        let service = self.service(name).ok_or_else(|| io::Error::other(format!("{} has no {name}", self.name)))?;
        let mut body = String::new();
        if matches!(name, "AVTransport" | "RenderingControl" | "GroupRenderingControl") {
            body.push_str("<InstanceID>0</InstanceID>");
        }
        for (key, value) in args {
            body.push_str(&format!("<{key}>{}</{key}>", escape(value)));
        }
        soap(&service.control_url, &service.kind, action, &body)
    }

    /// What this renderer can play of what we serve, best first for videos: "hls" (H.264 up to
    /// 1080p), "muxed" (MP4 360p), "audio" (M4A). All if the renderer does not tell.
    pub fn modes(&self) -> Vec<&'static str> {
        let Some(sink) = self.sink.as_deref().map(str::to_ascii_lowercase).filter(|s| !s.is_empty()) else {
            return vec!["hls", "muxed", "audio"];
        };
        let lists = |mime: &str| sink.contains(&format!(":{mime}:"));
        // Only explicit video types count: audio players (Sonos) list HLS too, for radio.
        let video = lists("video/mp4");
        let mut modes = Vec::new();
        if video && (lists("application/vnd.apple.mpegurl") || lists("application/x-mpegurl")) {
            modes.push("hls");
        }
        if video {
            modes.push("muxed");
        }
        if lists("audio/mp4") || lists("audio/x-m4a") {
            modes.push("audio");
        }
        modes
    }

    /// The mode for a video ("video") or a song ("music"); None if the renderer plays neither.
    pub fn mode_for(&self, kind: &str) -> Option<&'static str> {
        let modes = self.modes();
        if kind == "music" {
            // Songs as sound; a renderer without audio types gets the small video.
            return ["audio", "muxed", "hls"].into_iter().find(|m| modes.contains(m));
        }
        modes.first().copied()
    }

    // AVTransport

    pub fn load(&self, uri: &str, metadata: &str) -> io::Result<()> {
        // Some renderers refuse a new URI while playing.
        let _ = self.call("AVTransport", "Stop", &[]);
        self.call("AVTransport", "SetAVTransportURI", &[("CurrentURI", uri), ("CurrentURIMetaData", metadata)])?;
        self.play()
    }

    pub fn set_next(&self, uri: &str, metadata: &str) -> io::Result<()> {
        self.call("AVTransport", "SetNextAVTransportURI", &[("NextURI", uri), ("NextURIMetaData", metadata)]).map(|_| ())
    }

    pub fn play(&self) -> io::Result<()> {
        self.call("AVTransport", "Play", &[("Speed", "1")]).map(|_| ())
    }

    pub fn pause(&self) -> io::Result<()> {
        self.call("AVTransport", "Pause", &[]).map(|_| ())
    }

    pub fn stop(&self) -> io::Result<()> {
        self.call("AVTransport", "Stop", &[]).map(|_| ())
    }

    pub fn seek(&self, seconds: u64) -> io::Result<()> {
        self.call("AVTransport", "Seek", &[("Unit", "REL_TIME"), ("Target", &clock(seconds))]).map(|_| ())
    }

    /// Transport state ("PLAYING", "TRANSITIONING", "STOPPED", ...), position and duration in
    /// seconds, and the URI that plays.
    pub fn status(&self) -> io::Result<(String, u64, u64, String)> {
        let state = tag(&self.call("AVTransport", "GetTransportInfo", &[])?, "CurrentTransportState").unwrap_or_default();
        let info = self.call("AVTransport", "GetPositionInfo", &[])?;
        Ok((
            state,
            seconds(&tag(&info, "RelTime").unwrap_or_default()),
            seconds(&tag(&info, "TrackDuration").unwrap_or_default()),
            tag(&info, "TrackURI").unwrap_or_default(),
        ))
    }

    // Volume (0-100)

    pub fn volume(&self) -> io::Result<u32> {
        let r = self.call("RenderingControl", "GetVolume", &[("Channel", "Master")])?;
        Ok(tag(&r, "CurrentVolume").and_then(|v| v.parse().ok()).unwrap_or(0))
    }

    pub fn set_volume(&self, volume: u32) -> io::Result<()> {
        self.call("RenderingControl", "SetVolume", &[("Channel", "Master"), ("DesiredVolume", &volume.min(100).to_string())])
            .map(|_| ())
    }

    /// The address of this computer that the renderer reaches.
    pub fn local_address(&self) -> io::Result<IpAddr> {
        let socket = UdpSocket::bind("0.0.0.0:0")?;
        socket.connect((self.host.as_str(), 9))?;
        Ok(socket.local_addr()?.ip())
    }
}

/// One SOAP action; the error names the UPnP error code and description.
pub fn soap(control_url: &str, service_type: &str, action: &str, arguments: &str) -> io::Result<String> {
    let body = format!(
        "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" \
         s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:{action} xmlns:u=\"{service_type}\">\
         {arguments}</u:{action}></s:Body></s:Envelope>"
    );
    let response = ureq::post(control_url)
        .timeout(Duration::from_secs(10))
        .set("Content-Type", "text/xml; charset=\"utf-8\"")
        .set("SOAPACTION", &format!("\"{service_type}#{action}\""))
        .send_string(&body);
    match response {
        Ok(r) => Ok(r.into_string()?),
        Err(ureq::Error::Status(code, r)) => {
            let text = r.into_string().unwrap_or_default();
            Err(io::Error::other(format!(
                "{action}: HTTP {code} {} {}",
                tag(&text, "errorCode").unwrap_or_default(),
                tag(&text, "errorDescription").unwrap_or_default()
            )))
        }
        Err(e) => Err(io::Error::other(format!("{action}: {e}"))),
    }
}

/// Text of the first `<name>` element (any namespace prefix), unescaped.
pub fn tag(xml: &str, name: &str) -> Option<String> {
    let start = xml.find(&format!(":{name}>")).or_else(|| xml.find(&format!("<{name}>")))
        .or_else(|| xml.find(&format!("<{name} ")))?;
    let open_end = xml[start..].find('>')? + start + 1;
    let close = xml[open_end..].find("</")? + open_end;
    Some(unescape(&xml[open_end..close]))
}

pub fn escape(s: &str) -> String {
    s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;").replace('\'', "&apos;")
}

pub fn unescape(s: &str) -> String {
    s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}

/// Seconds of "H:MM:SS" (or "H:MM:SS.mmm"); 0 if it does not parse.
pub fn seconds(time: &str) -> u64 {
    time.split('.').next().unwrap_or("").split(':').try_fold(0u64, |total, part| {
        part.parse::<u64>().ok().map(|n| total * 60 + n)
    }).unwrap_or(0)
}

/// "H:MM:SS" of seconds.
pub fn clock(seconds: u64) -> String {
    format!("{}:{:02}:{:02}", seconds / 3600, seconds / 60 % 60, seconds % 60)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn renderer(sink: Option<&str>) -> Renderer {
        Renderer {
            description_url: String::new(), name: String::new(), manufacturer: String::new(), udn: String::new(),
            host: String::new(), services: Vec::new(), sink: sink.map(str::to_string),
        }
    }

    #[test]
    fn modes_from_sink_protocols() {
        let tv = renderer(Some("http-get:*:video/mp4:*,http-get:*:application/vnd.apple.mpegurl:*,http-get:*:audio/mp4:*"));
        assert_eq!(tv.modes(), ["hls", "muxed", "audio"]);
        assert_eq!(tv.mode_for("video"), Some("hls"));
        assert_eq!(tv.mode_for("music"), Some("audio"));
        let old_tv = renderer(Some("http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_BL_CIF15_AAC_520,http-get:*:audio/mpeg:*"));
        assert_eq!(old_tv.modes(), ["muxed"]);
        assert_eq!(old_tv.mode_for("music"), Some("muxed"));
        let sonos = renderer(Some("http-get:*:audio/mp4:*,http-get:*:application/x-mpegURL:*,x-rincon:*:*:*"));
        assert_eq!(sonos.modes(), ["audio"]);
        assert_eq!(sonos.mode_for("video"), Some("audio"));
        let picture_frame = renderer(Some("http-get:*:image/jpeg:*"));
        assert_eq!(picture_frame.mode_for("video"), None);
        assert_eq!(renderer(None).modes().len(), 3);
    }

    #[test]
    fn time_strings() {
        assert_eq!(seconds("1:02:03.500"), 3723);
        assert_eq!(seconds("NOT_IMPLEMENTED"), 0);
        assert_eq!(clock(3723), "1:02:03");
    }
}
