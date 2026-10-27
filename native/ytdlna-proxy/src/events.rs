//! UPnP eventing (GENA): subscribes to renderer services and receives their NOTIFY messages.
//!
//! The renderer sends state changes (transport state, track, position, OpenHome playlist ids)
//! to the callback URL on this proxy. Events are queued and picked up by the app.

use std::collections::{HashMap, VecDeque};
use std::io;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Condvar, Mutex, OnceLock};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Event {
    /// Subscription the event belongs to.
    pub token: String,
    pub name: String,
    pub value: String,
}

struct Subscription {
    event_url: String,
    sid: String,
}

const MAX_QUEUED: usize = 1000;
/// Short, so that a subscription of a killed app (never unsubscribed) is dropped by the
/// renderer soon: renderers that notify subscribers one by one are slowed down by dead ones.
const REQUESTED_TIMEOUT_SECONDS: u64 = 300;

fn subscriptions() -> &'static Mutex<HashMap<String, Subscription>> {
    static SUBS: OnceLock<Mutex<HashMap<String, Subscription>>> = OnceLock::new();
    SUBS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn queue() -> &'static (Mutex<VecDeque<Event>>, Condvar) {
    static QUEUE: OnceLock<(Mutex<VecDeque<Event>>, Condvar)> = OnceLock::new();
    QUEUE.get_or_init(|| (Mutex::new(VecDeque::new()), Condvar::new()))
}

fn new_token() -> String {
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let nanos = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0);
    format!("{:x}{:x}", nanos as u64, COUNTER.fetch_add(1, Ordering::Relaxed))
}

fn agent() -> &'static ureq::Agent {
    static AGENT: OnceLock<ureq::Agent> = OnceLock::new();
    AGENT.get_or_init(|| {
        ureq::AgentBuilder::new()
            .timeout(Duration::from_secs(5))
            .build()
    })
}

/// Subscribes to `event_url`. Events are delivered to `{callback_base}/evt/{token}`.
/// Returns the token that identifies the subscription.
pub fn subscribe(event_url: &str, callback_base: &str) -> io::Result<String> {
    let token = new_token();
    let callback = format!("<{}/evt/{}>", callback_base.trim_end_matches('/'), token);
    // Known before SUBSCRIBE is sent: renderers send the initial event right away, often before
    // the SUBSCRIBE response arrives, and drop the subscription if that event is refused.
    subscriptions()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .insert(token.clone(), Subscription { event_url: event_url.to_string(), sid: String::new() });
    let response = agent()
        .request("SUBSCRIBE", event_url)
        .set("CALLBACK", &callback)
        .set("NT", "upnp:event")
        .set("TIMEOUT", &format!("Second-{REQUESTED_TIMEOUT_SECONDS}"))
        .call();
    let response = match response {
        Ok(response) if response.header("SID").is_some() => response,
        other => {
            subscriptions().lock().unwrap_or_else(|e| e.into_inner()).remove(&token);
            let message = match other {
                Ok(_) => "SUBSCRIBE response without SID".to_string(),
                Err(e) => format!("SUBSCRIBE failed: {e}"),
            };
            return Err(io::Error::new(io::ErrorKind::Other, message));
        }
    };
    let sid = response.header("SID").unwrap_or_default().to_string();
    let timeout = parse_timeout(response.header("TIMEOUT"));
    crate::info!("Subscribed to {event_url} ({sid}, {timeout}s)");
    if let Some(sub) = subscriptions().lock().unwrap_or_else(|e| e.into_inner()).get_mut(&token) {
        sub.sid = sid;
    }
    spawn_renewal(token.clone(), timeout);
    Ok(token)
}

pub fn unsubscribe(token: &str) {
    let removed = subscriptions().lock().unwrap_or_else(|e| e.into_inner()).remove(token);
    if let Some(sub) = removed {
        let _ = agent().request("UNSUBSCRIBE", &sub.event_url).set("SID", &sub.sid).call();
        crate::debug!("Unsubscribed {}", sub.sid);
    }
}

fn parse_timeout(header: Option<&str>) -> u64 {
    header
        .and_then(|h| h.trim().strip_prefix("Second-"))
        .and_then(|s| s.parse().ok())
        .unwrap_or(REQUESTED_TIMEOUT_SECONDS)
}

/// Renews the subscription shortly before it expires, until it is removed.
fn spawn_renewal(token: String, timeout: u64) {
    let _ = thread::Builder::new().name("ytdlna-gena".into()).spawn(move || {
        let mut timeout = timeout;
        loop {
            let wait = timeout.saturating_sub(60).max(30);
            thread::sleep(Duration::from_secs(wait));
            let (url, sid) = {
                let subs = subscriptions().lock().unwrap_or_else(|e| e.into_inner());
                match subs.get(&token) {
                    Some(sub) => (sub.event_url.clone(), sub.sid.clone()),
                    None => return,
                }
            };
            match agent()
                .request("SUBSCRIBE", &url)
                .set("SID", &sid)
                .set("TIMEOUT", &format!("Second-{REQUESTED_TIMEOUT_SECONDS}"))
                .call()
            {
                Ok(response) => timeout = parse_timeout(response.header("TIMEOUT")),
                Err(e) => {
                    crate::error!("Renewing {sid} failed: {e}");
                    push(Event { token: token.clone(), name: "SubscriptionLost".into(), value: sid });
                    subscriptions().lock().unwrap_or_else(|e| e.into_inner()).remove(&token);
                    return;
                }
            }
        }
    });
}

