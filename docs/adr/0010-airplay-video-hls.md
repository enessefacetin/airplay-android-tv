# ADR 0010: AirPlay video (YouTube): UxPlay HLS + Media3 ExoPlayer

Status: accepted (2026-10-18)

## Context

Apps like YouTube send "AirPlay video": the receiver fetches HLS playlists itself.
Without support, YouTube only sent audio.

## Decision

- Advertise feature bits 0 and 4 and enable UxPlay's HLS mode. UxPlay fetches the
  playlists through the iPhone (FCUP over the reverse connection) and serves them on
  `http://localhost:7000/`.
- `HlsPlayer` plays that URL with Media3 ExoPlayer and follows play, pause, seek and
  stop from the phone, and reports position to it.
- Report "still playing" to `/playback-info` until a video has ended: the iPhone
  polls before playback starts, and "finished" made it give up.
- On a playback error, release the player and report "finished": the iPhone then
  stops too, instead of showing "playing" over a frozen frame. Show the error on
  the TV for 10 s, with its own text for DRM errors (added 2026-10-18).
- Wake the TV on `/play` (HLS doesn't go through RTSP SETUP).
- If mirroring frames arrive while a video plays, stop the video and wait for it to
  release the Surface first.

## Consequences

- Needs the local httpd patch (ADR 0002).
- DRM (FairPlay) video such as Apple TV+ cannot play. Its clear intro plays, then
  ExoPlayer fails with `UnsupportedDrmException` at the first encrypted segment.
