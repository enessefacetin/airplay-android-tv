# ADR 0008: Audio: native ALAC, MediaCodec AAC-ELD, one AudioTrack

Status: accepted (2026-10-18)

## Context

AirPlay music uses ALAC (ct=2). Mirroring audio uses AAC-ELD (ct=8). Hardware AAC
decoders often lack AAC-ELD.

## Decision

- ALAC is decoded natively with Apple's decoder; PCM goes to Kotlin.
- AAC is decoded with MediaCodec, preferring the platform software decoder
  `c2.android.aac.decoder`.
- One AudioTrack (44.1 kHz stereo, 16-bit) with a 3 s buffer, so writes never block.
  Its start threshold is 40 ms for mirroring and one device buffer for music: a
  stream track otherwise waits until the buffer is full before it starts.
- Every write is checked; a failed AudioTrack is recreated.
- Volume: AirPlay dB (-30..0, -144 = mute) maps to AudioTrack gain.

## Consequences

The iPhone's volume slider changes the gain, not the TV's volume (see ROADMAP).
