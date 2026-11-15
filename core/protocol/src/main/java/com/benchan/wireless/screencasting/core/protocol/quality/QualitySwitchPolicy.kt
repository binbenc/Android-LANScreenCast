package com.benchan.wireless.screencasting.core.protocol.quality

/** 会话中改档的执行方式（F18，PRD 8.1.3 D）。 */
enum class QualityChange {
    /** 不需要重建：DataChannel SET_QUALITY 即时改分辨率 / 帧率 / 码率。 */
    SET_QUALITY,

    /** 需要重建但接收端版本旧（没有 `game` 能力）：只改分辨率和帧率，切进游戏档时提示“对方版本较旧”。 */
    SET_QUALITY_LEGACY,

    /** 需要重建但媒体没有在正常投屏（断线重连中）：编码参数先生效，媒体恢复后补做重建。 */
    DEFER_REBUILD,

    /** 重建媒体连接（保留采集与轨道、不重新授权），5 秒超时回退。 */
    REBUILD,
}

/** 切换超时后的处理。 */
enum class SwitchTimeoutAction {
    /** 回到切换前的档位（再重建一次），提示“切换没有成功，已保持‘xx’”。 */
    ROLLBACK,

    /** 回退也没成功：如实进入“重连中”，按当前档位重建，沿用 30 秒重连窗口。 */
    RECONNECT,
}

/** F18 会话中切换的纯决策（SenderController 执行，单测覆盖）。 */
object QualitySwitchPolicy {
    /** 档位切换（媒体重建）超时：5 秒内新连接没连通就回退（PRD 8.1.3 D）。 */
    const val TIMEOUT_MS = 5_000L

    fun plan(from: QualityProfile, to: QualityProfile, receiverSupportsRebuild: Boolean, streaming: Boolean): QualityChange = when {
        !QualityProfile.needsMediaRebuild(from, to) -> QualityChange.SET_QUALITY
        !receiverSupportsRebuild -> QualityChange.SET_QUALITY_LEGACY
        !streaming -> QualityChange.DEFER_REBUILD
        else -> QualityChange.REBUILD
    }

    fun onTimeout(wasRollback: Boolean): SwitchTimeoutAction = if (wasRollback) SwitchTimeoutAction.RECONNECT else SwitchTimeoutAction.ROLLBACK
}
