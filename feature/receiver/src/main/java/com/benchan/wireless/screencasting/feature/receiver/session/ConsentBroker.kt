package com.benchan.wireless.screencasting.feature.receiver.session

import com.benchan.wireless.screencasting.core.data.report.CastProtocol
import com.benchan.wireless.screencasting.core.protocol.WscProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** 用户对确认弹窗的决定。 */
enum class ConsentResult { ALLOWED, REJECTED, TIMEOUT }

/**
 * 接收端确认弹窗的唯一入口（R-09：同时只接一路，三种协议共用“新请求要确认”的规则）。
 * 同一时刻只显示一个请求；后来的请求排队等待前一个结束。
 */
@Singleton
class ConsentBroker @Inject constructor() {
    private val ids = AtomicLong(1)
    private val _current = MutableStateFlow<ConfirmRequest?>(null)
    val current: StateFlow<ConfirmRequest?> = _current.asStateFlow()
    private var pending: CompletableDeferred<ConsentResult>? = null
    private val lock = kotlinx.coroutines.sync.Mutex()

    suspend fun request(
        peerName: String,
        peerAddress: String,
        protocol: CastProtocol,
        pin: String?,
        firstTime: Boolean,
        replacing: Boolean,
        timeoutMs: Long = WscProtocol.PAIR_CONFIRM_TIMEOUT_MS,
    ): ConsentResult = lock.lockAndRun {
        val d = CompletableDeferred<ConsentResult>()
        pending = d
        _current.value = ConfirmRequest(ids.getAndIncrement(), peerName, peerAddress, protocol, pin, firstTime, replacing, System.currentTimeMillis() + timeoutMs)
        try {
            withTimeoutOrNull(timeoutMs) { d.await() } ?: ConsentResult.TIMEOUT
        } finally {
            _current.value = null
            pending = null
        }
    }

    /** 弹窗按钮回调。返回键 = 拒绝（交互稿 12）。 */
    fun respond(requestId: Long, allowed: Boolean) {
        if (_current.value?.id != requestId) return
        pending?.complete(if (allowed) ConsentResult.ALLOWED else ConsentResult.REJECTED)
    }

    /** 发起方已经放弃（断开 / PIN 用尽）时撤掉弹窗。 */
    fun dismiss(requestId: Long) {
        if (_current.value?.id == requestId) pending?.complete(ConsentResult.REJECTED)
    }

    private suspend inline fun <T> kotlinx.coroutines.sync.Mutex.lockAndRun(block: () -> T): T {
        lock()
        try {
            return block()
        } finally {
            unlock()
        }
    }
}
