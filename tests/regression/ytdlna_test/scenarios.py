"""Regression scenarios. Each one checks one behaviour end to end, observing phone and TV.

Status values:
  pass  - works as required
  fail  - broken
  env   - could not be judged because of the test environment (e.g. the app itself misbehaves
          the same way without our patch); see the detail text
  info  - observation without a requirement (e.g. whether YouTube shows the cast button)
  skip  - not applicable, or a precondition failed
"""

import re
import time
from dataclasses import dataclass, field

from .control import Control
from .device import Device, LogStream, Ui
from .renderer import Renderer

PLAYING, PAUSED = 3, 2

TEST_VIDEO = {
    "yt": ("aqz-KE-bpKQ", "vnd.youtube:aqz-KE-bpKQ"),
    "music": ("dQw4w9WgXcQ", "https://music.youtube.com/watch?v=dQw4w9WgXcQ"),
}
SHARE_ACTIVITY = "app.ytdlna.extension.DlnaShareActivity"


class Skip(Exception):
    pass


@dataclass
class Result:
    status: str
    detail: str = ""
    evidence: dict = field(default_factory=dict)


@dataclass
class Context:
    app: str
    package: str
    phone: Device
    logs: LogStream
    ui: Ui
    control: Control
    renderer: Renderer
    renderer_device_url: str
    stream_host: str
    facts: dict = field(default_factory=dict)

    @property
    def video_id(self) -> str:
        return TEST_VIDEO[self.app][0]


def until(fn, timeout: float, interval: float = 0.5):
    """Calls fn until it returns something truthy; returns that or the last value."""
    deadline = time.monotonic() + timeout
    value = None
    while time.monotonic() < deadline:
        try:
            value = fn()
        except Exception as e:  # The renderer may briefly not answer while switching titles.
            value = None
            last_error = e
        if value:
            return value
        time.sleep(interval)
    return value


def renderer_state_is(ctx: Context, state: str, timeout: float) -> bool:
    return bool(until(lambda: ctx.renderer.state() == state, timeout))


# region Scenarios

def launch(ctx: Context) -> Result:
    """App starts, plays the test video and the test hooks answer."""
    ctx.phone.wake()
    ctx.phone.sh(f"am force-stop {ctx.package}")
    since = ctx.logs.mark()
    ctx.phone.sh(f"am start -a android.intent.action.VIEW -d '{TEST_VIDEO[ctx.app][1]}' -p {ctx.package}")
    dismissed = []
    for _ in range(6):
        if ctx.logs.wait(r"YtDlnaTest.*READY", 6, since):
            break
        dismissed += ctx.ui.dismiss_dialogs()
        # A dialog may have interrupted the start.
        ctx.phone.sh(f"am start -a android.intent.action.VIEW -d '{TEST_VIDEO[ctx.app][1]}' -p {ctx.package}")
    else:
        return Result("fail", "test hooks never got ready (no video loaded?)", {"dismissed": dismissed})
    ctx.control.cmd("setup", device=ctx.renderer_device_url, host=ctx.stream_host, albumArt="true")
    ctx.ui.dismiss_dialogs()
    return Result("pass", "", {"dismissed": dismissed})


def stream_capture(ctx: Context) -> Result:
    """The patch reads the app's streams, with direct URLs."""
    m = ctx.logs.wait(rf"Captured videoId={ctx.video_id} .* formats=(\d+) withUrl=(\d+) "
                      r"audioWithUrl=(\d+) videoWithUrl=(\d+)", 30)
    if not m:
        return Result("fail", "streams of the test video were not captured")
    formats, with_url, audio, video = (int(g) for g in m.groups())
    evidence = {"formats": formats, "withUrl": with_url, "audioWithUrl": audio, "videoWithUrl": video}
    # The renderer needs an audio track, and a video track for video (DASH), with direct URLs.
    if audio == 0 or video == 0:
        return Result("fail", f"too few direct stream URLs (audio {audio}, video {video}): "
                              "the app got SABR-only streams", evidence)
    return Result("pass", f"{with_url}/{formats} formats with URL (audio {audio}, video {video})", evidence)


