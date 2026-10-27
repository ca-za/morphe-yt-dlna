//! HTTP server the renderer streams from.

use std::io::{self, BufReader, BufWriter, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::OnceLock;
use std::thread;
use std::time::Duration;

use crate::events;
use crate::hls;
use crate::live;
use crate::http::{self, RangeResult, Request};
use crate::registry::{self, Entry, Stream};
use crate::upstream;

static PORT: OnceLock<u16> = OnceLock::new();

/// Starts the server once. Tries `preferred_port` first, then any free port.
pub fn start(preferred_port: u16) -> io::Result<u16> {
    if let Some(port) = PORT.get() {
        return Ok(*port);
    }
    let listener = TcpListener::bind(("0.0.0.0", preferred_port))
        .or_else(|_| TcpListener::bind(("0.0.0.0", 0)))?;
    let port = listener.local_addr()?.port();
    if PORT.set(port).is_err() {
        // Lost a race with another start call; use the winner.
        return Ok(*PORT.get().unwrap());
    }
    thread::Builder::new()
        .name("ytdlna-accept".into())
        .spawn(move || accept_loop(listener))?;
    crate::info!("Native stream proxy listening on port {port}");
    Ok(port)
}

fn accept_loop(listener: TcpListener) {
    for connection in listener.incoming() {
        match connection {
            Ok(stream) => {
                let _ = thread::Builder::new()
                    .name("ytdlna-conn".into())
                    .spawn(move || {
                        let peer = stream
                            .peer_addr()
                            .map(|a| a.ip().to_string())
                            .unwrap_or_default();
                        if let Err(e) = handle_connection(stream, &peer) {
                            // Renderers often close connections while seeking.
                            crate::debug!("Connection from {peer} ended: {e}");
                        }
                    });
            }
            Err(e) => crate::error!("Accept failed: {e}"),
        }
    }
}

fn handle_connection(stream: TcpStream, peer: &str) -> io::Result<()> {
    stream.set_nodelay(true)?;
    // Idle keep-alive connections are closed after a while.
    stream.set_read_timeout(Some(Duration::from_secs(60)))?;
    stream.set_write_timeout(Some(Duration::from_secs(60)))?;
    let mut reader = BufReader::new(stream.try_clone()?);
    let mut writer = BufWriter::with_capacity(64 * 1024, stream);

    while let Some(request) = http::read_request(&mut reader)? {
        if request.method == "NOTIFY" {
            // Renderer event (GENA). Bodies are small; refuse anything unreasonable.
            if request.content_length > 1024 * 1024 {
                return Ok(());
            }
            let mut body = vec![0u8; request.content_length];
            reader.read_exact(&mut body)?;
            let token = request.path.strip_prefix("/evt/").unwrap_or("");
            let known = events::handle_notify(token, &String::from_utf8_lossy(&body));
            http::write_head(&mut writer, if known { 200 } else { 412 }, &[("Content-Length", "0".into())], request.keep_alive)?;
            writer.flush()?;
            if !request.keep_alive {
                break;
            }
            continue;
        }
        crate::debug!(
            "Proxy {peer}: {} {}{}",
            request.method,
            request.path,
            request.range.as_deref().map(|r| format!(" range={r}")).unwrap_or_default()
        );
        let keep_alive = respond(&request, &mut writer)?;
        writer.flush()?;
        if !keep_alive {
            break;
        }
    }
    Ok(())
}

/// Answers one request. Returns whether the connection can be kept open.
fn respond<W: Write>(request: &Request, out: &mut W) -> io::Result<bool> {
    let head_only = match request.method.as_str() {
        "GET" => false,
        "HEAD" => true,
        _ => {
            http::write_head(out, 405, &[("Content-Length", "0".into())], false)?;
            return Ok(false);
        }
    };
    // Files of a live track: the entry is registered for their directory.
    if let Some((dir, leaf)) = request.path.rsplit_once('/') {
        if let Some(entry) = registry::get(dir) {
            if let Entry::LiveTrack(track) = entry.as_ref() {
                return serve_live(track, leaf, request, head_only, out);
            }
        }
    }
    let entry = match registry::get(&request.path) {
        Some(entry) => Some(entry),
        None => wait_for_lazy_entry(&request.path),
    };
    let Some(entry) = entry else {
        http::write_head(out, 404, &[("Content-Length", "0".into())], request.keep_alive)?;
        return Ok(request.keep_alive);
    };
    match entry.as_ref() {
        Entry::Text { mime, body } => write_text(out, mime, body, request, head_only),
        Entry::Stream(stream) => serve_stream(stream, request, head_only, out),
        Entry::LiveTrack(_) => {
            http::write_head(out, 404, &[("Content-Length", "0".into())], request.keep_alive)?;
            Ok(request.keep_alive)
        }
        Entry::Hls(media) => match hls_playlist(media) {
            Ok(body) => write_text(out, "application/vnd.apple.mpegurl", &body, request, head_only),
            Err(e) => {
                crate::info!("HLS playlist for {} failed: {e}", request.path);
                http::write_head(out, 502, &[("Content-Length", "0".into())], false)?;
                Ok(false)
            }
        },
    }
}

/// The media playlist, init section or a segment of a live track.
fn serve_live<W: Write>(
    track: &registry::LiveTrack,
    leaf: &str,
    request: &Request,
    head_only: bool,
    out: &mut W,
) -> io::Result<bool> {
    let result = match leaf {
        "index.m3u8" => live::head(&track.url).map(|head| {
            *track.init.lock().unwrap_or_else(|e| e.into_inner()) = Some(std::sync::Arc::new(head.init.clone()));
            ("application/vnd.apple.mpegurl", live::media_playlist(&head).into_bytes())
        }),
        "init.mp4" => {
            let cached = track.init.lock().unwrap_or_else(|e| e.into_inner()).clone();
            match cached {
                Some(init) => Ok((track.mime.as_str(), init.to_vec())),
                None => live::head(&track.url).map(|head| (track.mime.as_str(), head.init)),
            }
        }
        _ => match leaf.strip_suffix(".m4s").and_then(|n| n.parse::<u64>().ok()) {
            Some(sequence) => live::segment(&track.url, sequence).map(|data| (track.mime.as_str(), data)),
            None => {
                http::write_head(out, 404, &[("Content-Length", "0".into())], request.keep_alive)?;
                return Ok(request.keep_alive);
            }
        },
    };
    match result {
        Ok((mime, body)) => {
            http::write_head(out, 200, &[("Content-Type", mime.to_string()), ("Content-Length", body.len().to_string())],
                             request.keep_alive)?;
            if !head_only {
                out.write_all(&body)?;
            }
            Ok(request.keep_alive)
        }
        Err(e) => {
            crate::info!("Live {leaf} failed: {e}");
            http::write_head(out, 502, &[("Content-Length", "0".into())], false)?;
            Ok(false)
        }
    }
}

fn write_text<W: Write>(out: &mut W, mime: &str, body: &[u8], request: &Request, head_only: bool) -> io::Result<bool> {
    http::write_head(
        out,
        200,
        &[
            ("Content-Type", format!("{mime}; charset=utf-8")),
            ("Content-Length", body.len().to_string()),
        ],
        request.keep_alive,
    )?;
    if !head_only {
        out.write_all(body)?;
    }
    Ok(request.keep_alive)
}

/// The media playlist, built from the stream's segment index on first use.
fn hls_playlist(media: &registry::HlsMedia) -> io::Result<std::sync::Arc<Vec<u8>>> {
    let mut cached = media.playlist.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(body) = cached.as_ref() {
        return Ok(body.clone());
    }
    let range = format!("bytes={}-{}", media.index_start, media.index_end);
    let mut response = upstream::get(&media.url, Some(&range))?;
    let mut index = Vec::new();
    // A segment index is a few KB; the limit guards against a wrong range.
    (&mut response.reader).take(4 << 20).read_to_end(&mut index)?;
    let segments = hls::parse_sidx(&index, media.index_start)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "no usable segment index"))?;
    let body = std::sync::Arc::new(hls::media_playlist(&media.uri, media.init_end, &segments).into_bytes());
    crate::debug!("HLS playlist with {} segments for {}", segments.len(), media.uri);
    *cached = Some(body.clone());
    Ok(body)
}

