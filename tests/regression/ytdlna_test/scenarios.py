"""Regression scenarios. Each one checks one behaviour end to end, observing phone and TV.

Status values:
  pass  - works as required
  fail  - broken
  env   - could not be judged because of the test environment (e.g. the app itself misbehaves
          the same way without our patch); see the detail text
  info  - observation without a requirement (e.g. whether YouTube shows the cast button)
  skip  - not applicable, or a precondition failed
"""

import json
import os
import re
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

from .control import Control
from .device import Node, Device, LogStream, Ui
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
        # A scenario may switch to another video (the live stream).
        return self.facts.get("video_id") or TEST_VIDEO[self.app][0]


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
    started = _start_app(ctx)
    if started.status != "pass":
        return started
    # Morphe hides the cast button by default; it reads its settings when the app starts.
    # Crossfade (Music) off: a run cut short in the crossfade scenario leaves it on, and with it
    # Music allows no video mode.
    extras = {"crossfade": "false"} if ctx.app == "music" else {}
    if ctx.control.cmd("setup", showCastButton="true", **extras).get("restart"):
        started = _start_app(ctx)
        if started.status != "pass":
            return started
    ctx.control.cmd("setup", device=ctx.renderer_device_url, host=ctx.stream_host, albumArt="true")
    closed = ctx.ui.dismiss_dialogs()
    if any(c in ("Restart", "Neu starten") for c in closed):
        # Morphe's restart dialog ends the app; it comes back without the test video.
        started = _start_app(ctx)
        if started.status != "pass":
            return started
        ctx.control.cmd("setup", device=ctx.renderer_device_url, host=ctx.stream_host, albumArt="true")
        started.detail = (started.detail + " (restarted by Morphe's dialog)").strip()
    return started


def _start_app(ctx: Context, retries: int = 3) -> Result:
    ctx.phone.sh(f"am force-stop {ctx.package}")
    # Granted up front: YouTube sets up its notification channels once it may notify.
    ctx.phone.sh(f"pm grant {ctx.package} android.permission.POST_NOTIFICATIONS")
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
    # YouTube's background player can post its notification before the app created the channel
    # for it on a fresh install; Android then ends the app right after the start. Once more.
    time.sleep(2)
    if retries > 0 and ctx.logs.find_all(r"CannotPostForegroundServiceNotificationException", since):
        time.sleep(2)
        again = _start_app(ctx, retries - 1)
        again.detail = (again.detail + " (restarted after YouTube's notification crash)").strip()
        return again
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
    """The cast button shows when a renderer is found, and opens the DLNA picker."""
    # Dialogs can come up a while after the start (Morphe's experimental version notice).
    ctx.ui.dismiss_dialogs()
    # The renderer is found in the background; ask for a search instead of waiting a minute.
    ctx.control.cmd("rescan")
    if ctx.control.wait_status(lambda st: st.get("renderers", 0) > 0, 10).get("renderers", 0) == 0:
        return Result("fail", "renderer not found in the background")
    node = _find_cast_button(ctx)
    if not node and ctx.app == "music":
        mini = ctx.ui.find(rid="mini_player_view_pager_item") or ctx.ui.find(rid="mini_player")
        if mini:
            ctx.ui.tap(mini)
            node = until(lambda: _find_cast_button(ctx), 5)
    if not node:
        return Result("fail", "no cast button")
    evidence = {"button": node.resource_id, "bounds": node.bounds}
    if ctx.app == "yt":
        # YouTube's cast button (21.4x: an entry point button, not a MediaRouteButton) opens ours.
        # The player fades its buttons out after a few seconds: measure again before a second try.
        opened = None
        for _ in range(2):
            # A sheet over the player (bedtime reminder) would take the tap.
            if ctx.ui.dismiss_dialogs():
                node = _find_cast_button(ctx) or node
            ctx.ui.tap(node)
            opened = ctx.ui.wait(6, text=ctx.renderer.name)
            if opened:
                break
            node = _find_cast_button(ctx) or node
        _close_picker(ctx)
        ctx.control.local("play")
        if not opened:
            return Result("fail", "the cast button did not open the DLNA picker", evidence)
    return Result("pass", node.resource_id, evidence)


def _find_cast_button(ctx: Context):
    """A cast button that is really shown (with all its parents, not faded out), measured in the app."""
    def shown():
        for b in ctx.control.cmd("castButtons").get("buttons", []):
            if b["shown"] and b["width"] > 0 and b["height"] > 0 and b.get("alpha", 1) > 0.5:
                return Node(b["id"] or b["class"], "", "", (b["x"], b["y"], b["x"] + b["width"], b["y"] + b["height"]),
                            enabled=b["enabled"])
        return None
    button = shown()
    if not button and ctx.app == "yt":
        # The player shows its buttons after a tap on the video and fades them out again,
        # except while paused.
        ctx.control.local("pause")
        time.sleep(1)
        button = shown()
        if not button:
            ctx.phone.tap(540, 300)
            button = until(shown, 3, interval=0.3)
    return button


def _close_picker(ctx: Context) -> None:
    """Closes the DLNA picker if it is open (Back without it would minimize YouTube's player)."""
    if _picker_open(ctx):
        ctx.phone.key("KEYCODE_BACK")
        time.sleep(0.5)


def _picker_open(ctx: Context) -> bool:
    return bool(ctx.ui.find(text=ctx.renderer.name) or ctx.ui.find(contains="by address")
                or ctx.ui.find(contains="per Adresse"))


