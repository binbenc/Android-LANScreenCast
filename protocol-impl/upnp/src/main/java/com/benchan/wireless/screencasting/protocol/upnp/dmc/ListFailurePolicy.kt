package com.benchan.wireless.screencasting.protocol.upnp.dmc

/** 列表里某一项失败后怎么办（第 1 轮走查 PM P1-5）。 */
enum class ListFailureAction {
    /** 标为“已跳过”，3 秒后推下一项（电视放不了这一项）。 */
    SKIP,

    /** 按结束条件处理：与电视的连接已断开。 */
    END_DEVICE_LOST,

    /** 停在失败卡等用户（被拒、读不到等，跳过也没用）。 */
    STAY,
}

object ListFailurePolicy {
    /** 只有“这一项放不了”才跳过；连不上不能一路跳到底，被拒 / 读不到要让用户看到。 */
    fun decide(error: PushError): ListFailureAction = when (error) {
        PushError.UNSUPPORTED_FORMAT, PushError.START_TIMEOUT, PushError.URL_FAILED, PushError.DECODE_FAILED -> ListFailureAction.SKIP
        PushError.UNREACHABLE -> ListFailureAction.END_DEVICE_LOST
        PushError.REJECTED, PushError.FILE_UNREADABLE, PushError.NEEDS_TRANSCODE, PushError.TRANSCODE_FAILED, PushError.NO_NETWORK -> ListFailureAction.STAY
    }

    /** 跳过最后一项后的结束原因：一项都没放成功时不能说“已放完”。 */
    fun endReasonAfterLast(loadedCount: Int): PushEndReason = if (loadedCount > 0) PushEndReason.LIST_FINISHED else PushEndReason.ALL_FAILED

    /** 只有明确的格式错误才记为“这台电视放不了这种格式”（20 秒超时不算，第 1 轮 PM P2-11）。 */
    fun recordsFormatFailure(error: PushError): Boolean = error == PushError.UNSUPPORTED_FORMAT
}
