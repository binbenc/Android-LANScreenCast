package com.benchan.wireless.screencasting.core.protocol.pairing

import com.benchan.wireless.screencasting.core.protocol.crypto.Kdf

/** 二维码配对证明：mac = HMAC-SHA256(n, fpA || fpB || sessionId)（技术方案 3.5.4）。 */
object QrProof {
    fun compute(nonce: ByteArray, fingerprintA: ByteArray, fingerprintB: ByteArray, sessionId: String): ByteArray =
        Kdf.hmacSha256(nonce, fingerprintA, fingerprintB, sessionId.toByteArray(Charsets.UTF_8))

    fun verify(nonce: ByteArray, fingerprintA: ByteArray, fingerprintB: ByteArray, sessionId: String, mac: ByteArray): Boolean =
        Kdf.constantTimeEquals(compute(nonce, fingerprintA, fingerprintB, sessionId), mac)
}
