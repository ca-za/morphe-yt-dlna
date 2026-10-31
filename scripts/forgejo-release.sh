#!/usr/bin/env bash
# Publishes patched YouTube and YouTube Music APKs of a morphe-yt-dlna release on Forgejo
# (git.speranza.uno/carlo/yt-dlna-builds), where Obtainium picks them up.
#
# Usage: scripts/forgejo-release.sh [options] [tag]
#
#   tag              morphe-yt-dlna release, e.g. v0.1.15-alpha (default: the latest on GitHub)
#   --notes TEXT     what is new, as a "Neu: ..." paragraph of the release text
#   --yt VERSION     YouTube version (default: the newest Morphe recommends that we verified)
#   --music VERSION  YouTube Music version (likewise)
#   --dry-run        patch and check only, print the release text, publish nothing
#
# Per app: the official Morphe patches (scripts/upstream/state.json) and our bundle from the
# GitHub release, with "DLNA app icon" (Night). Signed with the desktop patcher's key
# (tools/morphe-data/morphe.keystore): the script stops if the APK has another certificate, as
# installed apps would then not update. The original APKs come from apks/ (APKMirror bundles).
set -euo pipefail

cd "$(dirname "$0")/.."

FORGEJO=https://git.speranza.uno
REPO=carlo/yt-dlna-builds
GITHUB_REPO=ca-za/morphe-yt-dlna
KEYSTORE=tools/morphe-data/morphe.keystore
# Certificate of the releases so far (CN=Morphe).
CERT_SHA256=424d70c3b4def1536387f0ef3d686712f41d749d2e0291d008848755740bbaba
YT=com.google.android.youtube
MUSIC=com.google.android.apps.youtube.music

notes="" yt_version="" music_version="" dry_run=false tag=""
while [ $# -gt 0 ]; do
  case "$1" in
    --notes) notes="${2:?--notes needs a text}"; shift 2 ;;
    --yt) yt_version="${2:?--yt needs a version}"; shift 2 ;;
    --music) music_version="${2:?--music needs a version}"; shift 2 ;;
    --dry-run) dry_run=true; shift ;;
    -h|--help) sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) echo "unknown option: $1" >&2; exit 1 ;;
    *) tag="$1"; shift ;;
  esac
done

source ./dev-env.sh >/dev/null

die() { echo "error: $*" >&2; exit 1; }

[ -n "$tag" ] || tag=$(gh release list -R "$GITHUB_REPO" -L 1 --json tagName -q '.[0].tagName')
version="${tag#v}"
git rev-parse -q --verify "refs/tags/$tag" >/dev/null || git fetch -q --tags origin
git rev-parse -q --verify "refs/tags/$tag" >/dev/null || die "tag $tag not found"

# Forgejo login from git's credential store (the same as for git push).
cred=$(printf 'protocol=https\nhost=%s\n\n' "${FORGEJO#https://}" | git credential fill 2>/dev/null) \
  || die "no git credentials for $FORGEJO"
user=$(sed -n 's/^username=//p' <<< "$cred")
password=$(sed -n 's/^password=//p' <<< "$cred")
api() { curl -sS --fail-with-body -u "$user:$password" "$@"; }
API="$FORGEJO/api/v1/repos/$REPO"

if api -o /dev/null "$API/releases/tags/$tag" 2>/dev/null; then
  $dry_run || die "release $tag exists on Forgejo already"
  echo "note: release $tag exists on Forgejo already (dry run)"
fi

# The newest version Morphe recommends (not experimental) in this release that we verified.
pick_version() {
  local package="$1"
  git show "$tag:patches-list.json" | python3 -c '
import json, sys
package = sys.argv[1]
verified = set(json.load(open("tests/regression/verified.json")).get(package, []))
key = lambda v: [int(x) for x in v.split(".")]
for patch in json.load(sys.stdin)["patches"]:
    if patch["name"] != "DLNA cast":
        continue
    for c in patch["compatiblePackages"] or []:
        if c["packageName"] == package:
            ok = [t["version"] for t in c["targets"] if not t["isExperimental"] and t["version"] in verified]
            print(max(ok, key=key) if ok else "")
' "$package"
}
[ -n "$yt_version" ] || yt_version=$(pick_version "$YT")
[ -n "$music_version" ] || music_version=$(pick_version "$MUSIC")
[ -n "$yt_version" ] || die "no recommended and verified YouTube version, use --yt"
[ -n "$music_version" ] || die "no recommended and verified YouTube Music version, use --music"

