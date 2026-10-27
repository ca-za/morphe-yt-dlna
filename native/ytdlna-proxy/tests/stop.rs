//! Stopping closes the port and forgets what was registered; starting opens it again.

use std::io::{Read, Write};
use std::net::TcpStream;
use std::thread;
use std::time::Duration;

use ytdlna::{registry, server};

fn get(port: u16, path: &str) -> String {
    let mut conn = TcpStream::connect(("127.0.0.1", port)).unwrap();
    write!(conn, "GET {path} HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n").unwrap();
    let mut response = String::new();
    conn.read_to_string(&mut response).unwrap();
    response
}

#[test]
fn stop_closes_the_port_and_clears_the_registry() {
    let port = server::start(0).unwrap();
    registry::put_text("/v/x/manifest.mpd".into(), "application/dash+xml".into(), b"<MPD/>".to_vec());
    assert!(get(port, "/v/x/manifest.mpd").starts_with("HTTP/1.1 200"));

    server::stop();
    let closed = (0..20).any(|_| {
        thread::sleep(Duration::from_millis(50));
        TcpStream::connect(("127.0.0.1", port)).is_err()
    });
    assert!(closed, "port {port} still open after stop");

    let again = server::start(0).unwrap();
    assert!(get(again, "/v/x/manifest.mpd").starts_with("HTTP/1.1 404"), "registry not cleared");
    server::stop();
}
