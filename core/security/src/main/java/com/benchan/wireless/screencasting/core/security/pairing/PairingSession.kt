package com.benchan.wireless.screencasting.core.security.pairing

import com.benchan.wireless.screencasting.core.common.Base64Url
import com.benchan.wireless.screencasting.core.protocol.crypto.Spake2
import com.benchan.wireless.screencasting.core.security.identity.Fingerprint

/**
 * 一次 PIN / 投屏码配对的 SPAKE2 交换（技术方案 4.1）。
 * idA = 发送端 deviceId，idB = 接收端 deviceId；AAD = fpA || fpB || sessionId，指纹取自本次 TLS 会话实际看到的证书。
 */
class SenderPairing(
    sessionId: String,
    pin: String,
    senderId: String,
    receiverId: String,
    senderFingerprint: Fingerprint,
    receiverFingerprint: Fingerprint,
) {
    private val spake = Spake2.forA(
        senderId.toByteArray(), receiverId.toByteArray(),
        Spake2.passwordScalar(pin, sessionId),
        Spake2.aad(senderFingerprint.bytes, receiverFingerprint.bytes, sessionId),
    )
    private var keys: Spake2.Keys? = null

    /** PAIR_PIN{point} 的值。 */
    val publicPoint: String = Base64Url.encode(spake.publicValue)

    /** 收到接收端 pB，返回 PAIR_CONFIRM{mac}。 */
    fun onPeerPoint(point: String): String {
        val k = spake.finish(Base64Url.decode(point))
        keys = k
        return Base64Url.encode(k.myConfirmation)
    }

    /** 校验 PAIR_OK{mac}。 */
    fun verifyReceiver(mac: String?): Boolean {
        val k = keys ?: return false
        return mac != null && runCatching { k.verifyPeer(Base64Url.decode(mac)) }.getOrDefault(false)
    }
}

class ReceiverPairing(
    sessionId: String,
    pin: String,
    senderId: String,
    receiverId: String,
    senderFingerprint: Fingerprint,
    receiverFingerprint: Fingerprint,
) {
    private val spake = Spake2.forB(
        senderId.toByteArray(), receiverId.toByteArray(),
        Spake2.passwordScalar(pin, sessionId),
        Spake2.aad(senderFingerprint.bytes, receiverFingerprint.bytes, sessionId),
    )
    private var keys: Spake2.Keys? = null

    val publicPoint: String = Base64Url.encode(spake.publicValue)

    /** 收到 pA；非法点抛出 Spake2Exception。 */
    fun onPeerPoint(point: String) {
        keys = spake.finish(Base64Url.decode(point))
    }

    /** 校验 PAIR_CONFIRM{mac}；成功时返回应放进 PAIR_OK 的确认 MAC，失败返回 null。 */
    fun verifySender(mac: String): String? {
        val k = keys ?: return null
        val ok = runCatching { k.verifyPeer(Base64Url.decode(mac)) }.getOrDefault(false)
        return if (ok) Base64Url.encode(k.myConfirmation) else null
    }
}
