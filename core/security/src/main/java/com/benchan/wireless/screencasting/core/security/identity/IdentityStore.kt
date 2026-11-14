package com.benchan.wireless.screencasting.core.security.identity

import com.benchan.wireless.screencasting.core.common.WscLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID

/**
 * 首次启动生成身份并持久化；之后每次读取同一身份（指纹不变，可信列表才有意义）。
 * 文件在 noBackupFilesDir，且备份规则已排除（PRD 8.5）。
 */
class IdentityStore(private val file: File, private val wrapper: KeyWrapper) {

    @Serializable
    private data class Persisted(val deviceId: String, val wrappedKey: String, val certificate: String)

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: DeviceIdentity? = null

    @Synchronized
    fun loadOrCreate(): DeviceIdentity {
        cached?.let { return it }
        val loaded = runCatching { load() }.onFailure { WscLog.w(TAG, "identity unreadable, regenerating", it) }.getOrNull()
        val identity = loaded ?: create()
        cached = identity
        return identity
    }

    private fun load(): DeviceIdentity? {
        if (!file.exists()) return null
        val p = json.decodeFromString(Persisted.serializer(), file.readText())
        val keyBytes = wrapper.unwrap(Base64.getDecoder().decode(p.wrappedKey))
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(p.certificate))) as X509Certificate
        return DeviceIdentity(p.deviceId, key, cert)
    }

    private fun create(): DeviceIdentity {
        val deviceId = UUID.randomUUID().toString()
        val kp = SelfSignedCertificates.generateKeyPair()
        val cert = SelfSignedCertificates.create(kp, deviceId)
        val p = Persisted(
            deviceId,
            Base64.getEncoder().encodeToString(wrapper.wrap(kp.private.encoded)),
            Base64.getEncoder().encodeToString(cert.encoded),
        )
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(Persisted.serializer(), p))
        WscLog.i(TAG, "generated new device identity")
        return DeviceIdentity(deviceId, kp.private, cert)
    }

    private companion object {
        const val TAG = "IdentityStore"
    }
}
