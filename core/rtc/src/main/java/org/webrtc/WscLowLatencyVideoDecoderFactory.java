/*
 *  Portions derived from WebRTC's MediaCodecVideoDecoderFactory.java
 *  (sdk/android/src/java/org/webrtc/MediaCodecVideoDecoderFactory.java):
 *
 *  Copyright 2018 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the WebRTC source
 *  tree (BSD-3-Clause). An additional intellectual property rights grant can be
 *  found in the file PATENTS. All contributing project authors may be found in
 *  the AUTHORS file in the root of the WebRTC source tree.
 *
 *  Modifications for WirelessScreenCasting (GPL-3.0-or-later as a whole):
 *  decoders are created with WscLowLatencyMediaCodecFactory (KEY_LOW_LATENCY /
 *  vendor low-latency keys), wrapped in a fallback to the standard decoder.
 *  See core/rtc/THIRD_PARTY.md.
 */
package org.webrtc;

import android.media.MediaCodecInfo;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.media.MediaCodecList;
import android.os.Build;

/**
 * 低延迟视频解码器工厂（F18，技术方案 3.4 “硬件解码低延迟”）。
 *
 * <p>放在 org.webrtc 包里是为了复用 libwebrtc 包内可见的 {@code AndroidVideoDecoder}（它通过 {@code MediaCodecWrapperFactory}
 * 创建 MediaCodec，正好是我们插入低延迟参数的位置），而不是整份复制解码器。
 *
 * <p>回退层次（任何一层失败都不影响出画面）：
 * <ol>
 *   <li>configure 带低延迟参数失败 → 同一个 MediaCodec reset 后按原参数 configure（{@link WscLowLatencyMediaCodecFactory}）；</li>
 *   <li>本工厂的解码器 initDecode 失败 → {@link VideoDecoderFallback} 切到标准解码器（与 {@link DefaultVideoDecoderFactory} 相同）；</li>
 *   <li>挑选编解码器时抛异常 / 找不到硬件解码器 → 直接返回标准解码器。</li>
 * </ol>
 * 支持的编解码器列表与 {@link DefaultVideoDecoderFactory} 完全相同，SDP 应答不变。
 */
public final class WscLowLatencyVideoDecoderFactory implements VideoDecoderFactory {
  private static final String TAG = "WscLowLatencyDecoder";

  /** 解码器配置结果（在解码线程回调）。 */
  public interface Listener {
    /**
     * @param codecName MediaCodec 名称
     * @param lowLatency 是否带着低延迟参数（KEY_LOW_LATENCY 或厂商私有键）成功 configure
     */
    void onDecoderConfigured(String codecName, boolean lowLatency);
  }

  private final EglBase.Context sharedContext;
  private final int operatingRate;
  private final Listener listener;
  private final VideoDecoderFactory standard;

  /**
   * @param sharedContext 与渲染器共享的 EGL 上下文（纹理零拷贝）
   * @param operatingRate 期望帧率（KEY_OPERATING_RATE；≤0 不设置）
   */
  public WscLowLatencyVideoDecoderFactory(
      EglBase.Context sharedContext, int operatingRate, Listener listener) {
    this.sharedContext = sharedContext;
    this.operatingRate = operatingRate;
    this.listener = listener;
    this.standard = new DefaultVideoDecoderFactory(sharedContext);
  }

  @Override
  public VideoCodecInfo[] getSupportedCodecs() {
    return standard.getSupportedCodecs();
  }

  @Override
  public VideoDecoder createDecoder(VideoCodecInfo codecInfo) {
    VideoDecoder fallback = standard.createDecoder(codecInfo);
    try {
      VideoCodecMimeType type = VideoCodecMimeType.valueOf(codecInfo.getName());
      MediaCodecInfo info = findHardwareCodec(type);
      if (info == null) {
        return fallback;
      }
      CodecCapabilities caps = info.getCapabilitiesForType(type.mimeType());
      Integer colorFormat =
          MediaCodecUtils.selectColorFormat(MediaCodecUtils.DECODER_COLOR_FORMATS, caps);
      if (colorFormat == null) {
        return fallback;
      }
      boolean featureLowLatency =
          Build.VERSION.SDK_INT >= 30
              && caps.isFeatureSupported(CodecCapabilities.FEATURE_LowLatency);
      VideoDecoder primary =
          new AndroidVideoDecoder(
              new WscLowLatencyMediaCodecFactory(featureLowLatency, operatingRate, listener),
              info.getName(),
              type,
              colorFormat,
              sharedContext);
      Logging.d(TAG, "low-latency decoder " + info.getName() + " featureLowLatency=" + featureLowLatency);
      return fallback != null ? new VideoDecoderFallback(fallback, primary) : primary;
    } catch (Throwable t) {
      // 任何异常（未知编解码器名、MediaCodecList 异常、厂商 ROM 问题）：回到标准解码器
      Logging.w(TAG, "fall back to standard decoder: " + t);
      return fallback;
    }
  }

  /** 与 HardwareVideoDecoderFactory 相同的挑选规则（第一个支持该类型、颜色格式可用、硬件加速的解码器）。 */
  private static MediaCodecInfo findHardwareCodec(VideoCodecMimeType type) {
    MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
    for (MediaCodecInfo info : list.getCodecInfos()) {
      if (info == null || info.isEncoder()) {
        continue;
      }
      if (!MediaCodecUtils.codecSupportsType(info, type)) {
        continue;
      }
      if (MediaCodecUtils.selectColorFormat(
              MediaCodecUtils.DECODER_COLOR_FORMATS, info.getCapabilitiesForType(type.mimeType()))
          == null) {
        continue;
      }
      if (MediaCodecUtils.isHardwareAccelerated(info)) {
        return info;
      }
    }
    return null;
  }
}
