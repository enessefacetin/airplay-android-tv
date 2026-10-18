# ADR 0003: Build OpenSSL libcrypto from source, armeabi-v7a only

Status: accepted (2026-10-18)

## Context

UxPlay needs OpenSSL `libcrypto`; the NDK doesn't include it. The Google TV
Streamer 4K reports `ro.product.cpu.abi = armeabi-v7a` (32-bit userspace) even
though its CPU is 64-bit.

## Decision

- `scripts/build-openssl.sh` downloads OpenSSL 3.5 (LTS) and builds a static
  `libcrypto.a` per ABI into `third_party/openssl/<abi>/` (git-ignored).
- The Gradle task `:app:buildOpenSsl` runs it automatically before CMake when the
  library is missing.
- Build only `armeabi-v7a` (`targetAbis` in `app/build.gradle.kts`).

## Consequences

- The first build takes a few minutes longer. CI must have the NDK installed.
- Supporting other devices means adding ABIs to `targetAbis`.
