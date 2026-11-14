/*
 * WirelessScreenCasting — UxPlay lib 的 JNI 桥接（GPL-3.0，技术方案 3.8）。
 *
 * 视频：镜像 H.264/H.265 NAL（Annex-B）回调到 Kotlin，由 MediaCodec 解码到 SurfaceView，不使用 GStreamer。
 * 音频：ALAC 在这里用 FFmpeg 解码为 S16 PCM；AAC-LC / AAC-ELD 原样交给 Kotlin（MediaCodec 解码）；输出统一走 AudioTrack。
 * 视频网址（HLS）：UxPlay 的 httpd 自己在 http://localhost:<port>/ 提供改写后的 playlist（本地 playlist 代理），
 *   on_video_play 把这个本地地址交给 Kotlin 的 Media3 ExoPlayer 播放。
 * JNI 方法用 RegisterNatives 绑定，不依赖符号名查找（技术方案 3.10.4）；库只导出 JNI_OnLoad。
 */
#include <jni.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include <openssl/evp.h>

#include "alac_decoder.h"
#include "dnssd.h"
#include "logger.h"
#include "raop.h"
#include "stream.h"
#include "wsc_bridge.h"

#define TAG "wsc-uxplay"
#define CALLBACKS_CLASS "com/benchan/wireless/screencasting/protocol/airplay/UxPlayCallbacks"
#define NATIVE_CLASS "com/benchan/wireless/screencasting/protocol/airplay/UxPlayNative"

static JavaVM *g_vm;
static jobject g_callbacks;
static pthread_key_t g_env_key;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER; /* 保护 start / stop */
static raop_t *g_raop;
static dnssd_t *g_dnssd;
static char *g_lang;

/* ALAC 解码器只在 RTP 音频线程上使用；格式切换时重建 */
static wsc_alac_t *g_alac;
static pthread_mutex_t g_alac_lock = PTHREAD_MUTEX_INITIALIZER;
static float g_volume_db = -15.0f; /* 回报给发送端的初始音量（AirPlay dB，范围 -30..0） */

static struct {
    jmethodID registerService, unregisterService;
    jmethodID onConnectionInit, onConnectionDestroy, onConnectionReset, onDisplayPin, onClientRequest;
    jmethodID onVideoCodec, onVideoData, onVideoFlush, onVideoReset, onMirrorRunning;
    jmethodID onAudioFormat, onAudioPcm, onAudioCompressed, onAudioVolume, onAudioFlush, onAudioMetadata, onAudioCoverArt;
    jmethodID onHlsPlay, onHlsSeek, onHlsRate, onHlsStop, onHlsPlaylistRemove, hlsPlaybackInfo;
    jmethodID onLog;
} M;

static void detach_thread(void *env) {
    if (env && g_vm) (*g_vm)->DetachCurrentThread(g_vm);
}

/* 原生线程（UxPlay 的 httpd / rtp 线程）首次回调时附加到 JVM，线程退出时由 pthread key 析构函数自动分离。 */
static JNIEnv *env_for_thread(void) {
    JNIEnv *env = NULL;
    if (!g_vm) return NULL;
    if ((*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6) == JNI_OK) return env;
    JavaVMAttachArgs args = {JNI_VERSION_1_6, "uxplay-native", NULL};
    if ((*g_vm)->AttachCurrentThread(g_vm, &env, &args) != JNI_OK) return NULL;
    pthread_setspecific(g_env_key, env);
    return env;
}

/* Kotlin 回调抛异常时不能留在 native 线程上（否则下一次 JNI 调用会崩溃）：记录后清除。 */
static int check_exception(JNIEnv *env, const char *where) {
    if (!(*env)->ExceptionCheck(env)) return 0;
    (*env)->ExceptionDescribe(env);
    (*env)->ExceptionClear(env);
    __android_log_print(ANDROID_LOG_ERROR, TAG, "exception in callback %s", where);
    return 1;
}

#define CB_ENV(env) JNIEnv *env = g_callbacks ? env_for_thread() : NULL; if (!env) return
#define CB_ENV_RET(env, ret) JNIEnv *env = g_callbacks ? env_for_thread() : NULL; if (!env) return ret

static void call_void(JNIEnv *env, jmethodID m, const char *name, ...) {
    va_list ap;
    va_start(ap, name);
    (*env)->CallVoidMethodV(env, g_callbacks, m, ap);
    va_end(ap);
    check_exception(env, name);
}

