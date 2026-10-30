#!/usr/bin/env bash
# Downloads the official Morphe patches and Morphe Desktop recorded in
# scripts/upstream/state.json into tools/ (what patch-and-install.sh and the regression tests use).
set -euo pipefail
cd "$(dirname "$0")/../.."
patches=$(python3 -c 'import json;print(json.load(open("scripts/upstream/state.json"))["morphePatches"])')
desktop=$(python3 -c 'import json;print(json.load(open("scripts/upstream/state.json"))["morpheDesktop"])')
mkdir -p tools
gh release download "v$patches" -R MorpheApp/morphe-patches -p "patches-$patches.mpp" -D tools --skip-existing
gh release download "v$desktop" -R MorpheApp/morphe-desktop -p "morphe-desktop-$desktop-all.jar" -D tools --skip-existing
echo "tools/patches-$patches.mpp, tools/morphe-desktop-$desktop-all.jar"
