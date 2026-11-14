package com.benchan.wireless.screencasting.core.common

import java.util.concurrent.CopyOnWriteArrayList

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** 日志输出端：Logcat、本地环形诊断日志（F25）各实现一个。 */
fun interface LogSink {
    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?)
}

/**
 * 全工程统一日志门面。纯 Kotlin，便于 :core:protocol 等 JVM 模块使用；
 * Android 侧在 Application 启动时注册 Logcat 与诊断日志两个 [LogSink]。
 *
 * 隐私约定（PRD 14）：不要记录 Wi-Fi 名称、投屏内容、推送的网址。
 */
object WscLog {
    private val sinks = CopyOnWriteArrayList<LogSink>()

    fun addSink(sink: LogSink) {
        sinks.addIfAbsent(sink)
    }

    fun removeSink(sink: LogSink) {
        sinks.remove(sink)
    }

    fun d(tag: String, message: String) = dispatch(LogLevel.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = dispatch(LogLevel.INFO, tag, message, null)
    fun w(tag: String, message: String, t: Throwable? = null) = dispatch(LogLevel.WARN, tag, message, t)
    fun e(tag: String, message: String, t: Throwable? = null) = dispatch(LogLevel.ERROR, tag, message, t)

    private fun dispatch(level: LogLevel, tag: String, message: String, t: Throwable?) {
        for (sink in sinks) {
            runCatching { sink.log(level, tag, message, t) }
        }
    }
}
