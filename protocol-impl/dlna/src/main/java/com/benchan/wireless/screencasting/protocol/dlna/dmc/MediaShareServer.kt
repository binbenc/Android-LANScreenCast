package com.benchan.wireless.screencasting.protocol.dlna.dmc

import com.benchan.wireless.screencasting.core.common.Hex
import com.benchan.wireless.screencasting.core.common.Ids
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.common.net.LanAddress
import com.benchan.wireless.screencasting.core.net.LanHttpServer
import com.benchan.wireless.screencasting.protocol.upnp.DlnaParams
import com.benchan.wireless.screencasting.protocol.upnp.MediaKind
import com.benchan.wireless.screencasting.protocol.upnp.RangeRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 文件服务的一个内容来源（content:// 或本地缓存文件）。 */
interface ShareSource {
    /** 字节数；未知时为 null（此时不支持 Range）。 */
    val sizeBytes: Long?
    val seekable: Boolean

    /** 打开并定位到 [offset]。调用方负责关闭。 */
    fun open(offset: Long): InputStream
}

class FileShareSource(private val file: File) : ShareSource {
    override val sizeBytes: Long get() = file.length()
    override val seekable: Boolean = true
    override fun open(offset: Long): InputStream = FileInputStream(file).also { s -> if (offset > 0) s.channel.position(offset) }
}

/** 本次会话里选中的一项（序号 → 来源）。 */
data class ShareEntry(val index: Int, val ext: String, val mime: String, val kind: MediaKind, val source: ShareSource, val converted: Boolean = false) {
    val name: String get() = "$index.$ext"
    val seekable: Boolean get() = source.seekable && source.sizeBytes != null
}

/**
 * DLNA 推送的本地文件服务（PRD 8.1.1 E、技术方案 3.13.4）。
 * - 地址：`http://手机IP:随机端口/<令牌>/<序号>.<扩展名>`；令牌 128 bit，每次会话一个；路径只含 ASCII，不含文件名。
 * - 只响应目标电视 IP（[allowedRemote]）且必须是局域网地址；其他一律 404（不暴露是否存在）。
 * - 只读 GET / HEAD，支持单段 Range（206 / 416），始终给 Content-Length（不分块），回 DLNA 头。
 * - 并发连接上限 [maxConnections]（电视会同时开多个连接读文件头尾）。
 * - 同一个服务也承载 GENA 回调：`NOTIFY /<令牌>/gena`。
 */
