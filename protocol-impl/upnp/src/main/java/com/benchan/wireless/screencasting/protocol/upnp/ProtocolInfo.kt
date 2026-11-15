package com.benchan.wireless.screencasting.protocol.upnp

/** UPnP protocolInfo 四段式："protocol:network:contentFormat:additionalInfo"。 */
data class ProtocolInfo(val protocol: String, val network: String, val contentFormat: String, val additionalInfo: String) {
    override fun toString(): String = "$protocol:$network:$contentFormat:$additionalInfo"

    val isHttpGet: Boolean get() = protocol.equals("http-get", ignoreCase = true)

    /** additionalInfo 里的 DLNA.ORG_PN（DLNA 配置名称），没有返回 null。 */
    val dlnaProfile: String?
        get() = additionalInfo.split(';').firstOrNull { it.trim().startsWith("DLNA.ORG_PN=", ignoreCase = true) }
            ?.substringAfter('=')?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        fun parse(text: String): ProtocolInfo? {
            val parts = text.trim().split(':', limit = 4)
            if (parts.size != 4 || parts.any { it.isEmpty() }) return null
            return ProtocolInfo(parts[0].trim(), parts[1].trim(), parts[2].trim(), parts[3].trim())
        }

        /** GetProtocolInfo 的 Sink（逗号分隔，容忍空格、空项与非法项）。 */
        fun parseList(csv: String?): List<ProtocolInfo> = csv.orEmpty().split(',').mapNotNull { parse(it) }
    }
}

/** 电视声明的格式与我们要推的格式是否匹配（只作参考，不硬拦，PRD 8.1.1 F）。 */
enum class SinkSupport { SUPPORTED, UNKNOWN, UNSUPPORTED }

object SinkMatcher {
    /** 容器 / MIME 级匹配。空列表、或含 `http-get:*:*:*` 时为 UNKNOWN。 */
    fun match(sink: List<ProtocolInfo>, mime: String): SinkSupport {
        val http = sink.filter { it.isHttpGet }
        if (http.isEmpty()) return SinkSupport.UNKNOWN
        if (http.any { it.contentFormat == "*" }) return SinkSupport.UNKNOWN
        val target = MediaMime.normalize(mime)
        return if (http.any { mimeMatches(it.contentFormat, target) }) SinkSupport.SUPPORTED else SinkSupport.UNSUPPORTED
    }

    private fun mimeMatches(format: String, target: String): Boolean {
        val f = MediaMime.normalize(format)
        if (f == target) return true
        // "video/*" 这类类型通配
        return f.endsWith("/*") && target.startsWith(f.removeSuffix("*"))
    }

    /**
     * 选实际上报的 MIME：MOV 文件在电视不认 video/quicktime 但认 video/mp4 时改报 video/mp4（容器兼容）[经验]。
     */
    fun preferredMime(sink: List<ProtocolInfo>, mime: String): String {
        val m = MediaMime.normalize(mime)
        if (m == "video/quicktime" && match(sink, m) == SinkSupport.UNSUPPORTED && match(sink, "video/mp4") == SinkSupport.SUPPORTED) {
            return "video/mp4"
        }
        return m
    }

    /** 某个 MIME 在 Sink 里声明的全部 DLNA 配置名称。 */
    fun profilesFor(sink: List<ProtocolInfo>, mime: String): List<String> {
        val target = MediaMime.normalize(mime)
        return sink.filter { it.isHttpGet && MediaMime.normalize(it.contentFormat) == target }.mapNotNull { it.dlnaProfile }
    }
}

/** 本地视频的格式信息（手机端用 MediaExtractor 读出）。 */
data class VideoFormatInfo(
    val containerMime: String,
    /** 视频轨 MIME，如 video/avc、video/hevc、video/x-vnd.on2.vp9、video/av01；纯音频为 null。 */
    val videoMime: String?,
    /** 音频轨 MIME，如 audio/mp4a-latm、audio/ac3、audio/vnd.dts。 */
    val audioMime: String?,
    val hdr: Boolean = false,
    val width: Int? = null,
    val height: Int? = null,
) {
    /** 记录“这台电视对这种格式失败过 / 成功过”的键（PRD F2：按 UDN 记在本机）。 */
    val key: String get() = listOf(MediaMime.normalize(containerMime), videoMime.orEmpty(), audioMime.orEmpty()).joinToString("|")
}

