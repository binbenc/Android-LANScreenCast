package com.benchan.wireless.screencasting.core.common

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 会话结束（清理）的守卫，解决两个问题（模拟器验证 E1 / E4）：
 *
 * 1. **清理不能被自己取消**：结束函数常在超时 / 重连 / 看门狗 Job 里被调用，而结束函数第一件事就是取消这些 Job；
 *    取消后遇到第一个挂起点（例如 `withContext(IO) { session.close() }`）就抛 CancellationException，后面的
 *    关闭会话、停服务、改状态全部被跳过。这里把清理放进 [NonCancellable]，一旦开始就执行完。
 * 2. **幂等**：同一代会话只结束一次；会话结束后迟到的回调（系统停止录屏、ICE 断开…）不能再覆盖结束页。
 *
 * 用法：每开始一个新会话调用 [begin] 拿到代号，回调里用 [isCurrent] 过滤，结束时 `end(generation) { … }`。
 * 只能在单线程调度器上使用（与控制器一致）。
 */
class SessionEndGuard {
    private var generation = 0L
    private var endedGeneration = -1L

    /** 开始新会话，返回其代号。 */
    fun begin(): Long {
        generation++
        return generation
    }

    val current: Long get() = generation

    /** 回调是否属于当前、且尚未结束的会话。 */
    fun isCurrent(gen: Long): Boolean = gen == generation && endedGeneration != generation

    /** 当前会话是否已经结束。 */
    val isEnded: Boolean get() = endedGeneration == generation

    /**
     * 结束代号为 [gen] 的会话：不是当前会话或已结束时返回 false 且不执行；否则在不可取消的上下文中执行 [block]。
     */
    suspend fun end(gen: Long = generation, block: suspend () -> Unit): Boolean {
        if (gen != generation || endedGeneration == generation) return false
        endedGeneration = generation
        withContext(NonCancellable) { block() }
        return true
    }
}