def video_id_hook(ctx: Context) -> Result:
    """The patch knows which title the player plays (ads and preloads are told apart)."""
    if ctx.logs.wait(rf"Now playing {ctx.video_id}", 20):
        return Result("pass")
    return Result("fail", "video load hook did not report the test video")


def cast_button(ctx: Context) -> Result:
    """The cast button is shown. Required in YouTube Music; in YouTube it needs a Cast device on the network."""
    # Dialogs can come up a while after the start (Morphe's experimental version notice).
    ctx.ui.dismiss_dialogs()
    node = ctx.ui.find(rid="mini_player_media_route_button") or ctx.ui.find(rid="media_route_button")
    if ctx.app == "yt":
        return Result("info", "visible" if node else "not visible (expected without a Google Cast device)")
    if not node:
        mini = ctx.ui.find(rid="mini_player_view_pager_item") or ctx.ui.find(rid="mini_player")
        if mini:
            ctx.ui.tap(mini)
            node = ctx.ui.wait(5, rid="media_route_button")
    ctx.facts["cast_button"] = node
    return Result("pass" if node else "fail", "" if node else "no cast button")


def start_cast(ctx: Context) -> Result:
    """The DLNA picker opens (cast button in Music, share target in YouTube) and starts the cast."""
    # The picker needs the captured streams of the playing title.
    ctx.logs.wait(rf"Captured videoId={ctx.video_id} ", 30)
    ctx.ui.dismiss_dialogs()
    since = ctx.logs.mark()
    if ctx.app == "music":
        button = ctx.ui.find(rid="media_route_button") or ctx.ui.find(rid="mini_player_media_route_button")
        if not button:
            raise Skip("no cast button")
        ctx.ui.tap(button)
    else:
        ctx.phone.sh(f"am start -n {ctx.package}/{SHARE_ACTIVITY} -a android.intent.action.SEND "
                     f"-t text/plain --es android.intent.extra.TEXT 'https://youtu.be/{ctx.video_id}'")
    entry = ctx.ui.wait(12, contains=ctx.renderer.name)
    if not entry:
        return Result("fail", f"picker did not list '{ctx.renderer.name}'")
    ctx.ui.tap(entry)
    m = ctx.logs.wait(r"Loading (\S+) \(([^)]*)\) \"(.*)\" by \"(.*)\" on", 20, since)
    if not m:
        return Result("fail", "cast session did not load a title")
    ctx.facts["title"] = m.group(3)
    if ctx.app == "music" and not m.group(4):
        return Result("fail", "title loaded without artist (needed for the album cover)")
    renderer_kind = ctx.logs.wait(r"Using (\w+) on", 1, since)
    return Result("pass", f"{m.group(2)}, {renderer_kind.group(1) if renderer_kind else '?'}",
                  {"videoId": m.group(1), "format": m.group(2), "title": m.group(3)})


def renderer_plays(ctx: Context) -> Result:
    """The TV plays the title: its state is PLAYING and its position advances."""
    # The renderer may still play a title of an earlier session for a moment.
    title = ctx.facts.get("title", "")
    if title and not until(lambda: ctx.renderer.metadata().get("title") == title, 20):
        return Result("fail", f"renderer plays '{ctx.renderer.metadata().get('title')}', not '{title}'")
    if not renderer_state_is(ctx, "PLAYING", 25):
        return Result("fail", f"renderer state {ctx.renderer.state()}")
    # Right after the start the renderer may still buffer at the start position.
    p1 = ctx.renderer.position_ms()
    p2 = until(lambda: (lambda p: p if p >= p1 + 2000 else None)(ctx.renderer.position_ms()), 12)
    evidence = {"positions": [p1, p2 or ctx.renderer.position_ms()]}
    if not p2:
        return Result("fail", "renderer position does not advance", evidence)
    return Result("pass", f"{p1 / 1000:.0f}s -> {p2 / 1000:.0f}s", evidence)


