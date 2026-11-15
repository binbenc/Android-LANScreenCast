package com.benchan.wireless.screencasting.feature.sender

import com.benchan.wireless.screencasting.feature.sender.push.PushServiceWatch
import com.benchan.wireless.screencasting.feature.sender.push.PushServiceWatch.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/** 第 1 轮 PM P0-1：服务只在本次会话结束时停止；“再推一个 / 从断点继续”时旧会话不会把新服务停掉。 */
class PushServiceWatchTest {
    @Test
    fun stopsOnlyForItsOwnSession() {
        // 启动瞬间控制器还没发布新会话：等待
        assertEquals(Decision.WAIT, PushServiceWatch.decide("new", 1_000, 1_100, null, false))
        // 读到的是上一次已结束的会话（再推一个）：也只是等待，不停
        assertEquals(Decision.WAIT, PushServiceWatch.decide("new", 1_000, 1_200, "old", true))
        assertEquals(Decision.KEEP, PushServiceWatch.decide("new", 1_000, 1_300, "new", false))
        assertEquals(Decision.STOP_ENDED, PushServiceWatch.decide("new", 1_000, 9_000, "new", true))
        // 5 秒仍没看到本次会话（启动失败）：停
        assertEquals(Decision.STOP_TIMEOUT, PushServiceWatch.decide("new", 1_000, 6_500, "old", true))
        assertEquals(Decision.STOP_TIMEOUT, PushServiceWatch.decide(null, 0, 6_000, null, false))
    }
}
