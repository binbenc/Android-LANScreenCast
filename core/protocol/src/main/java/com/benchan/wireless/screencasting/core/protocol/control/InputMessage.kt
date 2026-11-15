package com.benchan.wireless.screencasting.core.protocol.control

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToLong

/**
 * 反向控制输入通道 `wsc-input` 上的消息（PROTOCOL.md 4.2）。JSON 以 "t" 判别；字段名尽量短（60 Hz 发送）。
 *
 * 前向兼容：DataChannel 上**未知的 t 一律忽略**（与信令通道把未知 t 当畸形消息不同），[InputCodec.decodeOrNull] 返回 null。
 */
@Serializable
sealed interface InputMessage {
    /** 序号（uint32，单调递增；发送端丢弃不递增的事件，允许空洞）。 */
    val s: Long

    /**
     * 指针事件（收→发）。坐标是相对**解码帧**的 0..1 归一化值（去掉接收端黑边后），保留 4 位小数。
     *
     * @property g 几何代号：接收端最近一次与当前渲染帧宽高比一致的 `GEOM.g`。与发送端当前代号不符的事件被丢弃。
     * @property i 指针 id（A 期只用 0）。
     * @property dt 距本次手势 DOWN 的毫秒数（接收端单调时钟）；保留手势原始速度（fling）。
     * @property h 本条之前、尚未发送的历史采样 `[x, y, dt]`，从旧到新，最多 [InputCodec.MAX_HISTORY] 个。
     */
    @Serializable
    @SerialName("P")
    data class Pointer(
        override val s: Long,
        val g: Int,
        val a: PointerAction,
        val x: Double,
        val y: Double,
        val dt: Long,
        val i: Int = 0,
        val h: List<List<Double>>? = null,
    ) : InputMessage

    /** 按键 / 全局动作（收→发）。 */
    @Serializable
    @SerialName("K")
    data class Key(override val s: Long, val k: RemoteKey) : InputMessage

    /**
     * 滚动（收→发，B 期）：在归一化位置 ([x], [y]) 滚动 [dy] 个“内容高度”（-1..1，不为 0）。
     * [dy] > 0 = 向下滚动（看下面的内容，手指上滑）。鼠标滚轮每格 0.08，接收端把 50 ms 内的多格合并；TV 光标顶 / 底边继续按每次 ±1/3。
     */
    @Serializable
    @SerialName("W")
    data class Wheel(override val s: Long, val g: Int, val x: Double, val y: Double, val dy: Double) : InputMessage

    /** 文字（收→发，B 期，发送端 Android 13+）：提交到发送端当前输入框；密码框拒绝（IREJ SECURE_FIELD）。 */
    @Serializable
    @SerialName("TXT")
    data class Text(override val s: Long, val text: String) : InputMessage

    /**
     * 双指缩放（收→发，C 期）：一次完整的捏合，发送端一次性合成两根手指的手势（PINCH 宏）。
     * [x] / [y] 为两指中点（归一化）；[from] / [to] 为起止两指间距（相对内容宽度，0..2）；[ang] 为两指连线角度（弧度，缺省 0 = 水平）；
     * [dur] 为手势时长（毫秒，50..1000）。[to] > [from] = 放大。
     */
    @Serializable
    @SerialName("PINCH")
    data class Pinch(
        override val s: Long,
        val g: Int,
        val x: Double,
        val y: Double,
        val from: Double,
        val to: Double,
        val dur: Long,
        val ang: Double = 0.0,
    ) : InputMessage

    /** 发送端丢弃了序号为 [s] 的输入（发→收，限速）。 */
    @Serializable
    @SerialName("IREJ")
    data class Reject(override val s: Long, val why: RejectReason) : InputMessage
}

/** 一个历史采样。 */
data class InputSample(val x: Double, val y: Double, val dt: Long)

/** [InputMessage.Pointer.h] 解码为采样列表（已通过 [InputCodec] 校验）。 */
val InputMessage.Pointer.history: List<InputSample>
    get() = h.orEmpty().map { InputSample(it[0], it[1], it[2].toLong()) }

object InputCodec {
    /** 单条消息上限（字节）。 */
    const val MAX_BYTES = 4 * 1024

    /** 单条 P 最多携带的历史采样数。 */
    const val MAX_HISTORY = 32

    /** 最多支持的指针 id（GestureDescription 最多 10 笔）。 */
    const val MAX_POINTER_ID = 9

    private const val MAX_SEQ = 0xFFFF_FFFFL

    /** 单条 TXT 的最大字符数（接收端更长的文本分多条发送）。 */
    const val MAX_TEXT_CHARS = 1000

    val json: Json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "t"
        encodeDefaults = false
        explicitNulls = false
    }

    fun encode(message: InputMessage): String = json.encodeToString(InputMessage.serializer(), message)

    /** 解码并校验；未知类型、畸形或越界返回 null（调用方计数后丢弃）。 */
    fun decodeOrNull(text: String): InputMessage? {
        if (text.length > MAX_BYTES) return null
        val m = runCatching { json.decodeFromString(InputMessage.serializer(), text) }.getOrNull() ?: return null
        return m.takeIf(::isValid)
    }

    fun isValid(m: InputMessage): Boolean {
        if (m.s !in 0..MAX_SEQ) return false
        return when (m) {
            is InputMessage.Pointer -> {
                m.g >= 0 && m.i in 0..MAX_POINTER_ID && inUnit(m.x) && inUnit(m.y) && m.dt >= 0 &&
                    (m.h?.let { h -> h.size <= MAX_HISTORY && h.all { it.size == 3 && inUnit(it[0]) && inUnit(it[1]) && it[2] >= 0 && it[2] <= m.dt } } ?: true)
            }
            is InputMessage.Key -> true
            is InputMessage.Reject -> true
            is InputMessage.Wheel -> m.g >= 0 && inUnit(m.x) && inUnit(m.y) && !m.dy.isNaN() && m.dy in -1.0..1.0 && m.dy != 0.0
            is InputMessage.Text -> m.text.isNotEmpty() && m.text.length <= MAX_TEXT_CHARS
            is InputMessage.Pinch -> m.g >= 0 && inUnit(m.x) && inUnit(m.y) && m.from > 0.0 && m.from <= 2.0 && m.to > 0.0 && m.to <= 2.0 &&
                m.from != m.to && m.dur in 50..1000 && !m.ang.isNaN() && m.ang in -Math.PI..Math.PI
        }
    }

    /** 归一化坐标保留 4 位小数（3200 px 长边上误差约 0.3 px），也让 JSON 更短。 */
    fun round4(v: Double): Double = (v.coerceIn(0.0, 1.0) * 10_000).roundToLong() / 10_000.0

    private fun inUnit(v: Double) = !v.isNaN() && v in 0.0..1.0
}
