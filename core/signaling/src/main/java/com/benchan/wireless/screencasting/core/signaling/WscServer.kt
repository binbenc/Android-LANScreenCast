package com.benchan.wireless.screencasting.core.signaling

import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.protocol.WscProtocol
import com.benchan.wireless.screencasting.core.security.identity.DeviceIdentity
import com.benchan.wireless.screencasting.core.security.tls.TlsContexts
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.SSLParametersWebSocketServerFactory
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 接收端 WSC 信令服务（技术方案 3.5.1）：Java-WebSocket（MIT）+ WSS，强制客户端证书。
 * 首选端口 8443，被占用时回退为随机端口；实际端口通过 mDNS SRV、二维码、待机页告知发送端。
 */
class WscServer(
    private val identity: DeviceIdentity,
    private val preferredPort: Int = WscProtocol.PREFERRED_PORT,
    private val onConnection: (SignalingConnection) -> Unit,
) {
    @Volatile
    private var server: Impl? = null

    @Volatile
    var port: Int = -1
        private set

    val isRunning: Boolean get() = server != null

    /** 阻塞直到监听成功，返回实际端口。 */
    @Synchronized
    fun start(): Int {
        server?.let { return port }
        val ctx = TlsContexts.server(identity)
        val params = TlsContexts.serverParameters(ctx)
        for (p in listOf(preferredPort, 0).distinct()) {
            val impl = Impl(InetSocketAddress(p))
            impl.setWebSocketFactory(SSLParametersWebSocketServerFactory(ctx, params))
            impl.isReuseAddr = true
            impl.isTcpNoDelay = true
            // 心跳：Java-WebSocket 以该超时为周期发 ping，超时未收到 pong 视为断开（约等于 5s × 3 次）
            impl.connectionLostTimeout = (WscProtocol.PING_INTERVAL_MS * WscProtocol.PING_MISS_LIMIT / 1000).toInt()
            impl.start()
            if (impl.started.await(3, TimeUnit.SECONDS) && impl.startError == null) {
                server = impl
                port = impl.port
                WscLog.i(TAG, "listening on :$port")
                return port
            }
            WscLog.w(TAG, "bind :$p failed: ${impl.startError?.message}")
            runCatching { impl.stop(100) }
        }
        throw IllegalStateException("WSC server failed to start")
    }

    @Synchronized
    fun stop() {
        val s = server ?: return
        server = null
        port = -1
        runCatching { s.stop(500) }
        WscLog.i(TAG, "stopped")
    }

    private inner class Impl(address: InetSocketAddress) : WebSocketServer(address) {
        val started = CountDownLatch(1)

        @Volatile
        var startError: Exception? = null

        override fun onStart() {
            started.countDown()
        }

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            val path = handshake.resourceDescriptor?.substringBefore('?')
            if (path != WscProtocol.WS_PATH) {
                conn.close(CLOSE_POLICY, "bad path")
                return
            }
            val fp = runCatching { TlsContexts.peerFingerprint(conn.sslSession) }.getOrNull()
            if (fp == null) {
                // needClientAuth 下不应发生；没有客户端证书一律拒绝
                conn.close(CLOSE_POLICY, "client certificate required")
                return
            }
            val remote = conn.remoteSocketAddress?.address?.hostAddress ?: "?"
            val c = ServerConnection(conn, remote, fp)
            conn.setAttachment(c)
            WscLog.i(TAG, "connection from $remote")
            onConnection(c)
        }

        override fun onMessage(conn: WebSocket, message: String) {
            conn.getAttachment<ServerConnection>()?.deliver(message)
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
            conn.getAttachment<ServerConnection>()?.remoteClosed("closed $code ${reason.orEmpty()}")
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            if (conn == null) {
                startError = ex
                started.countDown()
            } else {
                WscLog.w(TAG, "connection error: ${ex.message}")
            }
        }
    }

    private class ServerConnection(
        private val socket: WebSocket,
        remote: String,
        fp: com.benchan.wireless.screencasting.core.security.identity.Fingerprint,
    ) : BaseSignalingConnection(remote, fp) {
        override fun sendText(text: String): Boolean = runCatching { socket.send(text) }.isSuccess
        override fun closeTransport(reason: String) = socket.close(1000, reason.take(100))
    }

    private companion object {
        const val TAG = "WscServer"
        const val CLOSE_POLICY = 1008
    }
}
