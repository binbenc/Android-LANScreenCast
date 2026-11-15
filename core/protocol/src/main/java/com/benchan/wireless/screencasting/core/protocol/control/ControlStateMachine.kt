package com.benchan.wireless.screencasting.core.protocol.control

/** 发送端当前的控制状态快照（映射为 `CTL_STATE`）。 */
data class ControlSnapshot(
    val phase: ControlPhase,
    val reason: ControlReason? = null,
    val rid: Int? = null,
    /** PENDING：确认剩余毫秒；IDLE 冷却中：距离可以再次请求的毫秒数。 */
    val remainMs: Long? = null,
)

/**
 * 一次事件的结果。
 *
 * @property changed 状态（phase / reason）是否变化：变化时要向接收端发 `CTL_STATE` 并刷新界面。
 * @property reply 是否需要即使未变化也回一条 `CTL_STATE`（例如重复请求、冷却期请求）。
 * @property ended 授权或请求在本次事件中结束时的原因（用于通知“已收回控制：手机已锁屏”等）；未结束为 null。
 */
data class ControlTransition(
    val snapshot: ControlSnapshot,
    val changed: Boolean,
    val reply: Boolean = changed,
    val ended: ControlReason? = null,
)

/**
 * 反向控制的发送端授权状态机（PRD F23、决策 #16、PROTOCOL.md 6.2；向量 `vectors/state/control-sequences.json`）。
 *
 * 公理：① 每次会话都要发送端本人确认（可信设备也不跳过）；② 被控制期间随时可以收回；
 * ③ 权限只在本次会话内有效，锁屏 / 熄屏 / 会话结束立即失效且不自动恢复。
 * 同一会话内断线重连只是一个暂停原因（[ControlReason.RECONNECTING]），恢复后自动续上，不再确认。
 *
 * 冷却：被拒 / 超时 / 取消 / 收回 / 不可用回复后 10 秒；同一会话第二次被本人拒绝起 60 秒。
 *
 * 不是线程安全的：调用方在单线程上驱动。时间由 [clock] 提供（毫秒，单调）。
 */
