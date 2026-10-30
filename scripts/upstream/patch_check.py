#!/usr/bin/env python3
"""
Patch test without an emulator: patches one app version with the official Morphe patches and
ours, and checks that every one of our patches for that app applied (each finds its place in the
app's code). Prints one JSON line with the result; exit code 1 if a patch of ours failed.

  scripts/upstream/patch_check.py --desktop tools/morphe-desktop-*-all.jar \\
      --official tools/patches-2.0.0.mpp --ours patches/build/libs/patches-x.mpp \\
      --package com.google.android.youtube --version 21.30.216 --apk yt.xapk --out out/check
"""

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
# Official patches that would hide the cast button (as in the regression tests).
DISABLED_OFFICIAL = {"com.google.android.youtube": ["Hide player overlay buttons"]}
# Ours that are off by default but tested too.
ENABLED_OURS = ["DLNA app icon"]


def expected(package: str) -> list[str]:
    """Our patches for {package} that run: on by default, or switched on above."""
    patches = json.loads((ROOT / "patches-list.json").read_text())["patches"]
    names = []
    for p in patches:
        packages = [c.get("packageName") for c in p.get("compatiblePackages") or []]
        if package in packages and (p.get("default") or p["name"] in ENABLED_OURS):
            names.append(p["name"])
    return names


def main() -> None:
    parser = argparse.ArgumentParser()
    for name in ("desktop", "official", "ours", "apk", "out"):
        parser.add_argument(f"--{name}", type=Path, required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    stem = f"{args.package}-{args.version}"
    result_file = args.out / f"{stem}.result.json"
    cmd = ["java", "-jar", str(args.desktop), "patch", "--continue-on-error", "-p", str(args.official)]
    for name in DISABLED_OFFICIAL.get(args.package, []):
        cmd += ["-d", name]
    cmd += ["-p", str(args.ours)]
    for name in ENABLED_OURS:
        cmd += ["-e", name]
    cmd += ["-r", str(result_file), "-o", str(args.out / f"{stem}.apk"), str(args.apk)]
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=2400)
    log = proc.stdout + proc.stderr
    (args.out / f"{stem}.log").write_text(log)

    applied = set(re.findall(r"INFO: Applied: (.+)", log))
    reasons = {}
    try:
        for f in json.loads(result_file.read_text()).get("failedPatches", []):
            reasons[f.get("name", "?")] = (f.get("error") or "").split("\n", 1)[0][:300]
    except (OSError, ValueError):
        pass
    ours = expected(args.package)
    missing = [n for n in ours if n not in applied]
    official_failed = sorted(n for n in reasons if n not in ours)
    report = {
        "package": args.package,
        "version": args.version,
        "ok": not missing and (args.out / f"{stem}.apk").exists(),
        "applied": sorted(n for n in ours if n in applied),
        "failed": {n: reasons.get(n, "not applied") for n in missing},
        # Failures of official patches: noted, they aren't ours to fix (Morphe lists the version).
        "officialFailed": official_failed,
    }
    print(json.dumps(report))
    sys.exit(0 if report["ok"] else 1)


if __name__ == "__main__":
    main()
