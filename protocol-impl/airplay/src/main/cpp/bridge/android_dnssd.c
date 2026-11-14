/*
 * WirelessScreenCasting — UxPlay dnssd 后端的 Android 实现（GPL-3.0）。
 * 不使用 Avahi / Bonjour / UxPlay 内置 mdnsd：TXT 记录在这里构建，注册交给 Kotlin 层 NsdManager
 * （技术方案 3.8，思路同 jqssun/android-airplay-server 的 android_dnssd_shim.c，本文件为独立实现）。
 * TXT 键值与 UxPlay lib/mdnsd/dnssd_mdnsd.c 保持一致。
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "dnssd.h"
#include "dnssdint.h"
#include "global.h"
#include "utils.h"
#include "wsc_bridge.h"

#define MAX_TXT 24
#define MAX_TXT_BYTES 1024
#define MAX_HWADDR_LEN 6

typedef struct {
    int count;
    char keys[MAX_TXT][16];
    char values[MAX_TXT][96];
    unsigned char wire[MAX_TXT_BYTES]; /* DNS TXT 线格式：len + "k=v" */
    int wire_len;
} txt_record_t;

typedef struct {
    txt_record_t raop;
    txt_record_t airplay;
    int raop_registered;
    int airplay_registered;
} android_dnssd_t;

static int txt_add(txt_record_t *t, const char *key, const char *value) {
    if (t->count >= MAX_TXT) return -1;
    size_t kl = strlen(key), vl = strlen(value);
    if (kl >= sizeof(t->keys[0]) || vl >= sizeof(t->values[0]) || kl + vl + 1 > 255) return -1;
    if (t->wire_len + 1 + (int) (kl + vl + 1) > MAX_TXT_BYTES) return -1;
    strcpy(t->keys[t->count], key);
    strcpy(t->values[t->count], value);
    t->count++;
    t->wire[t->wire_len++] = (unsigned char) (kl + vl + 1);
    memcpy(t->wire + t->wire_len, key, kl); t->wire_len += (int) kl;
    t->wire[t->wire_len++] = '=';
    memcpy(t->wire + t->wire_len, value, vl); t->wire_len += (int) vl;
    return 0;
}

static void txt_reset(txt_record_t *t) { memset(t, 0, sizeof(*t)); }

void *dnssd_private_init(dnssd_t *dnssd_public, int *error) {
    (void) dnssd_public;
    android_dnssd_t *d = calloc(1, sizeof(android_dnssd_t));
    if (!d && error) *error = DNSSD_ERROR_OUTOFMEM;
    return d;
}

void dnssd_private_destroy(void *dnssd_private) { free(dnssd_private); }

void dnssd_error_text(int *error, const char *appname) {
    (void) appname;
    if (error) wsc_log(2, "dnssd error %d", *error);
}

static int build_raop_txt(dnssd_t *pub, android_dnssd_t *d) {
    char features[22] = {0};
    const char *pw = "false";
    const char *sf = RAOP_SF;
    switch (pub->pin_pw) {
        case 1: pw = "true"; sf = "0x8c"; break;
        case 2: case 3: pw = "true"; sf = "0x84"; break;
        default: break;
    }
    snprintf(features, sizeof(features), "0x%X,0x%X", pub->features1, pub->features2);
    txt_reset(&d->raop);
    return txt_add(&d->raop, "ch", RAOP_CH) || txt_add(&d->raop, "cn", RAOP_CN) || txt_add(&d->raop, "da", RAOP_DA) ||
           txt_add(&d->raop, "et", RAOP_ET) || txt_add(&d->raop, "vv", RAOP_VV) || txt_add(&d->raop, "ft", features) ||
           txt_add(&d->raop, "am", GLOBAL_MODEL) || txt_add(&d->raop, "md", RAOP_MD) || txt_add(&d->raop, "rhd", RAOP_RHD) ||
           txt_add(&d->raop, "pw", pw) || txt_add(&d->raop, "sr", RAOP_SR) || txt_add(&d->raop, "ss", RAOP_SS) ||
           txt_add(&d->raop, "sv", RAOP_SV) || txt_add(&d->raop, "tp", RAOP_TP) || txt_add(&d->raop, "txtvers", RAOP_TXTVERS) ||
           txt_add(&d->raop, "sf", sf) || txt_add(&d->raop, "vs", RAOP_VS) || txt_add(&d->raop, "vn", RAOP_VN) ||
           txt_add(&d->raop, "pk", pub->pk ? pub->pk : "");
}

