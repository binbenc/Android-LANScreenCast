package com.benchan.wireless.screencasting.core.rtc

/**
 * SDP 文本处理（纯函数，可单测）。
 */
object SdpMunger {
    /**
     * Opus 参数（技术方案 3.3）：stereo=1; sprop-stereo=1; maxaveragebitrate=128000; useinbandfec=1; usedtx=0。
     * 内录的是音乐/视频声音，需要立体声和较高码率，并关闭 DTX（静音段不省流，避免爆音）。
     */
    fun tuneOpus(sdp: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        val opusPts = lines.mapNotNull { l ->
            Regex("^a=rtpmap:(\\d+) opus/48000", RegexOption.IGNORE_CASE).find(l)?.groupValues?.get(1)
        }.toSet()
        if (opusPts.isEmpty()) return sdp
        val wanted = linkedMapOf("stereo" to "1", "sprop-stereo" to "1", "maxaveragebitrate" to "128000", "useinbandfec" to "1", "usedtx" to "0")
        val seen = mutableSetOf<String>()
        for (i in lines.indices) {
            val m = Regex("^a=fmtp:(\\d+) (.*)$").find(lines[i]) ?: continue
            val pt = m.groupValues[1]
            if (pt !in opusPts) continue
            seen += pt
            val params = linkedMapOf<String, String>()
            m.groupValues[2].split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                val k = it.substringBefore('=')
                params[k] = it.substringAfter('=', "")
            }
            params.putAll(wanted)
            lines[i] = "a=fmtp:$pt " + params.entries.joinToString(";") { "${it.key}=${it.value}" }
        }
        // 没有 fmtp 行的 Opus：在 rtpmap 后补一行
        for (pt in opusPts - seen) {
            val idx = lines.indexOfFirst { it.startsWith("a=rtpmap:$pt ") }
            if (idx >= 0) lines.add(idx + 1, "a=fmtp:$pt " + wanted.entries.joinToString(";") { "${it.key}=${it.value}" })
        }
        return lines.joinToString("\r\n")
    }

    /** 统计 SDP 中的候选类型，用于验证“只有 host 候选”（零外联，PRD 8.3.1）。 */
    fun candidateTypes(sdpOrCandidate: String): Set<String> =
        Regex("typ (host|srflx|prflx|relay)").findAll(sdpOrCandidate).map { it.groupValues[1] }.toSet()

    /** 只放行 host 候选；srflx/relay 理论上不会出现（没有配置 STUN/TURN），出现即丢弃。 */
    fun isHostCandidate(candidate: String): Boolean = candidateTypes(candidate).let { it.isEmpty() || it == setOf("host") }
}
