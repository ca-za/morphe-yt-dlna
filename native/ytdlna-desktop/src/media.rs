//! The streams of videos, resolved with yt-dlp and served by the proxy.
//!
//! Every video is served under `/v/<id>/`; `/v/<id>/auto` is what the renderer gets: the HLS
//! master playlist or the single file, depending on the session's mode. Playlist entries point
//! there before their streams are resolved: the proxy then reports "NeedVideo" and waits while
//! they are resolved here. Stream URLs expire after about six hours; older ones are dropped, so
//! a later request resolves them again.

use std::collections::{HashMap, HashSet};
use std::io;
use std::sync::{Condvar, Mutex, OnceLock};
use std::thread;
use std::time::{Duration, Instant};

use ytdlna::{events, registry, server};

use crate::fmp4;
use crate::youtube::{self, Format, Info};

/// After this the stream URLs are resolved again (YouTube's expire after about six hours).
const MAX_AGE: Duration = Duration::from_secs(4 * 3600);

pub const PORT: u16 = 8720;

pub fn mime(mode: &str) -> &'static str {
    match mode {
        "hls" => "application/vnd.apple.mpegurl",
        "audio" => "audio/mp4",
        _ => "video/mp4",
    }
}

#[derive(Clone)]
pub struct Served {
    pub title: String,
    pub channel: String,
    pub duration: u64,
    pub thumbnail: String,
    pub label: String,
}

struct State {
    /// Video id -> what is served for it, in which mode, since when.
    served: HashMap<String, (Served, &'static str, Instant)>,
    resolving: HashSet<String>,
    /// The mode NeedVideo requests are served in (the session's).
    mode: &'static str,
}

fn state() -> &'static (Mutex<State>, Condvar) {
    static STATE: OnceLock<(Mutex<State>, Condvar)> = OnceLock::new();
    STATE.get_or_init(|| {
        (Mutex::new(State { served: HashMap::new(), resolving: HashSet::new(), mode: "hls" }), Condvar::new())
    })
}

/// Starts the proxy (once) and the thread that answers its NeedVideo requests.
pub fn start() -> io::Result<u16> {
    static STARTED: OnceLock<u16> = OnceLock::new();
    if let Some(port) = STARTED.get() {
        return Ok(*port);
    }
    ytdlna::upstream::set_range_as_query(true);
    let port = server::start(PORT)?;
    thread::Builder::new().name("need-video".into()).spawn(|| loop {
        let Some(event) = events::wait(Duration::from_secs(3600)) else { continue };
        if event.token.is_empty() && event.name == "NeedVideo" {
            let id = event.value;
            let mode = state().0.lock().unwrap_or_else(|e| e.into_inner()).mode;
            thread::spawn(move || {
                if let Err(e) = prepare(&id, mode) {
                    eprintln!("ytdlna host: streams of {id} failed: {e}");
                }
            });
        }
    })?;
    let _ = STARTED.set(port);
    Ok(port)
}

pub fn set_mode(mode: &'static str) {
    state().0.lock().unwrap_or_else(|e| e.into_inner()).mode = mode;
}

/// The renderer's address of a video: `/v/<id>/auto` under `base` ("http://host:port").
pub fn auto_uri(base: &str, video_id: &str) -> String {
    format!("{base}/v/{video_id}/auto")
}

/// Resolves and registers the streams of a video in `mode`, unless that is done and fresh.
/// Concurrent calls for the same video wait for the first.
pub fn prepare(video_id: &str, mode: &'static str) -> io::Result<Served> {
    let (lock, condvar) = state();
    {
        let mut s = lock.lock().unwrap_or_else(|e| e.into_inner());
        loop {
            if let Some((served, served_mode, at)) = s.served.get(video_id) {
                if *served_mode == mode && at.elapsed() < MAX_AGE {
                    return Ok(served.clone());
                }
            }
            if !s.resolving.contains(video_id) {
                break;
            }
            s = condvar.wait(s).unwrap_or_else(|e| e.into_inner());
        }
        s.resolving.insert(video_id.to_string());
        // Older stream URLs: whatever is registered goes, a request now waits for the new ones.
        s.served.remove(video_id);
    }
    registry::remove_prefix(&format!("/v/{video_id}/"));
    let result = youtube::resolve(video_id).and_then(|info| register(&info, mode).map(|label| (info, label)));
    let mut s = lock.lock().unwrap_or_else(|e| e.into_inner());
    s.resolving.remove(video_id);
    condvar.notify_all();
    let (info, label) = result?;
    let served = Served { title: info.title, channel: info.channel, duration: info.duration, thumbnail: info.thumbnail, label };
    s.served.insert(video_id.to_string(), (served.clone(), mode, Instant::now()));
    Ok(served)
}

