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
    socket.send_to(search.as_bytes(), SSDP)?;
    let mut found = BTreeSet::new();
    let mut buffer = [0u8; 4096];
    let end = Instant::now() + wait;
    while Instant::now() < end {
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

pub struct Renderer {
    pub name: String,
    pub host: String,
    control_url: String,
    service: String,
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
        for service in xml.split("<service>").skip(1) {
            let kind = tag(service, "serviceType").unwrap_or_default();
            if !kind.starts_with("urn:schemas-upnp-org:service:AVTransport:") {
                continue;
            }
            let control = tag(service, "controlURL").unwrap_or_default();
            let control_url = if control.starts_with("http") {
                control
            } else {
                format!("{base}/{}", control.trim_start_matches('/'))
            };
            return Ok(Renderer { name, host, control_url, service: kind });
        }
        Err(io::Error::other(format!("{name} has no AVTransport service")))
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
