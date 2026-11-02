//! A cast session: one renderer, the tab's queue and the title that plays.
//!
//! One thread does everything with the renderer, in order (`run`): commands from the extension,
//! results of background work and a tick every second, in which it reads the renderer's state,
//! follows its track changes and brings the tab's queue to it bit by bit. Resolving streams
//! (yt-dlp) and looking up metadata happen in other threads, which report back.
//!
//! Renderers with an OpenHome playlist get the whole queue as their playlist (as the Android
//! app's OpenHomeRenderer): the TV plays on by itself, its remote skips. Others get the next
//! title with SetNextAVTransportURI where they take it, else each title when the last one ended.

use std::collections::{HashMap, HashSet};
use std::sync::mpsc::{Receiver, RecvTimeoutError, Sender};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

use serde_json::{json, Value};

use crate::dlna::{self, Renderer};
use crate::media::{self, Served};
use crate::metadata::{self, Track};

pub type Out = Arc<dyn Fn(Value) + Send + Sync>;

pub enum Msg {
    /// `autoplay`: the queue is the title and what autoplay plays next (not a list of its own):
    /// played titles stay on the renderer, for its "back".
    Cast { renderer: String, video_id: String, queue: Vec<Track>, autoplay: bool, music: bool, start: u64, host: String },
    Queue(Vec<Track>, bool),
    Play,
    Pause,
    Toggle,
    Seek(u64),
    Next,
    Previous,
    Stop,
    Volume(u32),
    /// The current title's streams and metadata of a new session are ready (or failed).
    Ready { generation: u64, track: Track, result: Result<Served, String> },
    /// A title's metadata is complete.
    Completed(Track),
    /// The extension is gone.
    Quit,
}

/// Titles per tick added to or removed from a renderer playlist: an insert can take half a second.
const CHANGES_PER_TICK: usize = 3;
/// How long a playlist entry waits for its metadata before it goes in with what is known.
const METADATA_WAIT: Duration = Duration::from_secs(4);

enum Backend {
    OpenHome {
        track_of: HashMap<String, u32>,
        video_of: HashMap<u32, String>,
        current_track: u32,
        /// Removing the titles of a replaced queue.
        replacing: bool,
        refused: HashSet<String>,
    },
    AvTransport {
        /// The title given as next (SetNextAVTransportURI).
        next: Option<String>,
        next_supported: bool,
    },
}

struct Session {
    generation: u64,
    renderer: Renderer,
    mode: &'static str,
    base: String,
    music: bool,
    queue: Vec<Track>,
    autoplay: bool,
    current: String,
    /// The renderer plays (the first title was loaded).
    started: bool,
    backend: Backend,
    state: String,
    position: u64,
    played: bool,
    last_position: u64,
    pending_seek: Option<u64>,
    volume: Option<u32>,
    /// Since when a title waits for its metadata (for playlist entries).
    waiting: HashMap<String, Instant>,
}

struct Actor {
    out: Out,
    tx: Sender<Msg>,
    generation: u64,
    session: Option<Session>,
    completed: HashMap<String, Track>,
    completing: HashSet<String>,
    ticks: u64,
}

pub fn run(out: Out, tx: Sender<Msg>, rx: Receiver<Msg>) {
    let mut actor = Actor {
        out, tx, generation: 0, session: None, completed: HashMap::new(), completing: HashSet::new(), ticks: 0,
    };
    let mut next_tick = Instant::now() + Duration::from_secs(1);
    loop {
        let wait = next_tick.saturating_duration_since(Instant::now());
        match rx.recv_timeout(wait) {
            Ok(Msg::Quit) | Err(RecvTimeoutError::Disconnected) => {
                if let Some(s) = &actor.session {
                    let _ = stop(s);
                }
                return;
            }
            Ok(msg) => actor.handle(msg),
            Err(RecvTimeoutError::Timeout) => {
                actor.tick();
                next_tick = Instant::now() + Duration::from_secs(1);
            }
        }
    }
}