static jstring new_string(JNIEnv *env, const char *s) {
    /* NewStringUTF 需要 Modified UTF-8；设备名等来自网络的字符串先做最基本的防护：截断到 256 字节 */
    char buf[257];
    snprintf(buf, sizeof(buf), "%s", s ? s : "");
    return (*env)->NewStringUTF(env, buf);
}

static jbyteArray new_bytes(JNIEnv *env, const void *data, int len) {
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (arr && len > 0) (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *) data);
    return arr;
}

void wsc_log(int level, const char *fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    int prio = level <= LOGGER_ERR ? ANDROID_LOG_ERROR : level <= LOGGER_WARNING ? ANDROID_LOG_WARN
             : level <= LOGGER_INFO ? ANDROID_LOG_INFO : ANDROID_LOG_DEBUG;
    __android_log_write(prio, TAG, buf);
    /* 只把 INFO 及以上转给 Kotlin 诊断日志（DEBUG 量太大）；Kotlin 侧再脱敏网址 */
    if (level > LOGGER_INFO) return;
    CB_ENV(env);
    jstring s = new_string(env, buf);
    call_void(env, M.onLog, "onLog", (jint) level, s);
    (*env)->DeleteLocalRef(env, s);
}

static jobjectArray to_string_array(JNIEnv *env, const char **items, int count) {
    jclass cls = (*env)->FindClass(env, "java/lang/String");
    jobjectArray arr = (*env)->NewObjectArray(env, count, cls, NULL);
    for (int i = 0; i < count; i++) {
        jstring s = new_string(env, items[i]);
        (*env)->SetObjectArrayElement(env, arr, i, s);
        (*env)->DeleteLocalRef(env, s);
    }
    (*env)->DeleteLocalRef(env, cls);
    return arr;
}

void wsc_register_service(const char *type, const char *name, unsigned short port, const char **keys, const char **values, int count) {
    CB_ENV(env);
    jstring jt = new_string(env, type);
    jstring jn = new_string(env, name);
    jobjectArray jk = to_string_array(env, keys, count);
    jobjectArray jv = to_string_array(env, values, count);
    call_void(env, M.registerService, "registerService", jt, jn, (jint) port, jk, jv);
    (*env)->DeleteLocalRef(env, jt);
    (*env)->DeleteLocalRef(env, jn);
    (*env)->DeleteLocalRef(env, jk);
    (*env)->DeleteLocalRef(env, jv);
}

void wsc_unregister_service(const char *type) {
    CB_ENV(env);
    jstring jt = new_string(env, type);
    call_void(env, M.unregisterService, "unregisterService", jt);
    (*env)->DeleteLocalRef(env, jt);
}

/* ---------------- raop 回调：连接 ---------------- */

static void cb_conn_init(void *cls) { (void) cls; CB_ENV(env); call_void(env, M.onConnectionInit, "onConnectionInit"); }
static void cb_conn_destroy(void *cls) { (void) cls; CB_ENV(env); call_void(env, M.onConnectionDestroy, "onConnectionDestroy"); }
static void cb_conn_reset(void *cls, int reason) { (void) cls; CB_ENV(env); call_void(env, M.onConnectionReset, "onConnectionReset", (jint) reason); }
static void cb_conn_feedback(void *cls) { (void) cls; }

static void cb_display_pin(void *cls, char *pin) {
    (void) cls;
    CB_ENV(env);
    jstring s = new_string(env, pin);
    call_void(env, M.onDisplayPin, "onDisplayPin", s);
    (*env)->DeleteLocalRef(env, s);
}

/* 在 UxPlay httpd 线程上同步等待 Kotlin 的决定（R-09：已有会话时弹“请求替换当前投屏”）。 */
static void cb_report_client_request(void *cls, char *deviceid, char *model, char *name, bool *admit) {
    (void) cls;
    *admit = false;
    CB_ENV(env);
    jstring d = new_string(env, deviceid);
    jstring m = new_string(env, model);
    jstring n = new_string(env, name);
    jboolean ok = (*env)->CallBooleanMethod(env, g_callbacks, M.onClientRequest, d, m, n);
    if (!check_exception(env, "onClientRequest")) *admit = ok == JNI_TRUE;
    (*env)->DeleteLocalRef(env, d);
    (*env)->DeleteLocalRef(env, m);
    (*env)->DeleteLocalRef(env, n);
}

