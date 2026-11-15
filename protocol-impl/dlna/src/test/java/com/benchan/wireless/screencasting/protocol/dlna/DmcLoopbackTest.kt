package com.benchan.wireless.screencasting.protocol.dlna

import com.benchan.wireless.screencasting.core.net.LanHttpServer
import com.benchan.wireless.screencasting.protocol.dlna.dmc.FileShareSource
import com.benchan.wireless.screencasting.protocol.dlna.dmc.MediaShareServer
import com.benchan.wireless.screencasting.protocol.dlna.dmc.ShareEntry
import com.benchan.wireless.screencasting.protocol.upnp.DlnaParams
import com.benchan.wireless.screencasting.protocol.upnp.LastChangeParser
import com.benchan.wireless.screencasting.protocol.upnp.MediaKind
import com.benchan.wireless.screencasting.protocol.upnp.MediaMetadata
import com.benchan.wireless.screencasting.protocol.upnp.RendererDescriber
import com.benchan.wireless.screencasting.protocol.upnp.SinkMatcher
import com.benchan.wireless.screencasting.protocol.upnp.SinkSupport
import com.benchan.wireless.screencasting.protocol.upnp.SoapOutcome
import com.benchan.wireless.screencasting.protocol.upnp.dmc.HttpRendererPort
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PlaylistEntry
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PrepareResult
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PreparedMedia
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushEndReason
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushEngine
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushPhase
import com.benchan.wireless.screencasting.protocol.upnp.dmc.RendererQuirks
import com.benchan.wireless.screencasting.protocol.upnp.http.FetchResult
import com.benchan.wireless.screencasting.protocol.upnp.http.UpnpHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * DMC ↔ 我们自己的 DMR 在同一个 JVM 里端到端（技术方案 3.13.6）：描述拉取 → GetProtocolInfo → 推送 → 控制 → GENA → 结束；
 * 媒体文件经 [MediaShareServer] 提供。不需要设备、模拟器或网络。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DmcLoopbackTest {
    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timeout" }
            Thread.sleep(20)
        }
    }

    @Test
    fun pushToOwnRendererEndToEnd() = runBlocking {
        val player = FakePlayer()
        val gena = GenaManager(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val dmr = LanHttpServer("dmr-test") {
            dlnaModule({ DlnaDescriptions.device("客厅电视", "u-tv", null, null, null) }, DmrActions(player) { null }, gena) { svc ->
                GenaManager.lastChange(svc, player.s.value)
            }
        }
        val dmrPort = dmr.start()
        val file = File.createTempFile("wsc-dmc", ".mp4").apply { writeBytes(ByteArray(100_000) { (it % 251).toByte() }) }
        val share = MediaShareServer(allowedRemote = "127.0.0.1")
        share.start()
        val engineDispatcher = Dispatchers.Default.limitedParallelism(1)
        try {
            val described = RendererDescriber().describe("http://127.0.0.1:$dmrPort/dlna/description.xml", "127.0.0.1")
            assertNotNull(described)
            val desc = described!!.description
            assertEquals("u-tv", desc.udn)
            assertEquals("客厅电视", desc.displayName)
            val sink = RendererDescriber().sink(desc)!!
            assertTrue(sink.any { it.contentFormat == "video/mp4" })
            // 我们的 DMR 声明了 http-get:*:*:*，所以对任何格式都是“未知”（不提议转换，先推原文件）
            assertEquals(SinkSupport.UNKNOWN, SinkMatcher.match(sink, "video/x-matroska"))

            val entry = ShareEntry(0, "mp4", "video/mp4", MediaKind.VIDEO, FileShareSource(file))
            share.register(entry)
            val url = share.url("127.0.0.1", entry)
            val engine = PushEngine(HttpRendererPort(UpnpHttpClient(), desc), RendererQuirks(), true, engineDispatcher, { System.nanoTime() / 1_000_000 })
            engine.start(listOf(PlaylistEntry(MediaKind.VIDEO, false)), {
                PrepareResult.Ready(
                    PreparedMedia(url, "video/mp4", MediaKind.VIDEO, "第 1 集", DlnaParams.protocolInfo("video/mp4", DlnaParams.forLocal(MediaKind.VIDEO, true)),
                        sizeBytes = file.length(), durationMs = 120_000, ownershipKey = share.ownershipKey),
                )
            })
            waitUntil { engine.state.value.phase == PushPhase.PLAYING }
            assertEquals(url, player.s.value.uri)
            val meta = MediaMetadata.parse(player.s.value.metadataXml)!!
            assertEquals("第 1 集", meta.title)
            assertEquals("video/mp4", meta.mimeType)
            waitUntil { engine.state.value.volume != null }
            assertEquals(50, engine.state.value.volume)

            // 控制
            engine.pause()
            waitUntil { player.s.value.state == TransportState.PAUSED_PLAYBACK }
            engine.seekTo(65_000)
            waitUntil { player.pos == 65_000L }
            engine.setVolume(20)
            waitUntil { player.s.value.volume == 20 }

            // GENA：订阅 DMR 的 AVTransport 事件，回调到文件服务的 NOTIFY 路由
            val notified = CopyOnWriteArrayList<String>()
            share.onNotify = { _, body -> LastChangeParser.parse(body)?.transportState?.let(notified::add) }
            val sub = UpnpHttpClient().subscribe(desc.avTransport.eventSubUrl!!, share.genaCallbackUrl("127.0.0.1"), 300)
            assertNotNull(sub)
            waitUntil { notified.isNotEmpty() }
            assertEquals("PAUSED_PLAYBACK", notified.first())
            UpnpHttpClient().unsubscribe(desc.avTransport.eventSubUrl!!, sub!!.sid)

            // 电视来拉流：Range
            val head = raw(share.port, "GET ${url.substringAfter(":${share.port}")} HTTP/1.1\r\nHost: x\r\nRange: bytes=10-19\r\ngetcontentFeatures.dlna.org: 1\r\n\r\n", bodyBytes = 10)
            assertTrue(head, head.startsWith("HTTP/1.1 206"))
            assertTrue(head.contains("Content-Range: bytes 10-19/100000", ignoreCase = true))

            engine.end(PushEndReason.USER_STOPPED)
            assertEquals(TransportState.STOPPED, player.s.value.state)
            engine.close()
        } finally {
            share.stop()
            dmr.stop()
            file.delete()
        }
    }

    @Test
    fun mediaServerRangeHeadTokenAndIpRules() = runBlocking {
        val file = File.createTempFile("wsc-share", ".jpg").apply { writeBytes(ByteArray(1_000) { it.toByte() }) }
        val share = MediaShareServer(allowedRemote = "127.0.0.1")
        val port = share.start()
        try {
            val entry = ShareEntry(3, "jpg", "image/jpeg", MediaKind.IMAGE, FileShareSource(file), converted = true)
            share.register(entry)
            val path = "/${share.token}/3.jpg"
            assertTrue(share.url("192.168.1.5", entry).matches(Regex("http://192\\.168\\.1\\.5:\\d+/[0-9a-f]{32}/3\\.jpg")))

            val full = raw(port, "GET $path HTTP/1.1\r\nHost: x\r\ntransferMode.dlna.org: Interactive\r\n\r\n", bodyBytes = 1_000)
            assertTrue(full, full.startsWith("HTTP/1.1 200"))
            assertTrue(full.contains("Content-Length: 1000", ignoreCase = true))
            assertTrue(full.contains("Content-Type: image/jpeg", ignoreCase = true))
            assertTrue(full.contains("Accept-Ranges: bytes", ignoreCase = true))
            assertTrue(full.contains("contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=00D0", ignoreCase = true))
            assertTrue(full.contains("transferMode.dlna.org: Interactive", ignoreCase = true))
            assertTrue(!full.contains("chunked", ignoreCase = true))

            val head = raw(port, "HEAD $path HTTP/1.1\r\nHost: x\r\n\r\n", bodyBytes = 0)
            assertTrue(head, head.startsWith("HTTP/1.1 200"))
            assertTrue(head, head.contains("Content-Length: 1000", ignoreCase = true))

            val suffix = raw(port, "GET $path HTTP/1.1\r\nHost: x\r\nRange: bytes=-100\r\n\r\n", bodyBytes = 100)
            assertTrue(suffix.startsWith("HTTP/1.1 206"))
            assertTrue(suffix.contains("Content-Range: bytes 900-999/1000", ignoreCase = true))

            val bad = raw(port, "GET $path HTTP/1.1\r\nHost: x\r\nRange: bytes=5000-\r\n\r\n", bodyBytes = 0)
            assertTrue(bad, bad.startsWith("HTTP/1.1 416"))
            assertTrue(bad.contains("Content-Range: bytes */1000", ignoreCase = true))

            // 错误令牌、不存在的序号、根路径、POST → 404 / 405
            assertTrue(raw(port, "GET /${"0".repeat(32)}/3.jpg HTTP/1.1\r\nHost: x\r\n\r\n", 0).startsWith("HTTP/1.1 404"))
            assertTrue(raw(port, "GET /${share.token}/9.jpg HTTP/1.1\r\nHost: x\r\n\r\n", 0).startsWith("HTTP/1.1 404"))
            assertTrue(raw(port, "GET / HTTP/1.1\r\nHost: x\r\n\r\n", 0).startsWith("HTTP/1.1 404"))

            // 不是目标电视的 IP → 404
            share.allowedRemote = "192.168.1.50"
            assertTrue(raw(port, "GET $path HTTP/1.1\r\nHost: x\r\n\r\n", 0).startsWith("HTTP/1.1 404"))
            share.allowedRemote = "127.0.0.1"
            assertTrue(share.rangeRequests >= 1)
        } finally {
            share.stop()
            file.delete()
        }
        // 推送结束后旧地址失效（端口已关闭）
        assertTrue(runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", share.port.coerceAtLeast(1)), 300) } }.isFailure || share.port == -1)
    }

    @Test
    fun clientRefusesPublicAndHostnameTargets() = runBlocking {
        val c = UpnpHttpClient()
        assertEquals(SoapOutcome.Failure.Kind.BLOCKED, (c.soap("http://8.8.8.8/ctl", "urn:x", "Play", emptyList(), 1_000) as SoapOutcome.Failure).kind)
        assertEquals(SoapOutcome.Failure.Kind.BLOCKED, (c.soap("http://example.com/ctl", "urn:x", "Play", emptyList(), 1_000) as SoapOutcome.Failure).kind)
        assertTrue(c.fetchText("http://203.0.113.1/d.xml", 1_000) is FetchResult.Failed)
        assertEquals(null, c.subscribe("http://192.168.1.2/evt", "http://1.2.3.4/cb", 300))
        assertEquals(null, RendererDescriber(c).describe("http://192.168.1.9/d.xml", "192.168.1.20"))
    }

    /** 原始 socket 请求：读完响应头，再读 [bodyBytes] 字节。 */
    private fun raw(port: Int, request: String, bodyBytes: Int): String = Socket().use { s ->
        s.connect(InetSocketAddress("127.0.0.1", port), 2000)
        s.soTimeout = 3000
        s.getOutputStream().write(request.toByteArray())
        s.getOutputStream().flush()
        val input = s.getInputStream()
        val head = StringBuilder()
        var last4 = ""
        while (true) {
            val b = input.read()
            if (b < 0) break
            head.append(b.toChar())
            last4 = (last4 + b.toChar()).takeLast(4)
            if (last4 == "\r\n\r\n") break
        }
        var read = 0
        val buf = ByteArray(4096)
        while (read < bodyBytes) {
            val n = input.read(buf, 0, minOf(buf.size, bodyBytes - read))
            if (n < 0) break
            read += n
        }
        head.toString()
    }
}