def renderer_metadata(ctx: Context) -> Result:
    """Title, artist and cover are sent to the TV (album cover from Deezer/iTunes in Music)."""
    meta = until(lambda: ctx.renderer.metadata() if ctx.renderer.metadata().get("title") else None, 10) or {}
    problems = []
    if not meta.get("title"):
        problems.append("no title")
    if not meta.get("art"):
        problems.append("no cover")
    if ctx.app == "music" and meta.get("art") and not re.search(r"dzcdn|mzstatic", meta["art"]):
        problems.append("cover is not an album cover")
    if not meta.get("artist"):
        problems.append("no artist" + (" (channel)" if ctx.app == "yt" else ""))
    if not meta.get("duration"):
        problems.append("no duration")
    if ctx.app == "music":
        # Album and year come from the app or the music database (the test title is well known).
        problems += [f"no {name}" for name in ("album", "date") if not meta.get(name)]
    playlist = ctx.renderer.playlist_size()
    meta["playlistSize"] = playlist
    if ctx.renderer.openhome and ctx.app == "music":
        if playlist < 2:
            problems.append(f"OpenHome playlist has {playlist} entries")
        # "Up next": the next entry has its title, and a length once known (exact or estimated).
        upcoming = until(lambda: (lambda m: m if m.get("title") and m.get("duration") else None)(
            ctx.renderer.next_metadata()), 10) or ctx.renderer.next_metadata()
        meta["next"] = upcoming
        if not upcoming.get("title"):
            problems.append("next entry without title")
        elif not upcoming.get("duration"):
            problems.append("next entry without duration")
    detail = ", ".join(problems) or " · ".join(
        v for v in (meta.get("title"), meta.get("artist"), meta.get("album"), meta.get("date"), meta.get("publisher")) if v)
    return Result("fail" if problems else "pass", detail, meta)


def local_reduced(ctx: Context) -> Result:
    """The phone neither downloads nor decodes the video while casting."""
    evidence = {}
    if ctx.app == "yt":
        ok = ctx.logs.wait(r"lowered to 144p", 10)
        formats = ctx.logs.find_all(r"onFormatStreamChange")
        evidence["lastFormat"] = formats[-1][-160:] if formats else ""
        return Result("pass" if ok else "fail", "quality lowered" if ok else "quality not lowered", evidence)
    ok = ctx.logs.wait(r"smallest tracks only", 15)
    formats = ctx.logs.find_all(r"onFormatStreamChange")
    evidence["lastFormat"] = formats[-1][-160:] if formats else ""
    return Result("pass" if ok else "fail", "reduced streams" if ok else "streams not reduced", evidence)


def local_muted(ctx: Context) -> Result:
    """No sound on the phone while casting; the device volume is untouched."""
    status = ctx.control.status()
    pid = ctx.phone.pid(ctx.package)
    audio = ctx.phone.sh("dumpsys audio")
    players = [l.strip() for l in audio.splitlines() if f"/{pid} " in l and "AudioTrack" in l]
    evidence = {"muted": status.get("muted"), "players": players[:3]}
    if not status.get("muted"):
        return Result("fail", "app audio not muted", evidence)
    return Result("pass", "", evidence)


def phone_pause(ctx: Context) -> Result:
    """Pause on the phone pauses the TV."""
    # Only a playing phone can be paused: right after the cast starts, the app may still
    # reload the title (and shows itself as paused meanwhile).
    if not until(lambda: ctx.control.local_state() == PLAYING, 15):
        return Result("fail", f"phone not playing before the pause (state {ctx.control.local_state()})")
    ctx.control.local("pause")
    ok = renderer_state_is(ctx, "PAUSED_PLAYBACK", 8)
    return Result("pass" if ok else "fail", f"renderer {ctx.renderer.state()}")


def phone_play(ctx: Context) -> Result:
    """Play on the phone resumes the TV."""
    ctx.control.local("play")
    ok = renderer_state_is(ctx, "PLAYING", 8)
    if ok:
        return Result("pass")
    local = ctx.control.local_state()
    if local != PLAYING:
        return Result("env", f"the app itself did not resume (local state {local}); "
                             "check the same without the DLNA patch")
    return Result("fail", f"phone plays, renderer {ctx.renderer.state()}")


