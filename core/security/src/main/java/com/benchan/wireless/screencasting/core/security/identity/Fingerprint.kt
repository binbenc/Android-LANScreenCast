package com.benchan.wireless.screencasting.core.security.identity

import com.benchan.wireless.screencasting.core.common.Base64Url
import com.benchan.wireless.screencasting.core.common.Hex
import java.security.MessageDigest
import java.security.cert.X509Certificate

/** 证书指纹 = SHA-256(DER)（技术方案 3.5.2）。 */
class Fingerprint(val bytes: ByteArray) {
    init {
        require(bytes.size == SIZE) { "fingerprint must be $SIZE bytes" }
    }

    val hex: String get() = Hex.encode(bytes)

    /** mDNS TXT 的 fp：前 8 字节 base64url（PRD 8.3.1）。 */
    val txtPrefix: String get() = Base64Url.encode(bytes.copyOfRange(0, PREFIX_SIZE))

    /** 设置页展示用的短格式，例如 "3A:5F:…:C2"。 */
    val shortDisplay: String get() = hex.uppercase().chunked(2).let { it.take(4).joinToString(":") + ":…:" + it.takeLast(2).joinToString(":") }

    fun matchesPrefix(prefix: ByteArray): Boolean =
        prefix.isNotEmpty() && prefix.size <= SIZE && MessageDigest.isEqual(bytes.copyOfRange(0, prefix.size), prefix)

    override fun equals(other: Any?): Boolean = other is Fingerprint && MessageDigest.isEqual(bytes, other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    override fun toString(): String = shortDisplay

    companion object {
        const val SIZE = 32
        const val PREFIX_SIZE = 8

        fun of(certificate: X509Certificate): Fingerprint =
            Fingerprint(MessageDigest.getInstance("SHA-256").digest(certificate.encoded))

        fun fromHex(hex: String): Fingerprint = Fingerprint(Hex.decode(hex))

        fun prefixFromTxt(value: String): ByteArray? = runCatching { Base64Url.decode(value) }.getOrNull()
            ?.takeIf { it.size in 4..SIZE }
    }
}