def cast_button_follows(ctx: Context) -> Result:
    """The cast button hides when no renderer is reachable and shows again when one is.
    Other renderers in the network (found by the search) keep it shown: then only the test
    renderer's leaving and coming back is checked."""
    unreachable = "http://10.0.2.2:9/description.xml"
    test_renderer = ctx.renderer_device_url
    ctx.control.cmd("setup", device=unreachable)
    try:
        ctx.control.cmd("rescan")
        status = ctx.control.wait_status(lambda st: test_renderer not in st.get("rendererLocations", [test_renderer]), 12)
        gone = test_renderer not in status.get("rendererLocations", [test_renderer])
        others = [loc for loc in status.get("rendererLocations", []) if loc != unreachable]
        # With Google Cast devices (as the app reports them) or other renderers the button stays.
        hidden = status.get("googleCast") or others or until(
            lambda: not (ctx.ui.find(rid="mini_player_media_route_button") or ctx.ui.find(rid="media_route_button")), 8)
    finally:
        ctx.control.cmd("setup", device=ctx.renderer_device_url)
    ctx.control.cmd("rescan")
    back = test_renderer in ctx.control.wait_status(
        lambda st: test_renderer in st.get("rendererLocations", []), 12).get("rendererLocations", [])
    shown = until(lambda: ctx.ui.find(rid="mini_player_media_route_button") or ctx.ui.find(rid="media_route_button"), 8)
    problems = [p for p, bad in [("renderer still listed", not gone), ("button not hidden", not hidden),
                                 ("renderer not found again", not back), ("button not shown again", not shown)] if bad]
    return Result("fail" if problems else "pass", ", ".join(problems) or
                  (f"{len(others)} other renderers in the network keep the button" if others else ""),
                  {"others": others})


#: Title of the apps' own Google Cast dialog (androidx MediaRouter chooser), English and German.
GOOGLE_CAST_DIALOG = ["Select a device", "Gerät auswählen"]


def _open_picker(ctx: Context):
    """Opens the DLNA picker and waits for the renderer in it."""
    if ctx.app == "music":
        button = _find_cast_button(ctx)
        if button:
            ctx.ui.tap(button)
    else:
        # YouTube: through the share menu, where no cast button is involved.
        ctx.phone.sh(f"am start -n {ctx.package}/{SHARE_ACTIVITY} -a android.intent.action.SEND "
                     f"-t text/plain --es android.intent.extra.TEXT 'https://youtu.be/{ctx.video_id}'")
    return ctx.ui.wait(20, text=ctx.renderer.name)


def google_cast_link(ctx: Context) -> Result:
    """The link to the app's Google Cast dialog in the DLNA picker follows its setting (off by default)."""
    open_picker = lambda: _open_picker(ctx)

    ctx.control.cmd("setup", googleCastLink="false")
    if not open_picker():
        return Result("fail", "picker did not open")
    off = ctx.ui.find(text="Google Cast…")
    _close_picker(ctx)
    time.sleep(1)
    ctx.control.cmd("setup", googleCastLink="true")
    try:
        if not open_picker():
            return Result("fail", "picker did not open with the link on")
        link = ctx.ui.find(text="Google Cast…")
        dialog = None
        if link:
            ctx.ui.tap(link)
            # The app's dialog has the same title as ours, but none of our entries.
            dialog = until(lambda: None if _picker_open(ctx) else
                           next((t for t in GOOGLE_CAST_DIALOG if ctx.ui.find(contains=t)), None), 15)
        if dialog:
            ctx.phone.key("KEYCODE_BACK")
        else:
            _close_picker(ctx)
    finally:
        ctx.control.cmd("setup", googleCastLink="false")
    problems = [p for p, bad in [("link shown while off", off), ("link missing while on", not link),
                                 ("the link did not open the Google Cast dialog", link and not dialog)] if bad]
    return Result("fail" if problems else "pass", ", ".join(problems) or f"opens \"{dialog}\"")


def manual_devices(ctx: Context) -> Result:
    """Renderers added by address are marked as such, also when unreachable, and can be removed."""
    unreachable = "http://10.0.2.2:9/description.xml"
    ctx.control.cmd("setup", device=f"{ctx.renderer_device_url}|{unreachable}")
    since = ctx.logs.mark()
    try:
        if not _open_picker(ctx):
            return Result("fail", "picker did not open")
        marked = ctx.ui.wait(10, contains=(" · manual · ", " · manuell · "))
        listed = ctx.ui.wait(15, contains=("Manual · unreachable", "Manuell · nicht erreichbar"))
        removed = renderer_kept = False
        confirm = None
        tries = []
        if listed:
            # A tap while the list is rebuilt (a renderer answering) is lost: try again.
            for _ in range(3):
                nodes = ctx.ui.dump()
                dead = ctx.ui.find(contains=("Manual · unreachable", "Manuell · nicht erreichbar"), nodes=nodes)
                buttons = [n for n in nodes if n.desc in ("Remove", "Entfernen")]
                tries.append({"row": dead and dead.bounds, "buttons": [b.bounds for b in buttons]})
                if not dead or not buttons:
                    break
                # The delete button in the same row as the unreachable entry.
                ctx.ui.tap(min(buttons, key=lambda n: abs(n.center[1] - dead.center[1])))
                # Dumps are slow while the search spinner turns: wait long enough for the dialog.
                confirm = ctx.ui.wait(15, text=("Remove", "Entfernen"))
                if confirm:
                    break
        if confirm:
            ctx.ui.tap(confirm)
            removed = bool(ctx.logs.wait(r"Removed saved device " + re.escape(unreachable), 5, since))
            gone = until(lambda: not ctx.ui.find(contains=("Manual · unreachable", "Manuell · nicht erreichbar"), nodes=ctx.ui.dump()), 10)
            removed = removed and bool(gone)
            renderer_kept = bool(ctx.ui.find(text=ctx.renderer.name))
        _close_picker(ctx)
    finally:
        ctx.control.cmd("setup", device=ctx.renderer_device_url)
    problems = [p for p, bad in [("renderer not marked as manual", not marked),
                                 ("unreachable entry not listed", not listed),
                                 ("unreachable entry not removed", listed and not removed),
                                 ("renderer gone after removing another entry", removed and not renderer_kept)] if bad]
    return Result("fail" if problems else "pass", ", ".join(problems), {"tries": tries})