/// How long a request for a not yet loaded playlist item waits for its streams.
const LAZY_WAIT: Duration = Duration::from_secs(20);

/// Playlist items point to `/v/<videoId>/auto` before the app has loaded their streams.
/// A request for one asks the app to load the video (event "NeedVideo") and waits for it.
fn wait_for_lazy_entry(path: &str) -> Option<std::sync::Arc<Entry>> {
    let video_id = path.strip_prefix("/v/")?.strip_suffix("/auto")?;
    if video_id.is_empty() || video_id.contains('/') {
        return None;
    }
    crate::info!("Renderer wants {video_id}, waiting for its streams");
    events::push_local("NeedVideo", video_id);
    let deadline = std::time::Instant::now() + LAZY_WAIT;
    while std::time::Instant::now() < deadline {
        thread::sleep(Duration::from_millis(100));
        if let Some(entry) = registry::get(path) {
            return Some(entry);
        }
    }
    crate::info!("Streams of {video_id} did not arrive in time");
    None
}

fn serve_stream<W: Write>(stream: &Stream, request: &Request, head_only: bool, out: &mut W) -> io::Result<bool> {
    let total = stream.length();
    if total == 0 {
        return passthrough(stream, request, head_only, out);
    }
    let mut headers = vec![
        ("Content-Type", stream.mime.clone()),
        ("Accept-Ranges", "bytes".to_string()),
    ];
    let (status, start, end) = match http::parse_range(request.range.as_deref(), total) {
        RangeResult::Full => (200, 0, total - 1),
        RangeResult::Partial(start, end) => {
            headers.push(("Content-Range", format!("bytes {start}-{end}/{total}")));
            (206, start, end)
        }
        RangeResult::Unsatisfiable => {
            http::write_head(
                out,
                416,
                &[
                    ("Content-Range", format!("bytes */{total}")),
                    ("Content-Length", "0".into()),
                ],
                request.keep_alive,
            )?;
            return Ok(request.keep_alive);
        }
    };
    headers.push(("Content-Length", (end - start + 1).to_string()));
    http::write_head(out, status, &headers, request.keep_alive)?;
    if head_only {
        return Ok(request.keep_alive);
    }
    // Send the head right away, before the first upstream byte arrives.
    out.flush()?;
    let written = upstream::stream_range(&stream.url, start, end, out)?;
    if written != end - start + 1 {
        // The body is shorter than announced: the connection can not be reused.
        return Ok(false);
    }
    Ok(request.keep_alive)
}

/// Unknown length: forward the renderer's request as is and learn the length from the answer.
fn passthrough<W: Write>(stream: &Stream, request: &Request, head_only: bool, out: &mut W) -> io::Result<bool> {
    let mut response = match upstream::get(&stream.url, request.range.as_deref()) {
        Ok(response) => response,
        Err(e) => {
            crate::info!("Upstream failed: {e}");
            http::write_head(out, 502, &[("Content-Length", "0".into())], false)?;
            return Ok(false);
        }
    };
    if let Some(total) = response.total_length() {
        stream.set_length(total);
    }
    let mut headers = vec![
        ("Content-Type", stream.mime.clone()),
        ("Accept-Ranges", "bytes".to_string()),
    ];
    if let Some(length) = response.content_length {
        headers.push(("Content-Length", length.to_string()));
    }
    if let Some(range) = &response.content_range {
        headers.push(("Content-Range", range.clone()));
    }
    let keep_alive = request.keep_alive && response.content_length.is_some();
    http::write_head(out, response.status, &headers, keep_alive)?;
    if head_only {
        return Ok(keep_alive);
    }
    out.flush()?;
    io::copy(&mut response.reader, out)?;
    Ok(keep_alive)
}
