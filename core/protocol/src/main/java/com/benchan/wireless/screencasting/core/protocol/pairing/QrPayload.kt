package com.benchan.wireless.screencasting.core.protocol.pairing

import com.benchan.wireless.screencasting.core.common.Base64Url
import com.benchan.wireless.screencasting.core.protocol.WscProtocol
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 二维码内容（技术方案 3.5.4）：`wsc://IP:port?fp=<完整指纹 base64url>&n=<128bit 随机数>&v=1&name=<设备名>`。
 * 扫码得到接收端完整指纹（带外认证），发送端据此钉扎 TLS 证书，并在 TLS 内用 n 证明“扫过码”。
 */
data class QrPayload(
    val host: String,
    val port: Int,
    val fingerprint: ByteArray,
    val nonce: ByteArray,
    val version: Int = WscProtocol.VERSION,
    val name: String? = null,
) {
    fun toUri(): String = buildString {
        append(SCHEME).append("://").append(host).append(':').append(port)
        append("?fp=").append(Base64Url.encode(fingerprint))
        append("&n=").append(Base64Url.encode(nonce))
        append("&v=").append(version)
        name?.let { append("&name=").append(URLEncoder.encode(it.take(MAX_NAME), "UTF-8")) }
    }

    override fun equals(other: Any?): Boolean = other is QrPayload && host == other.host && port == other.port &&
        fingerprint.contentEquals(other.fingerprint) && nonce.contentEquals(other.nonce) &&
        version == other.version && name == other.name

    override fun hashCode(): Int = (host.hashCode() * 31 + port) * 31 + fingerprint.contentHashCode()

    companion object {
        const val SCHEME = "wsc"
        const val FINGERPRINT_BYTES = 32
        const val NONCE_BYTES = 16
        private const val MAX_NAME = 40

        fun parse(text: String): QrPayload? = runCatching {
            val uri = URI(text.trim())
            if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
            val host = uri.host ?: return null
            if (!isIpv4(host)) return null
            val port = uri.port.takeIf { it in 1..65535 } ?: return null
            val q = (uri.rawQuery ?: return null).split('&').mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }.toMap()
            val fp = Base64Url.decode(q["fp"] ?: return null)
            val n = Base64Url.decode(q["n"] ?: return null)
            if (fp.size != FINGERPRINT_BYTES || n.size != NONCE_BYTES) return null
            val v = q["v"]?.toIntOrNull() ?: WscProtocol.VERSION
            QrPayload(host, port, fp, n, v, q["name"])
        }.getOrNull()

        fun isIpv4(host: String): Boolean {
            val parts = host.split('.')
            return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() in 0..255 }
        }
    }
}
