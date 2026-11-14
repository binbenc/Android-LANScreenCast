package com.benchan.wireless.screencasting.core.rtc.sender

import android.os.SystemClock
import org.webrtc.VideoFrame

/**
 * 受保护内容推断（PRD S-08、交互稿 15 / Q3）：FLAG_SECURE 窗口在采集端是纯黑帧，系统不会通知。
 * 每秒抽 1 帧缩到 64×36 读亮度，连续 [blackSecondsThreshold] 秒全黑（Y ≤ 20）判定为“可能受保护”。
 * 真正的黑场视频也会触发，所以文案只能说“可能”，阈值待技术评审（Q12）。
 */
class ProtectedContentDetector(
    private val blackSecondsThreshold: Int = 3,
    private val onChange: (suspected: Boolean) -> Unit,
) {
    private var lastSampleAt = 0L
    private var blackStreak = 0
    private var suspected = false

    fun onFrame(frame: VideoFrame) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSampleAt < 1000) return
        lastSampleAt = now
        val black = runCatching { isBlack(frame.buffer) }.getOrDefault(false)
        blackStreak = if (black) blackStreak + 1 else 0
        val next = blackStreak >= blackSecondsThreshold
        if (next != suspected) {
            suspected = next
            onChange(next)
        }
    }

    private fun isBlack(buffer: VideoFrame.Buffer): Boolean {
        val small = buffer.cropAndScale(0, 0, buffer.width, buffer.height, 64, 36)
        try {
            val i420 = small.toI420() ?: return false
            try {
                val y = i420.dataY
                val stride = i420.strideY
                for (row in 0 until i420.height) {
                    for (col in 0 until i420.width) {
                        if ((y.get(row * stride + col).toInt() and 0xFF) > MAX_BLACK_LUMA) return false
                    }
                }
                return true
            } finally {
                i420.release()
            }
        } finally {
            small.release()
        }
    }

    private companion object {
        const val MAX_BLACK_LUMA = 20
    }
}
