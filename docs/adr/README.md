# Architecture decision records

Decisions made while building the receiver (first development session, 2026-10-18).
Format: context, decision, consequences. Add a new file for a new decision; mark
replaced ones as superseded instead of editing them.

- [0001: Use UxPlay's lib as the AirPlay protocol core](0001-uxplay-protocol-core.md)
- [0002: Vendor third-party sources with recorded versions and local patches](0002-vendored-sources.md)
- [0003: Build OpenSSL libcrypto from source, armeabi-v7a only](0003-openssl-build.md)
- [0004: Advertise AirPlay with Android's NsdManager](0004-mdns-via-nsdmanager.md)
- [0005: Wake the TV when an AirPlay session starts](0005-wake-on-airplay.md)
- [0006: Turning the TV off ends the AirPlay session](0006-tv-off-ends-session.md)
- [0007: Video: MediaCodec low-latency decoder with keyframe cache, H.265 4K](0007-video-pipeline.md)
- [0008: Audio: native ALAC, MediaCodec AAC-ELD, one AudioTrack](0008-audio-pipeline.md)
- [0009: A/V sync: per-stream clocks and a shared presentation latency](0009-av-sync.md)
- [0010: AirPlay video (YouTube): UxPlay HLS + Media3 ExoPlayer](0010-airplay-video-hls.md)
- [0011: Optional PIN pairing with remembered devices](0011-pin-pairing.md)
- [0012: TV remote controls AirPlay music over DACP](0012-remote-control-dacp.md)
- [0013: UI: framework views in code, 10-foot design, separate playback task](0013-ui-approach.md)
- [0014: Settings stored in SharedPreferences; changes restart the receiver](0014-settings.md)
- [0015: Build tooling, signing and release](0015-build-and-release.md)
- [0016: Out of scope: DRM video and AirPlay 2 multi-room audio](0016-not-supported.md)
- [0017: Sender volume steps the TV or AV receiver over HDMI-CEC](0017-tv-volume-over-cec.md)
