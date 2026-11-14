package com.benchan.wireless.screencasting.core.security.identity

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * 设备身份证书：ECDSA P-256 自签 X.509，CN = 随机 deviceId，有效期 20 年（技术方案 3.5.2）。
 * Android 平台没有公开的 X.509 构建 API，所以用 BouncyCastle bcpkix 生成（只用其 ASN.1 编码能力，签名仍走平台 JCA）。
 */
object SelfSignedCertificates {
    private const val VALID_YEARS = 20L

    fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
    }.generateKeyPair()

    fun create(keyPair: KeyPair, deviceId: String, nowMillis: Long = System.currentTimeMillis()): X509Certificate {
        val name = X500Name("CN=$deviceId")
        val notBefore = Date(nowMillis - TimeUnit.DAYS.toMillis(1))
        val notAfter = Date(nowMillis + TimeUnit.DAYS.toMillis(365 * VALID_YEARS))
        val serial = BigInteger(127, SecureRandom())
        val builder = JcaX509v3CertificateBuilder(name, serial, notBefore, notAfter, name, keyPair.public)
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }
}