class MediaShareServer(
    @Volatile var allowedRemote: String,
    private val maxConnections: Int = 8,
    private val monotonic: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    val token: String = Hex.encode(Ids.randomBytes(16))
    private val entries = ConcurrentHashMap<String, ShareEntry>()
    private val active = AtomicInteger(0)
    private val requestCount = AtomicInteger(0)
    private val rangeCount = AtomicInteger(0)
    private val lastRequest = AtomicLong(0)
    private var server: LanHttpServer? = null

    /** 电视取走了某一项（照片“已发送”的依据，交互稿 P09）。参数为序号。 */
    @Volatile
    var onServed: ((Int) -> Unit)? = null

    /** 某一项读不到（选择器授权中途失效 / 文件被删）。参数为序号。 */
    @Volatile
    var onReadError: ((Int) -> Unit)? = null

    /** GENA NOTIFY 回调：(SID, 请求体)。 */
    @Volatile
    var onNotify: ((String?, String) -> Unit)? = null

    val rangeRequests: Int get() = rangeCount.get()
    val requests: Int get() = requestCount.get()
    val activeConnections: Int get() = active.get()

    /** 最近一次媒体请求的单调时间（0 = 还没有）：前台服务据此决定是否持有唤醒锁。 */
    val lastRequestAt: Long get() = lastRequest.get()

    val port: Int get() = server?.port ?: -1

    /** 会话令牌路径前缀（判断“电视播放的还是不是我们的”）。 */
    val ownershipKey: String get() = "/$token/"

    fun register(entry: ShareEntry) {
        entries[entry.name] = entry
    }

    fun unregister(index: Int) {
        entries.keys.filter { it.substringBefore('.') == index.toString() }.forEach { entries.remove(it) }
    }

    fun url(hostIp: String, entry: ShareEntry): String = "http://$hostIp:$port/$token/${entry.name}"

    fun genaCallbackUrl(hostIp: String): String = "http://$hostIp:$port/$token/gena"

    suspend fun start(): Int {
        server?.let { return it.port }
        val s = LanHttpServer("dmc", preferredPort = 0) {
            routing {
                route("/{token}/gena", HttpMethod("NOTIFY")) {
                    handle {
                        if (!authorized(call)) return@handle call.respond(HttpStatusCode.NotFound)
                        val body = runCatching { call.receiveText() }.getOrDefault("")
                        if (body.length <= 64 * 1024) onNotify?.invoke(call.request.headers["SID"], body)
                        call.respond(HttpStatusCode.OK)
                    }
                }
                route("/{token}/{name}") {
                    get { serve(call, head = false) }
                    head { serve(call, head = true) }
                }
            }
        }
        val port = s.start()
        server = s
        return port
    }

    fun stop() {
        server?.stop()
        server = null
        entries.clear()
    }

    private fun authorized(call: ApplicationCall): Boolean {
        val t = call.parameters["token"].orEmpty()
        if (!MessageDigest.isEqual(t.toByteArray(), token.toByteArray())) return false
        val remote = call.request.origin.remoteAddress
        val addr = LanAddress.parseIpv4(remote) ?: runCatching { InetAddress.getByName(remote) }.getOrNull() ?: return false
        if (!LanAddress.isLan(addr)) return false
        return addr.hostAddress == allowedRemote || remote == allowedRemote
    }

    private suspend fun serve(call: ApplicationCall, head: Boolean) {
        if (!authorized(call)) {
            WscLog.w(TAG, "rejected media request")
            return call.respond(HttpStatusCode.NotFound)
        }
        val entry = entries[call.parameters["name"].orEmpty()] ?: return call.respond(HttpStatusCode.NotFound)
        if (active.get() >= maxConnections) return call.respond(HttpStatusCode.ServiceUnavailable)
        requestCount.incrementAndGet()
        lastRequest.set(monotonic())
        if (!head) onServed?.invoke(entry.index)
        val size = entry.source.sizeBytes
        val range = if (size != null && entry.seekable) RangeRequest.parse(call.request.headers["Range"], size) else RangeRequest.Full
        val fourth = DlnaParams.forLocal(entry.kind, entry.seekable, entry.converted)
        call.response.header("Accept-Ranges", if (entry.seekable) "bytes" else "none")
        call.response.header("contentFeatures.dlna.org", fourth)
        call.response.header("transferMode.dlna.org", call.request.headers["transferMode.dlna.org"]?.takeIf { it.isNotBlank() } ?: DlnaParams.transferMode(entry.kind))
        val type = ContentType.parse(entry.mime)
        when (range) {
            RangeRequest.Unsatisfiable -> {
                call.response.header("Content-Range", "bytes */${size ?: 0}")
                call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
            }
            RangeRequest.Full -> respondBody(call, entry, type, 0, size, HttpStatusCode.OK, head)
            is RangeRequest.Partial -> {
                rangeCount.incrementAndGet()
                call.response.header("Content-Range", range.range.contentRange(size!!))
                respondBody(call, entry, type, range.range.start, range.range.length, HttpStatusCode.PartialContent, head)
            }
        }
    }

    private suspend fun respondBody(call: ApplicationCall, entry: ShareEntry, type: ContentType, start: Long, length: Long?, status: HttpStatusCode, head: Boolean) {
        if (head) {
            call.respond(object : OutgoingContent.NoContent() {
                override val contentLength: Long? = length
                override val contentType: ContentType = type
                override val status: HttpStatusCode = status
            })
            return
        }
        // 先打开再回响应头：读不到时能如实告诉控制器（第 1 轮 PM P2-17），而不是静默断开
        val opened = try {
            withContext(Dispatchers.IO) { entry.source.open(start) }
        } catch (e: Exception) {
            WscLog.w(TAG, "source unreadable: ${e.javaClass.simpleName}")
            onReadError?.invoke(entry.index)
            return call.respond(HttpStatusCode.NotFound)
        }
        call.respond(object : OutgoingContent.WriteChannelContent() {
            override val contentLength: Long? = length
            override val contentType: ContentType = type
            override val status: HttpStatusCode = status
            override suspend fun writeTo(channel: ByteWriteChannel) {
                active.incrementAndGet()
                try {
                    withContext(Dispatchers.IO) {
                        opened.use { input ->
                            val buf = ByteArray(CHUNK)
                            var remaining = length ?: Long.MAX_VALUE
                            while (remaining > 0) {
                                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                                if (n < 0) break
                                channel.writeFully(buf, 0, n)
                                remaining -= n
                                lastRequest.set(monotonic())
                            }
                            channel.flush()
                        }
                    }
                } finally {
                    active.decrementAndGet()
                }
            }
        })
    }

    private companion object {
        const val TAG = "MediaShare"
        const val CHUNK = 64 * 1024
    }
}
