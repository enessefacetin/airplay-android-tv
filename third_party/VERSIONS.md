# Vendored sources
| Project | Commit | Date | Licence |
|---|---|---|---|
| FDH2/UxPlay (lib/ only) | 4e95a9e | 2026-10-16 | GPLv3 (individual lib files LGPL-2.1 etc.) |
| libimobiledevice/libplist (C sources) | fe3dc34 | 2026-09-30 | LGPL-2.1 |
| mikebrady/alac (decoder only) | 5d8c5db | 2026-04-10 | Apache-2.0 |

Removed from UxPlay lib: the dns_sd/ and mdnsd/ backends (replaced by app/src/main/cpp/android_dnssd.c) and its CMake files.

## Local patches

- `uxplay/lib/httpd.c`: reverse (PTTH) connections called the getter
  `http_request_is_reverse()` instead of `http_request_set_reverse()`, so the iPhone's
  responses were parsed as requests (HPE_INVALID_METHOD) and the connection was closed.
  This broke HLS (YouTube) because playlist requests to the phone then failed.
