"""Keeps the phone and TV emulators available: starts them when missing and restores the setup."""

import os
import subprocess
import time
import urllib.request
from pathlib import Path

from .device import Device

SDK = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
EMULATOR = SDK / "emulator/emulator"


def online(serial: str) -> bool:
    out = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout
    return any(line.split("\t")[0] == serial and line.endswith("device") for line in out.splitlines())


def booted(serial: str) -> bool:
    if not online(serial):
        return False
    return Device(serial).sh("getprop sys.boot_completed").strip() == "1"


def boot_id(serial: str) -> str:
    """Changes with every boot: tells a crashed and restarted emulator from one that ran on."""
    try:
        return Device(serial).sh("cat /proc/sys/kernel/random/boot_id", timeout=10).strip()
    except Exception:
        return ""


def start(avd: str, serial: str, log_dir: Path, timeout: float = 300) -> None:
    """Starts an AVD (window visible) on the port of {serial} and waits until it booted."""
    port = serial.split("-")[-1]
    log = (log_dir / f"emulator-{avd}.log").open("a")
    subprocess.Popen([str(EMULATOR), "-avd", avd, "-port", port, "-netdelay", "none", "-netspeed", "full",
                      # Silent on the Mac: the "none" backend still plays (and times) audio inside
                      # Android, unlike -no-audio, which removes the audio device.
                      "-audio", "none"],
                     stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if booted(serial):
            time.sleep(8)  # Let the launcher and services settle.
            return
        time.sleep(3)
    raise RuntimeError(f"{avd} did not boot within {timeout:.0f}s")


def _wait_renderer(args, tv: Device, timeout: float = 60) -> None:
    """Waits until the renderer answers; restarts its app once if it does not."""
    deadline = time.monotonic() + timeout
    restarted = False
    while time.monotonic() < deadline:
        try:
            urllib.request.urlopen(args.renderer_url, timeout=5).read()
            return
        except Exception:
            if not restarted and time.monotonic() > deadline - timeout / 2:
                package = args.tv_activity.split("/")[0]
                tv.sh(f"am force-stop {package}")
                tv.sh(f"am start -n {args.tv_activity}")
                restarted = True
            time.sleep(2)
    raise RuntimeError(f"renderer at {args.renderer_url} does not answer")


def ensure(args, log_dir: Path) -> list[str]:
    """Makes sure both emulators run and are set up. Returns what had to be (re)started."""
    restarted = []
    for serial, avd in ((args.tv, args.tv_avd), (args.phone, args.phone_avd)):
        if not booted(serial):
            if not avd:
                raise RuntimeError(f"{serial} is not running and no AVD name was given")
            print(f"  starting {avd} ({serial}) ...", flush=True)
            start(avd, serial, log_dir)
            restarted.append(avd)
    phone, tv = Device(args.phone), Device(args.tv)
    tv.adb("forward", "tcp:49152", "tcp:49152", check=False)
    phone.adb("forward", "tcp:8720", "tcp:8720", check=False)
    if args.tv_activity:
        tv.sh(f"am start -n {args.tv_activity}")
        _wait_renderer(args, tv)
    # MicroG must not be stopped by battery optimisation; the screen must not time out mid-test.
    phone.sh("dumpsys deviceidle whitelist +app.revanced.android.gms")
    phone.sh("svc power stayon true")
    phone.wake()
    if restarted:
        time.sleep(5)  # Renderer app start.
    return restarted
