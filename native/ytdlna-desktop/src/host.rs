//! Native messaging host for the Firefox extension.
//!
//! Firefox starts this program when the extension connects and talks to it over stdin/stdout:
//! each message is JSON with a 4 byte length in front (native byte order). stdout carries only
//! messages; logs go to stderr, which Firefox shows in the browser console.
//!
//! From the extension:
//!   {"cmd":"discover"}
//!   {"cmd":"cast","renderer":"<description url>","videoId":"...","mode":"hls|muxed|audio","start":12}
//!   {"cmd":"play"} {"cmd":"pause"} {"cmd":"seek","seconds":90} {"cmd":"stop"}
//! To the extension:
//!   {"type":"renderers","renderers":[{"url":"...","name":"..."}]}
//!   {"type":"casting","videoId":"...","title":"...","channel":"...","duration":213,"label":"HLS 1080p","renderer":"..."}
//!   {"type":"status","videoId":"...","state":"PLAYING","position":42,"duration":213}
//!   {"type":"ended","videoId":"..."}      the renderer played the video to its end
//!   {"type":"stopped","videoId":"..."}    the renderer stopped before the end (its remote)
//!   {"type":"error","message":"...","videoId":"..."}

use std::io::{self, Read, Write};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use serde_json::{json, Value};

use crate::dlna::{self, Renderer};
use crate::youtube;

#[derive(Clone)]
struct Session {
    generation: u64,
    renderer: Renderer,
    video_id: String,
    duration: u64,
}

struct Host {
    out: Mutex<io::Stdout>,
    session: Mutex<Option<Session>>,
    /// Raised by every cast and stop: older work drops its results.
    generation: AtomicU64,
}

impl Host {
    fn send(&self, message: Value) {
        let body = message.to_string();
        let mut out = self.out.lock().unwrap_or_else(|e| e.into_inner());
        let _ = out.write_all(&(body.len() as u32).to_ne_bytes());
        let _ = out.write_all(body.as_bytes());
        let _ = out.flush();
    }

    fn current(&self) -> Option<Session> {
        self.session.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }

    fn error(&self, message: impl std::fmt::Display, video_id: &str) {
        eprintln!("ytdlna host: {message}");
        self.send(json!({"type": "error", "message": message.to_string(), "videoId": video_id}));
    }
}

pub fn run() -> io::Result<()> {
    // Started by Firefox the PATH is the system's: add Homebrew's for yt-dlp and deno.
    let path = std::env::var("PATH").unwrap_or_default();
    std::env::set_var("PATH", format!("/opt/homebrew/bin:/usr/local/bin:{path}"));

    let host = Arc::new(Host { out: Mutex::new(io::stdout()), session: Mutex::new(None), generation: AtomicU64::new(0) });
    {
        let host = host.clone();
        thread::Builder::new().name("status".into()).spawn(move || follow(&host))?;
    }
    let mut input = io::stdin().lock();
    loop {
        let mut length = [0u8; 4];
        if input.read_exact(&mut length).is_err() {
            break; // Firefox closed the connection.
        }
        let mut body = vec![0u8; u32::from_ne_bytes(length) as usize];
        input.read_exact(&mut body)?;
        match serde_json::from_slice::<Value>(&body) {
            Ok(message) => handle(&host, message),
            Err(e) => host.error(format!("bad message: {e}"), ""),
        }
    }
    // Without the browser nobody controls the renderer: stop it, the proxy ends with us.
    if let Some(session) = host.current() {
        let _ = session.renderer.stop();
    }
    Ok(())
}

fn handle(host: &Arc<Host>, message: Value) {
    let text = |key: &str| message[key].as_str().unwrap_or("").to_string();
    match message["cmd"].as_str().unwrap_or("") {
        "discover" => {
            let host = host.clone();
            thread::spawn(move || discover(&host));
        }
        "cast" => {
            let generation = host.generation.fetch_add(1, Ordering::SeqCst) + 1;
            let host = host.clone();
            let (renderer, video_id, mode, address) = (text("renderer"), text("videoId"), text("mode"), text("host"));
            let start = message["start"].as_u64().unwrap_or(0);
            thread::spawn(move || {
                if let Err(e) = cast(&host, generation, &renderer, &video_id, &mode, &address, start) {
                    host.error(e, &video_id);
                }
            });
        }
        command @ ("play" | "pause" | "seek" | "stop") => {
            let Some(session) = host.current() else { return };
            if command == "stop" {
                host.generation.fetch_add(1, Ordering::SeqCst);
                *host.session.lock().unwrap_or_else(|e| e.into_inner()) = None;
            }
            let seconds = message["seconds"].as_f64().unwrap_or(0.0) as u64;
            let host = host.clone();
            let command = command.to_string();
            thread::spawn(move || {
                let r = &session.renderer;
                let result = match command.as_str() {
                    "play" => r.play(),
                    "pause" => r.pause(),
                    "seek" => r.seek(seconds),
                    _ => r.stop(),
                };
                if let Err(e) = result {
                    host.error(e, &session.video_id);
                }
            });
        }
        other => host.error(format!("unknown command {other:?}"), ""),
    }
}

