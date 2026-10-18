#ifndef ANDROID_DNSSD_H
#define ANDROID_DNSSD_H

#include "dnssd.h"

#ifdef __cplusplus
extern "C" {
#endif

/* service: DNSSD_SERVICE_RAOP or DNSSD_SERVICE_AIRPLAY */
const char *android_dnssd_service_name(dnssd_t *dnssd, int service);
unsigned short android_dnssd_service_port(dnssd_t *dnssd, int service);

#ifdef __cplusplus
}
#endif

#endif