def start_cast(ctx: Context) -> Result:
    """The DLNA picker opens (cast button in Music, share target in YouTube) and starts the cast."""
    # The picker needs the captured streams of the playing title.
    ctx.logs.wait(rf"Captured videoId={ctx.video_id} ", 30)
    ctx.ui.dismiss_dialogs()
    # The scenarios before take a while: from the start again, so the title does not end
    # during the ones after (Music's test song is 3:33 long).
    if ctx.video_id == TEST_VIDEO[ctx.app][0]:
        position = ctx.control.status().get("local", {}).get("position", 0)
        if position > 30_000:
            ctx.control.local("seek", ms="5000")
            time.sleep(1.5)
    since = ctx.logs.mark()
    tapped = {}
    if ctx.app == "music":
        find = lambda: ctx.ui.find(rid="media_route_button") or ctx.ui.find(rid="mini_player_media_route_button")
        button = find()
        if not button:
            raise Skip("no cast button")
        # A disabled button ignores taps (the app disables it without Google Cast devices).
        button = until(lambda: (lambda b: b if b and b.enabled else None)(find()), 5) or find() or button
        tapped = {"button": button.resource_id, "bounds": button.bounds, "enabled": button.enabled}
        ctx.ui.tap(button)
    else:
        ctx.phone.sh(f"am start -n {ctx.package}/{SHARE_ACTIVITY} -a android.intent.action.SEND "
                     f"-t text/plain --es android.intent.extra.TEXT 'https://youtu.be/{ctx.video_id}'")
    entry = ctx.ui.wait(20, text=ctx.renderer.name)
    if not entry:
        opened = ctx.logs.find_all(r"Could not show DLNA picker", since) or ctx.ui.find(contains="Google Cast")
        return Result("fail", f"picker did not list '{ctx.renderer.name}'"
                      + ("" if opened else " (the picker did not open)"), tapped)
    ctx.ui.tap(entry)
    m = ctx.logs.wait(r"Loading (\S+) \(([^)]*)\) \"(.*)\" by \"(.*)\" on", 10, since)
    if not m:
        # A tap while the sheet still slides in is lost: once more if the picker is still open.
        again = ctx.ui.find(text=ctx.renderer.name)
        if again:
            ctx.ui.tap(again)
        m = ctx.logs.wait(r"Loading (\S+) \(([^)]*)\) \"(.*)\" by \"(.*)\" on", 15, since)
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
    # The app's queue follows the title by a few seconds.
    playlist = ctx.renderer.playlist_size()
    if ctx.renderer.openhome and ctx.app == "music" and playlist < 2:
        playlist = until(lambda: (lambda n: n if n >= 2 else None)(ctx.renderer.playlist_size()), 10) \
            or ctx.renderer.playlist_size()
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
        # The length is known once the app loaded the title, or from the music database for
        # songs; a plain video further down the queue has none yet.
        meta["nextDuration"] = upcoming.get("duration") or "unknown"
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
    # Right after the start the phone reloads its title and puts it back at its position;
    # a seek in those seconds is undone with it (known limit). Seek once that is done.
    ctx.control.wait_status(lambda st: not (st.get("session") or {}).get("reloading"), 12)
    target = ctx.renderer.position_ms() + 60_000
    ctx.control.local("seek", ms=str(target))
    position = until(lambda: (lambda p: p if abs(p - target) < 6000 else None)(ctx.renderer.position_ms()), 10)
    return Result("pass" if position else "fail", f"target {target // 1000}s, renderer {ctx.renderer.position_ms() // 1000}s")


def volume_keys(ctx: Context) -> Result:
    """The phone's volume keys change the TV volume."""
    # Keys go to the focused window: a dialog over the app would get them.
    ctx.ui.dismiss_dialogs()
    before = ctx.renderer.volume()
    # At the top already (a real TV may be there): down first, then back up.
    first, back = ("KEYCODE_VOLUME_DOWN", "KEYCODE_VOLUME_UP") if before >= 95 else ("KEYCODE_VOLUME_UP", "KEYCODE_VOLUME_DOWN")
    ctx.phone.key(first)
    after = until(lambda: (lambda v: v if v != before else None)(ctx.renderer.volume()), 5)
    ctx.phone.key(back)
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
    if not node.enabled:
        # Titles without a song (or video) version have the switch disabled.
        raise Skip("this title has no song/video switch")
    shot = os.environ.get("YTDLNA_COVER_SHOT")
    if shot:
        ctx.phone.screenshot(Path(shot.replace(".png", f"-before-{expect}.png")))
    since = ctx.logs.mark()
    ctx.ui.tap(node)
    m = ctx.logs.wait(rf"Switching the renderer to {expect}", 12, since)
    if not m and not ctx.logs.find_all(r"Music mode ", since):
        # The tap may have been lost: once more.
        node = ctx.ui.find(rid=icon) or node
        ctx.ui.tap(node)
        m = ctx.logs.wait(rf"Switching the renderer to {expect}", 12, since)
    if not m:
        # Titles without a song (or video) version: the app itself stays in its mode.
        mode = "song" if expect == "audio" else "video"
        if not ctx.logs.find_all(rf"Music mode \S+ \({mode}\)", since):
            raise Skip(f"the app did not switch to {expect} (title without that version?)")
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
    result = _switch(ctx, "video_icon", "video")
    # The cover over the phone's video, for a look (YTDLNA_COVER_SHOT=<file.png>).
    shot = os.environ.get("YTDLNA_COVER_SHOT")
    if shot:
        time.sleep(4)
        ctx.phone.screenshot(Path(shot))
    return result


#: A Short opened while casting (any public Short works).
SHORT_VIDEO = os.environ.get("YTDLNA_SHORT_VIDEO", "LnR6W6ec1V8")


def shorts_ignored(ctx: Context) -> Result:
    """A Short opened while casting plays on the phone (sound, no cover) and the TV keeps the
    video: nothing of the Short is sent. Back in the video, the phone is muted again."""
    main_video = ctx.control.status().get("currentVideoId")
    renderer_before = ctx.renderer.position_ms()
    since = ctx.logs.mark()
    ctx.phone.sh(f"am start -a android.intent.action.VIEW -d 'https://www.youtube.com/shorts/{SHORT_VIDEO}' "
                 f"-p {ctx.package}")
    opened = ctx.logs.wait(r"Ignoring (\S+) of the Shorts player", 20, since)
    in_front = ctx.logs.wait(r"The Shorts player is in front", 15, since)
    time.sleep(6)
    status = ctx.control.status()
    during = {"current": status.get("currentVideoId"), "muted": status.get("muted"), "morphe": status.get("morphe"),
              "renderer": ctx.renderer.state(), "rendererPosition": ctx.renderer.position_ms()}
    loaded_short = ctx.logs.find_all(rf"Loading {SHORT_VIDEO} ", since)
    seeks = ctx.logs.find_all(r"Phone seek to .*sent to the renderer", since)
    # Back: once for the Short's panels, until the main player is in front again.
    back = None
    for _ in range(3):
        ctx.phone.key("KEYCODE_BACK")
        back = ctx.logs.wait(r"Main player in front again", 6, since)
        if back:
            break
    time.sleep(3)
    after = ctx.control.status()
    evidence = {"during": during, "seeks": seeks[:3], "rendererBefore": renderer_before,
                "afterMuted": after.get("muted"), "rendererAfter": ctx.renderer.state()}
    problems = [p for p, bad in [
        ("Short not recognized", not (opened and in_front)),
        ("the TV switched to the Short", loaded_short or during["current"] != main_video),
        ("seeks of the Short sent to the TV", seeks),
        ("Short muted on the phone", during["muted"]),
        ("TV not playing on", during["renderer"] != "PLAYING" or during["rendererPosition"] <= renderer_before),
        ("main player not noticed again", not back),
        ("phone not muted again", back and not after.get("muted")),
        ("TV stopped after the Short", ctx.renderer.state() != "PLAYING")] if bad]
    return Result("fail" if problems else "pass", ", ".join(problems), evidence)


