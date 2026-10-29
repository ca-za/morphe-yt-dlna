# Regression tests

Automated end-to-end tests of the DLNA cast patch against real YouTube and YouTube Music
versions. For each APK the runner patches it (official Morphe patches + ours, with the
`testHooks` option), installs it on the phone emulator and checks every feature against the
DLNA renderer on the TV emulator. It needs no interaction while it runs.

## Run

```sh
# Every APK in a folder, any versions, YouTube and YouTube Music mixed:
tests/regression/run.py apks/versions/*.apkm

# Only some scenarios, without rebuilding the patch bundle:
tests/regression/run.py --skip-build --only start_cast,renderer_plays,phone_next apks/x.apkm
```

APKs: `.apkm`/`.xapk` bundles from APKMirror or plain `.apk`. Versions not listed as
compatible are patched anyway (`-f`); patches that fail are recorded in the report.

Versions run oldest to newest per app. Installing an older version over a newer one keeps the
app data (and the login) with `adb uninstall -k`; data of a much newer version may not suit an
older app, in which case YouTube Music asks to sign in again.

## Prerequisites (once)

- Phone AVD `morphe_phone35` and TV AVD `gtv34` (names configurable). The runner starts them
  when they are not running, and restarts them if they crash.
- The renderer app on the TV (default `io.github.jqssun.airplay`, see `--tv-activity`).
- MicroG on the phone and a Google account signed in to the patched YouTube Music.
- `tools/` with Morphe Desktop and the official patch bundle, as for `scripts/patch-and-install.sh`.

## Output

`reports/<time>/`:

- `summary.md`: one column per APK, one row per scenario.
- `<app>-<version>/report.md` and `report.json`: result, detail and evidence per scenario.
- `<app>-<version>/logcat.txt`: the whole phone log of the run.
- `<app>-<version>/patch.log`, `fail-<scenario>.png` screenshots of failures.

Statuses: ✅ pass, ❌ fail, ⚠️ env (the app or emulator misbehaves the same way without
the patch, needs a look), ℹ️ info (observation only), ⏭️ skip.

## Scenarios

| Scenario | Checks |
|---|---|
| launch | app starts the test video, test hooks answer, first-start dialogs dismissed |
| stream_capture | streams are captured with direct URLs |
| video_id_hook | the playing title is recognised (not ads or preloads) |
| cast_button | cast button shown (required in Music, info in YouTube) |
| start_cast | picker lists the renderer and starts the cast (cast button / share target) |
| renderer_plays | TV plays and its position advances |
| renderer_metadata | title, cover (album cover in Music), OpenHome playlist |
| local_reduced | phone gets the smallest streams / 144p |
| local_muted | app sound muted, device volume untouched |
| phone_pause / phone_play | phone controls the TV |
| tv_pause / tv_play | TV controls the phone |
| phone_seek | seeking on the phone moves the TV |
| volume_keys | phone volume keys change the TV volume |
| phone_next / tv_next | title changes in both directions (Music) |
| song_mode / video_mode | song/video switch reaches the TV (Music) |
| screen_lock | casting continues 30 s with the phone locked |
| cast_button_follows | the cast button hides without renderers and comes back (Music) |
| google_cast_link | "Google Cast…" in the picker follows its setting |
| manual_devices | renderers added by address are listed and can be removed |
| ui_controls | the player's own buttons reach the TV |
| scrubber_seek | seeking with the progress bar reaches the TV |
| shorts_ignored | a Short opened while casting plays on the phone, the TV plays on (YouTube) |
| audio_language | the default audio track of a multi-language video is cast (YouTube) |
| renderer_extras | extras document, captions and the `<desc>` link for renderers that ask; plain renderers unchanged (YouTube) |
| music_lyrics | lyrics in the extras and as a caption track (Music) |
| sponsorblock | SponsorBlock skips reach the TV (YouTube) |
| hls_playback / live_hls | HLS for renderers without DASH, live streams |
| avtransport_next | renderers without OpenHome get the next title and the phone follows (Music) |
| crossfade | Morphe's crossfade does not break the cast (Music) |
| media_session | the cast follows the app's media session (local controls, queue, playlist) |
| stop_cast | TV stops, phone gets sound and video back, notification gone |
| no_crash | no crash of the app during the run |

## Environment variables

| Variable | Effect |
|---|---|
| `YTDLNA_EXTRA_PATCHES` | optional patches to apply too, comma separated (e.g. `DLNA session notifications`) |
| `YTDLNA_KEEP_CAST` | leave the cast running after the run (for a manual look) |
| `YTDLNA_SHOT_AFTER` | screenshot and UI dump after each scenario |
| `YTDLNA_COVER_SHOT` | file for a screenshot of the cover in video mode |
| `YTDLNA_SHORT_VIDEO`, `YTDLNA_LIVE_VIDEO`, `YTDLNA_SPONSOR_VIDEO`, `YTDLNA_MULTI_AUDIO_VIDEO` | other test videos for those scenarios |

## Emulator crashes

The runner checks after every failed scenario, after a broken-off install or setup and at
the end of each APK whether an emulator went down or rebooted (boot id). If so, it restarts
the emulators and runs that APK again from the start; the report shows `Attempt: 2` and which
emulator was restarted. A second crash ends the APK with `env: emulator crashed twice`, and
the runner goes on with the next APK.

## How it works

- Test hooks (`TestHooks.java`, only in builds with the `testHooks` patch option) set up the
  renderer address, report state and send player commands through the app's media session.
- The renderer is checked and controlled directly from the host over SOAP (`renderer.py`).
- One live logcat stream per run; scenarios wait for log lines instead of sleeping.
- UI elements are found by resource id and text from `uiautomator` dumps, not by position.