source_apk() {
  local found
  found=$(ls apks/"$1"_"$2"-*.apkm apks/"$1"_"$2"-*.apk 2>/dev/null | head -1 || true)
  [ -n "$found" ] || die "apks/ has no $1 $2 (download it from APKMirror)"
  echo "$found"
}
yt_apk=$(source_apk "$YT" "$yt_version")
music_apk=$(source_apk "$MUSIC" "$music_version")

morphe_patches=$(python3 -c 'import json;print(json.load(open("scripts/upstream/state.json"))["morphePatches"])')
morphe_desktop=$(python3 -c 'import json;print(json.load(open("scripts/upstream/state.json"))["morpheDesktop"])')
scripts/upstream/fetch-tools.sh >/dev/null
[ -f "$KEYSTORE" ] || die "$KEYSTORE is missing: without it the APKs get another key"

out=out/release
mkdir -p "$out"
bundle="$out/patches-$version.mpp"
gh release download "$tag" -R "$GITHUB_REPO" -p "patches-$version.mpp" -D "$out" --clobber

apksigner=$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)apksigner

patch_app() {
  local name="$1" apk="$2" app_version="$3"
  shift 3
  local file="$out/$name-$app_version-dlna-$version.apk" log="$out/$name-$app_version-$version.log"
  echo "Patching $name $app_version ..." >&2
  java -jar "tools/morphe-desktop-$morphe_desktop-all.jar" patch \
    -p "tools/patches-$morphe_patches.mpp" "$@" \
    -p "$bundle" -e "DLNA app icon" -O style=night \
    --keystore "$KEYSTORE" \
    -o "$file" "$apk" > "$log" 2>&1 || die "patching $name failed, see $log"
  grep -q "Applied: DLNA cast" "$log" || die "DLNA cast not applied to $name, see $log"
  ! grep -qE "SEVERE|FAILED" "$log" || die "patch errors for $name, see $log"
  "$apksigner" verify --print-certs "$file" | grep -q "SHA-256 digest: $CERT_SHA256" \
    || die "$file is not signed with the release key"
  echo "$file"
}
# The official patch that hides the cast button is left out in YouTube (as in patch-and-install.sh).
yt_file=$(patch_app youtube "$yt_apk" "$yt_version" -d "Hide player overlay buttons")
music_file=$(patch_app youtube-music "$music_apk" "$music_version")

body="Gepatcht mit den offiziellen Morphe-Patches $morphe_patches und morphe-yt-dlna $version (DLNA app icon: Night).

Enthält die von Morphe empfohlenen und bei uns im Emulator getesteten Versionen: YouTube $yt_version, YouTube Music $music_version."
[ -n "$notes" ] && body="$body

Neu: $notes"
body="$body

Signiert mit dem Schlüssel des Desktop-Patchers. Anmeldung braucht MicroG."

echo
echo "== $tag"
echo "$body"
echo
ls -l "$yt_file" "$music_file" | awk '{print $5, $9}'

if $dry_run; then
  echo "Dry run: nothing published."
  exit 0
fi

release_id=$(python3 -c '
import json, sys
print(json.dumps({"tag_name": sys.argv[1], "target_commitish": "main", "name": sys.argv[1],
                  "prerelease": True, "body": sys.argv[2]}))' "$tag" "$body" \
  | api -H "Content-Type: application/json" -d @- "$API/releases" \
  | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')
for file in "$yt_file" "$music_file"; do
  echo "Uploading $(basename "$file") ..."
  api -o /dev/null -F "attachment=@$file;type=application/vnd.android.package-archive" \
    "$API/releases/$release_id/assets?name=$(basename "$file")"
done
echo "Published: $FORGEJO/$REPO/releases/tag/$tag"
