# YT DLNA Patches

Cast YouTube and YouTube Music to DLNA / UPnP renderers (TVs, receivers, media players), as an
add-on to the official [Morphe](https://morphe.software) patches.

## Features

- **Cast button**: shows when a DLNA renderer is found on the network, opens a device picker
  like the apps' own cast dialog. Also from the share menu ("Stream to DLNA device").
- **Phone as remote**: play, pause, seek, next and volume keys on the phone control the TV, and
  the phone follows changes made on the TV. The phone stays silent and plays only the smallest
  streams while casting.
- **Formats**: DASH up to 4K, HLS up to 1080p for renderers without DASH, live streams as HLS.
- **Metadata**: title, artist (channel), cover, and in YouTube Music album, year and label from
  Deezer or iTunes. The OpenHome playlist or SetNextAVTransportURI gives the TV the next title.
- **YouTube Music**: song and video mode reach the TV; the player shows the album cover on its
  colors while casting.
- **Extras for renderers that ask for them**: captions, lyrics (YouTube Music), SponsorBlock
  segments and chapters, see [docs/renderer-guide.md](docs/renderer-guide.md). Plain renderers
  get the same as before.
- **Sonos**: groups shown by room, a rooms dialog to add or remove rooms and set their volume,
  and the group's volume on the volume keys.
- **Optional parts as patches of their own** (all on by default, each needs only "DLNA cast"):
  DLNA cover page, DLNA player controls, DLNA portrait lock, DLNA Sonos rooms, DLNA renderer
  extras. **DLNA session notifications** (YouTube Music, off by default) notices casts of other
  phones in the Wi-Fi and offers a remote control (media session).

## Install

> **Alpha**: releases are pre-releases (`v0.1.N-alpha`) for now.

The patch is an add-on: it is applied **together with** the official Morphe patches, which
Morphe Manager does only in Expert mode (in Simple mode it applies one source per app).

1. Add this source to Morphe Manager:
   https://morphe.software/add-source?github=ca-za/morphe-yt-dlna
2. Under *Sources*, open "YT DLNA Patches" and turn on **Pre-release patches**.
3. Under *Settings → Patching*, turn on **Expert mode**.
4. Patch YouTube or YouTube Music with the **recommended version** (the same for both sources;
   YouTube 21.20.405, YouTube Music 9.20.53 at the moment). On the patch selection screen, keep
   the recommended patches in the "Morphe Patches" tab and turn on "DLNA cast" in the
   "YT DLNA Patches" tab. Morphe warns about mixing sources; this one is meant for it.
   Without the official patches (including GmsCore support), "DLNA cast" refuses to patch.
5. To see the cast button, turn off Morphe's "Hide cast button" (on by default; YouTube:
   Player, YouTube Music: General). Casting from the share menu works either way.

Supported versions: the ones of the official patches that the regression tests cover, with
the same recommended and experimental ones.

## Settings

In Morphe's settings, under Player:

- **DLNA: Google Cast in the device list** (both apps, off): shows "Google Cast…" in the
  picker, leading to the app's own cast dialog.
- **DLNA: Album covers from music databases** (YouTube Music, on): looks up album covers and
  details on Deezer or iTunes; title and artist are sent to these services.
- **DLNA: Captions** (both apps, on): sends the video's captions to renderers that take them.
- **DLNA: Debug log** (both apps, off): records a log for bug reports and shows "Share debug
  log" in the picker.

## How it works and what leaves the phone

- The video streams are bound to the phone, so the phone serves them to the renderer through a
  small built-in proxy (Rust, port 8720 on the local network, open only while casting).
- Renderers are searched with SSDP while the app is in the foreground (once a minute).
- Requests to the renderer carry the phone's device name and the app (UPnP control point
  headers), so a renderer can show who is casting.
- With album covers on, YouTube Music sends title and artist to Deezer / iTunes.

