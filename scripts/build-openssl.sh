#!/usr/bin/env bash
# Builds a static OpenSSL libcrypto for Android into third_party/openssl/<abi>/.
# Run automatically by the :app:buildOpenSsl Gradle task when the library is missing.
set -euo pipefail

OPENSSL_VERSION="${OPENSSL_VERSION:-3.5.9}"   # 3.5 is the current LTS line
ABIS="${ABIS:-armeabi-v7a}"
API="${API:-28}"                              # must be <= minSdk

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
NDK="${ANDROID_NDK_ROOT:-$(ls -d "$SDK"/ndk/[0-9]* 2>/dev/null | sort -V | tail -1)}"
[ -d "$NDK" ] || { echo "Android NDK not found; set ANDROID_NDK_ROOT" >&2; exit 1; }

case "$(uname -s)" in
    Darwin) HOST_TAG=darwin-x86_64; JOBS="$(sysctl -n hw.ncpu)" ;;   # universal binaries on Apple Silicon too
    *)      HOST_TAG=linux-x86_64;  JOBS="$(nproc)" ;;
esac
export ANDROID_NDK_ROOT="$NDK"
export PATH="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin:$PATH"

WORK="$ROOT/build/openssl"
mkdir -p "$WORK"
TARBALL="$WORK/openssl-$OPENSSL_VERSION.tar.gz"
if [ ! -f "$TARBALL" ]; then
    echo "Downloading OpenSSL $OPENSSL_VERSION"
    curl -fL -o "$TARBALL.part" \
        "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz"
    mv "$TARBALL.part" "$TARBALL"
fi

for ABI in $ABIS; do
    case "$ABI" in
        armeabi-v7a) TARGET=android-arm ;;
        arm64-v8a)   TARGET=android-arm64 ;;
        x86)         TARGET=android-x86 ;;
        x86_64)      TARGET=android-x86_64 ;;
        *) echo "Unknown ABI $ABI" >&2; exit 1 ;;
    esac
    echo "Building libcrypto for $ABI ($TARGET, API $API) with NDK $(basename "$NDK")"
    SRC="$WORK/$ABI"
    rm -rf "$SRC" && mkdir -p "$SRC"
    tar xzf "$TARBALL" -C "$SRC" --strip-components 1
    (
        cd "$SRC"
        ./Configure "$TARGET" -D__ANDROID_API__="$API" no-shared no-tests
        make -j"$JOBS" build_generated libcrypto.a
    )
    OUT="$ROOT/third_party/openssl/$ABI"
    rm -rf "$OUT" && mkdir -p "$OUT/lib" "$OUT/include"
    cp "$SRC/libcrypto.a" "$OUT/lib/"
    cp -R "$SRC/include/openssl" "$OUT/include/"
    echo "-> $OUT"
done
