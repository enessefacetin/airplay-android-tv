# AGENTS.md

Context for AI agents and developers working on this repository.

## What this is

An AirPlay receiver app for Android TV, built for the **Google TV Streamer 4K**
(Android 14, 32-bit `armeabi-v7a` userspace). iPhones, iPads and Macs can mirror
their screen, play music and send video (YouTube) to the TV. Starting AirPlay wakes
the TV from standby over HDMI-CEC.

- Behaviour spec: [`docs/SPEC.md`](docs/SPEC.md)
- Decisions and why: [`docs/adr/`](docs/adr/README.md)
- What's next: [`docs/ROADMAP.md`](docs/ROADMAP.md)

Read these before changing behaviour. Add an ADR for a new significant decision and
update the spec and roadmap in the same change.

## Architecture

```
iPhone/Mac ──AirPlay──▶ UxPlay lib (C, third_party/)  ──JNI──▶ Kotlin app
                         pairing, FairPlay, RTSP,        app/src/main/cpp/airplay_jni.cpp
                         decryption, NTP, HLS proxy
```

| Area | Files |
|---|---|
| JNI bridge: server lifecycle, callbacks, clock mapping, ALAC decode, PIN, DACP IDs | `app/src/main/cpp/airplay_jni.cpp` |
| mDNS TXT records (replaces UxPlay's Avahi backend) | `app/src/main/cpp/android_dnssd.c` |
| Native build (UxPlay, libplist, ALAC, OpenSSL) | `app/src/main/cpp/CMakeLists.txt` |
| JNI interface and callback list | `airplay/AirPlayNative.kt` |
| Foreground service: owns everything, routes callbacks, pause detection | `AirPlayService.kt` |
| Shared state between service and screens | `AirPlaySession.kt` |
| Video decode (H.264/H.265, keyframe cache, scheduling) | `airplay/VideoDecoder.kt`, `airplay/VideoNal.kt` |
| Audio (AAC/PCM, AudioTrack, sync) | `airplay/AudioPlayer.kt` |
| A/V sync latency | `airplay/AvSync.kt` |
| AirPlay video (HLS) | `airplay/HlsPlayer.kt` (Media3 ExoPlayer) |
| mDNS registration | `airplay/MdnsAdvertiser.kt` |
| TV remote → sender (DACP) | `airplay/RemoteControl.kt` |
| Track metadata parser | `airplay/Dmap.kt` |
| Waking the TV | `TvWaker.kt`, `BootReceiver.kt`, `KeepAliveJob.kt` |
| Screens | `MainActivity.kt`, `SettingsActivity.kt`, `PlaybackActivity.kt`, `TvUi.kt` |
| Settings storage | `ReceiverSettings.kt` |

Kotlin paths are under `app/src/main/java/dev/matinzd/airplaytv/`.

Vendored code is in `third_party/` with versions and **local patches** listed in
`third_party/VERSIONS.md`. Don't edit vendored code without recording the patch there.

## Build, install, run

Requirements: Android Studio's SDK with NDK `29.0.13599879` and CMake `3.22.1`. Use
Android Studio's JDK:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:installDebug        # or :app:installRelease (signed with the debug key locally)
scripts/dev.sh                     # build, install, device setup, open app, follow logs
scripts/dev.sh --release           # same with the release build (normal logging)
./gradlew :app:testDebugUnitTest   # unit tests (JVM, no device), in app/src/test/
```

Unit tests cover pure logic only (sync rules, parsers, session state). Keep new logic
free of Android types where it is easy, so it can be tested the same way; pass a clock
instead of calling `System.nanoTime()` when timing matters (see `AvSync`).

The first build compiles OpenSSL (`scripts/build-openssl.sh`, run by Gradle
automatically), which takes a few minutes.

CI (`.github/workflows/ci.yml`) runs the unit tests, lint and a debug build on every push
and pull request. Lint errors fail the build; keep it at 0 errors.

Releases are automatic (release-please, `.github/workflows/release.yml`): conventional
commits on `main` keep a "release PR" up to date with the version bump and
`CHANGELOG.md`. `feat` bumps the minor version, `fix`/`perf` the patch (before 1.0).
Merging the release PR tags it and attaches a signed APK to the GitHub release. Don't
edit the version by hand: it is the `x-release-please-version` line in
`app/build.gradle.kts`, and `versionCode` is computed from it. The signing key secrets
are only in the `release` environment (main only); never add repository-level secrets
or use them in `ci.yml`.

## Working with the TV

The TV is reachable over adb at `10.0.0.242:5555` (check with `adb devices`).

```bash
adb() { "$HOME/Library/Android/sdk/platform-tools/adb" -s 10.0.0.242:5555 "$@"; }
adb exec-out screencap -p > screen.png          # screenshot (then look at it)
adb shell input keyevent KEYCODE_DPAD_CENTER    # press remote keys (DPAD_*, BACK, HOME, MEDIA_*)
adb logcat -v time -s AirPlayService AirPlayNative UxPlay VideoDecoder AudioPlayer AvSync \
  HlsPlayer RemoteControl MdnsAdvertiser TvWaker AndroidRuntime
adb shell dumpsys activity activities | grep topResumedActivity
scripts/setup-device.sh                         # one-time permissions after a fresh install
```

Testing without a phone:

- Pairing code screen: send a pair request yourself (needs the PIN setting on):
  `printf 'POST /pair-pin-start RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n' | nc -w 2 10.0.0.242 7000`
- `GET /info` on port 7000 shows the receiver answers.
- Mirroring, music, video, pairing and the remote need a real iPhone or Mac: ask the
  user to start them, then use screenshots, key events and logs.

**Installing a new build restarts the receiver and ends any running AirPlay
session.** Tell the user to start AirPlay again after each install.

Debug builds log sync/throughput stats every 5 s (`Diagnostics.enabled`); release
builds don't.

## Conventions

- Keep it simple: plain framework views (no Compose, no AndroidX UI), no DI framework,
  no new dependencies without a clear need. Media3 ExoPlayer is the only library.
- Commit after each step. Conventional commits: `<type>(<scope>): <subject>`,
  imperative, ≤ 72 characters; the body explains why. Never force-push or rewrite
  published history.
- Docs and UI text: simple, clear English (ASD-STE100 style); avoid em dashes.
- Verify on the device (screenshot or logs) before saying a change works, and say
  clearly when something is not yet tested.
- Every UxPlay callback must be registered (some are called without a null check).
- Native callbacks run on UxPlay's threads, never the main thread.

## Gotchas learned the hard way

- **A/V sync feedback loops**: never include anything that depends on the AudioTrack's
  queue in audio's latency need (inserted silence is queued). See ADR 0009.
- Audio and video may use different sender clocks; each stream has its own offset.
- A Surface can feed only one MediaCodec: release the old decoder before starting a
  new one, and stop ExoPlayer before mirroring resumes.
- Setting a TextView's text or re-laying out the screen restarts a marquee: only set
  changed values; scale views instead of resizing them.
- Pausing AirPlay music sends no message; packets just stop (pause = 1.2 s gap).
  Pausing also ends the audio stream, which clears the cover art in UxPlay.
- The iPhone doesn't notice a dropped music connection; Back pauses instead.
- The DACP-ID header has no leading zeros; the `iTunes_Ctrl_` service name has them.
- UxPlay's random PIN is single-use; the shown PIN is held as a fixed PIN until paired.
- The ALAC decoder needs `TARGET_RT_LITTLE_ENDIAN=1` on ARM.
- macOS: use `sed -i ''`; in zsh, don't name a shell variable `path` (it is `$PATH`).
