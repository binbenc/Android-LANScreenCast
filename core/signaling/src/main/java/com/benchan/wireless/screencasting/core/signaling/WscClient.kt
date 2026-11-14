package com.benchan.wireless.screencasting.core.signaling

import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.protocol.WscProtocol
import com.benchan.wireless.screencasting.core.security.identity.DeviceIdentity
import com.benchan.wireless.screencasting.core.security.tls.PinningTrustManager
import com.benchan.wireless.screencasting.core.security.tls.ServerPin
import com.benchan.wireless.screencasting.core.security.tls.TlsContexts
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 连接失败的原因，供 UI 区分“证书不符”和“连不上”。 */
class SignalingConnectException(message: String, val certificateMismatch: Boolean = false, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * 发送端 WSC 信令客户端（技术方案 3.5.3）：OkHttp WebSocket，TLS 1.3，出示本机证书并按指纹钉扎接收端证书。
 * 局域网 IP 没有主机名，hostnameVerifier 一律放行，安全性由指纹钉扎 + SPAKE2 绑定保证。
 */
class WscClient(private val identity: DeviceIdentity) {
    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(WscProtocol.PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    suspend fun connect(host: String, port: Int, pin: ServerPin, timeoutMs: Long = 6_000): SignalingConnection {
        val tm = PinningTrustManager(pin)
        val ctx = TlsContexts.client(identity, tm)
        val client = base.newBuilder()
            .sslSocketFactory(ctx.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(TlsVersion.TLS_1_3).build()))
            .build()
        val request = Request.Builder().url("https://$host:$port${WscProtocol.WS_PATH}").build()
        return withTimeout(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                var connection: ClientConnection? = null
                val ws = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val fp = tm.observed
                        if (fp == null) {
                            webSocket.cancel()
                            if (cont.isActive) cont.resumeWithException(SignalingConnectException("no peer certificate", true))
                            return
                        }
                        val c = ClientConnection(webSocket, host, fp)
                        connection = c
                        WscLog.i(TAG, "connected to $host:$port")
                        if (cont.isActive) cont.resume(c) else c.close("cancelled")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        connection?.deliver(text)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                        connection?.remoteClosed("closing $code $reason")
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        connection?.remoteClosed("closed $code $reason")
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        val c = connection
                        if (c != null) {
                            WscLog.w(TAG, "signaling lost: ${t.message}")
                            c.remoteClosed("failure ${t.javaClass.simpleName}")
                        } else if (cont.isActive) {
                            val mismatch = t is javax.net.ssl.SSLException && (t.cause is java.security.cert.CertificateException ||
                                t.message?.contains("fingerprint", ignoreCase = true) == true)
                            cont.resumeWithException(SignalingConnectException(t.message ?: "connect failed", mismatch, t))
                        }
                    }
                })
                cont.invokeOnCancellation { ws.cancel() }
            }
        }
    }

    private class ClientConnection(
        private val ws: WebSocket,
        remote: String,
        fp: com.benchan.wireless.screencasting.core.security.identity.Fingerprint,
    ) : BaseSignalingConnection(remote, fp) {
        override fun sendText(text: String): Boolean = ws.send(text)
        override fun closeTransport(reason: String) {
            ws.close(1000, reason.take(100))
        }
    }

    private companion object {
        const val TAG = "WscClient"
    }
}
