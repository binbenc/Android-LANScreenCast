package com.benchan.wireless.screencasting.core.protocol.control

import com.benchan.wireless.screencasting.core.protocol.message.LenientEnumSerializer
import kotlinx.serialization.Serializable

/**
 * 反向控制（PRD F23，PROTOCOL.md 第 6 节）的发送端授权状态，即 `CTL_STATE.state`。
 * 未知值降级为 [UNAVAILABLE]：接收端停止发送输入，最安全。
 */
@Serializable(with = ControlPhaseSerializer::class)
enum class ControlPhase {
    /** 发送端无法被控制：设置未开启 / 无障碍服务未开启 / 高级保护模式。 */
    UNAVAILABLE,

    /** 可以请求；未授权。 */
    IDLE,

    /** 等待发送端本人确认（≤30 秒）。 */
    PENDING,

    /** 已授权，正在注入。 */
    ACTIVE,

    /** 已授权但暂不注入（重连中 / 画面受保护 / 被共享应用不可见 / 分屏），条件消失后自动恢复。 */
    PAUSED,
}

/**
 * `CTL_STATE.reason` / `CTL_END.reason`。未知值降级为 [UNKNOWN]（界面显示通用文案）。
 */
@Serializable(with = ControlReasonSerializer::class)
enum class ControlReason {
    /** 发送端设置里没有打开“反向控制”。 */
    SETTING_OFF,

    /** 发送端无障碍服务没有开启（发送端已显示开启步骤）。 */
    SERVICE_OFF,

    /** 发送端开启了高级保护模式（Android 17+），系统不允许非辅助工具使用无障碍服务。 */
    AAPM,

    /** 发送端开着长辈模式（F24，PRD 8.1.4 D）：反向控制不可用，接收端提示“对方手机开着长辈模式，不能远程操作”。旧版按 UNKNOWN 解析。 */
    ELDER_MODE,

    /** 发送端本人拒绝。 */
    USER_DENIED,

    /** 30 秒无响应，视为拒绝。 */
    TIMEOUT,

    /** 接收端取消了请求。 */
    CANCELLED,

    /** 被拒 / 超时 / 取消 / 收回后的冷却期（10 秒）内再次请求。 */
    COOLDOWN,

    /** 发送端收回控制。 */
    REVOKED,

    /** 接收端退出控制。 */
    RECEIVER_END,

    /** 发送端锁屏或熄屏：控制结束，解锁后不自动恢复。 */
    LOCKED,

    /** 暂停原因：网络重连中。 */
    RECONNECTING,

    /** 暂停原因：发送端画面可能受保护（黑帧）。 */
    PROTECTED,

    /** 暂停原因：单应用共享时被共享的应用不在前台。 */
    APP_HIDDEN,

    /** 暂停原因：被共享的内容不是全屏（分屏 / 小窗），坐标无法换算。 */
    WINDOWED,

    /** 暂停原因：发送端正在使用本应用自己的界面（D9：不允许远程操作投屏应用本身，例如收回 / 设置）。 */
    OWN_APP,

    /** 暂停原因（C 期）：发送端前台是系统安装器或无障碍设置等敏感界面（只按前台包名判断，不读取界面内容）。 */
    SENSITIVE_APP,

    /** 投屏会话结束。 */
    SESSION_END,

    UNKNOWN,
}

/** wsc-input 上的指针动作（与 MotionEvent 的 DOWN / MOVE / UP / CANCEL 对应）。 */
@Serializable
enum class PointerAction {
    @kotlinx.serialization.SerialName("d") DOWN,
    @kotlinx.serialization.SerialName("m") MOVE,
    @kotlinx.serialization.SerialName("u") UP,
    @kotlinx.serialization.SerialName("c") CANCEL,
}

/**
 * 平台无关的按键 / 全局动作名（`K.k`）。发送端只执行 [RemoteKey.WHITELIST]：方向键 / 回车 / 删除需要 Android 13+、
 * 播放暂停需要 Android 16+，系统版本不够时回 `IREJ{UNSUPPORTED}`。通知栏 / 快捷设置 / 菜单不开放。未知值降级为 [UNKNOWN]。
 */
