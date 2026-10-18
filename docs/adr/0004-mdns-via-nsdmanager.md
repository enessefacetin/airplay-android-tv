# ADR 0004: Advertise AirPlay with Android's NsdManager

Status: accepted (2026-10-18)

## Context

UxPlay has two mDNS backends: Avahi/Bonjour (`dns_sd`) and its own responder
(`mdnsd`). On Android, apps can't use the system mDNS daemon from C, and a
responder inside the app would stop answering when the app sleeps.

## Decision

Replace both backends with `app/src/main/cpp/android_dnssd.c`, which only builds the
`_raop._tcp` and `_airplay._tcp` TXT records and service names. Kotlin
(`MdnsAdvertiser`) reads them over JNI and registers them with `NsdManager`.
Failed registrations are retried after 10 s.

## Consequences

- The system answers mDNS queries, also while the app sleeps, so the TV stays in the
  iPhone's AirPlay list in standby.
- Android may rename a service on a name conflict. The RAOP name contains the
  device ID, so conflicts are unlikely.
- Once, during a reinstall, the system responder logged "message is too large". It
  could not be reproduced; normal replies fit one packet. TXT records are not
  shortened, to stay compatible with Apple senders.
