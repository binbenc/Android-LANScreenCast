package com.benchan.wireless.screencasting.protocol.upnp

/** UPnP 时间格式 H+:MM:SS[.F+]（也兼容 F0/F1 分数写法）。 */
object UpnpTime {
    /** "HH:MM:SS"（Seek Target、GetPositionInfo 输出）。 */
    fun format(ms: Long): String {
        if (ms < 0) return "00:00:00"
        val s = ms / 1000
        return "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    }

    /** "H:MM:SS.mmm"（DIDL-Lite `res@duration`）。 */
    fun formatPrecise(ms: Long): String {
        val v = ms.coerceAtLeast(0)
        val s = v / 1000
        return "%d:%02d:%02d.%03d".format(s / 3600, s / 60 % 60, s % 60, v % 1000)
    }

    /** 解析失败（含 NOT_IMPLEMENTED、空串）返回 null。 */
    fun parse(text: String): Long? {
        val t = text.trim().removePrefix("+")
        val parts = t.split(':')
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val secParts = parts[2].split('.')
        val s = secParts[0].toLongOrNull() ?: return null
        val frac = secParts.getOrNull(1)?.let { f ->
            if (f.contains('/')) 0L else ("0.$f".toDoubleOrNull()?.times(1000))?.toLong()
        } ?: 0L
        if (m >= 60 || s >= 60 || h < 0 || m < 0 || s < 0) return null
        return ((h * 3600 + m * 60 + s) * 1000) + frac
    }
}
