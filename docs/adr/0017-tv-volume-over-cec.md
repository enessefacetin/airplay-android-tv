# ADR 0017: Sender volume steps the TV or AV receiver over HDMI-CEC

Status: rejected (2026-10-18). Built, tested on the device, then removed.

## Context

The sender's volume slider changes only the app's own audio level (digital gain).
The idea was to let the slider control the TV or AV receiver instead, and to keep
the slider in sync when the TV remote changes the volume.

Findings on the Streamer (Android 14) with an LG TV and an AV receiver on ARC:

- HDMI is a "full volume" output: the Android music volume stays at its maximum, and
  setting it does not change the HDMI output.
- With "HDMI-CEC volume control" on (`cmd hdmi_control cec_setting set
  volume_control_enabled 1`; it was off on the test device), the app's
  `AudioManager.adjustStreamVolume(STREAM_MUSIC, ADJUST_RAISE/LOWER, 0)` is sent as
  `<User Control Pressed> Volume up/down` to the receiver (CEC address 5). No
  permission is needed.
- The receiver has no absolute volume: it doesn't answer `<Give Audio Status>`, so
  only steps are possible and its level is unknown.
- The remote's volume keys are handled by the system before any app. A media session
  with a `VolumeProvider` and the foreground activity don't get them. Injecting keys
  needs `INJECT_EVENTS`. Only an accessibility service could catch them.
- The sender accepts DACP `volumeup`/`volumedown` (tested on iOS: 200 OK, then the
  phone sends its new volume back), so remote-to-phone sync is possible in principle.

The prototype turned slider moves into steps (16 over -30..0 dB, from the volume at
session start). In use:

- It was slow: each step is a separate CEC message that the receiver handles in turn.
- Moving the slider to the top sent 13 steps in about a second. The receiver took it
  as a held key and went to full volume.
- The slider and the receiver drift apart, because there is no level to sync to.

## Decision

Rejected. Keep two independent volumes, as an Apple TV does by default: the sender's
slider sets the app's digital gain, and the TV remote sets the TV or receiver volume.
The slider can never make the speakers louder than the level set on the receiver.

The digital gain costs little quality: turning 16-bit audio down by 20 dB still leaves
about 13 bits, which isn't audible at that volume.

## Consequences

- No CEC volume code and no accessibility service in the app.
- Revisit only for a receiver with absolute volume (CEC `<Set Audio Volume Level>` and
  `<Report Audio Status>`, Android's "absolute volume behavior"). Then the slider can
  map to an exact level with a maximum, and the remote's changes can go back to the
  sender with DACP `setproperty?dmcp.device-volume=<dB>`.
