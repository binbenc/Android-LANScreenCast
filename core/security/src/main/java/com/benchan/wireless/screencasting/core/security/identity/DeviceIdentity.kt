package com.benchan.wireless.screencasting.core.security.identity

import java.security.PrivateKey
import java.security.cert.X509Certificate

/** 本机身份：deviceId + 私钥 + 自签证书 + 指纹。 */
class DeviceIdentity(
    val deviceId: String,
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
) {
    val fingerprint: Fingerprint = Fingerprint.of(certificate)
}