def _intents(ctx: Context, kind: str) -> list:
    return [e for e in ctx.control.status().get("intents", []) if e.get("kind") == kind]


def _tap_play_pause(ctx: Context, kind: str) -> bool:
    """Taps the player's play/pause button. YouTube fades its controls out faster than a UI dump
    takes: there, a tap on the player shows them and a second tap at its centre hits the button."""
    before = len(_intents(ctx, kind))
    if ctx.app == "music":
        button = next((n for n in ctx.ui.dump() if "play_pause" in n.resource_id and n.enabled), None)
        if not button:
            mini = ctx.ui.find(rid="mini_player_view_pager_item") or ctx.ui.find(rid="mini_player")
            if mini:
                ctx.ui.tap(mini)
                time.sleep(2)
                button = next((n for n in ctx.ui.dump() if "play_pause" in n.resource_id and n.enabled), None)
        if not button:
            return False
        ctx.ui.tap(button)
    else:
        nodes = ctx.ui.dump()
        overlay = next((n for n in nodes if n.resource_id.endswith("player_overlays")), None)
        if overlay and "Expand" in overlay.desc:
            ctx.ui.tap(overlay)
            time.sleep(2)
            nodes = ctx.ui.dump()
        player = next((n for n in nodes if n.resource_id.endswith("watch_player")), None)
        if not player:
            return False
        x, y = player.center
        for _ in range(2):
            ctx.phone.tap(x, y)
            time.sleep(0.6)
            if len(_intents(ctx, kind)) > before:
                break
    return until(lambda: len(_intents(ctx, kind)) > before, 4)


def scrubber_seek(ctx: Context) -> Result:
    """Dragging the player's progress bar seeks the TV too."""
    if ctx.renderer.state() != "PLAYING":
        ctx.renderer.play()
        renderer_state_is(ctx, "PLAYING", 8)
    before = ctx.renderer.position_ms()
    nodes = ctx.ui.dump()
    since = ctx.logs.mark()
    if ctx.app == "music":
        bar = next((n for n in nodes if n.resource_id.split("/")[-1] == "time_bar"), None)
        if not bar:
            mini = ctx.ui.find(rid="mini_player_view_pager_item") or ctx.ui.find(rid="mini_player")
            if mini:
                ctx.ui.tap(mini)
                time.sleep(2)
            bar = ctx.ui.find(rid="time_bar")
        if not bar:
            return Result("env", "no progress bar")
        y = bar.center[1]
        x1, x2 = bar.bounds[0] + (bar.bounds[2] - bar.bounds[0]) // 5, bar.bounds[0] + (bar.bounds[2] - bar.bounds[0]) * 3 // 5
        ctx.phone.sh(f"input swipe {x1} {y} {x2} {y} 800")
    else:
        overlay = next((n for n in nodes if n.resource_id.endswith("player_overlays")), None)
        if overlay and "Expand" in overlay.desc:
            ctx.ui.tap(overlay)
            time.sleep(2)
            nodes = ctx.ui.dump()
        player = next((n for n in nodes if n.resource_id.split("/")[-1] == "watch_player"), None)
        if not player:
            return Result("env", "no player", {"ids": sorted({n.resource_id.split("/")[-1] for n in nodes})[:60]})
        since = ctx.logs.mark()
        left, top, right, bottom = player.bounds
        # The bar runs along the player's lower edge, once the controls are shown.
        ctx.phone.sh(f"input tap {(left + right) // 2} {(top + bottom) // 2}; sleep 0.4; "
                     f"input swipe {left + (right - left) // 5} {bottom - 4} {left + (right - left) * 3 // 5} {bottom - 4} 800")
    intent = ctx.logs.wait(r"Intent seek (\d+) ms source=(\S+) by (\S+).* -> (.*)$", 8, since)
    sent = ctx.logs.wait(r"Phone seek to (\d+) ms .*sent to the renderer", 6, since)
    target = int(sent.group(1)) if sent else None
    moved = target is not None and until(lambda: abs(ctx.renderer.position_ms() - target) < 8000, 12)
    evidence = {"before": before, "intent": intent.group(0) if intent else None,
                "sent": sent.group(0) if sent else None, "after": ctx.renderer.position_ms()}
    if not intent:
        return Result("fail", "the phone's player got no seek", evidence)
    problems = [p for p, bad in [("seek not sent to the TV", not sent), ("TV not at the new position", not moved)] if bad]
    return Result("fail" if problems else "pass", ", ".join(problems) or intent.group(2), evidence)


def ui_controls(ctx: Context) -> Result:
    """Pause and play with the player's own button: the hooks see them as the user's (by a view)
    and the TV follows."""
    if ctx.renderer.state() != "PLAYING":
        ctx.renderer.play()
        renderer_state_is(ctx, "PLAYING", 8)
    evidence = {}
    problems = []
    for kind, expected in (("pause", "PAUSED_PLAYBACK"), ("play", "PLAYING")):
        if not _tap_play_pause(ctx, kind):
            evidence["intents"] = ctx.control.status().get("intents", [])[-5:]
            return Result("env", f"could not {kind} with the player button", evidence)
        followed = renderer_state_is(ctx, expected, 10)
        last = _intents(ctx, kind)[-1]
        evidence[kind] = {"tv": ctx.renderer.state(), "intent": last}
        if not followed:
            problems.append(f"TV did not follow the {kind}")
        if not str(last.get("decision", "")).startswith("forward"):
            problems.append(f"{kind} not seen as the user's: {last.get('caller')} -> {last.get('decision')}")
        time.sleep(3)
    return Result("fail" if problems else "pass", ", ".join(problems), evidence)