fn stop(s: &Session) -> std::io::Result<()> {
    match s.backend {
        Backend::OpenHome { .. } => s.renderer.call("Playlist", "Stop", &[]).map(|_| ()),
        Backend::AvTransport { .. } => s.renderer.stop(),
    }
}

impl Actor {
    fn error(&self, message: impl std::fmt::Display, video_id: &str) {
        eprintln!("ytdlna host: {message}");
        (self.out)(json!({"type": "error", "message": message.to_string(), "videoId": video_id}));
    }

    fn handle(&mut self, msg: Msg) {
        match msg {
            Msg::Cast { renderer, video_id, queue, autoplay, music, start, host } => {
                self.cast(&renderer, &video_id, queue, autoplay, music, start, &host)
            }
            Msg::Queue(queue, autoplay) => {
                if let Some(s) = &mut self.session {
                    if !queue.is_empty() {
                        s.queue = dedupe(queue);
                        s.autoplay = autoplay;
                    }
                }
            }
            Msg::Ready { generation, track, result } => self.ready(generation, track, result),
            Msg::Completed(track) => {
                self.completing.remove(&track.video_id);
                self.completed.insert(track.video_id.clone(), track);
            }
            Msg::Quit => {}
            control => self.control(control),
        }
    }

    fn control(&mut self, msg: Msg) {
        let Some(s) = &self.session else { return };
        if !s.started {
            return;
        }
        let openhome = matches!(s.backend, Backend::OpenHome { .. });
        // Without a renderer playlist, next and previous load the queue's titles from here.
        if !openhome && matches!(msg, Msg::Next | Msg::Previous) {
            let index = s.queue.iter().position(|t| t.video_id == s.current);
            let target = index.and_then(|i| if matches!(msg, Msg::Next) { i.checked_add(1) } else { i.checked_sub(1) })
                .and_then(|i| s.queue.get(i)).map(|t| t.video_id.clone());
            if let Some(id) = target {
                let track = self.track_for(&id);
                if let Err(e) = self.switch_avtransport(track) {
                    self.error(e, &id);
                }
            }
            return;
        }
        if matches!(msg, Msg::Stop) {
            let s = self.session.take().unwrap();
            if let Err(e) = stop(&s) {
                self.error(e, "");
            }
            return;
        }
        let s = self.session.as_mut().unwrap();
        let r = &s.renderer;
        let result = match msg {
            Msg::Toggle if s.state == "PLAYING" => pause(r, openhome),
            Msg::Toggle | Msg::Play => {
                if openhome { r.call("Playlist", "Play", &[]).map(|_| ()) } else { r.play() }
            }
            Msg::Pause => pause(r, openhome),
            Msg::Seek(seconds) => {
                s.position = seconds;
                if openhome {
                    let playing = s.state == "PLAYING";
                    r.call("Playlist", "SeekSecondAbsolute", &[("Value", &seconds.to_string())]).and_then(|_| {
                        // Some renderers stay paused after an OpenHome seek.
                        if playing { r.call("Playlist", "Play", &[]).map(|_| ()) } else { Ok(()) }
                    })
                } else {
                    r.seek(seconds)
                }
            }
            Msg::Next => r.call("Playlist", "Next", &[]).map(|_| ()),
            Msg::Previous => r.call("Playlist", "Previous", &[]).map(|_| ()),
            Msg::Volume(volume) => {
                s.volume = Some(volume);
                set_volume(r, volume)
            }
            _ => Ok(()),
        };
        if let Err(e) = result {
            self.error(e, "");
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn cast(&mut self, renderer_url: &str, video_id: &str, queue: Vec<Track>, autoplay: bool, music: bool, start: u64, host: &str) {
        let queue = dedupe(queue);
        // The same renderer: the title is played from its playlist or loaded, the session stays.
        let reuse = self.session.as_mut().and_then(|s| {
            (s.started && s.renderer.description_url == renderer_url && s.music == music).then(|| {
                if !queue.is_empty() {
                    s.queue = queue.clone();
                    s.autoplay = autoplay;
                }
                (s.current == video_id, matches!(s.backend, Backend::OpenHome { .. }))
            })
        });
        if let Some((same, openhome)) = reuse {
            if same {
                return self.announce("casting");
            }
            let track = self.track_for(video_id);
            let result = if openhome { self.switch_openhome(track) } else { self.switch_avtransport(track) };
            if let Err(e) = result {
                self.error(e, video_id);
            }
            return;
        }
        // A new session.
        if let Some(old) = self.session.take() {
            if old.renderer.description_url != renderer_url {
                thread::spawn(move || { let _ = stop(&old); });
            }
        }
        self.generation += 1;
        let generation = self.generation;
        let renderer = match Renderer::open(renderer_url) {
            Ok(r) => r,
            Err(e) => return self.error(e, video_id),
        };
        let Some(mode) = renderer.mode_for(if music { "music" } else { "video" }) else {
            return self.error(format!("{} spielt keines der Formate ab", renderer.name), video_id);
        };
        let port = match media::start() {
            Ok(port) => port,
            Err(e) => return self.error(e, video_id),
        };
        media::set_mode(mode);
        let address = if host.is_empty() {
            match renderer.local_address() {
                Ok(a) => a.to_string(),
                Err(e) => return self.error(e, video_id),
            }
        } else {
            host.to_string()
        };
        // Sonos plays OpenHome-less; its queue comes with its own support (sonos.rs).
        let backend = if renderer.has("Playlist") {
            Backend::OpenHome { track_of: HashMap::new(), video_of: HashMap::new(), current_track: 0, replacing: false, refused: HashSet::new() }
        } else {
            Backend::AvTransport { next: None, next_supported: true }
        };
        eprintln!("ytdlna host: casting {video_id} to {} ({mode}, {})", renderer.name,
                  if matches!(backend, Backend::OpenHome { .. }) { "OpenHome playlist" } else { "AVTransport" });
        let mut track = queue.iter().find(|t| t.video_id == video_id).cloned()
            .unwrap_or_else(|| Track { video_id: video_id.to_string(), ..Default::default() });
        if let Some(done) = self.completed.get(video_id) {
            track = done.clone();
        }
        self.session = Some(Session {
            generation, renderer, mode, base: format!("http://{address}:{port}"), music, queue, autoplay,
            current: video_id.to_string(), started: false, backend, state: String::new(), position: 0,
            played: false, last_position: 0, pending_seek: (start > 3).then_some(start), volume: None,
            waiting: HashMap::new(),
        });
        // Streams and metadata of the first title in parallel.
        let tx = self.tx.clone();
        let id = video_id.to_string();
        thread::spawn(move || {
            let (track, result) = thread::scope(|scope| {
                let streams = scope.spawn(|| media::prepare(&id, mode).map_err(|e| e.to_string()));
                let mut track = track;
                metadata::complete(&mut track, music);
                (track, streams.join().unwrap_or_else(|_| Err("resolving failed".into())))
            });
            let _ = tx.send(Msg::Ready { generation, track, result });
        });
    }

    /// Metadata of a title as far as known (complete, or what the tab gave).
    fn track_for(&self, video_id: &str) -> Track {
        if let Some(done) = self.completed.get(video_id) {
            return done.clone();
        }
        self.session.as_ref().and_then(|s| s.queue.iter().find(|t| t.video_id == video_id).cloned())
            .unwrap_or_else(|| Track { video_id: video_id.to_string(), ..Default::default() })
    }

    /// Looks up a title's metadata in the background; true if it is complete.
    fn want(&mut self, track: &Track, music: bool) -> bool {
        if self.completed.contains_key(&track.video_id) {
            return true;
        }
        if self.completing.insert(track.video_id.clone()) {
            let tx = self.tx.clone();
            let mut track = track.clone();
            thread::spawn(move || {
                metadata::complete(&mut track, music);
                let _ = tx.send(Msg::Completed(track));
            });
        }
        false
    }

    fn ready(&mut self, generation: u64, mut track: Track, result: Result<Served, String>) {
        let Some(s) = &mut self.session else { return };
        if s.generation != generation {
            return;
        }
        let served = match result {
            Ok(served) => served,
            Err(e) => {
                let id = s.current.clone();
                self.session = None;
                return self.error(e, &id);
            }
        };
        if track.title.is_empty() {
            track.title = served.title.clone();
        }
        if track.artist.is_empty() {
            track.artist = served.channel.clone();
        }
        if track.duration == 0 {
            track.duration = served.duration;
        }
        if track.art.is_empty() {
            track.art = served.thumbnail.clone();
        }
        self.completed.insert(track.video_id.clone(), track.clone());
        let s = self.session.as_mut().unwrap();
        let result = match s.backend {
            Backend::OpenHome { .. } => start_openhome(s, &track),
            Backend::AvTransport { .. } => {
                let uri = media::auto_uri(&s.base, &track.video_id);
                s.renderer.load(&uri, &metadata::didl(&track, &uri, media::mime(s.mode)))
            }
        };
        if let Err(e) = result {
            let id = s.current.clone();
            self.session = None;
            return self.error(e, &id);
        }
        s.started = true;
        eprintln!("ytdlna host: {} ({}) on {}", track.title, served.label, s.renderer.name);
        self.announce_with_label("casting", &served.label);
    }

    fn announce(&self, kind: &str) {
        self.announce_with_label(kind, "");
    }

    /// "casting" or "track": the title that plays now, with what the renderer shows of it.
    fn announce_with_label(&self, kind: &str, label: &str) {
        let Some(s) = &self.session else { return };
        let t = self.track_for(&s.current);
        (self.out)(json!({
            "type": kind, "videoId": t.video_id, "title": t.title, "channel": t.artist, "album": t.album,
            "year": t.year, "art": t.art, "duration": t.duration, "renderer": s.renderer.name,
            "label": if label.is_empty() { mode_label(s.mode) } else { label },
        }));
    }

    /// Another title on an OpenHome renderer: from its playlist, or inserted after the current one.
    fn switch_openhome(&mut self, track: Track) -> std::io::Result<()> {
        let s = self.session.as_mut().unwrap();
        let Backend::OpenHome { track_of, video_of, current_track, .. } = &mut s.backend else { unreachable!() };
        let id = match track_of.get(&track.video_id) {
            Some(id) => *id,
            None => {
                let uri = media::auto_uri(&s.base, &track.video_id);
                let didl = metadata::didl(&track, &uri, media::mime(s.mode));
                let new = insert(&s.renderer, *current_track, &uri, &didl)?;
                track_of.insert(track.video_id.clone(), new);
                video_of.insert(new, track.video_id.clone());
                new
            }
        };
        *current_track = id;
        s.renderer.call("Playlist", "SeekId", &[("Value", &id.to_string())])?;
        s.renderer.call("Playlist", "Play", &[])?;
        s.current = track.video_id.clone();
        s.played = false;
        s.last_position = 0;
        self.announce("casting");
        Ok(())
    }

    fn switch_avtransport(&mut self, track: Track) -> std::io::Result<()> {
        let s = self.session.as_mut().unwrap();
        let uri = media::auto_uri(&s.base, &track.video_id);
        s.renderer.load(&uri, &metadata::didl(&track, &uri, media::mime(s.mode)))?;
        if let Backend::AvTransport { next, .. } = &mut s.backend {
            *next = None;
        }
        s.current = track.video_id.clone();
        s.played = false;
        s.last_position = 0;
        self.announce("casting");
        Ok(())
    }

    fn tick(&mut self) {
        self.ticks += 1;
        if self.ticks % 600 == 0 {
            media::expire();
        }
        let Some(s) = &mut self.session else { return };
        if !s.started {
            return;
        }
        let generation = s.generation;
        let openhome = matches!(s.backend, Backend::OpenHome { .. });
        let status = if openhome { openhome_status(s) } else { avtransport_status(s) };
        let Ok((state, position, duration, changed_to)) = status else { return };

        // The renderer moved on to another title by itself (or its remote).
        if let Some(video_id) = changed_to {
            s.current = video_id;
            s.played = false;
            s.last_position = 0;
            self.announce("track");
        }
        let s = self.session.as_mut().unwrap();
        if let (Some(target), "PLAYING") = (s.pending_seek, state.as_str()) {
            s.pending_seek = None;
            let r = if openhome {
                s.renderer.call("Playlist", "SeekSecondAbsolute", &[("Value", &target.to_string())]).map(|_| ())
            } else {
                s.renderer.seek(target)
            };
            if let Err(e) = r {
                eprintln!("ytdlna host: seek failed: {e}");
            }
        }
        if self.ticks % 3 == 1 {
            s.volume = get_volume(&s.renderer).ok();
        }
        s.state = state.clone();
        s.position = position;
        let track_duration = self.completed.get(&s.current).map_or(0, |t| t.duration);
        let duration = if duration > 0 { duration } else { track_duration };
        (self.out)(json!({"type": "status", "videoId": s.current, "state": state, "position": position,
                          "duration": duration, "volume": s.volume}));

        // The end of a title (and of the playlist, for OpenHome).
        let near_end = duration > 0 && s.last_position + 5 >= duration;
        // Some renderers report the end of an HLS stream as playing at 0 instead of stopped.
        let wrapped = !openhome && state == "PLAYING" && near_end && position + 10 < s.last_position;
        if state == "PLAYING" && !wrapped {
            s.played = true;
            s.last_position = position;
        } else if s.played && (wrapped || state == "STOPPED" || state == "NO_MEDIA_PRESENT") {
            s.played = false;
            let ended = duration == 0 || near_end;
            let current = s.current.clone();
            if !ended {
                (self.out)(json!({"type": "stopped", "videoId": current}));
                self.session = None;
                return;
            }
            if !openhome {
                // Without a next title on the renderer: the next one of the queue, from here.
                let index = s.queue.iter().position(|t| t.video_id == current);
                if let Some(next) = index.and_then(|i| s.queue.get(i + 1)).cloned() {
                    let next = self.completed.get(&next.video_id).cloned().unwrap_or(next);
                    if let Err(e) = self.switch_avtransport(next) {
                        self.error(e, &current);
                    } else {
                        self.announce("track");
                    }
                    return;
                }
            }
            (self.out)(json!({"type": "ended", "videoId": current}));
        }

        // The queue to the renderer.
        if self.session.as_ref().is_some_and(|s| s.generation == generation) {
            if openhome { self.sync_openhome() } else { self.sync_next() }
        }
    }

    /// Adds the queue's titles missing on the renderer playlist (those after the playing one
    /// first, each after its predecessor) and removes those of a replaced queue.
    fn sync_openhome(&mut self) {
        let s = self.session.as_ref().unwrap();
        let ids: Vec<String> = s.queue.iter().map(|t| t.video_id.clone()).collect();
        let Some(playing) = ids.iter().position(|id| *id == s.current) else { return };
        let music = s.music;
        let mut changes = self.remove_old_queue(&ids);
        let order: Vec<usize> = (playing + 1..ids.len()).chain(0..playing).collect();
        for i in order {
            if changes >= CHANGES_PER_TICK {
                return;
            }
            let s = self.session.as_ref().unwrap();
            let Backend::OpenHome { track_of, refused, .. } = &s.backend else { return };
            let id = &ids[i];
            if track_of.contains_key(id) || refused.contains(id) {
                continue;
            }
            // After its predecessor, which comes first in this order, or at the start.
            let after = if i == 0 { Some(0) } else { track_of.get(&ids[i - 1]).copied() };
            let Some(after) = after else { return };
            let queued = s.queue[i].clone();
            let ready = self.want(&queued, music);
            let s = self.session.as_mut().unwrap();
            let since = *s.waiting.entry(id.clone()).or_insert_with(Instant::now);
            if !ready && since.elapsed() < METADATA_WAIT {
                return; // In order: the next titles wait for this one.
            }
            let track = self.completed.get(id).cloned().unwrap_or(queued);
            let uri = media::auto_uri(&s.base, id);
            let didl = metadata::didl(&track, &uri, media::mime(s.mode));
            let Backend::OpenHome { track_of, video_of, refused, .. } = &mut s.backend else { return };
            match insert(&s.renderer, after, &uri, &didl) {
                Ok(new) => {
                    track_of.insert(id.clone(), new);
                    video_of.insert(new, id.clone());
                }
                Err(e) => {
                    eprintln!("ytdlna host: adding {id} to the renderer playlist failed: {e}");
                    refused.insert(id.clone());
                }
            }
            changes += 1;
        }
    }

    /// Deletes renderer entries of a replaced queue, a few per tick. Only once the tab has a new
    /// queue (none of the renderer's other titles in it): titles that dropped out of the tab's
    /// queue after playing stay, for the TV's "back".
    fn remove_old_queue(&mut self, ids: &[String]) -> usize {
        let s = self.session.as_mut().unwrap();
        if s.autoplay {
            return 0;
        }
        let Backend::OpenHome { track_of, video_of, current_track, replacing, .. } = &mut s.backend else { return 0 };
        let wanted: HashSet<u32> = ids.iter().filter_map(|id| track_of.get(id).copied())
            .chain(std::iter::once(*current_track)).collect();
        let old: Vec<u32> = video_of.keys().filter(|t| !wanted.contains(t)).copied().collect();
        if old.is_empty() {
            *replacing = false;
            return 0;
        }
        if !*replacing {
            if wanted.len() > 1 {
                return 0;
            }
            *replacing = true;
            eprintln!("ytdlna host: new queue: removing {} old titles from the renderer playlist", old.len());
        }
        let mut removed = 0;
        for track in old.iter().take(CHANGES_PER_TICK) {
            let _ = s.renderer.call("Playlist", "DeleteId", &[("Value", &track.to_string())]);
            if let Some(video) = video_of.remove(track) {
                track_of.remove(&video);
            }
            removed += 1;
        }
        if removed == old.len() {
            *replacing = false;
        }
        removed
    }

    /// The next title of the queue as the renderer's next (SetNextAVTransportURI).
    fn sync_next(&mut self) {
        let s = self.session.as_ref().unwrap();
        let Backend::AvTransport { next, next_supported } = &s.backend else { return };
        if !*next_supported {
            return;
        }
        let index = s.queue.iter().position(|t| t.video_id == s.current);
        let Some(wanted) = index.and_then(|i| s.queue.get(i + 1)).cloned() else { return };
        if next.as_deref() == Some(wanted.video_id.as_str()) {
            return;
        }
        let music = s.music;
        if !self.want(&wanted, music) {
            return;
        }
        let track = self.completed.get(&wanted.video_id).cloned().unwrap_or(wanted);
        let s = self.session.as_mut().unwrap();
        let uri = media::auto_uri(&s.base, &track.video_id);
        let result = s.renderer.set_next(&uri, &metadata::didl(&track, &uri, media::mime(s.mode)));
        let Backend::AvTransport { next, next_supported } = &mut s.backend else { return };
        match result {
            Ok(()) => *next = Some(track.video_id.clone()),
            Err(e) => {
                eprintln!("ytdlna host: {} takes no next title ({e}): titles follow one by one", s.renderer.name);
                *next_supported = false;
            }
        }
    }
}

fn dedupe(queue: Vec<Track>) -> Vec<Track> {
    let mut seen = HashSet::new();
    queue.into_iter().filter(|t| !t.video_id.is_empty() && seen.insert(t.video_id.clone())).collect()
}

fn mode_label(mode: &str) -> &'static str {
    match mode {
        "hls" => "HLS",
        "audio" => "AAC",
        _ => "360p MP4",
    }
}

fn pause(r: &Renderer, openhome: bool) -> std::io::Result<()> {
    if openhome { r.call("Playlist", "Pause", &[]).map(|_| ()) } else { r.pause() }
}

fn get_volume(r: &Renderer) -> std::io::Result<u32> {
    if r.has("RenderingControl") {
        return r.volume();
    }
    let v = r.call("Volume", "Volume", &[])?;
    Ok(dlna::tag(&v, "Value").and_then(|v| v.parse().ok()).unwrap_or(0))
}

fn set_volume(r: &Renderer, volume: u32) -> std::io::Result<()> {
    if r.has("RenderingControl") {
        return r.set_volume(volume);
    }
    r.call("Volume", "SetVolume", &[("Value", &volume.min(100).to_string())]).map(|_| ())
}

fn insert(r: &Renderer, after: u32, uri: &str, didl: &str) -> std::io::Result<u32> {
    let response = r.call("Playlist", "Insert", &[("AfterId", &after.to_string()), ("Uri", uri), ("Metadata", didl)])?;
    dlna::tag(&response, "NewId").and_then(|id| id.parse().ok())
        .ok_or_else(|| std::io::Error::other("Insert returned no id"))
}

/// Clears the renderer playlist and starts it with the first title; the queue follows by tick.
fn start_openhome(s: &mut Session, track: &Track) -> std::io::Result<()> {
    if s.renderer.has("Product") {
        let _ = s.renderer.call("Product", "SetSourceIndexByName", &[("Value", "Playlist")]);
    }
    s.renderer.call("Playlist", "DeleteAll", &[])?;
    let uri = media::auto_uri(&s.base, &track.video_id);
    let id = insert(&s.renderer, 0, &uri, &metadata::didl(track, &uri, media::mime(s.mode)))?;
    s.renderer.call("Playlist", "SeekId", &[("Value", &id.to_string())])?;
    s.renderer.call("Playlist", "Play", &[])?;
    if let Backend::OpenHome { track_of, video_of, current_track, .. } = &mut s.backend {
        track_of.clear();
        video_of.clear();
        track_of.insert(track.video_id.clone(), id);
        video_of.insert(id, track.video_id.clone());
        *current_track = id;
    }
    Ok(())
}

type Status = (String, u64, u64, Option<String>);

/// State, position, duration and the title the renderer switched to (if it did).
fn openhome_status(s: &mut Session) -> std::io::Result<Status> {
    let r = &s.renderer;
    let state = match dlna::tag(&r.call("Playlist", "TransportState", &[])?, "Value").unwrap_or_default().as_str() {
        "Playing" => "PLAYING",
        "Paused" => "PAUSED_PLAYBACK",
        "Stopped" => "STOPPED",
        "Buffering" => "TRANSITIONING",
        other => return Ok((other.to_string(), s.position, 0, None)),
    }.to_string();
    let id: u32 = dlna::tag(&r.call("Playlist", "Id", &[])?, "Value").and_then(|v| v.parse().ok()).unwrap_or(0);
    let (position, duration) = if r.has("Time") {
        let t = r.call("Time", "Time", &[])?;
        let number = |name: &str| dlna::tag(&t, name).and_then(|v| v.parse().ok()).unwrap_or(0);
        (number("Seconds"), number("Duration"))
    } else {
        let (_, position, duration, _) = r.status()?;
        (position, duration)
    };
    let Backend::OpenHome { video_of, current_track, .. } = &mut s.backend else { unreachable!() };
    let mut changed = None;
    if id != 0 && id != *current_track {
        if let Some(video) = video_of.get(&id) {
            *current_track = id;
            changed = Some(video.clone());
        }
    }
    Ok((state, position, duration, changed))
}

fn avtransport_status(s: &mut Session) -> std::io::Result<Status> {
    let (state, position, duration, uri) = s.renderer.status()?;
    let Backend::AvTransport { next, .. } = &mut s.backend else { unreachable!() };
    let mut changed = None;
    if let Some(n) = next.clone() {
        if uri.contains(&format!("/v/{n}/")) {
            *next = None;
            changed = Some(n);
        }
    }
    Ok((state, position, duration, changed))
}