/* pin_pw = 1（屏幕 PIN）时 UxPlay 不使用密码 */
static const char *cb_passwd(void *cls, int *len) { (void) cls; *len = 0; return NULL; }
static void cb_register_client(void *cls, const char *device_id, const char *pk, const char *name) { (void) cls; (void) device_id; (void) pk; (void) name; }
static bool cb_check_register(void *cls, const char *pk) { (void) cls; (void) pk; return true; }
static void cb_export_dacp(void *cls, const char *active_remote, const char *dacp_id) { (void) cls; (void) active_remote; (void) dacp_id; }
static void cb_get_custom_profile(void *cls, const char **custom_profile) { (void) cls; *custom_profile = NULL; }

/* ---------------- raop 回调：镜像视频 ---------------- */

static void cb_video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    (void) cls; (void) ntp;
    if (!data || data->data_len <= 0) return;
    CB_ENV(env);
    jbyteArray arr = new_bytes(env, data->data, data->data_len);
    if (!arr) { check_exception(env, "onVideoData alloc"); return; }
    call_void(env, M.onVideoData, "onVideoData", arr, (jlong) data->ntp_time_local, (jboolean) data->is_h265);
    (*env)->DeleteLocalRef(env, arr);
}

static int cb_video_set_codec_and_size(void *cls, video_codec_t codec, unsigned short ws, unsigned short hs,
                                       unsigned short x, unsigned short y, unsigned short w, unsigned short h) {
    (void) cls; (void) x; (void) y; (void) w; (void) h;
    CB_ENV_RET(env, 0);
    call_void(env, M.onVideoCodec, "onVideoCodec", (jboolean) (codec == VIDEO_CODEC_H265), (jint) ws, (jint) hs);
    return 0;
}

static void cb_video_flush(void *cls) { (void) cls; CB_ENV(env); call_void(env, M.onVideoFlush, "onVideoFlush"); }
static void cb_video_pause(void *cls) { (void) cls; }
static void cb_video_resume(void *cls) { (void) cls; }
static void cb_video_reset(void *cls, reset_type_t type) { (void) cls; CB_ENV(env); call_void(env, M.onVideoReset, "onVideoReset", (jint) type); }

static void cb_mirror_running(void *cls, bool running) {
    (void) cls;
    CB_ENV(env);
    call_void(env, M.onMirrorRunning, "onMirrorRunning", (jboolean) running);
}

/* ---------------- raop 回调：音频 ---------------- */

static void cb_audio_get_format(void *cls, unsigned char *ct, unsigned short *spf, bool *using_screen, bool *is_media, uint64_t *audio_format) {
    (void) cls; (void) audio_format;
    if (*ct == 2) {
        pthread_mutex_lock(&g_alac_lock);
        if (!g_alac) g_alac = wsc_alac_create();
        if (!g_alac) wsc_log(LOGGER_ERR, "ALAC decoder unavailable");
        pthread_mutex_unlock(&g_alac_lock);
    }
    CB_ENV(env);
    call_void(env, M.onAudioFormat, "onAudioFormat", (jint) *ct, (jint) *spf, (jboolean) *using_screen, (jboolean) *is_media);
}

static void cb_audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    (void) cls; (void) ntp;
    if (!data || data->data_len <= 0) return;
    CB_ENV(env);
    if (data->ct == 2) {
        int16_t pcm[WSC_ALAC_FRAME_SAMPLES * WSC_ALAC_CHANNELS * 2];
        pthread_mutex_lock(&g_alac_lock);
        int n = g_alac ? wsc_alac_decode(g_alac, data->data, data->data_len, pcm, (int) (sizeof(pcm) / sizeof(pcm[0]))) : -1;
        pthread_mutex_unlock(&g_alac_lock);
        if (n <= 0) return;
        jshortArray arr = (*env)->NewShortArray(env, n);
        if (!arr) { check_exception(env, "onAudioPcm alloc"); return; }
        (*env)->SetShortArrayRegion(env, arr, 0, n, pcm);
        call_void(env, M.onAudioPcm, "onAudioPcm", arr, (jint) n);
        (*env)->DeleteLocalRef(env, arr);
    } else {
        /* ct = 4 AAC-LC、8 AAC-ELD（MediaCodec 解码），其余按 S16 PCM 处理（与 UxPlay audio_renderer 一致） */
        jbyteArray arr = new_bytes(env, data->data, data->data_len);
        if (!arr) { check_exception(env, "onAudioCompressed alloc"); return; }
        call_void(env, M.onAudioCompressed, "onAudioCompressed", arr, (jint) data->ct);
        (*env)->DeleteLocalRef(env, arr);
    }
}