SPONSOR_VIDEO = os.environ.get("YTDLNA_SPONSOR_VIDEO", "3LopI4YeC4I")
# Its sponsor segment (SponsorBlock database), in ms.
SPONSOR_SEGMENT = (657_860, 722_177)


#: A video with many audio tracks (original English and dubbed languages).
MULTI_AUDIO_VIDEO = os.environ.get("YTDLNA_MULTI_AUDIO_VIDEO", "0e3GPea1Tyg")


def audio_language(ctx: Context) -> Result:
    """A video with several audio languages plays the track YouTube plays by default (its
    original) on the TV, not the one with the highest bitrate."""
    main_video = ctx.control.status().get("currentVideoId")
    since = ctx.logs.mark()
    ctx.phone.sh(f"am start -a android.intent.action.VIEW -d 'https://www.youtube.com/watch?v={MULTI_AUDIO_VIDEO}' "
                 f"-p {ctx.package}")
    try:
        loaded = ctx.logs.wait(rf"Loading {MULTI_AUDIO_VIDEO} \((.*?)\) \"", 40, since)
        if not loaded:
            return Result("env", "video not cast")
        detail = loaded.group(1)
        default = ctx.logs.wait(rf"itag=\d+ audio/\S+.* track=(\S+) default", 5, since)
        evidence = {"load": detail, "defaultTrack": default.group(1) if default else None}
        ok = "audio " in detail and "original" in detail.lower()
        return Result("pass" if ok else "fail", detail if ok else f"audio track not the original: {detail}", evidence)
    finally:
        if main_video:
            since = ctx.logs.mark()
            ctx.phone.sh(f"am start -a android.intent.action.VIEW -d 'https://www.youtube.com/watch?v={main_video}' "
                         f"-p {ctx.package}")
            ctx.logs.wait(rf"Loading {main_video} ", 30, since)


def music_lyrics(ctx: Context) -> Result:
    """YouTube Music: Morphe's lyrics for the song reach the extras document (for renderers
    that ask for extras)."""
    asks = "application/vnd.ytdlna.extras+json" in ctx.renderer.sink_protocols().lower()
    if not asks:
        ctx.control.cmd("setup", extras="true")
    try:
        if not ctx.control.status().get("casting"):
            started = start_cast(ctx)
            if started.status != "pass":
                return Result("env", f"cast start: {started.detail}")
        # The test song (by now the queue may play one without lyrics).
        video, link = TEST_VIDEO[ctx.app]
        if ctx.control.status().get("currentVideoId") != video:
            since = ctx.logs.mark()
            ctx.phone.sh(f"am start -a android.intent.action.VIEW -d '{link}' -p {ctx.package}")
            if not ctx.logs.wait(rf"Loading {video} ", 30, since):
                return Result("env", "test song not cast")
        url = f"http://127.0.0.1:8720/extras/{video}.json"

        def lyrics():
            try:
                doc = json.loads(_http(url))
            except Exception:
                return None
            return doc if doc.get("lyrics") else None
        doc = until(lyrics, 30)
        if not doc:
            found = ctx.logs.find_all(r"Lyrics for " + re.escape(video or "") + r": ")
            return Result("fail", "no lyrics in the extras", {"log": found[-3:]})
        lines = doc["lyrics"].get("lines", [])
        timed = sum(1 for l in lines if "start" in l)
        evidence = {"provider": doc["lyrics"].get("provider"), "synced": doc["lyrics"].get("synced"),
                    "lines": len(lines), "timed": timed, "first": lines[:2],
                    "captions": [c.get("label") for c in doc.get("captions", [])]}
        # The text itself is the providers' (LRCLIB's entry for the test song is vandalized):
        # check that lyrics arrive, and timed throughout when they are synced.
        ok = len(lines) >= 1 and (not doc["lyrics"].get("synced") or timed == len(lines))
        return Result("pass" if ok else "fail", f"{len(lines)} lines from {evidence['provider']}", evidence)
    finally:
        if not asks:
            ctx.control.cmd("setup", extras="false")


