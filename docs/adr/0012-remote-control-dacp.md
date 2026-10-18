# ADR 0012: TV remote controls AirPlay music over DACP

Status: accepted (2026-10-18)

## Context

On the music screen the TV remote should pause and skip, as on an Apple TV.

## Decision

- The sender's DACP-ID and Active-Remote (from UxPlay's `export_dacp`) go to
  `RemoteControl`, which finds `iTunes_Ctrl_<DACP-ID>` (`_dacp._tcp`) with NsdManager
  and sends `GET /ctrl-int/1/<command>` with the `Active-Remote` header.
- OK / play-pause: `playpause`; left/right and media keys: `previtem` / `nextitem`.
- Back: for music, sends `pause` and closes the screen (the iPhone doesn't notice a
  dropped audio connection, so disconnecting left it showing "connected"). For
  mirroring and video, disconnects. Home leaves everything running.
- Paused/playing state: pausing from the phone sends no message, it only stops the
  audio packets, so no audio for 1.2 s means paused. Track position comes from the
  sender's `progress` updates.

## Consequences

Verified with an iPhone: play/pause and next track work.
