package com.benchan.wireless.screencasting.core.protocol.crypto

import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.math.ec.ECPoint
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.SecureRandom

/** SPAKE2 校验失败（对方点非法、MAC 不匹配）。 */
class Spake2Exception(message: String) : Exception(message)

/**
 * SPAKE2，RFC 9382，套件 SPAKE2-P256-SHA256-HKDF-HMAC（技术方案 3.5.4）。
 *
 * - 角色 A = 发送端（输入 PIN），B = 接收端（显示 PIN）。
 * - 口令标量 w 由 [passwordScalar] 从 PIN + sessionId 导出。
 * - AAD = fpA || fpB || sessionId：把双方在本次 TLS 会话里实际看到的证书指纹绑进确认密钥（RFC 9382 第 4 节），
 *   中间人分别与两端握手时，两端看到的指纹不同，确认 MAC 必然失败。
 *
 * 用 RFC 9382 附录 B 的测试向量校验（见 protocol/vectors/spake2/rfc9382-p256.json）。
 */
class Spake2 private constructor(
    private val isA: Boolean,
    private val idA: ByteArray,
    private val idB: ByteArray,
    private val w: BigInteger,
    private val aad: ByteArray,
    private val secret: BigInteger,
) {
    /** 本端公开值（A 发送 pA，B 发送 pB），SEC1 未压缩编码。 */
    val publicValue: ByteArray = run {
        val blind = if (isA) M else N
        G.multiply(secret).add(blind.multiply(w)).normalize().getEncoded(false)
    }

    /** 收到对方公开值后的派生结果。 */
    class Keys internal constructor(
        val transcript: ByteArray,
        val ke: ByteArray,
        val kcA: ByteArray,
        val kcB: ByteArray,
        val confirmA: ByteArray,
        val confirmB: ByteArray,
        private val isA: Boolean,
    ) {
        /** 本端应发送的确认 MAC。 */
        val myConfirmation: ByteArray get() = if (isA) confirmA else confirmB

        /** 校验对方的确认 MAC（常量时间）。 */
        fun verifyPeer(mac: ByteArray): Boolean = Kdf.constantTimeEquals(if (isA) confirmB else confirmA, mac)
    }

    fun finish(peerPublic: ByteArray): Keys {
        val peerPoint = try {
            CURVE.curve.decodePoint(peerPublic).normalize()
        } catch (e: IllegalArgumentException) {
            throw Spake2Exception("invalid peer point")
        }
        if (peerPoint.isInfinity || !peerPoint.isValid) throw Spake2Exception("invalid peer point")
        val blind = if (isA) N else M
        // K = h * secret * (peer - w * blind)；P-256 的余因子 h = 1
        val k = peerPoint.subtract(blind.multiply(w)).multiply(secret.multiply(CURVE.h)).normalize()
        if (k.isInfinity) throw Spake2Exception("degenerate shared point")
        val kBytes = k.getEncoded(false)
        val pA = if (isA) publicValue else peerPublic
        val pB = if (isA) peerPublic else publicValue
        return deriveKeys(idA, idB, pA, pB, kBytes, w, aad, isA)
    }

    companion object {
        private val CURVE: X9ECParameters = CustomNamedCurves.getByName("P-256")
        private val G: ECPoint = CURVE.g
        private val ORDER: BigInteger = CURVE.n
        private val M: ECPoint = CURVE.curve.decodePoint(
            hex("02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f"),
        )
        private val N: ECPoint = CURVE.curve.decodePoint(
            hex("03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49"),
        )
        private const val SCALAR_LEN = 32
        private val random = SecureRandom()

        /** 发送端（A）。[x] 仅供测试向量注入，正式使用为空。 */
        fun forA(idA: ByteArray, idB: ByteArray, w: BigInteger, aad: ByteArray = ByteArray(0), x: BigInteger? = null) =
            Spake2(true, idA, idB, w, aad, x ?: randomScalar())

        /** 接收端（B）。 */
        fun forB(idA: ByteArray, idB: ByteArray, w: BigInteger, aad: ByteArray = ByteArray(0), y: BigInteger? = null) =
            Spake2(false, idA, idB, w, aad, y ?: randomScalar())

        /**
         * 从 PIN 导出口令标量：w = HKDF(PIN, salt = sessionId, info = "WSC-SPAKE2-w", 40 字节) mod p。
         * 多取 64 位再取模，消除取模偏差（RFC 9382 第 3.2 节建议）。
         */
        fun passwordScalar(pin: String, sessionId: String): BigInteger {
            val okm = Kdf.hkdf(pin.toByteArray(Charsets.UTF_8), sessionId.toByteArray(Charsets.UTF_8), "WSC-SPAKE2-w".toByteArray(), 40)
            return BigInteger(1, okm).mod(ORDER)
        }

        /** AAD = fpA || fpB || sessionId（指纹为 SHA-256 原始 32 字节）。 */
        fun aad(fingerprintA: ByteArray, fingerprintB: ByteArray, sessionId: String): ByteArray =
            fingerprintA + fingerprintB + sessionId.toByteArray(Charsets.UTF_8)

        private fun randomScalar(): BigInteger {
            while (true) {
                val bytes = ByteArray(SCALAR_LEN + 8).also { random.nextBytes(it) }
                val v = BigInteger(1, bytes).mod(ORDER)
                if (v.signum() != 0) return v
            }
        }

        internal fun deriveKeys(
            idA: ByteArray, idB: ByteArray, pA: ByteArray, pB: ByteArray, k: ByteArray,
            w: BigInteger, aad: ByteArray, isA: Boolean,
        ): Keys {
            val tt = ByteArrayOutputStream().apply {
                writeLenPrefixed(idA); writeLenPrefixed(idB)
                writeLenPrefixed(pA); writeLenPrefixed(pB)
                writeLenPrefixed(k); writeLenPrefixed(scalarBytes(w))
            }.toByteArray()
            val hash = Kdf.sha256(tt)
            val ke = hash.copyOfRange(0, 16)
            val ka = hash.copyOfRange(16, 32)
            val kc = Kdf.hkdf(ka, ByteArray(0), "ConfirmationKeys".toByteArray() + aad, 32)
            val kcA = kc.copyOfRange(0, 16)
            val kcB = kc.copyOfRange(16, 32)
            return Keys(tt, ke, kcA, kcB, Kdf.hmacSha256(kcA, tt), Kdf.hmacSha256(kcB, tt), isA)
        }

        private fun scalarBytes(v: BigInteger): ByteArray {
            val raw = v.toByteArray().let { if (it.size > SCALAR_LEN) it.copyOfRange(it.size - SCALAR_LEN, it.size) else it }
            return ByteArray(SCALAR_LEN - raw.size) + raw
        }

        private fun ByteArrayOutputStream.writeLenPrefixed(data: ByteArray) {
            // len 为 8 字节小端（RFC 9382 第 3.2 节）
            var len = data.size.toLong()
            repeat(8) { write((len and 0xFF).toInt()); len = len ushr 8 }
            write(data)
        }

        private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i ->
            ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }
    }
}
