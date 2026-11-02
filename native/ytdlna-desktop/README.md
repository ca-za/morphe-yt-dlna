# ytdlna-desktop (prototype)

Casts YouTube and YouTube Music from a Mac to DLNA renderers, with a Firefox extension
(`firefox/`) that talks to this program over native messaging.

## Firefox

1. `cargo build --release`, then `./install-firefox-host.sh`: puts the program into a small
   background app (`target/release/YT DLNA.app`, no Dock icon; macOS gives media keys to apps)
   and registers it for the extension in `~/Library/Application Support/Mozilla/NativeMessagingHosts`.
   Firefox starts it when the extension needs it and ends it with the extension (reloading the
   extension restarts it); after a new build, run the script again.
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
**Queue.** The tab's queue goes to the renderer: YouTube Music's queue (with its automix),
a YouTube playlist or mix, or a single video and what autoplay plays next. Renderers with an
OpenHome playlist get all of it and play on by themselves; their remote skips, and the tab
follows. Others get the next title in advance (SetNextAVTransportURI), or, if they do not take
one, each title once the last one ended. Streams are resolved only when the renderer asks for a
title (`media.rs`).

**Metadata.** Titles and artists from the page (oEmbed where it gives none); for songs the album,
year, label and album cover from Deezer, else iTunes, as in the Android app (`metadata.rs`).

**macOS.** While casting, the title shows in Control Center as playing; the media keys play,
pause and skip on the renderer (`nowplaying.rs`). Afterwards the keys go back to other apps.

**Volume and Sonos.** Both menus have a volume slider. Sonos players appear once per group,
named after its rooms ("Kitchen + 1"); its menu lists the household's rooms to add to the group or
take out, each with its volume, and the slider sets the group's volume (`sonos.rs`).

`YTDLNA_AVTRANSPORT_ONLY=1` treats every renderer as one without OpenHome (for tests).

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
