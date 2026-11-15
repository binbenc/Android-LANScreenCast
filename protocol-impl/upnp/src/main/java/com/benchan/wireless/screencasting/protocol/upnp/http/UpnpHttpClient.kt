package com.benchan.wireless.screencasting.protocol.upnp.http

import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.common.net.LanAddress
import com.benchan.wireless.screencasting.protocol.upnp.Soap
import com.benchan.wireless.screencasting.protocol.upnp.SoapOutcome
import com.benchan.wireless.screencasting.protocol.upnp.UrlGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** GET 结果。 */
sealed interface FetchResult {
    data class Ok(val body: String, val server: String?) : FetchResult
    data class Failed(val kind: SoapOutcome.Failure.Kind, val httpStatus: Int? = null) : FetchResult
}

/** SUBSCRIBE 结果。 */
data class GenaSubscription(val sid: String, val timeoutSeconds: Int)

/**
 * DMC 的 HTTP 客户端（技术方案 3.13.3.1）：OkHttp（项目已有依赖），关闭重定向与重试，不走代理，
 * 只接受局域网 IPv4 字面量（自定义 Dns 拒绝主机名）。每次调用单独设置总超时；`Connection: close`。
 */
class UpnpHttpClient(
    private val customClient: OkHttpClient? = null,
    private val userAgent: String = DEFAULT_USER_AGENT,
) {
    /**
     * 首次真正发请求时（已在 IO 线程）才构建 OkHttpClient：构建会初始化 SSLContext（约 300 ms），
     * 不能在 Hilt 注入 / 构造时（主线程）触发（第 3 轮复测 StrictMode 慢调用）。
     */
    private val client: OkHttpClient by lazy { customClient ?: shared }

    suspend fun fetchText(url: String, maxChars: Int, timeoutMs: Long = 4_000): FetchResult = withContext(Dispatchers.IO) { fetchTextBlocking(url, maxChars, timeoutMs) }

    private suspend fun fetchTextBlocking(url: String, maxChars: Int, timeoutMs: Long): FetchResult {
        if (!UrlGuard.isLanHttp(url)) {
            blocked()
            return FetchResult.Failed(SoapOutcome.Failure.Kind.BLOCKED)
        }
        val req = base(url).get().build()
        return try {
            execute(req, timeoutMs).use { r ->
                if (!r.isSuccessful) return FetchResult.Failed(SoapOutcome.Failure.Kind.NETWORK, r.code)
                val body = readLimited(r, maxChars) ?: return FetchResult.Failed(SoapOutcome.Failure.Kind.BAD_RESPONSE, r.code)
                FetchResult.Ok(body, r.header("Server"))
            }
        } catch (e: IOException) {
            FetchResult.Failed(kindOf(e))
        }
    }

    suspend fun soap(controlUrl: String, serviceType: String, action: String, args: List<Pair<String, String>>, timeoutMs: Long): SoapOutcome =
        withContext(Dispatchers.IO) { soapBlocking(controlUrl, serviceType, action, args, timeoutMs) }

    private suspend fun soapBlocking(controlUrl: String, serviceType: String, action: String, args: List<Pair<String, String>>, timeoutMs: Long): SoapOutcome {
        if (!UrlGuard.isLanHttp(controlUrl)) return blocked()
        val body = Soap.request(serviceType, action, args).toByteArray(Charsets.UTF_8).toRequestBody(XML)
        val req = base(controlUrl).post(body).header("SOAPACTION", Soap.actionHeader(serviceType, action)).build()
        return try {
            execute(req, timeoutMs).use { r ->
                val text = readLimited(r, Soap.MAX_BODY) ?: return SoapOutcome.Failure(SoapOutcome.Failure.Kind.BAD_RESPONSE, r.code)
                Soap.parseResponse(text, r.code)
            }
        } catch (e: IOException) {
            SoapOutcome.Failure(kindOf(e))
        }
    }

    suspend fun subscribe(eventUrl: String, callbackUrl: String, timeoutSeconds: Int): GenaSubscription? {
        if (!UrlGuard.isLanHttp(eventUrl) || !UrlGuard.isLanHttp(callbackUrl)) return null
        val req = base(eventUrl).method("SUBSCRIBE", null)
            .header("CALLBACK", "<$callbackUrl>").header("NT", "upnp:event").header("TIMEOUT", "Second-$timeoutSeconds").build()
        return genaCall(req, timeoutSeconds)
    }

    suspend fun renew(eventUrl: String, sid: String, timeoutSeconds: Int): GenaSubscription? {
        if (!UrlGuard.isLanHttp(eventUrl)) return null
        val req = base(eventUrl).method("SUBSCRIBE", null).header("SID", sid).header("TIMEOUT", "Second-$timeoutSeconds").build()
        return genaCall(req, timeoutSeconds)
    }

    suspend fun unsubscribe(eventUrl: String, sid: String) {
        if (!UrlGuard.isLanHttp(eventUrl)) return
        val req = base(eventUrl).method("UNSUBSCRIBE", null).header("SID", sid).build()
        withContext(Dispatchers.IO) { runCatching { execute(req, 2_000).close() } }
    }

    private suspend fun genaCall(req: Request, requested: Int): GenaSubscription? = withContext(Dispatchers.IO) { genaCallBlocking(req, requested) }

    private suspend fun genaCallBlocking(req: Request, requested: Int): GenaSubscription? = try {
        execute(req, 4_000).use { r ->
            val sid = r.header("SID")?.trim()
            if (!r.isSuccessful || sid.isNullOrEmpty()) null
            else GenaSubscription(sid, r.header("TIMEOUT")?.substringAfter("Second-", "")?.trim()?.toIntOrNull() ?: requested)
        }
    } catch (_: IOException) {
        null
    }

    private fun base(url: String): Request.Builder = Request.Builder().url(url)
        .header("User-Agent", userAgent)
        .header("Connection", "close")

    private fun blocked(): SoapOutcome.Failure {
        // 不记录完整地址（可能含令牌），只记录被拦截这件事
        WscLog.w(TAG, "blocked non-LAN request")
        return SoapOutcome.Failure(SoapOutcome.Failure.Kind.BLOCKED)
    }

    /** 读取响应体是阻塞 IO：所有公开方法都在 [Dispatchers.IO] 上执行（否则在主线程协程里调用会 NetworkOnMainThreadException）。 */
    private suspend fun execute(req: Request, timeoutMs: Long): Response {
        val call = client.newCall(req)
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        return call.await()
    }

    private fun readLimited(r: Response, maxChars: Int): String? {
        val source = r.body.source()
        if (source.request(maxChars.toLong() + 1)) return null // 超过上限
        return source.readByteArray().toString(Charsets.UTF_8)
    }

    companion object {
        private const val TAG = "UpnpHttp"
        const val DEFAULT_USER_AGENT = "Android UPnP/1.0 WSC/1.0 DLNADOC/1.50"
        private val XML = "text/xml; charset=\"utf-8\"".toMediaType()

        /** 只解析 IPv4 字面量，主机名一律失败（零外联）。 */
        private val literalOnlyDns = Dns { host ->
            val a = LanAddress.parseIpv4(host) ?: throw UnknownHostException("hostnames are not allowed")
            if (!LanAddress.isLan(a)) throw UnknownHostException("non-LAN address")
            listOf<InetAddress>(a)
        }

        val shared: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .dns(literalOnlyDns)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS) // 每次调用另设总超时；推到本应用接收端时要等对方确认（≤40 秒）
                .build()
        }

        private fun kindOf(e: IOException): SoapOutcome.Failure.Kind = when (e) {
            is InterruptedIOException -> SoapOutcome.Failure.Kind.TIMEOUT
            is UnknownHostException -> SoapOutcome.Failure.Kind.BLOCKED
            else -> SoapOutcome.Failure.Kind.NETWORK
        }

        private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { runCatching { cancel() } }
            enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            })
        }
    }
}
