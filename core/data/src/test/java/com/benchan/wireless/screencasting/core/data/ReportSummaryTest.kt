package com.benchan.wireless.screencasting.core.data

import com.benchan.wireless.screencasting.core.data.report.CastProtocol
import com.benchan.wireless.screencasting.core.data.report.ReportSummary
import com.benchan.wireless.screencasting.core.data.report.SessionRecord
import com.benchan.wireless.screencasting.core.data.report.SessionStatsAccumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReportSummaryTest {

    private fun record(name: String, success: Boolean, latencies: List<Int>, start: Long = 1000, disconnects: Int = 0): SessionRecord {
        val acc = SessionStatsAccumulator()
        latencies.forEach(acc::addLatency)
        return SessionRecord(
            sessionId = name + start, protocol = CastProtocol.WSC, role = "SENDER", peerName = name,
            startedAt = start, endedAt = start + 1, success = success,
            latencyP50Ms = acc.percentile(0.5), latencyP95Ms = acc.percentile(0.95),
            latencyHistogram = acc.histogram.toList(), disconnects = disconnects,
        )
    }

    @Test
    fun percentilesFromHistogram() {
        val acc = SessionStatsAccumulator()
        (1..100).forEach { acc.addLatency(it) }
        assertEquals(55, acc.percentile(0.5))
        assertEquals(95, acc.percentile(0.95))
        acc.addLatency(5000)
        assertEquals(405, SessionStatsAccumulator.percentileOf(acc.histogram.toList(), 1.0))
        assertNull(SessionStatsAccumulator().percentile(0.5))
    }

    @Test
    fun summaryAggregatesAndFiltersByTime() {
        val rs = listOf(
            record("客厅电视", true, List(10) { 64 }, start = 2000, disconnects = 1),
            record("客厅电视", true, List(10) { 74 }, start = 3000),
            record("书房盒子", false, emptyList(), start = 4000),
            record("旧设备", true, List(10) { 300 }, start = 10),
        )
        val s = ReportSummary.from(rs, sinceMillis = 1000)
        assertEquals(3, s.total)
        assertEquals(2, s.succeeded)
        assertEquals(67, s.successRatePercent)
        assertEquals(1, s.disconnects)
        assertEquals("客厅电视", s.topDevices.first().name)
        assertEquals(2, s.topDevices.first().count)
        assertEquals(70, s.avgLatencyMs)
        assertEquals(75, s.p95LatencyMs)
        assertNull(ReportSummary.from(emptyList()).successRatePercent)
    }

    @Test
    fun gameLatencySeparatedFromOtherProfiles() {
        val acc = SessionStatsAccumulator()
        List(20) { 82 }.forEach(acc::addLatency)
        val game = com.benchan.wireless.screencasting.core.data.report.GameReport(
            durationMs = 3 * 3_600_000L, enteredAtStart = false, switches = 2, latencyHistogram = acc.histogram.toList(),
        )
        val rs = listOf(
            record("客厅电视", true, List(10) { 140 }).copy(game = game),
            record("客厅电视", true, List(10) { 150 }, start = 2000),
        )
        val s = ReportSummary.from(rs)
        assertEquals(85, s.gameLatencyP50Ms)
        assertEquals(3 * 3_600_000L, s.gameDurationMs)
        assertNull(ReportSummary.from(listOf(record("x", true, listOf(50)))).gameLatencyP50Ms)
        // 总体延迟扣除游戏模式期间的样本（同一会话的总直方图包含游戏样本）
        val all = SessionStatsAccumulator()
        (List(10) { 150 } + List(20) { 82 }).forEach(all::addLatency)
        val mixed = record("x", true, emptyList()).copy(latencyHistogram = all.histogram.toList(), game = game)
        assertEquals(155, ReportSummary.from(listOf(mixed)).avgLatencyMs)
    }
}
