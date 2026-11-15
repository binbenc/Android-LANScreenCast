package com.benchan.wireless.screencasting.feature.sender

import com.benchan.wireless.screencasting.core.data.devices.RecentDevice
import com.benchan.wireless.screencasting.core.data.dlna.DlnaRendererRecord
import com.benchan.wireless.screencasting.core.data.settings.ElderTarget
import com.benchan.wireless.screencasting.core.discovery.DiscoveredDevice
import com.benchan.wireless.screencasting.core.discovery.DiscoverySource
import com.benchan.wireless.screencasting.core.protocol.quality.LatencyGrade
import com.benchan.wireless.screencasting.feature.sender.elder.ElderCopy
import com.benchan.wireless.screencasting.feature.sender.elder.ElderHelpText
import com.benchan.wireless.screencasting.feature.sender.elder.ElderOutcome
import com.benchan.wireless.screencasting.feature.sender.elder.ElderOutcomes
import com.benchan.wireless.screencasting.feature.sender.elder.ElderRecommend
import com.benchan.wireless.screencasting.feature.sender.elder.ElderTargets
import com.benchan.wireless.screencasting.feature.sender.elder.QuickConnect
import com.benchan.wireless.screencasting.feature.sender.session.ConnectFailure
import com.benchan.wireless.screencasting.feature.sender.session.EndReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElderLogicTest {
    private fun recent(id: String, host: String, at: Long) = RecentDevice(id, "TV-$id", host, 40100, "ff", at)
    private fun found(id: String, host: String) = DiscoveredDevice(id, "TV-$id", host, 40100, sources = setOf(DiscoverySource.MDNS), lastSeenAt = 1)

    // —— 一键连的目标（决策 #41）——

    @Test
    fun pinnedTargetsWinAndStayStable() {
        val pinned = listOf(ElderTarget(wscId = "a", name = "小米电视", label = "客厅电视"), ElderTarget(udn = "u1", name = "卧室"))
        val r = ElderTargets.resolve(pinned, listOf(recent("z", "1.1.1.1", 999)), emptyList())
        assertEquals(listOf("客厅电视", "卧室"), r.map { it.label })
        assertTrue(r.all { it.pinned })
    }

    @Test
    fun unpinnedFallsBackToLastSuccessful() {
        val mirrorNewer = ElderTargets.resolve(emptyList(), listOf(recent("a", "h", 200), recent("b", "h", 100)), listOf(DlnaRendererRecord("u", "DLNA 电视", lastSuccessAt = 150)))
        assertEquals("a", mirrorNewer.single().wscId)
        assertFalse(mirrorNewer.single().pinned)
        val pushNewer = ElderTargets.resolve(emptyList(), listOf(recent("a", "h", 100)), listOf(DlnaRendererRecord("u", "DLNA 电视", lastSuccessAt = 300)))
        assertEquals("u", pushNewer.single().udn)
        assertTrue(ElderTargets.resolve(emptyList(), emptyList(), listOf(DlnaRendererRecord("u", "x", lastUsedAt = 5))).isEmpty())
    }

    @Test
    fun blankLabelUsesFullName() {
        val r = ElderTargets.resolve(listOf(ElderTarget(wscId = "a", name = "sdk_gphone64_arm64", label = "")), emptyList(), emptyList())
        assertEquals("sdk_gphone64_arm64", r.single().label)
    }

    @Test
    fun labelClampedToEightCharacters() {
        assertEquals("一二三四五六七八", ElderTarget.clampLabel("一二三四五六七八九十"))
        assertEquals(8, ElderTarget.labelLength(ElderTarget.clampLabel("😀😀😀😀😀😀😀😀😀")))
        val json = ElderTarget.encode(listOf(ElderTarget(wscId = "a", name = "n", label = "客厅")))
        assertEquals("客厅", ElderTarget.decode(json).single().label)
        assertTrue(ElderTarget.decode("garbage").isEmpty())
    }

    // —— 一键连优先用新地址 ——

    @Test
    fun quickConnectPrefersFreshAddress() {
        val r = recent("a", "192.168.1.5", 1)
        val fresh = QuickConnect.address("a", r, listOf(found("a", "192.168.1.9")))!!
        assertEquals("192.168.1.9", fresh.host)
        assertTrue(fresh.fresh)
        val old = QuickConnect.address("a", r, listOf(found("b", "192.168.1.7")))!!
        assertEquals("192.168.1.5", old.host)
        assertFalse(old.fresh)
        assertNull(QuickConnect.address("a", null, emptyList()))
    }

    // —— 失败原因（PRD 8.1.4 F）——

    @Test
    fun outcomes() {
        assertEquals(ElderOutcome.NO_WIFI, ElderOutcomes.of(EndReason.CONNECT_FAILED, ConnectFailure.UNREACHABLE, hasLan = false))
        assertEquals(ElderOutcome.NOT_FOUND, ElderOutcomes.of(EndReason.CONNECT_FAILED, ConnectFailure.UNREACHABLE, hasLan = true))
        assertEquals(ElderOutcome.TV_REJECTED, ElderOutcomes.of(EndReason.REJECTED, null, true))
        assertEquals(ElderOutcome.CAPTURE_DENIED, ElderOutcomes.of(EndReason.CAPTURE_DENIED, null, true))
        assertEquals(ElderOutcome.DISCONNECTED, ElderOutcomes.of(EndReason.RECONNECT_FAILED, null, true))
        assertEquals(ElderOutcome.LOCKED, ElderOutcomes.of(EndReason.SCREEN_LOCKED, null, true))
        assertEquals(ElderOutcome.STOPPED, ElderOutcomes.of(EndReason.USER_STOPPED, null, true))
        // 说真实原因（UX P1-7）：没人点允许 ≠ 点了拒绝；正被别人用 ≠ 没找到；E05 期间断开 ≠ 没找到
        assertEquals(ElderOutcome.TV_NO_ANSWER, ElderOutcomes.of(EndReason.CONFIRM_TIMEOUT, null, true))
        assertEquals(ElderOutcome.TV_BUSY, ElderOutcomes.of(EndReason.BUSY, null, true))
        assertEquals(ElderOutcome.PIN_LOCKED, ElderOutcomes.of(EndReason.PAIR_LOCKED, null, true))
        assertEquals(ElderOutcome.DISCONNECTED, ElderOutcomes.of(EndReason.CONNECT_FAILED, ConnectFailure.LOST, true))
    }

    @Test
    fun captureTextFollowsSdkAndLanguage() {
        assertTrue(ElderCopy.capturePrompt(33, zh = true).second.contains("立即开始"))
        assertTrue(ElderCopy.capturePrompt(34, zh = true).second.contains("「开始」"))
        assertTrue(ElderCopy.capturePrompt(33, zh = false).second.contains("Start now"))
        assertTrue(ElderCopy.failure(ElderOutcome.CAPTURE_DENIED, "x", 34, true).second.contains("「开始」"))
        assertTrue(ElderCopy.failure(ElderOutcome.CAPTURE_DENIED, "x", 30, true).second.contains("立即开始"))
        assertEquals(null, ElderCopy.captureScopeHint(33))
        // 只能放照片的电视没找到：不提“局域投屏”
        assertFalse(ElderCopy.failure(ElderOutcome.PUSH_NOT_FOUND, "客厅").second.contains("局域投屏"))
    }

    @Test
    fun wizardMergesSameReceiver() {
        val now = 1_000_000L
        val rs = listOf(RecentDevice("w1", "客厅", "h", 1, "ff", now), RecentDevice("w0", "客厅", "h", 1, "ee", now - 500))
        val tr = listOf(
            com.benchan.wireless.screencasting.core.security.trust.TrustedDevice("w1", "客厅", "ff", 0, now),
            com.benchan.wireless.screencasting.core.security.trust.TrustedDevice("w0", "客厅", "ee", 0, now - 500),
        )
        val recs = listOf(
            DlnaRendererRecord("u1", "客厅", confirmedAt = 1, lastUsedAt = now - 10, wscId = "w1"),
            DlnaRendererRecord("u2", "客厅", confirmedAt = 1, lastUsedAt = now - 20), // 旧记录没有 wscId：按同名合并
            DlnaRendererRecord("u3", "卧室 DLNA", confirmedAt = 1, lastUsedAt = now - 30),
        )
        val c = com.benchan.wireless.screencasting.feature.sender.elder.ElderViewModel.mergeCandidates(rs, tr, recs, emptyList())
        // EMU-F1：同一台接收端只出现一次，并且同时能投屏、放照片
        assertEquals(listOf("客厅", "卧室 DLNA"), c.map { it.target.name })
        assertEquals("w1", c[0].target.wscId)
        assertTrue(c[0].canMirror && c[0].canPush)
        assertFalse(c[1].canMirror)
    }

    @Test
    fun copyAvoidsTechWords() {
        val all = ElderOutcome.entries.flatMap { ElderCopy.failure(it, "客厅电视").toList() } +
            listOf(ElderCopy.quality(LatencyGrade.POOR), ElderCopy.quality(LatencyGrade.EXCELLENT), ElderCopy.PIN_BODY)
        all.forEach { text -> listOf("DLNA", "mDNS", "IP", "码率", "延迟", "PIN").forEach { w -> assertFalse("$text contains $w", text.contains(w)) } }
        assertEquals("网络不太好，可能会卡", ElderCopy.quality(LatencyGrade.FAIR))
        assertTrue(ElderCopy.captureScopeHint(34)!!.contains("整个屏幕"))
        assertTrue(ElderCopy.capturePrompt(33).second.contains("立即开始"))
    }

    // —— 请家人帮忙：不含 Wi‑Fi 名称 / IP ——

    @Test
    fun helpTextHasNoPrivateInfo() {
        val f = ElderHelpText.Facts(
            "没找到 客厅电视", "请看看", "Redmi Note 12", "14", "客厅电视", "本应用接收端",
            ElderCopy.problem(ElderOutcome.NOT_FOUND), hasLan = true, multicastReply = false, tvReachable = false, time = "11-15 19:35",
        )
        val text = ElderHelpText.build(f)
        assertTrue(text.contains("组播：无回应"))
        assertTrue(text.contains("电视：未发现"))
        assertFalse(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}""").containsMatchIn(text))
        assertFalse(text.contains("SSID") || text.contains("Wi‑Fi 名"))
    }

    @Test
    fun recommendCardRules() {
        assertTrue(ElderRecommend.shouldShow(1.3f, false, hasSuccessfulCast = true, dismissed = false, elderOn = false))
        assertTrue(ElderRecommend.shouldShow(1.0f, true, true, false, false))
        assertFalse(ElderRecommend.shouldShow(1.15f, false, true, false, false))
        assertFalse(ElderRecommend.shouldShow(1.5f, false, hasSuccessfulCast = false, dismissed = false, elderOn = false))
        assertFalse(ElderRecommend.shouldShow(1.5f, false, true, dismissed = true, elderOn = false))
        assertFalse(ElderRecommend.shouldShow(1.5f, false, true, false, elderOn = true))
    }
}
