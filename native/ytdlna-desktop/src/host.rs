//! Native messaging host for the Firefox extension.
//!
//! Firefox starts this program when the extension connects and talks to it over stdin/stdout:
//! each message is JSON with a 4 byte length in front (native byte order). stdout carries only
//! messages; logs go to stderr, which Firefox shows in the browser console.
//!
//! From the extension:
//!   {"cmd":"discover"}
//!   {"cmd":"cast","renderer":"<description url>","videoId":"...","mode":"video|music","start":12,
//!    "queue":[{"videoId":"...","title":"...","artist":"...","album":"...","year":"...","art":"...","duration":213}]}
//!   {"cmd":"queue","queue":[...],"autoplay":false}   the tab's queue changed
//!     autoplay: the queue is the video and what autoplay plays next, not a list
//!   {"cmd":"play"} {"cmd":"pause"} {"cmd":"toggle"} {"cmd":"seek","seconds":90}
//!   {"cmd":"next"} {"cmd":"previous"} {"cmd":"stop"} {"cmd":"volume","value":30}
//! To the extension:
//!   {"type":"renderers","renderers":[{"url":"...","name":"...","modes":["hls","muxed","audio"]}]}
//!     modes: what the renderer plays of what we serve (empty: nothing)
//!   {"type":"casting", "videoId", "title", "channel", "album", "year", "art", "duration", "label", "renderer"}
//!   {"type":"track", ...the same}          the renderer moved on to this title by itself
//!   {"type":"status","videoId":"...","state":"PLAYING","position":42,"duration":213,"volume":20}
//!   {"type":"ended","videoId":"..."}      the renderer played the last title it had to its end
//!   {"type":"stopped","videoId":"..."}    the renderer stopped before the end (its remote)
//!   {"type":"error","message":"...","videoId":"..."}

use std::io::{self, Read, Write};
use std::sync::mpsc::{channel, Sender};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use serde_json::{json, Value};

use crate::dlna::{self, Renderer};
use crate::metadata::Track;
use crate::session::{self, Msg, Out};

pub fn run() -> io::Result<()> {
    // Started by Firefox the PATH is the system's: add Homebrew's for yt-dlp and deno.
    let path = std::env::var("PATH").unwrap_or_default();
    std::env::set_var("PATH", format!("/opt/homebrew/bin:/usr/local/bin:{path}"));

    let stdout = Mutex::new(io::stdout());
    let out: Out = Arc::new(move |message: Value| {
        let body = message.to_string();
        let mut out = stdout.lock().unwrap_or_else(|e| e.into_inner());
        let _ = out.write_all(&(body.len() as u32).to_ne_bytes());
        let _ = out.write_all(body.as_bytes());
        let _ = out.flush();
    });
    let (tx, rx) = channel();
    {
        let (out, tx) = (out.clone(), tx.clone());
        thread::Builder::new().name("session".into()).spawn(move || session::run(out, tx, rx))?;
    }
    {
        let (out, tx) = (out.clone(), tx.clone());
        thread::Builder::new().name("stdin".into()).spawn(move || read_messages(out, tx))?;
    }
    crate::nowplaying::init(tx);
    // The main thread runs what macOS delivers the media keys with; reading stdin ends the
    // process when Firefox goes.
    crate::nowplaying::run_main_loop()
}

fn read_messages(out: Out, tx: Sender<Msg>) {
    let mut input = io::stdin().lock();
    loop {
        let mut length = [0u8; 4];
        if input.read_exact(&mut length).is_err() {
            break; // Firefox closed the connection.
        }
        let mut body = vec![0u8; u32::from_ne_bytes(length) as usize];
        if input.read_exact(&mut body).is_err() {
            break;
        }
        match serde_json::from_slice::<Value>(&body) {
            Ok(message) => handle(&out, &tx, message),
            Err(e) => out(json!({"type": "error", "message": format!("bad message: {e}")})),
        }
    }
    // Without the browser nobody controls the renderer: it stops, and so does this program.
    let _ = tx.send(Msg::Quit);
    thread::sleep(Duration::from_millis(1500));
    std::process::exit(0);
}

fn queue(message: &Value) -> Vec<Track> {
    message["queue"].as_array().map(|q| q.iter().map(Track::from_json).collect()).unwrap_or_default()
}

fn handle(out: &Out, tx: &Sender<Msg>, message: Value) {
    let text = |key: &str| message[key].as_str().unwrap_or("").to_string();
    let msg = match message["cmd"].as_str().unwrap_or("") {
        "discover" => {
            let out = out.clone();
            thread::spawn(move || discover(&out));
            return;
        }
        "cast" => Msg::Cast {
            renderer: text("renderer"),
            video_id: text("videoId"),
            queue: queue(&message),
            autoplay: message["autoplay"].as_bool().unwrap_or(false),
            music: text("mode") == "music",
            start: message["start"].as_f64().unwrap_or(0.0) as u64,
            host: text("host"),
        },
        "queue" => Msg::Queue(queue(&message), message["autoplay"].as_bool().unwrap_or(false)),
        "play" => Msg::Play,
        "pause" => Msg::Pause,
        "toggle" => Msg::Toggle,
        "seek" => Msg::Seek(message["seconds"].as_f64().unwrap_or(0.0) as u64),
        "next" => Msg::Next,
        "previous" => Msg::Previous,
        "stop" => Msg::Stop,
        "volume" => Msg::Volume(message["value"].as_f64().unwrap_or(0.0).clamp(0.0, 100.0) as u32),
        other => {
            out(json!({"type": "error", "message": format!("unknown command {other:?}")}));
            return;
        }
    };
    let _ = tx.send(msg);
}

fn discover(out: &Out) {
    let urls = match dlna::discover(Duration::from_secs(3)) {
        Ok(urls) => urls,
        Err(e) => return out(json!({"type": "error", "message": format!("discovery failed: {e}")})),
    };
    // Descriptions in parallel; devices without AVTransport (not renderers) are left out.
    let found: Vec<Value> = thread::scope(|s| {
        let handles: Vec<_> = urls.iter().map(|url| s.spawn(move || (url, Renderer::open(url)))).collect();
        handles.into_iter().filter_map(|h| h.join().ok())
            .filter_map(|(url, r)| r.ok().map(|r| json!({"url": url, "name": r.name, "modes": r.modes()})))
            .collect()
    });
    out(json!({"type": "renderers", "renderers": found}));
}
