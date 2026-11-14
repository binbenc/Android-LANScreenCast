package com.benchan.wireless.screencasting.core.protocol

import com.benchan.wireless.screencasting.core.common.Clock
import com.benchan.wireless.screencasting.core.protocol.pairing.CastCode
import com.benchan.wireless.screencasting.core.protocol.pairing.PairingGuard
import com.benchan.wireless.screencasting.core.protocol.pairing.QrNonceManager
import com.benchan.wireless.screencasting.core.protocol.pairing.QrPayload
import com.benchan.wireless.screencasting.core.protocol.pairing.QrProof
import com.benchan.wireless.screencasting.core.protocol.session.ReconnectBackoff
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {
    private class FakeClock(var now: Long = 0) : Clock {
        override fun nowMillis() = now
    }

    @Test
    fun castCodeEncodeDecode() {
        val code = CastCode.of(byteArrayOf(192.toByte(), 168.toByte(), 1, 128.toByte()), "43709")
        assertEquals("12843709", code.digits)
        assertEquals("128 43709", code.display)
        assertEquals(code, CastCode.parse("128 43709"))
        // 末段 < 100 时补零（交互稿 Q6）
        assertEquals("02300001", CastCode(23, "00001").digits)
        assertEquals(23, CastCode.parse("02300001")!!.lastOctet)
    }

    @Test
    fun castCodeRejectsBadInput() {
        assertNull(CastCode.parse("1234567"))
        assertNull(CastCode.parse("25543709"))
        assertNull(CastCode.parse("00043709"))
        assertNull(CastCode.parse("12a43709"))
    }

    @Test
    fun castCodeResolvesOnlySlash24() {
        val local = byteArrayOf(192.toByte(), 168.toByte(), 1, 23)
        assertEquals("192.168.1.128", CastCode.resolveHost(local, 24, "128"))
        assertNull(CastCode.resolveHost(local, 16, "128"))
        assertNull(CastCode.resolveHost(local, 24, "255"))
        assertFalse(CastCode.supportsNetwork(20))
        assertTrue(CastCode.supportsNetwork(24))
    }

    @Test
    fun qrPayloadRoundTrip() {
        val p = QrPayload("192.168.1.128", 8443, ByteArray(32) { it.toByte() }, ByteArray(16) { 7 }, name = "客厅电视")
        val parsed = QrPayload.parse(p.toUri())
        assertEquals(p, parsed)
        assertTrue(p.toUri().startsWith("wsc://192.168.1.128:8443?fp="))
        assertNull(QrPayload.parse("http://192.168.1.1:80?fp=x"))
        assertNull(QrPayload.parse("wsc://example.com:8443?fp=AA&n=BB"))
        assertNull(QrPayload.parse("wsc://192.168.1.2:8443?fp=AAAA&n=BBBB"))
    }

    @Test
    fun qrProofBindsFingerprintsAndSession() {
        val n = ByteArray(16) { 3 }
        val a = ByteArray(32) { 1 }
        val b = ByteArray(32) { 2 }
        val mac = QrProof.compute(n, a, b, "s")
        assertTrue(QrProof.verify(n, a, b, "s", mac))
        assertFalse(QrProof.verify(n, a, b, "t", mac))
        assertFalse(QrProof.verify(n, b, a, "s", mac))
    }

    @Test
    fun pinFailuresLockFor60Seconds() {
        val clock = FakeClock()
        val guard = PairingGuard(clock)
        assertEquals(2, guard.recordFailure("ip"))
        assertEquals(1, guard.recordFailure("ip"))
        assertEquals(0, guard.recordFailure("ip"))
        assertTrue(guard.isLocked("ip"))
        assertFalse(guard.tryRequest("ip"))
        clock.now += 59_999
        assertTrue(guard.isLocked("ip"))
        clock.now += 1
        assertFalse(guard.isLocked("ip"))
        assertTrue(guard.tryRequest("ip"))
        assertFalse(guard.isLocked("other"))
    }

    @Test
    fun pairRequestRateLimit() {
        val clock = FakeClock()
        val guard = PairingGuard(clock)
        repeat(5) { assertTrue(guard.tryRequest("ip")) }
        assertFalse(guard.tryRequest("ip"))
        clock.now += 30_000
        assertTrue(guard.tryRequest("ip"))
    }

    @Test
    fun qrNonceIsSingleUseAndExpires() {
        val clock = FakeClock()
        var counter = 0
        val m = QrNonceManager(clock) { ByteArray(16) { (counter++).toByte() } }
        val first = m.current()
        assertArrayEquals(first, m.consume())
        assertFalse(first.contentEquals(m.current()))
        clock.now += 60_000
        assertNull(m.consume())
        assertNotNull(m.current())
    }

    @Test
    fun backoffScheduleMatchesPrd() {
        val s = ReconnectBackoff.schedule()
        assertEquals(listOf(500L, 1000L, 2000L, 4000L), s.take(4))
        assertEquals(30_000L, s.sum())
        assertTrue(s.all { it <= 4000 })
    }
}
