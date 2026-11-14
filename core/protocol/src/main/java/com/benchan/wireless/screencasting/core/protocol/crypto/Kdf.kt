package com.benchan.wireless.screencasting.core.protocol.crypto

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HKDF-SHA256（RFC 5869）与 HMAC-SHA256，只用 JCA 标准算法。 */
object Kdf {
    private const val HMAC = "HmacSHA256"
    private const val HASH_LEN = 32

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC)
        // HMAC 允许空密钥，但 JCA 的 SecretKeySpec 不允许，用一个 0 字节的哈希长度密钥代替（RFC 5869：salt 为空时取 HashLen 个 0）
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(HASH_LEN) else key, HMAC))
        parts.forEach { mac.update(it) }
        return mac.doFinal()
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH_LEN)
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(HASH_LEN) else salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            t = hmacSha256(prk, t, info, byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - offset)
            System.arraycopy(t, 0, out, offset, n)
            offset += n
            counter++
        }
        return out
    }

    /** 常量时间比较，避免 MAC 校验的时序侧信道。 */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}
