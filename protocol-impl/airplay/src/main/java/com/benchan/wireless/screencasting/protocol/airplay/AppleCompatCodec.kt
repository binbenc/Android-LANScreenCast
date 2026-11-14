package com.benchan.wireless.screencasting.protocol.airplay

import kotlin.math.pow

/*
 * 兼容 Apple 设备接收端里与 Android API 无关的纯逻辑（JVM 单测覆盖，见 src/test）。
 */

/** AirPlay 音量（dB，-30..0，-144 = 静音）→ AudioTrack 线性增益（0..1）。与 UxPlay audio_set_volume 的映射一致（不做 taper）。 */
object AirPlayVolume {
    const val MUTE_DB = -144f
    private const val MIN_DB = -30f

    fun toLinear(volumeDb: Float): Float {
        if (volumeDb.isNaN() || volumeDb == MUTE_DB || volumeDb <= MIN_DB) return 0f
        if (volumeDb >= 0f) return 1f
        // 发送端滑块已线性映射到 -30..0 dB，直接按 dB 转线性增益
        return 10.0.pow(volumeDb / 20.0).toFloat().coerceIn(0f, 1f)
    }
}

/** RAOP 音频格式（UxPlay audio_decode_struct.ct）。 */
enum class RaopAudioCodec(val ct: Int, val samplesPerFrame: Int, val mime: String?) {
    PCM(1, 352, null),
    ALAC(2, 352, null),
    AAC_LC(4, 1024, "audio/mp4a-latm"),
    AAC_ELD(8, 480, "audio/mp4a-latm"),
    ;

    /** MediaCodec csd-0（AudioSpecificConfig，44.1 kHz 立体声），与 UxPlay audio_renderer 的 codec_data 相同。 */
    val csd0: ByteArray?
        get() = when (this) {
            AAC_LC -> byteArrayOf(0x12, 0x10)
            AAC_ELD -> byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00)
            else -> null
        }

    companion object {
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 2

        fun fromCt(ct: Int): RaopAudioCodec = entries.firstOrNull { it.ct == ct } ?: PCM
    }
}

/** Annex-B 码流工具：把 UxPlay 前置在关键帧前的参数集（SPS/PPS 或 VPS/SPS/PPS）拆成 codec config 单独送入 MediaCodec。 */
object AnnexB {
    /** 返回所有 NAL 单元（含起始码）的起始偏移，按出现顺序。 */
    fun nalOffsets(data: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i + 3 <= data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) {
                    out += i
                    i += 3
                    continue
                }
                if (i + 4 <= data.size && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                    out += i
                    i += 4
                    continue
                }
            }
            i++
        }
        return out
    }

    /** 起始码之后第一个字节所在位置。 */
    private fun headerIndex(data: ByteArray, offset: Int): Int = if (data[offset + 2].toInt() == 1) offset + 3 else offset + 4

    fun nalType(data: ByteArray, offset: Int, h265: Boolean): Int {
        val h = headerIndex(data, offset)
        if (h >= data.size) return -1
        val b = data[h].toInt() and 0xFF
        return if (h265) (b shr 1) and 0x3F else b and 0x1F
    }

    private fun isParameterSet(type: Int, h265: Boolean): Boolean =
        if (h265) type in 32..34 else type == 7 || type == 8

    /**
     * 开头连续的参数集 NAL 的总长度；0 表示没有。整包都是参数集时返回 data.size。
     * MediaCodec 要求参数集用 BUFFER_FLAG_CODEC_CONFIG 单独提交，部分硬件解码器不接受与 IDR 混在同一个 buffer 里。
     */
    fun codecConfigLength(data: ByteArray, h265: Boolean): Int {
        val offsets = nalOffsets(data)
        if (offsets.isEmpty() || offsets[0] != 0) return 0
        for ((idx, off) in offsets.withIndex()) {
            if (!isParameterSet(nalType(data, off, h265), h265)) return if (idx == 0) 0 else off
        }
        return data.size
    }

    /** 包内是否含关键帧（H.264 IDR=5；H.265 IRAP 16..21）。 */
    fun containsKeyFrame(data: ByteArray, h265: Boolean): Boolean = nalOffsets(data).any { off ->
        val t = nalType(data, off, h265)
        if (h265) t in 16..21 else t == 5
    }
}

