package com.benchan.wireless.screencasting.core.security.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 私钥落盘前的包裹加密。 */
interface KeyWrapper {
    fun wrap(plain: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray): ByteArray
}

/**
 * 方案 2（技术方案 3.5.2）：私钥 PKCS#8 用 AndroidKeyStore 中不可导出的 AES-256-GCM 密钥包裹后存在 App 私有目录。
 * 好处是同一私钥既能给 TLS 用，也能（可选）作为 WebRTC 的 DTLS 证书。
 */
class AndroidKeystoreKeyWrapper(private val alias: String = "wsc_identity_wrap") : KeyWrapper {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        return ByteBuffer.allocate(1 + iv.size + ct.size).put(iv.size.toByte()).put(iv).put(ct).array()
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        val buf = ByteBuffer.wrap(wrapped)
        val iv = ByteArray(buf.get().toInt()).also { buf.get(it) }
        val ct = ByteArray(buf.remaining()).also { buf.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }
}

/** 单测用：不加密。 */
object PlainKeyWrapper : KeyWrapper {
    override fun wrap(plain: ByteArray) = plain
    override fun unwrap(wrapped: ByteArray) = wrapped
}
