# ADR 0006: Turning the TV off ends the AirPlay session

Status: accepted (2026-10-18)

## Context

The partial wake lock keeps the server alive in standby. That also kept an active
mirroring session alive, so the iPhone still showed "connected" with the TV off.

## Decision

On `ACTION_SCREEN_OFF`, the service calls `nativeDisconnect()`, which restarts
UxPlay's HTTP server and drops all client connections. The server keeps listening,
so AirPlay can still wake the TV.

## Consequences

Behaves like an Apple TV: turning the TV off stops mirroring on the phone.
