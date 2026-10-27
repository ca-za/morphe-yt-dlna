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
- **YouTube Music**: song and video mode reach the TV.

## Install

> **Alpha**: releases are pre-releases (`1.0.0-alpha.N`) for now.

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

## How it works and what leaves the phone

- The video streams are bound to the phone, so the phone serves them to the renderer through a
  small built-in proxy (Rust, port 8720 on the local network, open only while casting).
- Renderers are searched with SSDP while the app is in the foreground (once a minute).
- Requests to the renderer carry the phone's device name and the app (UPnP control point
  headers), so a renderer can show who is casting.
- With album covers on, YouTube Music sends title and artist to Deezer / iTunes.

<!-- PATCHES_START EXPANDED -->
> **[v1.0.0-alpha.2](https://github.com/ca-za/morphe-yt-dlna/releases/tag/v1.0.0-alpha.2)**&nbsp;&nbsp;•&nbsp;&nbsp;`dev`&nbsp;&nbsp;•&nbsp;&nbsp;2 patches total
<details open>
<summary>📦 YouTube&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 🧪&nbsp;21.42.241 | 🧪&nbsp;21.41.525 | 21.20.405 | 20.51.39 |
| :---: | :---: | :---: | :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [DLNA cast](#dlna-cast) | Adds DLNA / UPnP casting to the cast button. Streams the current video or song to a DLNA media renderer on the local network. Requires the official Morphe patches (with GmsCore support). The cast button shows when a DLNA renderer is found; turn off Morphe's "Hide cast button" (on by default) to see it. Casting from the share menu works either way. | • Test hooks |

</details>

<details open>
<summary>📦 YouTube Music&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 🧪&nbsp;9.42.52 | 🧪&nbsp;9.41.52 | 🧪&nbsp;9.40.51 | 9.20.53 | 9.15.51 |
| :---: | :---: | :---: | :---: | :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [DLNA cast](#dlna-cast) | Adds DLNA / UPnP casting to the cast button. Streams the current video or song to a DLNA media renderer on the local network. Requires the official Morphe patches (with GmsCore support). The cast button shows when a DLNA renderer is found; turn off Morphe's "Hide cast button" (on by default) to see it. Casting from the share menu works either way. | • Test hooks |

</details>

<!-- PATCHES_END -->

## Development

- `./gradlew :patches:buildAndroid` builds the patch bundle (needs Rust with the Android targets
  and the Android NDK, see `native/build-android.sh`).
- `tests/regression/run.py` runs the end-to-end regression tests on emulators, see
  [tests/regression/README.md](tests/regression/README.md).

This project is not affiliated with Google, YouTube or the Morphe project.
