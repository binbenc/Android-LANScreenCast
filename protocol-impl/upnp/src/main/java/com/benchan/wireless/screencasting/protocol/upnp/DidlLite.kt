package com.benchan.wireless.screencasting.protocol.upnp

/** 推送内容的大类（决定 upnp:class、DLNA 传输模式与状态判定）。 */
enum class MediaKind(val upnpClass: String) {
    VIDEO("object.item.videoItem"),
    AUDIO("object.item.audioItem.musicTrack"),
    IMAGE("object.item.imageItem.photo"),
}

/** SetAVTransportURI 元数据的降级档位（PRD 8.1.1 F：完整 → 精简 → 不带）。 */
enum class MetadataLevel { FULL, MINIMAL, NONE }

/** 要写进 DIDL-Lite 的一项。 */
data class DidlItem(
    val title: String,
    val kind: MediaKind,
    val protocolInfo: String,
    val url: String,
    val sizeBytes: Long? = null,
    val durationMs: Long? = null,
    /** "1920x1080"。 */
    val resolution: String? = null,
)

/** DIDL-Lite 元数据中我们关心的字段（DMR 解析用，技术方案 3.7）。 */
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
            return runCatching {
                val doc = Xml.parse(didl, Soap.MAX_BODY)
                val res = Xml.first(doc, "res")
                MediaMetadata(
                    title = Xml.first(doc, "title")?.textContent?.trim(),
                    creator = Xml.first(doc, "creator")?.textContent?.trim(),
                    upnpClass = Xml.first(doc, "class")?.textContent?.trim(),
                    protocolInfo = res?.getAttribute("protocolInfo")?.takeIf { it.isNotBlank() },
                    durationMs = res?.getAttribute("duration")?.takeIf { it.isNotBlank() }?.let(UpnpTime::parse),
                    resUrl = res?.textContent?.trim()?.takeIf { it.isNotBlank() },
                )
            }.getOrNull()
        }
    }
}

/** DIDL-Lite 生成（技术方案 3.13.3.2）。不用 CDATA；标题去控制字符并截断。 */
object DidlLite {
    const val MAX_TITLE = 100

    fun build(item: DidlItem, level: MetadataLevel): String {
        if (level == MetadataLevel.NONE) return ""
        val title = sanitizeTitle(item.title)
        return buildString {
            append("""<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"""")
            if (level == MetadataLevel.FULL) append(""" xmlns:dlna="urn:schemas-dlna-org:metadata-1-0/"""")
            append(">")
            append("""<item id="wsc-1" parentID="-1" restricted="1">""")
            append("<dc:title>").append(Xml.escape(title)).append("</dc:title>")
            append("<upnp:class>").append(item.kind.upnpClass).append("</upnp:class>")
            append("""<res protocolInfo="""").append(Xml.escape(item.protocolInfo)).append('"')
            if (level == MetadataLevel.FULL) {
                item.sizeBytes?.takeIf { it > 0 }?.let { append(""" size="$it"""") }
                item.durationMs?.takeIf { it > 0 }?.let { append(""" duration="${UpnpTime.formatPrecise(it)}"""") }
                item.resolution?.let { append(""" resolution="${Xml.escape(it)}"""") }
            }
            append(">").append(Xml.escape(item.url)).append("</res>")
            append("</item></DIDL-Lite>")
        }
    }

    fun sanitizeTitle(raw: String): String {
        val cleaned = raw.filter { it >= ' ' || it == '\t' }.trim().ifEmpty { "Media" }
        if (cleaned.length <= MAX_TITLE) return cleaned
        // 不在代理对中间截断
        var end = MAX_TITLE
        if (Character.isHighSurrogate(cleaned[end - 1])) end--
        return cleaned.substring(0, end)
    }
}
