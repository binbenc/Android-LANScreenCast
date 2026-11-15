/*
 *  Portions derived from WebRTC's MediaCodecWrapperFactoryImpl.java
 *  (sdk/android/src/java/org/webrtc/MediaCodecWrapperFactoryImpl.java):
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
 *  configure() adds low-latency keys and falls back to the original format.
 *  See core/rtc/THIRD_PARTY.md.
 */
package org.webrtc;

import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaCodecInfo;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.view.Surface;
import com.benchan.wireless.screencasting.core.rtc.receiver.DecoderLowLatencyKeys;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;

/**
 * 创建 MediaCodec 并在 configure 时加入低延迟参数（F18）：
 * API 30+ 且解码器声明 FEATURE_LowLatency 时 {@code KEY_LOW_LATENCY=1}；{@code KEY_PRIORITY=0}（实时）、
 * {@code KEY_OPERATING_RATE}；以及按解码器名前缀匹配的厂商私有键（{@link DecoderLowLatencyKeys}，待真机兼容表校准）。
 * 带参数 configure 抛异常时 reset 后按原参数重试，保证“最差也和标准解码器一样”。
 */
final class WscLowLatencyMediaCodecFactory implements MediaCodecWrapperFactory {
  private static final String TAG = "WscLowLatencyDecoder";

  private final boolean featureLowLatency;
  private final int operatingRate;
  private final WscLowLatencyVideoDecoderFactory.Listener listener;

  WscLowLatencyMediaCodecFactory(
      boolean featureLowLatency,
      int operatingRate,
      WscLowLatencyVideoDecoderFactory.Listener listener) {
    this.featureLowLatency = featureLowLatency;
    this.operatingRate = operatingRate;
    this.listener = listener;
  }

  @Override
  public MediaCodecWrapper createByCodecName(String name) throws IOException {
    return new Wrapper(MediaCodec.createByCodecName(name), name);
  }

  private final class Wrapper implements MediaCodecWrapper {
    private final MediaCodec codec;
    private final String name;

    Wrapper(MediaCodec codec, String name) {
      this.codec = codec;
      this.name = name;
    }

    @Override
    public void configure(MediaFormat format, Surface surface, MediaCrypto crypto, int flags) {
      MediaFormat tuned = new MediaFormat(format);
      boolean lowLatency = false;
      if (featureLowLatency && Build.VERSION.SDK_INT >= 30) {
        tuned.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        lowLatency = true;
      }
      Map<String, Integer> vendor = DecoderLowLatencyKeys.vendorKeys(name);
      for (Map.Entry<String, Integer> e : vendor.entrySet()) {
        tuned.setInteger(e.getKey(), e.getValue());
      }
      lowLatency |= !vendor.isEmpty();
      tuned.setInteger(MediaFormat.KEY_PRIORITY, 0);
      if (operatingRate > 0) {
        tuned.setInteger(MediaFormat.KEY_OPERATING_RATE, operatingRate);
      }
      try {
        codec.configure(tuned, surface, crypto, flags);
        Logging.d(TAG, name + " configured, lowLatency=" + lowLatency);
        notifyConfigured(lowLatency);
      } catch (RuntimeException e) {
        // 部分 ROM 对未知 / 不支持的键直接抛异常：回到 libwebrtc 原始参数（与标准解码器一致）
        Logging.w(TAG, name + " rejected low-latency format, retry with standard format: " + e);
        try {
          codec.reset();
        } catch (RuntimeException ignored) {
          // reset 失败时下面的 configure 会抛出，由 AndroidVideoDecoder 回退到标准解码器
        }
        codec.configure(format, surface, crypto, flags);
        notifyConfigured(false);
      }
    }

    private void notifyConfigured(boolean lowLatency) {
      if (listener != null) {
        try {
          listener.onDecoderConfigured(name, lowLatency);
        } catch (RuntimeException ignored) {
          // 监听器只用于统计，不能影响解码
        }
      }
    }

    @Override
    public void start() {
      codec.start();
    }

    @Override
    public void flush() {
      codec.flush();
    }

    @Override
    public void stop() {
      codec.stop();
    }

    @Override
    public void release() {
      codec.release();
    }

    @Override
    public int dequeueInputBuffer(long timeoutUs) {
      return codec.dequeueInputBuffer(timeoutUs);
    }

    @Override
    public void queueInputBuffer(
        int index, int offset, int size, long presentationTimeUs, int flags) {
      codec.queueInputBuffer(index, offset, size, presentationTimeUs, flags);
    }

    @Override
    public int dequeueOutputBuffer(BufferInfo info, long timeoutUs) {
      return codec.dequeueOutputBuffer(info, timeoutUs);
    }

    @Override
    public void releaseOutputBuffer(int index, boolean render) {
      codec.releaseOutputBuffer(index, render);
    }

    @Override
    public MediaFormat getInputFormat() {
      return codec.getInputFormat();
    }

    @Override
    public MediaFormat getOutputFormat() {
      return codec.getOutputFormat();
    }

    @Override
    public MediaFormat getOutputFormat(int index) {
      return codec.getOutputFormat(index);
    }

    @Override
    public ByteBuffer getInputBuffer(int index) {
      return codec.getInputBuffer(index);
    }

    @Override
    public ByteBuffer getOutputBuffer(int index) {
      return codec.getOutputBuffer(index);
    }

    @Override
    public Surface createInputSurface() {
      return codec.createInputSurface();
    }

    @Override
    public void setParameters(Bundle params) {
      codec.setParameters(params);
    }

    @Override
    public MediaCodecInfo getCodecInfo() {
      return codec.getCodecInfo();
    }
  }
}
