//! Init and index ranges of a fragmented MP4 stream.
//!
//! The Android app gets them from YouTube's player response; yt-dlp does not pass them on, so
//! they are read from the file head: `ftyp`, `moov` (the init section) and the `sidx` box.

use std::io::{self, Read};

pub struct Ranges {
    /// Last byte of the init section.
    pub init_end: u64,
    /// First and last byte of the segment index.
    pub index_start: u64,
    pub index_end: u64,
}

/// Fresh stream URLs answer 403 for a second or two; they work after that.
const FRESH_URL_WAIT: std::time::Duration = std::time::Duration::from_secs(8);

pub fn probe(url: &str) -> io::Result<Ranges> {
    let started = std::time::Instant::now();
    let mut response = loop {
        match ytdlna::upstream::get(url, Some("bytes=0-65535")) {
            Ok(r) => break r,
            Err(e) if e.to_string().contains("403") && started.elapsed() < FRESH_URL_WAIT => {
                std::thread::sleep(std::time::Duration::from_millis(500));
            }
            Err(e) => return Err(e),
        }
    };
    let mut head = Vec::new();
    (&mut response.reader).take(65536).read_to_end(&mut head)?;
    parse(&head).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "no moov and sidx in the file head"))
}

fn parse(head: &[u8]) -> Option<Ranges> {
    let mut pos = 0usize;
    let mut init_end = None;
    while pos + 8 <= head.len() {
        let mut size = u32::from_be_bytes(head[pos..pos + 4].try_into().ok()?) as u64;
        let kind = &head[pos + 4..pos + 8];
        if size == 1 {
            size = u64::from_be_bytes(head.get(pos + 8..pos + 16)?.try_into().ok()?);
        }
        if size < 8 {
            return None;
        }
        let end = pos as u64 + size - 1;
        match kind {
            b"moov" => init_end = Some(end),
            b"sidx" => {
                return Some(Ranges { init_end: init_end?, index_start: pos as u64, index_end: end });
            }
            _ => {}
        }
        pos = usize::try_from(end + 1).ok()?;
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    fn boxed(kind: &[u8; 4], body: usize) -> Vec<u8> {
        let mut b = ((8 + body) as u32).to_be_bytes().to_vec();
        b.extend_from_slice(kind);
        b.resize(8 + body, 0);
        b
    }

    #[test]
    fn finds_init_and_index() {
        let mut head = boxed(b"ftyp", 16);
        head.extend(boxed(b"moov", 100));
        head.extend(boxed(b"sidx", 40));
        let r = parse(&head).unwrap();
        assert_eq!(r.init_end, 24 + 108 - 1);
        assert_eq!(r.index_start, 24 + 108);
        assert_eq!(r.index_end, 24 + 108 + 48 - 1);
    }

    #[test]
    fn no_index_in_head() {
        let mut head = boxed(b"ftyp", 16);
        head.extend(boxed(b"moov", 100));
        assert!(parse(&head).is_none());
    }
}
