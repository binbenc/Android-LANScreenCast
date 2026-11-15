package com.benchan.wireless.screencasting.feature.sender.push

/**
 * 前台服务什么时候该停（第 1 轮 PM P0-1）：只在“本次”会话结束时停；
 * 启动后还没看到本次会话（start 是异步的）或看到的是上一次已结束的会话时，最多等 [graceMs]。
 */
internal object PushServiceWatch {
    enum class Decision { KEEP, STOP_ENDED, WAIT, STOP_TIMEOUT }

    fun decide(watchId: String?, watchSince: Long, now: Long, sessionId: String?, sessionEnded: Boolean, graceMs: Long = 5_000): Decision = when {
        watchId != null && sessionId == watchId -> if (sessionEnded) Decision.STOP_ENDED else Decision.KEEP
        now - watchSince < graceMs -> Decision.WAIT
        else -> Decision.STOP_TIMEOUT
    }
}
