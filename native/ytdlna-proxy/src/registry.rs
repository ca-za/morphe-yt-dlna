//! What the proxy serves, by request path.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock, RwLock};

pub enum Entry {
    /// Generated content, such as a DASH manifest.
    Text { mime: String, body: Vec<u8> },
    /// A YouTube stream, fetched on demand.
    Stream(Stream),
    /// An HLS media playlist over one stream, built from its segment index on first request.
    Hls(HlsMedia),
    /// One format of a live stream: `<path>/index.m3u8`, `<path>/init.mp4`, `<path>/<sq>.m4s`.
    LiveTrack(LiveTrack),
}

pub struct LiveTrack {
    pub url: String,
    pub mime: String,
    /// The init section (ftyp + moov), once read.
    pub init: Mutex<Option<Arc<Vec<u8>>>>,
}

pub struct HlsMedia {
    /// The stream file, relative to the playlist (as the renderer requests it).
    pub uri: String,
    /// YouTube URL of the stream, to read its segment index from.
    pub url: String,
    pub init_end: u64,
    pub index_start: u64,
    pub index_end: u64,
    /// The playlist once built.
    pub playlist: Mutex<Option<Arc<Vec<u8>>>>,
}

pub struct Stream {
    pub mime: String,
    pub url: String,
    /// Total size in bytes, 0 if unknown. Learned from the first response if needed.
    length: AtomicU64,
}

impl Stream {
    pub fn length(&self) -> u64 {
        self.length.load(Ordering::Relaxed)
    }

    pub fn set_length(&self, length: u64) {
        self.length.store(length, Ordering::Relaxed);
    }
}

/// Upper bound so a long session does not keep every video ever cast.
const MAX_ENTRIES: usize = 400;

fn map() -> &'static RwLock<HashMap<String, Arc<Entry>>> {
    static MAP: OnceLock<RwLock<HashMap<String, Arc<Entry>>>> = OnceLock::new();
    MAP.get_or_init(|| RwLock::new(HashMap::new()))
}

fn insert(path: String, entry: Entry) {
    let mut map = map().write().unwrap_or_else(|e| e.into_inner());
    if map.len() >= MAX_ENTRIES && !map.contains_key(&path) {
        map.clear();
    }
    map.insert(path, Arc::new(entry));
}

pub fn put_text(path: String, mime: String, body: Vec<u8>) {
    insert(path, Entry::Text { mime, body });
}

pub fn put_stream(path: String, mime: String, url: String, length: u64) {
    insert(
        path,
        Entry::Stream(Stream {
            mime,
            url,
            length: AtomicU64::new(length),
        }),
    );
}

pub fn put_hls(path: String, uri: String, url: String, init_end: u64, index_start: u64, index_end: u64) {
    insert(
        path,
        Entry::Hls(HlsMedia {
            uri,
            url,
            init_end,
            index_start,
            index_end,
            playlist: Mutex::new(None),
        }),
    );
}

pub fn put_live_track(path: String, url: String, mime: String) {
    insert(path, Entry::LiveTrack(LiveTrack { url, mime, init: Mutex::new(None) }));
}

pub fn remove_prefix(prefix: &str) {
    let mut map = map().write().unwrap_or_else(|e| e.into_inner());
    map.retain(|path, _| !path.starts_with(prefix));
}

pub fn get(path: &str) -> Option<Arc<Entry>> {
    map().read().unwrap_or_else(|e| e.into_inner()).get(path).cloned()
}