@Serializable(with = RemoteKeySerializer::class)
enum class RemoteKey {
    BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS,
    DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT, DPAD_CENTER,
    ENTER, DEL, MENU, PLAY_PAUSE,
    UNKNOWN,
    ;

    companion object {
        /** 全局动作：返回、主页、最近任务（A 期）。 */
        val GLOBAL_WHITELIST: Set<RemoteKey> = setOf(BACK, HOME, RECENTS)

        /** 方向键焦点导航（B 期，发送端 Android 13+）。 */
        val DPAD: Set<RemoteKey> = setOf(DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT, DPAD_CENTER)

        /** 发给当前输入框的编辑键（B 期，发送端 Android 13+，经无障碍输入法）。 */
        val EDITOR: Set<RemoteKey> = setOf(ENTER, DEL)

        /** 发送端会执行的全部按键（NOTIFICATIONS / QUICK_SETTINGS / MENU 不开放）。 */
        val WHITELIST: Set<RemoteKey> = GLOBAL_WHITELIST + DPAD + EDITOR + PLAY_PAUSE
    }
}

/** `IREJ.why`：发送端丢弃某条输入的原因（限速，每类每秒最多一条）。未知值降级为 [UNKNOWN]。 */
@Serializable(with = RejectReasonSerializer::class)
enum class RejectReason {
    /** 当前不是 ACTIVE（未授权 / 暂停）。 */
    PAUSED,

    /** 几何代号与发送端当前采集几何不符（旋转 / 换档瞬间），本次手势已取消。 */
    GEOM_STALE,

    /** 发送端本机有人触摸屏幕，注入被系统取消。 */
    LOCAL_TOUCH,

    /** 落在受保护区域：边缘胶囊、状态栏 / 通知栏、确认页。 */
    BLOCKED_TARGET,

    /** 发送端不支持该操作（系统版本或本期未开放）。 */
    UNSUPPORTED,

    /** 发送端已锁屏。 */
    LOCKED,

    /** TXT / 编辑键：当前输入框是密码框，拒绝远程输入。 */
    SECURE_FIELD,

    /** TXT / 编辑键：发送端当前没有获得焦点的输入框。 */
    NO_EDITOR,

    UNKNOWN,
}

/** 能力字符串（`CTL_STATE.feat`）。 */
object ControlFeatures {
    const val TAP = "tap"
    const val STROKE = "stroke"
    const val GLOBAL = "global"

    /** 滚轮滚动（W 消息，B 期，全部版本）。 */
    const val WHEEL = "wheel"

    /** 方向键焦点导航（B 期，Android 13+）。 */
    const val DPAD = "dpad"

    /** 文字输入与回车 / 删除（B 期，Android 13+）。 */
    const val TEXT = "text"

    /** 播放 / 暂停（B 期，Android 16+）。 */
    const val MEDIA = "media"

    /** 双指缩放（C 期，PINCH 消息；多笔 GestureDescription 自 API 24 起可用，minSdk 29 全覆盖）。 */
    const val MULTI = "multi"

    /** 发送端系统版本（API 级别）对应的能力集合（技术方案 3.12 能力矩阵）。 */
    fun forSdk(sdk: Int): List<String> = buildList {
        add(TAP)
        add(STROKE)
        add(GLOBAL)
        add(WHEEL)
        add(MULTI)
        if (sdk >= 33) {
            add(DPAD)
            add(TEXT)
        }
        if (sdk >= 36) add(MEDIA)
    }
}

object ControlPhaseSerializer : LenientEnumSerializer<ControlPhase>("ControlPhase", ControlPhase.entries.toTypedArray(), ControlPhase.UNAVAILABLE)

object ControlReasonSerializer : LenientEnumSerializer<ControlReason>("ControlReason", ControlReason.entries.toTypedArray(), ControlReason.UNKNOWN)

object RemoteKeySerializer : LenientEnumSerializer<RemoteKey>("RemoteKey", RemoteKey.entries.toTypedArray(), RemoteKey.UNKNOWN)

object RejectReasonSerializer : LenientEnumSerializer<RejectReason>("RejectReason", RejectReason.entries.toTypedArray(), RejectReason.UNKNOWN)
