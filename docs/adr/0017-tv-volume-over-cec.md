# ADR 0017: Sender volume steps the TV or AV receiver over HDMI-CEC

Status: accepted (2026-10-18)

## Context

The sender's volume slider changed only the app's own audio level. At a low slider
position the TV or receiver stayed loud and the audio lost resolution, and the TV
remote's volume and the slider worked separately.

Findings on the Streamer (Android 14):

- HDMI is a "full volume" output: the Android music volume stays at its maximum, and
  setting it (`setStreamVolume`) does not change the HDMI output.
- With "HDMI-CEC volume control" on (`cmd hdmi_control cec_setting set
  volume_control_enabled 1`), `AudioManager.adjustStreamVolume(STREAM_MUSIC,
  ADJUST_RAISE/LOWER, 0)` from the app sends `<User Control Pressed> Volume up/down`
  to the audio system (the AV receiver on ARC, CEC address 5) or the TV. This needs
  no permission.
- Absolute volume (`<Set Audio Volume Level>`, AVB) is not available: the test
  receiver does not answer `<Give Audio Status>`. So only steps are possible, and the
  receiver's level is unknown.
- Injecting key events needs `INJECT_EVENTS`, even into the app's own window.

## Decision

- A setting, "Device volume controls the TV" (off by default).
- When on, the app plays at full level and `TvVolume` turns slider moves into steps:
  16 steps over -30..0 dB (one iPhone volume button press = one step).
- The first volume in a session is only the starting point, so connecting doesn't
  change the receiver's level. The level resets when the last connection closes.
- Mute (-144) silences the app's audio and sends no CEC mute, because the mute key
  toggles and could get out of step.

## Consequences

- The slider and the receiver's level do not match exactly; they move together.
- If CEC volume control is off, the slider does nothing while the setting is on
  (the setting's description says it needs CEC volume control).
- One step on the receiver may be a different amount than on the iPhone. If the range
  feels too small or too large, the steps per slider range is the value to change.