static int build_airplay_txt(dnssd_t *pub, android_dnssd_t *d) {
    char device_id[3 * MAX_HWADDR_LEN];
    char features[22] = {0};
    if (utils_hwaddr_airplay(device_id, sizeof(device_id), pub->hw_addr, pub->hw_addr_len) < 0) return -1;
    snprintf(features, sizeof(features), "0x%X,0x%X", pub->features1, pub->features2);
    txt_reset(&d->airplay);
    return txt_add(&d->airplay, "deviceid", device_id) || txt_add(&d->airplay, "features", features) ||
           txt_add(&d->airplay, "pw", pub->pin_pw ? "true" : "false") || txt_add(&d->airplay, "flags", "0x4") ||
           txt_add(&d->airplay, "model", GLOBAL_MODEL) || txt_add(&d->airplay, "pk", pub->pk ? pub->pk : "") ||
           txt_add(&d->airplay, "pi", AIRPLAY_PI) || txt_add(&d->airplay, "srcvers", AIRPLAY_SRCVERS) ||
           txt_add(&d->airplay, "vv", AIRPLAY_VV);
}

static void publish(const char *type, const char *name, unsigned short port, const txt_record_t *t) {
    const char *keys[MAX_TXT];
    const char *values[MAX_TXT];
    for (int i = 0; i < t->count; i++) { keys[i] = t->keys[i]; values[i] = t->values[i]; }
    wsc_register_service(type, name, port, keys, values, t->count);
}

int dnssd_register_raop(dnssd_t *pub, unsigned short port) {
    android_dnssd_t *d = (android_dnssd_t *) pub->dnssd_private;
    if (!d || build_raop_txt(pub, d)) return -1;
    char hw[2 * MAX_HWADDR_LEN + 1];
    if (utils_hwaddr_raop(hw, sizeof(hw), pub->hw_addr, pub->hw_addr_len) < 0) return -1;
    char name[256];
    snprintf(name, sizeof(name), "%s@%s", hw, pub->name);
    publish("_raop._tcp", name, port, &d->raop);
    d->raop_registered = 1;
    return 0;
}

int dnssd_register_airplay(dnssd_t *pub, unsigned short port) {
    android_dnssd_t *d = (android_dnssd_t *) pub->dnssd_private;
    if (!d || build_airplay_txt(pub, d)) return -1;
    publish("_airplay._tcp", pub->name, port, &d->airplay);
    d->airplay_registered = 1;
    return 0;
}

void dnssd_unregister_raop(dnssd_t *pub) {
    android_dnssd_t *d = (android_dnssd_t *) pub->dnssd_private;
    if (d && d->raop_registered) { wsc_unregister_service("_raop._tcp"); d->raop_registered = 0; }
}

void dnssd_unregister_airplay(dnssd_t *pub) {
    android_dnssd_t *d = (android_dnssd_t *) pub->dnssd_private;
    if (d && d->airplay_registered) { wsc_unregister_service("_airplay._tcp"); d->airplay_registered = 0; }
}

const char *dnssd_get_raop_txt(dnssd_t *pub, int *length) {
    android_dnssd_t *d = (android_dnssd_t *) pub->dnssd_private;
    *length = d ? d->raop.wire_len : 0;
    return d ? (const char *) d->raop.wire : NULL;
}

const char *dnssd_get_airplay_txt(dnssd_t *pub, int *length) {
    android_dnssd_t *d = (android_dnssd_t *) pub->dnssd_private;
    *length = d ? d->airplay.wire_len : 0;
    return d ? (const char *) d->airplay.wire : NULL;
}

/* NsdManager 自己处理守护进程通信，没有需要应用轮询的 fd。 */
int dnssd_get_service_fd(dnssd_t *dnssd, int service) { (void) dnssd; (void) service; return -1; }
int dnssd_process_service(dnssd_t *dnssd, int service) { (void) dnssd; (void) service; return 0; }