static void cb_audio_flush(void *cls) { (void) cls; CB_ENV(env); call_void(env, M.onAudioFlush, "onAudioFlush"); }
static double cb_audio_set_client_volume(void *cls) { (void) cls; return (double) g_volume_db; }

static void cb_audio_set_volume(void *cls, float volume) {
    (void) cls;
    g_volume_db = volume;
    CB_ENV(env);
    call_void(env, M.onAudioVolume, "onAudioVolume", (jfloat) volume);
}

static void cb_audio_set_metadata(void *cls, const void *buffer, int buflen) {
    (void) cls;
    if (!buffer || buflen <= 0) return;
    CB_ENV(env);
    jbyteArray arr = new_bytes(env, buffer, buflen);
    if (!arr) { check_exception(env, "onAudioMetadata alloc"); return; }
    call_void(env, M.onAudioMetadata, "onAudioMetadata", arr);
    (*env)->DeleteLocalRef(env, arr);
}

static void cb_audio_set_coverart(void *cls, const void *buffer, int buflen) {
    (void) cls;
    if (!buffer || buflen <= 0 || buflen > 4 * 1024 * 1024) return;
    CB_ENV(env);
    jbyteArray arr = new_bytes(env, buffer, buflen);
    if (!arr) { check_exception(env, "onAudioCoverArt alloc"); return; }
    call_void(env, M.onAudioCoverArt, "onAudioCoverArt", arr);
    (*env)->DeleteLocalRef(env, arr);
}

static void cb_audio_stop_coverart_rendering(void *cls) { (void) cls; }
static void cb_audio_remote_control_id(void *cls, const char *dacp_id, const char *active_remote) { (void) cls; (void) dacp_id; (void) active_remote; }
static void cb_audio_set_progress(void *cls, uint32_t *start, uint32_t *curr, uint32_t *end) { (void) cls; (void) start; (void) curr; (void) end; }

/* ---------------- raop 回调：视频网址（HLS） ---------------- */

static void cb_on_video_play(void *cls, const char *location, const float start_position) {
    (void) cls;
    CB_ENV(env);
    jstring s = new_string(env, location);
    call_void(env, M.onHlsPlay, "onHlsPlay", s, (jfloat) start_position);
    (*env)->DeleteLocalRef(env, s);
}

static void cb_on_video_scrub(void *cls, const float position) { (void) cls; CB_ENV(env); call_void(env, M.onHlsSeek, "onHlsSeek", (jfloat) position); }
static void cb_on_video_rate(void *cls, const float rate) { (void) cls; CB_ENV(env); call_void(env, M.onHlsRate, "onHlsRate", (jfloat) rate); }
static void cb_on_video_stop(void *cls) { (void) cls; CB_ENV(env); call_void(env, M.onHlsStop, "onHlsStop"); }

static float cb_on_video_playlist_remove(void *cls) {
    (void) cls;
    CB_ENV_RET(env, 0.0f);
    jfloat pos = (*env)->CallFloatMethod(env, g_callbacks, M.onHlsPlaylistRemove);
    return check_exception(env, "onHlsPlaylistRemove") ? 0.0f : pos;
}

/* Kotlin 填充 double[7]：duration, position, seekStart, seekDuration, rate, bufferEmpty, bufferFull；返回是否仍在播放 */
static void cb_on_video_acquire_playback_info(void *cls, playback_info_t *info) {
    (void) cls;
    CB_ENV(env);
    jdoubleArray arr = (*env)->NewDoubleArray(env, 7);
    if (!arr) { check_exception(env, "hlsPlaybackInfo alloc"); return; }
    jboolean playing = (*env)->CallBooleanMethod(env, g_callbacks, M.hlsPlaybackInfo, arr);
    if (!check_exception(env, "hlsPlaybackInfo")) {
        jdouble v[7];
        (*env)->GetDoubleArrayRegion(env, arr, 0, 7, v);
        info->duration = v[0];
        info->position = v[1];
        info->seek_start = v[2];
        info->seek_duration = v[3];
        info->rate = (float) v[4];
        info->playback_buffer_empty = v[5] != 0.0;
        info->playback_buffer_full = v[6] != 0.0;
        info->ready_to_play = true;
        info->playback_likely_to_keep_up = true;
        if (!playing) {
            /* 与 UxPlay 一致：-1 告诉发送端视频已播完 */
            info->position = -1.0;
            info->duration = -1.0;
        }
    }
    (*env)->DeleteLocalRef(env, arr);
}

