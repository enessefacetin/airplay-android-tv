# ADR 0001: Use UxPlay's lib as the AirPlay protocol core

Status: accepted (2026-10-18)

## Context

AirPlay receivers need pairing, FairPlay (playfair) handshakes, RTSP, encrypted
mirror and audio streams, and NTP time sync. Writing this from scratch is months of
reverse engineering. Two open-source C cores exist:

- [FDH2/UxPlay](https://github.com/FDH2/UxPlay) `lib/`: actively maintained, works
  with current iOS and macOS, supports H.265 and HLS (YouTube) video.
- dsafa22/AirplayServer: already Android-ready (JNI), but based on old RPiPlay code
  with known breakage on newer iOS.

## Decision

Use UxPlay's `lib/` and write our own Android glue (JNI bridge, mDNS, decoders).
Do not use UxPlay's GStreamer renderers or its `uxplay.cpp` main program; use them
only as a reference for how the core is driven.

## Consequences

- The project is GPLv3, as UxPlay is. `LICENSE` is GPLv3.
- We follow UxPlay's callback API (`raop_callbacks_t`). Some callbacks are called
  without a null check, so the bridge registers a no-op for every callback.
- Upstream bugs can affect us; see ADR 0002 for how local patches are tracked.