def renderer_extras(ctx: Context) -> Result:
    """Extras only for renderers that ask: one that does not gets plain metadata. One that asks
    (the test renderer may; otherwise forced here) finds a <desc> in the metadata pointing to the
    extras document, with Morphe's SponsorBlock segments, served with an ETag."""
    main_video = ctx.control.status().get("currentVideoId")
    problems = []
    plain = ctx.renderer.didl()
    asks = "application/vnd.ytdlna.extras+json" in ctx.renderer.sink_protocols().lower()
    # Our item ids start with "ytdlna-" anyway: the extras are the <desc> in our namespace.
    evidence = {"rendererAsks": asks, "hasExtras": "urn:ytdlna-org" in plain}
    if not asks and "urn:ytdlna-org" in plain:
        problems.append("extras sent to a renderer that did not ask")
    if asks and "urn:ytdlna-org" not in plain:
        problems.append("no extras for a renderer that asks")
    if not asks:
        ctx.control.cmd("setup", extras="true")
    try:
        since = ctx.logs.mark()
        ctx.phone.sh(f"am start -a android.intent.action.VIEW -d 'https://www.youtube.com/watch?v={SPONSOR_VIDEO}' "
                     f"-p {ctx.package}")
        if not ctx.logs.wait(rf"Loading {SPONSOR_VIDEO} ", 30, since):
            return Result("env", "sponsor video not cast", evidence)
        # YouTube may briefly go back to the previous video after the link: wait for this one's.
        href = until(lambda: (re.search(rf'<ytdlna:extras [^>]*href="([^"]*/{SPONSOR_VIDEO}\.json)"',
                                        ctx.renderer.didl()) or [None, None])[1], 25)
        evidence["href"] = href
        if not href:
            problems.append("no extras in the metadata")
            return Result("fail", ", ".join(problems), evidence)
        # The proxy as this computer reaches it.
        url = re.sub(r"^http://[^/]+", "http://127.0.0.1:8720", href.replace("&amp;", "&"))

        def fetch(etag=None):
            request = urllib.request.Request(url, headers={"If-None-Match": etag} if etag else {})
            try:
                with urllib.request.urlopen(request, timeout=10) as response:
                    return response.status, response.headers.get("ETag"), json.loads(response.read())
            except urllib.error.HTTPError as e:
                return e.code, e.headers.get("ETag"), None
        doc = until(lambda: (lambda r: r if r[2] and r[2].get("complete") else None)(fetch()), 25) or fetch()
        status, etag, body = doc
        evidence["document"] = body
        sponsor = [x for x in (body or {}).get("segments", []) if x.get("category") == "sponsor"]
        if status != 200 or not body:
            problems.append(f"document not served (HTTP {status})")
        elif not body.get("complete"):
            problems.append("document not complete after 25 s")
        elif not sponsor or abs(sponsor[0].get("start", 0) - SPONSOR_SEGMENT[0] / 1000) > 1:
            problems.append("sponsor segment missing")
        # Loudness of the cast audio (YouTube gives it per format), for an even level.
        audio = (body or {}).get("audio") or {}
        evidence["audio"] = audio
        if not isinstance(audio.get("loudnessDb"), (int, float)):
            problems.append("no loudness in the extras")
        evidence["chapters"] = len((body or {}).get("chapters", []))
        # Captions: an SRT resource for a renderer that lists text/srt, the tracks in the extras.
        lists_srt = "text/srt" in ctx.renderer.sink_protocols().lower()
        didl = ctx.renderer.didl()
        srt = re.search(r'protocolInfo="http-get:\*:text/srt:\*"[^>]*>([^<]+)<', didl)
        evidence["captionsResource"] = srt.group(1) if srt else None
        if lists_srt and not srt:
            problems.append("no captions resource for a renderer that lists text/srt")
        if not lists_srt and srt:
            problems.append("captions resource for a renderer that does not list text/srt")
        if srt:
            body = _http(re.sub(r"^http://[^/]+", "http://127.0.0.1:8720", srt.group(1).replace("&amp;", "&")))
            evidence["srtStart"] = body[:120]
            if not re.match(r"1\n\d\d:\d\d:\d\d,\d{3} --> ", body):
                problems.append("captions resource is not SRT")
        with_tracks = until(lambda: (lambda r: r if r[2] and r[2].get("captions") else None)(fetch()), 20)
        evidence["captionTracks"] = [(t.get("language"), t.get("auto"), t.get("default")) for t in
                                     (with_tracks[2]["captions"] if with_tracks else [])]
        if not with_tracks:
            problems.append("no caption tracks in the extras")
        # The document grew with the tracks: revalidate its latest version.
        etag = fetch()[1] or etag
        if etag:
            again = fetch(etag)[0]
            evidence["revalidate"] = again
            if again != 304:
                problems.append(f"same ETag answered with {again}, not 304")
        else:
            problems.append("no ETag")
    finally:
        ctx.control.cmd("setup", extras="false")
        if main_video:
            since = ctx.logs.mark()
            ctx.phone.sh(f"am start -a android.intent.action.VIEW -d 'https://www.youtube.com/watch?v={main_video}' "
                         f"-p {ctx.package}")
            ctx.logs.wait(rf"Loading {main_video} ", 30, since)
    return Result("fail" if problems else "pass", ", ".join(problems), evidence)


def sponsorblock(ctx: Context) -> Result:
    """Morphe's SponsorBlock skips a sponsor segment on the phone: the skip reaches the TV.
    Diagnostic: the debug lines (segments, TV position at the skip, time the TV spent inside)
    are the evidence."""
    main_video = ctx.control.status().get("currentVideoId")
    ctx.control.cmd("setup", debugLog="true")
    since = ctx.logs.mark()
    ctx.phone.sh(f"am start -a android.intent.action.VIEW -d 'https://www.youtube.com/watch?v={SPONSOR_VIDEO}' "
                 f"-p {ctx.package}")
    loaded = ctx.logs.wait(rf"Loading {SPONSOR_VIDEO} ", 30, since)
    segments = ctx.logs.wait(rf"SponsorBlock: [1-9]\d* segments for {SPONSOR_VIDEO}", 30, since)
    evidence = {"loaded": bool(loaded), "segments": bool(segments)}
    if not loaded:
        return Result("env", "video not cast", evidence)
    renderer_state_is(ctx, "PLAYING", 20)
    time.sleep(5)
    start, end = SPONSOR_SEGMENT
    ctx.control.local("seek", ms=str(start - 12_000))
    skip = ctx.logs.wait(r"SponsorBlock skip: .*sent to the TV", 35, since)
    # The segment runs to the end of the video: right after the skip the TV is past it, or has
    # already ended there.
    after = []
    if skip:
        deadline = time.time() + 8
        while time.time() < deadline:
            state = ctx.renderer.state()
            position = ctx.renderer.position_ms()
            after.append((state, position))
            if state in ("STOPPED", "NO_MEDIA_PRESENT") or (position or 0) >= end - 1000:
                break
            time.sleep(1)
    lines = ctx.logs.find_all(r"SponsorBlock|Phone seek to|Correcting phone", since)
    evidence.update({"lines": lines[-25:], "tvAfterSkip": after})
    past = any(st in ("STOPPED", "NO_MEDIA_PRESENT") or (pos or 0) >= end - 1000 for st, pos in after)
    problems = [p for p, bad in [
        ("no segments loaded", not segments),
        ("no skip sent to the TV", not skip),
        ("TV not past the segment", skip and not past)] if bad]
    # Back to the test video for the scenarios after this one.
    if main_video:
        ctx.phone.sh(f"am start -a android.intent.action.VIEW -d 'https://www.youtube.com/watch?v={main_video}' "
                     f"-p {ctx.package}")
        ctx.logs.wait(rf"Loading {main_video} ", 30, ctx.logs.mark())
    return Result("fail" if problems else "pass", ", ".join(problems), evidence)


