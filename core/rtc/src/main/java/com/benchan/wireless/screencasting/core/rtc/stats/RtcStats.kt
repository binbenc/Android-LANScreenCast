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
    /** 每包平均排队发送耗时（`totalPacketSendDelay / packetsSent` 的增量，ms）；拿不到时为 null。 */
    val pacerMs: Double? = null,
    /** 采集侧内容帧率（不含保底重发，由会话填写）。 */
    val captureFps: Int? = null,
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
    /** 每帧从收到第一个包到解码完成的平均耗时（`totalProcessingDelay / framesDecoded` 的增量，ms）。 */
    val processingMs: Double? = null,
    /** 低延迟解码器是否生效（F18，由会话填写；null = 未使用低延迟解码器工厂 / 尚未配置）。 */
    val lowLatencyDecoder: Boolean? = null,
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
    private var lastSendDelay = 0.0
    private var lastPacketsSent = 0.0
    private var lastProcessing = 0.0
    private var lastProcessedFrames = 0L

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
        val sendDelay = out.num("totalPacketSendDelay")
        val packetsSent = out.num("packetsSent")
        val pacerMs = if (sendDelay != null && packetsSent != null && packetsSent > lastPacketsSent && sendDelay >= lastSendDelay) {
            (sendDelay - lastSendDelay) * 1000 / (packetsSent - lastPacketsSent)
        } else null
        if (sendDelay != null) lastSendDelay = sendDelay
        if (packetsSent != null) lastPacketsSent = packetsSent

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
            pacerMs = pacerMs,
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
        val processing = inb.num("totalProcessingDelay")
        val processingMs = if (processing != null && framesDecoded > lastProcessedFrames && processing >= lastProcessing) {
            (processing - lastProcessing) * 1000 / (framesDecoded - lastProcessedFrames)
        } else null
        if (processing != null) lastProcessing = processing
        lastProcessedFrames = framesDecoded
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
            processingMs = processingMs,
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
 * 端到端延迟分段（技术方案 3.4 方法 M3；PRD 8.1.3 E 统计浮层的“网络、缓冲、解码、显示”四段）。
 * [measured] 列出来自 WebRTC 实测统计的段，其余段为模型值（见 [LatencyEstimator]）。
 */
data class LatencyBreakdown(
    /** 采集合成：VirtualDisplay 出帧等待（模型值）。 */
    val captureMs: Double,
    /** 编码 + 发送排队（实测：totalEncodeTime / totalPacketSendDelay）。 */
    val encodeMs: Double,
    /** 单程网络（实测：RTT / 2）。 */
    val networkMs: Double,
    /** 组帧 + 接收缓冲（实测：totalProcessingDelay − totalDecodeTime，或 jitterBufferDelay）。 */
    val bufferMs: Double,
    /** 解码（实测：totalDecodeTime / framesDecoded）。 */
    val decodeMs: Double,
    /** 渲染上屏（模型值）。 */
    val displayMs: Double,
    val measured: Set<Segment>,
) {
    enum class Segment { ENCODE, NETWORK, BUFFER, DECODE }

    val totalMs: Int get() = (captureMs + encodeMs + networkMs + bufferMs + decodeMs + displayMs).toInt()
}

/**
 * 端到端延迟估算（不含面板自身的响应时间）：能拿到的段一律用 WebRTC 实测统计，拿不到时才用下面集中的回退常数。
 * 只是估算值，界面显示为“约 xx ms（估算）”；真值以高速摄像（M1）校准后修改本对象里的模型常数。
 *
 * 实测来源：
 * - 编码：发送端 outbound-rtp `totalEncodeTime / framesEncoded`；排队：`totalPacketSendDelay / packetsSent`；
 * - 网络：candidate-pair `currentRoundTripTime / 2`（或 remote-inbound-rtp `roundTripTime`）；
 * - 缓冲：inbound-rtp `totalProcessingDelay / framesDecoded − 解码`（含组帧与抖动缓冲）；没有时用 `jitterBufferDelay / jitterBufferEmittedCount`；
 * - 解码：inbound-rtp `totalDecodeTime / framesDecoded`。
 */
object LatencyEstimator {
    /** VirtualDisplay 合成到出帧的固定开销（模型值，TODO(M0-T4) 高速摄像校准）。 */
    const val COMPOSITION_MS = 8.0

    /** 渲染到屏幕扫描输出的固定开销（模型值，TODO(M0-T4) 高速摄像校准）。 */
    const val SCANOUT_MS = 8.0

    /** 回退常数：对应统计拿不到时使用（旧版本对端不回传、首秒还没有增量）。 */
    const val FALLBACK_ENCODE_MS = 10.0
    const val FALLBACK_RTT_MS = 10.0
    const val FALLBACK_BUFFER_MS = 30.0
    const val FALLBACK_DECODE_MS = 10.0

    /**
     * @param fps **档位目标帧率**（含降帧后的上限），采集等待与渲染等待按它的半个帧间隔估算。不要传瞬时出帧率：
     *   画面静止 / 低帧率内容时“等下一帧”不属于传输延迟，按瞬时帧率会把估算抬高几百毫秒（F18 走查 P0-3）。
     * @return null 表示网络与缓冲都没有实测值，此时不显示数字（不猜）
     */
    fun breakdown(
        fps: Int,
        encodeMs: Double?,
        rttMs: Int?,
        jitterBufferMs: Double?,
        decodeMs: Double?,
        processingMs: Double? = null,
        pacerMs: Double? = null,
    ): LatencyBreakdown? {
        if (rttMs == null && jitterBufferMs == null && processingMs == null) return null
        val frameMs = 1000.0 / (if (fps in 1..240) fps else 30)
        val measured = mutableSetOf<LatencyBreakdown.Segment>()
        val encode = encodeMs?.also { measured += LatencyBreakdown.Segment.ENCODE } ?: FALLBACK_ENCODE_MS
        val network = rttMs?.let { measured += LatencyBreakdown.Segment.NETWORK; it / 2.0 } ?: (FALLBACK_RTT_MS / 2)
        val decode = decodeMs?.also { measured += LatencyBreakdown.Segment.DECODE } ?: FALLBACK_DECODE_MS
        val buffer = when {
            processingMs != null && decodeMs != null -> (processingMs - decodeMs).coerceAtLeast(0.0).also { measured += LatencyBreakdown.Segment.BUFFER }
            jitterBufferMs != null -> jitterBufferMs.also { measured += LatencyBreakdown.Segment.BUFFER }
            else -> FALLBACK_BUFFER_MS
        }
        return LatencyBreakdown(
            captureMs = frameMs / 2 + COMPOSITION_MS,
            encodeMs = encode + (pacerMs ?: 0.0),
            networkMs = network,
            bufferMs = buffer,
            decodeMs = decode,
            displayMs = frameMs / 2 + SCANOUT_MS,
            measured = measured,
        )
    }

    fun estimate(
        fps: Int,
        encodeMs: Double?,
        rttMs: Int?,
        jitterBufferMs: Double?,
        decodeMs: Double?,
        processingMs: Double? = null,
        pacerMs: Double? = null,
    ): Int? = breakdown(fps, encodeMs, rttMs, jitterBufferMs, decodeMs, processingMs, pacerMs)?.totalMs
}
