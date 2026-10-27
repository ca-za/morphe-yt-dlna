//! GENA round trip: subscribe at a fake renderer, receive its NOTIFY through the proxy.

use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::mpsc;
use std::thread;
use std::time::Duration;

use ytdlna::{events, server};

#[test]
fn subscribe_and_receive_notify() {
    let proxy_port = server::start(0).unwrap();

    // Fake renderer: answers SUBSCRIBE and reports the callback URL.
    let renderer = TcpListener::bind("127.0.0.1:0").unwrap();
    let renderer_port = renderer.local_addr().unwrap().port();
    let (callback_tx, callback_rx) = mpsc::channel::<String>();
    thread::spawn(move || {
        let (mut conn, _) = renderer.accept().unwrap();
        let mut reader = BufReader::new(conn.try_clone().unwrap());
        let mut callback = String::new();
        loop {
            let mut line = String::new();
            reader.read_line(&mut line).unwrap();
            let line = line.trim_end();
            if line.is_empty() {
                break;
            }
            if let Some(v) = line.strip_prefix("CALLBACK: ").or_else(|| line.strip_prefix("callback: ")) {
                callback = v.trim_matches(|c| c == '<' || c == '>').to_string();
            }
        }
        conn.write_all(b"HTTP/1.1 200 OK\r\nSID: uuid:test-sid\r\nTIMEOUT: Second-1800\r\nContent-Length: 0\r\n\r\n").unwrap();
        callback_tx.send(callback).unwrap();
    });

    let token = events::subscribe(
        &format!("http://127.0.0.1:{renderer_port}/AVTransport/event"),
        &format!("http://127.0.0.1:{proxy_port}"),
    )
    .unwrap();
    let callback = callback_rx.recv_timeout(Duration::from_secs(5)).unwrap();
    assert!(callback.ends_with(&format!("/evt/{token}")), "callback {callback}");

    // The renderer notifies a state change.
    let body = "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\"><e:property><LastChange>&lt;Event&gt;&lt;InstanceID val=&quot;0&quot;&gt;&lt;TransportState val=&quot;PLAYING&quot;/&gt;&lt;/InstanceID&gt;&lt;/Event&gt;</LastChange></e:property></e:propertyset>";
    let path = callback.split_once(&format!("127.0.0.1:{proxy_port}")).unwrap().1;
    let mut conn = TcpStream::connect(("127.0.0.1", proxy_port)).unwrap();
    write!(
        conn,
        "NOTIFY {path} HTTP/1.1\r\nHost: x\r\nNT: upnp:event\r\nNTS: upnp:propchange\r\nSID: uuid:test-sid\r\nSEQ: 0\r\nContent-Type: text/xml\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
        body.len()
    )
    .unwrap();
    let mut response = String::new();
    conn.read_to_string(&mut response).unwrap();
    assert!(response.starts_with("HTTP/1.1 200"), "{response}");

    let event = events::wait(Duration::from_secs(2)).expect("event");
    assert_eq!(event.token, token);
    assert_eq!(event.name, "TransportState");
    assert_eq!(event.value, "PLAYING");
}
