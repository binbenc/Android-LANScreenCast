package com.benchan.wireless.screencasting.core.data.report

/** 常用设备统计行（交互稿 10）。 */
data class DeviceUsage(val name: String, val count: Int, val avgLatencyMs: Int?)

/** 本机投屏报告（PRD 8.6 / 14、交互稿 10）。 */
data class ReportSummary(
    val total: Int,
    val succeeded: Int,
    val disconnects: Int,
    val autoRecovered: Int,
    val avgLatencyMs: Int?,
    val p95LatencyMs: Int?,
    val histogram: List<Int>,
    val topDevices: List<DeviceUsage>,
) {
    val successRatePercent: Int? get() = if (total == 0) null else (succeeded * 100 + total / 2) / total

    companion object {
        fun from(records: List<SessionRecord>, sinceMillis: Long? = null): ReportSummary {
            val list = if (sinceMillis == null) records else records.filter { it.startedAt >= sinceMillis }
            val merged = IntArray(SessionStatsAccumulator.BUCKETS)
            list.forEach { r -> r.latencyHistogram.forEachIndexed { i, c -> if (i < merged.size) merged[i] += c } }
            val hist = merged.toList()
            val samples = hist.sum()
            val avg = if (samples == 0) null else
                (hist.mapIndexed { i, c -> (i * SessionStatsAccumulator.BUCKET_MS + SessionStatsAccumulator.BUCKET_MS / 2).toLong() * c }.sum() / samples).toInt()
            val devices = list.groupBy { it.peerName }.map { (name, rs) ->
                val lat = rs.mapNotNull { it.latencyP50Ms }
                DeviceUsage(name, rs.size, if (lat.isEmpty()) null else lat.average().toInt())
            }.sortedByDescending { it.count }.take(5)
            return ReportSummary(
                total = list.size,
                succeeded = list.count { it.success },
                disconnects = list.sumOf { it.disconnects },
                autoRecovered = list.sumOf { it.autoRecovered },
                avgLatencyMs = avg,
                p95LatencyMs = SessionStatsAccumulator.percentileOf(hist, 0.95),
                histogram = hist,
                topDevices = devices,
            )
        }
    }
}
