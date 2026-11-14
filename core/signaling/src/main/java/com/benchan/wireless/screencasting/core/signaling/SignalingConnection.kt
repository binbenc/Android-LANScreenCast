package com.benchan.wireless.screencasting.core.signaling

import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.protocol.message.MessageIdGenerator
import com.benchan.wireless.screencasting.core.protocol.message.WscCodec
import com.benchan.wireless.screencasting.core.protocol.message.WscDecodeException
import com.benchan.wireless.screencasting.core.protocol.message.WscMessage
import com.benchan.wireless.screencasting.core.protocol.message.WscPayload
import com.benchan.wireless.screencasting.core.protocol.message.toMessage
import com.benchan.wireless.screencasting.core.security.identity.Fingerprint
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.atomic.AtomicBoolean

/** 信令连接上的事件。 */
sealed interface SignalingEvent {
    data class Message(val message: WscMessage) : SignalingEvent

    /** 收到无法解析的消息（畸形 JSON 等），调用方应回 ERROR{BAD_MESSAGE} 并断开。 */
    data class Malformed(val reason: String) : SignalingEvent

    data class Closed(val reason: String, val byRemote: Boolean) : SignalingEvent
}

/** 一条已完成 TLS 双向认证的 WSC 信令连接（WebSocket over TLS 1.3）。 */
interface SignalingConnection {
    /** 对端 IP。 */
    val remoteAddress: String

    /** 对端证书指纹（本次 TLS 会话实际看到的）。 */
    val peerFingerprint: Fingerprint

    /** 单消费者事件流。 */
    val events: Flow<SignalingEvent>

    val isOpen: Boolean

    fun send(payload: WscPayload, sessionId: String?): Boolean

    fun close(reason: String = "")
}

/** 公共实现：编解码、事件通道、消息 id。具体收发由子类实现。 */
internal abstract class BaseSignalingConnection(
    override val remoteAddress: String,
    override val peerFingerprint: Fingerprint,
) : SignalingConnection {
    private val channel = Channel<SignalingEvent>(Channel.UNLIMITED)
    private val ids = MessageIdGenerator()
    private val closed = AtomicBoolean(false)

    override val events: Flow<SignalingEvent> = channel.receiveAsFlow()
    override val isOpen: Boolean get() = !closed.get()

    protected abstract fun sendText(text: String): Boolean
    protected abstract fun closeTransport(reason: String)

    override fun send(payload: WscPayload, sessionId: String?): Boolean {
        if (closed.get()) return false
        val msg = payload.toMessage(ids.next(), sessionId)
        WscLog.d(TAG, "→ ${msg.type} #${msg.id}")
        return sendText(WscCodec.encode(msg))
    }

    override fun close(reason: String) {
        if (closed.compareAndSet(false, true)) {
            runCatching { closeTransport(reason) }
            channel.trySend(SignalingEvent.Closed(reason, byRemote = false))
            channel.close()
        }
    }

    fun deliver(text: String) {
        val event = try {
            val msg = WscCodec.decode(text)
            WscLog.d(TAG, "← ${msg.type} #${msg.id}")
            SignalingEvent.Message(msg)
        } catch (e: WscDecodeException) {
            WscLog.w(TAG, "malformed message from $remoteAddress: ${e.message}")
            SignalingEvent.Malformed(e.message ?: "malformed")
        }
        channel.trySend(event)
    }

    fun remoteClosed(reason: String) {
        if (closed.compareAndSet(false, true)) {
            channel.trySend(SignalingEvent.Closed(reason, byRemote = true))
            channel.close()
        }
    }

    private companion object {
        const val TAG = "Signaling"
    }
}
