package com.benchan.wireless.screencasting.feature.receiver.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.data.settings.AppMode
import com.benchan.wireless.screencasting.core.data.settings.SettingsRepository
import com.benchan.wireless.screencasting.core.data.settings.effectiveMode
import com.benchan.wireless.screencasting.core.ui.util.DeviceKind
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 开机自启（决策 #14，默认关闭）。Android 15 起禁止从 BOOT_COMPLETED 启动 mediaPlayback 等类型的前台服务，
 * 所以这里只启动发现服务（WSC 监听 + mDNS 注册，不计入界面引用计数）。
 * 收到需要确认的请求或开始播放时，用高优先级通知（full-screen intent）把接收端界面拉到前台确认 / 播放（ReceiverAlerts）。
 * 局限：没有前台服务时进程可能很快被系统或厂商 ROM 回收；部分电视不显示通知；效果需真机验证（设置页开关说明已如实写明）。
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject
    lateinit var settings: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val s = settings.current()
                // 只在接收端模式下启动后台监听（P2）
                if (s.bootStart && s.effectiveMode(DeviceKind.isTv(context)) == AppMode.RECEIVER) {
                    WscLog.i("BootReceiver", "boot start enabled: starting discovery listener")
                    // 以前台服务承载后台监听，否则进程很快被回收（E2）
                    BackgroundListenService.start(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