fn discover(host: &Host) {
    let urls = match dlna::discover(Duration::from_secs(3)) {
        Ok(urls) => urls,
        Err(e) => return host.error(format!("discovery failed: {e}"), ""),
    };
    // Descriptions in parallel; devices without AVTransport (not renderers) are left out.
    let found: Vec<Value> = thread::scope(|s| {
        let handles: Vec<_> = urls.iter().map(|url| s.spawn(move || (url, Renderer::open(url)))).collect();
        handles.into_iter().filter_map(|h| h.join().ok())
            .filter_map(|(url, r)| r.ok().map(|r| json!({"url": url, "name": r.name})))
            .collect()
    });
    host.send(json!({"type": "renderers", "renderers": found}));
}

fn cast(host: &Host, generation: u64, renderer_url: &str, video_id: &str, mode: &str, address: &str, start: u64)
        -> io::Result<()> {
    let renderer = Renderer::open(renderer_url)?;
    let info = youtube::resolve(video_id)?;
    if host.generation.load(Ordering::SeqCst) != generation {
        return Ok(()); // Another video was cast meanwhile.
    }
    let address = if address.is_empty() { renderer.local_address()?.to_string() } else { address.to_string() };
    let media = crate::serve(&info, if mode.is_empty() { "hls" } else { mode }, &address)?;
    if host.generation.load(Ordering::SeqCst) != generation {
        return Ok(());
    }
    renderer.load(&media.uri, &media.metadata)?;
    eprintln!("ytdlna host: {} ({}) on {}", info.title, media.label, renderer.name);
    host.send(json!({"type": "casting", "videoId": info.id, "title": info.title, "channel": info.channel,
                     "duration": info.duration, "label": media.label, "renderer": renderer.name}));
    *host.session.lock().unwrap_or_else(|e| e.into_inner()) = Some(Session {
        generation, renderer: renderer.clone(), video_id: info.id.clone(), duration: info.duration,
    });
    if start > 3 {
        // Renderers seek only once they play.
        for _ in 0..20 {
            thread::sleep(Duration::from_millis(500));
            if matches!(renderer.status(), Ok((state, _)) if state == "PLAYING") {
                break;
            }
        }
        renderer.seek(start)?;
    }
    Ok(())
}

/// Reports the renderer's state every second and notices the end of a video.
fn follow(host: &Host) {
    let mut played_generation = 0;
    let mut last_position = 0;
    let mut position_generation = 0;
    loop {
        thread::sleep(Duration::from_secs(1));
        let Some(session) = host.current() else { continue };
        let Ok((state, position)) = session.renderer.status() else { continue };
        if host.generation.load(Ordering::SeqCst) != session.generation {
            continue; // The answer is about an older video.
        }
        let position = dlna::seconds(&position);
        if position_generation != session.generation {
            position_generation = session.generation;
            last_position = 0;
        }
        host.send(json!({"type": "status", "videoId": session.video_id, "state": state,
                         "position": position, "duration": session.duration}));
        let near_end = session.duration > 0 && last_position + 5 >= session.duration;
        // Some renderers report the end of an HLS stream as playing at 0 instead of stopped.
        let wrapped = state == "PLAYING" && near_end && position + 10 < last_position;
        if state == "PLAYING" && !wrapped {
            played_generation = session.generation;
            last_position = position;
            continue;
        }
        if played_generation == session.generation && (wrapped || state == "STOPPED" || state == "NO_MEDIA_PRESENT") {
            // Renderers often report position 0 once stopped: judge by the last one seen.
            let ended = session.duration == 0 || near_end;
            if wrapped {
                let _ = session.renderer.stop();
            }
            host.send(json!({"type": if ended { "ended" } else { "stopped" }, "videoId": session.video_id}));
            played_generation = 0;
            let mut current = host.session.lock().unwrap_or_else(|e| e.into_inner());
            if current.as_ref().is_some_and(|s| s.generation == session.generation) {
                *current = None;
            }
        }
    }
}
