package com.benchan.wireless.screencasting.core.data.log

import android.content.Context
import com.benchan.wireless.screencasting.core.common.LogLevel
import com.benchan.wireless.screencasting.core.common.LogSink
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地诊断日志（F25、PRD 14）：环形文件，超过 [MAX_BYTES] 时只保留后一半。
 * 只存本机，导出前用户可先预览（设置 → 导出本地日志）。不记录 Wi-Fi 名称、投屏内容、推送的网址。
 */
@Singleton
class DiagnosticLog @Inject constructor(@ApplicationContext context: Context) : LogSink {
    private val dir = File(context.noBackupFilesDir, "diagnostics").apply { mkdirs() }
    private val file = File(dir, "wsc.log")
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "wsc-diag-log").apply { isDaemon = true } }
    private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US) }

    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        if (level == LogLevel.DEBUG) return
        val line = buildString {
            append(timeFormat.get()!!.format(Date())).append(' ')
            append(level.name.first()).append('/').append(tag).append(": ").append(message)
            throwable?.let { append(" | ").append(it.javaClass.simpleName).append(": ").append(it.message) }
            append('\n')
        }
        executor.execute { appendLine(line) }
    }

    private fun appendLine(line: String) {
        runCatching {
            file.appendText(line)
            if (file.length() > MAX_BYTES) trim()
        }
    }

    private fun trim() {
        val text = file.readText()
        val keep = text.substring(text.length / 2).substringAfter('\n')
        file.writeText(keep)
    }

    /** 读出当前日志全文（供预览/导出）。 */
    fun readAll(): String {
        val done = java.util.concurrent.CountDownLatch(1)
        executor.execute { done.countDown() }   // 等待已排队的写入完成
        done.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return if (file.exists()) file.readText() else ""
    }

    fun clear() {
        executor.execute { file.delete() }
    }

    companion object {
        const val MAX_BYTES = 512 * 1024L
    }
}
