/*
 * WirelessScreenCasting — Apple 设备 PIN 配对的状态守卫（GPL-3.0）。
 *
 * 上游 UxPlay 的一次性 PIN：PAIR-PIN-START 生成随机 PIN，第一次 pair-setup-pin 读取后立即清零。
 * 发送端输错后直接重试（macOS 会这样做，不再发 PAIR-PIN-START）时，服务端用 "0000" 校验，
 * 用户怎么输都“密码不正确”；而且任何客户端不经过 PAIR-PIN-START 也能用 "0000" 尝试配对。
 * CMakeLists.txt 在构建期给 raop_handlers.h 打补丁，改为由这里决定 PIN 是否可用：
 *   - 只有 PAIR-PIN-START 签发过的 PIN 可用；签发后 WSC_PIN_TTL_SEC 秒过期；
 *   - 同一个 PIN 最多校验失败 WSC_PIN_MAX_FAILURES 次，之后作废（防暴力尝试）；
 *   - 配对成功后作废。
 */
#ifndef WSC_PIN_GUARD_H
#define WSC_PIN_GUARD_H

#include <stdbool.h>

#define WSC_PIN_TTL_SEC 120
#define WSC_PIN_MAX_FAILURES 3

/* PAIR-PIN-START 签发了新 PIN（重置失败计数与过期时间） */
void wsc_pin_issued(void);
/* pair-setup-pin 第一步：当前 PIN 是否仍可用于校验 */
bool wsc_pin_usable(void);
/* 校验失败；返回 true 表示 PIN 已作废（应收起屏幕上的 PIN） */
bool wsc_pin_failed(void);
/* 配对成功 / 服务启停：作废当前 PIN */
void wsc_pin_reset(void);

#endif
