#!/usr/bin/env bash
# Registers ytdlna-desktop as native messaging host for the Firefox extension (macOS).
# Usage: native/ytdlna-desktop/install-firefox-host.sh   (after cargo build --release)
#
# The program goes into a small app bundle (YT DLNA.app, no Dock icon): macOS gives the media keys
# and Control Center's "now playing" to apps, which a bare program is not.
set -euo pipefail
dir="$(cd "$(dirname "$0")" && pwd)"
binary="$dir/target/release/ytdlna-desktop"
[ -x "$binary" ] || { echo "build first: (cd $dir && cargo build --release)" >&2; exit 1; }

app="$dir/target/release/YT DLNA.app"
mkdir -p "$app/Contents/MacOS"
cp "$binary" "$app/Contents/MacOS/ytdlna-desktop"
cat > "$app/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleIdentifier</key><string>uno.speranza.ytdlna</string>
  <key>CFBundleName</key><string>YT DLNA</string>
  <key>CFBundleDisplayName</key><string>YT DLNA</string>
  <key>CFBundleExecutable</key><string>ytdlna-desktop</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleVersion</key><string>0.1.0</string>
  <key>CFBundleShortVersionString</key><string>0.1.0</string>
  <key>LSUIElement</key><true/>
  <key>LSMinimumSystemVersion</key><string>12.0</string>
</dict>
</plist>
PLIST
# Signed ad hoc, as macOS wants for apps on Apple silicon.
codesign --force --sign - "$app" >/dev/null 2>&1 || true

hosts="$HOME/Library/Application Support/Mozilla/NativeMessagingHosts"
mkdir -p "$hosts"
cat > "$hosts/uno.speranza.ytdlna.json" <<JSON
{
  "name": "uno.speranza.ytdlna",
  "description": "YT DLNA: casts YouTube to DLNA renderers",
  "path": "$app/Contents/MacOS/ytdlna-desktop",
  "type": "stdio",
  "allowed_extensions": ["ytdlna@speranza.uno"]
}
JSON
echo "Installed: $hosts/uno.speranza.ytdlna.json -> $app"
