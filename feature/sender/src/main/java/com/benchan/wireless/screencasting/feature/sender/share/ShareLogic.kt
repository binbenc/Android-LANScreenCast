package com.benchan.wireless.screencasting.feature.sender.share

import com.benchan.wireless.screencasting.protocol.upnp.MediaKind
import com.benchan.wireless.screencasting.protocol.upnp.MediaMime

/**
 * F14 系统分享菜单推送的纯逻辑（PRD 8.1.2 C/D，交互稿第 14 章）。不依赖 Android，单测覆盖。
 */

/** 分享项的类型判定（PRD 8.1.2 C“类型判定”）：内容提供方 MIME → 意图类型 → 扩展名；都判断不出 = 不支持。 */
object ShareClassifier {
    /** 不能推送的“媒体”：本地 m3u8 / mpd 播放列表（分片是相对路径，电视拉不到，PRD 8.1.2 A）。 */
    private val PLAYLISTS = setOf("application/vnd.apple.mpegurl", "application/dash+xml")
    private val PLAYLIST_EXT = setOf("m3u8", "m3u", "mpd")

    /** 视为“没说清楚”的 MIME：继续看下一个来源。 */
    private fun undetermined(mime: String?): Boolean {
        val m = mime?.substringBefore(';')?.trim()?.lowercase()
        return m.isNullOrEmpty() || m == "application/octet-stream" || m == "*/*" || m == "application/*" ||
            m == "binary/octet-stream" || m == "application/unknown"
    }

    private fun kindOfMime(mime: String): MediaKind? {
        val m = MediaMime.normalize(mime)
        if (m in PLAYLISTS) return null
        // 意图里的通配类型（image/*、video/*）也能说明类型
        return when {
            m.startsWith("video/") -> MediaKind.VIDEO
            m.startsWith("audio/") -> MediaKind.AUDIO
            m.startsWith("image/") -> MediaKind.IMAGE
            else -> null
        }
    }

    fun extensionOf(name: String?): String? {
        val n = name?.trim()?.substringAfterLast('/') ?: return null
        if (!n.contains('.')) return null
        return n.substringAfterLast('.').lowercase().takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) }
    }

    /**
     * @param providerMime ContentResolver.getType()
     * @param intentMime 分享意图的类型（多选时可能是 `*` + `/` + `*`）
     * @param displayName 文件名（只用来取扩展名，不记录）
     * @return 类型；null = 不支持（过滤掉）
     */
    fun classify(providerMime: String?, intentMime: String?, displayName: String?): MediaKind? {
        val ext = extensionOf(displayName)
        if (ext in PLAYLIST_EXT) return null
        if (!undetermined(providerMime)) return kindOfMime(providerMime!!)
        if (!undetermined(intentMime)) return kindOfMime(intentMime!!)
        val byExt = ext?.let(MediaMime::fromExtension) ?: return null
        return kindOfMime(byExt)
    }

    /** 推送用的 MIME：提供方给的具体类型优先，否则按扩展名，最后按类型兜底。 */
    fun mimeFor(kind: MediaKind, providerMime: String?, displayName: String?): String {
        if (!undetermined(providerMime) && providerMime?.contains('*') == false) return MediaMime.normalize(providerMime)
        extensionOf(displayName)?.let(MediaMime::fromExtension)?.let { return it }
        return when (kind) {
            MediaKind.VIDEO -> "video/mp4"
            MediaKind.AUDIO -> "audio/mpeg"
            MediaKind.IMAGE -> "image/jpeg"
        }
    }
}

/** 网址在分享文字里的类型（决策 #33）。 */
enum class UrlKind { MEDIA, WEB_PAGE }

/** 从分享文字里找网址（PRD 8.1.2 C“分享的是文字”）。App 不访问这个网址、不展开短链接。 */
object ShareText {
    // 网址里不会出现的字符：空白、引号、尖括号，以及视频 App 常见格式“【标题】https://…”里的中文标点
    private val URL = Regex("""(?i)https?://[^\s<>"'`，。！？；：、（）【】《》「」『』“”‘’…]+""")
    private const val TRAILING = ".,;:!?)]}>'\""

    fun firstHttpUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val m = URL.find(text) ?: return null
        val url = m.value.trimEnd { it in TRAILING }
        // 至少要有主机名
        val host = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        return url.takeIf { host.isNotBlank() && host.any(Char::isLetterOrDigit) }
    }

    /** 只看扩展名（PRD 8.1.1 C“网址”）：能识别为媒体 = MEDIA；其余（含 b23.tv 这类短链接）= WEB_PAGE。 */
    fun urlKind(url: String): UrlKind = if (MediaMime.guessForUrl(url).first != null) UrlKind.MEDIA else UrlKind.WEB_PAGE
}

/** 推送期间怎样保证文件一直可读（决策 #42）。 */
enum class ReadMode {
    /** 复制到应用缓存，推送全程读副本。 */
    COPY,