static void cb_raop_log(void *cls, int level, const char *msg) { (void) cls; wsc_log(level, "%s", msg); }

/* ---------------- JNI 入口 ---------------- */

static void stop_locked(JNIEnv *env) {
    if (g_dnssd) { dnssd_unregister_raop(g_dnssd); dnssd_unregister_airplay(g_dnssd); }
    if (g_raop) { raop_stop_httpd(g_raop); raop_destroy(g_raop); g_raop = NULL; }
    if (g_dnssd) { dnssd_destroy(g_dnssd); g_dnssd = NULL; }
    pthread_mutex_lock(&g_alac_lock);
    wsc_alac_destroy(g_alac);
    g_alac = NULL;
    pthread_mutex_unlock(&g_alac_lock);
    free(g_lang);
    g_lang = NULL;
    if (g_callbacks) { (*env)->DeleteGlobalRef(env, g_callbacks); g_callbacks = NULL; }
}

/*
 * 启动 RAOP / AirPlay 服务。flags：bit0 = 需要屏幕 PIN，bit1 = 支持视频网址（HLS），bit2 = 支持 H.265。
 * 返回 0 成功；负数为失败阶段（-1 dnssd、-2 raop_init、-3 raop_init2、-4 httpd、-5 mDNS 注册）。
 */
