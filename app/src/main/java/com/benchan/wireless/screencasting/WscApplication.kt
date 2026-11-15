package com.benchan.wireless.screencasting

import android.app.Application
import android.util.Log
import com.benchan.wireless.screencasting.protocol.dlna.dmc.DmcCache
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

    @Inject
    lateinit var directShare: com.benchan.wireless.screencasting.feature.sender.share.DirectShareShortcuts

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            WscLog.addSink(LogcatSink)
            // 仅 debug：主线程网络直接崩溃（系统默认已对网络如此，这里显式写出并记录磁盘读写与慢调用，防止回归，第 2 轮复测）
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectNetwork().detectCustomSlowCalls().detectDiskWrites()
                    .penaltyLog().penaltyDeathOnNetwork()
                    .build(),
            )
            android.os.StrictMode.setVmPolicy(
                android.os.StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog().build(),
            )
        }
        WscLog.addSink(diagnosticLog)
        WscLog.i("App", "start ${BuildConfig.VERSION_NAME} appleCompat=${BuildConfig.AIRPLAY_AVAILABLE}")
        // DLNA 推送：清理上次异常退出留下的转换文件 / 照片缓存（PRD 8.1.1 F2）
        Thread({ DmcCache.cleanup(this) }, "wsc-dmc-cleanup").start()
        // F14：清理进程被杀 / 重启前留下的分享副本（PRD 8.1.2 D“清理”）；按推送记录维护 Direct Share 快捷方式
        com.benchan.wireless.screencasting.feature.sender.share.ShareStaging.cleanupStale(this)
        directShare.start()
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
