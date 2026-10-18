# ADR 0011: Optional PIN pairing with remembered devices

Status: accepted (2026-10-18)

## Context

Anyone on the Wi-Fi can AirPlay to the TV. UxPlay supports an on-screen PIN
("legacy pairing", feature bit 27).

## Decision

- Setting "Require a code for new devices" (off by default) turns on PIN pairing.
- A new device sees a random 4-digit code full screen on the TV (the TV wakes up).
  After pairing, its public key is remembered and it reconnects without a code.
- UxPlay's random PIN is single-use, and the iPhone first retries an old saved code
  by itself, which used up the new one. The shown code is therefore held as a fixed
  PIN (`pin + 10000`) until a device pairs or 60 s pass.
- The code stays on screen even when the iPhone drops the connection that asked for
  it (it reconnects after the user types the code).

## Consequences

Verified with an iPhone. "Forget remembered devices" in Settings clears the list.
