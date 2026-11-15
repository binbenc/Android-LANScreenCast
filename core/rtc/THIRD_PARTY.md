# core/rtc 第三方代码说明

本模块整体按项目许可证 GPL-3.0-or-later 发布。以下文件包含改写自 WebRTC 的代码，保留原始版权声明：

| 本仓库文件 | 来源 | 许可证 | 改动 |
|---|---|---|---|
| `src/main/java/org/webrtc/WscLowLatencyVideoDecoderFactory.java` | WebRTC `sdk/android/src/java/org/webrtc/MediaCodecVideoDecoderFactory.java`（Copyright 2018 The WebRTC project authors），与本项目使用的 `io.github.webrtc-sdk:android:150.7871.04` 同一里程碑 | BSD-3-Clause（WebRTC 源码树根目录 `LICENSE`，另有 `PATENTS` 专利授权） | 解码器挑选规则沿用上游（第一个硬件加速、颜色格式可用的解码器）；改为用 `WscLowLatencyMediaCodecFactory` 创建 `AndroidVideoDecoder`，并用 `VideoDecoderFallback` 包一层标准解码器作为回退 |
| `src/main/java/com/benchan/wireless/screencasting/core/rtc/sender/WscScreenCapturer.kt` | WebRTC `sdk/android/api/org/webrtc/ScreenCapturerAndroid.java`（Copyright 2016 The WebRTC project authors），按 150.7871.04 字节码核对行为 | BSD-3-Clause | 自己持有交给 VirtualDisplay 的 Surface：改尺寸换新 Surface 后立即 release 旧的、停止时释放（SDK 版本不释放，StrictMode LeakedClosableViolation，F18 EMU-F4） |
| `src/main/java/org/webrtc/WscLowLatencyMediaCodecFactory.java` | WebRTC `sdk/android/src/java/org/webrtc/MediaCodecWrapperFactoryImpl.java`（Copyright 2018 The WebRTC project authors） | BSD-3-Clause | `configure()` 加入 `KEY_LOW_LATENCY`（API 30+ 且声明 `FEATURE_LowLatency`）、`KEY_PRIORITY`、`KEY_OPERATING_RATE` 与厂商私有键；带参数 configure 失败时 reset 后按原参数重试 |

说明：

- 两个文件放在 `org.webrtc` 包里，是为了复用 libwebrtc 包内可见的 `AndroidVideoDecoder`、`MediaCodecWrapper(Factory)`、`MediaCodecUtils`、`VideoCodecMimeType`，避免整份复制解码器。升级 libwebrtc 版本时需确认这些包内接口的签名没有变化（编译会直接报错）。
- 厂商私有键名（`DecoderLowLatencyKeys.kt`）是各芯片厂商公开的 MediaCodec 扩展参数名，属事实信息，未复制任何第三方实现代码。
- BSD-3-Clause 与 GPL-3.0 兼容；发布时随 NOTICE 一并列出 WebRTC 的版权与许可证（技术方案 3.1：webrtc-sdk 预编译包本身也是 BSD-3）。
