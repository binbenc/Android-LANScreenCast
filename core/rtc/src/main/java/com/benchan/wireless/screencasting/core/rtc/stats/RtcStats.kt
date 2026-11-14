package com.benchan.wireless.screencasting.core.rtc.stats

/** getStats 中的一条记录（与 org.webrtc.RTCStats 解耦，便于单测）。 */
data class StatEntry(val type: String, val id: String, val timestampUs: Double, val members: Map<String, Any?>) {
    fun num(key: String): Double? = (members[key] as? Number)?.toDouble()
    fun str(key: String): String? = members[key]?.toString()
    fun bool(key: String): Boolean? = members[key] as? Boolean
}

/** 发送端每秒统计快照。 */
data class SenderStatsSnapshot(
    val bitrateKbps: Int,
    val fps: Int,
    val width: Int,
    val height: Int,
    val rttMs: Int?,
    val encodeMs: Double?,
    val lossPercent: Double,
    val availableOutgoingBitrateBps: Long?,
    val encoder: String?,
    val bytesSent: Long,
    val framesEncoded: Long,
    val hostOnly: Boolean,
)

/** 接收端每秒统计快照。 */
data class ReceiverStatsSnapshot(
    val bitrateKbps: Int,
    val fps: Int,
    val width: Int,
    val height: Int,
    val jitterBufferMs: Double?,
    val decodeMs: Double?,
    val framesDecoded: Long,
    val framesDropped: Long,
    val framesReceived: Long,
    val decoder: String?,
    val hardwareDecoder: Boolean?,
    val rttMs: Int?,
    val bytesReceived: Long,
    val codec: String?,
) {
    val dropPercent: Double get() = if (framesReceived <= 0) 0.0 else framesDropped * 100.0 / framesReceived
}

/**
 * 从 WebRTC 标准统计计算每秒指标（技术方案 3.4 方法 M3）。码率需要与上一次快照做差，所以是有状态的。
 */
class StatsParser {
    private var lastBytes = 0L
    private var lastTimestampUs = 0.0
    private var lastEncodeTime = 0.0
    private var lastFramesEncoded = 0L
    private var lastDecodeTime = 0.0
    private var lastFramesDecoded = 0L
    private var lastJbDelay = 0.0
    private var lastJbEmitted = 0.0
    private var lastPacketsLost = 0.0
    private var lastPacketsTotal = 0.0

    fun parseSender(entries: Collection<StatEntry>): SenderStatsSnapshot? {
        val out = entries.firstOrNull { it.type == "outbound-rtp" && it.str("kind") == "video" } ?: return null
        val bytes = out.num("bytesSent")?.toLong() ?: 0L
        val ts = out.timestampUs
        val kbps = rate(bytes, ts)
        val framesEncoded = out.num("framesEncoded")?.toLong() ?: 0L
        val totalEncode = out.num("totalEncodeTime") ?: 0.0
        val encodeMs = if (framesEncoded > lastFramesEncoded) (totalEncode - lastEncodeTime) * 1000 / (framesEncoded - lastFramesEncoded) else null
        lastEncodeTime = totalEncode
        lastFramesEncoded = framesEncoded

        val remoteIn = entries.firstOrNull { it.type == "remote-inbound-rtp" && it.str("kind") == "video" }
        val pair = selectedPair(entries)
        val rtt = (remoteIn?.num("roundTripTime") ?: pair?.num("currentRoundTripTime"))?.let { (it * 1000).toInt() }
        val loss = remoteIn?.num("fractionLost")?.let { it * 100 } ?: 0.0
        return SenderStatsSnapshot(
            bitrateKbps = kbps,
            fps = out.num("framesPerSecond")?.toInt() ?: 0,
            width = out.num("frameWidth")?.toInt() ?: 0,
            height = out.num("frameHeight")?.toInt() ?: 0,
            rttMs = rtt,
            encodeMs = encodeMs,
            lossPercent = loss,
            availableOutgoingBitrateBps = pair?.num("availableOutgoingBitrate")?.toLong(),
            encoder = out.str("encoderImplementation"),
            bytesSent = bytes,
            framesEncoded = framesEncoded,
            hostOnly = hostOnly(entries),
        )
    }

