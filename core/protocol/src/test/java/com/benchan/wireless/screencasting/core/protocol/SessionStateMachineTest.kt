package com.benchan.wireless.screencasting.core.protocol

import com.benchan.wireless.screencasting.core.protocol.message.ErrorCode
import com.benchan.wireless.screencasting.core.protocol.message.MessageType
import com.benchan.wireless.screencasting.core.protocol.message.Role
import com.benchan.wireless.screencasting.core.protocol.session.SessionEvent
import com.benchan.wireless.screencasting.core.protocol.session.SessionState
import com.benchan.wireless.screencasting.core.protocol.session.SessionStateMachine
import com.benchan.wireless.screencasting.core.protocol.session.TrustPolicy
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateMachineTest {

    private fun parseEvent(s: String): SessionEvent {
        val parts = s.split(':')
        return when (parts[0]) {
            "open" -> SessionEvent.TransportOpened
            "lost" -> SessionEvent.TransportLost
            "connecting" -> SessionEvent.TransportConnecting
            "auth" -> SessionEvent.LocallyAuthenticated
            "media-up" -> SessionEvent.MediaConnected
            "media-down" -> SessionEvent.MediaDisconnected
            "exhausted" -> SessionEvent.ReconnectExhausted
            "sent" -> SessionEvent.Sent(MessageType.valueOf(parts[1]))
            "recv" -> {
                val flags = parts.drop(2).toSet()
                SessionEvent.Received(
                    MessageType.valueOf(parts[1]),
                    trusted = "trusted" in flags,
                    confirmRequired = "confirm" in flags,
                    resumeOk = "resumeOk" in flags,
                    errorCode = when {
                        "fatal" in flags -> ErrorCode.PAIR_REJECTED
                        "nonfatal" in flags -> ErrorCode.PIN_MISMATCH
                        else -> null
                    },
                )
            }
            else -> error("bad event $s")
        }
    }

    @Test
    fun sharedStateVectors() {
        val cases = Vectors.load("state/sequences.json")["cases"]!!.jsonArray.map { it.jsonObject }
        for (c in cases) {
            val name = c["name"]!!.jsonPrimitive.content
            val sm = SessionStateMachine(Role.valueOf(c["role"]!!.jsonPrimitive.content))
            val events = c["events"]!!.jsonArray.map { it.jsonPrimitive.content }
            val expect = c["expect"]!!.jsonArray.map { it.jsonPrimitive.content }
            val rejected = c["rejected"]?.jsonArray?.map { it.jsonPrimitive.int }?.toSet() ?: emptySet()
            events.forEachIndexed { i, ev ->
                val t = sm.onEvent(parseEvent(ev))
                assertEquals("$name #$i ($ev)", expect[i], t.to.name)
                assertEquals("$name #$i rejected?", i in rejected, !t.accepted)
            }
        }
    }

    /** 表驱动：任何未认证状态下收到 OFFER/ANSWER/ICE 都必须被拒绝并断开（技术方案 3.5.5 约束 1）。 */
    @Test
    fun mediaMessagesRejectedBeforeAuthentication() {
        val unauthenticated = SessionState.entries.filterNot { it.isAuthenticated || it == SessionState.CLOSED }
        for (role in Role.entries) {
            for (state in unauthenticated) {
                for (type in listOf(MessageType.OFFER, MessageType.ANSWER, MessageType.ICE)) {
                    val sm = SessionStateMachine(role, state)
                    val t = sm.onEvent(SessionEvent.Received(type))
                    assertFalse("$role $state $type should be rejected", t.accepted)
                    assertEquals(ErrorCode.UNAUTHENTICATED, t.rejected)
                    assertEquals(SessionState.CLOSED, sm.state)
                }
            }
        }
    }

    /** 表驱动：所有（状态 × 收到的消息）组合都给出确定结果，不抛异常；BYE 总是关闭会话。 */
    @Test
    fun everyStateMessageCombinationIsDefined() {
        for (role in Role.entries) for (state in SessionState.entries) for (type in MessageType.entries) {
            val sm = SessionStateMachine(role, state)
            val t = sm.onEvent(SessionEvent.Received(type))
            if (type == MessageType.BYE && state != SessionState.CLOSED) assertEquals(SessionState.CLOSED, t.to)
            if (state == SessionState.CLOSED) assertFalse(t.accepted)
        }
    }

    @Test
    fun receiverOnlyAcceptsPairingMessagesWhilePairing() {
        val sm = SessionStateMachine(Role.RECEIVER, SessionState.AUTHENTICATED)
        assertFalse(sm.onEvent(SessionEvent.Received(MessageType.PAIR_PIN)).accepted)
        val sm2 = SessionStateMachine(Role.RECEIVER, SessionState.PAIRING)
        assertTrue(sm2.onEvent(SessionEvent.Received(MessageType.PAIR_PIN)).accepted)
        assertTrue(sm2.onEvent(SessionEvent.LocallyAuthenticated).accepted)
        assertEquals(SessionState.AUTHENTICATED, sm2.state)
    }

    @Test
    fun trustRequiresBothSides() {
        // 发送端移除了信任：即使接收端仍信任，也必须重新配对（PROTOCOL.md 2.1）
        assertFalse(TrustPolicy.receiverTrusts(locallyTrusted = true, senderHelloTrusted = false))
        assertTrue(TrustPolicy.receiverTrusts(locallyTrusted = true, senderHelloTrusted = true))
        // 旧版发送端没有该字段：沿用旧行为
        assertTrue(TrustPolicy.receiverTrusts(locallyTrusted = true, senderHelloTrusted = null))
        assertFalse(TrustPolicy.receiverTrusts(locallyTrusted = false, senderHelloTrusted = true))
        assertFalse(TrustPolicy.receiverTrusts(locallyTrusted = false, senderHelloTrusted = null))

        // 接收端据此进入 PAIRING，之后 PAIR_REQ 合法
        val sm = SessionStateMachine(Role.RECEIVER)
        sm.onEvent(SessionEvent.TransportOpened)
        val trusted = TrustPolicy.receiverTrusts(locallyTrusted = true, senderHelloTrusted = false)
        sm.onEvent(SessionEvent.Received(MessageType.HELLO, trusted = trusted))
        assertEquals(SessionState.PAIRING, sm.state)
        assertTrue(sm.onEvent(SessionEvent.Received(MessageType.PAIR_REQ)).accepted)
    }
}
