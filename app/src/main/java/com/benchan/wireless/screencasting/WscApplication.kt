package com.benchan.wireless.screencasting

import android.app.Application
import android.util.Log
import com.benchan.wireless.screencasting.core.common.LogLevel
import com.benchan.wireless.screencasting.core.common.LogSink
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.data.log.DiagnosticLog
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * 应用入口。零外联（PRD 8.5）：不初始化任何统计、广告、崩溃上报 SDK；日志只写 Logcat 与本地环形诊断日志（F25）。
 */
@HiltAndroidApp
class WscApplication : Application() {
    @Inject
    lateinit var diagnosticLog: DiagnosticLog

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) WscLog.addSink(LogcatSink)
        WscLog.addSink(diagnosticLog)
        WscLog.i("App", "start ${BuildConfig.VERSION_NAME} appleCompat=${BuildConfig.AIRPLAY_AVAILABLE}")
    }

    private object LogcatSink : LogSink {
        override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
            val t = "WSC/$tag"
            when (level) {
                LogLevel.DEBUG -> Log.d(t, message, throwable)
                LogLevel.INFO -> Log.i(t, message, throwable)
                LogLevel.WARN -> Log.w(t, message, throwable)
                LogLevel.ERROR -> Log.e(t, message, throwable)
            }
        }
    }
}
