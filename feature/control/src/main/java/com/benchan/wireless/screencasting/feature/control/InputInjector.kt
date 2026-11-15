package com.benchan.wireless.screencasting.feature.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.control.GestureCommand
import com.benchan.wireless.screencasting.core.control.PointD
import com.benchan.wireless.screencasting.core.control.TimedPoint
import com.benchan.wireless.screencasting.core.protocol.control.RemoteKey

/**
 * 输入注入器（技术方案“反向控制”1 节）。A 期唯一实现是无障碍服务 [A11yGestureInjector]；
 * 预留接口以便将来评估 Shizuku / adb 的 InputManager 注入作为可选增强（不是必需条件）。
 */
interface InputInjector {
    /** 执行一条手势命令（屏幕像素坐标）。线程安全：内部切到注入线程。[GestureCommand.Abort] 立即生效（不排队）。 */
    fun execute(command: GestureCommand)

    /** 全局动作（返回 / 主页 / 最近任务 / 方向键 13+ / 播放暂停 16+）；调用方已按白名单与能力过滤。返回系统是否接受。 */
    fun global(key: RemoteKey): Boolean

    /** 把文字提交到当前输入框（Android 13+）。返回 null = 成功，否则为拒绝原因（SECURE_FIELD / NO_EDITOR / UNSUPPORTED）。 */
    fun text(text: String): com.benchan.wireless.screencasting.core.protocol.control.RejectReason?

    /** 编辑键 ENTER / DEL（Android 13+）。返回 null = 成功，否则为拒绝原因。 */
    fun editorKey(key: RemoteKey): com.benchan.wireless.screencasting.core.protocol.control.RejectReason?

    /** 丢弃排队中的命令（正在执行的一段由系统自然结束）。 */
    fun clear()

    /** 注入被系统取消（通常是发送端本机有人触摸屏幕）。在注入线程回调；本注入器自己发起的取消不回调。 */
    var onCancelled: (() -> Unit)?
}

/**
 * 基于 AccessibilityService.dispatchGesture 的注入器。
 *
 * - 续接段（continueStroke + willContinue）必须等上一段 onCompleted 后再发：同时发会把上一段取消。
 *   等待期间积压的续接段合并为一段（路径相连、时长相加、上限 300 ms）。
 * - 一段被取消（本机有人触摸、系统中断）后，丢弃同一手势剩余的续接段，直到下一次按下 / 点击。
 * - **中止（用户决策 D5）**：不在最后位置抬起（会变成点击 / 长按），而是立即派发一个新的极短手势——
 *   系统收到新的非续接手势时会给进行中的笔画发 ACTION_CANCEL。这个新手势落在本应用自己的透明“吸收窗口”上
 *   （[sinkPoint]，无障碍覆盖层，只在投屏会话期间存在），不会点到任何应用。
 * - 坐标夹到 ≥0（StrokeDescription 不接受负坐标的路径）。
 *
 * 【需真机验证】continueStroke 在各 ROM 上的稳定性、willContinue 结束后指针保持按下的时长、
 * 新手势取消进行中笔画时目标应用收到的是 ACTION_CANCEL。
 */
