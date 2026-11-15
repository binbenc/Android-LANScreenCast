package com.benchan.wireless.screencasting.feature.control

import com.benchan.wireless.screencasting.core.protocol.control.ControlPhase
import com.benchan.wireless.screencasting.core.protocol.control.ControlReason
import com.benchan.wireless.screencasting.core.protocol.control.InputMessage
import com.benchan.wireless.screencasting.core.protocol.datachannel.DcMessage

/**
 * 当前投屏会话与反向控制之间的连接（由 SenderController 实现）。只在 WSC 镜像会话中存在：
 * DLNA / 兼容 Apple 设备的会话没有 wsc-input 通道，不会创建本连接。
 */
interface ControlLink {
    /** 对方（接收端）显示名。 */
    val peerName: String

    /** DataChannel `wsc`。 */
    fun sendDc(message: DcMessage)

    /** DataChannel `wsc-input`（发送端只回 IREJ）。 */
    fun sendInput(message: InputMessage)
}

/** 发送端界面看到的控制状态（投屏页 07 / R04、通知、确认页 R03）。 */
data class ControlUi(
    val phase: ControlPhase = ControlPhase.UNAVAILABLE,
    val reason: ControlReason? = null,
    val peerName: String = "",
    /** 授权开始的墙钟时间（“已控制 03:12”）；未授权为 0。 */
    val grantedAt: Long = 0,
    /** PENDING：自动拒绝的墙钟时间。 */
    val pendingDeadline: Long = 0,
    /** 是否有进行中的投屏会话。 */
    val inSession: Boolean = false,
) {
    /** 正在被控制（含暂停）：显示控制卡、胶囊段“被控制中”、通知“××正在控制本机”。 */
    val controlled: Boolean get() = phase == ControlPhase.ACTIVE || phase == ControlPhase.PAUSED
}

/** 一次性事件（Snackbar）。 */
sealed interface ControlEvent {
    /** 控制结束：[reason] = REVOKED（本机收回）/ RECEIVER_END / LOCKED / SESSION_END …… */
    data class Ended(val reason: ControlReason) : ControlEvent
}

/** 交互稿 R05 / R08 的暂停原因短语。 */
object ControlCopy {
    /** 回到桌面（D9：离开本应用后对方才能操作）。 */
    fun goHome(context: android.content.Context) {
        val home = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(home) }
    }

    fun pauseLabel(reason: ControlReason?): String = when (reason) {
        ControlReason.RECONNECTING -> "正在重连"
        ControlReason.PROTECTED -> "画面受保护"
        ControlReason.APP_HIDDEN -> "已离开共享的应用"
        ControlReason.WINDOWED -> "分屏时不可控制"
        ControlReason.OWN_APP -> "本应用内不可控制"
        ControlReason.SENSITIVE_APP -> "此界面不可控制"
        else -> "暂停"
    }

    /** 边缘胶囊文案；字号 ≥130% 时去掉“控制”二字（UX 走查 G1）。 */
    fun overlayLabel(phase: ControlPhase, reason: ControlReason?, largeFont: Boolean = false): String = when {
        phase != ControlPhase.PAUSED -> "控制中"
        largeFont -> "已暂停 · ${pauseLabel(reason)}"
        else -> "控制已暂停 · ${pauseLabel(reason)}"
    }

    /** 屏幕朗读开启时的提示（UX 走查 §4）。 */
    const val TALKBACK_CONSENT = "本机开着屏幕朗读（如 TalkBack），对方的点击和滑动可能无法正常生效"
    const val TALKBACK_CARD = "开着屏幕朗读时，对方的点击和滑动可能无法正常生效"

    /** 隐私说明（用户决策 D-1，各处统一，避免“包名”一词）。 */
    const val PRIVACY_SHORT = "只识别当前打开的是哪个应用、哪个页面（不含页面上的文字），用于在一些界面（例如安装应用、授予权限）自动暂停控制。不收集任何数据。"
}
