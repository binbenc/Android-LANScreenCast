package com.benchan.wireless.screencasting.core.protocol

/**
 * WSC 协议常量（PRD 8.3.1、技术方案 3.5.6，权威定义见仓库根目录 protocol/PROTOCOL.md）。
 */
object WscProtocol {
    /** 当前协议主版本。HELLO 交换，向后兼容 N-1 个大版本。 */
    const val VERSION = 1
    const val MIN_COMPATIBLE_VERSION = 1

    /** mDNS 服务类型（NsdManager 使用不带 .local 的形式）。 */
    const val SERVICE_TYPE = "_wsc._tcp"

    /** 接收端信令端口首选值（交互稿 11：192.168.1.128 : 8443），被占用时回退为随机端口。 */
    const val PREFERRED_PORT = 8443

    /** WebSocket 子路径。 */
    const val WS_PATH = "/wsc"

    /** 心跳：每 5 秒 ping，连续 3 次无响应判定断开（技术方案 3.5.6）。 */
    const val PING_INTERVAL_MS = 5_000L
    const val PING_MISS_LIMIT = 3

    /** 配对确认弹窗超时（PRD 10：30 秒）。 */
    const val PAIR_CONFIRM_TIMEOUT_MS = 30_000L

    /** 断线重连最长等待（PRD 8.4：30 秒）。 */
    const val RECONNECT_WINDOW_MS = 30_000L

    /** mDNS TXT 记录键（PRD 8.3.1）。 */
    object Txt {
        const val VERSION = "v"
        const val DEVICE_ID = "id"
        const val NAME = "n"
        const val CAPS = "caps"
        const val FINGERPRINT_PREFIX = "fp"
    }

    /** 能力字符串。 */
    object Caps {
        const val H264 = "h264"
        const val H265 = "h265"
        const val OPUS = "opus"
    }
}

/** 版本协商：主版本差距不超过 1（N-1 兼容）时取两者较小值。 */
object VersionNegotiation {
    fun negotiate(local: Int, remote: Int, localMin: Int = WscProtocol.MIN_COMPATIBLE_VERSION): Int? {
        val chosen = minOf(local, remote)
        return if (chosen >= localMin && kotlin.math.abs(local - remote) <= 1) chosen else null
    }
}
