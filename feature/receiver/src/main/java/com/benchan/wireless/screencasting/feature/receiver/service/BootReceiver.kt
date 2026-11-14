package com.benchan.wireless.screencasting.feature.receiver.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.data.settings.SettingsRepository
import com.benchan.wireless.screencasting.feature.receiver.session.ReceiverController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 开机自启（决策 #14，默认关闭）。Android 15 起禁止从 BOOT_COMPLETED 启动 mediaPlayback 等类型的前台服务，
 * 所以这里只启动发现服务（WSC 监听 + mDNS 注册），收到投屏时再升级为前台服务（PRD R-05）。
 * 局限：没有前台服务时进程可能很快被系统或厂商 ROM 回收，效果需真机验证。
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var controller: ReceiverController

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                if (settings.current().bootStart) {
                    WscLog.i("BootReceiver", "boot start enabled: starting discovery listener")
                    controller.startListening()
                }
            } finally {
                pending.finish()
            }
        }
    }
}
