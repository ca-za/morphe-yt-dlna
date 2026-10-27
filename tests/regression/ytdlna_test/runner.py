"""Runs all scenarios for one APK and writes the report."""

import json
import subprocess
import time
import traceback
from pathlib import Path

from . import build, emulators, scenarios
from .control import Control
from .device import Device, LogStream, Ui
from .renderer import Renderer
from .scenarios import Context, Result, Skip

ICONS = {"pass": "✅", "fail": "❌", "env": "⚠️", "info": "ℹ️", "skip": "⏭️"}


class EmulatorLost(Exception):
    pass


def run_apk(apk: build.ApkInfo, bundle: Path, args, out_dir: Path) -> dict:
    """Runs one APK; repeats it once if an emulator crashed during the run."""
    for attempt in (1, 2):
        restarted = emulators.ensure(args, out_dir.parent)
        try:
            report = _run_apk(apk, bundle, args, out_dir)
        except EmulatorLost as e:
            print(f"  emulator lost ({e}), {'retrying' if attempt == 1 else 'giving up'}", flush=True)
            if attempt == 2:
                report = {"apk": apk.path.name, "app": apk.app, "version": apk.version, "patch": {},
                          "results": [], "verdict": f"env: emulator crashed twice ({e})",
                          "started": time.strftime("%Y-%m-%d %H:%M:%S")}
                return _write(report, out_dir)
            continue
        report["attempt"] = attempt
        report["emulatorsRestarted"] = restarted
        return _write(report, out_dir)


class Watch:
    """Notices emulators that went down, also when they came back up in the meantime."""

    def __init__(self, args):
        self.serials = (args.phone, args.tv)
        self.boots = {s: emulators.boot_id(s) for s in self.serials}

    def lost(self) -> "str | None":
        for serial in self.serials:
            if not emulators.booted(serial):
                return f"{serial} down"
            if emulators.boot_id(serial) != self.boots[serial]:
                return f"{serial} rebooted"
        return None


def _run_apk(apk: build.ApkInfo, bundle: Path, args, out_dir: Path) -> dict:
    watch = Watch(args)
    try:
        return _run_scenarios(apk, bundle, args, out_dir, watch)
    except EmulatorLost:
        raise
    except Exception as e:
        # Patching, installing or the setup broke off: an emulator crash, or a real error.
        lost = watch.lost()
        if lost:
            raise EmulatorLost(f"{lost}: {e}")
        raise


