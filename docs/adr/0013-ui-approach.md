# ADR 0013: UI: framework views in code, 10-foot design, separate playback task

Status: accepted (2026-10-18)

## Context

The app has four screens (home, settings, playback, pairing code shown in playback)
and the owner prefers minimal tooling and dependencies.

## Decision

- Plain `android.app.Activity` with views built in code; no AndroidX UI, Compose or
  Leanback. Shared look in `TvUi` (colours, large type, focusable cards with a
  clear focus outline, overscan-safe margins of 80/48 dp).
- `PlaybackActivity` runs in its own task (`taskAffinity=.playback`, `singleTask`,
  excluded from recents), so it can open over any app. `MainActivity` is
  `singleTask`; opening the app during a session goes back to the session, and the
  home screen shows "Return to AirPlay".
- Music screen: cover art on a blurred copy of itself, playing/paused status,
  progress bar, on-screen controls that appear on key presses, single-line scrolling
  title and artist (no layout shift between tracks).
- OLED protection: content drifts every minute; the screen dims after 5 min without
  a key press or track change.
- Text and icons are only set when they change, and the progress bar is scaled, not
  resized: any re-layout restarts the title marquee.

## Consequences

Small and dependency-free, but every screen is hand-written code.