internal class A11yGestureInjector(
    private val service: AccessibilityService,
    private val handler: Handler,
    private val sinkPoint: () -> PointD?,
) : InputInjector {
    private val queue = ArrayDeque<GestureCommand>()
    private var current: GestureDescription? = null
    private var lastStroke: GestureDescription.StrokeDescription? = null
    private var broken = false

    /** 本注入器主动取消的手势：它们的 onCancelled 不当作“本机有人触摸”。 */
    private val selfCancelled = HashSet<GestureDescription>()

    // —— 追踪计数（日志只记段类型 / 结果 / 计数，不记坐标）——
    private var gestureNo = 0
    private var segDispatched = 0
    private var segMerged = 0
    private var segDropped = 0
    private var sysCancelled = 0
    private var rejected = 0
    private val kinds = HashMap<GestureDescription, String>()

    private fun startGestureTrace() {
        gestureNo++
        segDispatched = 0
        segMerged = 0
        segDropped = 0
        sysCancelled = 0
        rejected = 0
    }

    private fun endGestureTrace(result: String) {
        WscLog.i(TAG, "gesture #$gestureNo $result: dispatched=$segDispatched merged=$segMerged dropped=$segDropped sysCancelled=$sysCancelled rejected=$rejected")
    }

    override var onCancelled: (() -> Unit)? = null

    override fun execute(command: GestureCommand) {
        handler.post {
            if (command is GestureCommand.Abort) {
                abortNow()
            } else {
                queue.addLast(command)
                pump()
            }
        }
    }

    override fun clear() {
        handler.post { queue.clear() }
    }

    override fun global(key: RemoteKey): Boolean {
        val action = when (key) {
            RemoteKey.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            RemoteKey.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            RemoteKey.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            in RemoteKey.DPAD -> if (android.os.Build.VERSION.SDK_INT >= 33) dpadAction(key) else return false
            RemoteKey.PLAY_PAUSE -> if (android.os.Build.VERSION.SDK_INT >= 36) AccessibilityService.GLOBAL_ACTION_MEDIA_PLAY_PAUSE else return false
            else -> return false
        }
        return service.performGlobalAction(action)
    }

    @androidx.annotation.RequiresApi(33)
    private fun dpadAction(key: RemoteKey): Int = when (key) {
        RemoteKey.DPAD_UP -> AccessibilityService.GLOBAL_ACTION_DPAD_UP
        RemoteKey.DPAD_DOWN -> AccessibilityService.GLOBAL_ACTION_DPAD_DOWN
        RemoteKey.DPAD_LEFT -> AccessibilityService.GLOBAL_ACTION_DPAD_LEFT
        RemoteKey.DPAD_RIGHT -> AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT
        else -> AccessibilityService.GLOBAL_ACTION_DPAD_CENTER
    }

    override fun text(text: String): com.benchan.wireless.screencasting.core.protocol.control.RejectReason? {
        if (android.os.Build.VERSION.SDK_INT < 33) return com.benchan.wireless.screencasting.core.protocol.control.RejectReason.UNSUPPORTED
        return A11yText.commit(service, text)
    }

    override fun editorKey(key: RemoteKey): com.benchan.wireless.screencasting.core.protocol.control.RejectReason? {
        if (android.os.Build.VERSION.SDK_INT < 33) return com.benchan.wireless.screencasting.core.protocol.control.RejectReason.UNSUPPORTED
        return A11yText.key(service, key)
    }

    /** 取消进行中 / 按住中的笔画，不产生点击。 */
    private fun abortNow() {
        // 丢弃当前手势剩余的续接段（下一次按下 / 点击保留）
        while ((queue.firstOrNull() as? GestureCommand.Segment)?.first == false) queue.removeFirst()
        val held = lastStroke != null || current != null
        lastStroke = null
        broken = false
        if (!held) {
            WscLog.d(TAG, "abort: nothing in progress")
            return
        }
        current?.let { selfCancelled += it }
        val p = sinkPoint() ?: FALLBACK_SINK
        val cancel = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(pathOf(listOf(TimedPoint(p.x, p.y, 0))), 0, 1))
            .build()
        selfCancelled += cancel
        current = cancel
        kinds[cancel] = "abort"
        if (!service.dispatchGesture(cancel, callback, handler)) {
            selfCancelled -= cancel
            current = null
            WscLog.w(TAG, "abort gesture rejected")
        }
        endGestureTrace("aborted (sink=${sinkPoint() != null})")
    }

    private fun pump() {
        if (current != null) return
        while (queue.isNotEmpty()) {
            val cmd = mergeNext() ?: continue
            if (dispatch(cmd)) return
        }
    }

    /** 取出下一条命令；连续的续接段合并。被取消手势的剩余续接段直接丢弃（返回 null）。 */
    private fun mergeNext(): GestureCommand? {
        val head = queue.removeFirst()
        if (head is GestureCommand.Segment && !head.first) {
            if (broken || lastStroke == null) {
                segDropped++
                if (head.last) {
                    broken = false
                    endGestureTrace("dropped tail (broken=${lastStroke == null})")
                }
                return null
            }
            var merged: GestureCommand.Segment = head
            while (!merged.last) {
                val next = queue.firstOrNull() as? GestureCommand.Segment ?: break
                if (next.first) break
                queue.removeFirst()
                segMerged++
                merged = GestureCommand.Segment(
                    points = merged.points + next.points.drop(1),
                    durationMs = (merged.durationMs + next.durationMs).coerceAtMost(MAX_MERGED_MS),
                    first = false,
                    last = next.last,
                )
            }
            return merged
        }
        broken = false
        if (head is GestureCommand.Tap || (head is GestureCommand.Segment && head.first)) startGestureTrace()
        return head
    }

    private fun dispatch(cmd: GestureCommand): Boolean {
        if (cmd is GestureCommand.Multi) return dispatchMulti(cmd)
        val stroke = runCatching {
            when (cmd) {
                is GestureCommand.Tap -> {
                    lastStroke = null
                    GestureDescription.StrokeDescription(pathOf(listOf(TimedPoint(cmd.x, cmd.y, 0))), 0, cmd.durationMs)
                }
                is GestureCommand.Segment -> {
                    val path = pathOf(cmd.points)
                    val d = cmd.durationMs.coerceAtLeast(1)
                    val s = if (cmd.first) {
                        GestureDescription.StrokeDescription(path, 0, d, !cmd.last)
                    } else {
                        lastStroke!!.continueStroke(path, 0, d, !cmd.last)
                    }
                    lastStroke = if (cmd.last) null else s
                    s
                }
                GestureCommand.Abort -> return false
                is GestureCommand.Multi -> return false
            }
        }.getOrElse {
            WscLog.w(TAG, "invalid stroke: ${it.javaClass.simpleName}")
            markBroken()
            return false
        }
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        current = gesture
        val kind = when (cmd) {
            is GestureCommand.Tap -> "tap"
            is GestureCommand.Segment -> when {
                cmd.first && cmd.last -> "stroke"
                cmd.first -> "down"
                cmd.last -> "up"
                else -> "move"
            }
            GestureCommand.Abort -> "abort"
            is GestureCommand.Multi -> "multi"
        }
        kinds[gesture] = kind
        val accepted = service.dispatchGesture(gesture, callback, handler)
        segDispatched++
        WscLog.d(TAG, "#$gestureNo dispatch $kind ok=$accepted")
        if (!accepted) {
            rejected++
            kinds.remove(gesture)
            current = null
            markBroken()
            endGestureTrace("rejected at $kind")
        }
        return accepted
    }

    /** 多指一次性手势（PINCH 宏）：所有手指同时开始、同时结束，不使用续接。 */
    private fun dispatchMulti(cmd: GestureCommand.Multi): Boolean {
        startGestureTrace()
        lastStroke = null
        val gesture = runCatching {
            val b = GestureDescription.Builder()
            cmd.strokes.take(GestureDescription.getMaxStrokeCount()).forEach { pts ->
                b.addStroke(GestureDescription.StrokeDescription(pathOf(pts), 0, cmd.durationMs.coerceAtLeast(1)))
            }
            b.build()
        }.getOrElse {
            WscLog.w(TAG, "invalid multi stroke: ${it.javaClass.simpleName}")
            return false
        }
        current = gesture
        kinds[gesture] = "multi"
        val ok = service.dispatchGesture(gesture, callback, handler)
        segDispatched++
        WscLog.d(TAG, "#$gestureNo dispatch multi(${cmd.strokes.size}) ok=$ok")
        if (!ok) {
            rejected++
            kinds.remove(gesture)
            current = null
            endGestureTrace("rejected at multi")
        }
        return ok
    }

    private fun markBroken() {
        if (lastStroke != null) broken = true
        lastStroke = null
    }

    private val callback = object : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            selfCancelled.remove(gestureDescription)
            val kind = kinds.remove(gestureDescription)
            if (gestureDescription === current) current = null
            if (kind == "tap" || kind == "stroke" || kind == "up" || kind == "multi") endGestureTrace("completed ($kind)")
            pump()
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            val mine = gestureDescription != null && selfCancelled.remove(gestureDescription)
            val kind = kinds.remove(gestureDescription)
            if (gestureDescription === current) current = null
            if (!mine) {
                sysCancelled++
                WscLog.i(TAG, "#$gestureNo system cancelled $kind (local touch or system interrupt)")
                markBroken()
                onCancelled?.invoke()
            } else {
                WscLog.d(TAG, "#$gestureNo self-cancelled $kind")
            }
            pump()
        }
    }

    private fun pathOf(points: List<TimedPoint>): Path = Path().apply {
        val first = points.first()
        moveTo(first.x.coerceAtLeast(0.0).toFloat(), first.y.coerceAtLeast(0.0).toFloat())
        for (p in points.drop(1)) lineTo(p.x.coerceAtLeast(0.0).toFloat(), p.y.coerceAtLeast(0.0).toFloat())
    }

    private companion object {
        const val TAG = "A11yInjector"
        const val MAX_MERGED_MS = 300L

        /** 吸收窗口不存在时的兜底：屏幕左上角（状态栏角落，绝大多数系统点按无反应）。 */
        val FALLBACK_SINK = PointD(1.0, 1.0)
    }
}