def _run_scenarios(apk: build.ApkInfo, bundle: Path, args, out_dir: Path, watch: Watch) -> dict:
    out_dir.mkdir(parents=True, exist_ok=True)
    report = {"apk": apk.path.name, "app": apk.app, "package": apk.package, "version": apk.version,
              "started": time.strftime("%Y-%m-%d %H:%M:%S"), "results": []}

    started = time.monotonic()
    patched = build.patch(apk, bundle, out_dir)
    report["patch"] = {"ok": patched.ok, "failed": patched.failed, "errors": patched.errors,
                       "reasons": patched.reasons,
                       "dlnaApplied": "DLNA cast" in patched.applied, "applied": len(patched.applied),
                       "seconds": round(time.monotonic() - started)}
    if not patched.ok or "DLNA cast" not in patched.applied:
        reason = patched.reasons.get("DLNA cast")
        report["verdict"] = f"patch failed: {reason}" if reason else "patch failed"
        return report

    phone = Device(args.phone)
    package = "app.morphe.android." + apk.package.removeprefix("com.google.android.")
    # Only YouTube Music needs its data (the login) for the tests.
    report["install"] = phone.install(patched.apk, package, keep_data_on_downgrade=apk.app == "music")
    # A fresh install asks for it over everything; the cast notification needs it as well.
    phone.sh(f"pm grant {package} android.permission.POST_NOTIFICATIONS")
    phone.sh(f"am force-stop {'app.morphe.android.youtube' if apk.app == 'music' else 'app.morphe.android.apps.youtube.music'}")

    logs = LogStream(phone, out_dir / "logcat.txt")
    ctx = Context(app=apk.app, package=package, phone=phone, logs=logs, ui=Ui(phone),
                  control=Control(phone, logs, package), renderer=Renderer(args.renderer_url),
                  renderer_device_url=args.renderer_device_url, stream_host=args.stream_host)
    try:
        aborted = None
        for name, fn, apps in scenarios.SCENARIOS:
            if apk.app not in apps:
                continue
            if aborted and name != "no_crash":
                report["results"].append({"name": name, "status": "skip", "detail": f"after {aborted} failed"})
                continue
            t0 = time.monotonic()
            result = _run_scenario(fn, ctx)
            if result.status == "fail":
                # A dialog of the app or the account (like the bedtime reminder) may have come up
                # during the scenario: closed, the scenario runs once more.
                closed = ctx.ui.dismiss_dialogs()
                if closed:
                    result = _run_scenario(fn, ctx)
                    result.detail = f"{result.detail} (run again after closing {', '.join(closed)})".strip()
            entry = {"name": name, "doc": (fn.__doc__ or "").strip(), "status": result.status,
                     "detail": result.detail, "evidence": result.evidence,
                     "seconds": round(time.monotonic() - t0, 1)}
            if result.status in ("fail", "env"):
                lost = watch.lost()
                if lost:
                    raise EmulatorLost(f"{lost} during {name}")
            if result.status == "fail":
                shot = out_dir / f"fail-{name}.png"
                phone.screenshot(shot)
                entry["screenshot"] = shot.name
            # Later scenarios need these: after a failure or skip their results would only follow from it.
            if result.status in ("fail", "skip") and name in scenarios.REQUIRED:
                aborted = name
            report["results"].append(entry)
            print(f"  {ICONS[result.status]} {name}: {result.detail}", flush=True)
    finally:
        try:
            ctx.control.cmd("stop", timeout=5)
        except Exception:
            pass
        logs.close()

    # A crash with all later scenarios passing is unlikely, but the results would not be trustworthy.
    lost = watch.lost()
    if lost:
        raise EmulatorLost(f"{lost} during the run")
    statuses = [r["status"] for r in report["results"]]
    report["verdict"] = "fail" if "fail" in statuses else "pass"
    report["seconds"] = round(time.monotonic() - started)
    return report


def _run_scenario(fn, ctx) -> Result:
    try:
        return fn(ctx)
    except Skip as e:
        return Result("skip", str(e))
    except Exception as e:
        return Result("fail", f"{type(e).__name__}: {e}", {"traceback": traceback.format_exc()})


def _write(report: dict, out_dir: Path) -> dict:
    (out_dir / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False))
    lines = [f"# {report['app']} {report['version']} — {report['verdict']}", "",
             f"APK: `{report['apk']}`  ", f"Started: {report['started']}  ",
             f"Attempt: {report.get('attempt', 1)}"
             + (f", emulators restarted: {', '.join(report['emulatorsRestarted'])}"
                if report.get('emulatorsRestarted') else "") + "  ",
             f"Patch: {'ok' if report.get('patch', {}).get('dlnaApplied') else 'FAILED'}, "
             f"{report.get('patch', {}).get('applied', 0)} patches applied"
             + (f", failed: {', '.join(report['patch']['failed'])}" if report.get('patch', {}).get('failed') else ""), ""]
    if report.get("results"):
        lines += ["| | Test | Result | s |", "|---|---|---|---|"]
        for r in report["results"]:
            detail = r.get("detail", "").replace("|", "/")[:140]
            lines.append(f"| {ICONS[r['status']]} | {r['name']} | {detail} | {r.get('seconds', '')} |")
    (out_dir / "report.md").write_text("\n".join(lines) + "\n")
    return report


def summary(reports: list[dict], path: Path) -> None:
    names = []
    for r in reports:
        for res in r.get("results", []):
            if res["name"] not in names:
                names.append(res["name"])
    header = "| Test | " + " | ".join(f"{r['app']} {r['version']}" for r in reports) + " |"
    lines = ["# Regression summary", "", header, "|---" * (len(reports) + 1) + "|"]
    lines.append("| patch | " + " | ".join("✅" if r["patch"].get("dlnaApplied") else "❌" for r in reports) + " |")
    for name in names:
        cells = []
        for r in reports:
            res = next((x for x in r.get("results", []) if x["name"] == name), None)
            cells.append(ICONS[res["status"]] if res else "")
        lines.append(f"| {name} | " + " | ".join(cells) + " |")
    path.write_text("\n".join(lines) + "\n")
