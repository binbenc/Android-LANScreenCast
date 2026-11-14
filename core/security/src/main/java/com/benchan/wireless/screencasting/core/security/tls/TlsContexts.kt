package com.benchan.wireless.screencasting.core.security.tls

import com.benchan.wireless.screencasting.core.security.identity.DeviceIdentity
import com.benchan.wireless.screencasting.core.security.identity.Fingerprint
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * 信令 TLS 配置（技术方案 3.5.3）：只允许 TLS 1.3；双向自签证书；不做 CA 校验，改为指纹钉扎 / 应用层判断。
 */
object TlsContexts {
    const val PROTOCOL = "TLSv1.3"

    /** 接收端：要求客户端证书；任何自签证书都能完成握手，是否可信由状态机按指纹决定（未知指纹只允许 PAIR_*）。 */
    fun server(identity: DeviceIdentity): SSLContext = SSLContext.getInstance("TLS").apply {
        init(arrayOf(IdentityKeyManager(identity)), arrayOf(AcceptSelfSignedTrustManager()), null)
    }

    fun serverParameters(context: SSLContext): SSLParameters = context.defaultSSLParameters.apply {
        protocols = arrayOf(PROTOCOL)
        needClientAuth = true
    }

    /** 发送端：出示本机证书，按 [pin] 校验接收端证书。 */
    fun client(identity: DeviceIdentity, trustManager: PinningTrustManager): SSLContext = SSLContext.getInstance("TLS").apply {
        init(arrayOf(IdentityKeyManager(identity)), arrayOf(trustManager), null)
    }

    /** 读取握手后对端证书指纹。 */
    fun peerFingerprint(session: SSLSession?): Fingerprint? = runCatching {
        (session?.peerCertificates?.firstOrNull() as? X509Certificate)?.let(Fingerprint::of)
    }.getOrNull()
}

/** 始终返回本机身份的 KeyManager（TLS 1.3 下按签名算法协商，不区分 keyType）。 */
class IdentityKeyManager(private val identity: DeviceIdentity) : X509ExtendedKeyManager() {
    private val chain = arrayOf(identity.certificate)
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
    override fun getCertificateChain(alias: String?): Array<X509Certificate> = chain
    override fun getPrivateKey(alias: String?): PrivateKey = identity.privateKey

    private companion object {
        const val ALIAS = "wsc"
    }
}

/** 校验证书确实是自签且在有效期内（防止乱填的证书），不做 CA 链校验。 */
internal fun checkSelfSigned(chain: Array<out X509Certificate>?): X509Certificate {
    val cert = chain?.firstOrNull() ?: throw CertificateException("empty certificate chain")
    try {
        cert.checkValidity()
        cert.verify(cert.publicKey)
    } catch (e: Exception) {
        throw CertificateException("invalid self-signed certificate", e)
    }
    return cert
}

/** 接收端（服务端）用：接受任意合法自签客户端证书；是否可信由应用层按指纹判断（未知指纹只允许 PAIR_*）。 */
@android.annotation.SuppressLint("CustomX509TrustManager")
class AcceptSelfSignedTrustManager : X509ExtendedTrustManager() {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { checkSelfSigned(chain) }
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) { checkSelfSigned(chain) }
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) { checkSelfSigned(chain) }
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("server role only")
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = throw CertificateException("server role only")
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = throw CertificateException("server role only")
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** 发送端连接接收端时的钉扎方式。 */
sealed interface ServerPin {
    /** 完整指纹：可信列表、二维码、最近设备。 */
    data class Full(val fingerprint: Fingerprint) : ServerPin

    /** mDNS TXT 中的指纹前缀（8 字节）。 */
    class Prefix(val prefix: ByteArray) : ServerPin

    /**
     * 不预先钉扎（投屏码 / 手动 IP 的首次配对）：握手后记录指纹，随后由 SPAKE2 把双方看到的指纹写进 AAD 做绑定，
     * 中间人会导致确认 MAC 失败（技术方案 3.5.4）。
     */
    data object None : ServerPin
}

/** 指纹钉扎 TrustManager，并记录实际看到的服务端指纹（局域网自签证书无 CA 可依，钉扎即校验）。 */
@android.annotation.SuppressLint("CustomX509TrustManager")
class PinningTrustManager(private val pin: ServerPin) : X509ExtendedTrustManager() {
    @Volatile
    var observed: Fingerprint? = null
        private set

    private fun check(chain: Array<out X509Certificate>?) {
        val cert = checkSelfSigned(chain)
        val fp = Fingerprint.of(cert)
        val ok = when (pin) {
            is ServerPin.Full -> pin.fingerprint == fp
            is ServerPin.Prefix -> fp.matchesPrefix(pin.prefix)
            ServerPin.None -> true
        }
        if (!ok) throw CertificateException("certificate fingerprint mismatch")
        observed = fp
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("client role only")
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = throw CertificateException("client role only")
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = throw CertificateException("client role only")
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
