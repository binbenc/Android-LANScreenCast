package com.benchan.wireless.screencasting.protocol.upnp

import org.w3c.dom.Element
import java.net.URI

/** UPnP 服务类型（DMC 侧只看前缀，版本号以设备描述里声明的为准）。 */
object UpnpServices {
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"
    const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

    internal fun sameService(declared: String, wanted: String): Boolean =
        declared.substringBeforeLast(':').equals(wanted.substringBeforeLast(':'), ignoreCase = true)
}

data class ServiceEndpoint(
    /** 设备声明的完整类型（可能是 :2 / :3），SOAPACTION 与命名空间都用它。 */
    val serviceType: String,
    val controlUrl: String,
    val eventSubUrl: String?,
    val scpdUrl: String?,
)

/** 一台可推送的 DMR（设备描述解析结果）。 */
data class RendererDescription(
    /** 不带 "uuid:" 前缀。 */
    val udn: String,
    val friendlyName: String?,
    val manufacturer: String?,
    val modelName: String?,
    val modelNumber: String?,
    val location: String,
    val host: String,
    val avTransport: ServiceEndpoint,
    val renderingControl: ServiceEndpoint?,
    val connectionManager: ServiceEndpoint?,
    /** 本应用接收端（描述里的 wsc:service）的设备 ID。 */
    val wscId: String? = null,
    val isOwnApp: Boolean = false,
) {
    /** PRD 8.1.1 B：friendlyName → 品牌 + 型号 → IP。 */
    val displayName: String
        get() = friendlyName?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(manufacturer, modelName).joinToString(" ").takeIf { it.isNotBlank() }
            ?: host
}

/** 设备描述解析（技术方案 3.13.2.2）。URLBase / 相对 URL / 嵌套 deviceList；所有地址必须在 SSDP 来源主机上。 */
object DeviceDescriptionParser {
    const val MAX_CHARS = 256 * 1024
    private const val WSC_NS = "urn:wsc:device-1-0"
    private val ABSOLUTE = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")

    fun parse(xml: String, location: String, sourceHost: String): RendererDescription? {
        if (!UrlGuard.isSameLanHost(location, sourceHost)) return null
        val doc = runCatching { Xml.parse(xml, MAX_CHARS) }.getOrNull() ?: return null
        val root = doc.documentElement ?: return null
        val base = Xml.childText(root, "URLBase")?.takeIf { UrlGuard.isSameLanHost(it, sourceHost) } ?: location
        val device = findRenderer(Xml.child(root, "device") ?: return null) ?: return null
        val services = Xml.child(device, "serviceList")?.let { Xml.children(it) }.orEmpty()
        fun endpoint(wanted: String): ServiceEndpoint? {
            val s = services.firstOrNull { svc -> Xml.childText(svc, "serviceType")?.let { UpnpServices.sameService(it, wanted) } == true } ?: return null
            val control = resolve(base, Xml.childText(s, "controlURL"))?.takeIf { UrlGuard.isSameLanHost(it, sourceHost) } ?: return null
            return ServiceEndpoint(
                serviceType = Xml.childText(s, "serviceType")!!,
                controlUrl = control,
                eventSubUrl = resolve(base, Xml.childText(s, "eventSubURL"))?.takeIf { UrlGuard.isSameLanHost(it, sourceHost) },
                scpdUrl = resolve(base, Xml.childText(s, "SCPDURL"))?.takeIf { UrlGuard.isSameLanHost(it, sourceHost) },
            )
        }
        val avt = endpoint(UpnpServices.AV_TRANSPORT) ?: return null
        val udn = Xml.childText(device, "UDN")?.removePrefix("uuid:")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val wscIdList = doc.getElementsByTagNameNS(WSC_NS, "id")
        val wscId = if (wscIdList.length > 0) wscIdList.item(0).textContent?.trim()?.takeIf { it.isNotEmpty() } else null
        val manufacturer = Xml.childText(device, "manufacturer")
        return RendererDescription(
            udn = udn,
            friendlyName = Xml.childText(device, "friendlyName"),
            manufacturer = manufacturer,
            modelName = Xml.childText(device, "modelName"),
            modelNumber = Xml.childText(device, "modelNumber"),
            location = location,
            host = sourceHost,
            avTransport = avt,
            renderingControl = endpoint(UpnpServices.RENDERING_CONTROL),
            connectionManager = endpoint(UpnpServices.CONNECTION_MANAGER),
            wscId = wscId,
            isOwnApp = wscId != null || manufacturer == "WirelessScreenCasting",
        )
    }

    /** 根设备或嵌套设备中第一个带 AVTransport 的（优先 MediaRenderer 类型）。 */
    private fun findRenderer(device: Element): Element? {
        val all = mutableListOf<Element>()
        fun walk(d: Element, depth: Int) {
            if (depth > 4) return
            all += d
            Xml.child(d, "deviceList")?.let { list -> Xml.children(list).filter { Xml.localName(it) == "device" }.forEach { walk(it, depth + 1) } }
        }
        walk(device, 0)
        fun hasAvt(d: Element) = Xml.child(d, "serviceList")?.let { Xml.children(it) }.orEmpty()
            .any { s -> Xml.childText(s, "serviceType")?.let { UpnpServices.sameService(it, UpnpServices.AV_TRANSPORT) } == true }
        return all.firstOrNull { hasAvt(it) && Xml.childText(it, "deviceType")?.contains("MediaRenderer", true) == true }
            ?: all.firstOrNull(::hasAvt)
    }

    /** 解析相对地址；不带 "/" 的相对路径按 base 目录解析（UPnP 1.0 规则）。 */
    fun resolve(base: String, ref: String?): String? {
        var r = ref?.trim()?.takeIf { it.isNotEmpty() }?.replace(" ", "%20") ?: return null
        // 形如 "_urn:schemas-upnp-org:service:AVTransport_control" 的相对路径会被误当成 scheme，补 "./"
        if (!ABSOLUTE.matches(r) && !r.startsWith("/")) r = "./$r"
        return runCatching { URI(base).resolve(URI(r)).toString() }.getOrNull()
    }
}
