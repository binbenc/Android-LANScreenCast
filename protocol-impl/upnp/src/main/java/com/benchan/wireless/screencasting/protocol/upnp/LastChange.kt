package com.benchan.wireless.screencasting.protocol.upnp

/** GENA LastChange 事件里 InstanceID=0 的变量（Volume / Mute 只保留 Master 声道）。 */
data class LastChangeEvent(val values: Map<String, String>) {
    val transportState: String? get() = values["TransportState"]
    val transportStatus: String? get() = values["TransportStatus"]
    val volume: Int? get() = values["Volume"]?.toIntOrNull()
    val mute: Boolean? get() = values["Mute"]?.let { it == "1" || it.equals("true", true) }
    val trackUri: String? get() = values["CurrentTrackURI"] ?: values["AVTransportURI"]
}

/** 解析 NOTIFY 的 propertyset（LastChange 的值是被转义的 XML 文本，需要二次解析）。 */
object LastChangeParser {
    fun parse(propertySet: String): LastChangeEvent? {
        val doc = runCatching { Xml.parse(propertySet, Soap.MAX_BODY) }.getOrNull() ?: return null
        val lc = Xml.first(doc, "LastChange")?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val inner = runCatching { Xml.parse(lc, Soap.MAX_BODY) }.getOrNull() ?: return null
        val instances = inner.getElementsByTagNameNS("*", "InstanceID")
        if (instances.length == 0) return null
        val out = LinkedHashMap<String, String>()
        for (i in 0 until instances.length) {
            val inst = instances.item(i) as org.w3c.dom.Element
            if (inst.getAttribute("val").ifEmpty { "0" } != "0") continue
            Xml.children(inst).forEach { e ->
                val channel = e.getAttribute("channel")
                if (channel.isNotEmpty() && !channel.equals("Master", ignoreCase = true)) return@forEach
                out[Xml.localName(e)] = e.getAttribute("val")
            }
        }
        return LastChangeEvent(out)
    }
}
