"""adb access, live logcat and UI automation for one Android device."""

import re
import subprocess
import threading
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

ADB = "adb"


class Device:
    def __init__(self, serial: str):
        self.serial = serial

    def adb(self, *args: str, timeout: float = 60, check: bool = True) -> str:
        result = subprocess.run([ADB, "-s", self.serial, *args], capture_output=True, text=True, timeout=timeout)
        if check and result.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} failed: {result.stdout}{result.stderr}".strip())
        return result.stdout

    def sh(self, command: str, timeout: float = 60) -> str:
        return self.adb("shell", command, timeout=timeout, check=False)

    def key(self, keycode: str) -> None:
        self.sh(f"input keyevent {keycode}")

    def tap(self, x: int, y: int) -> None:
        self.sh(f"input tap {x} {y}")

    def screenshot(self, path: Path) -> None:
        data = subprocess.run([ADB, "-s", self.serial, "exec-out", "screencap", "-p"],
                              capture_output=True, timeout=30).stdout
        path.write_bytes(data)

    def pid(self, package: str) -> str:
        return self.sh(f"pidof {package}").strip()

    def wake(self) -> None:
        self.key("KEYCODE_WAKEUP")
        self.sh("wm dismiss-keyguard")

    def install(self, apk: Path, package: str, keep_data_on_downgrade: bool = True) -> str:
        """Installs over an existing version, keeping its data (and with it the login).

        Data of a newer version can crash an older one; without keep_data_on_downgrade it is
        removed when going back to an older version."""
        out = subprocess.run([ADB, "-s", self.serial, "install", "-r", "-d", str(apk)],
                             capture_output=True, text=True, timeout=600)
        text = out.stdout + out.stderr
        if "Success" in text:
            return "installed"
        if "DOWNGRADE" in text or "UPDATE_INCOMPATIBLE" in text:
            # Older version: remove the app but keep its data, then install.
            # (`adb uninstall -k` only prints a notice and removes nothing.)
            self.sh(f"cmd package uninstall {'-k ' if keep_data_on_downgrade else ''}{package}")
            out = subprocess.run([ADB, "-s", self.serial, "install", str(apk)],
                                 capture_output=True, text=True, timeout=600)
            if "Success" in out.stdout + out.stderr:
                return "installed (downgrade, data kept)" if keep_data_on_downgrade \
                    else "installed (downgrade, data cleared)"
        raise RuntimeError(f"install failed: {text.strip()}")


class LogStream:
    """Live logcat of a device. Lines are kept so tests can wait for patterns without polling adb."""

    TAGS = re.compile(r"\b(YtDlna|YtDlnaTest|YT\.mlplayer|AndroidRuntime)\b")

    def __init__(self, device: Device, file: Path):
        self.device = device
        self.file = file.open("w", encoding="utf-8", errors="replace")
        self.lines: list[str] = []
        self.cond = threading.Condition()
        device.adb("logcat", "-c", check=False)
        self.proc = subprocess.Popen([ADB, "-s", device.serial, "logcat", "-v", "threadtime"],
                                     stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True,
                                     encoding="utf-8", errors="replace")
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self) -> None:
        for line in self.proc.stdout:
            self.file.write(line)
            if self.TAGS.search(line):
                with self.cond:
                    self.lines.append(line.rstrip("\n"))
                    self.cond.notify_all()

    def mark(self) -> int:
        with self.cond:
            return len(self.lines)

    def wait(self, pattern: str, timeout: float, since: int = 0) -> "re.Match | None":
        """First line after {since} matching {pattern}, waiting up to {timeout} seconds."""
        regex = re.compile(pattern)
        deadline = time.monotonic() + timeout
        index = since
        with self.cond:
            while True:
                while index < len(self.lines):
                    match = regex.search(self.lines[index])
                    index += 1
                    if match:
                        return match
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return None
                self.cond.wait(remaining)

    def find_all(self, pattern: str, since: int = 0) -> list[str]:
        regex = re.compile(pattern)
        with self.cond:
            return [line for line in self.lines[since:] if regex.search(line)]

    def close(self) -> None:
        self.proc.terminate()
        self.file.close()


