/*
 * Android DNS-SD backend for UxPlay's lib.
 *
 * UxPlay ships two backends (dns_sd/ for Avahi/Bonjour, mdnsd/ for its own
 * responder). Android apps can't reach the system mDNS daemon from C, so this
 * backend only *builds* the RAOP and AirPlay TXT records and service names.
 * The JNI bridge reads them back and Kotlin registers the services with
 * NsdManager. The system mDNS responder then keeps answering queries, which is
 * what lets the iPhone see the Streamer while it is in standby.
 *
 * TXT record contents mirror UxPlay's dns_sd/dns_sd.c.
 */

#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <assert.h>

#include "dnssd.h"
#include "dnssdint.h"
#include "utils.h"
#include "android_dnssd.h"

#define MAX_TXT 1024
#define MAX_SERVNAME 256

typedef struct {
    unsigned char data[MAX_TXT];
    int len;
} txt_record_t;

typedef struct dnssd_private_s {
    txt_record_t raop_txt;
    txt_record_t airplay_txt;
    char raop_name[MAX_SERVNAME];
    char airplay_name[MAX_SERVNAME];
    unsigned short raop_port;
    unsigned short airplay_port;
} dnssd_private_t;

/* DNS TXT entries are length-prefixed "key=value" strings (RFC 6763 section 6). */
static void txt_set(txt_record_t *txt, const char *key, const char *value) {
    int key_len = (int) strlen(key);
    int value_len = (int) strlen(value);
    int entry_len = key_len + 1 + value_len;
    if (entry_len > 255 || txt->len + 1 + entry_len > MAX_TXT) {
        return;
    }
    txt->data[txt->len++] = (unsigned char) entry_len;
    memcpy(txt->data + txt->len, key, key_len);
    txt->len += key_len;
    txt->data[txt->len++] = '=';
    memcpy(txt->data + txt->len, value, value_len);
    txt->len += value_len;
}

void *dnssd_private_init(dnssd_t *dnssd_public, int *error) {
    (void) dnssd_public;
    if (error) *error = DNSSD_ERROR_NOERROR;
    dnssd_private_t *dnssd = (dnssd_private_t *) calloc(1, sizeof(dnssd_private_t));
    if (!dnssd && error) *error = DNSSD_ERROR_OUTOFMEM;
    return dnssd;
}

void dnssd_private_destroy(void *private_data) {
    free(private_data);
}

int dnssd_register_raop(dnssd_t *dnssd_public, unsigned short port) {
    char features[22] = {0};
    char servname[MAX_SERVNAME];
    assert(dnssd_public && dnssd_public->dnssd_private);
    dnssd_private_t *dnssd = (dnssd_private_t *) dnssd_public->dnssd_private;
    txt_record_t *txt = &dnssd->raop_txt;

    snprintf(features, sizeof(features), "0x%X,0x%X", dnssd_public->features1, dnssd_public->features2);

    txt->len = 0;
    txt_set(txt, "ch", RAOP_CH);
    txt_set(txt, "cn", RAOP_CN);
    txt_set(txt, "da", RAOP_DA);
    txt_set(txt, "et", RAOP_ET);
    txt_set(txt, "vv", RAOP_VV);
    txt_set(txt, "ft", features);
    txt_set(txt, "am", GLOBAL_MODEL);
    txt_set(txt, "md", RAOP_MD);
    txt_set(txt, "rhd", RAOP_RHD);
    switch (dnssd_public->pin_pw) {
    case 1:
        txt_set(txt, "pw", "true");
        txt_set(txt, "sf", "0x8c");
        break;
    case 2:
    case 3:
        txt_set(txt, "pw", "true");
        txt_set(txt, "sf", "0x84");
        break;
    default:
        txt_set(txt, "pw", "false");
        txt_set(txt, "sf", RAOP_SF);
        break;
    }
    txt_set(txt, "sr", RAOP_SR);
    txt_set(txt, "ss", RAOP_SS);
    txt_set(txt, "sv", RAOP_SV);
    txt_set(txt, "tp", RAOP_TP);
    txt_set(txt, "txtvers", RAOP_TXTVERS);
    txt_set(txt, "vs", RAOP_VS);
    txt_set(txt, "vn", RAOP_VN);
    txt_set(txt, "pk", dnssd_public->pk);

    /* RAOP service name is "<hwaddr-hex>@<name>" */
    if (utils_hwaddr_raop(servname, sizeof(servname), dnssd_public->hw_addr, dnssd_public->hw_addr_len) < 0) {
        return -1;
    }
    if (sizeof(servname) < strlen(servname) + 1 + dnssd_public->name_len + 1) {
        return -2;
    }
    strncat(servname, "@", sizeof(servname) - strlen(servname) - 1);
    strncat(servname, dnssd_public->name, sizeof(servname) - strlen(servname) - 1);

    snprintf(dnssd->raop_name, sizeof(dnssd->raop_name), "%s", servname);
    dnssd->raop_port = port;
    return 0;
}

