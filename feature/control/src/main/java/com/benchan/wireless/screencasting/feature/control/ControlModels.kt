package com.benchan.wireless.screencasting.feature.control

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
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

/** 交互稿 R05 / R08 的暂停原因短语。文字在资源里（英文默认 / values-zh），跟随系统语言。 */
object ControlCopy {
    /** 回到桌面（D9：离开本应用后对方才能操作）。 */
    fun goHome(context: android.content.Context) {
        val home = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(home) }
    }

    @StringRes
    fun pauseLabelRes(reason: ControlReason?): Int = when (reason) {
        ControlReason.RECONNECTING -> R.string.ctl_pause_reconnecting
        ControlReason.PROTECTED -> R.string.ctl_pause_protected
        ControlReason.APP_HIDDEN -> R.string.ctl_pause_app_hidden
        ControlReason.WINDOWED -> R.string.ctl_pause_windowed
        ControlReason.OWN_APP -> R.string.ctl_pause_own_app
        ControlReason.SENSITIVE_APP -> R.string.ctl_pause_sensitive
        else -> R.string.ctl_pause_other
    }

    @Composable
    fun pauseLabel(reason: ControlReason?): String = stringResource(pauseLabelRes(reason))

    fun pauseLabel(context: Context, reason: ControlReason?): String = context.getString(pauseLabelRes(reason))

    /** 边缘胶囊文案的资源与参数；字号 ≥130% 时去掉“控制”二字（UX 走查 G1）。 */
    @StringRes
    fun overlayLabelRes(phase: ControlPhase, largeFont: Boolean = false): Int = when {
        phase != ControlPhase.PAUSED -> R.string.ctl_overlay_active
        largeFont -> R.string.ctl_overlay_paused_short
        else -> R.string.ctl_overlay_paused
    }

    fun overlayLabel(context: Context, phase: ControlPhase, reason: ControlReason?, largeFont: Boolean = false): String =
        if (phase != ControlPhase.PAUSED) {
            context.getString(overlayLabelRes(phase, largeFont))
        } else {
            context.getString(overlayLabelRes(phase, largeFont), pauseLabel(context, reason))
        }

    /** 屏幕朗读开启时的提示（UX 走查 §4）。 */
    val TALKBACK_CONSENT: String
        @Composable get() = stringResource(R.string.ctl_talkback_consent)
    val TALKBACK_CARD: String
        @Composable get() = stringResource(R.string.ctl_talkback_card)

    /** 隐私说明（用户决策 D-1，各处统一，避免“包名”一词）。 */
    val PRIVACY_SHORT: String
        @Composable get() = stringResource(R.string.ctl_privacy_short)
}
