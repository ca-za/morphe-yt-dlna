//! End-to-end test: renderer -> proxy -> fake upstream with Range support.

use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::Arc;
use std::thread;

use ytdlna::{registry, server, upstream};

const SIZE: usize = 20 * 1024 * 1024 + 123; // More than two chunks.

fn byte_at(i: usize) -> u8 {
    (i.wrapping_mul(31) ^ (i >> 7)) as u8
}

struct FakeUpstream {
    port: u16,
    requests: Arc<AtomicUsize>,
}

/// Serves SIZE bytes with Range support. Cuts the first ranged response off half way.
fn fake_upstream() -> FakeUpstream {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let requests = Arc::new(AtomicUsize::new(0));
    let cut_once = Arc::new(AtomicBool::new(true));
    let counter = requests.clone();
    thread::spawn(move || {
        for conn in listener.incoming() {
            let mut conn = conn.unwrap();
            let counter = counter.clone();
            let cut_once = cut_once.clone();
            thread::spawn(move || {
                let mut reader = BufReader::new(conn.try_clone().unwrap());
                loop {
                    let mut line = String::new();
                    if reader.read_line(&mut line).unwrap_or(0) == 0 {
                        return;
                    }
                    let mut range = None;
                    loop {
                        let mut h = String::new();
                        reader.read_line(&mut h).unwrap();
                        let h = h.trim_end().to_string();
                        if h.is_empty() {
                            break;
                        }
                        if let Some(v) = h.strip_prefix("Range: bytes=") {
                            let (a, b) = v.split_once('-').unwrap();
                            range = Some((a.parse::<usize>().unwrap(), b.parse::<usize>().unwrap()));
                        }
                    }
                    counter.fetch_add(1, Ordering::SeqCst);
                    let (status, start, end) = match range {
                        Some((a, b)) => (206, a, b.min(SIZE - 1)),
                        None => (200, 0, SIZE - 1),
                    };
                    let len = end - start + 1;
                    let mut head = format!("HTTP/1.1 {status} X\r\nContent-Length: {len}\r\n");
                    if status == 206 {
                        head.push_str(&format!("Content-Range: bytes {start}-{end}/{SIZE}\r\n"));
                    }
                    head.push_str("\r\n");
                    conn.write_all(head.as_bytes()).unwrap();
                    let body: Vec<u8> = (start..=end).map(byte_at).collect();
                    if status == 206 && start > 0 && cut_once.swap(false, Ordering::SeqCst) {
                        let _ = conn.write_all(&body[..len / 2]);
                        return; // Drop the connection mid-body.
                    }
                    if conn.write_all(&body).is_err() {
                        return;
                    }
                }
            });
        }
    });
    FakeUpstream { port, requests }
}

/// Sends one request on `conn` and reads the response. Returns (status, headers, body).
fn request(conn: &mut BufReader<TcpStream>, path: &str, range: Option<&str>) -> (u16, Vec<String>, Vec<u8>) {
    let mut req = format!("GET {path} HTTP/1.1\r\nHost: test\r\n");
    if let Some(r) = range {
        req.push_str(&format!("Range: {r}\r\n"));
    }
    req.push_str("\r\n");
    conn.get_mut().write_all(req.as_bytes()).unwrap();

    let mut line = String::new();
    conn.read_line(&mut line).unwrap();
    let status: u16 = line.split_whitespace().nth(1).unwrap().parse().unwrap();
    let mut headers = Vec::new();
    let mut length = 0usize;
    loop {
        let mut h = String::new();
        conn.read_line(&mut h).unwrap();
        let h = h.trim_end().to_string();
        if h.is_empty() {
            break;
        }
        if let Some(v) = h.strip_prefix("Content-Length: ") {
            length = v.parse().unwrap();
        }
        headers.push(h);
    }
    let mut body = vec![0u8; length];
    conn.read_exact(&mut body).unwrap();
    (status, headers, body)
}

#[test]
fn proxy_end_to_end() {
    let upstream_server = fake_upstream();
    upstream::set_user_agent("test".into());
    let url = format!("http://127.0.0.1:{}/videoplayback", upstream_server.port);
    registry::put_stream("/v/test/1.mp4".into(), "video/mp4".into(), url.clone(), SIZE as u64);
    registry::put_stream("/v/test/unknown.mp4".into(), "video/mp4".into(), url, 0);
    registry::put_text("/v/test/manifest.mpd".into(), "application/dash+xml".into(), b"<MPD/>".to_vec());

    let port = server::start(0).unwrap();
    let mut conn = BufReader::new(TcpStream::connect(("127.0.0.1", port)).unwrap());

    // Text entry.
    let (status, _, body) = request(&mut conn, "/v/test/manifest.mpd", None);
    assert_eq!(status, 200);
    assert_eq!(body, b"<MPD/>");

    // Full download over several chunks, with one upstream cut-off to retry.
    let (status, _, body) = request(&mut conn, "/v/test/1.mp4", None);
    assert_eq!(status, 200);
    assert_eq!(body.len(), SIZE);
    assert!(body.iter().enumerate().all(|(i, b)| *b == byte_at(i)), "full body differs");

    // Ranged request on the same keep-alive connection.
    let (status, headers, body) = request(&mut conn, "/v/test/1.mp4", Some("bytes=1000-1999"));
    assert_eq!(status, 206);
    assert!(headers.iter().any(|h| h == &format!("Content-Range: bytes 1000-1999/{SIZE}")));
    assert_eq!(body, (1000..2000).map(byte_at).collect::<Vec<_>>());

    // Unsatisfiable range.
    let (status, _, _) = request(&mut conn, "/v/test/1.mp4", Some(&format!("bytes={SIZE}-")));
    assert_eq!(status, 416);

    // Unknown length: passed through, then the length is learned.
    let (status, headers, body) = request(&mut conn, "/v/test/unknown.mp4", Some("bytes=10-19"));
    assert_eq!(status, 206);
    assert!(headers.iter().any(|h| h == &format!("Content-Range: bytes 10-19/{SIZE}")));
    assert_eq!(body, (10..20).map(byte_at).collect::<Vec<_>>());

    // Not found.
    let (status, _, _) = request(&mut conn, "/v/test/missing.mp4", None);
    assert_eq!(status, 404);

    // 3 chunks + 1 retry + 1 ranged + 1 passthrough.
    assert!(upstream_server.requests.load(Ordering::SeqCst) >= 6);
}