def _tv_command(ctx: Context, name: str, send, event: str, phone_ok) -> Result:
    """Sends a command on the TV and waits for the phone to follow.

    On failure it waits longer to tell a late renderer event (slow renderer) from a lost one,
    and records both times in the evidence."""
    since = ctx.logs.mark()
    ctx.phone.sh(f"log -t YtDlnaTest 'TV {name} sent'")
    send()
    if until(lambda: phone_ok(ctx.control.local_state()), 8):
        return Result("pass")
    seen = ctx.logs.wait(rf"Event TransportState={event}", 15, since)
    sent = ctx.logs.wait(rf"TV {name} sent", 0, since)
    # From the log times: when the event arrived, not when it was looked for.
    late = _seconds_between(sent.string, seen.string) if seen and sent else None
    followed = phone_ok(ctx.control.local_state())
    evidence = {"eventAfterSeconds": late, "phoneFollowedLate": followed,
                "renderer": ctx.renderer.state(), "phone": ctx.control.local_state()}
    if not seen:
        detail = f"no {event} event from the renderer within 23s"
    elif followed:
        detail = f"renderer event only after {late}s, phone then followed"
    else:
        detail = f"event after {late}s, phone did not follow (state {evidence['phone']})"
    return Result("fail", detail, evidence)


def _seconds_between(first: str, second: str) -> "float | None":
    """Seconds between two threadtime logcat lines (same day)."""
    def seconds(line: str):
        m = re.search(r"(\d\d):(\d\d):(\d\d)\.(\d{3})", line)
        return int(m[1]) * 3600 + int(m[2]) * 60 + int(m[3]) + int(m[4]) / 1000 if m else None
    a, b = seconds(first), seconds(second)
    return round(b - a, 1) if a is not None and b is not None else None


def tv_pause(ctx: Context) -> Result:
    """Pause on the TV pauses the phone."""
    # Let the previous play settle first: the renderer stalls on commands in quick succession.
    if not renderer_state_is(ctx, "PLAYING", 10):
        return Result("fail", f"renderer not playing before the test ({ctx.renderer.state()})")
    time.sleep(2)
    return _tv_command(ctx, "pause", ctx.renderer.pause, "Paused", lambda s: s == PAUSED)


def tv_play(ctx: Context) -> Result:
    """Play on the TV resumes the phone."""
    result = _tv_command(ctx, "play", ctx.renderer.play, "Playing", lambda s: s in (PLAYING, 6))
    # The event arrived and the session resumed the phone, but the app does not play on:
    # YouTube in the emulator does not resume after a pause, also without our patch.
    if result.status == "fail" and result.evidence.get("eventAfterSeconds") is not None \
            and ctx.renderer.state() == "PLAYING" and ctx.logs.find_all("Resumed on the renderer"):
        result.status = "env"
    return result


def phone_seek(ctx: Context) -> Result:
    """Seeking on the phone moves the TV to the same position."""
    target = ctx.renderer.position_ms() + 60_000
    ctx.control.local("seek", ms=str(target))
    position = until(lambda: (lambda p: p if abs(p - target) < 6000 else None)(ctx.renderer.position_ms()), 10)
    return Result("pass" if position else "fail", f"target {target // 1000}s, renderer {ctx.renderer.position_ms() // 1000}s")


def volume_keys(ctx: Context) -> Result:
    """The phone's volume keys change the TV volume."""
    # Keys go to the focused window: a dialog over the app would get them.
    ctx.ui.dismiss_dialogs()
    before = ctx.renderer.volume()
    ctx.phone.key("KEYCODE_VOLUME_UP")
    after = until(lambda: (lambda v: v if v != before else None)(ctx.renderer.volume()), 5)
    ctx.phone.key("KEYCODE_VOLUME_DOWN")
    return Result("pass" if after else "fail", f"{before} -> {after if after else ctx.renderer.volume()}")


def phone_next(ctx: Context) -> Result:
    """Next title on the phone plays it on the TV."""
    before = ctx.control.status().get("currentVideoId")
    since = ctx.logs.mark()
    ctx.control.local("next")
    m = ctx.logs.wait(r"Loading (\S+) \(", 20, since)
    if not m or m.group(1) == before:
        return Result("fail", "renderer did not get the next title")
    ok = renderer_state_is(ctx, "PLAYING", 10)
    return Result("pass" if ok else "fail", f"{before} -> {m.group(1)}, renderer {ctx.renderer.state()}")