static jint native_start(JNIEnv *env, jclass clazz, jobject callbacks, jstring jname, jbyteArray jhw, jint flags,
                         jstring jkeyfile, jstring jlang) {
    (void) clazz;
    pthread_mutex_lock(&g_lock);
    if (g_raop) { pthread_mutex_unlock(&g_lock); return 0; }
    const int require_pin = flags & 1, hls = (flags >> 1) & 1, h265 = (flags >> 2) & 1;
    g_callbacks = (*env)->NewGlobalRef(env, callbacks);
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    const char *keyfile = (*env)->GetStringUTFChars(env, jkeyfile, NULL);
    const char *lang = jlang ? (*env)->GetStringUTFChars(env, jlang, NULL) : NULL;
    char hw[6] = {0};
    jsize hw_len = (*env)->GetArrayLength(env, jhw);
    (*env)->GetByteArrayRegion(env, jhw, 0, hw_len < 6 ? hw_len : 6, (jbyte *) hw);

    int rc = 0, error = 0;
    /* pin_pw：1 = 首次连接显示 4 位 PIN，发送端记住配对（交互稿 09B“连接时要求输入 PIN”） */
    g_dnssd = dnssd_init(name, (int) strlen(name), hw, 6, require_pin ? 1 : 0, &error);
    if (!g_dnssd) { wsc_log(LOGGER_ERR, "dnssd_init failed (%d)", error); rc = -1; goto out; }
    /* 与 uxplay.cpp start_dnssd() 一致的功能位调整 */
    dnssd_set_airplay_features(g_dnssd, 0, hls);        /* video supported */
    dnssd_set_airplay_features(g_dnssd, 4, hls);        /* HLS supported */
    dnssd_set_airplay_features(g_dnssd, 42, h265);      /* screen multi codec (H.265) */
    dnssd_set_airplay_features(g_dnssd, 27, require_pin); /* legacy pairing：PIN 模式需要 */

    raop_callbacks_t cb;
    memset(&cb, 0, sizeof(cb));
    cb.audio_process = cb_audio_process;
    cb.video_process = cb_video_process;
    cb.video_pause = cb_video_pause;
    cb.video_resume = cb_video_resume;
    cb.conn_feedback = cb_conn_feedback;
    cb.conn_reset = cb_conn_reset;
    cb.video_reset = cb_video_reset;
    cb.video_set_codec_and_size = cb_video_set_codec_and_size;
    cb.conn_init = cb_conn_init;
    cb.conn_destroy = cb_conn_destroy;
    cb.audio_flush = cb_audio_flush;
    cb.video_flush = cb_video_flush;
    cb.audio_set_client_volume = cb_audio_set_client_volume;
    cb.audio_set_volume = cb_audio_set_volume;
    cb.audio_set_metadata = cb_audio_set_metadata;
    cb.audio_set_coverart = cb_audio_set_coverart;
    cb.audio_stop_coverart_rendering = cb_audio_stop_coverart_rendering;
    cb.audio_remote_control_id = cb_audio_remote_control_id;
    cb.audio_set_progress = cb_audio_set_progress;
    cb.audio_get_format = cb_audio_get_format;
    cb.mirror_video_running = cb_mirror_running;
    cb.report_client_request = cb_report_client_request;
    cb.display_pin = cb_display_pin;
    cb.register_client = cb_register_client;
    cb.check_register = cb_check_register;
    cb.passwd = cb_passwd;
    cb.export_dacp = cb_export_dacp;
    cb.on_video_play = cb_on_video_play;
    cb.on_video_scrub = cb_on_video_scrub;
    cb.on_video_rate = cb_on_video_rate;
    cb.on_video_stop = cb_on_video_stop;
    cb.on_video_acquire_playback_info = cb_on_video_acquire_playback_info;
    cb.on_video_playlist_remove = cb_on_video_playlist_remove;
    cb.get_custom_profile = cb_get_custom_profile;

    char device_id[18];
    snprintf(device_id, sizeof(device_id), "%02X:%02X:%02X:%02X:%02X:%02X",
             hw[0] & 0xff, hw[1] & 0xff, hw[2] & 0xff, hw[3] & 0xff, hw[4] & 0xff, hw[5] & 0xff);
    g_raop = raop_init(&cb);
    if (!g_raop) { rc = -2; goto out; }
    raop_set_log_callback(g_raop, cb_raop_log, NULL);
    raop_set_log_level(g_raop, LOGGER_INFO);
    /* nohold = 0：已有 Apple 设备连接时新的 Apple 设备会被 UxPlay 拒绝（409），不会被直接抢占 */
    if (raop_init2(g_raop, 0, device_id, keyfile) != 0) { free(g_raop); g_raop = NULL; rc = -3; goto out; }
    if (hls) {
        raop_set_plist(g_raop, "hls", 1);
        g_lang = strdup(lang && *lang ? lang : "en");
        raop_set_lang(g_raop, g_lang, NULL, g_lang);
    }
    unsigned short port = 0;
    if (raop_start_httpd(g_raop, &port) < 0 || port == 0) { rc = -4; goto out; }
    raop_set_port(g_raop, port);
    raop_set_dnssd(g_raop, g_dnssd);
    /* 与 UxPlay 一致：AirPlay 端口复用 RAOP 端口 */
    if (dnssd_register_raop(g_dnssd, port) != 0 || dnssd_register_airplay(g_dnssd, port) != 0) { rc = -5; goto out; }
    wsc_log(LOGGER_INFO, "receiver started on port %u (pin=%d hls=%d h265=%d)", port, require_pin, hls, h265);

out:
    (*env)->ReleaseStringUTFChars(env, jname, name);
    (*env)->ReleaseStringUTFChars(env, jkeyfile, keyfile);
    if (lang) (*env)->ReleaseStringUTFChars(env, jlang, lang);
    if (rc != 0) stop_locked(env);
    pthread_mutex_unlock(&g_lock);
    return rc;
}

static void native_stop(JNIEnv *env, jclass clazz) {
    (void) clazz;
    pthread_mutex_lock(&g_lock);
    stop_locked(env);
    pthread_mutex_unlock(&g_lock);
}

/*
 * 设备自检（androidTest 调用）：FFmpeg ALAC 解码器可创建，且 UxPlay 用到的 OpenSSL 算法在裁剪后的 libcrypto 中都可用
 * （构建脚本关闭了大量算法；缺失只会在运行时 EVP fetch 失败，链接期发现不了）。
 */
