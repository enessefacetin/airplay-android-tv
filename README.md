# AirPlay receiver for Android TV

[![CI](https://github.com/matinzd/airplay-android-tv/actions/workflows/ci.yml/badge.svg)](https://github.com/matinzd/airplay-android-tv/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/matinzd/airplay-android-tv)](https://github.com/matinzd/airplay-android-tv/releases/latest)

Mirror an iPhone/iPad/Mac screen or AirPlay music to an Android TV device (built and tested on a
Google TV Streamer 4K, Android 14). Starting AirPlay wakes the device from standby, and HDMI-CEC
turns on the TV and switches to its input. Turning the TV off ends the session.

## Features

- **Screen mirroring** from iPhone, iPad and Mac, up to 4K (H.265) at 60 fps, with low delay
  (the TV's game mode is requested). A phone held upright fills the side bars with a blurred
  copy of its screen.
- **AirPlay music** with cover art, title, artist and album, playing/paused state and track
  progress. Long titles scroll instead of moving the layout.
- **AirPlay video** from apps such as YouTube and Safari, with play, pause, seek and stop from
  the phone.
- **Lip-sync** between sound and picture; an optional low-latency mode for games.
- **Wakes the TV** from standby when AirPlay starts (HDMI-CEC turns it on and switches input).
  The receiver stays in the AirPlay list while the TV is off.
- **TV remote**: play/pause and previous/next track for music; Back pauses music or ends
  mirroring.
- **Optional pairing code** for new devices; paired devices are remembered.
- **10-foot UI** for the remote, with burn-in protection on the music screen (the content
  moves slowly and dims after 5 minutes).
- **Settings** for the AirPlay name, pairing code, 4K and low-latency mirroring.

## Install

Download the APK from the [latest release](https://github.com/matinzd/airplay-android-tv/releases/latest)
and install it with `adb install airplay-tv-<version>.apk`, then run `scripts/setup-device.sh` once
(see [Build and run](#build-and-run)). Only `armeabi-v7a` is built.

## Documentation

- [Specification](docs/SPEC.md): what the receiver does, in detail
- [Architecture decisions](docs/adr/README.md): why it works the way it does
- [Roadmap](docs/ROADMAP.md): what's done and what's next
- [AGENTS.md](AGENTS.md): context for development (also for AI agents)

## How it works

- **Protocol:** [UxPlay](https://github.com/FDH2/UxPlay)'s `lib/` (pairing, FairPlay, RTSP, mirror and
  audio streams) built with the NDK, with OpenSSL `libcrypto`, libplist and Apple's ALAC decoder.
  Sources are vendored in `third_party/` (versions in `third_party/VERSIONS.md`).
- **Discovery:** `android_dnssd.c` builds the `_airplay._tcp` / `_raop._tcp` TXT records and
  `MdnsAdvertiser` registers them with `NsdManager`.
- **Video:** H.264, or H.265 for 4K mirroring from senders that support it, to a low-latency
  MediaCodec decoder (`c2.mtk.avc.decoder.lowlatency` on the Streamer). The playback screen asks the TV for low-latency game mode (ALLM).
- **Audio:** AAC-ELD (mirroring) through MediaCodec, ALAC (music) decoded natively, both to AudioTrack.
- **AirPlay video** (YouTube's AirPlay button, Safari video): UxPlay fetches the HLS playlists through
  the iPhone and serves them on `http://localhost:7000/`; `HlsPlayer` plays that with Media3 ExoPlayer
  and follows play/pause/seek/stop from the iPhone.
- **Lip-sync:** audio and video carry the sender's timestamps (NTP-synced by UxPlay). `AvSync` picks
  one latency from what each stream needs; video frames are scheduled with
  `releaseOutputBuffer(index, timeNs)` and audio inserts silence or drops packets to stay on the same
  timeline. AirPlay music plays at the exact time the sender intends (about 2 s after it arrives).
- **Wake on AirPlay:** `AirPlayService` is a foreground service holding a partial wake lock. On RTSP
  SETUP (or `/play` for AirPlay video) it turns the screen on and opens `PlaybackActivity`. Turning
  the TV off drops the session. It starts on boot, and `KeepAliveJob` restarts it if the system stops it.

## Settings

Open the app on the TV and choose **Settings**:

- **AirPlay name**: defaults to the TV's device name.
- **Require a code for new devices**: a new iPhone/Mac must enter a code shown on the TV once; it
  is then remembered. "Remembered devices" forgets them all.
- **4K mirroring (H.265)**: on by default; turn off on slow Wi-Fi.
- **Low-latency mirroring**: video doesn't wait for audio, so the picture follows the phone faster
  but sound may lag (useful for games and scrolling).

## Build and run

Requires Android Studio's SDK with NDK 29 and CMake 3.22.1. The first build also compiles OpenSSL.

```
scripts/dev.sh                          # build, install, set up, follow logs (10.0.0.242:5555)
scripts/dev.sh --release                # release build: INFO logging, for everyday use
ANDROID_SERIAL=host:port scripts/dev.sh # another device
```

`scripts/setup-device.sh` (run by `dev.sh`, or `./gradlew :app:setupDevice`) grants what the app
needs to wake the TV from the background: display over other apps, battery-optimisation exemption,
notifications.

## Limitations

- No FairPlay-protected video (Apple TV+, Netflix casting). Screen mirroring works everywhere.
- No AirPlay 2 multi-room audio (playing in sync with HomePods).
- The phone's volume controls the app's sound level; the TV or receiver volume is set with the
  TV remote ([ADR 0017](docs/adr/0017-tv-volume-over-cec.md)).

## Releases

Releases are automatic: commits on `main` follow [Conventional Commits](https://www.conventionalcommits.org/),
and [release-please](https://github.com/googleapis/release-please) keeps a release pull request with
the next version and the [changelog](CHANGELOG.md). Merging it publishes a GitHub release with a
signed APK.

## Licence

GPLv3, as required by UxPlay.