int dnssd_register_airplay(dnssd_t *dnssd_public, unsigned short port) {
    char device_id[3 * MAX_HWADDR_LEN];
    char features[22] = {0};
    assert(dnssd_public && dnssd_public->dnssd_private);
    dnssd_private_t *dnssd = (dnssd_private_t *) dnssd_public->dnssd_private;
    txt_record_t *txt = &dnssd->airplay_txt;

    snprintf(features, sizeof(features), "0x%X,0x%X", dnssd_public->features1, dnssd_public->features2);
    if (utils_hwaddr_airplay(device_id, sizeof(device_id), dnssd_public->hw_addr, dnssd_public->hw_addr_len) < 0) {
        return -1;
    }

    txt->len = 0;
    txt_set(txt, "deviceid", device_id);
    txt_set(txt, "features", features);
    txt_set(txt, "pw", dnssd_public->pin_pw ? "true" : "false");
    txt_set(txt, "flags", "0x4");
    txt_set(txt, "model", GLOBAL_MODEL);
    txt_set(txt, "pk", dnssd_public->pk);
    txt_set(txt, "pi", AIRPLAY_PI);
    txt_set(txt, "srcvers", AIRPLAY_SRCVERS);
    txt_set(txt, "vv", AIRPLAY_VV);

    snprintf(dnssd->airplay_name, sizeof(dnssd->airplay_name), "%s", dnssd_public->name);
    dnssd->airplay_port = port;
    return 0;
}

const char *dnssd_get_raop_txt(dnssd_t *dnssd_public, int *length) {
    dnssd_private_t *dnssd = (dnssd_private_t *) dnssd_public->dnssd_private;
    *length = dnssd->raop_txt.len;
    return (const char *) dnssd->raop_txt.data;
}

const char *dnssd_get_airplay_txt(dnssd_t *dnssd_public, int *length) {
    dnssd_private_t *dnssd = (dnssd_private_t *) dnssd_public->dnssd_private;
    *length = dnssd->airplay_txt.len;
    return (const char *) dnssd->airplay_txt.data;
}

/* Registration lives in Kotlin (NsdManager); nothing to do here. */
void dnssd_unregister_raop(dnssd_t *dnssd_public) { (void) dnssd_public; }
void dnssd_unregister_airplay(dnssd_t *dnssd_public) { (void) dnssd_public; }

int dnssd_get_service_fd(dnssd_t *dnssd_public, int service) {
    (void) dnssd_public; (void) service;
    return -1;
}

int dnssd_process_service(dnssd_t *dnssd_public, int service) {
    (void) dnssd_public; (void) service;
    return -1;
}

void dnssd_error_text(int *error, const char *appname) {
    (void) appname;
    fprintf(stderr, "android_dnssd: error %d\n", error ? *error : 0);
}

const char *android_dnssd_service_name(dnssd_t *dnssd_public, int service) {
    dnssd_private_t *dnssd = (dnssd_private_t *) dnssd_public->dnssd_private;
    return service == DNSSD_SERVICE_RAOP ? dnssd->raop_name : dnssd->airplay_name;
}

unsigned short android_dnssd_service_port(dnssd_t *dnssd_public, int service) {
    dnssd_private_t *dnssd = (dnssd_private_t *) dnssd_public->dnssd_private;
    return service == DNSSD_SERVICE_RAOP ? dnssd->raop_port : dnssd->airplay_port;
}
