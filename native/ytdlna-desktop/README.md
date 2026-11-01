# ytdlna-desktop (prototype)

Casts YouTube videos from a Mac to DLNA renderers, as a first step towards a Firefox extension
(the extension would talk to this program over native messaging).

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
