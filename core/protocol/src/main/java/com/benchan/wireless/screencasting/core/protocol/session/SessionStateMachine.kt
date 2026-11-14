package com.benchan.wireless.screencasting.core.protocol.session

import com.benchan.wireless.screencasting.core.protocol.message.ErrorCode
import com.benchan.wireless.screencasting.core.protocol.message.MessageType
import com.benchan.wireless.screencasting.core.protocol.message.Role

/** 状态机输入事件。 */
sealed interface SessionEvent {
    data object TransportConnecting : SessionEvent
    data object TransportOpened : SessionEvent
    data object TransportLost : SessionEvent

    /** 发出消息（由本端逻辑产生）。 */
    data class Sent(val type: MessageType) : SessionEvent

    /** 收到消息。[trusted]/[confirmRequired]/[resumeOk] 仅对 HELLO 有意义。 */
    data class Received(
        val type: MessageType,
        val trusted: Boolean = false,
        val confirmRequired: Boolean = false,
        val resumeOk: Boolean = false,
        val errorCode: ErrorCode? = null,
    ) : SessionEvent

    /** 接收端本地完成认证（可信直连、用户允许 + PIN 校验成功、二维码校验成功）。 */
    data object LocallyAuthenticated : SessionEvent
    data object MediaConnected : SessionEvent
    data object MediaDisconnected : SessionEvent
    data object ReconnectExhausted : SessionEvent
    data object Closed : SessionEvent
}

/** 一次转移的结果。[rejected] 不为空时表示该事件在当前状态非法，调用方应回 ERROR 并断开。 */
data class Transition(
    val from: SessionState,
    val to: SessionState,
    val rejected: ErrorCode? = null,
) {
    val accepted: Boolean get() = rejected == null
}

/**
 * 纯函数式的 WSC 会话状态机（与 TS 端共享 protocol/vectors/state 测试向量）。
 * 只负责“当前状态下某个事件是否合法、合法时转到哪里”，不做 I/O。
 */
class SessionStateMachine(val role: Role, initial: SessionState = SessionState.DISCONNECTED) {
    var state: SessionState = initial
        private set

    /** 是否处于曾经认证过的会话中（用于断线后的 RESUMING 判断）。 */
    var wasAuthenticated: Boolean = false
        private set

    fun onEvent(event: SessionEvent): Transition {
        val from = state
        val result = next(from, event)
        if (result.accepted) {
            state = result.to
            if (state.isAuthenticated) wasAuthenticated = true
            if (state == SessionState.CLOSED) wasAuthenticated = false
        } else {
            // 认证类违规是致命的：直接关闭
            if (result.rejected?.fatal == true) state = SessionState.CLOSED
        }
        return result.copy(to = state)
    }

    private fun next(s: SessionState, e: SessionEvent): Transition {
        fun ok(to: SessionState) = Transition(s, to)
        fun reject(code: ErrorCode = ErrorCode.BAD_MESSAGE) = Transition(s, s, code)

        if (s == SessionState.CLOSED) {
            return if (e is SessionEvent.TransportConnecting && role == Role.SENDER) ok(SessionState.CONNECTING) else reject()
        }

        return when (e) {
            SessionEvent.Closed, SessionEvent.ReconnectExhausted -> ok(SessionState.CLOSED)

            SessionEvent.TransportConnecting -> when (s) {
                SessionState.DISCONNECTED -> ok(SessionState.CONNECTING)
                SessionState.RECONNECTING -> ok(SessionState.RECONNECTING)
                else -> reject()
            }

            SessionEvent.TransportOpened -> when (s) {
                SessionState.DISCONNECTED, SessionState.CONNECTING ->
                    ok(if (role == Role.SENDER) SessionState.CONNECTING else SessionState.AWAIT_HELLO)
                SessionState.RECONNECTING -> ok(SessionState.RECONNECTING)
                else -> reject()
            }

            SessionEvent.TransportLost -> when {
                // 已认证的会话：进入重连，保留采集会话（PRD 8.4）
                s == SessionState.STREAMING || s == SessionState.NEGOTIATING ||
                    s == SessionState.RESUMING || s == SessionState.RECONNECTING ||
                    (s == SessionState.AUTHENTICATED && wasAuthenticated && role == Role.SENDER) ->
                    ok(SessionState.RECONNECTING)
                else -> ok(SessionState.CLOSED)
            }

            is SessionEvent.Sent -> onSent(s, e.type)
            is SessionEvent.Received -> onReceived(s, e)

            SessionEvent.LocallyAuthenticated -> when (s) {
                SessionState.PAIRING, SessionState.AWAIT_HELLO -> ok(SessionState.AUTHENTICATED)
                else -> reject()
            }

            SessionEvent.MediaConnected -> when (s) {
                SessionState.NEGOTIATING, SessionState.STREAMING -> ok(SessionState.STREAMING)
                else -> reject()
            }

            SessionEvent.MediaDisconnected -> when (s) {
                // 只是 ICE 断开、信令仍在：回到 NEGOTIATING，等待 ICE restart
                SessionState.STREAMING -> ok(SessionState.NEGOTIATING)
                else -> ok(s)
            }
        }
    }

