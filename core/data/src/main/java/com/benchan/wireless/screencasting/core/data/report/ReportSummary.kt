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
    /** 最近被反向控制的投屏（新 → 旧，最多 5 条）：“本次被××控制 mm:ss”。 */
    val recentControl: List<ControlUsage> = emptyList(),
    /** 协议统计（交互稿 14-H：只在协议统计里加 DLNA 推送一类）。 */
    val byProtocol: Map<CastProtocol, Int> = emptyMap(),
    /** DLNA 推送（PRD 8.1.1 G）：成功率与首次播放耗时 P50。 */
    val pushTotal: Int = 0,
    val pushSucceeded: Int = 0,
    val pushFirstPlayP50Ms: Long? = null,
) {
    val pushSuccessRatePercent: Int? get() = if (pushTotal == 0) null else (pushSucceeded * 100 + pushTotal / 2) / pushTotal

    val successRatePercent: Int? get() = if (total == 0) null else (succeeded * 100 + total / 2) / total

    companion object {
        fun from(records: List<SessionRecord>, sinceMillis: Long? = null): ReportSummary {
            val list = if (sinceMillis == null) records else records.filter { it.startedAt >= sinceMillis }
            val pushes = list.filter { it.protocol == CastProtocol.DLNA_PUSH }
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
                recentControl = list.filter { (it.controlledMs ?: 0) > 0 }.sortedByDescending { it.endedAt }.take(5)
                    .map { ControlUsage(it.peerName, it.controlledMs ?: 0, it.endedAt) },
                byProtocol = list.groupingBy { it.protocol }.eachCount(),
                pushTotal = pushes.size,
                pushSucceeded = pushes.count { it.success },
                pushFirstPlayP50Ms = pushes.mapNotNull { it.firstFrameMs }.sorted().let { if (it.isEmpty()) null else it[(it.size - 1) / 2] },
            )
        }
    }
}

/** 一次被反向控制的投屏（本机报告，用户决策 D8）。 */
data class ControlUsage(val peerName: String, val controlledMs: Long, val endedAt: Long) {
    /** “mm:ss”（超过 1 小时为“h:mm:ss”）。 */
    val durationLabel: String
        get() {
            val t = controlledMs / 1_000
            return if (t >= 3_600) "%d:%02d:%02d".format(t / 3_600, t / 60 % 60, t % 60) else "%02d:%02d".format(t / 60, t % 60)
        }
}
