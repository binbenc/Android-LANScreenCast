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
import com.benchan.wireless.screencasting.feature.sender.ui.UiText
import com.benchan.wireless.screencasting.feature.sender.ui.uiPlural
import com.benchan.wireless.screencasting.feature.sender.ui.uiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
        // 系统按钮名按系统版本与语言区分（模拟系统文字），作为参数放进资源
        assertEquals(uiText(R.string.elder_capture_tap, "立即开始"), ElderCopy.capturePrompt(33, zh = true).second)
        assertEquals(uiText(R.string.elder_capture_tap, "开始"), ElderCopy.capturePrompt(34, zh = true).second)
        assertEquals(uiText(R.string.elder_capture_tap, "Start now"), ElderCopy.capturePrompt(33, zh = false).second)
        assertEquals(uiText(R.string.elder_capture_tap, "Start"), ElderCopy.capturePrompt(34, zh = false).second)
        assertEquals(uiText(R.string.elder_fail_capture_body, "开始"), ElderCopy.failure(ElderOutcome.CAPTURE_DENIED, "x", 34, true).second)
        assertEquals(uiText(R.string.elder_fail_capture_body, "立即开始"), ElderCopy.failure(ElderOutcome.CAPTURE_DENIED, "x", 30, true).second)
        assertNull(ElderCopy.captureScopeHint(33))
        assertEquals(uiText(R.string.elder_capture_scope, "整个屏幕"), ElderCopy.captureScopeHint(34))
        assertEquals(uiText(R.string.elder_capture_scope, "Entire screen"), ElderCopy.captureScopeHint(34, zh = false))
        // 只能放照片的电视没找到：用不提“局域投屏”的检查项
        assertEquals(uiText(R.string.elder_fail_push_not_found_body), ElderCopy.failure(ElderOutcome.PUSH_NOT_FOUND, "客厅").second)
        assertEquals(uiText(R.string.elder_fail_not_found_title, "客厅"), ElderCopy.failure(ElderOutcome.PUSH_NOT_FOUND, "客厅").first)
    }

    @Test
    fun copyPicksResources() {
        assertEquals(uiText(R.string.elder_quality_poor), ElderCopy.quality(LatencyGrade.FAIR))
        assertEquals(uiText(R.string.elder_quality_poor), ElderCopy.quality(LatencyGrade.POOR))
        assertEquals(uiText(R.string.elder_quality_good), ElderCopy.quality(LatencyGrade.EXCELLENT))
        assertEquals(uiText(R.string.elder_quality_good), ElderCopy.quality(null))
        assertEquals(uiText(R.string.elder_status_found, "客厅"), ElderCopy.statusFound("客厅", pinned = true))
        assertEquals(uiText(R.string.elder_status_found_last, "客厅"), ElderCopy.statusFound("客厅", pinned = false))
        assertEquals(uiText(R.string.elder_main_spoken, "客厅", uiText(R.string.elder_spoken_missing)), ElderCopy.mainButtonSpoken("客厅", false))
        // 时长：不到 1 分钟 / N 分钟（复数）/ H 小时 M 分钟
        assertEquals(uiText(R.string.elder_elapsed_just), ElderCopy.elapsed(59_000))
        assertEquals(uiPlural(R.plurals.elder_elapsed_minutes, 5), ElderCopy.elapsed(5 * 60_000L + 10))
        assertEquals(uiText(R.string.elder_elapsed_hours, 1, 5), ElderCopy.elapsed(65 * 60_000L))
        // 序号从 1 开始
        assertEquals(uiText(R.string.elder_photo_counter, 3, 10), ElderCopy.photoCounter(2, 10))
        assertEquals(uiText(R.string.elder_item_counter, 1, 2), ElderCopy.itemCounter(0, 2))
        // 转换耗时
        assertNull(ElderCopy.duration(null))
        assertEquals(uiText(R.string.elder_duration_under), ElderCopy.duration(30_000))
        assertEquals(uiPlural(R.plurals.elder_duration_about, 6), ElderCopy.duration(6 * 60_000L))
        assertEquals(uiText(R.string.elder_transcode_offer, uiText(R.string.elder_duration_unknown)), ElderCopy.transcodeOffer(null))
        assertEquals(uiText(R.string.elder_transcode_running, 40), ElderCopy.transcodeRunning(40, ElderCopy.eta(null)))
        assertEquals(uiText(R.string.elder_transcode_running_eta, 40, uiPlural(R.plurals.elder_eta, 2)), ElderCopy.transcodeRunning(40, ElderCopy.eta(120_000)))
        // 每个结果都有自己的问题描述
        assertEquals(ElderOutcome.entries.size, ElderOutcome.entries.map { ElderCopy.problem(it) }.toSet().size)
    }

    /** 长辈文案（英文默认 + 中文）不出现技术词，两种语言的键一致，绝不出现 AirPlay。 */
    @Test
    fun resourcesAvoidTechWordsInBothLanguages() {
        val en = strings("values")
        val zh = strings("values-zh")
        assertEquals(en.keys, zh.keys)
        val elderFacing = { k: String -> k.startsWith("elder_fail_") || k.startsWith("elder_quality_") || k == "elder_pin_body" }
        listOf(en, zh).forEach { m ->
            m.forEach { (k, v) ->
                assertFalse("$k contains AirPlay", v.contains("AirPlay", ignoreCase = true))
                if (elderFacing(k)) {
                    listOf("DLNA", "mDNS", "IP", "PIN").forEach { w -> assertFalse("$k: $v contains $w", Regex("\\b$w\\b").containsMatchIn(v)) }
                    listOf("码率", "延迟", "latency", "bitrate").forEach { w -> assertFalse("$k: $v contains $w", v.contains(w, ignoreCase = true)) }
                }
            }
        }
    }

    private fun strings(dir: String): Map<String, String> {
        val f = listOf("src/main/res/$dir/strings_elder.xml", "feature/sender/src/main/res/$dir/strings_elder.xml").map(::File).first { it.exists() }
        val text = f.readText()
        return Regex("""<(string|plurals) name="([^"]+)">(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL).findAll(text).associate { it.groupValues[2] to it.groupValues[3] }
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

    // —— 请家人帮忙：不含 Wi‑Fi 名称 / IP ——

    @Test
    fun helpTextHasNoPrivateInfo() {
        val f = ElderHelpText.Facts(
            uiText(R.string.elder_fail_not_found_title, "客厅电视"), uiText(R.string.elder_fail_not_found_body), "Redmi Note 12", "14", "客厅电视",
            uiText(R.string.elder_kind_own), ElderCopy.problem(ElderOutcome.NOT_FOUND), hasLan = true, multicastReply = false, tvReachable = false, time = "11-15 19:35",
        )
        val lines = ElderHelpText.summary(f)
        assertTrue(uiText(R.string.elder_help_network, uiText(R.string.elder_help_connected), uiText(R.string.elder_help_no_reply)) in lines)
        assertTrue(uiText(R.string.elder_help_tv, uiText(R.string.elder_help_undiscovered)) in lines)
        assertTrue(uiText(R.string.elder_help_tv_kind, "客厅电视", uiText(R.string.elder_kind_own)) in lines)
        assertTrue(uiText(R.string.elder_help_problem, uiText(R.string.elder_problem_not_found)) in lines)
        // 参数里只有机型、系统版本、叫法、时间：没有 IP / Wi‑Fi 名称
        fun flat(t: Any): List<String> = when (t) {
            is UiText.Res -> t.args.flatMap(::flat)
            is UiText.Plural -> t.args.flatMap(::flat)
            is UiText.Raw -> listOf(t.value)
            else -> listOf(t.toString())
        }
        val args = lines.flatMap(::flat)
        assertFalse(args.any { Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}""").containsMatchIn(it) })
        assertEquals(setOf("Redmi Note 12", "14", "客厅电视", "11-15 19:35"), args.toSet())
        // 没检测时说“未检测”
        val unchecked = ElderHelpText.summary(f.copy(hasLan = false, multicastReply = null, tvReachable = null, tvKind = null))
        assertTrue(uiText(R.string.elder_help_network, uiText(R.string.elder_help_disconnected), uiText(R.string.elder_help_unchecked)) in unchecked)
        assertTrue(uiText(R.string.elder_help_tv, "客厅电视") in unchecked)
    }

    // —— 固定电视的名字跟随最新设备名（电视改名后不再显示旧快照）——

    private val pinnedSnapshot = ElderTarget(wscId = "w1", udn = "u1", name = "sdk_gphone64_arm64", label = "")

    @Test
    fun blankLabelFollowsDiscoveredName() {
        val renamed = DiscoveredDevice("w1", "客厅电视", "h", 1, sources = setOf(DiscoverySource.MDNS), lastSeenAt = 1)
        val r = ElderTargets.resolve(listOf(pinnedSnapshot), listOf(RecentDevice("w1", "旧名", "h", 1, "ff", 1)), emptyList(), listOf(renamed))
        assertEquals("客厅电视", r.single().label)
        assertEquals("客厅电视", r.single().name)
    }

    @Test
    fun blankLabelFallsBackToRecentThenDlnaThenSnapshot() {
        val recentRenamed = listOf(RecentDevice("w1", "客厅电视", "h", 1, "ff", 1))
        val dlnaRenamed = listOf(DlnaRendererRecord("u1", "客厅 DLNA"))
        assertEquals("客厅电视", ElderTargets.resolve(listOf(pinnedSnapshot), recentRenamed, dlnaRenamed).single().label)
        assertEquals("客厅 DLNA", ElderTargets.resolve(listOf(pinnedSnapshot), emptyList(), dlnaRenamed).single().label)
        assertEquals("sdk_gphone64_arm64", ElderTargets.resolve(listOf(pinnedSnapshot), emptyList(), emptyList()).single().label)
    }

    @Test
    fun customLabelAlwaysWins() {
        val renamed = DiscoveredDevice("w1", "客厅电视", "h", 1, sources = setOf(DiscoverySource.MDNS), lastSeenAt = 1)
        val r = ElderTargets.resolve(listOf(pinnedSnapshot.copy(label = "奶奶的电视")), emptyList(), emptyList(), listOf(renamed))
        assertEquals("奶奶的电视", r.single().label)
        assertEquals("客厅电视", r.single().name)
    }

    @Test
    fun snapshotRefreshedOnlyWhenDiscoveredNameChanges() {
        val renamed = DiscoveredDevice("w1", "客厅电视", "h", 1, sources = setOf(DiscoverySource.MDNS), lastSeenAt = 1)
        val other = ElderTarget(wscId = "w2", name = "卧室", label = "卧室")
        val updated = ElderTargets.refreshSnapshots(listOf(pinnedSnapshot, other), listOf(renamed))!!
        assertEquals(listOf("客厅电视", "卧室"), updated.map { it.name })
        assertEquals(listOf("", "卧室"), updated.map { it.label })
        // 名字已是最新 / 没发现：不写存储
        assertNull(ElderTargets.refreshSnapshots(updated, listOf(renamed)))
        assertNull(ElderTargets.refreshSnapshots(listOf(pinnedSnapshot), emptyList()))
    }

    @Test
    fun wizardOverwritesSnapshotWithLatestName() {
        val renamed = DiscoveredDevice("w1", "客厅电视", "h", 1, sources = setOf(DiscoverySource.MDNS), lastSeenAt = 1)
        assertEquals("客厅电视", ElderTargets.withLatestNames(listOf(pinnedSnapshot), emptyList(), listOf(renamed)).single().name)
        val cand = ElderTarget(wscId = "w1", name = "候选新名")
        val viaCandidate = ElderTargets.withLatestNames(listOf(pinnedSnapshot.copy(label = "叫法")), listOf(cand), emptyList()).single()
        assertEquals("候选新名", viaCandidate.name)
        assertEquals("叫法", viaCandidate.label)
        // 向导候选也显示发现结果里的新名字
        val now = 1_000L
        val c = com.benchan.wireless.screencasting.feature.sender.elder.ElderViewModel.mergeCandidates(
            listOf(RecentDevice("w1", "sdk_gphone64_arm64", "h", 1, "ff", now)),
            listOf(com.benchan.wireless.screencasting.core.security.trust.TrustedDevice("w1", "sdk_gphone64_arm64", "ff", 0, now)),
            emptyList(), listOf(renamed),
        )
        assertEquals("客厅电视", c.single().target.name)
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
