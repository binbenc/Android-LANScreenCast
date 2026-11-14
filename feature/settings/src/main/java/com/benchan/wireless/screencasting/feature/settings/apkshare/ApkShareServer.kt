package com.benchan.wireless.screencasting.feature.settings.apkshare

import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.net.LanHttpServer
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

/** 安装包里包含的原生库 ABI（决定 32 位电视能否安装）。 */
data class ApkInfo(val file: File, val abis: Set<String>, val sizeBytes: Long) {
    /** 不含原生库（纯 Java）或包含 armeabi-v7a 时，32 位电视也能装。 */
    val supports32Bit: Boolean get() = abis.isEmpty() || "armeabi-v7a" in abis

    companion object {
        fun inspect(file: File): ApkInfo {
            val abis = runCatching {
                ZipFile(file).use { zip ->
                    zip.entries().asSequence().mapNotNull { e ->
                        val n = e.name
                        if (n.startsWith("lib/") && n.count { it == '/' } >= 2) n.split('/')[1] else null
                    }.toSet()
                }
            }.getOrDefault(emptySet())
            return ApkInfo(file, abis, file.length())
        }
    }
}

/**
 * F26 / 决策 #20：手机在局域网内临时分发本机 APK（单 APK 双模式，安装包本身就是接收端）。
 * 只监听局域网明文 HTTP；URL 带随机短令牌，离开页面或超时即停止（PRD 8.1 S-06 同样的规则）。
 * 页面不引用任何外部资源（零外联）。
 */
class ApkShareServer(private val apk: ApkInfo, private val appLabel: String, private val preferredPort: Int = PREFERRED_PORT) {
    val token: String = randomToken()
    private val downloads = AtomicInteger(0)
    private var server: LanHttpServer? = null

    val downloadCount: Int get() = downloads.get()

    suspend fun start(): Int {
        val s = LanHttpServer("apk-share", preferredPort = preferredPort) {
            routing {
                get("/") { call.respond(HttpStatusCode.NotFound) }
                get("/$token") { call.respondText(page(), ContentType.Text.Html.withParameter("charset", "utf-8")) }
                get("/$token/$FILE_NAME") {
                    downloads.incrementAndGet()
                    WscLog.i(TAG, "apk download #${downloads.get()}")
                    call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, FILE_NAME).toString())
                    call.respond(LocalFileContent(apk.file, ContentType.parse("application/vnd.android.package-archive")))
                }
            }
        }
        val port = s.start()
        server = s
        return port
    }

    fun stop() {
        server?.stop()
        server = null
    }

    private fun page(): String = """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(appLabel)} 安装包</title><style>body{font-family:sans-serif;background:#111418;color:#E1E2E8;margin:0;padding:48px 27px}
a.btn{display:inline-block;background:#A2C9F0;color:#0A3050;padding:20px 40px;border-radius:999px;font-size:28px;text-decoration:none}
p{font-size:20px;line-height:1.6;color:#C2C7CF}</style></head><body>
<h1>${esc(appLabel)}</h1><p>免费、开源（GPL‑3.0）、不联网的局域网投屏接收端。安装后打开即进入“等待投屏”。</p>
<p><a class="btn" href="/$token/$FILE_NAME">下载安装包（${apk.sizeBytes / 1_048_576} MB）</a></p>
<p>下载后在电视的“文件管理”或“下载”里打开安装。如果提示禁止安装，请在系统设置里允许本浏览器 / 文件管理器“安装未知来源应用”。</p>
</body></html>"""

    companion object {
        private const val TAG = "ApkShare"
        const val PREFERRED_PORT = 8765
        const val FILE_NAME = "wsc-receiver.apk"
        private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789" // 去掉易混淆字符，方便用遥控器输入

        fun randomToken(length: Int = 4): String {
            val r = SecureRandom()
            return String(CharArray(length) { ALPHABET[r.nextInt(ALPHABET.length)] })
        }

        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