/// Drops served videos whose stream URLs are about to expire (called now and then).
pub fn expire() {
    let mut s = state().0.lock().unwrap_or_else(|e| e.into_inner());
    let old: Vec<String> = s.served.iter().filter(|(_, (_, _, at))| at.elapsed() >= MAX_AGE).map(|(id, _)| id.clone()).collect();
    for id in old {
        s.served.remove(&id);
        registry::remove_prefix(&format!("/v/{id}/"));
    }
}

fn register(info: &Info, mode: &str) -> io::Result<String> {
    if let Some(agent) = youtube::user_agent(info) {
        ytdlna::upstream::set_user_agent(agent.to_string());
    }
    let auto = format!("/v/{}/auto", info.id);
    match mode {
        "hls" => {
            let (master, label) = register_hls(info)?;
            registry::put_text(format!("/v/{}/master.m3u8", info.id), "application/vnd.apple.mpegurl".into(), master.clone());
            registry::put_text(auto, "application/vnd.apple.mpegurl".into(), master);
            Ok(label)
        }
        "muxed" => register_single(info, &auto, |f| f.has_video && f.has_audio && f.mime == "video/mp4", "360p MP4"),
        "audio" => register_single(info, &auto, |f| !f.has_video && f.mime == "audio/mp4", "AAC"),
        other => Err(io::Error::other(format!("unknown mode {other}"))),
    }
}

/// HLS from separate H.264 tracks (up to 1080p) and the best AAC track, as the Android app does
/// for renderers without DASH. Returns the master playlist.
fn register_hls(info: &Info) -> io::Result<(Vec<u8>, String)> {
    let prefix = format!("/v/{}/", info.id);
    let mut video: Vec<&Format> = info.formats.iter()
        .filter(|f| f.has_video && !f.has_audio && f.codecs.starts_with("avc1") && f.height <= 1080)
        .collect();
    video.sort_by_key(|f| std::cmp::Reverse((f.height, f.bitrate)));
    let audio = info.formats.iter()
        .filter(|f| !f.has_video && f.codecs.starts_with("mp4a"))
        .max_by_key(|f| f.bitrate)
        .ok_or_else(|| io::Error::other("no AAC audio track"))?;
    if video.is_empty() {
        return Err(io::Error::other("no H.264 video track"));
    }
    let mut master = String::from("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n");
    master.push_str(&format!(
        "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"Audio\",DEFAULT=YES,AUTOSELECT=YES,URI=\"{}.m3u8\"\n",
        audio.itag
    ));
    // The tracks' ranges are read in parallel: one request each.
    let tracks: Vec<&Format> = video.iter().copied().chain(std::iter::once(audio)).collect();
    let ranges: Vec<_> = thread::scope(|s| {
        let handles: Vec<_> = tracks.iter().map(|f| s.spawn(|| fmp4::probe(&f.url))).collect();
        handles.into_iter().map(|h| h.join().unwrap()).collect()
    });
    let mut best = 0;
    for (format, range) in tracks.iter().zip(ranges) {
        let range = match range {
            Ok(r) => r,
            Err(e) => {
                eprintln!("ytdlna host: skipping {}: {e}", format.itag);
                if std::ptr::eq(*format, audio) {
                    return Err(e);
                }
                continue;
            }
        };
        let file = format!("{}.{}", format.itag, if format.has_video { "mp4" } else { "m4a" });
        registry::put_stream(format!("{prefix}{file}"), format.mime.clone(), format.url.clone(), format.size);
        registry::put_hls(format!("{prefix}{}.m3u8", format.itag), file, format.url.clone(),
                          range.init_end, range.index_start, range.index_end);
        if format.has_video {
            best = best.max(format.height);
            master.push_str(&format!(
                "#EXT-X-STREAM-INF:BANDWIDTH={},CODECS=\"{},{}\",RESOLUTION={}x{}{},AUDIO=\"audio\"\n{}.m3u8\n",
                format.bitrate + audio.bitrate, format.codecs, audio.codecs, format.width, format.height,
                if format.fps > 0 { format!(",FRAME-RATE={}.000", format.fps) } else { String::new() },
                format.itag
            ));
        }
    }
    if best == 0 {
        return Err(io::Error::other("no usable H.264 video track"));
    }
    Ok((master.into_bytes(), format!("HLS {best}p")))
}

fn register_single(info: &Info, auto: &str, wanted: impl Fn(&Format) -> bool, label: &str) -> io::Result<String> {
    let format = info.formats.iter().filter(|f| wanted(f)).max_by_key(|f| (f.height, f.bitrate))
        .ok_or_else(|| io::Error::other(format!("no {label} format")))?;
    let file = format!("/v/{}/{}.{}", info.id, format.itag, if format.has_video { "mp4" } else { "m4a" });
    registry::put_stream(file, format.mime.clone(), format.url.clone(), format.size);
    registry::put_stream(auto.to_string(), format.mime.clone(), format.url.clone(), format.size);
    Ok(label.to_string())
}
