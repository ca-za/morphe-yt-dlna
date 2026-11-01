//! Stream URLs of a video through yt-dlp.
//!
//! The YouTube web player streams over SABR (no plain file URLs), so the streams are not taken
//! from the browser: yt-dlp asks YouTube itself, as another client, and returns direct URLs.

use std::io;
use std::process::Command;

use serde_json::Value;

pub struct Info {
    pub id: String,
    pub title: String,
    pub channel: String,
    pub duration: u64,
    pub thumbnail: String,
    pub formats: Vec<Format>,
    user_agent: Option<String>,
}

pub struct Format {
    pub itag: String,
    pub url: String,
    /// "video/mp4", "audio/mp4", "video/webm", ...
    pub mime: String,
    /// The codecs for HLS: "avc1.640028", "mp4a.40.2". Empty for a muxed format.
    pub codecs: String,
    pub has_video: bool,
    pub has_audio: bool,
    pub width: u64,
    pub height: u64,
    pub fps: u64,
    /// Bits per second.
    pub bitrate: u64,
    pub size: u64,
}

fn clen(url: &str) -> u64 {
    url.split(['?', '&']).find_map(|p| p.strip_prefix("clen=")).and_then(|v| v.parse().ok()).unwrap_or(0)
}

/// The User-Agent yt-dlp would send to the stream URLs.
pub fn user_agent(info: &Info) -> Option<&str> {
    info.user_agent.as_deref()
}

pub fn resolve(video_id: &str) -> io::Result<Info> {
    let output = Command::new("yt-dlp")
        .args(["-J", "--no-warnings", "--no-playlist", "--"])
        .arg(format!("https://www.youtube.com/watch?v={video_id}"))
        .output()
        .map_err(|e| io::Error::new(e.kind(), format!("yt-dlp could not be started: {e}")))?;
    if !output.status.success() {
        let message = String::from_utf8_lossy(&output.stderr);
        return Err(io::Error::other(format!("yt-dlp: {}", message.trim())));
    }
    let json: Value = serde_json::from_slice(&output.stdout).map_err(io::Error::other)?;
    let text = |v: &Value, key: &str| v[key].as_str().unwrap_or("").to_string();
    let number = |v: &Value, key: &str| v[key].as_f64().unwrap_or(0.0) as u64;
    let mut formats = Vec::new();
    let mut user_agent = None;
    for f in json["formats"].as_array().into_iter().flatten() {
        let itag = text(f, "format_id");
        // Direct files only (no HLS from YouTube, no dynamic range compressed copies).
        if text(f, "protocol") != "https" || itag.contains('-') {
            continue;
        }
        if user_agent.is_none() {
            user_agent = f["http_headers"]["User-Agent"].as_str().map(str::to_string);
        }
        let vcodec = text(f, "vcodec");
        let acodec = text(f, "acodec");
        let has_video = !vcodec.is_empty() && vcodec != "none";
        let has_audio = !acodec.is_empty() && acodec != "none";
        let container = match text(f, "ext").as_str() {
            "mp4" | "m4a" => "mp4",
            "webm" => "webm",
            _ => continue,
        };
        let mime = format!("{}/{container}", if has_video { "video" } else { "audio" });
        let codecs = match (has_video, has_audio) {
            (true, false) => vcodec,
            (false, true) => acodec,
            _ => String::new(),
        };
        formats.push(Format {
            itag,
            url: text(f, "url"),
            mime,
            codecs,
            has_video,
            has_audio,
            width: number(f, "width"),
            height: number(f, "height"),
            fps: number(f, "fps"),
            bitrate: (f["tbr"].as_f64().unwrap_or(0.0) * 1000.0) as u64,
            // The proxy needs the size for ranges; the URL has it as "clen" when yt-dlp lacks it.
            size: match number(f, "filesize") {
                0 => clen(&text(f, "url")),
                size => size,
            },
        });
    }
    Ok(Info {
        id: text(&json, "id"),
        title: text(&json, "title"),
        channel: text(&json, "channel"),
        duration: number(&json, "duration"),
        thumbnail: text(&json, "thumbnail"),
        formats,
        user_agent,
    })
}
