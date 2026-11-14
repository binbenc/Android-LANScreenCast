package com.benchan.wireless.screencasting.core.protocol

import com.benchan.wireless.screencasting.core.common.Hex
import com.benchan.wireless.screencasting.core.protocol.crypto.Spake2
import com.benchan.wireless.screencasting.core.protocol.crypto.Spake2Exception
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class Spake2Test {

    @Test
    fun rfc9382AppendixBVectors() {
        val cases = Vectors.load("spake2/rfc9382-p256.json")["cases"]!!.jsonArray
        assertEquals(4, cases.size)
        for (c in cases.map { it.jsonObject }) {
            fun s(k: String) = c[k]!!.jsonPrimitive.content
            fun b(k: String) = Hex.decode(s(k))
            val idA = s("A").toByteArray()
            val idB = s("B").toByteArray()
            val w = BigInteger(s("w"), 16)
            val a = Spake2.forA(idA, idB, w, x = BigInteger(s("x"), 16))
            val bSide = Spake2.forB(idA, idB, w, y = BigInteger(s("y"), 16))
            assertArrayEquals("pA ${s("A")}/${s("B")}", b("pA"), a.publicValue)
            assertArrayEquals("pB", b("pB"), bSide.publicValue)

            val ka = a.finish(bSide.publicValue)
            val kb = bSide.finish(a.publicValue)
            assertArrayEquals("TT", b("TT"), ka.transcript)
            assertArrayEquals(b("Ke"), ka.ke)
            assertArrayEquals(b("KcA"), ka.kcA)
            assertArrayEquals(b("KcB"), ka.kcB)
            assertArrayEquals(b("confA"), ka.confirmA)
            assertArrayEquals(b("confB"), ka.confirmB)
            assertArrayEquals(ka.transcript, kb.transcript)
            assertTrue(kb.verifyPeer(ka.myConfirmation))
            assertTrue(ka.verifyPeer(kb.myConfirmation))
        }
    }

    @Test
    fun samePinAgreesAndWrongPinFails() {
        val sid = "session-1"
        val aad = Spake2.aad(ByteArray(32) { 1 }, ByteArray(32) { 2 }, sid)
        val a = Spake2.forA("A".toByteArray(), "B".toByteArray(), Spake2.passwordScalar("4702", sid), aad)
        val b = Spake2.forB("A".toByteArray(), "B".toByteArray(), Spake2.passwordScalar("4702", sid), aad)
        val ka = a.finish(b.publicValue)
        val kb = b.finish(a.publicValue)
        assertArrayEquals(ka.ke, kb.ke)
        assertTrue(kb.verifyPeer(ka.myConfirmation))

        val wrong = Spake2.forB("A".toByteArray(), "B".toByteArray(), Spake2.passwordScalar("4703", sid), aad)
        val kw = wrong.finish(a.publicValue)
        val ka2 = a.finish(wrong.publicValue)
        assertFalse(kw.verifyPeer(ka2.myConfirmation))
    }

    @Test
    fun differentFingerprintsInAadFailConfirmation() {
        // 中间人：两端看到的证书指纹集合不同 → 确认密钥不同
        val sid = "s"
        val w = Spake2.passwordScalar("12345", sid)
        val a = Spake2.forA(ByteArray(0), ByteArray(0), w, Spake2.aad(ByteArray(32) { 1 }, ByteArray(32) { 9 }, sid))
        val b = Spake2.forB(ByteArray(0), ByteArray(0), w, Spake2.aad(ByteArray(32) { 1 }, ByteArray(32) { 2 }, sid))
        val ka = a.finish(b.publicValue)
        val kb = b.finish(a.publicValue)
        assertFalse(kb.verifyPeer(ka.myConfirmation))
    }

    @Test(expected = Spake2Exception::class)
    fun invalidPeerPointRejected() {
        val a = Spake2.forA(ByteArray(0), ByteArray(0), BigInteger.TEN)
        a.finish(ByteArray(65).also { it[0] = 4 })
    }

    @Test
    fun passwordScalarDependsOnSession() {
        assertFalse(Spake2.passwordScalar("1234", "a") == Spake2.passwordScalar("1234", "b"))
        assertEquals(Spake2.passwordScalar("1234", "a"), Spake2.passwordScalar("1234", "a"))
    }
}
