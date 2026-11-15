package com.benchan.wireless.screencasting.protocol.upnp.dmc

import com.benchan.wireless.screencasting.protocol.upnp.LastChangeEvent
import com.benchan.wireless.screencasting.protocol.upnp.MediaKind
import com.benchan.wireless.screencasting.protocol.upnp.MetadataLevel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PushEngineTest {
    private val token = "/0123456789abcdef0123456789abcdef/"

    private fun media(i: Int, kind: MediaKind = MediaKind.VIDEO, remote: Boolean = false) = PreparedMedia(
        url = if (remote) "http://cdn.example.com/v$i.mp4" else "http://192.168.1.2:4000${token}$i.mp4",
        mime = "video/mp4", kind = kind, title = "第 $i 项", protocolInfo = "http-get:*:video/mp4:*",
        durationMs = 60_000, remote = remote, ownershipKey = if (remote) "cdn.example.com" else token,
    )

    private class Harness(scope: TestScope, val tv: FakeRenderer = FakeRenderer(), quirks: RendererQuirks = RendererQuirks(), consentAware: Boolean = false) {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val engine = PushEngine(tv.port, quirks, hasRenderingControl = true, dispatcher = dispatcher, now = { scope.testScheduler.currentTime }, consentAware = consentAware)
        val events = mutableListOf<PushEvent>()
        init {
            scope.backgroundScope.launch(dispatcher) { engine.events.collect { events += it } }
        }
    }

    private fun TestScope.harness(tv: FakeRenderer = FakeRenderer(), quirks: RendererQuirks = RendererQuirks(), consentAware: Boolean = false) =
        Harness(this, tv, quirks, consentAware)

    private fun entries(n: Int, kind: MediaKind = MediaKind.VIDEO, remote: Boolean = false) = List(n) { PlaylistEntry(kind, remote) }

    @Test
    fun happyPathLoadsAndPlays() = runTest {
        val h = harness()
        h.engine.start(entries(1), { PrepareResult.Ready(media(0)) })
        advanceTimeBy(3_000)
        val s = h.engine.state.value
        assertEquals(PushPhase.PLAYING, s.phase)
        assertEquals(30, s.volume)
        assertTrue(s.hasVolume)
        assertEquals("http://192.168.1.2:4000${token}0.mp4", h.tv.uri)
        assertTrue(h.tv.metadata.contains("第 0 项"))
        assertEquals(0, h.tv.count("Stop")) // 电视原来没有内容，不先 Stop
        assertTrue(h.events.any { it is PushEvent.Loaded })
        assertTrue(h.engine.stats.value.firstPlayMs!! >= 0)
        h.engine.end(PushEndReason.USER_STOPPED)
        assertEquals(1, h.tv.count("Stop"))
        assertEquals(PushPhase.ENDED, h.engine.state.value.phase)
        h.engine.close()
    }

    @Test
    fun stopsFirstWhenTvIsBusyAndRetriesOn701() = runTest {
        val tv = FakeRenderer().apply { state = "PLAYING"; setFaults += 701 }
        val h = harness(tv)
        h.engine.start(entries(1), { PrepareResult.Ready(media(0)) })
        advanceTimeBy(3_000)
        assertEquals(PushPhase.PLAYING, h.engine.state.value.phase)
        assertEquals(2, tv.count("Stop")) // 先 Stop + 701 后再 Stop
        assertEquals(2, tv.count("SetAVTransportURI"))
        assertTrue("stop_before_set" in h.engine.stats.value.fallbacks)
        h.engine.close()
    }

    @Test
    fun metadataFallsBackFullMinimalNone() = runTest {
        val tv = FakeRenderer().apply { setFaults += 402; setFaults += 501 }
        val h = harness(tv)
        h.engine.start(entries(1), { PrepareResult.Ready(media(0)) })
        advanceTimeBy(3_000)
        assertEquals(PushPhase.PLAYING, h.engine.state.value.phase)
        assertEquals(MetadataLevel.NONE, h.engine.quirks.value.metadataLevel)
        assertEquals("", tv.metadata)
        assertTrue("metadata_none" in h.engine.stats.value.fallbacks)
        h.engine.close()
        // 下次直接从学到的档位开始
        val tv2 = FakeRenderer()
        val h2 = harness(tv2, RendererQuirks(metadataLevel = MetadataLevel.NONE))
        h2.engine.start(entries(1), { PrepareResult.Ready(media(0)) })
        advanceTimeBy(3_000)
        assertEquals(1, tv2.count("SetAVTransportURI"))
        h2.engine.close()
    }

    @Test
    fun playRetriesWhileTransitioning() = runTest {
        val tv = FakeRenderer().apply { playFaults += 701; playFaults += 701 }
        val h = harness(tv)
        h.engine.start(entries(1), { PrepareResult.Ready(media(0)) })
        advanceTimeBy(6_000)
        assertEquals(3, tv.count("Play"))
        assertEquals(PushPhase.PLAYING, h.engine.state.value.phase)
        h.engine.close()
    }

    @Test
    fun unsupportedFormatAndUrlFailures() = runTest {
        val tv = FakeRenderer().apply { setFaults += 714 }
        val h = harness(tv)
        h.engine.start(entries(1), { PrepareResult.Ready(media(0)) })
        advanceTimeBy(3_000)
        assertEquals(PushPhase.FAILED, h.engine.state.value.phase)
        assertEquals(PushError.UNSUPPORTED_FORMAT, h.engine.state.value.failure!!.error)
        assertEquals(714, h.engine.state.value.failure!!.upnpCode)
        h.engine.close()
        // 网址：加载后回到停止 → 网址打不开
        val tv2 = FakeRenderer().apply {
            var n = 0
            afterPlay = { it.state = "TRANSITIONING" }
            setFaults.clear()
        }
        val h2 = harness(tv2)
        h2.engine.start(entries(1, remote = true), { PrepareResult.Ready(media(0, remote = true)) })
        advanceTimeBy(1_200)
        tv2.state = "STOPPED"
        advanceTimeBy(3_000)
        assertEquals(PushError.URL_FAILED, h2.engine.state.value.failure!!.error)
        h2.engine.close()
    }

    @Test
    fun startTimeoutAfter20sAndSlowHintAt10s() = runTest {
        val tv = FakeRenderer().apply { afterPlay = { } } // 永远 STOPPED，没进入过 TRANSITIONING
        val h = harness(tv)
        h.engine.start(entries(1), { PrepareResult.Ready(media(0)) })
        advanceTimeBy(10_500)
        assertTrue(h.engine.state.value.loadingSlow)
        advanceTimeBy(11_000)
        assertEquals(PushError.START_TIMEOUT, h.engine.state.value.failure!!.error)
        assertFalse(h.engine.state.value.loadingSlow)
        h.engine.close()
    }

    @Test
    fun playlistAutoAdvancesAndFinishes() = runTest {
        val tv = FakeRenderer()
        val h = harness(tv)
        h.engine.start(entries(2), { i -> PrepareResult.Ready(media(i)) })
        advanceTimeBy(3_000)
        // 播到接近结尾后电视停止 → 自动下一项
        tv.positionMs = 58_000
        advanceTimeBy(1_500)
        tv.state = "STOPPED"
        advanceTimeBy(4_000)
        assertEquals(1, h.engine.state.value.index)
        assertTrue(h.events.any { it is PushEvent.AutoAdvanced })
        assertEquals("http://192.168.1.2:4000${token}1.mp4", tv.uri)
        tv.positionMs = 59_000
        advanceTimeBy(1_500)
        tv.state = "NO_MEDIA_PRESENT"
        advanceTimeBy(2_000)
        assertEquals(PushEndReason.LIST_FINISHED, h.engine.state.value.endReason)
        assertEquals(1, h.engine.stats.value.autoAdvanceCount)
        h.engine.close()
    }

    @Test
    fun stoppedOnTvAfter5Seconds() = runTest {
        val tv = FakeRenderer()
        val h = harness(tv)
        h.engine.start(entries(1), { PrepareResult.Ready(media(0)) })
        advanceTimeBy(3_000)
        tv.positionMs = 10_000
        advanceTimeBy(1_500)
        tv.state = "STOPPED"
        advanceTimeBy(3_000)
        assertNull(h.engine.state.value.endReason) // 短暂停止不算
        advanceTimeBy(4_000)
        assertEquals(PushEndReason.STOPPED_ON_TV, h.engine.state.value.endReason)
        h.engine.close()
    }

    @Test
    fun takenOverEndsWithoutSendingStop() = runTest {
        val tv = FakeRenderer()
        val h = harness(tv)
        h.engine.start(entries(1), { PrepareResult.Ready(media(0)) })
        advanceTimeBy(3_000)
        val stops = tv.count("Stop")
        tv.trackUriOverride = "http://192.168.1.77:8000/other.mp4"
        advanceTimeBy(2_500)
        assertEquals(PushEndReason.TAKEN_OVER, h.engine.state.value.endReason)
        assertEquals(stops, tv.count("Stop"))
        h.engine.close()
    }

    @Test
    fun deviceLostAfter30sOfFailures() = runTest {
        val tv = FakeRenderer()
        val h = harness(tv)
        h.engine.start(entries(1), { PrepareResult.Ready(media(0)) })
        advanceTimeBy(3_000)
        tv.offline = true
        advanceTimeBy(20_000)
        assertNull(h.engine.state.value.endReason)
        advanceTimeBy(12_000)
        assertEquals(PushEndReason.DEVICE_LOST, h.engine.state.value.endReason)
        h.engine.close()
    }

    @Test
    fun seekFallsBackToAbsTimeThenMarksUnsupported() = runTest {
        val tv = FakeRenderer().apply { seekFaults["REL_TIME"] = 710 }
        val h = harness(tv)
        h.engine.start(entries(1), { PrepareResult.Ready(media(0)) })
        advanceTimeBy(3_000)
        h.engine.seekTo(30_000)
        runCurrent()
        assertEquals(30_000L, tv.positionMs)
        assertEquals(SeekUnit.ABS_TIME, h.engine.quirks.value.seekUnit)
        tv.seekFaults["ABS_TIME"] = 711
        h.engine.seekTo(40_000)
        runCurrent()
        assertFalse(h.engine.state.value.canSeek)
        assertTrue(h.engine.quirks.value.seekUnsupported)
        assertTrue(h.events.any { it is PushEvent.SeekUnsupported })
        h.engine.close()
    }

    @Test
    fun optimisticPauseRevertsWhenTvIgnoresIt() = runTest {
        val tv = FakeRenderer().apply { pauseWorks = false }
        val h = harness(tv)
        h.engine.start(entries(1), { PrepareResult.Ready(media(0)) })
        advanceTimeBy(3_000)
        h.engine.pause()
        runCurrent()
        assertEquals(PushPhase.PAUSED, h.engine.state.value.phase)
        advanceTimeBy(4_000)
        assertEquals(PushPhase.PLAYING, h.engine.state.value.phase)
        assertTrue(h.events.any { it == PushEvent.NoResponse })
        h.engine.close()
    }

    @Test
    fun tvRemotePauseIsReflectedByPollingAndEvents() = runTest {
        val tv = FakeRenderer()
        val h = harness(tv)
        h.engine.start(entries(1), { PrepareResult.Ready(media(0)) })
        advanceTimeBy(3_000)
        tv.state = "PAUSED_PLAYBACK"
        advanceTimeBy(1_100)
        assertEquals(PushPhase.PAUSED, h.engine.state.value.phase)
        h.engine.onEvent(LastChangeEvent(mapOf("TransportState" to "PLAYING", "Volume" to "55")))
        runCurrent()
        assertEquals(PushPhase.PLAYING, h.engine.state.value.phase)
        assertEquals(55, h.engine.state.value.volume)
        assertEquals(SyncMode.EVENTS, h.engine.state.value.syncMode)
        h.engine.close()
    }

    @Test
    fun photoSwitchingIsDebouncedAndNoStopBeforeSet() = runTest {
        val tv = FakeRenderer()
        val h = harness(tv)
        h.engine.start(entries(5, MediaKind.IMAGE), { i -> PrepareResult.Ready(media(i, MediaKind.IMAGE)) })
        advanceTimeBy(1_000)
        assertEquals(PushPhase.SHOWING, h.engine.state.value.phase)
        h.engine.select(1); runCurrent()
        advanceTimeBy(100)
        h.engine.select(2); runCurrent()
        advanceTimeBy(100)
        h.engine.select(3); runCurrent()
        assertEquals(3, h.engine.state.value.index)
        advanceTimeBy(1_000)
        assertEquals(2, tv.count("SetAVTransportURI")) // 第 0 张 + 最后一张
        assertTrue(tv.uri.endsWith("3.mp4"))
        assertEquals(0, tv.count("Stop"))
        assertEquals(3, h.engine.stats.value.switchCount)
        h.engine.close()
    }

    @Test
    fun photoIdleWarnsThenEnds() = runTest {
        val tv = FakeRenderer()
        val h = harness(tv)
        h.engine.start(entries(2, MediaKind.IMAGE), { i -> PrepareResult.Ready(media(i, MediaKind.IMAGE)) })
        advanceTimeBy(29 * 60_000L + 20_000)
        assertTrue(h.engine.state.value.idleWarning)
        assertTrue(h.events.any { it == PushEvent.IdleWarning })
        h.engine.keepAlive(); runCurrent()
        assertFalse(h.engine.state.value.idleWarning)
        advanceTimeBy(29 * 60_000L)
        assertNull(h.engine.state.value.endReason)
        advanceTimeBy(2 * 60_000L)
        assertEquals(PushEndReason.IDLE_TIMEOUT, h.engine.state.value.endReason)
        assertTrue(tv.count("Stop") >= 1)
        h.engine.close()
    }

    @Test
    fun pausedVideoNeverIdlesOut() = runTest {
        val tv = FakeRenderer()
        val h = harness(tv)
        h.engine.start(entries(1), { PrepareResult.Ready(media(0)) })
        advanceTimeBy(3_000)
        tv.state = "PAUSED_PLAYBACK"
        h.engine.setForeground(false)
        advanceTimeBy(60 * 60_000L)
        assertNull(h.engine.state.value.endReason)
        assertEquals(PushPhase.PAUSED, h.engine.state.value.phase)
        h.engine.close()
    }

    @Test
    fun volumeIsDebounced() = runTest {
        val tv = FakeRenderer()
        val h = harness(tv)
        h.engine.start(entries(1), { PrepareResult.Ready(media(0)) })
        advanceTimeBy(3_000)
        repeat(5) { h.engine.setVolume(40 + it); runCurrent() }
        advanceTimeBy(500)
        assertEquals(1, tv.count("SetVolume"))
        assertEquals(44, tv.volume)
        h.engine.adjustVolume(-5); runCurrent()
        advanceTimeBy(500)
        assertEquals(39, tv.volume)
        h.engine.close()
    }

    @Test
    fun deferredPrepareAndReload() = runTest {
        val tv = FakeRenderer()
        val h = harness(tv)
        var ready = false
        h.engine.start(entries(1), { if (ready) PrepareResult.Ready(media(0)) else PrepareResult.Deferred(PushError.NEEDS_TRANSCODE) })
        advanceTimeBy(500)
        assertEquals(PushError.NEEDS_TRANSCODE, h.engine.state.value.failure!!.error)
        assertEquals(0, tv.count("SetAVTransportURI"))
        ready = true
        h.engine.reload()
        advanceTimeBy(3_000)
        assertEquals(PushPhase.PLAYING, h.engine.state.value.phase)
        h.engine.close()
    }

    @Test
    fun monitorDecisions() {
        val t = PushTimings()
        val m = PlaybackMonitor(MediaKind.VIDEO, token, remote = false, timings = t)
        assertEquals(MonitorDecision.CONTINUE, m.onSample(0, TransportStateValue.PLAYING, "http://192.168.1.2:4000${token}0.mp4", 1_000, 60_000, 1_000))
        // 网址被 TV 改写成另一个本机地址但令牌相同 → 仍是我们的
        assertTrue(m.isOurs("http://192.168.1.3:4000${token}1.mp4"))
        // 网址推送比较完整地址（主机大小写、末尾斜杠、百分号编码不影响）
        assertTrue(PlaybackMonitor.isOurs("HTTP://CDN.example.com/a%20b.mp4/", "http://cdn.example.com/a b.mp4", remote = true))
        assertFalse(PlaybackMonitor.isOurs("http://cdn.example.com/other.mp4", "http://cdn.example.com/a.mp4", remote = true))
        val img = PlaybackMonitor(MediaKind.IMAGE, token, remote = false, timings = t)
        assertEquals(MonitorDecision.CONTINUE, img.onSample(0, TransportStateValue.STOPPED, null, null, null, 1_000))
        assertEquals(MonitorDecision.CONTINUE, img.onSample(60_000, TransportStateValue.STOPPED, null, null, null, 1_000))
        // 后台轮询间隔 5 秒时放宽“接近结尾”的判断
        val bg = PlaybackMonitor(MediaKind.AUDIO, token, remote = false, timings = t)
        bg.onSample(0, TransportStateValue.PLAYING, null, 51_000, 60_000, 5_000)
        assertEquals(MonitorDecision.FINISHED, bg.onSample(5_000, TransportStateValue.STOPPED, null, 0, 60_000, 5_000))
    }

    @Test
    fun quirksRoundTrip() {
        val q = RendererQuirks(MetadataLevel.MINIMAL, stopBeforeSet = false, seekUnit = SeekUnit.ABS_TIME, seekUnsupported = true)
        assertEquals(q, RendererQuirks.fromMap(q.toMap()))
        assertEquals(RendererQuirks(), RendererQuirks.fromMap(mapOf("metadata" to "BOGUS")))
        assertEquals(TransportStateValue.PAUSED, TransportStateValue.of("PAUSED"))
        assertEquals(TransportStateValue.UNKNOWN, TransportStateValue.of("CUSTOM"))
    }

    // ---------------------------------------------------------------- 第 1 轮修复

    /** EMU-1：推到本应用接收端，对方 28 秒后才点“允许”——等待期间不计入 20 秒，之后正常播放。 */
    @Test
    fun consentWaitDoesNotCountTowardsTheBudget() = runTest {
        val tv = FakeRenderer().apply { setDelayMs = 28_000 }
        val h = harness(tv, consentAware = true)
        h.engine.start(entries(1), { PrepareResult.Ready(media(0)) })
        advanceTimeBy(5_000)
        assertTrue(h.engine.state.value.awaitingConsent)
        assertFalse(h.engine.state.value.loadingSlow) // 等对方确认时不出“电视上点允许”的 10 秒提示（已经一开始就提示）
        advanceTimeBy(26_000)
        assertEquals(PushPhase.PLAYING, h.engine.state.value.phase)
        assertFalse(h.engine.state.value.awaitingConsent)
        h.engine.close()
    }

    /** EMU-1：普通电视 SetAVTransportURI 卡住——20 秒总预算到点就给结论，并尽力发 Stop。 */
    @Test
    fun hungRendererFailsAt20sAndSendsStop() = runTest {
        val tv = FakeRenderer().apply { setDelayMs = 60_000 }
        val h = harness(tv)
        h.engine.start(entries(1), { PrepareResult.Ready(media(0)) })
        advanceTimeBy(19_000)
        assertEquals(PushPhase.LOADING, h.engine.state.value.phase)
        advanceTimeBy(2_000)
        assertEquals(PushPhase.FAILED, h.engine.state.value.phase)
        assertEquals(PushError.START_TIMEOUT, h.engine.state.value.failure!!.error)
        advanceTimeBy(100)
        assertTrue(tv.count("Stop") >= 1)
        h.engine.close()
    }

    /** PM P1-1：超时按最后一次已知错误给原因（设置地址后一直失联 → 连不上）。 */
    @Test
    fun timeoutUsesLastKnownError() = runTest {
        val tv = FakeRenderer().apply { afterPlay = { } }
        val h = harness(tv)
        h.engine.start(entries(1), { PrepareResult.Ready(media(0)) })
        advanceTimeBy(1_000)
        tv.transportInfoFails = true
        advanceTimeBy(21_000)
        assertEquals(PushError.UNREACHABLE, h.engine.state.value.failure!!.error)
        h.engine.close()
    }

    /** PM P0-2：等用户选择 / 正在转换不计空闲；放着不管 10 分钟才回收，原因不是“照片空闲”。 */
    @Test
    fun awaitingChoiceAndTranscodingAreNotPhotoIdle() = runTest {
        val tv = FakeRenderer()
        val h = harness(tv)
        h.engine.start(entries(1), { PrepareResult.Deferred(PushError.NEEDS_TRANSCODE) })
        advanceTimeBy(1_000)
        assertEquals(PushPhase.AWAITING_CHOICE, h.engine.state.value.phase)
        h.engine.setBusy(true); runCurrent()
        advanceTimeBy(45 * 60_000L)
        assertNull(h.engine.state.value.endReason)
        assertFalse(h.engine.state.value.idleWarning)
        h.engine.setBusy(false); runCurrent()
        advanceTimeBy(9 * 60_000L)
        assertNull(h.engine.state.value.endReason)
        advanceTimeBy(2 * 60_000L)
        assertEquals(PushEndReason.INACTIVE, h.engine.state.value.endReason)
        h.engine.close()
    }

    /** PM P2-17：文件服务报告读不到 → 当前项转为失败卡。 */
    @Test
    fun reportedReadErrorFailsCurrentItem() = runTest {
        val h = harness()
        h.engine.start(entries(1), { PrepareResult.Ready(media(0)) })
        advanceTimeBy(3_000)
        h.engine.reportError(0, PushError.FILE_UNREADABLE); runCurrent()
        assertEquals(PushPhase.FAILED, h.engine.state.value.phase)
        assertEquals(PushError.FILE_UNREADABLE, h.engine.state.value.failure!!.error)
        h.engine.close()
    }

    /** PM P2-18：网址推送按 GetMediaInfo.CurrentURI 判断接管；CDN 跳转后的 TrackURI 不算被接管。 */
    @Test
    fun remotePushUsesCurrentUriForTakeover() = runTest {
        val tv = FakeRenderer()
        val h = harness(tv)
        val m = media(0, remote = true).copy(ownershipKey = "http://cdn.example.com/v0.mp4")
        h.engine.start(entries(1, remote = true), { PrepareResult.Ready(m) })
        advanceTimeBy(3_000)
        tv.trackUriOverride = "http://edge-42.cdn2.example.net/abc/v0.mp4?sig=1"
        advanceTimeBy(5_000)
        assertNull(h.engine.state.value.endReason)
        tv.mediaInfoUri = "http://192.168.1.77/other.mp4"
        advanceTimeBy(3_000)
        assertEquals(PushEndReason.TAKEN_OVER, h.engine.state.value.endReason)
        h.engine.close()
    }

    /** PM P2-18：后台看到停止后加快复查，10 秒内下结论。 */
    @Test
    fun backgroundStopIsDetectedWithin10s() = runTest {
        val tv = FakeRenderer()
        val h = harness(tv)
        h.engine.start(entries(1), { PrepareResult.Ready(media(0)) })
        advanceTimeBy(3_000)
        h.engine.setForeground(false); runCurrent()
        tv.positionMs = 10_000
        advanceTimeBy(6_000)
        tv.state = "STOPPED"
        advanceTimeBy(10_000)
        assertEquals(PushEndReason.STOPPED_ON_TV, h.engine.state.value.endReason)
        h.engine.close()
    }

    @Test
    fun listFailurePolicy() {
        assertEquals(ListFailureAction.SKIP, ListFailurePolicy.decide(PushError.UNSUPPORTED_FORMAT))
        assertEquals(ListFailureAction.SKIP, ListFailurePolicy.decide(PushError.START_TIMEOUT))
        assertEquals(ListFailureAction.END_DEVICE_LOST, ListFailurePolicy.decide(PushError.UNREACHABLE))
        assertEquals(ListFailureAction.STAY, ListFailurePolicy.decide(PushError.REJECTED))
        assertEquals(ListFailureAction.STAY, ListFailurePolicy.decide(PushError.FILE_UNREADABLE))
        assertEquals(PushEndReason.ALL_FAILED, ListFailurePolicy.endReasonAfterLast(0))
        assertEquals(PushEndReason.LIST_FINISHED, ListFailurePolicy.endReasonAfterLast(2))
        assertTrue(ListFailurePolicy.recordsFormatFailure(PushError.UNSUPPORTED_FORMAT))
        assertFalse(ListFailurePolicy.recordsFormatFailure(PushError.START_TIMEOUT))
    }
}