def screen_lock(ctx: Context) -> Result:
    """Casting goes on with the phone locked."""
    if ctx.renderer.state() != "PLAYING":
        ctx.renderer.play()
        renderer_state_is(ctx, "PLAYING", 8)
    # The title must not end while locked (the next one starts at 0): leave it enough time.
    duration = ctx.control.status().get("local", {}).get("duration", 0)
    if duration and duration - ctx.renderer.position_ms() < 45_000:
        ctx.control.local("seek", ms="5000")
        until(lambda: ctx.renderer.position_ms() < 20_000, 10)
        time.sleep(3)
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
    # The proxy closes its port between casts.
    port_closed = until(lambda: not _listening(ctx, 8720), 6)
    problems = [p for p, bad in [("renderer not stopped", not stopped), ("not unmuted", not unmuted),
                                 ("session still active", status.get("casting")),
                                 ("cast notification left", notification),
                                 ("proxy port still open", not port_closed)] if bad]
    return Result("fail" if problems else "pass", ", ".join(problems))


def hls_playback(ctx: Context) -> Result:
    """Renderers without DASH get HLS (fMP4, H.264/AAC): the TV plays it and its position advances."""
    ctx.control.cmd("setup", preferHls="true")
    try:
        if ctx.app == "music":
            ctx.facts.pop("title", None)
        started = start_cast(ctx)
        if started.status != "pass":
            return Result("fail", f"cast start: {started.detail}")
        evidence = {"format": started.detail}
        if "HLS" not in started.detail:
            return Result("fail", f"not cast as HLS ({started.detail})", evidence)
        meta = until(lambda: (lambda m: m if "mpegurl" in m.get("protocol", "") else None)(ctx.renderer.metadata()),
                     10) or ctx.renderer.metadata()
        evidence["protocol"] = meta.get("protocol")
        # The playlists as the renderer gets them (the phone's proxy is forwarded to this computer).
        video_id = started.evidence.get("videoId", ctx.video_id)
        master = _http(f"http://127.0.0.1:8720/v/{video_id}/master.m3u8")
        variants = re.findall(r"^(\d+\.m3u8)$", master, re.M)
        media = _http(f"http://127.0.0.1:8720/v/{video_id}/{variants[0]}") if variants else ""
        evidence.update({"variants": len(variants), "segments": media.count("#EXTINF"),
                         "master": master[:400]})
        problems = []
        if not variants:
            problems.append("master playlist without variants")
        elif media.count("#EXTINF") < 2:
            problems.append("media playlist without segments")
        if not renderer_state_is(ctx, "PLAYING", 30):
            problems.append(f"renderer state {ctx.renderer.state()}")
        else:
            p1 = ctx.renderer.position_ms()
            p2 = until(lambda: (lambda p: p if p >= p1 + 3000 else None)(ctx.renderer.position_ms()), 15)
            evidence["positions"] = [p1, p2]
            if not p2:
                problems.append("renderer position does not advance")
        return Result("fail" if problems else "pass", ", ".join(problems) or
                      f"{started.detail.split(',')[0]}, {len(variants)} variants, {evidence['segments']} segments",
                      evidence)
    finally:
        ctx.control.cmd("setup", preferHls="false")
        try:
            ctx.control.cmd("stop")
        except Exception:
            pass


def _http(url: str) -> str:
    try:
        return urllib.request.urlopen(url, timeout=15).read().decode("utf-8", "replace")
    except Exception as e:  # The evidence shows what went wrong.
        return f"ERROR {e}"


#: A 24/7 live stream (Lofi Girl's synthwave radio). Override with YTDLNA_LIVE_VIDEO if it ends.
LIVE_VIDEO = os.environ.get("YTDLNA_LIVE_VIDEO", "4xDzrJKXOOY")


def live_hls(ctx: Context) -> Result:
    """Live streams play on the TV as HLS, with every playlist and segment fetched through the phone."""
    since = ctx.logs.mark()
    ctx.phone.sh(f"am start -a android.intent.action.VIEW -d 'vnd.youtube:{LIVE_VIDEO}' -p {ctx.package}")
    captured = ctx.logs.wait(rf"Captured videoId={LIVE_VIDEO} .*", 40, since)
    if not captured:
        return Result("env", f"live video {LIVE_VIDEO} did not load")
    if " live " not in captured.group(0) + " ":
        return Result("env", f"{LIVE_VIDEO} is not live right now (set YTDLNA_LIVE_VIDEO)")
    ctx.facts["video_id"] = LIVE_VIDEO
    ctx.facts.pop("title", None)
    try:
        # Sheets over the player (bedtime reminder, notices) pause it; that pause would be cast.
        ctx.ui.dismiss_dialogs()
        started = start_cast(ctx)
        if started.status != "pass":
            return Result("fail", f"cast start: {started.detail}")
        if ctx.ui.dismiss_dialogs():
            ctx.control.local("play")
        evidence = {"format": started.detail}
        if "Live HLS" not in started.detail:
            return Result("fail", f"not cast as live HLS ({started.detail})", evidence)
        base = f"http://127.0.0.1:8720/v/{LIVE_VIDEO}/"
        master = _http(base + "live.m3u8")
        variants = re.findall(r"^(L\d+/index\.m3u8)$", master, re.M)
        media = _http(base + variants[0]) if variants else ""
        segments = re.findall(r"^(\d+)\.m4s$", media, re.M)
        track = variants[0].split("/")[0] if variants else ""
        segment = _http_bytes(f"{base}{track}/{segments[-2]}.m4s") if len(segments) > 1 else b""
        init = _http_bytes(f"{base}{track}/init.mp4") if track else b""
        evidence.update({"variants": len(variants), "segments": len(segments), "segmentBytes": len(segment),
                         "segmentStarts": segment[4:8].decode("latin-1"), "init": init[4:8].decode("latin-1"),
                         "master": master[:300], "media": media[:300]})
        problems = []
        if not variants:
            problems.append("live playlist without variants")
        if "googlevideo.com" in master + media:
            problems.append("playlist points to YouTube")
        if not segments:
            problems.append("variant playlist without segments")
        elif segment[4:8] in (b"ftyp", b"moov") or len(segment) < 1000:
            problems.append("segment not served (or with its init section)")
        if init[4:8] != b"ftyp":
            problems.append("no init section")
        if not renderer_state_is(ctx, "PLAYING", 40):
            problems.append(f"renderer state {ctx.renderer.state()}")
        else:
            # In a sliding live window the position stays about the same (the window start moves
            # along): the renderer plays if its position changes at all and it stays PLAYING,
            # while the playlist moves on.
            p1 = ctx.renderer.position_ms()
            p2 = until(lambda: (lambda p: p if abs(p - p1) >= 1000 else None)(ctx.renderer.position_ms()), 20)
            time.sleep(6)  # longer than one segment
            later = _http(base + variants[0]) if variants else ""
            first = lambda text: int((re.search(r"#EXT-X-MEDIA-SEQUENCE:(\d+)", text) or [0, 0])[1])
            evidence.update({"positions": [p1, p2], "sequence": [first(media), first(later)]})
            if p2 is None:
                problems.append("renderer position does not change")
            if first(later) <= first(media):
                problems.append("live playlist does not move on")
            if ctx.renderer.state() != "PLAYING":
                problems.append(f"renderer stopped playing ({ctx.renderer.state()})")
        return Result("fail" if problems else "pass", ", ".join(problems) or
                      f"{started.detail.split(',')[0]}, {len(variants)} variants, window of {len(segments)} segments",
                      evidence)
    finally:
        ctx.facts.pop("video_id", None)
        try:
            ctx.control.cmd("stop")
        except Exception:
            pass


