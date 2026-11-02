//! macOS media integration: the title on the renderer in Control Center and on the lock screen,
//! and the media keys (play/pause, next, previous) driving the renderer.
//!
//! Only while a session plays: then this program is the "now playing" app; afterwards the keys go
//! back to whatever played before (Music, the browser). The command callbacks arrive on the main
//! thread's run loop, which `run_main_loop` runs.

use std::sync::mpsc::Sender;

use crate::metadata::Track;
use crate::session::Msg;

#[cfg(target_os = "macos")]
mod imp {
    use std::sync::{Mutex, OnceLock};
    use std::time::Duration;

    use souvlaki::{MediaControlEvent, MediaControls, MediaMetadata, MediaPlayback, MediaPosition, PlatformConfig, SeekDirection};

    use super::*;

    struct State {
        controls: MediaControls,
        tx: Sender<Msg>,
        attached: bool,
        position: u64,
    }

    fn state() -> &'static Mutex<Option<State>> {
        static STATE: OnceLock<Mutex<Option<State>>> = OnceLock::new();
        STATE.get_or_init(|| Mutex::new(None))
    }

    pub fn init(tx: Sender<Msg>) {
        let config = PlatformConfig { dbus_name: "ytdlna", display_name: "YT DLNA", hwnd: None };
        if let Ok(controls) = MediaControls::new(config) {
            *state().lock().unwrap_or_else(|e| e.into_inner()) = Some(State { controls, tx, attached: false, position: 0 });
        }
    }

    fn with(f: impl FnOnce(&mut State)) {
        if let Some(s) = state().lock().unwrap_or_else(|e| e.into_inner()).as_mut() {
            f(s);
        }
    }

    fn attach(s: &mut State) {
        if s.attached {
            return;
        }
        let tx = Mutex::new(s.tx.clone());
        let _ = s.controls.attach(move |event| {
            let current = state().lock().ok().and_then(|s| s.as_ref().map(|s| s.position)).unwrap_or(0);
            let msg = match event {
                MediaControlEvent::Play => Msg::Play,
                MediaControlEvent::Pause => Msg::Pause,
                MediaControlEvent::Toggle => Msg::Toggle,
                MediaControlEvent::Next => Msg::Next,
                MediaControlEvent::Previous => Msg::Previous,
                MediaControlEvent::Stop => Msg::Stop,
                MediaControlEvent::SetPosition(MediaPosition(at)) => Msg::Seek(at.as_secs()),
                MediaControlEvent::Seek(SeekDirection::Forward) => Msg::Seek(current + 10),
                MediaControlEvent::Seek(SeekDirection::Backward) => Msg::Seek(current.saturating_sub(10)),
                MediaControlEvent::SeekBy(SeekDirection::Forward, by) => Msg::Seek(current + by.as_secs()),
                MediaControlEvent::SeekBy(SeekDirection::Backward, by) => Msg::Seek(current.saturating_sub(by.as_secs())),
                _ => return,
            };
            let _ = tx.lock().map(|tx| tx.send(msg));
        });
        s.attached = true;
    }

    pub fn track(track: &Track, renderer: &str) {
        with(|s| {
            attach(s);
            let artist = if track.artist.is_empty() { renderer.to_string() } else { format!("{} · {renderer}", track.artist) };
            let _ = s.controls.set_metadata(MediaMetadata {
                title: Some(&track.title),
                artist: Some(&artist),
                album: (!track.album.is_empty()).then_some(track.album.as_str()),
                cover_url: (!track.art.is_empty()).then_some(track.art.as_str()),
                duration: (track.duration > 0).then(|| Duration::from_secs(track.duration)),
            });
        });
    }

    pub fn playback(playing: bool, position: u64) {
        with(|s| {
            s.position = position;
            let progress = Some(MediaPosition(Duration::from_secs(position)));
            let _ = s.controls.set_playback(if playing { MediaPlayback::Playing { progress } } else { MediaPlayback::Paused { progress } });
        });
    }

    pub fn clear() {
        with(|s| {
            let _ = s.controls.set_playback(MediaPlayback::Stopped);
            let _ = s.controls.set_metadata(MediaMetadata::default());
            if s.attached {
                let _ = s.controls.detach();
                s.attached = false;
            }
        });
    }

    pub fn run_main_loop() -> ! {
        loop {
            unsafe { core_foundation::runloop::CFRunLoopRun() };
            std::thread::sleep(Duration::from_millis(100));
        }
    }
}

#[cfg(not(target_os = "macos"))]
mod imp {
    use super::*;

    pub fn init(_tx: Sender<Msg>) {}
    pub fn track(_track: &Track, _renderer: &str) {}
    pub fn playback(_playing: bool, _position: u64) {}
    pub fn clear() {}
    pub fn run_main_loop() -> ! {
        loop {
            std::thread::park();
        }
    }
}

pub use imp::{clear, init, playback, run_main_loop, track};
