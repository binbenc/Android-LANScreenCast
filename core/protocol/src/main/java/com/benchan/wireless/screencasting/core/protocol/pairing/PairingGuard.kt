package com.benchan.wireless.screencasting.core.protocol.pairing

import com.benchan.wireless.screencasting.core.common.Clock

/**
 * 配对防暴力策略（技术方案 3.5.4、决策 #19、交互稿 Q8）：
 * - 同一个 PIN 最多错 [maxPinFailures] 次（默认 3），之后作废并锁定该设备 [lockoutMs]（默认 60 秒）；
 * - 每台设备 [requestWindowMs]（30 秒）内最多 [maxRequestsPerWindow]（5）次 PAIR_REQ。
 *
 * key 用发送端的来源 IP（未认证时 deviceId 可伪造，IP 至少需要真实在局域网内）。
 */
class PairingGuard(
    private val clock: Clock = Clock.System,
    private val maxPinFailures: Int = 3,
    private val lockoutMs: Long = 60_000,
    private val maxRequestsPerWindow: Int = 5,
    private val requestWindowMs: Long = 30_000,
) {
    private data class Entry(var failures: Int = 0, var lockedUntil: Long = 0, val requests: ArrayDeque<Long> = ArrayDeque())

    private val entries = HashMap<String, Entry>()

    @Synchronized
    fun isLocked(key: String): Boolean = (entries[key]?.lockedUntil ?: 0) > clock.nowMillis()

    @Synchronized
    fun remainingLockMs(key: String): Long = ((entries[key]?.lockedUntil ?: 0) - clock.nowMillis()).coerceAtLeast(0)

    /** 记录一次 PAIR_REQ，超过频率限制返回 false。 */
    @Synchronized
    fun tryRequest(key: String): Boolean {
        val now = clock.nowMillis()
        val e = entries.getOrPut(key) { Entry() }
        if (e.lockedUntil > now) return false
        while (e.requests.isNotEmpty() && now - e.requests.first() >= requestWindowMs) e.requests.removeFirst()
        if (e.requests.size >= maxRequestsPerWindow) return false
        e.requests.addLast(now)
        return true
    }

    /** 记录一次 PIN 错误。返回剩余可尝试次数；为 0 时表示 PIN 已作废、设备已锁定。 */
    @Synchronized
    fun recordFailure(key: String): Int {
        val e = entries.getOrPut(key) { Entry() }
        e.failures++
        if (e.failures >= maxPinFailures) {
            e.lockedUntil = clock.nowMillis() + lockoutMs
            e.failures = 0
            return 0
        }
        return maxPinFailures - e.failures
    }

    @Synchronized
    fun recordSuccess(key: String) {
        entries[key]?.let { it.failures = 0 }
    }
}

/**
 * 二维码随机数：一次有效，60 秒过期（技术方案 3.5.4）。过期或用过之后自动轮换，接收端待机页随之刷新二维码。
 */
class QrNonceManager(
    private val clock: Clock = Clock.System,
    private val ttlMs: Long = 60_000,
    private val generator: () -> ByteArray,
) {
    private var nonce: ByteArray = generator()
    private var issuedAt: Long = clock.nowMillis()

    @Synchronized
    fun current(): ByteArray {
        if (clock.nowMillis() - issuedAt >= ttlMs) rotate()
        return nonce.copyOf()
    }

    @Synchronized
    fun expiresAt(): Long = issuedAt + ttlMs

    /** 取出当前有效随机数并立即作废（一次有效）。 */
    @Synchronized
    fun consume(): ByteArray? {
        if (clock.nowMillis() - issuedAt >= ttlMs) {
            rotate()
            return null
        }
        val n = nonce
        rotate()
        return n
    }

    @Synchronized
    fun rotate() {
        nonce = generator()
        issuedAt = clock.nowMillis()
    }
}