@dataclass
class Node:
    resource_id: str
    text: str
    desc: str
    bounds: tuple[int, int, int, int]
    package: str = ""

    @property
    def center(self) -> tuple[int, int]:
        return (self.bounds[0] + self.bounds[2]) // 2, (self.bounds[1] + self.bounds[3]) // 2


class Ui:
    """UI automation through uiautomator dumps: elements are found by id or text, not by position."""

    def __init__(self, device: Device):
        self.device = device

    def dump(self) -> list[Node]:
        for _ in range(3):
            # --windows: all windows, also dialogs over translucent activities (the share target).
            self.device.sh("uiautomator dump --windows /sdcard/ytdlna-ui.xml >/dev/null 2>&1")
            xml = self.device.adb("exec-out", "cat", "/sdcard/ytdlna-ui.xml", check=False)
            try:
                root = ET.fromstring(xml)
                break
            except ET.ParseError:
                time.sleep(0.5)
        else:
            return []
        nodes = []
        for n in root.iter("node"):
            b = [int(v) for v in re.findall(r"\d+", n.get("bounds", "[0,0][0,0]"))]
            nodes.append(Node(n.get("resource-id", "").split("/")[-1], n.get("text", ""),
                              n.get("content-desc", ""), tuple(b), n.get("package", "")))
        return nodes

    def find(self, rid: str = None, text: str = None, contains: str = None, nodes=None) -> "Node | None":
        """Element by id or text. Not found on the screen: known dialogs (which can come up any
        time, like Morphe's experimental version notice) are dismissed and it is looked for again."""
        node = self._find(rid, text, contains, nodes if nodes is not None else self.dump())
        if node is None and nodes is None and self.dismiss_dialogs():
            node = self._find(rid, text, contains, self.dump())
        return node

    def _find(self, rid, text, contains, nodes) -> "Node | None":
        for node in nodes:
            if node.bounds[2] <= node.bounds[0] or node.bounds[3] <= node.bounds[1]:
                continue
            if rid and node.resource_id != rid:
                continue
            if text and node.text != text:
                continue
            if contains and contains.lower() not in (node.text + " " + node.desc).lower():
                continue
            return node
        return None

    def wait(self, timeout: float, **criteria) -> "Node | None":
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            node = self.find(**criteria)
            if node:
                return node
            time.sleep(0.4)
        return None

    def tap(self, node: Node) -> None:
        self.device.tap(*node.center)

    # First-start dialogs of YouTube, YouTube Music, Morphe and MicroG.
    DISMISS = ["Reject all", "Do not show again", "Restart", "Not now", "No thanks", "Dismiss",
               "Got it", "Skip", "Alle ablehnen", "Nicht mehr anzeigen", "Neu starten", "Nicht jetzt"]

    # Buttons with a prefix (emoji), matched by contained text. Morphe asks about experimental
    # app versions (YouTube 21.20+, Music 9.40+); keeping the installed version is wanted here.
    DISMISS_CONTAINS = ["I want experimental"]

    def dismiss_dialogs(self, rounds: int = 4) -> list[str]:
        """Taps known dismiss buttons until none is left. Returns what was tapped."""
        tapped = []
        for _ in range(rounds):
            nodes = self.dump()
            hit = next((n for label in self.DISMISS for n in nodes if n.text == label), None) \
                or next((n for label in self.DISMISS_CONTAINS for n in nodes if label in n.text), None) \
                or next((n for n in nodes if n.text in ("Allow", "Zulassen")
                         and "permissioncontroller" in n.package), None)
            if not hit:
                break
            self.tap(hit)
            tapped.append(hit.text)
            time.sleep(1.5)
        return tapped
