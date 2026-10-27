//! HLS media playlists for YouTube's fragmented MP4 streams.
//!
//! A stream's segment index (the `sidx` box at its index range) lists every fragment with its
//! size and duration. Each fragment becomes an HLS segment as a byte range of the same file,
//! so the renderer fetches the stream the proxy already serves, with Range requests.

/// One fragment: byte offset and size in the file, duration in seconds.
#[derive(Debug, Clone, PartialEq)]
pub struct Segment {
    pub offset: u64,
    pub size: u64,
    pub duration: f64,
}

/// Parses a `sidx` box that starts at file offset `box_offset`.
/// Returns None if `data` is not a usable segment index.
pub fn parse_sidx(data: &[u8], box_offset: u64) -> Option<Vec<Segment>> {
    let mut r = Reader { data, pos: 0 };
    let mut box_size = r.u32()? as u64;
    if r.take(4)? != b"sidx" {
        return None;
    }
    if box_size == 1 {
        box_size = r.u64()?;
    }
    let version = r.u8()?;
    r.take(3)?; // flags
    r.take(4)?; // reference_ID
    let timescale = r.u32()? as f64;
    let first_offset = if version == 0 {
        r.take(4)?; // earliest_presentation_time
        r.u32()? as u64
    } else {
        r.take(8)?;
        r.u64()?
    };
    r.take(2)?; // reserved
    let count = r.u16()?;
    if timescale <= 0.0 || count == 0 {
        return None;
    }
    // Fragments follow the end of the sidx box, after first_offset.
    let mut offset = box_offset + box_size + first_offset;
    let mut segments = Vec::with_capacity(count as usize);
    for _ in 0..count {
        let reference = r.u32()?;
        let duration = r.u32()? as f64 / timescale;
        r.take(4)?; // SAP
        if reference & 0x8000_0000 != 0 {
            // A reference to another index: not used by YouTube's on-demand streams.
            return None;
        }
        let size = (reference & 0x7fff_ffff) as u64;
        segments.push(Segment { offset, size, duration });
        offset += size;
    }
    Some(segments)
}

/// A VOD media playlist (HLS version 7, fMP4) over byte ranges of `uri`, whose init segment
/// spans bytes `0..=init_end`.
pub fn media_playlist(uri: &str, init_end: u64, segments: &[Segment]) -> String {
    let target = segments.iter().map(|s| s.duration).fold(1.0_f64, f64::max).ceil() as u64;
    let mut out = String::with_capacity(64 + segments.len() * 48);
    out.push_str("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-INDEPENDENT-SEGMENTS\n");
    out.push_str(&format!("#EXT-X-TARGETDURATION:{target}\n#EXT-X-MEDIA-SEQUENCE:0\n"));
    out.push_str(&format!("#EXT-X-MAP:URI=\"{uri}\",BYTERANGE=\"{}@0\"\n", init_end + 1));
    for segment in segments {
        out.push_str(&format!(
            "#EXTINF:{:.3},\n#EXT-X-BYTERANGE:{}@{}\n{uri}\n",
            segment.duration, segment.size, segment.offset
        ));
    }
    out.push_str("#EXT-X-ENDLIST\n");
    out
}

struct Reader<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    fn take(&mut self, n: usize) -> Option<&'a [u8]> {
        let slice = self.data.get(self.pos..self.pos.checked_add(n)?)?;
        self.pos += n;
        Some(slice)
    }

    fn u8(&mut self) -> Option<u8> {
        Some(self.take(1)?[0])
    }

    fn u16(&mut self) -> Option<u16> {
        Some(u16::from_be_bytes(self.take(2)?.try_into().ok()?))
    }

    fn u32(&mut self) -> Option<u32> {
        Some(u32::from_be_bytes(self.take(4)?.try_into().ok()?))
    }

    fn u64(&mut self) -> Option<u64> {
        Some(u64::from_be_bytes(self.take(8)?.try_into().ok()?))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A version 0 sidx with the given (size, duration in timescale units) references.
    pub(crate) fn sidx(timescale: u32, first_offset: u32, refs: &[(u32, u32)]) -> Vec<u8> {
        let mut body = vec![0u8, 0, 0, 0]; // version 0, flags
        body.extend(1u32.to_be_bytes()); // reference_ID
        body.extend(timescale.to_be_bytes());
        body.extend(0u32.to_be_bytes()); // earliest_presentation_time
        body.extend(first_offset.to_be_bytes());
        body.extend(0u16.to_be_bytes());
        body.extend((refs.len() as u16).to_be_bytes());
        for (size, duration) in refs {
            body.extend(size.to_be_bytes());
            body.extend(duration.to_be_bytes());
            body.extend(0x9000_0000u32.to_be_bytes()); // starts with SAP type 1
        }
        let mut data = ((body.len() + 8) as u32).to_be_bytes().to_vec();
        data.extend(b"sidx");
        data.extend(body);
        data
    }

    #[test]
    fn parses_segments_after_the_index() {
        let data = sidx(1000, 0, &[(5000, 5005), (4000, 4500)]);
        let segments = parse_sidx(&data, 700).unwrap();
        let first = 700 + data.len() as u64;
        assert_eq!(segments.len(), 2);
        assert_eq!(segments[0], Segment { offset: first, size: 5000, duration: 5.005 });
        assert_eq!(segments[1].offset, first + 5000);
        assert!((segments[1].duration - 4.5).abs() < 1e-9);
    }

    #[test]
    fn rejects_other_data() {
        assert!(parse_sidx(b"\0\0\0\x10moov\0\0\0\0\0\0\0\0", 0).is_none());
        assert!(parse_sidx(&sidx(1000, 0, &[(10, 10)])[..20], 0).is_none());
    }

    #[test]
    fn playlist_uses_byte_ranges_of_one_file() {
        let segments = vec![
            Segment { offset: 1000, size: 500, duration: 5.0 },
            Segment { offset: 1500, size: 400, duration: 4.2 },
        ];
        let playlist = media_playlist("3.mp4", 699, &segments);
        assert!(playlist.starts_with("#EXTM3U\n#EXT-X-VERSION:7\n"));
        assert!(playlist.contains("#EXT-X-TARGETDURATION:5\n"));
        assert!(playlist.contains("#EXT-X-MAP:URI=\"3.mp4\",BYTERANGE=\"700@0\"\n"));
        assert!(playlist.contains("#EXTINF:4.200,\n#EXT-X-BYTERANGE:400@1500\n3.mp4\n"));
        assert!(playlist.ends_with("#EXT-X-ENDLIST\n"));
    }
}
