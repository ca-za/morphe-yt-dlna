//! Finding renderers (SSDP) and driving them over AVTransport.

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

#[derive(Clone)]
pub struct Renderer {
    pub name: String,
    pub host: String,
    control_url: String,
    service: String,
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
        let name = tag(&xml, "friendlyName").unwrap_or_default();
        let base = description_url.splitn(4, '/').take(3).collect::<Vec<_>>().join("/");
        let host = base.rsplit('/').next().unwrap_or("").rsplit_once(':').map_or("", |h| h.0).to_string();
        let absolute = |control: String| {
            if control.starts_with("http") { control } else { format!("{base}/{}", control.trim_start_matches('/')) }
        };
        // The services of the device with the AVTransport: a sibling device's ConnectionManager
        // (Sonos: the media server) lists other formats.
        for list in xml.split("<serviceList>").skip(1) {
            let list = list.split("</serviceList>").next().unwrap_or("");
            let mut transport = None;
            let mut connection_manager = None;
            for service in list.split("<service>").skip(1) {
                let kind = tag(service, "serviceType").unwrap_or_default();
                let control = absolute(tag(service, "controlURL").unwrap_or_default());
                if kind.starts_with("urn:schemas-upnp-org:service:AVTransport:") {
                    transport = Some((kind, control));
                } else if kind.starts_with("urn:schemas-upnp-org:service:ConnectionManager:") {
                    connection_manager = Some((kind, control));
                }
            }
            let Some((service, control_url)) = transport else { continue };
            let sink = connection_manager.and_then(|(kind, url)| sink_protocols(&kind, &url).ok());
            return Ok(Renderer { name, host, control_url, service, sink });
        }
        Err(io::Error::other(format!("{name} has no AVTransport service")))
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
            for mode in ["audio", "muxed", "hls"] {
                if modes.contains(&mode) {
                    return Some(mode);
                }
            }
            return None;
        }
        modes.first().copied()
    }

    fn call(&self, action: &str, args: &[(&str, &str)]) -> io::Result<String> {
        let mut body = format!(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" \
             s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:{action} xmlns:u=\"{}\"><InstanceID>0</InstanceID>",
            self.service
        );
        for (key, value) in args {
            body.push_str(&format!("<{key}>{}</{key}>", escape(value)));
        }
        body.push_str(&format!("</u:{action}></s:Body></s:Envelope>"));
        let response = ureq::post(&self.control_url)
            .timeout(Duration::from_secs(10))
            .set("Content-Type", "text/xml; charset=\"utf-8\"")
            .set("SOAPACTION", &format!("\"{}#{action}\"", self.service))
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

    pub fn load(&self, uri: &str, metadata: &str) -> io::Result<()> {
        // Some renderers refuse a new URI while playing.
        let _ = self.call("Stop", &[]);
        self.call("SetAVTransportURI", &[("CurrentURI", uri), ("CurrentURIMetaData", metadata)])?;
        self.call("Play", &[("Speed", "1")]).map(|_| ())
    }

    pub fn play(&self) -> io::Result<()> {
        self.call("Play", &[("Speed", "1")]).map(|_| ())
    }

    pub fn pause(&self) -> io::Result<()> {
        self.call("Pause", &[]).map(|_| ())
    }

    pub fn stop(&self) -> io::Result<()> {
        self.call("Stop", &[]).map(|_| ())
    }

    pub fn seek(&self, seconds: u64) -> io::Result<()> {
        let target = format!("{}:{:02}:{:02}", seconds / 3600, seconds / 60 % 60, seconds % 60);
        self.call("Seek", &[("Unit", "REL_TIME"), ("Target", &target)]).map(|_| ())
    }

    /// Transport state ("PLAYING", "TRANSITIONING", "STOPPED", ...) and position "H:MM:SS".
    pub fn status(&self) -> io::Result<(String, String)> {
        let state = tag(&self.call("GetTransportInfo", &[])?, "CurrentTransportState").unwrap_or_default();
        let position = tag(&self.call("GetPositionInfo", &[])?, "RelTime").unwrap_or_default();
        Ok((state, position))
    }

    /// The address of this computer that the renderer reaches.
    pub fn local_address(&self) -> io::Result<IpAddr> {
        let socket = UdpSocket::bind("0.0.0.0:0")?;
        socket.connect((self.host.as_str(), 9))?;
        Ok(socket.local_addr()?.ip())
    }
}

/// GetProtocolInfo: the renderer's sink protocols ("http-get:*:video/mp4:*,...").
fn sink_protocols(service: &str, control_url: &str) -> io::Result<String> {
    let body = format!(
        "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" \
         s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:GetProtocolInfo xmlns:u=\"{service}\"/></s:Body></s:Envelope>"
    );
    let response = ureq::post(control_url)
        .timeout(Duration::from_secs(5))
        .set("Content-Type", "text/xml; charset=\"utf-8\"")
        .set("SOAPACTION", &format!("\"{service}#GetProtocolInfo\""))
        .send_string(&body)
        .map_err(io::Error::other)?
        .into_string()?;
    Ok(tag(&response, "Sink").unwrap_or_default())
}

/// Text of the first `<name>` element (any namespace prefix).
fn tag(xml: &str, name: &str) -> Option<String> {
    let start = xml.find(&format!(":{name}>")).or_else(|| xml.find(&format!("<{name}>")))?;
    let open_end = xml[start..].find('>')? + start + 1;
    let close = xml[open_end..].find("</")? + open_end;
    Some(unescape(&xml[open_end..close]))
}

pub fn escape(s: &str) -> String {
    s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;").replace('\'', "&apos;")
}

fn unescape(s: &str) -> String {
    s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}

/// Seconds of "H:MM:SS" (or "H:MM:SS.mmm"); 0 if it does not parse.
pub fn seconds(time: &str) -> u64 {
    time.split('.').next().unwrap_or("").split(':').try_fold(0u64, |total, part| {
        part.parse::<u64>().ok().map(|n| total * 60 + n)
    }).unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn renderer(sink: Option<&str>) -> Renderer {
        Renderer { name: String::new(), host: String::new(), control_url: String::new(), service: String::new(),
                   sink: sink.map(str::to_string) }
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
}
