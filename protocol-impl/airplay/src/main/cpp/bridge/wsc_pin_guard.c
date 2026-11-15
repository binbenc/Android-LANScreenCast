/* WirelessScreenCasting — Apple 设备 PIN 配对状态守卫（GPL-3.0），说明见 wsc_pin_guard.h。 */
#include "wsc_pin_guard.h"

#include <pthread.h>
#include <time.h>

static pthread_mutex_t g_pin_lock = PTHREAD_MUTEX_INITIALIZER;
static bool g_active;
static int g_failures;
static time_t g_issued_at;

static time_t now_sec(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec;
}

void wsc_pin_issued(void) {
    pthread_mutex_lock(&g_pin_lock);
    g_active = true;
    g_failures = 0;
    g_issued_at = now_sec();
    pthread_mutex_unlock(&g_pin_lock);
}

bool wsc_pin_usable(void) {
    pthread_mutex_lock(&g_pin_lock);
    if (g_active && now_sec() - g_issued_at > WSC_PIN_TTL_SEC) g_active = false;
    bool ok = g_active && g_failures < WSC_PIN_MAX_FAILURES;
    pthread_mutex_unlock(&g_pin_lock);
    return ok;
}

bool wsc_pin_failed(void) {
    pthread_mutex_lock(&g_pin_lock);
    if (g_active && ++g_failures >= WSC_PIN_MAX_FAILURES) g_active = false;
    bool revoked = !g_active;
    pthread_mutex_unlock(&g_pin_lock);
    return revoked;
}

void wsc_pin_reset(void) {
    pthread_mutex_lock(&g_pin_lock);
    g_active = false;
    g_failures = 0;
    pthread_mutex_unlock(&g_pin_lock);
}
