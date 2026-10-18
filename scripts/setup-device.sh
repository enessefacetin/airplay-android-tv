#!/usr/bin/env bash
# One-time setup on the Android TV, over adb.
#   ANDROID_SERIAL=10.0.0.242:5555 scripts/setup-device.sh
set -uo pipefail

PKG=dev.matinzd.airplaytv
ADB="${ADB:-adb}"
tv() { "$ADB" shell "$@"; }

echo "== Granting permissions to $PKG"
# Start the playback screen from the background when an iPhone connects.
tv appops set "$PKG" SYSTEM_ALERT_WINDOW allow
# Turn the screen on from standby (Android 14+ app-op; ignored where it doesn't exist).
tv appops set "$PKG" TURN_SCREEN_ON allow 2>/dev/null || true
# Fallback path: a full-screen-intent notification.
tv appops set "$PKG" USE_FULL_SCREEN_INTENT allow 2>/dev/null || true
tv pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
# Keep the listener alive in standby: no Doze/app-standby restrictions.
tv dumpsys deviceidle whitelist +"$PKG"
tv appops set "$PKG" RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true

echo "== Starting the receiver (opening the app starts its service)"
tv am start -n "$PKG/.MainActivity"

echo "== Device state (for wake-from-standby)"
echo -n "hdmi_control_enabled (CEC, null = default on): "; tv settings get global hdmi_control_enabled
echo -n "Low power standby: "; tv dumpsys power | grep -m1 -i "mLowPowerStandbyActive" | tr -d ' '
tv appops get "$PKG" SYSTEM_ALERT_WINDOW