/** 推送前的格式判断（PRD 8.1.1 F2“何时自动提议”①）。 */
data class FormatVerdict(val support: SinkSupport, val containerUnsupported: Boolean, val codecUnsupported: Boolean) {
    /** 明显不在电视声明里 → 推送前提议转换。 */
    val suggestTranscode: Boolean get() = support == SinkSupport.UNSUPPORTED
}

object FormatCheck {
    /** DLNA 配置名称里代表视频编码的前缀。 */
    private val codecTokens: Map<String, List<String>> = mapOf(
        "video/avc" to listOf("AVC"), "video/hevc" to listOf("HEVC", "HVC", "H265"),
        "video/x-vnd.on2.vp9" to listOf("VP9"), "video/av01" to listOf("AV1"), "video/mp4v-es" to listOf("MPEG4"),
        "video/mpeg2" to listOf("MPEG_PS", "MPEG_TS", "MPEG2"),
    )

    fun evaluate(sink: List<ProtocolInfo>, info: VideoFormatInfo): FormatVerdict {
        val container = SinkMatcher.match(sink, info.containerMime)
        if (container == SinkSupport.UNKNOWN) return FormatVerdict(SinkSupport.UNKNOWN, containerUnsupported = false, codecUnsupported = false)
        val mime = SinkMatcher.preferredMime(sink, info.containerMime)
        if (SinkMatcher.match(sink, mime) == SinkSupport.UNSUPPORTED) {
            return FormatVerdict(SinkSupport.UNSUPPORTED, containerUnsupported = true, codecUnsupported = false)
        }
        // 只有当该容器的 Sink 条目全部带配置名称时，才能据此判断编码
        val entries = sink.filter { it.isHttpGet && MediaMime.normalize(it.contentFormat) == MediaMime.normalize(mime) }
        val tokens = info.videoMime?.let { codecTokens[it.lowercase()] }
        if (tokens != null && entries.isNotEmpty() && entries.all { it.dlnaProfile != null }) {
            val ok = entries.any { e -> tokens.any { t -> e.dlnaProfile!!.uppercase().startsWith(t) || e.dlnaProfile!!.uppercase().contains("_$t") } }
            if (!ok) return FormatVerdict(SinkSupport.UNSUPPORTED, containerUnsupported = false, codecUnsupported = true)
        }
        return FormatVerdict(SinkSupport.SUPPORTED, containerUnsupported = false, codecUnsupported = false)
    }
}

/** protocolInfo 第四段（DLNA 参数，技术方案 3.13.3.2）。 */
object DlnaParams {
    /** 流式 + 后台 + HTTP 停顿 + DLNA 1.5。 */
    const val FLAGS_STREAMING = "01700000000000000000000000000000"

    /** 交互（图片）+ 后台 + DLNA 1.5。 */
    const val FLAGS_INTERACTIVE = "00D00000000000000000000000000000"

    /** 本地文件：OP=01（支持字节 Range）/ 00（不可 seek）；CI=1 表示转换过的内容。 */
    fun forLocal(kind: MediaKind, seekable: Boolean, converted: Boolean = false, jpegProfile: String? = null): String {
        val op = if (seekable) "01" else "00"
        val ci = if (converted) "1" else "0"
        val flags = if (kind == MediaKind.IMAGE) FLAGS_INTERACTIVE else FLAGS_STREAMING
        val pn = if (kind == MediaKind.IMAGE && jpegProfile != null) "DLNA.ORG_PN=$jpegProfile;" else ""
        return "${pn}DLNA.ORG_OP=$op;DLNA.ORG_CI=$ci;DLNA.ORG_FLAGS=$flags"
    }

    fun protocolInfo(mime: String, fourth: String): String = "http-get:*:$mime:$fourth"

    /** 网址推送：我们不知道对方服务器支持什么。 */
    fun forRemote(mime: String?): String = "http-get:*:${mime ?: "*"}:*"

    /** JPEG 配置名称按尺寸选择（DLNA 指南：SM ≤640×480、MED ≤1024×768、LRG ≤4096×4096）。 */
    fun jpegProfile(width: Int, height: Int): String {
        val long = maxOf(width, height)
        val short = minOf(width, height)
        return when {
            long <= 640 && short <= 480 -> "JPEG_SM"
            long <= 1024 && short <= 768 -> "JPEG_MED"
            else -> "JPEG_LRG"
        }
    }

    /** transferMode.dlna.org 缺省值。 */
    fun transferMode(kind: MediaKind): String = if (kind == MediaKind.IMAGE) "Interactive" else "Streaming"
}
