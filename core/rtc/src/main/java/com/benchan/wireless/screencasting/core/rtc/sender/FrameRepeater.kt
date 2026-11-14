package com.benchan.wireless.screencasting.core.rtc.sender

import android.os.SystemClock
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame

/**
 * 静止画面保底帧率（技术方案 3.2）：VirtualDisplay 只在内容变化时出帧。超过 1000/minFps ms 没有新帧时，
 * 用 SurfaceTextureHelper.forceFrame() 重发最后一帧（OES 纹理仍保留上一帧内容），避免接收端误判卡死、GCC 低估带宽。
 *
 * 重发帧的时间戳与上一帧相同，会被编码器当作“旧帧”丢弃，所以这里统一改写为单调递增的时间戳。
 * 同时把帧交给 [frameTap]（受保护内容检测按 1 帧/秒抽样）。
 */
class FrameRepeater(
    private val downstream: CapturerObserver,
    private val helper: SurfaceTextureHelper,
    minFps: Int = 5,
    private val frameTap: ((VideoFrame) -> Unit)? = null,
) : CapturerObserver {
    private val intervalMs = (1000 / minFps.coerceIn(1, 30)).toLong()

    @Volatile
    private var lastFrameAtMs = 0L
    private var lastTimestampNs = 0L

    @Volatile
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (SystemClock.elapsedRealtime() - lastFrameAtMs >= intervalMs && lastFrameAtMs > 0) {
                helper.forceFrame()
            }
            helper.handler.postDelayed(this, intervalMs)
        }
    }

    override fun onCapturerStarted(success: Boolean) {
        downstream.onCapturerStarted(success)
        if (success) {
            running = true
            helper.handler.postDelayed(tick, intervalMs)
        }
    }

    override fun onCapturerStopped() {
        running = false
        helper.handler.removeCallbacks(tick)
        downstream.onCapturerStopped()
    }

    override fun onFrameCaptured(frame: VideoFrame) {
        lastFrameAtMs = SystemClock.elapsedRealtime()
        frameTap?.invoke(frame)
        if (frame.timestampNs > lastTimestampNs) {
            lastTimestampNs = frame.timestampNs
            downstream.onFrameCaptured(frame)
            return
        }
        // 重发帧：改写时间戳（buffer 引用计数 +1，转发后释放新帧）
        val ts = maxOf(System.nanoTime(), lastTimestampNs + 1)
        lastTimestampNs = ts
        frame.buffer.retain()
        val restamped = VideoFrame(frame.buffer, frame.rotation, ts)
        try {
            downstream.onFrameCaptured(restamped)
        } finally {
            restamped.release()
        }
    }
}
