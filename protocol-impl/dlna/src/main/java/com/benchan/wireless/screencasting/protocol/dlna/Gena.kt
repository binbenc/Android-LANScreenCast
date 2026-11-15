package com.benchan.wireless.screencasting.protocol.dlna

import com.benchan.wireless.screencasting.protocol.upnp.UpnpTime
import com.benchan.wireless.screencasting.protocol.upnp.Xml

import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.net.InetUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** GENA 订阅（UPnP DA 第 4 章）。 */
data class Subscription(
    val sid: String,
    val service: DlnaService,
    val callbacks: List<URI>,
    var expiresAt: Long,
    var seq: Long = 0,
)

/**
 * GENA 事件：SUBSCRIBE / RENEW / UNSUBSCRIBE；LastChange 事件节流 200ms（技术方案 3.7）。
 * 回调地址只允许局域网地址（防 SSRF，也守住零外联，PRD 8.5）。
 */
class GenaManager(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sender: (URI, Subscription, String) -> Boolean = ::sendNotify,
) {
    private val subs = ConcurrentHashMap<String, Subscription>()
    private val pending = ConcurrentHashMap<DlnaService, Job>()

    fun subscribe(service: DlnaService, callbackHeader: String?, timeoutHeader: String?): Subscription? {
        val callbacks = parseCallbacks(callbackHeader).filter { u ->
            u.scheme == "http" && runCatching { InetUtils.isLanAddress(InetAddress.getByName(u.host)) }.getOrDefault(false)
        }
        if (callbacks.isEmpty()) return null
        val sub = Subscription("uuid:" + UUID.randomUUID(), service, callbacks, clock() + timeoutSeconds(timeoutHeader) * 1000L)
        subs[sub.sid] = sub
        return sub
    }

    fun renew(sid: String, timeoutHeader: String?): Subscription? {
        val s = subs[sid] ?: return null
        s.expiresAt = clock() + timeoutSeconds(timeoutHeader) * 1000L
        return s
    }

    fun unsubscribe(sid: String): Boolean = subs.remove(sid) != null

    fun count(): Int = subs.size

    /** 立即向一个新订阅者发送初始事件（SEQ=0）。 */
    fun sendInitial(sub: Subscription, propertySet: String) {
        scope.launch(Dispatchers.IO) { deliver(sub, propertySet) }
    }

    /** 状态变化：200ms 内合并为一次 NOTIFY。 */
    fun publish(service: DlnaService, propertySet: () -> String) {
        if (pending[service]?.isActive == true) return
        pending[service] = scope.launch(Dispatchers.IO) {
            delay(THROTTLE_MS)
            val body = propertySet()
            val now = clock()
            subs.values.filter { it.service == service }.forEach { s ->
                if (s.expiresAt < now) subs.remove(s.sid) else deliver(s, body)
            }
        }
    }

    private fun deliver(sub: Subscription, body: String) {
        val ok = sub.callbacks.any { sender(it, sub, body) }
        sub.seq = if (sub.seq == 0xFFFFFFFFL) 1 else sub.seq + 1
        if (!ok) WscLog.w(TAG, "notify failed for ${sub.sid}")
    }

    companion object {
        private const val TAG = "Gena"
        const val THROTTLE_MS = 200L
        const val DEFAULT_TIMEOUT_S = 1800

        fun parseCallbacks(header: String?): List<URI> =
            Regex("<([^>]+)>").findAll(header.orEmpty()).mapNotNull { runCatching { URI(it.groupValues[1]) }.getOrNull() }.toList()

        fun timeoutSeconds(header: String?): Int =
            header?.substringAfter("Second-", "")?.toIntOrNull()?.coerceIn(60, 7200) ?: DEFAULT_TIMEOUT_S

        /** LastChange 事件体（AVTransport / RenderingControl 共用格式）。 */
        fun lastChange(service: DlnaService, snap: PlayerSnapshot): String {
            val ns = if (service == DlnaService.AV_TRANSPORT) "urn:schemas-upnp-org:metadata-1-0/AVT/" else "urn:schemas-upnp-org:metadata-1-0/RCS/"
            val inner = buildString {
                append("""<Event xmlns="$ns"><InstanceID val="0">""")
                if (service == DlnaService.AV_TRANSPORT) {
                    append("""<TransportState val="${snap.state.name}"/>""")
                    append("""<TransportStatus val="${if (snap.error) "ERROR_OCCURRED" else "OK"}"/>""")
                    append("""<CurrentTrackDuration val="${UpnpTime.format(snap.durationMs)}"/>""")
                    append("""<CurrentMediaDuration val="${UpnpTime.format(snap.durationMs)}"/>""")
                    append("""<AVTransportURI val="${Xml.escape(snap.uri)}"/>""")
                    append("""<CurrentTransportActions val="${DmrActions.currentActions(snap.state)}"/>""")
                } else {
                    append("""<Volume channel="Master" val="${snap.volume}"/>""")
                    append("""<Mute channel="Master" val="${if (snap.muted) 1 else 0}"/>""")
                }
                append("</InstanceID></Event>")
            }
            return propertySet("LastChange" to inner)
        }

        fun propertySet(vararg props: Pair<String, String>): String = buildString {
            append("""<?xml version="1.0" encoding="utf-8"?><e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0">""")
            props.forEach { (k, v) -> append("<e:property><$k>").append(Xml.escape(v)).append("</$k></e:property>") }
            append("</e:propertyset>")
        }

        /** 原始 socket 发 NOTIFY（HttpURLConnection 不支持该方法）。 */
        fun sendNotify(url: URI, sub: Subscription, body: String): Boolean = runCatching {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val port = if (url.port > 0) url.port else 80
            Socket().use { s ->
                s.connect(InetSocketAddress(url.host, port), 2_000)
                s.soTimeout = 2_000
                val path = (url.rawPath ?: "/").ifEmpty { "/" } + (url.rawQuery?.let { "?$it" } ?: "")
                val head = "NOTIFY $path HTTP/1.1\r\nHOST: ${url.host}:$port\r\nCONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n" +
                    "NT: upnp:event\r\nNTS: upnp:propchange\r\nSID: ${sub.sid}\r\nSEQ: ${sub.seq}\r\nCONTENT-LENGTH: ${bytes.size}\r\nCONNECTION: close\r\n\r\n"
                s.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
                val status = s.getInputStream().bufferedReader().readLine().orEmpty()
                status.contains(" 200")
            }
        }.getOrDefault(false)
    }
}
