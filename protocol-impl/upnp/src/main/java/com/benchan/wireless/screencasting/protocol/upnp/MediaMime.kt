package com.benchan.wireless.screencasting.protocol.upnp

/** 扩展名 ↔ MIME ↔ 内容类型（技术方案 3.13.3：URL 扩展名必须与 MIME 一致；网址只按扩展名猜）。 */
object MediaMime {
    private val byExt: Map<String, String> = linkedMapOf(
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm",
        "mov" to "video/quicktime", "avi" to "video/x-msvideo", "ts" to "video/mp2t", "m2ts" to "video/mp2t",
        "mts" to "video/mp2t", "mpg" to "video/mpeg", "mpeg" to "video/mpeg", "flv" to "video/x-flv",
        "3gp" to "video/3gpp", "wmv" to "video/x-ms-wmv", "m3u8" to "application/vnd.apple.mpegurl",
        "mpd" to "application/dash+xml",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "flac" to "audio/flac", "wav" to "audio/wav",
        "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/ogg", "wma" to "audio/x-ms-wma", "amr" to "audio/amr",
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp",
        "heic" to "image/heic", "heif" to "image/heif", "bmp" to "image/bmp", "avif" to "image/avif",
    )

    /** MIME 规范化：别名 → 我们使用的写法。 */
    private val canonical: Map<String, String> = mapOf(
        "video/x-mkv" to "video/x-matroska", "video/mkv" to "video/x-matroska", "audio/x-flac" to "audio/flac",
        "audio/x-m4a" to "audio/mp4", "audio/m4a" to "audio/mp4", "video/mov" to "video/quicktime", "audio/x-wav" to "audio/wav",
        "audio/wave" to "audio/wav", "video/avi" to "video/x-msvideo", "video/x-m4v" to "video/mp4", "audio/mp3" to "audio/mpeg",
        "application/x-mpegurl" to "application/vnd.apple.mpegurl", "audio/mpegurl" to "application/vnd.apple.mpegurl",
        "image/jpg" to "image/jpeg", "image/pjpeg" to "image/jpeg", "video/mpeg4" to "video/mp4",
    )

    private val extByMime: Map<String, String> = mapOf(
        "video/mp4" to "mp4", "video/x-matroska" to "mkv", "video/webm" to "webm", "video/quicktime" to "mov",
        "video/x-msvideo" to "avi", "video/mp2t" to "ts", "video/mpeg" to "mpg", "video/x-flv" to "flv", "video/3gpp" to "3gp",
        "video/x-ms-wmv" to "wmv", "audio/mpeg" to "mp3", "audio/mp4" to "m4a", "audio/aac" to "aac", "audio/flac" to "flac",
        "audio/wav" to "wav", "audio/ogg" to "ogg", "audio/x-ms-wma" to "wma", "audio/amr" to "amr", "image/jpeg" to "jpg",
        "image/png" to "png", "image/gif" to "gif", "image/webp" to "webp", "image/heic" to "heic", "image/heif" to "heif",
        "image/bmp" to "bmp", "image/avif" to "avif",
    )

    fun normalize(mime: String): String {
        val m = mime.substringBefore(';').trim().lowercase()
        return canonical[m] ?: m
    }

    fun fromExtension(ext: String): String? = byExt[ext.lowercase()]

    /** URL 路径最后一段的扩展名（忽略查询串和片段）。 */
    fun extensionOfUrl(url: String): String? {
        val path = url.substringBefore('#').substringBefore('?')
        val afterScheme = path.substringAfter("://", path)
        val last = afterScheme.substringAfter('/', "").substringAfterLast('/')
        if (!last.contains('.')) return null
        return last.substringAfterLast('.').lowercase().takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) }
    }

    /** 文件扩展名；未知 MIME 退回 "bin"（只用于 URL 路径，电视不会按 bin 判断格式）。 */
    fun extensionFor(mime: String): String = extByMime[normalize(mime)] ?: "bin"

    fun kindOf(mime: String): MediaKind? {
        val m = normalize(mime)
        return when {
            m.startsWith("video/") || m == "application/vnd.apple.mpegurl" || m == "application/dash+xml" -> MediaKind.VIDEO
            m.startsWith("audio/") -> MediaKind.AUDIO
            m.startsWith("image/") -> MediaKind.IMAGE
            else -> null
        }
    }

    /** 网址推送（PRD C）：按扩展名推断；推断不出时按视频推，MIME 未知。 */
    fun guessForUrl(url: String): Pair<String?, MediaKind> {
        val mime = extensionOfUrl(url)?.let(::fromExtension) ?: return null to MediaKind.VIDEO
        return mime to (kindOf(mime) ?: MediaKind.VIDEO)
    }
}
