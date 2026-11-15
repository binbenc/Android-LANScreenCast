package com.benchan.wireless.screencasting.feature.sender.ui

import androidx.annotation.StringRes
import com.benchan.wireless.screencasting.core.protocol.quality.FpsCapReason
import com.benchan.wireless.screencasting.core.protocol.quality.GameHint
import com.benchan.wireless.screencasting.core.protocol.quality.LatencyCause
import com.benchan.wireless.screencasting.core.protocol.quality.QualityProfile
import com.benchan.wireless.screencasting.feature.sender.R
import com.benchan.wireless.screencasting.feature.sender.session.ConnectFailure
import com.benchan.wireless.screencasting.feature.sender.session.EndReason

/** Snackbar 上的“下一步”按钮。 */
internal enum class EndedAction(@StringRes val label: Int) {
    DIAGNOSTICS(R.string.sender_action_diagnostics),
    RETYPE_CAST_CODE(R.string.sender_action_retype),
    RESCAN(R.string.sender_action_rescan),
}

/**
 * 发送端结束提示的文案选择（纯函数，便于单测；只返回资源引用 + 参数，由界面按系统语言解析）。
 * 原则：说清发生了什么 + 下一步怎么做；协议错误码只写日志，不出现在界面上（PM/UX 审查 B1、C6）。
 */
internal object SenderCopy {

    /** 设备名为空时的称呼（“电视” / “the TV”）。 */
    fun tvName(name: String?): UiText = name?.let { UiText.Raw(it) } ?: uiText(R.string.sender_tv_fallback)

    /** 结束原因 → Snackbar 文案；返回 null 表示用整页（[EndedScreen]）或不提示。 */
    fun endedSnackbar(reason: EndReason, name: String?, failure: ConnectFailure? = null): UiText? {
        val n = tvName(name)
        return when (reason) {
            EndReason.REJECTED -> uiText(R.string.sender_ended_rejected, n)
            EndReason.CAST_CODE_WRONG -> uiText(R.string.sender_ended_cast_code_wrong)
            EndReason.QR_EXPIRED -> uiText(R.string.sender_ended_qr_expired)
            EndReason.AUTH_FAILED -> uiText(R.string.sender_ended_auth_failed)
            EndReason.CONFIRM_TIMEOUT -> uiText(R.string.sender_ended_confirm_timeout, n)
            EndReason.PIN_TIMEOUT -> uiText(R.string.sender_ended_pin_timeout)
            EndReason.RECEIVER_NO_DISPLAY -> uiText(R.string.sender_ended_receiver_no_display, n)
            EndReason.PAIR_LOCKED -> uiText(R.string.sender_ended_pair_locked)
            EndReason.CONNECT_FAILED -> connectFailed(n, failure)
            EndReason.CERTIFICATE_MISMATCH -> uiText(R.string.sender_ended_certificate_mismatch)
            EndReason.BUSY -> uiText(R.string.sender_ended_busy, n)
            EndReason.REMOTE_CLOSED -> uiText(R.string.sender_ended_remote_closed, n)
            EndReason.INTERNAL_ERROR -> uiText(R.string.sender_ended_internal_error)
            else -> null
        }
    }

    private fun connectFailed(n: UiText, failure: ConnectFailure?): UiText = when (failure) {
        ConnectFailure.HANDSHAKE_TIMEOUT -> uiText(R.string.sender_fail_handshake_timeout, n)
        ConnectFailure.QR_PAIR_TIMEOUT -> uiText(R.string.sender_fail_qr_pair_timeout)
        ConnectFailure.CODE_PAIR_TIMEOUT -> uiText(R.string.sender_fail_code_pair_timeout)
        ConnectFailure.FIRST_FRAME_TIMEOUT -> uiText(R.string.sender_fail_first_frame_timeout)
        ConnectFailure.MEDIA_BLOCKED -> uiText(R.string.sender_fail_media_blocked)
        ConnectFailure.LOST -> uiText(R.string.sender_fail_lost, n)
        ConnectFailure.UNREACHABLE, null -> uiText(R.string.sender_fail_unreachable, n)
    }

    /** Snackbar 的下一步按钮。 */
    fun endedAction(reason: EndReason): EndedAction? = when (reason) {
        EndReason.CONNECT_FAILED -> EndedAction.DIAGNOSTICS
        EndReason.CAST_CODE_WRONG -> EndedAction.RETYPE_CAST_CODE
        EndReason.QR_EXPIRED -> EndedAction.RESCAN
        else -> null
    }

    /** 超过约 20 字的提示用长时长显示，来得及读完（S1）。传入解析后的文字。 */
    fun isLongText(text: String): Boolean = text.length > 20

    /** 05B 投屏码页的网络提示；null 表示当前网络可以使用投屏码。 */
    fun castCodeNetworkHint(hasLan: Boolean, prefixSupported: Boolean): UiText? = when {
        !hasLan -> uiText(R.string.sender_cast_code_no_wifi)
        !prefixSupported -> uiText(R.string.sender_cast_code_unsupported_network)
        else -> null
    }

