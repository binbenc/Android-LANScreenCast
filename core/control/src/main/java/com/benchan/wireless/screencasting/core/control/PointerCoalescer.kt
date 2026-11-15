package com.benchan.wireless.screencasting.core.control

import com.benchan.wireless.screencasting.core.protocol.control.InputCodec
import com.benchan.wireless.screencasting.core.protocol.control.InputMessage
import com.benchan.wireless.screencasting.core.protocol.control.InputSample
import com.benchan.wireless.screencasting.core.protocol.control.PointerAction
import com.benchan.wireless.screencasting.core.protocol.control.RemoteKey

/**
 * 接收端输入合并器（PROTOCOL.md 4.2）：把触摸屏的原始事件（可能 120 Hz）变成 wsc-input 消息。
 *
 * - DOWN / UP / CANCEL 立即发送；MOVE 至多每 [intervalMs] 发一条，期间的采样放进 `h` 历史（最多 32 个）。
 * - 按住不动时每 [keepAliveMs] 发一条保活 MOVE（发送端 1 秒收不到事件会自动抬起）。
 * - 单指（A 期）：DOWN 时若上一个手势未结束，先发 CANCEL。
 * - 序号对本通道的所有消息（P / K）单调递增。
 *
 * 不是线程安全的：调用方单线程驱动；时间由 [clock] 提供（毫秒，单调）。
 */
class PointerCoalescer(
    private val clock: () -> Long,
    private val intervalMs: Long = 16,
    private val keepAliveMs: Long = 250,
) {
    private var seq = 0L
    private var downAt = -1L
    private var gen = 0
    private var lastSentAt = 0L
    private var last: InputSample? = null
    private val buffered = ArrayList<InputSample>()

    /** 是否有手势进行中。 */
    val active: Boolean get() = downAt >= 0

    /** 本通道的下一个序号（也用于 K 消息）。 */
    fun nextSeq(): Long {
        seq = (seq + 1) and 0xFFFF_FFFFL
        return seq
    }

    /** 按下。返回要发送的消息（可能先有一条取消上一个手势的 CANCEL）。 */
    fun down(nx: Double, ny: Double, generation: Int): List<InputMessage> {
        val out = ArrayList<InputMessage>(2)
        cancel()?.let(out::add)
        val now = clock()
        downAt = now
        gen = generation
        buffered.clear()
        val s = InputSample(InputCodec.round4(nx), InputCodec.round4(ny), 0)
        last = s
        lastSentAt = now
        out += pointer(PointerAction.DOWN, s, null)
        return out
    }

    /** 移动；未到发送间隔时缓冲并返回 null。 */
    fun move(nx: Double, ny: Double): InputMessage? {
        if (!active) return null
        val now = clock()
        val s = InputSample(InputCodec.round4(nx), InputCodec.round4(ny), now - downAt)
        if (s.x == last?.x && s.y == last?.y) return tick()
        buffered += s
        last = s
        return if (now - lastSentAt >= intervalMs) flushMove(now) else null
    }

    /** 抬起：缓冲的采样随 UP 一起发出。 */
    fun up(nx: Double, ny: Double): InputMessage? {
        if (!active) return null
        val now = clock()
        val s = InputSample(InputCodec.round4(nx), InputCodec.round4(ny), now - downAt)
        val history = buffered.toList()
        buffered.clear()
        downAt = -1
        return pointer(PointerAction.UP, s, history)
    }

    /** 取消当前手势（手指滑进黑边 / 系统取消 / 控制结束）。 */
    fun cancel(): InputMessage? {
        if (!active) return null
        val now = clock()
        val l = last ?: return null
        val dt = (now - downAt).coerceAtLeast(l.dt)
        buffered.clear()
        downAt = -1
        return pointer(PointerAction.CANCEL, l.copy(dt = dt), null)
    }

    /** 定时调用：到时间就发出缓冲的移动；按住不动则发保活。 */
    fun tick(): InputMessage? {
        if (!active) return null
        val now = clock()
        return when {
            buffered.isNotEmpty() && now - lastSentAt >= intervalMs -> flushMove(now)
            buffered.isEmpty() && now - lastSentAt >= keepAliveMs -> {
                val l = last ?: return null
                lastSentAt = now
                pointer(PointerAction.MOVE, l.copy(dt = now - downAt), null)
            }
            else -> null
        }
    }

    /** 全局动作 / 按键。 */
    fun key(k: RemoteKey): InputMessage = InputMessage.Key(nextSeq(), k)

    private fun flushMove(now: Long): InputMessage {
        val latest = buffered.removeAt(buffered.lastIndex)
        val history = buffered.toList()
        buffered.clear()
        lastSentAt = now
        return pointer(PointerAction.MOVE, latest, history)
    }

    private fun pointer(a: PointerAction, s: InputSample, history: List<InputSample>?): InputMessage.Pointer {
        val h = history?.takeLast(InputCodec.MAX_HISTORY)?.takeIf { it.isNotEmpty() }?.map { listOf(it.x, it.y, it.dt.toDouble()) }
        return InputMessage.Pointer(s = nextSeq(), g = gen, a = a, x = s.x, y = s.y, dt = s.dt, h = h)
    }
}
