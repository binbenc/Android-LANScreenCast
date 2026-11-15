package com.benchan.wireless.screencasting.protocol.upnp

/** UPnP 错误码（UPnP Device Architecture + AVTransport:1 规范）。 */
enum class UpnpError(val code: Int, val description: String) {
    INVALID_ACTION(401, "Invalid Action"),
    INVALID_ARGS(402, "Invalid Args"),
    ACTION_FAILED(501, "Action Failed"),
    ACCESS_DENIED(606, "Action not authorized"),
    TRANSITION_NOT_AVAILABLE(701, "Transition not available"),
    NO_CONTENTS(702, "No contents"),
    READ_ERROR(703, "Read error"),
    FORMAT_NOT_SUPPORTED(704, "Format not supported for playback"),
    TRANSPORT_LOCKED(705, "Transport is locked"),
    UNSUPPORTED_SEEK_MODE(710, "Seek mode not supported"),
    ILLEGAL_SEEK_TARGET(711, "Illegal seek target"),
    ILLEGAL_MIME(714, "Illegal MIME-type"),
    CONTENT_BUSY(715, "Content busy"),
    RESOURCE_NOT_FOUND(716, "Resource not found"),
    INVALID_INSTANCE_ID(718, "Invalid InstanceID");

    companion object {
        fun of(code: Int?): UpnpError? = entries.firstOrNull { it.code == code }
    }
}

class SoapException(val error: UpnpError, message: String = error.description) : Exception(message)

data class SoapRequest(val serviceType: String, val action: String, val args: Map<String, String>)

/** 控制点一次 SOAP 调用的结果。 */
sealed interface SoapOutcome {
    data class Ok(val values: Map<String, String>) : SoapOutcome

    /** 设备返回了 UPnP Fault（或非 200 且无法解析）。 */
    data class Fault(val code: Int?, val description: String?, val httpStatus: Int) : SoapOutcome

    /** 网络层失败：超时、连接失败、响应不是 SOAP、地址被零外联规则拦截。 */
    data class Failure(val kind: Kind, val httpStatus: Int? = null) : SoapOutcome {
        enum class Kind { TIMEOUT, NETWORK, BAD_RESPONSE, BLOCKED }
    }
}

/**
 * SOAP 1.1 编解码（技术方案 3.7 / 3.13.3）。DMR 用 [parse] / [response] / [fault]，DMC 用 [request] / [parseResponse]。
 * 防护：限制 64KB、拒绝 DOCTYPE / ENTITY。
 */
object Soap {
    const val MAX_BODY = 64 * 1024
    private const val ENVELOPE_HEAD =
        """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>"""
    private const val ENVELOPE_TAIL = "</s:Body></s:Envelope>"

    fun parse(body: String, soapActionHeader: String?): SoapRequest {
        if (body.length > MAX_BODY) throw SoapException(UpnpError.INVALID_ARGS, "body too large")
        val doc = try {
            Xml.parse(body, MAX_BODY)
        } catch (e: XmlException) {
            throw SoapException(UpnpError.INVALID_ARGS, e.message ?: "malformed xml")
        }
        val bodyEl = Xml.children(doc.documentElement).firstOrNull { it.localName == "Body" }
            ?: throw SoapException(UpnpError.INVALID_ARGS, "no Body")
        val actionEl = Xml.children(bodyEl).firstOrNull() ?: throw SoapException(UpnpError.INVALID_ACTION, "no action")
        val header = soapActionHeader?.trim()?.trim('"')
        val serviceType = header?.substringBefore('#') ?: actionEl.namespaceURI.orEmpty()
        val action = actionEl.localName ?: header?.substringAfter('#') ?: throw SoapException(UpnpError.INVALID_ACTION)
        val args = Xml.children(actionEl).associate { (it.localName ?: it.nodeName) to it.textContent.orEmpty() }
        return SoapRequest(serviceType, action, args)
    }

    fun response(serviceType: String, action: String, out: Map<String, String>): String = buildString {
        append(ENVELOPE_HEAD)
        append("""<u:${action}Response xmlns:u="$serviceType">""")
        out.forEach { (k, v) -> append("<$k>").append(Xml.escape(v)).append("</$k>") }
        append("</u:${action}Response>").append(ENVELOPE_TAIL)
    }

    fun fault(error: UpnpError): String =
        """$ENVELOPE_HEAD<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>${error.code}</errorCode><errorDescription>${error.description}</errorDescription></UPnPError></detail></s:Fault>$ENVELOPE_TAIL"""

    /** 控制点请求体。参数按顺序输出（部分电视按顺序解析）；值做一次 XML 转义（DIDL 元数据因此是双重转义，这是正确的）。 */
    fun request(serviceType: String, action: String, args: List<Pair<String, String>>): String = buildString {
        append(ENVELOPE_HEAD)
        append("""<u:$action xmlns:u="$serviceType">""")
        args.forEach { (k, v) -> append("<$k>").append(Xml.escape(v)).append("</$k>") }
        append("</u:$action>").append(ENVELOPE_TAIL)
    }

    /** SOAPACTION 头（带双引号）。 */
    fun actionHeader(serviceType: String, action: String): String = "\"$serviceType#$action\""

    /**
     * 解析控制点收到的响应：Fault → [SoapOutcome.Fault]；`<XxxResponse>` 的子元素 → [SoapOutcome.Ok]。
     * 宽松：部分电视 200 时响应体为空，按成功处理。
     */
    fun parseResponse(body: String, httpStatus: Int): SoapOutcome {
        if (body.isBlank()) {
            return if (httpStatus in 200..299) SoapOutcome.Ok(emptyMap()) else SoapOutcome.Fault(null, null, httpStatus)
        }
        val doc = try {
            Xml.parse(body, MAX_BODY)
        } catch (_: XmlException) {
            return if (httpStatus in 200..299) SoapOutcome.Failure(SoapOutcome.Failure.Kind.BAD_RESPONSE, httpStatus)
            else SoapOutcome.Fault(null, null, httpStatus)
        }
        val root = doc.documentElement
        val bodyEl = (if (Xml.localName(root).equals("Envelope", true)) Xml.child(root, "Body") else null)
        val first = bodyEl?.let { Xml.children(it).firstOrNull() }
        if (first == null || Xml.localName(first).equals("Fault", true) || httpStatus !in 200..299) {
            val code = Xml.first(doc, "errorCode")?.textContent?.trim()?.toIntOrNull()
            val desc = Xml.first(doc, "errorDescription")?.textContent?.trim()
            if (first == null && httpStatus in 200..299) return SoapOutcome.Failure(SoapOutcome.Failure.Kind.BAD_RESPONSE, httpStatus)
            return SoapOutcome.Fault(code, desc, httpStatus)
        }
        return SoapOutcome.Ok(Xml.children(first).associate { Xml.localName(it) to it.textContent.orEmpty() })
    }
}
