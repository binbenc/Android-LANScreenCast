/*
 *  Derived from WebRTC's ScreenCapturerAndroid.java
 *  (sdk/android/api/org/webrtc/ScreenCapturerAndroid.java):
 *
 *  Copyright 2016 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the WebRTC source
 *  tree (BSD-3-Clause). An additional intellectual property rights grant can be
 *  found in the file PATENTS. All contributing project authors may be found in
 *  the AUTHORS file in the root of the WebRTC source tree.
 *
 *  Modifications for WirelessScreenCasting (GPL-3.0-or-later as a whole):
 *  the Surface handed to the VirtualDisplay is owned and released explicitly.
 *  See core/rtc/THIRD_PARTY.md.
 */
package com.benchan.wireless.screencasting.core.rtc.sender

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.ThreadUtils
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * 屏幕采集器（行为与 libwebrtc 150 的 `ScreenCapturerAndroid` 一致：先 registerCallback 再 createVirtualDisplay，
 * 改尺寸时 `VirtualDisplay.resize` 并重新 setSurface，一个会话只有一个 VirtualDisplay）。
 *
 * 唯一的区别：SDK 每次 changeCaptureFormat 都 `setSurface(new Surface(...))` 而不释放旧的 Surface，
 * StrictMode 报 LeakedClosableViolation（F18 模拟器复测 EMU-F4），每次切档 / 旋转都多一个等 GC 回收的 Surface。
 * 这里自己持有 Surface：换上新 Surface 之后立即 release 旧的，停止采集时也释放。
 */
class WscScreenCapturer(
    private val permissionData: Intent,
    private val projectionCallback: MediaProjection.Callback,
) : VideoCapturer, VideoSink {
    private var width = 0
    private var height = 0
    private var virtualDisplay: VirtualDisplay? = null
    private var surface: Surface? = null
    private var helper: SurfaceTextureHelper? = null
    private var observer: CapturerObserver? = null
    private var projectionManager: MediaProjectionManager? = null
    private var disposed = false

    /** startCapture 之后可用；会话期间不变。 */
    var mediaProjection: MediaProjection? = null
        private set

    private fun checkNotDisposed() {
        if (disposed) throw RuntimeException("capturer is disposed.")
    }

    @Synchronized
    override fun initialize(surfaceTextureHelper: SurfaceTextureHelper?, context: Context, capturerObserver: CapturerObserver?) {
        checkNotDisposed()
        observer = capturerObserver ?: throw RuntimeException("capturerObserver not set.")
        helper = surfaceTextureHelper ?: throw RuntimeException("surfaceTextureHelper not set.")
        projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    @Synchronized
    override fun startCapture(width: Int, height: Int, ignoredFramerate: Int) {
        checkNotDisposed()
        this.width = width
        this.height = height
        val h = helper ?: return
        val mp = projectionManager?.getMediaProjection(Activity.RESULT_OK, permissionData) ?: error("getMediaProjection returned null")
        mediaProjection = mp
        // Android 14+：必须在 createVirtualDisplay 之前注册回调
        mp.registerCallback(projectionCallback, h.handler)
        updateVirtualDisplay()
        observer?.onCapturerStarted(true)
        h.startListening(this)
    }

    @Synchronized
    override fun stopCapture() {
        checkNotDisposed()
        val h = helper ?: return
        ThreadUtils.invokeAtFrontUninterruptibly(h.handler) {
            h.stopListening()
            observer?.onCapturerStopped()
            virtualDisplay?.release()
            virtualDisplay = null
            surface?.release()
            surface = null
            mediaProjection?.let {
                it.unregisterCallback(projectionCallback)
                it.stop()
            }
            mediaProjection = null
        }
    }

    @Synchronized
    override fun dispose() {
        disposed = true
    }

    @Synchronized
    override fun changeCaptureFormat(width: Int, height: Int, ignoredFramerate: Int) {
        checkNotDisposed()
        this.width = width
        this.height = height
        if (virtualDisplay == null) return
        val h = helper ?: return
        ThreadUtils.invokeAtFrontUninterruptibly(h.handler) { updateVirtualDisplay() }
    }

    /** 在 SurfaceTextureHelper 线程（或 startCapture 中）调用。 */
    private fun updateVirtualDisplay() {
        val h = helper ?: return
        h.setTextureSize(width, height)
        val vd = virtualDisplay
        if (vd == null) {
            createVirtualDisplay()
            return
        }
        vd.resize(width, height, VIRTUAL_DISPLAY_DPI)
        // 与 SDK 相同：换一个新 Surface 让新的缓冲区尺寸生效；不同的是旧 Surface 立即释放
        val next = Surface(h.surfaceTexture)
        vd.surface = next
        surface?.release()
        surface = next
    }

    private fun createVirtualDisplay() {
        val h = helper ?: return
        virtualDisplay?.release()
        surface?.release()
        val s = Surface(h.surfaceTexture)
        surface = s
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "WebRTC_ScreenCapture", width, height, VIRTUAL_DISPLAY_DPI, DISPLAY_FLAGS, s, null, null,
        )
    }

    override fun onFrame(frame: VideoFrame) {
        observer?.onFrameCaptured(frame)
    }

    override fun isScreencast(): Boolean = true

    private companion object {
        const val DISPLAY_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        const val VIRTUAL_DISPLAY_DPI = 400
    }
}
