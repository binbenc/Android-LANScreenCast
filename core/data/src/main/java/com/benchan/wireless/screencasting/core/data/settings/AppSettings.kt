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
    /** 反向控制（PRD F23）：允许接收端在投屏时请求操作本机，默认关闭；每次会话仍需本人确认（决策 #16）。 */
    val remoteControlEnabled: Boolean = false,
    /** TV 接收端反向控制模式（光标 / 方向键），切换后记住到下次（交互稿 R11）。 */
    val tvControlMode: com.benchan.wireless.screencasting.core.control.TvControlMode = com.benchan.wireless.screencasting.core.control.TvControlMode.CURSOR,
    /** 投屏结束后自动关闭无障碍服务（R01，C 期，默认关）。 */
    val remoteControlAutoDisable: Boolean = false,
    /** 上次会话结束时已按设置自动关闭了无障碍服务（R01 显示说明卡，服务重新开启后清除）。 */
    val remoteControlAutoDisabled: Boolean = false,
    /** 接收端“双指缩放会在松手后执行”提示已显示过（每次安装一次）。 */
    val pinchHintShown: Boolean = false,
    /** TV 首次进入控制的操作提示卡已看过（交互稿 R10）。 */
    val tvControlHintShown: Boolean = false,
    /** F14 Direct Share：在系统分享菜单显示最近推送成功的电视（默认开，决策 #34）。 */
    val directShareEnabled: Boolean = true,
    /** F14 分享页“第一次使用”说明已关闭（交互稿 S01）。 */
    val shareIntroDismissed: Boolean = false,
    /** F24 长辈模式（只在发送端模式生效，PRD 8.1.4）。 */
    val elderModeEnabled: Boolean = false,
    /** F24 家人固定的 1~2 台电视（决策 #41）；空 = 用上次成功投屏的设备。退出长辈模式时保留，重新开启时向导预填。 */
    val elderTargets: List<ElderTarget> = emptyList(),
    /** F24 设备列表顶部“字太小？可以打开长辈模式”推荐卡已关闭（交互稿 E02，永不再出现）。 */
    val elderRecommendDismissed: Boolean = false,
    /** F24 刚退出长辈模式：设备列表顶部常驻提示，点“知道了”清除（交互稿 E11）。 */
    val elderExitNotice: Boolean = false,
)

/** 实际生效的模式：未手动选择时按设备类型（TV → 接收端，其余 → 发送端）。 */
fun AppSettings.effectiveMode(isTv: Boolean): AppMode = mode ?: if (isTv) AppMode.RECEIVER else AppMode.SENDER
