/*
 * WirelessScreenCasting — RAOP ALAC 解码（GPL-3.0）。
 * FFmpeg 只编译了 ALAC 解码器（scripts/build-native-deps.sh：--disable-everything --enable-decoder=alac），
 * AAC-LC / AAC-ELD 交给 Kotlin 层的 MediaCodec（技术方案 3.8）。
 */
#include "alac_decoder.h"

#include <stdlib.h>
#include <string.h>

#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/mem.h>

/* size=36, 'alac', version=0, frameLength=352, compatibleVersion=0, bitDepth=16, pb=40, mb=10, kb=14,
 * numChannels=2, maxRun=255, maxFrameBytes=0, avgBitRate=0, sampleRate=44100 */
const uint8_t wsc_alac_magic_cookie[36] = {
    0x00, 0x00, 0x00, 0x24, 'a', 'l', 'a', 'c', 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x01, 0x60, 0x00, 0x10, 0x28, 0x0a, 0x0e, 0x02, 0x00, 0xff,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xac, 0x44,
};

struct wsc_alac_s {
    AVCodecContext *ctx;
    AVPacket *pkt;
    AVFrame *frame;
};

wsc_alac_t *wsc_alac_create(void) {
    const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_ALAC);
    if (!codec) return NULL;
    wsc_alac_t *dec = calloc(1, sizeof(*dec));
    if (!dec) return NULL;
    dec->ctx = avcodec_alloc_context3(codec);
    dec->pkt = av_packet_alloc();
    dec->frame = av_frame_alloc();
    if (!dec->ctx || !dec->pkt || !dec->frame) goto fail;

    dec->ctx->extradata = av_mallocz(sizeof(wsc_alac_magic_cookie) + AV_INPUT_BUFFER_PADDING_SIZE);
    if (!dec->ctx->extradata) goto fail;
    memcpy(dec->ctx->extradata, wsc_alac_magic_cookie, sizeof(wsc_alac_magic_cookie));
    dec->ctx->extradata_size = (int) sizeof(wsc_alac_magic_cookie);
    dec->ctx->sample_rate = WSC_ALAC_SAMPLE_RATE;
    av_channel_layout_default(&dec->ctx->ch_layout, WSC_ALAC_CHANNELS);
    if (avcodec_open2(dec->ctx, codec, NULL) < 0) goto fail;
    return dec;
fail:
    wsc_alac_destroy(dec);
    return NULL;
}

static inline int16_t sample_at(const AVFrame *f, int ch, int i) {
    switch (f->format) {
        case AV_SAMPLE_FMT_S16P: return ((const int16_t *) f->extended_data[ch])[i];
        case AV_SAMPLE_FMT_S16: return ((const int16_t *) f->extended_data[0])[i * f->ch_layout.nb_channels + ch];
        case AV_SAMPLE_FMT_S32P: return (int16_t) (((const int32_t *) f->extended_data[ch])[i] >> 16);
        case AV_SAMPLE_FMT_S32: return (int16_t) (((const int32_t *) f->extended_data[0])[i * f->ch_layout.nb_channels + ch] >> 16);
        default: return 0;
    }
}

int wsc_alac_decode(wsc_alac_t *dec, const uint8_t *data, int len, int16_t *out, int out_capacity) {
    if (!dec || !data || len <= 0 || !out) return -1;
    if (av_new_packet(dec->pkt, len) < 0) return -2;
    memcpy(dec->pkt->data, data, (size_t) len);
    int rc = avcodec_send_packet(dec->ctx, dec->pkt);
    av_packet_unref(dec->pkt);
    if (rc < 0) return -3;

    int written = 0;
    while ((rc = avcodec_receive_frame(dec->ctx, dec->frame)) == 0) {
        int channels = dec->frame->ch_layout.nb_channels;
        int n = dec->frame->nb_samples;
        for (int i = 0; i < n && written + WSC_ALAC_CHANNELS <= out_capacity; i++) {
            /* 单声道源复制到两个声道；多于 2 声道只取前两个 */
            out[written++] = sample_at(dec->frame, 0, i);
            out[written++] = sample_at(dec->frame, channels > 1 ? 1 : 0, i);
        }
        av_frame_unref(dec->frame);
    }
    return (rc == AVERROR(EAGAIN) || rc == AVERROR_EOF) ? written : -4;
}

void wsc_alac_destroy(wsc_alac_t *dec) {
    if (!dec) return;
    if (dec->ctx) avcodec_free_context(&dec->ctx);
    if (dec->pkt) av_packet_free(&dec->pkt);
    if (dec->frame) av_frame_free(&dec->frame);
    free(dec);
}