def tv_next(ctx: Context) -> Result:
    """Next title on the TV (OpenHome playlist) moves the phone along."""
    if not ctx.renderer.openhome:
        raise Skip("renderer has no playlist")
    before = ctx.control.status().get("currentVideoId")
    ctx.renderer.next()
    status = ctx.control.wait_status(lambda s: s.get("currentVideoId") not in (None, before), 20)
    after = status.get("currentVideoId")
    if after == before:
        return Result("fail", "phone did not follow the renderer")
    ok = renderer_state_is(ctx, "PLAYING", 10)
    return Result("pass" if ok else "fail", f"{before} -> {after}, renderer {ctx.renderer.state()}")


def _switch(ctx: Context, icon: str, expect: str) -> Result:
    node = ctx.ui.find(rid=icon)
    if not node:
        mini = ctx.ui.find(rid="mini_player_view_pager_item")
        if mini:
            ctx.ui.tap(mini)
        node = ctx.ui.wait(5, rid=icon)
    if not node:
        raise Skip("song/video switch not found")
    since = ctx.logs.mark()
    ctx.ui.tap(node)
    m = ctx.logs.wait(rf"Switching the renderer to {expect}", 12, since)
    if not m:
        return Result("fail", f"renderer not switched to {expect}")
    # Music videos are announced as such (renderers may show a music video screen).
    wanted = "object.item.audioItem" if expect == "audio" else "object.item.videoItem.musicVideoClip"
    meta = until(lambda: (lambda md: md if md["class"].startswith(wanted) else None)(ctx.renderer.metadata()),
                 12) or ctx.renderer.metadata()
    playing = renderer_state_is(ctx, "PLAYING", 10)
    kind_ok = meta.get("class", "").startswith(wanted)
    return Result("pass" if kind_ok and playing else "fail",
                  f"{meta.get('class')}, renderer {ctx.renderer.state()}", meta)


def song_mode(ctx: Context) -> Result:
    """YouTube Music's song mode sends audio only to the TV."""
    return _switch(ctx, "song_icon", "audio")


def video_mode(ctx: Context) -> Result:
    """YouTube Music's video mode sends video to the TV."""
    return _switch(ctx, "video_icon", "video")


def screen_lock(ctx: Context) -> Result:
    """Casting goes on with the phone locked."""
    if ctx.renderer.state() != "PLAYING":
        ctx.renderer.play()
        renderer_state_is(ctx, "PLAYING", 8)
    p1 = ctx.renderer.position_ms()
    ctx.phone.key("KEYCODE_SLEEP")
    time.sleep(30)
    state, p2 = ctx.renderer.state(), ctx.renderer.position_ms()
    ctx.phone.wake()
    advanced = (p2 - p1) / 1000
    ok = state == "PLAYING" and advanced >= 20
    return Result("pass" if ok else "fail", f"renderer {state}, advanced {advanced:.0f}s in 30s locked")


def stop_cast(ctx: Context) -> Result:
    """Stopping ends playback on the TV and gives sound and video back to the phone."""
    since = ctx.logs.mark()
    ctx.control.cmd("stop")
    stopped = renderer_state_is(ctx, "STOPPED", 8)
    unmuted = ctx.logs.wait(r"Unmuted", 8, since)
    status = ctx.control.status()
    notification = "channel=ytdlna_cast" in ctx.phone.sh("dumpsys notification --noredact")
    problems = [p for p, bad in [("renderer not stopped", not stopped), ("not unmuted", not unmuted),
                                 ("session still active", status.get("casting")),
                                 ("cast notification left", notification)] if bad]
    return Result("fail" if problems else "pass", ", ".join(problems))


