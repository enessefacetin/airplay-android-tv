# ADR 0015: Build tooling, signing and release

Status: accepted (2026-10-18)

## Decision

- AGP 9.4, Gradle 9.7, Kotlin built into AGP, NDK 29, CMake 3.22.1, compileSdk 36,
  targetSdk 34 (the Streamer runs Android 14; lint's ExpiredTargetSdkVersion is off),
  minSdk 28. No DI framework.
- Local release builds are signed with the debug key, so release and debug install
  over each other. With `RELEASE_KEYSTORE` set (CI), the release key is used.
- `.github/workflows/release.yml` (manual) builds a signed APK and publishes a
  GitHub release named after `versionName`.
- `scripts/dev.sh [--release]` builds, installs, runs `setup-device.sh`, opens the
  app and follows logs into `build/logcat.log`.
- Periodic sync and throughput stats log only in debug builds (`Diagnostics`).
  UxPlay logs at DEBUG level in debug builds and INFO in release builds.
