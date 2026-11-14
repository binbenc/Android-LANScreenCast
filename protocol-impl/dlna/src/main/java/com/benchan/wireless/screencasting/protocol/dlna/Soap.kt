package com.benchan.wireless.screencasting.protocol.dlna

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** UPnP 错误码（UPnP Device Architecture + AVTransport 规范）。 */
enum class UpnpError(val code: Int, val description: String) {
    INVALID_ACTION(401, "Invalid Action"),
    INVALID_ARGS(402, "Invalid Args"),
    ACTION_FAILED(501, "Action Failed"),
    TRANSITION_NOT_AVAILABLE(701, "Transition not available"),
    ILLEGAL_SEEK_TARGET(711, "Illegal seek target"),
    UNSUPPORTED_SEEK_MODE(710, "Seek mode not supported"),
    ILLEGAL_MIME(714, "Illegal MIME-type"),
    RESOURCE_NOT_FOUND(716, "Resource not found"),
    ACCESS_DENIED(606, "Action not authorized"),
}

class SoapException(val error: UpnpError, message: String = error.description) : Exception(message)

data class SoapRequest(val serviceType: String, val action: String, val args: Map<String, String>)

/**
 * SOAP 1.1 编解码（技术方案 3.7）。防护：限制 64KB、拒绝 DOCTYPE / ENTITY（防 XXE 与“十亿笑声”）。
 * 用 javax.xml DOM：Android 与 JVM 都有，便于单测。
 */
object Soap {
    const val MAX_BODY = 64 * 1024

    fun parse(body: String, soapActionHeader: String?): SoapRequest {
        if (body.length > MAX_BODY) throw SoapException(UpnpError.INVALID_ARGS, "body too large")
        if (body.contains("<!DOCTYPE", ignoreCase = true) || body.contains("<!ENTITY", ignoreCase = true)) {
            throw SoapException(UpnpError.INVALID_ARGS, "DTD not allowed")
        }
        val doc = try {
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
            }.newDocumentBuilder().parse(ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            throw SoapException(UpnpError.INVALID_ARGS, "malformed xml")
        }
        val bodyEl = children(doc.documentElement).firstOrNull { it.localName == "Body" }
            ?: throw SoapException(UpnpError.INVALID_ARGS, "no Body")
        val actionEl = children(bodyEl).firstOrNull() ?: throw SoapException(UpnpError.INVALID_ACTION, "no action")
        val header = soapActionHeader?.trim()?.trim('"')
        val serviceType = header?.substringBefore('#') ?: actionEl.namespaceURI.orEmpty()
        val action = actionEl.localName ?: header?.substringAfter('#') ?: throw SoapException(UpnpError.INVALID_ACTION)
        val args = children(actionEl).associate { (it.localName ?: it.nodeName) to it.textContent.orEmpty() }
        return SoapRequest(serviceType, action, args)
    }

    private fun children(e: Element): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = e.childNodes
        for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.let(out::add)
        return out
    }

    fun response(serviceType: String, action: String, out: Map<String, String>): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?>""")
        append("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""")
        append("""<u:${action}Response xmlns:u="$serviceType">""")
        out.forEach { (k, v) -> append("<$k>").append(Xml.escape(v)).append("</$k>") }
        append("</u:${action}Response></s:Body></s:Envelope>")
    }

    fun fault(error: UpnpError): String =
        """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>${error.code}</errorCode><errorDescription>${error.description}</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"""
}

/** DIDL-Lite 元数据中我们关心的字段（技术方案 3.7）。 */
data class MediaMetadata(
    val title: String?,
    val creator: String?,
    val upnpClass: String?,
    val protocolInfo: String?,
    val durationMs: Long?,
    val resUrl: String?,
) {
    val mimeType: String? get() = protocolInfo?.split(':')?.getOrNull(2)?.takeIf { it != "*" && it.isNotBlank() }

    companion object {
        /** 宽松解析：很多国内 App 的 DIDL 不规范，解析失败返回 null，不影响播放。 */
        fun parse(didl: String?): MediaMetadata? {
            if (didl.isNullOrBlank() || didl.length > Soap.MAX_BODY) return null
            if (didl.contains("<!DOCTYPE", true) || didl.contains("<!ENTITY", true)) return null
            return runCatching {
                val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
                    .newDocumentBuilder().parse(ByteArrayInputStream(didl.toByteArray(Charsets.UTF_8)))
                fun first(local: String): Element? {
                    val list = doc.getElementsByTagNameNS("*", local)
                    return if (list.length > 0) list.item(0) as Element else null
                }
                val res = first("res")
                MediaMetadata(
                    title = first("title")?.textContent?.trim(),
                    creator = first("creator")?.textContent?.trim(),
                    upnpClass = first("class")?.textContent?.trim(),
                    protocolInfo = res?.getAttribute("protocolInfo")?.takeIf { it.isNotBlank() },
                    durationMs = res?.getAttribute("duration")?.takeIf { it.isNotBlank() }?.let(UpnpTime::parse),
                    resUrl = res?.textContent?.trim()?.takeIf { it.isNotBlank() },
                )
            }.getOrNull()
        }
    }
}

/** UPnP 时间格式 H+:MM:SS[.F+]。 */
object UpnpTime {
    fun format(ms: Long): String {
        if (ms < 0) return "00:00:00"
        val s = ms / 1000
        return "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    }

    fun parse(text: String): Long? {
        val t = text.trim()
        val parts = t.split(':')
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val secParts = parts[2].split('.')
        val s = secParts[0].toLongOrNull() ?: return null
        val frac = secParts.getOrNull(1)?.let { f ->
            if (f.contains('/')) 0L else ("0.$f".toDoubleOrNull()?.times(1000))?.toLong()
        } ?: 0L
        if (m >= 60 || s >= 60 || h < 0) return null
        return ((h * 3600 + m * 60 + s) * 1000) + frac
    }
}
