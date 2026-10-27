//! Minimal HTTP/1.1 request parsing and response writing for the renderer side.

use std::io::{self, BufRead, Write};

pub struct Request {
    pub method: String,
    pub path: String,
    pub range: Option<String>,
    pub keep_alive: bool,
}

/// Reads one request head. Returns Ok(None) when the client closed the connection.
pub fn read_request<R: BufRead>(reader: &mut R) -> io::Result<Option<Request>> {
    let mut line = String::new();
    // Skip stray empty lines between pipelined requests.
    loop {
        line.clear();
        if reader.read_line(&mut line)? == 0 {
            return Ok(None);
        }
        if !line.trim().is_empty() {
            break;
        }
    }
    let mut parts = line.split_whitespace();
    let method = parts.next().unwrap_or("").to_ascii_uppercase();
    let target = parts.next().unwrap_or("/");
    let version = parts.next().unwrap_or("HTTP/1.0");
    let path = target.split('?').next().unwrap_or("/").to_string();

    let mut range = None;
    let mut keep_alive = version.eq_ignore_ascii_case("HTTP/1.1");
    let mut header_bytes = 0usize;
    loop {
        line.clear();
        let n = reader.read_line(&mut line)?;
        if n == 0 {
            return Ok(None);
        }
        header_bytes += n;
        if header_bytes > 64 * 1024 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "headers too large"));
        }
        let trimmed = line.trim_end();
        if trimmed.is_empty() {
            break;
        }
        if let Some((name, value)) = trimmed.split_once(':') {
            let value = value.trim();
            if name.eq_ignore_ascii_case("range") {
                range = Some(value.to_string());
            } else if name.eq_ignore_ascii_case("connection") {
                let value = value.to_ascii_lowercase();
                if value.contains("close") {
                    keep_alive = false;
                } else if value.contains("keep-alive") {
                    keep_alive = true;
                }
            }
        }
    }
    Ok(Some(Request {
        method,
        path,
        range,
        keep_alive,
    }))
}

#[derive(Debug, PartialEq, Eq)]
pub enum RangeResult {
    /// No (usable) range: send everything.
    Full,
    /// Inclusive byte range.
    Partial(u64, u64),
    Unsatisfiable,
}

/// Parses a single "bytes=" range against a known total length.
pub fn parse_range(header: Option<&str>, total: u64) -> RangeResult {
    let Some(header) = header else {
        return RangeResult::Full;
    };
    let Some(spec) = header.trim().strip_prefix("bytes=") else {
        return RangeResult::Full;
    };
    if spec.contains(',') {
        // Multiple ranges are not supported; serving the full body is allowed.
        return RangeResult::Full;
    }
    let Some((start, end)) = spec.trim().split_once('-') else {
        return RangeResult::Full;
    };
    let (start, end) = (start.trim(), end.trim());
    if total == 0 {
        return RangeResult::Unsatisfiable;
    }
    if start.is_empty() {
        // Suffix range: the last N bytes.
        let Ok(n) = end.parse::<u64>() else {
            return RangeResult::Full;
        };
        if n == 0 {
            return RangeResult::Unsatisfiable;
        }
        return RangeResult::Partial(total.saturating_sub(n), total - 1);
    }
    let Ok(start) = start.parse::<u64>() else {
        return RangeResult::Full;
    };
    if start >= total {
        return RangeResult::Unsatisfiable;
    }
    let end = if end.is_empty() {
        total - 1
    } else {
        match end.parse::<u64>() {
            Ok(end) if end >= start => end.min(total - 1),
            _ => return RangeResult::Full,
        }
    };
    RangeResult::Partial(start, end)
}

pub const DLNA_FEATURES: &str =
    "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000";

pub fn reason(status: u16) -> &'static str {
    match status {
        200 => "OK",
        206 => "Partial Content",
        404 => "Not Found",
        405 => "Method Not Allowed",
        416 => "Range Not Satisfiable",
        _ => "Bad Gateway",
    }
}

/// Writes a response head. `headers` are extra "Name: value" pairs.
pub fn write_head<W: Write>(
    out: &mut W,
    status: u16,
    headers: &[(&str, String)],
    keep_alive: bool,
) -> io::Result<()> {
    let mut head = format!("HTTP/1.1 {} {}\r\n", status, reason(status));
    for (name, value) in headers {
        head.push_str(name);
        head.push_str(": ");
        head.push_str(value);
        head.push_str("\r\n");
    }
    head.push_str("transferMode.dlna.org: Streaming\r\n");
    head.push_str("contentFeatures.dlna.org: ");
    head.push_str(DLNA_FEATURES);
    head.push_str("\r\n");
    head.push_str(if keep_alive {
        "Connection: keep-alive\r\n\r\n"
    } else {
        "Connection: close\r\n\r\n"
    });
    out.write_all(head.as_bytes())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ranges() {
        assert_eq!(parse_range(None, 100), RangeResult::Full);
        assert_eq!(parse_range(Some("bytes=0-9"), 100), RangeResult::Partial(0, 9));
        assert_eq!(parse_range(Some("bytes=10-"), 100), RangeResult::Partial(10, 99));
        assert_eq!(parse_range(Some("bytes=-10"), 100), RangeResult::Partial(90, 99));
        assert_eq!(parse_range(Some("bytes=50-500"), 100), RangeResult::Partial(50, 99));
        assert_eq!(parse_range(Some("bytes=100-"), 100), RangeResult::Unsatisfiable);
        assert_eq!(parse_range(Some("bytes=0-1,5-6"), 100), RangeResult::Full);
        assert_eq!(parse_range(Some("items=0-1"), 100), RangeResult::Full);
    }

    #[test]
    fn request_parsing() {
        let raw = b"GET /v/abc/1.mp4?x=1 HTTP/1.1\r\nHost: a\r\nRange: bytes=0-1\r\n\r\n";
        let mut reader = io::BufReader::new(&raw[..]);
        let req = read_request(&mut reader).unwrap().unwrap();
        assert_eq!(req.method, "GET");
        assert_eq!(req.path, "/v/abc/1.mp4");
        assert_eq!(req.range.as_deref(), Some("bytes=0-1"));
        assert!(req.keep_alive);
        assert!(read_request(&mut reader).unwrap().is_none());
    }
}
