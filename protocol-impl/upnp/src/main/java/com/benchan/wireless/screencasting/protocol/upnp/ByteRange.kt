package com.benchan.wireless.screencasting.protocol.upnp

/** HTTP 单段字节区间（含端点）。 */
data class ByteRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1
    fun contentRange(size: Long): String = "bytes $start-$endInclusive/$size"
}

/** Range 头解析结果（RFC 9110 14.2）：无效的 Range 头被忽略（按整文件 200 返回）。 */
sealed interface RangeRequest {
    data object Full : RangeRequest
    data class Partial(val range: ByteRange) : RangeRequest
    data object Unsatisfiable : RangeRequest

    companion object {
        /** 支持 `bytes=a-b`、`bytes=a-`、`bytes=-n`；多段只取第一段（电视不会用多段）。 */
        fun parse(header: String?, size: Long): RangeRequest {
            if (header.isNullOrBlank()) return Full
            val h = header.trim()
            if (!h.startsWith("bytes=", ignoreCase = true)) return Full
            val spec = h.substring(6).split(',').firstOrNull()?.trim().orEmpty()
            val dash = spec.indexOf('-')
            if (dash < 0) return Full
            val a = spec.substring(0, dash).trim()
            val b = spec.substring(dash + 1).trim()
            if (size <= 0) return Unsatisfiable
            if (a.isEmpty()) {
                val n = b.toLongOrNull() ?: return Full
                if (n <= 0) return Unsatisfiable
                val start = (size - n).coerceAtLeast(0)
                return Partial(ByteRange(start, size - 1))
            }
            val start = a.toLongOrNull()?.takeIf { it >= 0 } ?: return Full
            val end = if (b.isEmpty()) size - 1 else (b.toLongOrNull() ?: return Full)
            if (start >= size) return Unsatisfiable
            if (end < start) return Full
            return Partial(ByteRange(start, minOf(end, size - 1)))
        }
    }
}
