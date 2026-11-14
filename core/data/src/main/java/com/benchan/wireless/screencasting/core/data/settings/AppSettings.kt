package com.benchan.wireless.screencasting.core.data.settings

import com.benchan.wireless.screencasting.core.protocol.quality.QualityProfile

/** 单 APK 双模式（决策 #5）。 */
enum class AppMode { SENDER, RECEIVER }

/** 连接策略（决策 #13）：可信设备免确认 / 每次都确认。 */
enum class ConnectionPolicy { TRUSTED_SKIP_CONFIRM, ALWAYS_CONFIRM }

/** 设置项（PRD 8.6）。 */
data class AppSettings(
    val deviceName: String,
    /** null = 按设备类型自动（手机/平板发送、TV 接收）。 */
    val mode: AppMode?,
    val onboardingDone: Boolean = false,
    val quality: QualityProfile = QualityProfile.DEFAULT,
    val autoAdjustQuality: Boolean = true,
    val audioEnabled: Boolean = true,
    val connectionPolicy: ConnectionPolicy = ConnectionPolicy.TRUSTED_SKIP_CONFIRM,
    val dlnaEnabled: Boolean = true,
    val dlnaConfirmUnknown: Boolean = true,
    /** 兼容 Apple 设备（非官方），默认关闭（决策 #3）。 */
    val appleCompatEnabled: Boolean = false,
    /** 兼容 Apple 设备时连接要求 PIN（交互稿默认开，Q7）。 */
    val appleCompatPin: Boolean = true,
    /** 开机自启，默认关闭（决策 #14）。 */
    val bootStart: Boolean = false,
    /** 录屏授权前说明卡“下次不再提示”。 */
    val skipCaptureIntro: Boolean = false,
)
