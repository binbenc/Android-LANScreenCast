package com.benchan.wireless.screencasting.core.protocol.quality

import kotlinx.serialization.Serializable

/** 画质档位对应的降级偏好（映射到 WebRTC RtpParameters.DegradationPreference）。 */
enum class Degradation { MAINTAIN_FRAMERATE, MAINTAIN_RESOLUTION, BALANCED }

/**
 * 画质档位（PRD 8.6、技术方案 3.4 帧率与码率策略表）。
 * GAME 在 MVP 只映射为 720p60 + 音视频不同步组，抖动缓冲专项优化在 V1（F18，交互稿 Q5）。
 */
@Serializable
enum class QualityProfile(
    val label: String,
    val longEdge: Int,
    val shortEdge: Int,
    val fps: Int,
    val maxBitrateBps: Int,
    val minBitrateBps: Int,
    val degradation: Degradation,
    /** 接收端 WebRTC-ForcePlayoutDelay 的 max_ms（M0 实测后校准）。 */
    val playoutDelayMaxMs: Int,
    /** 是否把音频与视频放进同一个 MediaStream 做唇音同步（游戏档关闭）。 */
    val avSync: Boolean,
) {
    SMOOTH("流畅", 1280, 720, 30, 4_000_000, 1_000_000, Degradation.MAINTAIN_FRAMERATE, 150, true),
    BALANCED("均衡", 1920, 1080, 30, 8_000_000, 1_000_000, Degradation.BALANCED, 120, true),
    HD("高清", 1920, 1080, 60, 15_000_000, 2_000_000, Degradation.MAINTAIN_RESOLUTION, 150, true),
    GAME("游戏", 1280, 720, 60, 8_000_000, 1_000_000, Degradation.MAINTAIN_FRAMERATE, 50, false);

    /** 首帧起始码率 = 上限的 50%（技术方案 3.2：抬高 GCC 起始码率）。 */
    val startBitrateBps: Int get() = maxBitrateBps / 2

    /** 交互稿 07 的说明文字，例如“1080p · 30 帧 · 最高 8 Mbps”。 */
    val summary: String get() = "${shortEdge}p · $fps 帧 · 最高 ${maxBitrateBps / 1_000_000} Mbps"

    companion object {
        val DEFAULT = BALANCED
    }
}

/** 采集尺寸（宽高都是 16 的倍数）。 */
data class CaptureSize(val width: Int, val height: Int) {
    companion object {
        private const val ALIGN = 16

        /**
         * 把屏幕尺寸等比缩放进档位的外接框（横屏 long×short，竖屏 short×long），并向下对齐到 16（技术方案 3.2）。
         * 屏幕本身比框小时不放大。
         */
        fun fit(screenWidth: Int, screenHeight: Int, profile: QualityProfile): CaptureSize {
            require(screenWidth > 0 && screenHeight > 0)
            val landscape = screenWidth >= screenHeight
            val boxW = if (landscape) profile.longEdge else profile.shortEdge
            val boxH = if (landscape) profile.shortEdge else profile.longEdge
            val scale = minOf(1.0, minOf(boxW.toDouble() / screenWidth, boxH.toDouble() / screenHeight))
            val w = align((screenWidth * scale).toInt())
            val h = align((screenHeight * scale).toInt())
            return CaptureSize(w, h)
        }

        private fun align(v: Int): Int = (v / ALIGN * ALIGN).coerceAtLeast(ALIGN)
    }
}
