//! Casts YouTube videos from a desktop computer to DLNA renderers (prototype).
//!
//! ytdlna-desktop discover
//! ytdlna-desktop cast <description-url> <video-id> [--mode hls|muxed|audio] [--host <ip>]
//!
//! yt-dlp resolves the streams, the stream proxy of the Android patch serves them on this
//! computer, and the renderer gets the proxy URL over AVTransport.

mod dlna;
mod fmp4;
mod host;
mod media;
mod metadata;
mod nowplaying;
mod session;
mod youtube;

use std::process::ExitCode;
use std::time::{Duration, Instant};


fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    // Firefox starts native messaging hosts with the path of their manifest.
    if args.first().is_some_and(|a| a.ends_with(".json")) || args.first().map(String::as_str) == Some("host") {
        return match host::run() {
            Ok(()) => ExitCode::SUCCESS,
            Err(e) => {
                eprintln!("ytdlna host: {e}");
                ExitCode::FAILURE
            }
        };
    }
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
            Ok(r) => println!("{url}  {}  [{}]", r.name, r.modes().join(", ")),
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
    let mode: &'static str = match option(args, "--mode") {
        Some("hls") => "hls",
        Some("muxed") => "muxed",
        Some("audio") => "audio",
        Some(other) => return Err(std::io::Error::other(format!("unknown mode {other}"))),
        None => renderer.mode_for("video").ok_or_else(|| std::io::Error::other("the renderer plays none of our formats"))?,
    };
    let host = match option(args, "--host") {
        Some(h) => h.to_string(),
        None => renderer.local_address()?.to_string(),
    };

    let started = Instant::now();
    let port = media::start()?;
    let served = media::prepare(video_id, mode)?;
    println!("{} - {} ({} s) ready after {:.1} s", served.title, served.channel, served.duration,
             started.elapsed().as_secs_f64());
    let mut track = metadata::Track { video_id: video_id.to_string(), title: served.title.clone(),
                                      artist: served.channel.clone(), duration: served.duration, ..Default::default() };
    metadata::complete(&mut track, mode == "audio");
    let uri = media::auto_uri(&format!("http://{host}:{port}"), video_id);
    println!("Serving {} at {uri}", served.label);
    renderer.load(&uri, &metadata::didl(&track, &uri, media::mime(mode)))?;
    println!("Sent to {} after {:.1} s", renderer.name, started.elapsed().as_secs_f64());

    // Follow the renderer until it stops.
    let mut was_playing = false;
    let mut last = String::new();
    loop {
        std::thread::sleep(Duration::from_secs(2));
        let (state, position) = match renderer.status() {
            Ok((state, position, _, _)) => (state, dlna::clock(position)),
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

