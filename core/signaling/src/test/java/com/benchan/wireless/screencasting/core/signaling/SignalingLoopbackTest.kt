package com.benchan.wireless.screencasting.core.signaling

import com.benchan.wireless.screencasting.core.protocol.message.Bye
import com.benchan.wireless.screencasting.core.protocol.message.ByeReason
import com.benchan.wireless.screencasting.core.protocol.message.Hello
import com.benchan.wireless.screencasting.core.protocol.message.MessageType
import com.benchan.wireless.screencasting.core.security.identity.DeviceIdentity
import com.benchan.wireless.screencasting.core.security.identity.SelfSignedCertificates
import com.benchan.wireless.screencasting.core.security.tls.ServerPin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** JVM 上的 WSS 回环：Java-WebSocket 服务端 + OkHttp 客户端，双向 TLS 1.3 + 指纹钉扎。 */
class SignalingLoopbackTest {
    private fun identity(id: String): DeviceIdentity {
        val kp = SelfSignedCertificates.generateKeyPair()
        return DeviceIdentity(id, kp.private, SelfSignedCertificates.create(kp, id))
    }

    @Test
    fun helloRoundTripOverMutualTls() = runBlocking {
        val receiver = identity("receiver")
        val sender = identity("sender")
        val accepted = CompletableDeferred<SignalingConnection>()
        val server = WscServer(receiver, preferredPort = 0) { accepted.complete(it) }
        val port = server.start()
        try {
            val client = WscClient(sender).connect("127.0.0.1", port, ServerPin.Full(receiver.fingerprint))
            val serverSide = withTimeout(5_000) { accepted.await() }
            assertEquals(sender.fingerprint, serverSide.peerFingerprint)
            assertEquals(receiver.fingerprint, client.peerFingerprint)

            assertTrue(client.send(Hello(1, "sender", "手机", role = "sender"), "sid"))
            val got = withTimeout(5_000) { serverSide.events.filterIsInstance<SignalingEvent.Message>().first() }
            assertEquals(MessageType.HELLO, got.message.type)
            assertEquals("sid", got.message.sessionId)
            assertEquals("手机", (got.message.payload as Hello).name)

            serverSide.send(Bye(ByeReason.RECEIVER_EXIT), "sid")
            val bye = withTimeout(5_000) { client.events.filterIsInstance<SignalingEvent.Message>().first() }
            assertEquals(MessageType.BYE, bye.message.type)

            client.close("done")
            val closed = withTimeout(5_000) { serverSide.events.filterIsInstance<SignalingEvent.Closed>().first() }
            assertTrue(closed.byRemote)
        } finally {
            server.stop()
        }
    }

    @Test
    fun wrongPinIsRejected() = runBlocking {
        val receiver = identity("receiver")
        val server = WscServer(receiver, preferredPort = 0) {}
        val port = server.start()
        try {
            WscClient(identity("sender")).connect("127.0.0.1", port, ServerPin.Full(identity("evil").fingerprint))
            fail("should not connect")
        } catch (e: SignalingConnectException) {
            // expected
        } finally {
            server.stop()
        }
    }
}
