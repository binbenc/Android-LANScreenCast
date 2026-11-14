package com.benchan.wireless.screencasting.core.data.report

/**
 * 会话进行中累计统计（纯逻辑，可单测）：延迟直方图、码率均值、断线与自动恢复次数。
 */
class SessionStatsAccumulator {
    val histogram = IntArray(BUCKETS)
    private var bitrateSumKbps = 0L
    private var bitrateCount = 0
    var disconnects = 0
        private set
    var autoRecovered = 0
        private set
    var framesDropped = 0L
    var codec: String? = null

    @Synchronized
    fun addLatency(ms: Int) {
        if (ms < 0) return
        histogram[(ms / BUCKET_MS).coerceAtMost(BUCKETS - 1)]++
    }

    @Synchronized
    fun addBitrate(kbps: Int) {
        if (kbps <= 0) return
        bitrateSumKbps += kbps
        bitrateCount++
    }

    @Synchronized
    fun onDisconnected() {
        disconnects++
    }

    @Synchronized
    fun onRecovered() {
        autoRecovered++
    }

    fun avgBitrateKbps(): Int? = if (bitrateCount == 0) null else (bitrateSumKbps / bitrateCount).toInt()

    fun percentile(p: Double): Int? = percentileOf(histogram.toList(), p)

    companion object {
        const val BUCKET_MS = 10
        const val BUCKETS = 41   // 0..400ms，最后一个桶为 ≥400ms

        /** 由直方图估算百分位（取桶中点）。 */
        fun percentileOf(hist: List<Int>, p: Double): Int? {
            val total = hist.sum()
            if (total == 0) return null
            val target = kotlin.math.ceil(total * p).toInt().coerceAtLeast(1)
            var acc = 0
            hist.forEachIndexed { i, c ->
                acc += c
                if (acc >= target) return i * BUCKET_MS + BUCKET_MS / 2
            }
            return (hist.size - 1) * BUCKET_MS
        }
    }
}
