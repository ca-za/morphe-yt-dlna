//! HTTP server the renderer streams from.

use std::io::{self, BufReader, BufWriter, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::OnceLock;
use std::thread;
use std::time::Duration;

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
    let Some(entry) = registry::get(&request.path) else {
        http::write_head(out, 404, &[("Content-Length", "0".into())], request.keep_alive)?;
        return Ok(request.keep_alive);
    };
    match entry.as_ref() {
        Entry::Text { mime, body } => {
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
        Entry::Stream(stream) => serve_stream(stream, request, head_only, out),
    }
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