static jboolean native_self_test(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    wsc_alac_t *d = wsc_alac_create();
    if (!d) return JNI_FALSE;
    wsc_alac_destroy(d);
    static const char *ciphers[] = {"AES-128-GCM", "AES-128-CTR", "AES-128-CBC"};
    static const char *digests[] = {"SHA1", "SHA224", "SHA256", "SHA384", "SHA512", "MD5"};
    for (size_t i = 0; i < sizeof(ciphers) / sizeof(ciphers[0]); i++) {
        EVP_CIPHER *c = EVP_CIPHER_fetch(NULL, ciphers[i], NULL);
        if (!c) { __android_log_print(ANDROID_LOG_ERROR, TAG, "self-test: cipher %s missing", ciphers[i]); return JNI_FALSE; }
        EVP_CIPHER_free(c);
    }
    for (size_t i = 0; i < sizeof(digests) / sizeof(digests[0]); i++) {
        EVP_MD *m = EVP_MD_fetch(NULL, digests[i], NULL);
        if (!m) { __android_log_print(ANDROID_LOG_ERROR, TAG, "self-test: digest %s missing", digests[i]); return JNI_FALSE; }
        EVP_MD_free(m);
    }
    static const int keys[] = {EVP_PKEY_ED25519, EVP_PKEY_X25519};
    for (size_t i = 0; i < sizeof(keys) / sizeof(keys[0]); i++) {
        EVP_PKEY *pkey = NULL;
        EVP_PKEY_CTX *ctx = EVP_PKEY_CTX_new_id(keys[i], NULL);
        int ok = ctx && EVP_PKEY_keygen_init(ctx) > 0 && EVP_PKEY_keygen(ctx, &pkey) > 0;
        EVP_PKEY_free(pkey);
        EVP_PKEY_CTX_free(ctx);
        if (!ok) { __android_log_print(ANDROID_LOG_ERROR, TAG, "self-test: keygen %d failed", keys[i]); return JNI_FALSE; }
    }
    return JNI_TRUE;
}

#define GET(field, name, sig) do { M.field = (*env)->GetMethodID(env, cb, name, sig); if (!M.field) return JNI_ERR; } while (0)

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    g_vm = vm;
    pthread_key_create(&g_env_key, detach_thread);
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;

    jclass cb = (*env)->FindClass(env, CALLBACKS_CLASS);
    if (!cb) return JNI_ERR;
    GET(registerService, "registerService", "(Ljava/lang/String;Ljava/lang/String;I[Ljava/lang/String;[Ljava/lang/String;)V");
    GET(unregisterService, "unregisterService", "(Ljava/lang/String;)V");
    GET(onConnectionInit, "onConnectionInit", "()V");
    GET(onConnectionDestroy, "onConnectionDestroy", "()V");
    GET(onConnectionReset, "onConnectionReset", "(I)V");
    GET(onDisplayPin, "onDisplayPin", "(Ljava/lang/String;)V");
    GET(onClientRequest, "onClientRequest", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z");
    GET(onVideoCodec, "onVideoCodec", "(ZII)V");
    GET(onVideoData, "onVideoData", "([BJZ)V");
    GET(onVideoFlush, "onVideoFlush", "()V");
    GET(onVideoReset, "onVideoReset", "(I)V");
    GET(onMirrorRunning, "onMirrorRunning", "(Z)V");
    GET(onAudioFormat, "onAudioFormat", "(IIZZ)V");
    GET(onAudioPcm, "onAudioPcm", "([SI)V");
    GET(onAudioCompressed, "onAudioCompressed", "([BI)V");
    GET(onAudioVolume, "onAudioVolume", "(F)V");
    GET(onAudioFlush, "onAudioFlush", "()V");
    GET(onAudioMetadata, "onAudioMetadata", "([B)V");
    GET(onAudioCoverArt, "onAudioCoverArt", "([B)V");
    GET(onHlsPlay, "onHlsPlay", "(Ljava/lang/String;F)V");
    GET(onHlsSeek, "onHlsSeek", "(F)V");
    GET(onHlsRate, "onHlsRate", "(F)V");
    GET(onHlsStop, "onHlsStop", "()V");
    GET(onHlsPlaylistRemove, "onHlsPlaylistRemove", "()F");
    GET(hlsPlaybackInfo, "hlsPlaybackInfo", "([D)Z");
    GET(onLog, "onLog", "(ILjava/lang/String;)V");
    (*env)->DeleteLocalRef(env, cb);

    jclass native = (*env)->FindClass(env, NATIVE_CLASS);
    if (!native) return JNI_ERR;
    static const JNINativeMethod methods[] = {
        {"nativeStart", "(L" CALLBACKS_CLASS ";Ljava/lang/String;[BILjava/lang/String;Ljava/lang/String;)I", (void *) native_start},
        {"nativeStop", "()V", (void *) native_stop},
        {"nativeSelfTest", "()Z", (void *) native_self_test},
    };
    if ((*env)->RegisterNatives(env, native, methods, sizeof(methods) / sizeof(methods[0])) != JNI_OK) return JNI_ERR;
    (*env)->DeleteLocalRef(env, native);
    return JNI_VERSION_1_6;
}
