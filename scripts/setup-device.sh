#!/usr/bin/env bash
# One-time setup on the Android TV, over adb.
#   ANDROID_SERIAL=10.0.0.242:5555 scripts/setup-device.sh
set -uo pipefail

PKG=dev.matinzd.airplaytv
ADB="${ADB:-adb}"
sh() { "$ADB" shell "$@"; }

echo "== Granting permissions to $PKG"
# Start the playback screen from the background when an iPhone connects.
sh appops set "$PKG" SYSTEM_ALERT_WINDOW allow
# Turn the screen on from standby (Android 14+ app-op; ignored where it doesn't exist).
sh appops set "$PKG" TURN_SCREEN_ON allow 2>/dev/null || true
# Fallback path: a full-screen-intent notification.
sh appops set "$PKG" USE_FULL_SCREEN_INTENT allow 2>/dev/null || true
sh pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
# Keep the listener alive in standby: no Doze/app-standby restrictions.
sh dumpsys deviceidle whitelist +"$PKG"
sh appops set "$PKG" RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true

echo "== Starting the receiver service"
sh am start-foreground-service -n "$PKG/.AirPlayService"

echo "== Device state (for wake-from-standby)"
echo -n "hdmi_control_enabled (CEC, null = default on): "; sh settings get global hdmi_control_enabled
echo -n "Low power standby: "; sh dumpsys power | grep -m1 -i "mLowPowerStandbyActive" | tr -d ' '
sh appops get "$PKG" SYSTEM_ALERT_WINDOW
