/*
 * WirelessScreenCasting — UxPlay lib 的 JNI 桥接（GPL-3.0，技术方案 3.8）。
 * 视频：镜像 NAL 单元回调到 Kotlin（MediaCodec 解码到 SurfaceView），不使用 GStreamer。
 * 音频：TODO(F15-audio) AAC-ELD / ALAC 解码 + Oboe 输出尚未实现，audio_process 目前丢弃数据。
 * JNI 方法用 RegisterNatives 绑定，不依赖符号名查找（技术方案 3.10.4）。
 */
#include <jni.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include "raop.h"
#include "dnssd.h"
#include "stream.h"
#include "wsc_bridge.h"

#define TAG "wsc-uxplay"

static JavaVM *g_vm;
static jobject g_callbacks;
static pthread_key_t g_env_key;
static raop_t *g_raop;
static dnssd_t *g_dnssd;

static struct {
    jmethodID registerService, unregisterService, onConnectionInit, onConnectionDestroy, onDisplayPin,
        onClientRequest, onVideoCodec, onVideoData, onMirrorRunning, onLog;
} M;

static void detach_thread(void *unused) { (void) unused; if (g_vm) (*g_vm)->DetachCurrentThread(g_vm); }

/* 原生线程（UxPlay 的 httpd / rtp 线程）首次回调时附加到 JVM，线程退出时自动分离。 */
static JNIEnv *env_for_thread(void) {
    JNIEnv *env = NULL;
    if ((*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6) == JNI_OK) return env;
    if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) return NULL;
    pthread_setspecific(g_env_key, env);
    return env;
}

void wsc_log(int level, const char *fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    __android_log_write(level <= 3 ? ANDROID_LOG_WARN : ANDROID_LOG_DEBUG, TAG, buf);
    JNIEnv *env = g_callbacks ? env_for_thread() : NULL;
    if (env) {
        jstring s = (*env)->NewStringUTF(env, buf);
        (*env)->CallVoidMethod(env, g_callbacks, M.onLog, level, s);
        (*env)->DeleteLocalRef(env, s);
    }
}

static jobjectArray to_string_array(JNIEnv *env, const char **items, int count) {
    jclass cls = (*env)->FindClass(env, "java/lang/String");
    jobjectArray arr = (*env)->NewObjectArray(env, count, cls, NULL);
    for (int i = 0; i < count; i++) {
        jstring s = (*env)->NewStringUTF(env, items[i]);
        (*env)->SetObjectArrayElement(env, arr, i, s);
        (*env)->DeleteLocalRef(env, s);
    }
    return arr;
}

void wsc_register_service(const char *type, const char *name, unsigned short port, const char **keys, const char **values, int count) {
    JNIEnv *env = env_for_thread();
    if (!env || !g_callbacks) return;
    jstring jt = (*env)->NewStringUTF(env, type);
    jstring jn = (*env)->NewStringUTF(env, name);
    jobjectArray jk = to_string_array(env, keys, count);
    jobjectArray jv = to_string_array(env, values, count);
    (*env)->CallVoidMethod(env, g_callbacks, M.registerService, jt, jn, (jint) port, jk, jv);
}

void wsc_unregister_service(const char *type) {
    JNIEnv *env = env_for_thread();
    if (!env || !g_callbacks) return;
    jstring jt = (*env)->NewStringUTF(env, type);
    (*env)->CallVoidMethod(env, g_callbacks, M.unregisterService, jt);
    (*env)->DeleteLocalRef(env, jt);
}

/* ---------------- raop 回调 ---------------- */

static void cb_conn_init(void *cls) { (void) cls; JNIEnv *e = env_for_thread(); if (e) (*e)->CallVoidMethod(e, g_callbacks, M.onConnectionInit); }
static void cb_conn_destroy(void *cls) { (void) cls; JNIEnv *e = env_for_thread(); if (e) (*e)->CallVoidMethod(e, g_callbacks, M.onConnectionDestroy); }
static void cb_conn_reset(void *cls, int reason) { (void) reason; cb_conn_destroy(cls); }