    private fun onSent(s: SessionState, type: MessageType): Transition {
        fun ok(to: SessionState) = Transition(s, to)
        fun reject() = Transition(s, s, ErrorCode.BAD_MESSAGE)
        return when (type) {
            MessageType.HELLO -> when {
                role == Role.SENDER && s == SessionState.CONNECTING -> ok(SessionState.HELLO_SENT)
                role == Role.SENDER && s == SessionState.RECONNECTING -> ok(SessionState.RESUMING)
                // 接收端回复 HELLO 时状态由收到的 HELLO 决定，这里不变
                role == Role.RECEIVER && (s == SessionState.PAIRING || s == SessionState.AUTHENTICATED ||
                    s == SessionState.NEGOTIATING || s == SessionState.STREAMING) -> ok(s)
                else -> reject()
            }
            MessageType.PAIR_REQ, MessageType.PAIR_PIN, MessageType.PAIR_CONFIRM, MessageType.PAIR_QR ->
                if (s == SessionState.PAIRING) ok(s) else reject()
            MessageType.PAIR_OK ->
                if (role == Role.RECEIVER && (s == SessionState.PAIRING || s == SessionState.AUTHENTICATED)) ok(SessionState.AUTHENTICATED) else reject()
            MessageType.OFFER -> if (role == Role.SENDER && s.isAuthenticated) ok(SessionState.NEGOTIATING) else Transition(s, s, ErrorCode.UNAUTHENTICATED)
            MessageType.ANSWER -> if (role == Role.RECEIVER && s.isAuthenticated) ok(if (s == SessionState.STREAMING) s else SessionState.NEGOTIATING) else Transition(s, s, ErrorCode.UNAUTHENTICATED)
            MessageType.ICE -> if (s.isAuthenticated) ok(s) else Transition(s, s, ErrorCode.UNAUTHENTICATED)
            MessageType.BYE -> ok(SessionState.CLOSED)
            MessageType.ERROR -> ok(s)
        }
    }

    private fun onReceived(s: SessionState, e: SessionEvent.Received): Transition {
        fun ok(to: SessionState) = Transition(s, to)
        fun reject(code: ErrorCode = ErrorCode.BAD_MESSAGE) = Transition(s, s, code)
        return when (e.type) {
            MessageType.HELLO -> when {
                role == Role.SENDER && s == SessionState.HELLO_SENT ->
                    ok(if (e.trusted && !e.confirmRequired) SessionState.AUTHENTICATED else SessionState.PAIRING)
                role == Role.SENDER && s == SessionState.RESUMING ->
                    if (e.resumeOk) ok(SessionState.NEGOTIATING) else Transition(s, SessionState.CLOSED, null)
                role == Role.RECEIVER && s == SessionState.AWAIT_HELLO ->
                    ok(if (e.trusted && !e.confirmRequired) SessionState.AUTHENTICATED else SessionState.PAIRING)
                else -> reject()
            }
            // pB 由接收端发给发送端，其余配对消息只能由发送端发给接收端
            MessageType.PAIR_PIN -> if (s == SessionState.PAIRING) ok(s) else reject()
            MessageType.PAIR_REQ, MessageType.PAIR_CONFIRM, MessageType.PAIR_QR ->
                if (role == Role.RECEIVER && s == SessionState.PAIRING) ok(s) else reject()
            MessageType.PAIR_OK ->
                if (role == Role.SENDER && s == SessionState.PAIRING) ok(SessionState.AUTHENTICATED) else reject()
            // 认证前收到 SDP / ICE：致命错误，断开（技术方案 3.5.5）
            MessageType.OFFER ->
                if (role == Role.RECEIVER && s.isAuthenticated) ok(SessionState.NEGOTIATING) else reject(ErrorCode.UNAUTHENTICATED)
            MessageType.ANSWER ->
                if (role == Role.SENDER && s.isAuthenticated) ok(if (s == SessionState.STREAMING) s else SessionState.NEGOTIATING) else reject(ErrorCode.UNAUTHENTICATED)
            MessageType.ICE ->
                if (s.isAuthenticated) ok(s) else reject(ErrorCode.UNAUTHENTICATED)
            MessageType.BYE -> ok(SessionState.CLOSED)
            MessageType.ERROR ->
                if (e.errorCode?.fatal != false) ok(SessionState.CLOSED) else ok(s)
        }
    }
}