/// Handles a NOTIFY body sent to `/evt/{token}`. Returns false for unknown subscriptions.
pub fn handle_notify(token: &str, body: &str) -> bool {
    if !subscriptions().lock().unwrap_or_else(|e| e.into_inner()).contains_key(token) {
        return false;
    }
    for (name, value) in parse_propertyset(body) {
        crate::debug!("Event {name} = {}", truncate(&value, 120));
        push(Event { token: token.to_string(), name, value });
    }
    true
}

fn truncate(s: &str, max: usize) -> &str {
    match s.char_indices().nth(max) {
        Some((i, _)) => &s[..i],
        None => s,
    }
}

/// Queues an event from the proxy itself (empty token), such as "NeedVideo".
pub fn push_local(name: &str, value: &str) {
    push(Event { token: String::new(), name: name.to_string(), value: value.to_string() });
}

fn push(event: Event) {
    let (lock, condvar) = queue();
    let mut queue = lock.lock().unwrap_or_else(|e| e.into_inner());
    if queue.len() >= MAX_QUEUED {
        queue.pop_front();
    }
    queue.push_back(event);
    condvar.notify_all();
}

/// Waits up to `timeout` for the next event.
pub fn wait(timeout: Duration) -> Option<Event> {
    let (lock, condvar) = queue();
    let queue = lock.lock().unwrap_or_else(|e| e.into_inner());
    let (mut queue, _) = condvar
        .wait_timeout_while(queue, timeout, |q| q.is_empty())
        .unwrap_or_else(|e| e.into_inner());
    queue.pop_front()
}

/// Flattens a GENA property set into (name, value) pairs.
///
/// AVTransport and RenderingControl send a single `LastChange` property whose value is an
/// escaped XML document with `<Name val="..."/>` elements; those are expanded. OpenHome
/// services send one property per state variable.
pub fn parse_propertyset(body: &str) -> Vec<(String, String)> {
    let mut result = Vec::new();
    for element in leaf_elements(body) {
        if element.name == "LastChange" {
            let inner = unescape(&element.text);
            for change in leaf_elements(&inner) {
                if let Some(value) = change.val {
                    result.push((change.name, unescape(&value)));
                }
            }
        } else {
            result.push((element.name, unescape(&element.text)));
        }
    }
    result
}

struct Element {
    name: String,
    val: Option<String>,
    text: String,
}

/// Elements without child elements, in document order. Namespace prefixes are dropped.
/// Only as much XML as UPnP events need: no CDATA, comments are skipped.
fn leaf_elements(xml: &str) -> Vec<Element> {
    let mut elements = Vec::new();
    let mut rest = xml;
    while let Some(start) = rest.find('<') {
        rest = &rest[start + 1..];
        if rest.starts_with('/') || rest.starts_with('?') || rest.starts_with('!') {
            continue;
        }
        let Some(end) = rest.find('>') else { break };
        let tag = &rest[..end];
        let self_closing = tag.ends_with('/');
        let tag = tag.trim_end_matches('/');
        let name_end = tag.find(|c: char| c.is_whitespace()).unwrap_or(tag.len());
        let qualified = &tag[..name_end];
        let name = qualified.rsplit(':').next().unwrap_or(qualified).to_string();
        let val = attribute(&tag[name_end..], "val");
        rest = &rest[end + 1..];
        if self_closing {
            elements.push(Element { name, val, text: String::new() });
            continue;
        }
        // A leaf has its closing tag before any other element.
        let next = rest.find('<').unwrap_or(rest.len());
        if rest[next..].starts_with("</") {
            let text = rest[..next].to_string();
            elements.push(Element { name, val, text });
        }
    }
    elements
}

fn attribute(attributes: &str, name: &str) -> Option<String> {
    let mut rest = attributes;
    while let Some(position) = rest.find(name) {
        let before_ok = position == 0
            || rest[..position].ends_with(|c: char| c.is_whitespace() || c == ':');
        let after = rest[position + name.len()..].trim_start();
        if before_ok {
            if let Some(after) = after.strip_prefix('=') {
                let after = after.trim_start();
                let quote = after.chars().next()?;
                if quote == '"' || quote == '\'' {
                    let value = &after[1..];
                    return value.find(quote).map(|end| value[..end].to_string());
                }
            }
        }
        rest = &rest[position + name.len()..];
    }
    None
}

pub fn unescape(s: &str) -> String {
    s.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn av_transport_last_change() {
        let body = r#"<?xml version="1.0"?>
<e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0"><e:property><LastChange>&lt;Event xmlns=&quot;urn:schemas-upnp-org:metadata-1-0/AVT/&quot;&gt;&lt;InstanceID val=&quot;0&quot;&gt;&lt;TransportState val=&quot;PAUSED_PLAYBACK&quot;/&gt;&lt;CurrentTrackURI val=&quot;http://a/b?x=1&amp;amp;y=2&quot;/&gt;&lt;/InstanceID&gt;&lt;/Event&gt;</LastChange></e:property></e:propertyset>"#;
        let events = parse_propertyset(body);
        assert!(events.contains(&("TransportState".into(), "PAUSED_PLAYBACK".into())));
        assert!(events.contains(&("CurrentTrackURI".into(), "http://a/b?x=1&y=2".into())));
    }

    #[test]
    fn openhome_properties() {
        let body = r#"<e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0"><e:property><TransportState>Playing</TransportState></e:property><e:property><Id>3</Id></e:property></e:propertyset>"#;
        let events = parse_propertyset(body);
        assert_eq!(
            events,
            vec![("TransportState".into(), "Playing".into()), ("Id".into(), "3".into())]
        );
    }
}
