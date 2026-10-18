# ADR 0015: Build tooling, signing and release

Status: accepted (2026-10-18)

## Decision

- AGP 9.4, Gradle 9.7, Kotlin built into AGP, NDK 29, CMake 3.22.1, compileSdk 36,
  targetSdk 34 (the Streamer runs Android 14; lint's ExpiredTargetSdkVersion is off),
  minSdk 28. No DI framework.
- Local release builds are signed with the debug key, so release and debug install
  over each other. With `RELEASE_KEYSTORE` set (CI), the release key is used.
- CI (`ci.yml`): unit tests, lint (errors fail) and a debug build on every push and
  pull request; the debug APK is kept as an artifact for 14 days.
- Releases (`release.yml`, release-please): conventional commits keep a release PR
  with the next version and `CHANGELOG.md`. Merging it creates the tag and GitHub
  release, then a job builds the APK signed with the release key and attaches it.
  The version is one annotated line in `app/build.gradle.kts`; `versionCode` is
  derived from it (1.2.3 = 10203), so every release installs over the last one.
- Both workflows share `.github/actions/android-setup` (JDK 25, Gradle cache, NDK,
  CMake) and cache `third_party/openssl/` by the build script's hash and NDK version.
- The signing key secrets are only in the `release` environment, which only `main`
  can deploy to; only the APK job uses it. Pull request runs (even ones that edit the
  workflows) can't read them, and there are no repository-level secrets. Workflow runs
  for pull requests from outside contributors need approval.
- Actions are pinned to their major versions; the repository setting "Allow GitHub
  Actions to create and approve pull requests" must be on for the release PR.
- `scripts/dev.sh [--release]` builds, installs, runs `setup-device.sh`, opens the
  app and follows logs into `build/logcat.log`.
- Periodic sync and throughput stats log only in debug builds (`Diagnostics`).
  UxPlay logs at DEBUG level in debug builds and INFO in release builds.