/** DAAP / DMAP 元数据（RAOP SET_PARAMETER 的 `mlit` 列表）里我们关心的字段。 */
data class TrackInfo(val title: String?, val artist: String?, val album: String?)

object DmapMetadata {
    fun parse(data: ByteArray): TrackInfo? {
        if (data.size < 8 || tag(data, 0) != "mlit") return null
        val end = (8L + length(data, 4).toUInt().toLong()).coerceAtMost(data.size.toLong()).toInt()
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var p = 8
        while (p + 8 <= end) {
            val t = tag(data, p)
            val len = length(data, p + 4)
            // 长度来自网络：用 Long 比较，避免 p + 8 + len 整数溢出
            if (len < 0 || p + 8L + len > end) break
            val value = { String(data, p + 8, len, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() } }
            when (t) {
                "minm" -> title = value()
                "asar" -> artist = value()
                "asal" -> album = value()
            }
            p += 8 + len
        }
        return if (title == null && artist == null && album == null) null else TrackInfo(title, artist, album)
    }

    private fun tag(d: ByteArray, at: Int) = String(d, at, 4, Charsets.US_ASCII)
    private fun length(d: ByteArray, at: Int): Int =
        ((d[at].toInt() and 0xFF) shl 24) or ((d[at + 1].toInt() and 0xFF) shl 16) or
            ((d[at + 2].toInt() and 0xFF) shl 8) or (d[at + 3].toInt() and 0xFF)
}

/**
 * 诊断日志脱敏（PRD F25：不记录推送网址）：UxPlay 日志里的 URL 与 IP 地址替换为占位符；
 * 导出日志会在界面上预览，因此把上游日志里的 “AirPlay” 字样替换为 “Apple-compat”（PRD 12）。
 */
object UxPlayLogRedactor {
    private val url = Regex("""\b(?:https?|rtsp|mlhls|nfhls)://\S+""", RegexOption.IGNORE_CASE)
    private val ipv4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
    private val trademark = Regex("""air\s?play""", RegexOption.IGNORE_CASE)

    fun redact(message: String): String =
        message.replace(url, "<url>").replace(ipv4, "<ip>").replace(trademark, "Apple-compat")
}

/** UxPlay 需要一个稳定的 6 字节“硬件地址”作为设备标识：随机生成，置为本地管理的单播地址（不读取真实 MAC）。 */
object AppleDeviceAddress {
    fun normalize(random: ByteArray): ByteArray {
        require(random.size == 6) { "hardware address must be 6 bytes" }
        return random.copyOf().also { it[0] = ((it[0].toInt() and 0xFC) or 0x02).toByte() }
    }

    fun isValid(bytes: ByteArray?): Boolean = bytes != null && bytes.size == 6 && (bytes[0].toInt() and 0x03) == 0x02
}

/** nativeStart 的 flags 位（与 bridge/uxplay_jni.c 对应）。 */
object UxPlayFlags {
    const val REQUIRE_PIN = 1
    const val HLS = 1 shl 1
    const val H265 = 1 shl 2

    fun of(requirePin: Boolean, hls: Boolean, h265: Boolean): Int =
        (if (requirePin) REQUIRE_PIN else 0) or (if (hls) HLS else 0) or (if (h265) H265 else 0)
}

/** nativeStart 的返回码（与 bridge/uxplay_jni.c 对应）。 */
object UxPlayStartError {
    fun describe(rc: Int): String = when (rc) {
        0 -> "ok"
        -1 -> "mDNS 初始化失败"
        -2, -3 -> "协议栈初始化失败"
        -4 -> "无法监听端口"
        -5 -> "mDNS 注册失败"
        else -> "未知错误 $rc"
    }
}
