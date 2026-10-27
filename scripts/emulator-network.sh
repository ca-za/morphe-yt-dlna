#!/usr/bin/env bash
# Connects the phone emulator and the TV emulator through the host for testing.
# Emulators can not reach each other directly, and SSDP multicast does not pass between them.
#
#   phone -> 10.0.2.2:49152 -> host -> TV:49152   (DLNA control, device description)
#   TV    -> 10.0.2.2:8720  -> host -> phone:8720 (stream proxy)
#
# In the app's DLNA dialog then use:
#   "Add device by address": http://10.0.2.2:49152/description.xml
#   "Stream address":        10.0.2.2
#
# Usage: scripts/emulator-network.sh [phone-serial] [tv-serial]
set -euo pipefail
phone="${1:-emulator-5556}"
tv="${2:-emulator-5554}"
adb -s "$tv" forward tcp:49152 tcp:49152
adb -s "$phone" forward tcp:8720 tcp:8720
adb forward --list