static void cb_audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    (void) cls; (void) ntp; (void) data; /* TODO(F15-audio) */
}

static void cb_video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    (void) cls; (void) ntp;
    JNIEnv *e = env_for_thread();
    if (!e || !data || data->data_len <= 0) return;
    jbyteArray arr = (*e)->NewByteArray(e, data->data_len);
    (*e)->SetByteArrayRegion(e, arr, 0, data->data_len, (const jbyte *) data->data);
    (*e)->CallVoidMethod(e, g_callbacks, M.onVideoData, arr, (jlong) data->ntp_time_local, (jboolean) data->is_h265);
    (*e)->DeleteLocalRef(e, arr);
}

static int cb_video_set_codec_and_size(void *cls, video_codec_t codec, unsigned short ws, unsigned short hs,
                                       unsigned short x, unsigned short y, unsigned short w, unsigned short h) {
    (void) cls; (void) x; (void) y; (void) w; (void) h;
    JNIEnv *e = env_for_thread();
    if (e) (*e)->CallVoidMethod(e, g_callbacks, M.onVideoCodec, (jboolean) (codec == VIDEO_CODEC_H265), (jint) ws, (jint) hs);
    return 0;
}

static void cb_mirror_running(void *cls, bool running) {
    (void) cls;
    JNIEnv *e = env_for_thread();
    if (e) (*e)->CallVoidMethod(e, g_callbacks, M.onMirrorRunning, (jboolean) running);
}

static void cb_display_pin(void *cls, char *pin) {
    (void) cls;
    JNIEnv *e = env_for_thread();
    if (!e) return;
    jstring s = (*e)->NewStringUTF(e, pin);
    (*e)->CallVoidMethod(e, g_callbacks, M.onDisplayPin, s);
    (*e)->DeleteLocalRef(e, s);
}

static void cb_report_client_request(void *cls, char *deviceid, char *model, char *name, bool *admit) {
    (void) cls;
    JNIEnv *e = env_for_thread();
    if (!e) { *admit = false; return; }
    jstring d = (*e)->NewStringUTF(e, deviceid ? deviceid : "");
    jstring m = (*e)->NewStringUTF(e, model ? model : "");
    jstring n = (*e)->NewStringUTF(e, name ? name : "");
    *admit = (*e)->CallBooleanMethod(e, g_callbacks, M.onClientRequest, d, m, n);
}

static void cb_raop_log(void *cls, int level, const char *msg) { (void) cls; wsc_log(level, "%s", msg); }

/* ---------------- JNI 入口 ---------------- */

static jint native_start(JNIEnv *env, jclass clazz, jobject callbacks, jstring jname, jbyteArray jhw, jboolean require_pin, jstring jkeyfile) {
    (void) clazz;
    if (g_raop) return 0;
    g_callbacks = (*env)->NewGlobalRef(env, callbacks);
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    const char *keyfile = (*env)->GetStringUTFChars(env, jkeyfile, NULL);
    jsize hw_len = (*env)->GetArrayLength(env, jhw);
    char hw[6] = {0};
    (*env)->GetByteArrayRegion(env, jhw, 0, hw_len < 6 ? hw_len : 6, (jbyte *) hw);

    int error = 0;
    /* pin_pw：1 = 每次连接显示 4 位 PIN（交互稿 09B“连接时要求输入 PIN”） */
    g_dnssd = dnssd_init(name, (int) strlen(name), hw, 6, require_pin ? 1 : 0, &error);
    raop_callbacks_t cb;
    memset(&cb, 0, sizeof(cb));
    cb.audio_process = cb_audio_process;
    cb.video_process = cb_video_process;
    cb.video_set_codec_and_size = cb_video_set_codec_and_size;
    cb.conn_init = cb_conn_init;
    cb.conn_destroy = cb_conn_destroy;
    cb.conn_reset = cb_conn_reset;
    cb.mirror_video_running = cb_mirror_running;
    cb.display_pin = cb_display_pin;
    cb.report_client_request = cb_report_client_request;

    int rc = -1;
    char device_id[18];
    snprintf(device_id, sizeof(device_id), "%02X:%02X:%02X:%02X:%02X:%02X",
             hw[0] & 0xff, hw[1] & 0xff, hw[2] & 0xff, hw[3] & 0xff, hw[4] & 0xff, hw[5] & 0xff);
    if (g_dnssd && (g_raop = raop_init(&cb)) != NULL && raop_init2(g_raop, 0, device_id, keyfile) == 0) {
        raop_set_log_callback(g_raop, cb_raop_log, NULL);
        raop_set_dnssd(g_raop, g_dnssd);
        unsigned short port = 0;
        raop_start_httpd(g_raop, &port);
        raop_set_port(g_raop, port);
        /* 与 UxPlay 一致：AirPlay 端口复用 RAOP 端口 */
        if (dnssd_register_raop(g_dnssd, port) == 0 && dnssd_register_airplay(g_dnssd, port) == 0) rc = 0;
    } else {
        wsc_log(2, "raop init failed (dnssd error %d)", error);
    }
    (*env)->ReleaseStringUTFChars(env, jname, name);
    (*env)->ReleaseStringUTFChars(env, jkeyfile, keyfile);
    return rc;
}

