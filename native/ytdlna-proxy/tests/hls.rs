//! End-to-end test: the proxy builds an HLS playlist from a stream's segment index.

use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::thread;

use ytdlna::{registry, server};

/// A version 0 sidx box with (size, duration) references, timescale 1000.
fn sidx(refs: &[(u32, u32)]) -> Vec<u8> {
    let mut body = vec![0u8, 0, 0, 0];
    body.extend(1u32.to_be_bytes());
    body.extend(1000u32.to_be_bytes());
    body.extend(0u32.to_be_bytes());
    body.extend(0u32.to_be_bytes());
    body.extend(0u16.to_be_bytes());
    body.extend((refs.len() as u16).to_be_bytes());
    for (size, duration) in refs {
        body.extend(size.to_be_bytes());
        body.extend(duration.to_be_bytes());
        body.extend(0x9000_0000u32.to_be_bytes());
    }
    let mut data = ((body.len() + 8) as u32).to_be_bytes().to_vec();
    data.extend(b"sidx");
    data.extend(body);
    data
}

/// Serves `file` with Range support, one request per connection.
fn upstream(file: Vec<u8>) -> u16 {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    thread::spawn(move || {
        for conn in listener.incoming() {
            let mut conn = conn.unwrap();
            let mut reader = BufReader::new(conn.try_clone().unwrap());
            let mut range = (0, file.len() - 1);
            loop {
                let mut line = String::new();
                reader.read_line(&mut line).unwrap();
                let line = line.trim_end();
                if line.is_empty() {
                    break;
                }
                if let Some(v) = line.strip_prefix("Range: bytes=") {
                    let (a, b) = v.split_once('-').unwrap();
                    range = (a.parse().unwrap(), b.parse::<usize>().unwrap().min(file.len() - 1));
                }
            }
            let body = &file[range.0..=range.1];
            write!(conn, "HTTP/1.1 206 X\r\nContent-Length: {}\r\nContent-Range: bytes {}-{}/{}\r\nConnection: close\r\n\r\n",
                   body.len(), range.0, range.1, file.len()).unwrap();
            conn.write_all(body).unwrap();
        }
    });
    port
}

#[test]
fn serves_playlist_built_from_the_segment_index() {
    let index = sidx(&[(3000, 5000), (2000, 4000)]);
    let mut file = vec![7u8; 100]; // init segment
    file.extend(&index);
    file.extend(vec![1u8; 5000]);
    let index_end = 100 + index.len() as u64 - 1;
    let upstream_port = upstream(file);
    let proxy_port = server::start(0).unwrap();
    registry::put_hls("/v/x/3.m3u8".into(), "3.mp4".into(),
                      format!("http://127.0.0.1:{upstream_port}/videoplayback"), 99, 100, index_end);

    let mut conn = TcpStream::connect(("127.0.0.1", proxy_port)).unwrap();
    write!(conn, "GET /v/x/3.m3u8 HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n").unwrap();
    let mut response = String::new();
    conn.read_to_string(&mut response).unwrap();
    assert!(response.starts_with("HTTP/1.1 200"), "{response}");
    assert!(response.contains("application/vnd.apple.mpegurl"));
    let first = index_end + 1;
    assert!(response.contains("#EXT-X-MAP:URI=\"3.mp4\",BYTERANGE=\"100@0\""), "{response}");
    assert!(response.contains(&format!("#EXTINF:5.000,\n#EXT-X-BYTERANGE:3000@{first}\n3.mp4\n")), "{response}");
    assert!(response.contains(&format!("#EXTINF:4.000,\n#EXT-X-BYTERANGE:2000@{}\n3.mp4\n", first + 3000)));
    assert!(response.contains("#EXT-X-ENDLIST"));
}
