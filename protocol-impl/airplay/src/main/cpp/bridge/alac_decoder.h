/* WirelessScreenCasting — RAOP ALAC 解码（FFmpeg libavcodec，仅 ALAC 解码器；GPL-3.0）。 */
#ifndef WSC_ALAC_DECODER_H
#define WSC_ALAC_DECODER_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct wsc_alac_s wsc_alac_t;

/* RAOP ALAC 固定参数：44100 Hz / 16 bit / 2 声道 / 每帧 352 个采样（与 UxPlay audio_renderer 的 magic cookie 相同）。 */
#define WSC_ALAC_SAMPLE_RATE 44100
#define WSC_ALAC_CHANNELS 2
#define WSC_ALAC_FRAME_SAMPLES 352

/* 36 字节 ALAC magic cookie（'alac' atom），供单测核对。 */
extern const uint8_t wsc_alac_magic_cookie[36];

wsc_alac_t *wsc_alac_create(void);

/* 解码一个 ALAC 包，输出交错 S16 PCM。返回写入的 int16 个数（帧数 × 声道数），出错返回负数。 */
int wsc_alac_decode(wsc_alac_t *dec, const uint8_t *data, int len, int16_t *out, int out_capacity);

void wsc_alac_destroy(wsc_alac_t *dec);

#ifdef __cplusplus
}
#endif
#endif
