#!/usr/bin/env bash
# Build, install and start the receiver on the TV, then follow its logs.
# Output goes to build/dev.log and build/logcat.log as well as the terminal.
#   scripts/dev.sh                      # uses 10.0.0.242:5555
#   ANDROID_SERIAL=host:port scripts/dev.sh
set -euo pipefail
cd "$(dirname "$0")/.."

export ANDROID_SERIAL="${ANDROID_SERIAL:-10.0.0.242:5555}"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
STUDIO_JDK="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
if [ -z "${JAVA_HOME:-}" ] && [ -d "$STUDIO_JDK" ]; then
    export JAVA_HOME="$STUDIO_JDK"
fi

mkdir -p build
{
    "$ADB" connect "$ANDROID_SERIAL" || true
    ./gradlew :app:installDebug
    ADB="$ADB" bash scripts/setup-device.sh
    "$ADB" shell am start -n dev.matinzd.airplaytv/.MainActivity
} 2>&1 | tee build/dev.log

echo "== Following app logs (Ctrl-C to stop)"
"$ADB" logcat -c
"$ADB" logcat -v time -s AirPlayService AirPlayNative UxPlay VideoDecoder AudioPlayer AvSync \
    HlsPlayer ExoPlayerImpl MediaCodecVideoRenderer MdnsAdvertiser TvWaker AudioTrack AndroidRuntime DEBUG | tee build/logcat.log
