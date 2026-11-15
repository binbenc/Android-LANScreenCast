package com.benchan.wireless.screencasting.feature.sender.ui

import com.benchan.wireless.screencasting.core.protocol.quality.FpsCapReason
import com.benchan.wireless.screencasting.core.protocol.quality.GameHint
import com.benchan.wireless.screencasting.core.protocol.quality.LatencyCause
import com.benchan.wireless.screencasting.core.protocol.quality.QualityProfile
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


    const val NOTIFICATION_TIP = "允许通知后，可以在通知栏停止投屏"
    const val NOTIFICATION_ALLOW = "允许通知"
    const val LOCK_SCREEN_TIP = "提示：锁屏会停止投屏，镜像期间请保持手机亮屏"
    const val AUDIO_NO_PERMISSION = "未授予录音权限，只投画面。可在系统设置中开启，下次投屏生效"
    const val CAMERA_DENIED = "需要相机权限才能扫码"
    const val CAMERA_DENIED_ACTION = "改用投屏码"
    const val CAMERA_PERMANENTLY_DENIED = "相机权限已被关闭，可在系统设置中开启"
    const val OPEN_SETTINGS = "去系统设置"

    // —— F18 游戏模式（PRD 8.1.3；交互稿 15 G01–G04）。界面不出现任何目标延迟数字（产品拍板），只显示“约 xx ms”估算 ——

    /**
     * 分段按钮下的档位说明（层级 ② 常驻）。音频没有和画面分开（声音关闭、旧版接收端中途切游戏）时不提“声音可能比画面稍晚”；
     * 降为 30 帧时如实写出原因（记住的 30 帧要说明“上次跟不上”，并告诉用户可以重试，PM 走查 P2-7 / UX 走查 P2-18）。
     */
    fun qualityDescription(p: QualityProfile, audioOn: Boolean = true, fpsCapped: FpsCapReason? = null): String = when (p) {
        QualityProfile.SMOOTH -> p.summary + "，网络一般时更稳定"
        QualityProfile.BALANCED -> p.summary + "，适合日常镜像"
        QualityProfile.HD -> p.summary + "，需要较好的 5GHz 网络"
        QualityProfile.GAME -> when (fpsCapped) {
            null -> "720p · 60 帧 · 延迟优先"
            FpsCapReason.REMEMBERED -> "720p · 30 帧（这台电视上次跟不上 60 帧，再点一次“游戏”可以重试）"
            else -> "720p · 30 帧（已自动降低）· 延迟优先"
        } + if (audioOn) "。声音可能比画面稍晚，看视频建议用“均衡”" else ""
    }

    /** G02 切换中的说明行（预告停顿，不写秒数）。 */
    fun qualitySwitching(to: QualityProfile): String = if (to.lowLatency) "正在切换到游戏模式，电视画面会停一下" else "正在切换画质，电视画面会停一下"

    /** 切换失败后回退原档位时的说明行（UX 走查 P1-3）。 */
    fun qualityRestoring(to: QualityProfile): String = "正在恢复“${to.label}”…"

    /** G02 完成时读屏播报。 */
    fun qualitySwitched(to: QualityProfile): String = if (to.lowLatency) "已切换到游戏模式" else "已切换到“${to.label}”"

    /** G03 “这台电视上实测约 85 ms（估算）”；不在游戏档时说明是游戏模式的实测值（UX 走查 P2-1）。 */
    fun measuredLatency(ms: Int, inGame: Boolean = true): String =
        (if (inGame) "这台电视上实测约 " else "这台电视上游戏模式实测约 ") + "$ms\u202Fms（估算）"

    fun measuredLatencyA11y(ms: Int, inGame: Boolean = true): String =
        (if (inGame) "这台电视上实测延迟约 " else "这台电视上游戏模式实测延迟约 ") + "$ms 毫秒，估算"

    fun autoAdjustDetail(p: QualityProfile): String = if (p.lowLatency) "游戏模式下只降码率和清晰度，不切档" else "网络变差时自动降为“流畅”"

    const val HINT_DISMISS = "知道了"

    /** 提示位卡片文案（交互稿 G04 ①–⑨）：标题、补充说明、出路按钮（可空）。 */
    data class HintCopy(val title: String, val detail: String?, val action: String?)

    fun hint(h: GameHint): HintCopy = when (h) {
        is GameHint.SwitchFailed -> HintCopy("切换没有成功，已保持“${h.kept.label}”", "电视画面已恢复", null)
        GameHint.Thermal -> HintCopy("手机温度较高，已降为 30 帧", "温度恢复后，可以再选一次“游戏”", null)
        is GameHint.FpsDropped -> if (h.receiverCause) {
            HintCopy("这台电视跟不上 60 帧，已改为 30 帧", "下次投到这台电视时直接用 30 帧", null)
        } else {
            HintCopy("手机性能不足，已改为 30 帧", "可以再选一次“游戏”重新尝试 60 帧", null)
        }
        // 会话中改档不再写入默认画质（P1-7），所以说清“下次怎么才能生效”
        GameHint.NextTime -> HintCopy("已切到 720p · 60 帧", "低延迟将在下次投屏生效：开始投屏前选“游戏”，或在设置里把默认画质设为“游戏”", null)
        GameHint.LegacyReceiver -> HintCopy(
            "对方的局域投屏版本较旧",
            "只能提供 720p · 60 帧，延迟不会明显降低。可以在“设置 → 把接收端安装到电视”给它更新",
            "去更新",
        )
        is GameHint.LatencyUnmet -> HintCopy(
            "这台电视上延迟约 ${h.latencyMs}\u202Fms，比游戏模式预期的高",
            when (h.cause) {
                LatencyCause.DECODER -> "电视解码较慢，常见于老款电视或入门盒子"
                LatencyCause.NETWORK -> "Wi‑Fi 不够稳定。建议手机和电视都连 5GHz，并靠近路由器"
                // 解码和网络都不明显偏慢：只给数字，不猜原因（PM 走查 P0-3）
                null -> null
            },
            null,
        )
        GameHint.PoorNetwork -> HintCopy("网络较差，画面可能卡顿或变模糊", null, "切到流畅")
        is GameHint.LowBattery -> HintCopy("电量 ${h.percent}%，游戏模式耗电更快", "接上充电器可以玩得更久", null)
    }
}