def _http_bytes(url: str) -> bytes:
    try:
        return urllib.request.urlopen(url, timeout=30).read()
    except Exception:
        return b""


def avtransport_next(ctx: Context) -> Result:
    """Renderers without OpenHome get the next title (SetNextAVTransportURI) and the phone follows."""
    # A cast still running would show the renderer as connected: from the start.
    ctx.control.cmd("stop")
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
        # The length is known for songs (music database) or titles the app loaded already.
        problems = [] if upcoming.get("title") else ["next title without title"]
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
                      ", ".join(problems) or f"next: {upcoming.get('title')} ({upcoming.get('duration') or 'length unknown'})",
                      evidence)
    finally:
        ctx.renderer.use_openhome = True
        ctx.control.cmd("setup", avTransportOnly="false")
        try:
            ctx.control.cmd("stop")
        except Exception:
            pass


def _listening(ctx: Context, port: int) -> bool:
    """Whether something on the phone listens on {port} (state 0A in /proc/net/tcp)."""
    suffix = f":{port:04X}"
    for line in ctx.phone.sh("cat /proc/net/tcp /proc/net/tcp6").splitlines():
        fields = line.split()
        if len(fields) > 3 and fields[1].endswith(suffix) and fields[3] == "0A":
            return True
    return False


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


def crossfade(ctx: Context) -> Result:
    """With Morphe's crossfade on (a second player for the next song, a pause right before each
    skip), skips on the phone and on the TV still move both, and no pause reaches the TV."""
    if ctx.control.cmd("setup", crossfade="true").get("restart"):
        started = _start_app(ctx)
        if started.status != "pass":
            return Result("env", f"restart with crossfade: {started.detail}")
    try:
        ctx.ui.dismiss_dialogs()
        started = start_cast(ctx)
        if started.status != "pass":
            return Result("fail", f"cast start: {started.detail}")
        if not renderer_state_is(ctx, "PLAYING", 25):
            return Result("fail", f"renderer state {ctx.renderer.state()}")
        # The cast start reloads the phone's title through its neighbour: skip once it is back.
        ctx.control.wait_status(lambda st: st.get("currentVideoId")
                                and st.get("currentVideoId") == st.get("session", {}).get("loadedVideoId"), 15)
        time.sleep(2)
        since = ctx.logs.mark()
        results = {"phone_next": phone_next(ctx), "tv_next": tv_next(ctx)}
        pauses = ctx.logs.find_all(r"Phone pause by .*sent to the renderer", since)
        playing = renderer_state_is(ctx, "PLAYING", 10)
        evidence = {name: f"{r.status}: {r.detail}" for name, r in results.items()}
        evidence["pauses"] = pauses[:3]
        problems = [f"{name}: {r.detail}" for name, r in results.items() if r.status != "pass"]
        problems += [p for p, bad in [("a skip paused the TV", pauses), ("TV not playing", not playing)] if bad]
        return Result("fail" if problems else "pass", ", ".join(problems), evidence)
    finally:
        try:
            ctx.control.cmd("stop")
        except Exception:
            pass
        if ctx.control.cmd("setup", crossfade="false").get("restart"):
            _start_app(ctx)


def media_session(ctx: Context) -> Result:
    """The cast session followed the app's media session (local controls, queue, playlist)."""
    if not ctx.logs.find_all(r"Cast session started"):
        return Result("skip", "no cast session in this run")
    missing = ctx.logs.find_all(r"App media session not found")
    return Result("fail" if missing else "pass", "app media session not found" if missing else "")

# endregion


# Order matters: later scenarios build on the state earlier ones leave.
SCENARIOS = [
    ("launch", launch, {"yt", "music"}),
    ("stream_capture", stream_capture, {"yt", "music"}),
    ("video_id_hook", video_id_hook, {"yt", "music"}),
    ("cast_button", cast_button, {"yt", "music"}),
    ("cast_button_follows", cast_button_follows, {"music"}),
    ("google_cast_link", google_cast_link, {"yt", "music"}),
    ("manual_devices", manual_devices, {"yt", "music"}),
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
    ("ui_controls", ui_controls, {"yt", "music"}),
    ("scrubber_seek", scrubber_seek, {"yt", "music"}),
    ("volume_keys", volume_keys, {"yt", "music"}),
    ("phone_next", phone_next, {"music"}),
    ("tv_next", tv_next, {"music"}),
    ("song_mode", song_mode, {"music"}),
    ("video_mode", video_mode, {"music"}),
    ("shorts_ignored", shorts_ignored, {"yt"}),
    ("audio_language", audio_language, {"yt"}),
    ("renderer_extras", renderer_extras, {"yt"}),
    ("sponsorblock", sponsorblock, {"yt"}),
    ("screen_lock", screen_lock, {"yt", "music"}),
    ("stop_cast", stop_cast, {"yt", "music"}),
    ("hls_playback", hls_playback, {"yt"}),
    ("live_hls", live_hls, {"yt"}),
    ("avtransport_next", avtransport_next, {"music"}),
    ("music_lyrics", music_lyrics, {"music"}),
    ("crossfade", crossfade, {"music"}),
    ("media_session", media_session, {"yt", "music"}),
    ("no_crash", no_crash, {"yt", "music"}),
]
# Without these, the remaining scenarios can not run.
REQUIRED = {"launch", "start_cast"}