    fun parseReceiver(entries: Collection<StatEntry>): ReceiverStatsSnapshot? {
        val inb = entries.firstOrNull { it.type == "inbound-rtp" && it.str("kind") == "video" } ?: return null
        val bytes = inb.num("bytesReceived")?.toLong() ?: 0L
        val kbps = rate(bytes, inb.timestampUs)
        val framesDecoded = inb.num("framesDecoded")?.toLong() ?: 0L
        val totalDecode = inb.num("totalDecodeTime") ?: 0.0
        val decodeMs = if (framesDecoded > lastFramesDecoded) (totalDecode - lastDecodeTime) * 1000 / (framesDecoded - lastFramesDecoded) else null
        lastDecodeTime = totalDecode
        lastFramesDecoded = framesDecoded
        val jb = inb.num("jitterBufferDelay") ?: 0.0
        val jbn = inb.num("jitterBufferEmittedCount") ?: 0.0
        val jitterMs = if (jbn > lastJbEmitted) (jb - lastJbDelay) * 1000 / (jbn - lastJbEmitted) else null
        lastJbDelay = jb
        lastJbEmitted = jbn
        val codecId = inb.str("codecId")
        val codec = entries.firstOrNull { it.id == codecId }?.str("mimeType")?.substringAfter('/')
        return ReceiverStatsSnapshot(
            bitrateKbps = kbps,
            fps = inb.num("framesPerSecond")?.toInt() ?: 0,
            width = inb.num("frameWidth")?.toInt() ?: 0,
            height = inb.num("frameHeight")?.toInt() ?: 0,
            jitterBufferMs = jitterMs,
            decodeMs = decodeMs,
            framesDecoded = framesDecoded,
            framesDropped = inb.num("framesDropped")?.toLong() ?: 0L,
            framesReceived = inb.num("framesReceived")?.toLong() ?: framesDecoded,
            decoder = inb.str("decoderImplementation"),
            hardwareDecoder = inb.bool("powerEfficientDecoder"),
            rttMs = selectedPair(entries)?.num("currentRoundTripTime")?.let { (it * 1000).toInt() },
            bytesReceived = bytes,
            codec = codec,
        )
    }

    /** 丢包率（接收端视角，按包计）。 */
    fun receiverLossPercent(entries: Collection<StatEntry>): Double {
        val inb = entries.firstOrNull { it.type == "inbound-rtp" && it.str("kind") == "video" } ?: return 0.0
        val lost = inb.num("packetsLost") ?: 0.0
        val total = lost + (inb.num("packetsReceived") ?: 0.0)
        val dl = lost - lastPacketsLost
        val dt = total - lastPacketsTotal
        lastPacketsLost = lost
        lastPacketsTotal = total
        return if (dt <= 0) 0.0 else (dl / dt * 100).coerceIn(0.0, 100.0)
    }

    private fun rate(bytes: Long, tsUs: Double): Int {
        val kbps = if (lastTimestampUs > 0 && tsUs > lastTimestampUs && bytes >= lastBytes) {
            ((bytes - lastBytes) * 8 / ((tsUs - lastTimestampUs) / 1_000_000.0) / 1000).toInt()
        } else 0
        lastBytes = bytes
        lastTimestampUs = tsUs
        return kbps
    }

    private fun selectedPair(entries: Collection<StatEntry>): StatEntry? {
        val transport = entries.firstOrNull { it.type == "transport" }
        val selectedId = transport?.str("selectedCandidatePairId")
        return entries.firstOrNull { it.type == "candidate-pair" && it.id == selectedId }
            ?: entries.firstOrNull { it.type == "candidate-pair" && it.bool("nominated") == true && it.str("state") == "succeeded" }
    }

    /** 已选候选对的本端 / 远端候选都必须是 host（零外联验收的运行时佐证）。 */
    private fun hostOnly(entries: Collection<StatEntry>): Boolean {
        val pair = selectedPair(entries) ?: return true
        val local = entries.firstOrNull { it.id == pair.str("localCandidateId") }?.str("candidateType")
        val remote = entries.firstOrNull { it.id == pair.str("remoteCandidateId") }?.str("candidateType")
        return (local == null || local == "host") && (remote == null || remote == "host")
    }
}

/**
 * 端到端延迟估算（技术方案 3.4 方法 M3 的分段相加，不含面板延迟）：
 * 采集合成（约 1 帧）+ 编码 + 单程网络（RTT/2）+ 接收缓冲 + 解码 + 渲染（约 1 帧）。
 * 只是估算值，真值以高速摄像（M1）校准，M0 后修正常数。
 */
object LatencyEstimator {
    fun estimate(fps: Int, encodeMs: Double?, rttMs: Int?, jitterBufferMs: Double?, decodeMs: Double?): Int? {
        if (rttMs == null && jitterBufferMs == null) return null
        val frameMs = 1000.0 / (if (fps in 1..120) fps else 30)
        val captureMs = frameMs / 2 + 8
        val renderMs = frameMs / 2 + 8
        val total = captureMs + (encodeMs ?: 10.0) + (rttMs ?: 10) / 2.0 + (jitterBufferMs ?: 30.0) + (decodeMs ?: 10.0) + renderMs
        return total.toInt()
    }
}
