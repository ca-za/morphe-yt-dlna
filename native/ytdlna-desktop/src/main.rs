//! Casts YouTube videos from a desktop computer to DLNA renderers (prototype).
//!
//! ytdlna-desktop discover
//! ytdlna-desktop cast <description-url> <video-id> [--mode hls|muxed|audio] [--host <ip>]
//!
//! yt-dlp resolves the streams, the stream proxy of the Android patch serves them on this
//! computer, and the renderer gets the proxy URL over AVTransport.

mod dlna;
mod fmp4;
mod youtube;

use std::process::ExitCode;
use std::time::{Duration, Instant};

use ytdlna::{registry, server};

use youtube::{Format, Info};

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let result = match args.first().map(String::as_str) {
        Some("discover") => discover(),
        Some("cast") if args.len() >= 3 => cast(&args[1], &args[2], &args[3..]),
        _ => {
            eprintln!("usage: ytdlna-desktop discover\n       ytdlna-desktop cast <description-url> <video-id> [--mode hls|muxed|audio] [--host <ip>]");
            return ExitCode::from(2);
        }
    };
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("error: {e}");
            ExitCode::FAILURE
        }
    }
}

fn discover() -> std::io::Result<()> {
    for url in dlna::discover(Duration::from_secs(3))? {
        match dlna::Renderer::open(&url) {
            Ok(r) => println!("{url}  {}", r.name),
            Err(e) => println!("{url}  ({e})"),
        }
    }
    Ok(())
}

fn option<'a>(args: &'a [String], name: &str) -> Option<&'a str> {
    args.iter().position(|a| a == name).and_then(|i| args.get(i + 1)).map(String::as_str)
}

fn cast(description_url: &str, video_id: &str, args: &[String]) -> std::io::Result<()> {
    let renderer = dlna::Renderer::open(description_url)?;
    let mode = option(args, "--mode").unwrap_or("hls");
    let host = match option(args, "--host") {
        Some(h) => h.to_string(),
        None => renderer.local_address()?.to_string(),
    };

    let started = Instant::now();
    let info = youtube::resolve(video_id)?;
    println!("{} - {} ({} s, {} formats) in {:.1} s", info.title, info.channel, info.duration,
             info.formats.len(), started.elapsed().as_secs_f64());

    ytdlna::upstream::set_range_as_query(true);
    if let Some(agent) = youtube::user_agent(&info) {
        ytdlna::upstream::set_user_agent(agent.to_string());
    }
    let port = server::start(8720)?;
    let base = format!("http://{host}:{port}");
    let (path, mime, label) = match mode {
        "hls" => register_hls(&info)?,
        "muxed" => register_single(&info, |f| f.has_video && f.has_audio && f.mime == "video/mp4", "muxed")?,
        "audio" => register_single(&info, |f| !f.has_video && f.mime == "audio/mp4", "audio")?,
        other => return Err(std::io::Error::other(format!("unknown mode {other}"))),
    };
    let uri = format!("{base}{path}");
    println!("Serving {label} at {uri} (ready after {:.1} s)", started.elapsed().as_secs_f64());

    renderer.load(&uri, &didl(&info, &uri, mime))?;
    println!("Sent to {} after {:.1} s", renderer.name, started.elapsed().as_secs_f64());

    // Follow the renderer until it stops.
    let mut was_playing = false;
    let mut last = String::new();
    loop {
        std::thread::sleep(Duration::from_secs(2));
        let (state, position) = match renderer.status() {
            Ok(s) => s,
            Err(e) => {
                println!("status failed: {e}");
                continue;
            }
        };
        let line = format!("{state} {position}");
        if line != last {
            println!("[{:5.1} s] {line}", started.elapsed().as_secs_f64());
            last = line;
        }
        if state == "PLAYING" {
            was_playing = true;
        } else if was_playing && (state == "STOPPED" || state == "NO_MEDIA_PRESENT") {
            println!("Renderer stopped.");
            return Ok(());
        }
    }
}

/// HLS from separate H.264 tracks (up to 1080p) and the best AAC track, as the Android app does
/// for renderers without DASH.
fn register_hls(info: &Info) -> std::io::Result<(String, &'static str, String)> {
    let prefix = format!("/v/{}/", info.id);
    let mut video: Vec<&Format> = info.formats.iter()
        .filter(|f| f.has_video && !f.has_audio && f.codecs.starts_with("avc1") && f.height <= 1080)
        .collect();
    video.sort_by_key(|f| std::cmp::Reverse((f.height, f.bitrate)));
    let audio = info.formats.iter()
        .filter(|f| !f.has_video && f.codecs.starts_with("mp4a"))
        .max_by_key(|f| f.bitrate)
        .ok_or_else(|| std::io::Error::other("no AAC audio track"))?;
    if video.is_empty() {
        return Err(std::io::Error::other("no H.264 video track"));
    }
    let mut master = String::from("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n");
    master.push_str(&format!(
        "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"Audio\",DEFAULT=YES,AUTOSELECT=YES,URI=\"{}.m3u8\"\n",
        audio.itag
    ));
    // The tracks' ranges are read in parallel: one request each.
    let tracks: Vec<&Format> = video.iter().copied().chain(std::iter::once(audio)).collect();
    let ranges: Vec<_> = std::thread::scope(|s| {
        let handles: Vec<_> = tracks.iter().map(|f| s.spawn(|| fmp4::probe(&f.url))).collect();
        handles.into_iter().map(|h| h.join().unwrap()).collect()
    });
    let mut best = 0;
    for (format, range) in tracks.iter().zip(ranges) {
        let range = match range {
            Ok(r) => r,
            Err(e) => {
                eprintln!("  skipping {}: {e}", format.itag);
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
    let path = format!("{prefix}master.m3u8");
    registry::put_text(path.clone(), "application/vnd.apple.mpegurl".into(), master.into_bytes());
    Ok((path, "application/vnd.apple.mpegurl", format!("HLS {best}p")))
}

fn register_single(info: &Info, wanted: impl Fn(&Format) -> bool, label: &str)
                   -> std::io::Result<(String, &'static str, String)> {
    let format = info.formats.iter().filter(|f| wanted(f)).max_by_key(|f| (f.height, f.bitrate))
        .ok_or_else(|| std::io::Error::other(format!("no {label} format")))?;
    let path = format!("/v/{}/{}.{}", info.id, format.itag, if format.has_video { "mp4" } else { "m4a" });
    registry::put_stream(path.clone(), format.mime.clone(), format.url.clone(), format.size);
    let mime = if format.has_video { "video/mp4" } else { "audio/mp4" };
    Ok((path, mime, format!("{label} itag {}", format.itag)))
}

fn didl(info: &Info, uri: &str, mime: &str) -> String {
    let class = if mime.starts_with("audio") { "object.item.audioItem.musicTrack" } else { "object.item.videoItem" };
    let duration = format!("{}:{:02}:{:02}.000", info.duration / 3600, info.duration / 60 % 60, info.duration % 60);
    let e = dlna::escape;
    format!(
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" \
         xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"ytdlna-{}\" parentID=\"0\" restricted=\"1\">\
         <dc:title>{}</dc:title><dc:creator>{}</dc:creator><upnp:artist>{}</upnp:artist>\
         <upnp:albumArtURI>{}</upnp:albumArtURI><upnp:class>{class}</upnp:class>\
         <res protocolInfo=\"http-get:*:{mime}:*\" duration=\"{duration}\">{}</res></item></DIDL-Lite>",
        e(&info.id), e(&info.title), e(&info.channel), e(&info.channel), e(&info.thumbnail), e(uri)
    )
}
