package com.benchan.wireless.screencasting.core.common

/** 可注入的时钟，便于单测控制超时、限流、退避。 */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}

/** 单调时钟（不受系统改时影响），用于测量耗时。 */
fun interface MonotonicClock {
    fun elapsedMillis(): Long

    companion object {
        val System: MonotonicClock = MonotonicClock { java.lang.System.nanoTime() / 1_000_000 }
    }
}
