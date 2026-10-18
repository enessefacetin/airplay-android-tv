# ADR 0002: Vendor third-party sources with recorded versions and local patches

Status: accepted (2026-10-18)

## Context

UxPlay, libplist and Apple's ALAC decoder are needed as source code (they are built
with the NDK). Git submodules complicate the build and the cloud/CI setup.

## Decision

Copy only the needed sources into `third_party/` and record the upstream commit,
date and licence of each in `third_party/VERSIONS.md`. Every local change to
vendored code is listed under "Local patches" in that file.

Current local patch: `uxplay/lib/httpd.c` calls `http_request_set_reverse()` (it
called the getter `http_request_is_reverse()`), which broke HLS / YouTube video.
Similar upstream report: FDH2/UxPlay#596 (we decided not to file our own report).

## Consequences

- Updating UxPlay means re-copying `lib/` and re-applying the local patches.
- The ALAC decoder needs `TARGET_RT_LITTLE_ENDIAN=1` on ARM (set in CMake, not a
  source patch): Apple's code only detects little-endian on x86/Windows.
