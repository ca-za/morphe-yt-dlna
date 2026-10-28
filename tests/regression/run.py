#!/usr/bin/env python3
"""Automated regression tests of the DLNA cast patch on real app versions.

Patches each given APK (YouTube or YouTube Music, any version, also untested ones), installs it
on the phone emulator and checks every feature end to end against the DLNA renderer on the TV
emulator. Writes one report per APK and a summary.

  tests/regression/run.py apks/*.apkm
  tests/regression/run.py --only start_cast,renderer_plays apks/com.google.android.youtube_21.42.241*.apkm

Prerequisites: both emulators running, the renderer app installed on the TV, MicroG and a
signed-in Google account on the phone (YouTube Music needs it). See tests/regression/README.md.
"""

import argparse
import shutil
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from ytdlna_test import build, runner, scenarios  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("apks", nargs="+", type=Path, help="APK/APKM/XAPK files of YouTube or YouTube Music")
    parser.add_argument("--phone", default="emulator-5556")
    parser.add_argument("--tv", default="emulator-5554")
    parser.add_argument("--phone-avd", default="morphe_phone35", help="AVD started when the phone is not running")
    parser.add_argument("--tv-avd", default="gtv34", help="AVD started when the TV is not running")
    parser.add_argument("--tv-activity", default="io.github.jqssun.airplay/.MainActivity",
                        help="renderer app to start on the TV, empty to skip")
    parser.add_argument("--renderer-url", default="http://127.0.0.1:49152/description.xml",
                        help="renderer description as reached from this computer")
    parser.add_argument("--renderer-device-url", default="http://10.0.2.2:49152/description.xml",
                        help="renderer description as reached from the phone")
    parser.add_argument("--stream-host", default="10.0.2.2", help="phone address as reached from the TV")
    parser.add_argument("--only", help="comma separated scenario names (launch is always run)")
    parser.add_argument("--out", type=Path, default=Path(__file__).resolve().parents[2] / "reports")
    parser.add_argument("--skip-build", action="store_true", help="use the existing patch bundle")
    args = parser.parse_args()

    if args.only:
        keep = set(args.only.split(",")) | {"launch", "no_crash"}
        scenarios.SCENARIOS[:] = [s for s in scenarios.SCENARIOS if s[0] in keep]

    run_dir = args.out / time.strftime("%Y%m%d-%H%M%S")
    run_dir.mkdir(parents=True)
    print(f"Reports: {run_dir}")

    if args.skip_build:
        bundle = max((b for b in (build.ROOT / "patches/build/libs").glob("patches-*.mpp")
                      if not b.name.endswith("-sources.mpp")), key=lambda b: b.stat().st_mtime)
    else:
        print("Building patch bundle ...", flush=True)
        bundle = build.build_bundle()
    # Own copy: the bundle can be rebuilt (fixes) while this run goes on.
    shutil.copy(bundle, run_dir / "patches.mpp")
    bundle = run_dir / "patches.mpp"
    apks = sorted((build.apk_info(p) for p in args.apks), key=lambda a: (a.app, _version_key(a.version)))
    reports = []
    for apk in apks:
        print(f"\n== {apk.app} {apk.version} ({apk.path.name})", flush=True)
        try:
            reports.append(runner.run_apk(apk, bundle, args, run_dir / f"{apk.app}-{apk.version}"))
        except Exception as e:  # Keep going with the next version.
            print(f"  run aborted: {e}", flush=True)
            reports.append({"app": apk.app, "version": apk.version, "patch": {}, "verdict": f"error: {e}",
                            "results": []})
        print(f"  -> {reports[-1]['verdict']}", flush=True)

    runner.summary(reports, run_dir / "summary.md")
    print(f"\nSummary: {run_dir / 'summary.md'}")
    return 0 if all(r["verdict"] == "pass" for r in reports) else 1


def _version_key(version: str):
    return [int(p) if p.isdigit() else p for p in version.replace("-", ".").split(".")]


if __name__ == "__main__":
    sys.exit(main())
