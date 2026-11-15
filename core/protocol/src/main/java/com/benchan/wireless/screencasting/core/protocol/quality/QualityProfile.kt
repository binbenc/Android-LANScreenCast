package com.benchan.wireless.screencasting.core.protocol.quality

import kotlinx.serialization.Serializable

/** 画质档位对应的降级偏好（映射到 WebRTC RtpParameters.DegradationPreference）。 */
enum class Degradation { MAINTAIN_FRAMERATE, MAINTAIN_RESOLUTION, BALANCED }

/**
 * 画质档位（PRD 8.6、技术方案 3.4 帧率与码率策略表）。
 * GAME = F18 游戏模式（PRD 8.1.3）：720p60 + 音视频不同步组 + 接收端低延迟参数（[ReceiverTuning]）+ 自动调节不切档。
 * 接收端播放缓冲等参数不再按档位逐个配置，而是按“是否低延迟”两类集中在 [ReceiverTuning]（M0-T4 扫参后只改那里）。
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
    /** 是否把音频与视频放进同一个 MediaStream 做唇音同步（游戏档关闭）。 */
    val avSync: Boolean,
) {
    SMOOTH("流畅", 1280, 720, 30, 4_000_000, 1_000_000, Degradation.MAINTAIN_FRAMERATE, true),
    BALANCED("均衡", 1920, 1080, 30, 8_000_000, 1_000_000, Degradation.BALANCED, true),
    HD("高清", 1920, 1080, 60, 15_000_000, 2_000_000, Degradation.MAINTAIN_RESOLUTION, true),
    GAME("游戏", 1280, 720, 60, 8_000_000, 1_000_000, Degradation.MAINTAIN_FRAMERATE, false);

    /** 游戏模式（F18）：接收端最低缓冲 + 低延迟解码 + 音画分组，“自动调节”不切出该档。 */
    val lowLatency: Boolean get() = this == GAME

    /** 首帧起始码率 = 上限的 50%（技术方案 3.2：抬高 GCC 起始码率）。 */
    val startBitrateBps: Int get() = maxBitrateBps / 2

    /** 交互稿 07 的说明文字，例如“1080p · 30 帧 · 最高 8 Mbps”。 */
    val summary: String get() = "${shortEdge}p · $fps 帧 · 最高 ${maxBitrateBps / 1_000_000} Mbps"

    companion object {
        val DEFAULT = BALANCED

        /** 线上字符串 → 档位；未知值（新版本加的档位）返回 null，调用方按普通档处理（PROTOCOL.md 2.2 / 4.3）。 */
        fun fromWire(wire: String?): QualityProfile? = entries.firstOrNull { it.name == wire }

        /**
         * 会话中从 [from] 切到 [to] 是否要重建媒体连接（F18 会话中切换，PROTOCOL.md 4.3）：
         * 接收端播放缓冲（字段试验）和解码器在建立 PeerConnectionFactory 时固定，音视频分组在 addTrack 时固定，
         * 二者任一变化都只能新建 PeerConnection。其他切换（分辨率 / 帧率 / 码率）走 SET_QUALITY 即时生效。
         */
        fun needsMediaRebuild(from: QualityProfile, to: QualityProfile): Boolean =
            ReceiverTuning.of(from) != ReceiverTuning.of(to) || from.avSync != to.avSync
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