    /** 把读授权转交给推送前台服务，直接读原文件。 */
    TRANSFER,
}

/**
 * 读取策略（决策 #42，技术方案 3.14.3）：照片 / 音乐一律复制。
 * 视频：**真机验证（Q20）前默认复制**（用户拍板），转交授权的代码保留，由 [TRANSFER_VIDEO_BY_DEFAULT]
 * 或调试开关 `debug.wsc.share_transfer 1` 打开。打开后：content:// + 普通文件 + 有大小 → 转交；
 * “不确定”（云盘管道、取不到大小、这个来源以前转交后读失败过）仍复制。
 * 视频超过单个复制上限但原文件可随机读取时，只能转交（复制不了）。
 */
object ShareReadPolicy {
    /** 真机验证通过后改为 true（同步更新技术方案 3.14.3 与开发进度 4.13）。 */
    const val TRANSFER_VIDEO_BY_DEFAULT = false

    fun decide(
        kind: MediaKind,
        scheme: String?,
        sizeBytes: Long?,
        seekable: Boolean,
        sourceNeedsCopy: Boolean,
        transferEnabled: Boolean,
        maxSingleCopy: Long = ShareLimits.MAX_SINGLE_COPY,
    ): ReadMode {
        if (kind != MediaKind.VIDEO) return ReadMode.COPY
        val transferable = scheme == "content" && seekable && sizeBytes != null
        if (!transferable) return ReadMode.COPY
        if (sizeBytes != null && sizeBytes > maxSingleCopy) return ReadMode.TRANSFER
        if (!transferEnabled || sourceNeedsCopy) return ReadMode.COPY
        return ReadMode.TRANSFER
    }
}

/** 复制的上限与余量（PRD 8.1.2 D ④，交互稿 Q15-C；写入技术方案 3.14.3 与开发进度）。 */
object ShareLimits {
    private const val MIB = 1024L * 1024
    private const val GIB = 1024L * MIB

    /** 复制后至少留给系统的空间。 */
    const val RESERVE_BYTES = 500L * MIB

    /** 单个文件最多复制 4 GB（更大的视频只能转交授权）。 */
    const val MAX_SINGLE_COPY = 4L * GIB

    /** 一次分享最多复制 8 GB。 */
    const val MAX_TOTAL_COPY = 8L * GIB

    /** 一次最多推送 100 项（与 F13 一致）。 */
    const val MAX_ITEMS = 100

    /** 复制失败（非空间不足）的重试次数。 */
    const val COPY_RETRIES = 1

    /** 到达即检查的总时长上限。 */
    const val CHECK_BUDGET_MS = 2_000L

    /** 单项试读的上限（只存在云端的文件，提供方可能先下载再返回，交互稿走查 P2-4）。 */
    const val PROBE_TIMEOUT_MS = 1_500L

    /** 复制时多久没有读到新数据算“读取中断”（云盘流卡住，P2-5）。 */
    const val STALL_MS = 30_000L
}

/** 空间规划结果（交互稿 S05 ⑧⑨）。 */
data class SpacePlan(
    /** 全部要复制的项需要的空间（不含余量）。 */
    val requiredBytes: Long,
    val availableBytes: Long,
    /** 按分享顺序从头累加，放得下的项数（转交授权的项不占空间）。 */
    val fitCount: Int,
    /** 前 [fitCount] 项需要复制的大小。 */
    val fitBytes: Long,
    val total: Int,
) {
    val fitsAll: Boolean get() = fitCount == total
}

object SpacePlanner {
    /**
     * @param copyBytes 每项需要复制的大小（按分享顺序）；0 = 不需要复制（转交授权）；null = 大小未知（复制时边写边查）
     */
    fun plan(copyBytes: List<Long?>, availableBytes: Long, reserve: Long = ShareLimits.RESERVE_BYTES, maxTotal: Long = ShareLimits.MAX_TOTAL_COPY): SpacePlan {
        val budget = minOf((availableBytes - reserve).coerceAtLeast(0), maxTotal)
        var sum = 0L
        var fit = 0
        var fitBytes = 0L
        var stopped = false
        for (b in copyBytes) {
            val need = b ?: 0L
            sum += need
            if (!stopped && fitBytes + need <= budget) {
                fit++
                fitBytes += need
            } else {
                stopped = true
            }
        }
        return SpacePlan(sum, availableBytes, fit, fitBytes, copyBytes.size)
    }

    /** 大小未知的流：边复制边检查，可用空间低于余量就停（交互稿 S05“时机”）。 */
    fun streamMayContinue(availableBytes: Long, reserve: Long = ShareLimits.RESERVE_BYTES): Boolean = availableBytes > reserve
}

/** 读不到的原因（交互稿 S05：只写两类人话原因 + 判断不出时的通用文案）。 */
enum class UnreadableReason {
    /** 分享它的应用没有允许读取（无授权、file:// 地址）。 */
    NO_PERMISSION,

    /** 读不到这个文件（判断不出原因，不猜）。 */
    GENERIC,
}