<!-- PATCHES_START EXPANDED -->
> **[v0.1.7-alpha](https://github.com/ca-za/morphe-yt-dlna/releases/tag/v0.1.7-alpha)**&nbsp;&nbsp;•&nbsp;&nbsp;`dev`&nbsp;&nbsp;•&nbsp;&nbsp;11 patches total
<details open>
<summary>📦 YouTube&nbsp;&nbsp;•&nbsp;&nbsp;5 patches</summary>
<br>

**🎯 Supported versions:**

| 🧪&nbsp;21.42.241 | 🧪&nbsp;21.41.525 | 21.20.405 | 20.51.39 |
| :---: | :---: | :---: | :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [DLNA Sonos rooms](#dlna-sonos-rooms) | Sonos groups in the device list (one row per group, named after its room), a rooms dialog to add or remove rooms and set their volume, the group's volume on the volume keys, and the same for renderers that feed Sonos rooms themselves. |  |
| [DLNA cast](#dlna-cast) | Adds DLNA / UPnP casting to the cast button. Streams the current video or song to a DLNA media renderer on the local network. Requires the official Morphe patches (with GmsCore support). The cast button shows when a DLNA renderer is found; turn off Morphe's "Hide cast button" (on by default) to see it. Casting from the share menu works either way. | • Test hooks |
| [DLNA player controls](#dlna-player-controls) | While casting, YouTube keeps the player controls on screen (no hide timer), so the renderer can be controlled at a glance. A tap still hides and shows them. |  |
| [DLNA portrait lock](#dlna-portrait-lock) | Keeps the phone upright while casting (the video plays on the TV). |  |
| [DLNA renderer extras](#dlna-renderer-extras) | Captions, lyrics (YouTube Music) and SponsorBlock segments for renderers that ask for them (see the renderer guide). Plain renderers get the same as without it. |  |

</details>

<details open>
<summary>📦 YouTube Music&nbsp;&nbsp;•&nbsp;&nbsp;6 patches</summary>
<br>

**🎯 Supported versions:**

| 🧪&nbsp;9.42.52 | 🧪&nbsp;9.41.52 | 🧪&nbsp;9.40.51 | 9.20.53 | 9.15.51 |
| :---: | :---: | :---: | :---: | :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [DLNA Sonos rooms](#dlna-sonos-rooms) | Sonos groups in the device list (one row per group, named after its room), a rooms dialog to add or remove rooms and set their volume, the group's volume on the volume keys, and the same for renderers that feed Sonos rooms themselves. |  |
| [DLNA cast](#dlna-cast) | Adds DLNA / UPnP casting to the cast button. Streams the current video or song to a DLNA media renderer on the local network. Requires the official Morphe patches (with GmsCore support). The cast button shows when a DLNA renderer is found; turn off Morphe's "Hide cast button" (on by default) to see it. Casting from the share menu works either way. | • Test hooks |
| [DLNA cover page](#dlna-cover-page) | While casting, YouTube Music's player shows the album cover on its colors over the whole page (darker toward the bottom, fading to the next song's), like Spotify. |  |
| [DLNA portrait lock](#dlna-portrait-lock) | Keeps the phone upright while casting (the video plays on the TV). |  |
| [DLNA renderer extras](#dlna-renderer-extras) | Captions, lyrics (YouTube Music) and SponsorBlock segments for renderers that ask for them (see the renderer guide). Plain renderers get the same as without it. |  |
| [DLNA session notifications](#dlna-session-notifications) | Shows a notification when a DLNA renderer in the Wi-Fi plays a title cast with the "DLNA cast" patch from any phone ("Playing on <device>"). Searches briefly when the phone joins an unmetered network and about every 20 minutes on one, in a separate lightweight process; nothing runs in between. Resumes after a reboot without opening the app. Its buttons start a remote control (media session with lock screen controls and the renderer's volume on the volume keys) that steps aside as soon as the phone plays something itself. |  |

</details>

<!-- PATCHES_END -->

## Development

- `./gradlew :patches:buildAndroid` builds the patch bundle (needs Rust with the Android targets
  and the Android NDK, see `native/build-android.sh`).
- `tests/regression/run.py` runs the end-to-end regression tests on emulators, see
  [tests/regression/README.md](tests/regression/README.md).

This project is not affiliated with Google, YouTube or the Morphe project.
