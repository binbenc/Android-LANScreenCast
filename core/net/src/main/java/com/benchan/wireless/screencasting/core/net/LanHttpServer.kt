package com.benchan.wireless.screencasting.core.net

import com.benchan.wireless.screencasting.core.common.WscLog
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 局域网明文 HTTP 服务（Ktor CIO）：DLNA 设备描述 / SOAP / GENA、F26 安装包分发共用。
 * 只监听局域网，绝不访问公网（PRD 8.5）。CIO 不支持服务端 TLS，所以不承载 WSC 信令。
 */
class LanHttpServer(
    private val name: String,
    private val preferredPort: Int = 0,
    private val module: Application.() -> Unit,
) {
    private val serverJob = kotlinx.coroutines.SupervisorJob()
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @Volatile
    var port: Int = -1
        private set

    val isRunning: Boolean get() = server != null

    /** 启动并返回实际端口；首选端口被占用时回退到随机端口。 */
    suspend fun start(): Int = withContext(Dispatchers.IO) {
        server?.let { return@withContext port }
        val candidates = if (preferredPort > 0) listOf(preferredPort, 0) else listOf(0)
        var lastError: Throwable? = null
        for (p in candidates) {
            try {
                // 先探测可用端口：Ktor 的 resolvedConnectors() 在部分环境下不会返回，直接用确定的端口启动更可靠
                val actual = if (p == 0) java.net.ServerSocket(0).use { it.localPort } else {
                    // 先探测是否已有进程在监听（部分系统上 SO_REUSEADDR 会让重复绑定“成功”，请求却被别的进程接走）
                    check(!isListening(p)) { "port $p in use" }
                    java.net.ServerSocket().use { it.reuseAddress = false; it.bind(java.net.InetSocketAddress(p)) }
                    p
                }
                // 服务端协程必须挂在独立的 Job 上：否则会成为调用方协程的子协程，调用方的 withContext 永远等不到结束
                val s = embeddedServer(CIO, port = actual, host = "0.0.0.0", parentCoroutineContext = serverJob, module = module)
                s.start(wait = false)
                server = s
                port = actual
                WscLog.i(TAG, "$name http listening on :$actual")
                return@withContext actual
            } catch (e: Exception) {
                lastError = e
                WscLog.w(TAG, "$name http bind :$p failed", e)
            }
        }
        throw lastError ?: IllegalStateException("bind failed")
    }

    fun stop() {
        server?.let { s ->
            runCatching { s.stop(gracePeriodMillis = 100, timeoutMillis = 500) }
            WscLog.i(TAG, "$name http stopped")
        }
        server = null
        port = -1
    }

    private companion object {
        const val TAG = "LanHttpServer"

        fun isListening(port: Int): Boolean = runCatching {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port), 200) }
            true
        }.getOrDefault(false)
    }
}