static void native_stop(JNIEnv *env, jclass clazz) {
    (void) clazz;
    if (g_dnssd) { dnssd_unregister_raop(g_dnssd); dnssd_unregister_airplay(g_dnssd); }
    if (g_raop) { raop_stop_httpd(g_raop); raop_destroy(g_raop); g_raop = NULL; }
    if (g_dnssd) { dnssd_destroy(g_dnssd); g_dnssd = NULL; }
    if (g_callbacks) { (*env)->DeleteGlobalRef(env, g_callbacks); g_callbacks = NULL; }
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    g_vm = vm;
    pthread_key_create(&g_env_key, detach_thread);
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;

    jclass cb = (*env)->FindClass(env, "com/benchan/wireless/screencasting/protocol/airplay/UxPlayCallbacks");
    if (!cb) return JNI_ERR;
    M.registerService = (*env)->GetMethodID(env, cb, "registerService", "(Ljava/lang/String;Ljava/lang/String;I[Ljava/lang/String;[Ljava/lang/String;)V");
    M.unregisterService = (*env)->GetMethodID(env, cb, "unregisterService", "(Ljava/lang/String;)V");
    M.onConnectionInit = (*env)->GetMethodID(env, cb, "onConnectionInit", "()V");
    M.onConnectionDestroy = (*env)->GetMethodID(env, cb, "onConnectionDestroy", "()V");
    M.onDisplayPin = (*env)->GetMethodID(env, cb, "onDisplayPin", "(Ljava/lang/String;)V");
    M.onClientRequest = (*env)->GetMethodID(env, cb, "onClientRequest", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z");
    M.onVideoCodec = (*env)->GetMethodID(env, cb, "onVideoCodec", "(ZII)V");
    M.onVideoData = (*env)->GetMethodID(env, cb, "onVideoData", "([BJZ)V");
    M.onMirrorRunning = (*env)->GetMethodID(env, cb, "onMirrorRunning", "(Z)V");
    M.onLog = (*env)->GetMethodID(env, cb, "onLog", "(ILjava/lang/String;)V");

    jclass native = (*env)->FindClass(env, "com/benchan/wireless/screencasting/protocol/airplay/UxPlayNative");
    if (!native) return JNI_ERR;
    static const JNINativeMethod methods[] = {
        {"nativeStart", "(Lcom/benchan/wireless/screencasting/protocol/airplay/UxPlayCallbacks;Ljava/lang/String;[BZLjava/lang/String;)I", (void *) native_start},
        {"nativeStop", "()V", (void *) native_stop},
    };
    if ((*env)->RegisterNatives(env, native, methods, 2) != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_6;
}