class ControlStateMachine(
    private val clock: () -> Long,
    initialAvailability: ControlReason? = ControlReason.SETTING_OFF,
) {
    private sealed interface Core {
        data object Idle : Core
        data class Pending(val rid: Int, val deadline: Long) : Core
        data class Granted(val rid: Int) : Core
    }

    private var core: Core = Core.Idle
    private var availability: ControlReason? = initialAvailability
    private val pauses = LinkedHashSet<ControlReason>()
    private var cooldownUntil = 0L
    private var lastReason: ControlReason? = null
    private var lastRid: Int? = null
    private var denials = 0

    /** 是否允许注入：只有 ACTIVE。 */
    val canInject: Boolean get() = phase == ControlPhase.ACTIVE

    val phase: ControlPhase
        get() = when (core) {
            Core.Idle -> if (availability != null) ControlPhase.UNAVAILABLE else ControlPhase.IDLE
            is Core.Pending -> ControlPhase.PENDING
            is Core.Granted -> if (pauses.isEmpty()) ControlPhase.ACTIVE else ControlPhase.PAUSED
        }

    /** 当前暂停原因（按出现顺序）。 */
    val pauseReasons: Set<ControlReason> get() = pauses.toSet()

    fun snapshot(): ControlSnapshot {
        val now = clock()
        return when (val c = core) {
            Core.Idle -> {
                val p = phase
                val reason = if (p == ControlPhase.UNAVAILABLE) availability else lastReason
                val remain = (cooldownUntil - now).takeIf { it > 0 }
                ControlSnapshot(p, reason, lastRid, remain)
            }
            is Core.Pending -> ControlSnapshot(ControlPhase.PENDING, null, c.rid, (c.deadline - now).coerceAtLeast(0))
            is Core.Granted -> ControlSnapshot(phase, primaryPause(), c.rid)
        }
    }

    /** 可用性变化：null = 可用；否则为不可用原因（SETTING_OFF / SERVICE_OFF / AAPM）。不可用时结束已有的请求或授权。 */
    fun setAvailability(reason: ControlReason?): ControlTransition = mutate {
        availability = reason
        if (reason != null && core != Core.Idle) {
            endWith(reason, cooldown = false)
        } else {
            null
        }
    }

    /**
     * 接收端请求控制。
     *
     * @param deviceLocked 发送端此刻已锁屏：不弹确认页，直接回复 LOCKED（交互稿 R03）。
     */
    fun request(rid: Int, deviceLocked: Boolean = false): ControlTransition {
        val before = snapshot()
        val now = clock()
        when {
            core != Core.Idle -> return ControlTransition(before, changed = false, reply = true)
            availability != null -> {
                lastRid = rid
                // 不可用的回复同样冷却 10 秒，防止接收端反复请求（PM 走查 G2）
                if (now >= cooldownUntil) cooldownUntil = now + COOLDOWN_MS
                return ControlTransition(snapshot(), changed = false, reply = true)
            }
            deviceLocked -> {
                lastRid = rid
                lastReason = ControlReason.LOCKED
            }
            now < cooldownUntil -> {
                lastRid = rid
                lastReason = ControlReason.COOLDOWN
            }
            else -> {
                lastRid = rid
                lastReason = null
                core = Core.Pending(rid, now + CONFIRM_TIMEOUT_MS)
            }
        }
        val after = snapshot()
        return ControlTransition(after, changed = after.phase != before.phase || after.reason != before.reason, reply = true)
    }

    /** 发送端本人在确认页点“允许本次”。 */
    fun allow(): ControlTransition = mutate {
        val c = core
        if (c is Core.Pending && clock() < c.deadline) {
            core = Core.Granted(c.rid)
            lastReason = null
        }
        null
    }

    /** 发送端本人拒绝（确认页 / 通知 / 返回键）。 */
    fun deny(): ControlTransition = mutate {
        if (core is Core.Pending) endWith(ControlReason.USER_DENIED, cooldown = true) else null
    }

    /** 定时调用：确认超时。 */
    fun tick(): ControlTransition = mutate {
        val c = core
        if (c is Core.Pending && clock() >= c.deadline) endWith(ControlReason.TIMEOUT, cooldown = true) else null
    }

    /** 接收端发来 CTL_END：取消请求或退出控制。 */
    fun receiverEnd(): ControlTransition = mutate {
        when (core) {
            is Core.Pending -> endWith(ControlReason.CANCELLED, cooldown = true)
            is Core.Granted -> endWith(ControlReason.RECEIVER_END, cooldown = false)
            Core.Idle -> null
        }
    }

    /** 发送端收回控制（通知 / 边缘胶囊 / 投屏页 / 停止投屏）。请求中收回等同拒绝。 */
    fun revoke(): ControlTransition = mutate {
        when (core) {
            is Core.Pending -> endWith(ControlReason.USER_DENIED, cooldown = true)
            is Core.Granted -> endWith(ControlReason.REVOKED, cooldown = true)
            Core.Idle -> null
        }
    }

    /** 锁屏 / 熄屏：请求或授权立即结束，解锁后不自动恢复。 */
    fun locked(): ControlTransition = mutate {
        if (core != Core.Idle) endWith(ControlReason.LOCKED, cooldown = false) else null
    }

    /** 暂停条件变化（RECONNECTING / PROTECTED / APP_HIDDEN / WINDOWED）。条件在空闲时也记录，授权后立即生效。 */
    fun setPaused(reason: ControlReason, paused: Boolean): ControlTransition = mutate {
        if (paused) pauses += reason else pauses -= reason
        null
    }

    /** 会话结束。 */
    fun sessionEnded(): ControlTransition = mutate {
        pauses.clear()
        if (core != Core.Idle) endWith(ControlReason.SESSION_END, cooldown = false) else null
    }

    /** 同时有多个暂停原因时，按固定优先级显示一个（UX 走查 S1，状态机与 CTL_STATE 共用）。 */
    private fun primaryPause(): ControlReason? = pauses.minByOrNull { PAUSE_PRIORITY.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }

    private fun endWith(reason: ControlReason, cooldown: Boolean): ControlReason {
        core = Core.Idle
        lastReason = reason
        if (reason == ControlReason.USER_DENIED) denials++
        // 同一会话第二次被本人拒绝起冷却 60 秒（用户决策 D7），其余 10 秒
        if (cooldown) cooldownUntil = clock() + if (reason == ControlReason.USER_DENIED && denials >= 2) LONG_COOLDOWN_MS else COOLDOWN_MS
        return reason
    }

    private inline fun mutate(block: () -> ControlReason?): ControlTransition {
        val before = snapshot()
        val ended = block()
        val after = snapshot()
        val changed = after.phase != before.phase || after.reason != before.reason
        return ControlTransition(after, changed, ended = ended)
    }

    companion object {
        /** 确认页超时（交互稿 R03：30 秒无响应 = 拒绝）。 */
        const val CONFIRM_TIMEOUT_MS = 30_000L

        /** 被拒 / 超时 / 取消 / 收回后的冷却期（交互稿 R08：10 秒内不能再次请求）。 */
        const val COOLDOWN_MS = 10_000L

        /** 同一会话第二次及以后被本人拒绝后的冷却期（用户决策 D7）。 */
        const val LONG_COOLDOWN_MS = 60_000L

        /** 暂停原因显示优先级（高 → 低）：本应用 > 敏感界面 > 画面受保护 > 分屏 > 离开共享应用 > 重连。 */
        val PAUSE_PRIORITY: List<ControlReason> = listOf(
            ControlReason.OWN_APP,
            ControlReason.SENSITIVE_APP,
            ControlReason.PROTECTED,
            ControlReason.WINDOWED,
            ControlReason.APP_HIDDEN,
            ControlReason.RECONNECTING,
        )
    }
}
