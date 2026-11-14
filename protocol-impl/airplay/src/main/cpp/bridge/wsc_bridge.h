/* WirelessScreenCasting — 原生层到 Kotlin 的回调（GPL-3.0）。 */
#ifndef WSC_BRIDGE_H
#define WSC_BRIDGE_H

void wsc_log(int level, const char *fmt, ...);
void wsc_register_service(const char *type, const char *name, unsigned short port,
                          const char **keys, const char **values, int count);
void wsc_unregister_service(const char *type);

#endif
