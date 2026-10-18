# ADR 0014: Settings stored in SharedPreferences; changes restart the receiver

Status: accepted (2026-10-18)

## Decision

`ReceiverSettings` (SharedPreferences): AirPlay name (default: the TV's device
name), require PIN, 4K, low-latency mirroring, remembered devices. A change calls
`AirPlayService.restart()`, which stops the native server and starts it again with
the new values, so mDNS re-announces them.
