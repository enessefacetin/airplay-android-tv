# ADR 0007: Video: MediaCodec low-latency decoder with keyframe cache, H.265 4K

Status: accepted (2026-10-18)

## Context

Mirroring is H.264 (or H.265 for 4K) in Annex-B form. Frames arrive before the
playback screen and its Surface exist (the TV is waking up), and the iPhone sends a
keyframe only at the start and on rotation.

## Decision

- `VideoDecoder` keeps every frame since the last keyframe (up to 48 MB) and replays
  them into the decoder when a Surface is attached.
- Prefer a decoder with `FEATURE_LowLatency` (`c2.mtk.avc.decoder.lowlatency`,
  `c2.mtk.hevc.decoder.lowlatency` on the Streamer). This cut the delay a lot.
- A new SPS (rotation) starts a fresh decoder. The old one is released first: a
  Surface can feed only one decoder.
- Advertise feature bit 42 (screen multi-codec) and a 3840x2160 display so senders
  can use H.265 4K; senders that can't keep using H.264. A setting turns 4K off.
- The playback window asks for minimal post-processing (HDMI ALLM / game mode).

## Consequences

- Rotation works without errors; 4K H.265 mirroring runs at about 60 fps.
- HEVC decoders get VPS+SPS+PPS together as `csd-0`.
