#!/usr/bin/env bash
# Builds the patch bundle, patches YouTube or YouTube Music together with the
# official Morphe patches and installs the result on a device.
#
# Usage: scripts/patch-and-install.sh yt|music [adb-serial]
#
# Expects in the project folder (all git-ignored):
#   apks/com.google.android.youtube_*.apkm, apks/com.google.android.apps.youtube.music_*.apkm
#   tools/morphe-desktop-*-all.jar, tools/patches-*.mpp (official Morphe patches)
set -euo pipefail

cd "$(dirname "$0")/.."
source ./dev-env.sh

app="${1:?usage: $0 yt|music [adb-serial]}"
serial="${2:-emulator-5556}"

# Official patches that hide the cast button by default are left out,
# otherwise the DLNA picker can not be reached.
case "$app" in
  yt) apk=$(ls apks/com.google.android.youtube_*.apkm | head -1)
      disable=(-d "Hide player overlay buttons") ;;
  music) apk=$(ls apks/com.google.android.apps.youtube.music_*.apkm | head -1)
      disable=() ;;
  *) echo "unknown app: $app" >&2; exit 1 ;;
esac

# The newest of each (older ones may be lying around).
desktop=$(ls -t tools/morphe-desktop-*-all.jar | head -1)
official=$(ls -t tools/patches-*.mpp | head -1)

./gradlew buildAndroid --console=plain -q
ours=$(ls -t patches/build/libs/patches-*.mpp | head -1)

mkdir -p out
java -jar "$desktop" patch \
  -p "$official" ${disable[@]+"${disable[@]}"} \
  -p "$ours" \
  -o "out/$app-dlna.apk" \
  "$apk" 2>&1 | grep -E 'INFO: (Applied|Saved to /)|SEVERE|ERROR|failed' || true

adb -s "$serial" install -r "out/$app-dlna.apk"
