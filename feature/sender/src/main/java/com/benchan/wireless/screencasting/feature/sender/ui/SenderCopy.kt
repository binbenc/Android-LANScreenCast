package com.benchan.wireless.screencasting.feature.sender.ui

import com.benchan.wireless.screencasting.feature.sender.session.ConnectFailure
import com.benchan.wireless.screencasting.feature.sender.session.EndReason

/** Snackbar 上的“下一步”按钮。 */
internal enum class EndedAction(val label: String) {
    DIAGNOSTICS("网络诊断"),
    RETYPE_CAST_CODE("重新输入"),
    RESCAN("重新扫码"),
}

/**
 * 发送端结束提示的文案（纯函数，便于单测）。原则：说清发生了什么 + 下一步怎么做；
 * 协议错误码只写日志，不出现在界面上（PM/UX 审查 B1、C6）。
 */
internal object SenderCopy {

    /** 结束原因 → Snackbar 文案；返回 null 表示用整页（[EndedScreen]）或不提示。 */
    fun endedSnackbar(reason: EndReason, name: String?, failure: ConnectFailure? = null): String? {
        val n = name ?: "电视"
        return when (reason) {
            EndReason.REJECTED -> "$n 拒绝了投屏请求"
            EndReason.CAST_CODE_WRONG -> "投屏码不正确。电视上的投屏码已经更新，请按电视上显示的新码重新输入"
            EndReason.QR_EXPIRED -> "二维码已过期，请重新扫描电视上的二维码"
            EndReason.AUTH_FAILED -> "身份验证失败，请重试；仍失败时在两台设备上都移除对方后重新配对"
            EndReason.CONFIRM_TIMEOUT -> "$n 在 30 秒内没有确认，请重试"
            EndReason.PIN_TIMEOUT -> "30 秒内没有完成配对。请重新连接，在电视上选择“允许”后输入电视上显示的 PIN"
            EndReason.RECEIVER_NO_DISPLAY -> "$n 上没有打开“等待投屏”页面，请在电视上打开本应用后重试"
            EndReason.PAIR_LOCKED -> "尝试次数过多，请 60 秒后再试"
            EndReason.CONNECT_FAILED -> connectFailed(n, failure)
            EndReason.CERTIFICATE_MISMATCH -> "设备身份校验失败：可能是其他设备冒充，或接收端重装过（可在设置中移除信任后重新配对）"
            EndReason.BUSY -> "$n 正在接收其他投屏。请先在电视上结束当前投屏后再试"
            EndReason.REMOTE_CLOSED -> "$n 已断开投屏"
            EndReason.INTERNAL_ERROR -> "连接出错了，请重试；反复出现时请把两台设备都更新到最新版本"
            else -> null
        }
    }

    private fun connectFailed(n: String, failure: ConnectFailure?): String = when (failure) {
        ConnectFailure.HANDSHAKE_TIMEOUT -> "$n 没有回应：请确认电视停留在“等待投屏”页面，路由器可能拦截了设备互访"
        ConnectFailure.QR_PAIR_TIMEOUT -> "扫码配对没有回应：请确认电视停留在“等待投屏”页面后重新扫码"
        ConnectFailure.CODE_PAIR_TIMEOUT -> "投屏码配对没有回应：请核对电视上的投屏码后重试，或改用扫码连接"
        ConnectFailure.FIRST_FRAME_TIMEOUT -> "画面一直传不过去：路由器可能限制了设备间的视频传输，可以试试网络诊断"
        ConnectFailure.MEDIA_BLOCKED -> "画面传输被路由器阻断：手机和电视能互相找到，但视频数据过不去（可能开启了 AP 隔离或连的是访客网络），可以试试网络诊断"
        ConnectFailure.LOST -> "与 $n 的连接中断了，请重试"
        ConnectFailure.UNREACHABLE, null -> "连不上 $n：请确认电视已打开“等待投屏”页面，并且和手机连同一个路由器"
    }

    /** Snackbar 的下一步按钮。 */
    fun endedAction(reason: EndReason): EndedAction? = when (reason) {
        EndReason.CONNECT_FAILED -> EndedAction.DIAGNOSTICS
        EndReason.CAST_CODE_WRONG -> EndedAction.RETYPE_CAST_CODE
        EndReason.QR_EXPIRED -> EndedAction.RESCAN
        else -> null
    }

    /** 超过约 20 字的提示用长时长显示，来得及读完（S1）。 */
    fun isLongText(text: String): Boolean = text.length > 20

    /** 05B 投屏码页的网络提示；null 表示当前网络可以使用投屏码。 */
    fun castCodeNetworkHint(hasLan: Boolean, prefixSupported: Boolean): String? = when {
        !hasLan -> "手机没有连接 Wi‑Fi。请先连接和电视相同的路由器"
        !prefixSupported -> "当前网络不支持投屏码（网段较大），请改用扫码或手动输入地址"
        else -> null
    }

    /** 列表中置灰的 DLNA 设备被点击时的说明。 */
    fun dlnaOnlyDevice(name: String): String = "$name 只支持 DLNA 推送，暂不能镜像屏幕。可以在视频 App 里点“投屏”按钮推送到它"

    const val NOTIFICATION_TIP = "允许通知后，可以在通知栏停止投屏"
    const val NOTIFICATION_ALLOW = "允许通知"
    const val LOCK_SCREEN_TIP = "提示：锁屏会停止投屏，镜像期间请保持手机亮屏"
    const val AUDIO_NO_PERMISSION = "未授予录音权限，只投画面。可在系统设置中开启，下次投屏生效"
    const val CAMERA_DENIED = "需要相机权限才能扫码"
    const val CAMERA_DENIED_ACTION = "改用投屏码"
    const val CAMERA_PERMANENTLY_DENIED = "相机权限已被关闭，可在系统设置中开启"
    const val OPEN_SETTINGS = "去系统设置"
}
