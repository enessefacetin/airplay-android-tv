# ADR 0005: Wake the TV when an AirPlay session starts

Status: accepted (2026-10-18)

## Context

The main goal: starting AirPlay on an iPhone turns on the TV. The Streamer controls
the TV over HDMI-CEC: when the Streamer's display turns on, it sends "One Touch
Play", which powers on the TV and selects its input.

## Decision

- `AirPlayService` is a foreground service (`specialUse`) with a partial wake lock
  and a Wi-Fi lock, so the server and mDNS stay alive in standby. It uses
  `START_STICKY`, starts on boot (`BootReceiver`), and `KeepAliveJob` (persisted,
  every 15 min) restarts it if the system stopped it.
- On RTSP SETUP (`report_client_request`), on HLS `/play`, on a pairing PIN, and when
  AirPlay music starts or resumes, `TvWaker` turns the screen on
  (`ACQUIRE_CAUSES_WAKEUP` wake lock) and starts `PlaybackActivity`.
- Starting an activity from the background needs the `SYSTEM_ALERT_WINDOW` app-op,
  granted once over adb by `scripts/setup-device.sh` (with battery-optimisation
  exemption and notifications). Without it, a full-screen-intent notification is
  the fallback.

## Consequences

- Verified: the TV wakes from standby and after a reboot (without opening the app).
- The overnight standby test is still open (see ROADMAP).
- `setup-device.sh` must run once per install on a new device.
