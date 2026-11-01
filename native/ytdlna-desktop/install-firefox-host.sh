#!/usr/bin/env bash
# Registers ytdlna-desktop as native messaging host for the Firefox extension (macOS).
# Usage: native/ytdlna-desktop/install-firefox-host.sh   (after cargo build --release)
set -euo pipefail
dir="$(cd "$(dirname "$0")" && pwd)"
binary="$dir/target/release/ytdlna-desktop"
[ -x "$binary" ] || { echo "build first: (cd $dir && cargo build --release)" >&2; exit 1; }
hosts="$HOME/Library/Application Support/Mozilla/NativeMessagingHosts"
mkdir -p "$hosts"
cat > "$hosts/uno.speranza.ytdlna.json" <<JSON
{
  "name": "uno.speranza.ytdlna",
  "description": "YT DLNA: casts YouTube to DLNA renderers",
  "path": "$binary",
  "type": "stdio",
  "allowed_extensions": ["ytdlna@speranza.uno"]
}
JSON
echo "Installed: $hosts/uno.speranza.ytdlna.json -> $binary"
