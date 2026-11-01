# ytdlna-desktop (prototype)

Casts YouTube and YouTube Music from a Mac to DLNA renderers, with a Firefox extension
(`firefox/`) that talks to this program over native messaging.

## Firefox

1. `cargo build --release`, then `./install-firefox-host.sh` (registers the program for the
   extension in `~/Library/Application Support/Mozilla/NativeMessagingHosts`).
2. Firefox: `about:debugging` → This Firefox → Load Temporary Add-on → `firefox/manifest.json`.
   Release Firefox keeps unsigned extensions only until it restarts; for a lasting install the
   extension has to be signed (addons.mozilla.org, unlisted) or Firefox Developer Edition /
   ESR with `xpinstall.signatures.required` off.
3. Open a video on youtube.com or music.youtube.com and click the cast button in the player
   (YouTube: next to the subtitles button; YouTube Music: before repeat), or the extension's
   icon in the toolbar (Firefox puts it in the extensions menu first).

Each renderer is asked for the formats it plays (ConnectionManager GetProtocolInfo) when it is
found. It gets the best one it lists: HLS up to 1080p, else the 360p MP4, else sound only
(Sonos). Songs from YouTube Music go out as sound where the renderer plays it. Renderers with
none of these formats are left out of the menus.

While casting, the tab's player stays muted and paused at the renderer's position. Its play
button (and a click into the video, space, k) plays or pauses the renderer, its progress bar
seeks it. At the end of a video the tab moves on to its next title (its queue, autoplay), which
is then cast. 
The native messaging protocol is described in `src/host.rs`. For a renderer that is not found
on the network (an emulator), the extension's storage key `host` sets the address the renderer
reaches this computer at (e.g. `10.0.2.2`).

## Command line

```
ytdlna-desktop discover
ytdlna-desktop cast <description-url> <video-id> [--mode hls|muxed|audio] [--host <ip>]
```

- yt-dlp resolves the stream URLs (the YouTube web player itself streams over SABR, without
  plain file URLs). Needs `yt-dlp` and a JavaScript runtime for it (deno) on the PATH.
- The stream proxy of the Android patch (`../ytdlna-proxy`) serves them on port 8720.
- `hls` (default) is H.264 up to 1080p with AAC, as the Android app sends to renderers without
  DASH; `muxed` is the single 360p file; `audio` the AAC track.

Notes on yt-dlp's stream URLs (TV client): they refuse the Range header (403) but take the
range as `&range=a-b` parameter (`upstream::set_range_as_query`), and they answer 403 for a
second or two after they were issued.

Build: `cargo build --release` (rustup's toolchain).
