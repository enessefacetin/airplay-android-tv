# AirPlay receiver for Android TV

Mirror an iPhone/iPad/Mac screen or AirPlay music to an Android TV device (built and tested on a
Google TV Streamer 4K, Android 14). Starting AirPlay wakes the device from standby, and HDMI-CEC
turns on the TV and switches to its input. Turning the TV off ends the session.

## How it works

- **Protocol:** [UxPlay](https://github.com/FDH2/UxPlay)'s `lib/` (pairing, FairPlay, RTSP, mirror and
  audio streams) built with the NDK, with OpenSSL `libcrypto`, libplist and Apple's ALAC decoder.
  Sources are vendored in `third_party/` (versions in `third_party/VERSIONS.md`).
- **Discovery:** `android_dnssd.c` builds the `_airplay._tcp` / `_raop._tcp` TXT records and
  `MdnsAdvertiser` registers them with `NsdManager`.
- **Video:** H.264 to MediaCodec (`c2.mtk.avc.decoder.lowlatency` on the Streamer), rendered as soon
  as it's decoded. The playback screen asks the TV for low-latency game mode (ALLM).
- **Audio:** AAC-ELD (mirroring) through MediaCodec, ALAC (music) decoded natively, both to AudioTrack.
- **Wake on AirPlay:** `AirPlayService` is a foreground service holding a partial wake lock. On RTSP
  SETUP it turns the screen on and opens `PlaybackActivity`.

## Build and run

Requires Android Studio's SDK with NDK 29 and CMake 3.22.1. The first build also compiles OpenSSL.

```
scripts/dev.sh                          # build, install, set up, follow logs (10.0.0.242:5555)
ANDROID_SERIAL=host:port scripts/dev.sh # another device
```

`scripts/setup-device.sh` (run by `dev.sh`, or `./gradlew :app:setupDevice`) grants what the app
needs to wake the TV from the background: display over other apps, battery-optimisation exemption,
notifications.

## Limitations

- No FairPlay-protected video (Apple TV+, Netflix casting) and no "AirPlay video" (HLS) mode, so
  apps like YouTube send audio only. Screen mirroring works everywhere.
- H.264 only (no HEVC), mirroring at up to 1080p60.
- Audio and video are played as soon as they arrive, without NTP-based A/V sync.

## Licence

GPLv3, as required by UxPlay.