def avtransport_next(ctx: Context) -> Result:
    """Renderers without OpenHome get the next title (SetNextAVTransportURI) and the phone follows."""
    ctx.control.cmd("setup", avTransportOnly="true")
    ctx.renderer.use_openhome = False
    try:
        started = start_cast(ctx)
        if started.status != "pass":
            return Result("fail", f"cast start: {started.detail}")
        if "AvTransportRenderer" not in started.detail:
            return Result("fail", f"not cast as plain AVTransport ({started.detail})")
        if not renderer_state_is(ctx, "PLAYING", 25):
            return Result("fail", f"renderer state {ctx.renderer.state()}")
        current = ctx.renderer.metadata(avtransport=True)
        upcoming = until(lambda: (lambda m: m if m.get("uri") else None)(ctx.renderer.next_metadata(avtransport=True)),
                         15) or {}
        evidence = {"current": current, "next": upcoming}
        if not upcoming.get("uri"):
            return Result("fail", "no next title on the renderer", evidence)
        problems = [f"next title without {name}" for name in ("title", "duration") if not upcoming.get(name)]
        # Near the end the renderer moves on to the next title by itself; the phone follows.
        since = ctx.logs.mark()
        duration = current.get("duration", "")
        h, m, sec = (duration.split(".")[0] or "0:0:0").split(":")
        total = (int(h) * 3600 + int(m) * 60 + int(sec)) * 1000
        if total <= 10_000:
            return Result("fail", f"current title has no usable duration ({duration})", evidence)
        ctx.renderer.seek_ms(total - 6000)
        moved = ctx.logs.wait(r"Renderer moved on to the next title (\S+)", 30, since)
        followed = ctx.logs.wait(r"Phone follows the renderer to (\S+)", 15, since)
        playing = renderer_state_is(ctx, "PLAYING", 25)
        title = until(lambda: (lambda t: t if t == upcoming.get("title") else None)(
            ctx.renderer.metadata(avtransport=True).get("title")), 15)
        evidence.update({"moved": bool(moved), "followed": bool(followed), "playing": playing})
        problems += [p for p, bad in [("renderer did not move on", not moved), ("phone did not follow", not followed),
                                      ("renderer not playing the next title", not (playing and title))] if bad]
        return Result("fail" if problems else "pass",
                      ", ".join(problems) or f"next: {upcoming.get('title')} ({upcoming.get('duration')})", evidence)
    finally:
        ctx.renderer.use_openhome = True
        ctx.control.cmd("setup", avTransportOnly="false")
        try:
            ctx.control.cmd("stop")
        except Exception:
            pass


def no_crash(ctx: Context) -> Result:
    """The app did not crash during the run."""
    crashes = ctx.logs.find_all(r"FATAL EXCEPTION|AndroidRuntime.*" + re.escape(ctx.package))
    if not crashes:
        return Result("pass")
    stack = ctx.logs.find_all(r"AndroidRuntime")
    ours = [line for line in stack if "app.ytdlna" in line]
    # Without our code in the stack trace it is the app's own crash: to check, not a patch failure.
    status = "fail" if ours else "env"
    reason = next((line for line in stack if "Exception" in line and "FATAL" not in line), crashes[0])
    return Result(status, ("" if ours else "not in our code: ") + reason.split("AndroidRuntime: ")[-1][:160],
                  {"lines": stack[:30]})

# endregion


# Order matters: later scenarios build on the state earlier ones leave.
SCENARIOS = [
    ("launch", launch, {"yt", "music"}),
    ("stream_capture", stream_capture, {"yt", "music"}),
    ("video_id_hook", video_id_hook, {"yt", "music"}),
    ("cast_button", cast_button, {"yt", "music"}),
    ("start_cast", start_cast, {"yt", "music"}),
    ("renderer_plays", renderer_plays, {"yt", "music"}),
    ("renderer_metadata", renderer_metadata, {"yt", "music"}),
    ("local_reduced", local_reduced, {"yt", "music"}),
    ("local_muted", local_muted, {"yt", "music"}),
    ("phone_pause", phone_pause, {"yt", "music"}),
    ("phone_play", phone_play, {"yt", "music"}),
    ("tv_pause", tv_pause, {"yt", "music"}),
    ("tv_play", tv_play, {"yt", "music"}),
    ("phone_seek", phone_seek, {"yt", "music"}),
    ("volume_keys", volume_keys, {"yt", "music"}),
    ("phone_next", phone_next, {"music"}),
    ("tv_next", tv_next, {"music"}),
    ("song_mode", song_mode, {"music"}),
    ("video_mode", video_mode, {"music"}),
    ("screen_lock", screen_lock, {"yt", "music"}),
    ("stop_cast", stop_cast, {"yt", "music"}),
    ("avtransport_next", avtransport_next, {"music"}),
    ("no_crash", no_crash, {"yt", "music"}),
]
# Without these, the remaining scenarios can not run.
REQUIRED = {"launch", "start_cast"}
