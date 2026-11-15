package com.benchan.wireless.screencasting.protocol.upnp

import com.benchan.wireless.screencasting.protocol.upnp.http.FetchResult
import com.benchan.wireless.screencasting.protocol.upnp.http.UpnpHttpClient

/** 描述 + SSDP Server 头 + 电视声明的 Sink 格式。 */
data class DescribedRenderer(val description: RendererDescription, val server: String?)

/**
 * 拉取并解析设备描述（技术方案 3.13.2.2）；LOCATION 必须是 SSDP 来源主机上的局域网地址，否则不发请求。
 */
class RendererDescriber(private val client: UpnpHttpClient = UpnpHttpClient()) {
    suspend fun describe(location: String, sourceHost: String, server: String? = null): DescribedRenderer? {
        if (!UrlGuard.isSameLanHost(location, sourceHost)) return null
        val r = client.fetchText(location, DeviceDescriptionParser.MAX_CHARS, timeoutMs = 3_000) as? FetchResult.Ok ?: return null
        val d = DeviceDescriptionParser.parse(r.body, location, sourceHost) ?: return null
        return DescribedRenderer(d, server ?: r.server)
    }

    /** ConnectionManager#GetProtocolInfo 的 Sink；拿不到返回 null（不影响推送）。 */
    suspend fun sink(d: RendererDescription): List<ProtocolInfo>? {
        val cm = d.connectionManager ?: return null
        val out = client.soap(cm.controlUrl, cm.serviceType, "GetProtocolInfo", emptyList(), 4_000) as? SoapOutcome.Ok ?: return null
        return ProtocolInfo.parseList(out.values["Sink"])
    }
}
