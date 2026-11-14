package com.benchan.wireless.screencasting.core.security

import com.benchan.wireless.screencasting.core.security.identity.DeviceIdentity
import com.benchan.wireless.screencasting.core.security.identity.Fingerprint
import com.benchan.wireless.screencasting.core.security.identity.IdentityStore
import com.benchan.wireless.screencasting.core.security.identity.PlainKeyWrapper
import com.benchan.wireless.screencasting.core.security.identity.SelfSignedCertificates
import com.benchan.wireless.screencasting.core.security.pairing.ReceiverPairing
import com.benchan.wireless.screencasting.core.security.pairing.SenderPairing
import com.benchan.wireless.screencasting.core.security.tls.PinningTrustManager
import com.benchan.wireless.screencasting.core.security.tls.ServerPin
import com.benchan.wireless.screencasting.core.security.tls.TlsContexts
import com.benchan.wireless.screencasting.core.security.trust.TrustedDeviceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

class SecurityTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun newIdentity(id: String): DeviceIdentity {
        val kp = SelfSignedCertificates.generateKeyPair()
        return DeviceIdentity(id, kp.private, SelfSignedCertificates.create(kp, id))
    }

    @Test
    fun identityIsPersistedAndStable() {
        val file = tmp.newFolder().resolve("id.json")
        val a = IdentityStore(file, PlainKeyWrapper).loadOrCreate()
        val b = IdentityStore(file, PlainKeyWrapper).loadOrCreate()
        assertEquals(a.deviceId, b.deviceId)
        assertEquals(a.fingerprint, b.fingerprint)
        assertTrue(a.certificate.notAfter.time - System.currentTimeMillis() > 19L * 365 * 24 * 3600 * 1000)
    }

    @Test
    fun fingerprintPrefixAndHex() {
        val id = newIdentity("x")
        val fp = id.fingerprint
        assertEquals(64, fp.hex.length)
        val prefix = Fingerprint.prefixFromTxt(fp.txtPrefix)!!
        assertEquals(8, prefix.size)
        assertTrue(fp.matchesPrefix(prefix))
        assertFalse(newIdentity("y").fingerprint.matchesPrefix(prefix))
        assertEquals(fp, Fingerprint.fromHex(fp.hex))
    }

    /** 真实 TLS 1.3 双向认证握手：指纹钉扎成功 / 失败、双方都能读到对端指纹。 */
    @Test
    fun mutualTlsWithPinning() {
        val server = newIdentity("receiver")
        val client = newIdentity("sender")
        val ctx = TlsContexts.server(server)
        val ss = ctx.serverSocketFactory.createServerSocket(0, 5, InetAddress.getLoopbackAddress()) as SSLServerSocket
        ss.sslParameters = TlsContexts.serverParameters(ctx)
        val pool = Executors.newSingleThreadExecutor()
        val serverSeen = pool.submit<Fingerprint?> {
            (ss.accept() as SSLSocket).use { s ->
                s.startHandshake()
                assertEquals("TLSv1.3", s.session.protocol)
                TlsContexts.peerFingerprint(s.session)
            }
        }
        val tm = PinningTrustManager(ServerPin.Full(server.fingerprint))
        val cctx = TlsContexts.client(client, tm)
        (cctx.socketFactory.createSocket(InetAddress.getLoopbackAddress(), ss.localPort) as SSLSocket).use { s ->
            s.enabledProtocols = arrayOf("TLSv1.3")
            s.startHandshake()
        }
        assertEquals(client.fingerprint, serverSeen.get(10, TimeUnit.SECONDS))
        assertEquals(server.fingerprint, tm.observed)

        // 钉扎了错误指纹：握手失败
        val pool2 = Executors.newSingleThreadExecutor()
        pool2.submit { runCatching { (ss.accept() as SSLSocket).use { it.startHandshake() } } }
        val bad = TlsContexts.client(client, PinningTrustManager(ServerPin.Full(newIdentity("evil").fingerprint)))
        var failed = false
        try {
            (bad.socketFactory.createSocket(InetAddress.getLoopbackAddress(), ss.localPort) as SSLSocket).use { it.startHandshake() }
        } catch (_: SSLHandshakeException) {
            failed = true
        }
        assertTrue(failed)
        ss.close()
        pool.shutdownNow(); pool2.shutdownNow()
    }

    @Test
    fun pinPairingSucceedsAndDetectsMitm() {
        val sender = newIdentity("A")
        val receiver = newIdentity("B")
        val sid = "sid-1"
        val s = SenderPairing(sid, "4702", "A", "B", sender.fingerprint, receiver.fingerprint)
        val r = ReceiverPairing(sid, "4702", "A", "B", sender.fingerprint, receiver.fingerprint)
        r.onPeerPoint(s.publicPoint)
        val macA = s.onPeerPoint(r.publicPoint)
        val macB = r.verifySender(macA)
        assertNotNull(macB)
        assertTrue(s.verifyReceiver(macB))

        // 错误 PIN
        val r2 = ReceiverPairing(sid, "4703", "A", "B", sender.fingerprint, receiver.fingerprint)
        val s2 = SenderPairing(sid, "4702", "A", "B", sender.fingerprint, receiver.fingerprint)
        r2.onPeerPoint(s2.publicPoint)
        assertNull(r2.verifySender(s2.onPeerPoint(r2.publicPoint)))

        // 中间人：发送端看到的是攻击者的证书
        val mitm = newIdentity("M")
        val s3 = SenderPairing(sid, "4702", "A", "B", sender.fingerprint, mitm.fingerprint)
        val r3 = ReceiverPairing(sid, "4702", "A", "B", sender.fingerprint, receiver.fingerprint)
        r3.onPeerPoint(s3.publicPoint)
        assertNull(r3.verifySender(s3.onPeerPoint(r3.publicPoint)))
    }

    @Test
    fun trustedDeviceStore() {
        val store = TrustedDeviceStore(tmp.newFolder().resolve("t.json"))
        val fp = newIdentity("A").fingerprint
        assertFalse(store.isTrusted(fp))
        store.add("A", "小张的手机", fp, now = 1)
        assertTrue(store.isTrusted(fp))
        store.touch(fp, now = 5)
        assertEquals(5, store.findById("A")!!.lastSeenAt)
        store.remove("A")
        assertFalse(store.isTrusted(fp))
    }
}