    // —— F18 游戏模式（PRD 8.1.3；交互稿 15 G01–G04）。界面不出现任何目标延迟数字（产品拍板），只显示“约 xx ms”估算 ——

    /**
     * 分段按钮下的档位说明（层级 ② 常驻）。音频没有和画面分开（声音关闭、旧版接收端中途切游戏）时不提“声音可能比画面稍晚”；
     * 降为 30 帧时如实写出原因（记住的 30 帧要说明“上次跟不上”，并告诉用户可以重试，PM 走查 P2-7 / UX 走查 P2-18）。
     */
    fun qualityDescription(p: QualityProfile, audioOn: Boolean = true, fpsCapped: FpsCapReason? = null): UiText = when (p) {
        QualityProfile.SMOOTH -> uiText(R.string.sender_quality_desc_smooth, p.summaryText)
        QualityProfile.BALANCED -> uiText(R.string.sender_quality_desc_balanced, p.summaryText)
        QualityProfile.HD -> uiText(R.string.sender_quality_desc_hd, p.summaryText)
        QualityProfile.GAME -> {
            val main = when (fpsCapped) {
                null -> uiText(R.string.sender_quality_desc_game)
                FpsCapReason.REMEMBERED -> uiText(R.string.sender_quality_desc_game_remembered)
                else -> uiText(R.string.sender_quality_desc_game_capped)
            }
            if (audioOn) uiText(R.string.sender_quality_desc_game_audio_late, main) else main
        }
    }

    /** G02 切换中的说明行（预告停顿，不写秒数）。 */
    fun qualitySwitching(to: QualityProfile): UiText =
        uiText(if (to.lowLatency) R.string.sender_quality_switching_game else R.string.sender_quality_switching)

    /** 切换失败后回退原档位时的说明行（UX 走查 P1-3）。 */
    fun qualityRestoring(to: QualityProfile): UiText = uiText(R.string.sender_quality_restoring, to.labelText)

    /** G02 完成时读屏播报。 */
    fun qualitySwitched(to: QualityProfile): UiText =
        if (to.lowLatency) uiText(R.string.sender_quality_switched_game) else uiText(R.string.sender_quality_switched, to.labelText)

    /** G03 “这台电视上实测约 85 ms（估算）”；不在游戏档时说明是游戏模式的实测值（UX 走查 P2-1）。 */
    fun measuredLatency(ms: Int, inGame: Boolean = true): UiText =
        uiText(if (inGame) R.string.sender_measured_latency else R.string.sender_measured_latency_game, ms)

    fun measuredLatencyA11y(ms: Int, inGame: Boolean = true): UiText =
        uiText(if (inGame) R.string.sender_measured_latency_a11y else R.string.sender_measured_latency_game_a11y, ms)

    fun autoAdjustDetail(p: QualityProfile): UiText =
        uiText(if (p.lowLatency) R.string.sender_auto_adjust_game else R.string.sender_auto_adjust_detail)

    /** 提示位卡片文案（交互稿 G04 ①–⑨）：标题、补充说明、出路按钮（可空）。 */
    data class HintCopy(val title: UiText, val detail: UiText?, @StringRes val action: Int?)

    fun hint(h: GameHint): HintCopy = when (h) {
        is GameHint.SwitchFailed -> HintCopy(uiText(R.string.sender_hint_switch_failed, h.kept.labelText), uiText(R.string.sender_hint_switch_failed_detail), null)
        GameHint.Thermal -> HintCopy(uiText(R.string.sender_hint_thermal), uiText(R.string.sender_hint_thermal_detail), null)
        is GameHint.FpsDropped -> if (h.receiverCause) {
            HintCopy(uiText(R.string.sender_hint_fps_receiver), uiText(R.string.sender_hint_fps_receiver_detail), null)
        } else {
            HintCopy(uiText(R.string.sender_hint_fps_phone), uiText(R.string.sender_hint_fps_phone_detail), null)
        }
        // 会话中改档不再写入默认画质（P1-7），所以说清“下次怎么才能生效”
        GameHint.NextTime -> HintCopy(uiText(R.string.sender_hint_next_time), uiText(R.string.sender_hint_next_time_detail), null)
        GameHint.LegacyReceiver -> HintCopy(
            uiText(R.string.sender_hint_legacy_receiver),
            uiText(R.string.sender_hint_legacy_receiver_detail),
            R.string.sender_hint_legacy_receiver_action,
        )
        is GameHint.LatencyUnmet -> HintCopy(
            uiText(R.string.sender_hint_latency_unmet, h.latencyMs),
            when (h.cause) {
                LatencyCause.DECODER -> uiText(R.string.sender_hint_latency_decoder)
                LatencyCause.NETWORK -> uiText(R.string.sender_hint_latency_network)
                // 解码和网络都不明显偏慢：只给数字，不猜原因（PM 走查 P0-3）
                null -> null
            },
            null,
        )
        GameHint.PoorNetwork -> HintCopy(uiText(R.string.sender_hint_poor_network), null, R.string.sender_hint_poor_network_action)
        is GameHint.LowBattery -> HintCopy(uiText(R.string.sender_hint_low_battery, h.percent), uiText(R.string.sender_hint_low_battery_detail), null)
    }
}
