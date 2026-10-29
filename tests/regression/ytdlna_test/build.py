"""Building the patch bundle and patching APKs."""

import json
import os
import re
import subprocess
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]

APPS = {
    "com.google.android.youtube": "yt",
    "com.google.android.apps.youtube.music": "music",
}
# Official patches that would hide the cast button, which the DLNA picker is reached through.
DISABLED_OFFICIAL = {"yt": ["Hide player overlay buttons"], "music": []}


@dataclass
class ApkInfo:
    path: Path
    package: str
    version: str

    @property
    def app(self) -> str:
        return APPS[self.package]


@dataclass
class PatchResult:
    apk: Path
    ok: bool
    applied: list[str] = field(default_factory=list)
    failed: list[str] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)
    reasons: dict = field(default_factory=dict)


def env() -> dict:
    e = dict(os.environ)
    e.setdefault("JAVA_HOME", "/opt/homebrew/opt/openjdk@21")
    e["PATH"] = f"{e['JAVA_HOME']}/bin:{e['PATH']}"
    return e


def apk_info(path: Path) -> ApkInfo:
    """Package and version of an APK, APKM or XAPK."""
    if path.suffix in (".apkm", ".xapk"):
        with zipfile.ZipFile(path) as z:
            for name in ("info.json", "manifest.json"):
                if name in z.namelist():
                    info = json.loads(z.read(name))
                    package = info.get("pname") or info.get("package_name")
                    version = info.get("release_version") or info.get("version_name")
                    if package and version:
                        return ApkInfo(path, package, str(version))
    out = subprocess.run([_aapt2(), "dump", "badging", str(path)], capture_output=True, text=True).stdout
    match = re.search(r"package: name='([^']+)'.*versionName='([^']+)'", out)
    if not match:
        raise RuntimeError(f"Can not read package and version of {path}")
    return ApkInfo(path, match.group(1), match.group(2))


def _aapt2() -> str:
    tools = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk")) / "build-tools"
    return str(sorted(tools.glob("*/aapt2"))[-1])


def build_bundle() -> Path:
    """Builds our patch bundle (including the Rust proxy) once per run."""
    secrets = dict(env())
    secrets.setdefault("GITHUB_ACTOR", subprocess.run(["gh", "api", "user", "-q", ".login"],
                                                      capture_output=True, text=True).stdout.strip())
    secrets.setdefault("GITHUB_TOKEN", subprocess.run(["gh", "auth", "token"],
                                                      capture_output=True, text=True).stdout.strip())
    subprocess.run(["./gradlew", "buildAndroid", "--console=plain", "-q"], cwd=ROOT, env=secrets, check=True)
    # The Gradle and Kotlin daemons hold several GB; the emulators need that memory.
    subprocess.run(["./gradlew", "--stop", "-q"], cwd=ROOT, env=secrets, check=False)
    return max((b for b in (ROOT / "patches/build/libs").glob("patches-*.mpp") if not b.name.endswith("-sources.mpp")), key=lambda b: b.stat().st_mtime)


def patch(apk: ApkInfo, bundle: Path, out_dir: Path) -> PatchResult:
    """Patches with the official Morphe patches and ours (with test hooks), forcing unknown versions."""
    desktop = sorted((ROOT / "tools").glob("morphe-desktop-*-all.jar"))[-1]
    official = sorted((ROOT / "tools").glob("patches-*.mpp"))[-1]
    out = out_dir / f"{apk.app}-{apk.version}-patched.apk"
    cmd = ["java", "-jar", str(desktop), "patch", "-f", "--continue-on-error", "-p", str(official)]
    for name in DISABLED_OFFICIAL[apk.app]:
        cmd += ["-d", name]
    result_file = out_dir / "patch-result.json"
    cmd += ["-p", str(bundle), "-e", "DLNA cast", "-O", "testHooks=true"]
    # Our optional patches, for testing them (YTDLNA_EXTRA_PATCHES="Name 1,Name 2").
    for name in filter(None, (n.strip() for n in os.environ.get("YTDLNA_EXTRA_PATCHES", "").split(","))):
        cmd += ["-e", name]
    cmd += ["-r", str(result_file), "-o", str(out), str(apk.path)]
    proc = subprocess.run(cmd, capture_output=True, text=True, env=env(), timeout=1800)
    log = proc.stdout + proc.stderr
    (out_dir / "patch.log").write_text(log)
    result = PatchResult(out, out.exists())
    result.applied = re.findall(r"INFO: Applied: (.+)", log)
    result.failed = re.findall(r"(?:FAILED|failed): ([^\n]+)", log)
    result.errors = [l for l in log.splitlines() if "Exception" in l or l.startswith("ERROR")][:20]
    # The log only names failed patches; the result file has the reason.
    try:
        failed = json.loads(result_file.read_text()).get("failedPatches", [])
        result.reasons = {f.get("name", "?"): _first_line(f.get("error", "")) for f in failed}
    except (OSError, ValueError):
        pass
    return result


def _first_line(error: str) -> str:
    """'app.morphe.patcher.patch.PatchException: X' -> 'PatchException: X'."""
    line = error.split("\n", 1)[0]
    head, _, message = line.partition(": ")
    return f"{head.rsplit('.', 1)[-1]}: {message}" if message else line
