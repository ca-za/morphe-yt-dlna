//! Live streams as HLS, from the live formats the app received.
//!
//! A live format's URL answers with its newest segment, and with `sq=N` with segment N, as the
//! app plays it. Every segment is self-initializing (ftyp, moov, then emsg, moof, mdat), and its
//! emsg box names its sequence number and target duration. From this the proxy builds a sliding
//! HLS media playlist per format: an init section (ftyp + moov) and the newest segments, served
//! without their own ftyp/moov.

use std::io::{self, Read};

use crate::upstream;

/// Segments in a media playlist: enough for the renderer's live buffer.
const WINDOW: u64 = 8;
/// The newest segment's start holds ftyp, moov and emsg: a few KB.
const HEAD_BYTES: u64 = 16 * 1024;
/// Upper bound for one segment (5 s of 1080p is a few MB).
const MAX_SEGMENT: u64 = 64 << 20;

/// Sequence number and target duration of the newest segment, and the init section.
pub struct Head {
    pub sequence: u64,
    pub target_seconds: f64,
    pub init: Vec<u8>,
}

/// Reads the newest segment's start of a live format.
pub fn head(url: &str) -> io::Result<Head> {
    let mut response = upstream::get(url, Some(&format!("bytes=0-{}", HEAD_BYTES - 1)))?;
    let mut data = Vec::new();
    (&mut response.reader).take(HEAD_BYTES).read_to_end(&mut data)?;
    parse_head(&data).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "no live segment head"))
}

pub fn parse_head(data: &[u8]) -> Option<Head> {
    let mut init = Vec::new();
    let mut sequence = None;
    let mut target_seconds = 5.0;
    for (kind, start, end) in boxes(data) {
        match kind {
            b"ftyp" | b"moov" => init.extend_from_slice(data.get(start..end)?),
            b"emsg" => {
                let text = String::from_utf8_lossy(data.get(start..end)?).into_owned();
                sequence = sequence.or_else(|| field(&text, "Sequence-Number:"));
                if let Some(us) = field(&text, "Target-Duration-Us:") {
                    target_seconds = us as f64 / 1_000_000.0;
                }
            }
            b"moof" => break,
            _ => {}
        }
    }
    if init.is_empty() {
        return None;
    }
    Some(Head { sequence: sequence?, target_seconds, init })
}

/// A live media playlist with the newest `WINDOW` segments up to `head`.
pub fn media_playlist(head: &Head) -> String {
    let first = head.sequence.saturating_sub(WINDOW - 1);
    let target = head.target_seconds.ceil().max(1.0) as u64;
    let mut out = format!(
        "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n#EXT-X-TARGETDURATION:{target}\n\
         #EXT-X-MEDIA-SEQUENCE:{first}\n#EXT-X-MAP:URI=\"init.mp4\"\n"
    );
    for sequence in first..=head.sequence {
        out.push_str(&format!("#EXTINF:{:.3},\n{sequence}.m4s\n", head.target_seconds));
    }
    out
}

/// Segment `sequence` of a live format, without its own ftyp and moov.
pub fn segment(url: &str, sequence: u64) -> io::Result<Vec<u8>> {
    let separator = if url.contains('?') { '&' } else { '?' };
    let mut response = upstream::get(&format!("{url}{separator}sq={sequence}"), None)?;
    let mut data = Vec::new();
    (&mut response.reader).take(MAX_SEGMENT).read_to_end(&mut data)?;
    Ok(strip_init(data))
}

/// Drops the leading ftyp and moov boxes: the init section is served separately.
pub fn strip_init(data: Vec<u8>) -> Vec<u8> {
    let mut cut = 0;
    for (kind, _, end) in boxes(&data) {
        if kind != b"ftyp" && kind != b"moov" {
            break;
        }
        cut = end;
    }
    if cut == 0 { data } else { data[cut..].to_vec() }
}

/// Top-level boxes: (type, start, end) with start and end covering the whole box.
fn boxes(data: &[u8]) -> impl Iterator<Item = (&[u8; 4], usize, usize)> {
    let mut pos = 0usize;
    std::iter::from_fn(move || {
        let header = data.get(pos..pos + 8)?;
        let size = u32::from_be_bytes(header[..4].try_into().ok()?) as usize;
        let kind: &[u8; 4] = header[4..8].try_into().ok()?;
        if size < 8 {
            return None;
        }
        let start = pos;
        // The last box may be cut off (head reads only the start).
        let end = (pos + size).min(data.len());
        pos += size;
        Some((kind, start, end))
    })
}

fn field(text: &str, name: &str) -> Option<u64> {
    let rest = &text[text.find(name)? + name.len()..];
    rest.trim_start().split(|c: char| !c.is_ascii_digit()).next()?.parse().ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn mp4_box(kind: &[u8; 4], payload: &[u8]) -> Vec<u8> {
        let mut out = ((payload.len() + 8) as u32).to_be_bytes().to_vec();
        out.extend_from_slice(kind);
        out.extend_from_slice(payload);
        out
    }

    fn segment_bytes(sequence: u64) -> Vec<u8> {
        let emsg = format!("\0\0\0\0http://youtube.com/streaming/metadata/segment/102015\0\0Sequence-Number: {sequence}\r\nTarget-Duration-Us: 5000000\r\n");
        [mp4_box(b"ftyp", b"iso6"), mp4_box(b"moov", b"tracks"), mp4_box(b"emsg", emsg.as_bytes()),
         mp4_box(b"moof", b"frag"), mp4_box(b"mdat", &[1; 100])].concat()
    }

    #[test]
    fn reads_sequence_and_init_from_the_head() {
        let data = segment_bytes(2287473);
        let head = parse_head(&data[..data.len() - 50]).unwrap();
        assert_eq!(head.sequence, 2287473);
        assert_eq!(head.target_seconds, 5.0);
        assert_eq!(head.init, [mp4_box(b"ftyp", b"iso6"), mp4_box(b"moov", b"tracks")].concat());
        assert!(parse_head(&mp4_box(b"moof", b"x")).is_none());
    }

    #[test]
    fn playlist_slides_over_the_newest_segments() {
        let head = Head { sequence: 100, target_seconds: 5.0, init: vec![] };
        let playlist = media_playlist(&head);
        assert!(playlist.contains("#EXT-X-MEDIA-SEQUENCE:93\n"));
        assert!(playlist.contains("#EXT-X-MAP:URI=\"init.mp4\"\n"));
        assert!(playlist.contains("#EXTINF:5.000,\n93.m4s\n"));
        assert!(playlist.ends_with("100.m4s\n"));
        assert!(!playlist.contains("ENDLIST"));
        assert_eq!(playlist.matches(".m4s").count(), 8);
    }

    #[test]
    fn segments_lose_their_init_section() {
        let stripped = strip_init(segment_bytes(7));
        assert_eq!(&stripped[4..8], b"emsg");
        let plain = mp4_box(b"moof", b"x");
        assert_eq!(strip_init(plain.clone()), plain);
    }
}
