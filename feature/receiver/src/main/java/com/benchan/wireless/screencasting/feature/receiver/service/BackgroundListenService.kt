package com.benchan.wireless.screencasting.feature.receiver.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.feature.receiver.R
import com.benchan.wireless.screencasting.feature.receiver.session.ReceiverController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 开机自启的后台监听保活（模拟器验证 E2）：没有前台服务时进程约 100 秒后被系统当作空进程回收。
 *
 * - 类型 connectedDevice：在局域网内与其他设备（手机）保持连接；所需权限 CHANGE_WIFI_MULTICAST_STATE 已声明。
 *   Android 15 起 BOOT_COMPLETED 不能启动 mediaPlayback / dataSync 等类型，connectedDevice 不在限制列表；
 *   Android 12+ 的“后台启动前台服务”限制对 BOOT_COMPLETED 有豁免。不用 specialUse（需额外说明，F-Droid / 审核不友好）。
 * - 低打扰常驻通知（IMPORTANCE_MIN）：“正在后台等待投屏”+“停止”。
 * - 关闭“开机自启”、切到发送端或点“停止”时由 [ReceiverController] 停止本服务。
 */
@AndroidEntryPoint
class BackgroundListenService : LifecycleService() {
    @Inject
    lateinit var controller: ReceiverController

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            // 用户在通知上点“停止”：撤销本次后台监听（不改设置，下次开机仍会启动）
            controller.stopBackgroundListening()
            stopNow()
            return START_NOT_STICKY
        }
        ensureChannel(this)
        val ok = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }.onFailure { WscLog.w(TAG, "startForeground failed", it) }.isSuccess
        if (!ok) {
            stopSelf()
            return START_NOT_STICKY
        }
        controller.startBackgroundListening()
        return START_NOT_STICKY
    }

    private fun stopNow() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun build() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_receive)
        .setContentTitle(getString(R.string.receiver_bg_title))
        .setContentText(getString(R.string.receiver_bg_text))
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                PendingIntent.getActivity(this, 20, it, PendingIntent.FLAG_IMMUTABLE)
            },
        )
        .addAction(
            0, getString(R.string.receiver_bg_stop),
            PendingIntent.getService(this, 21, Intent(this, BackgroundListenService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE),
        )
        .build()

    companion object {
        private const val TAG = "BgListenService"
        private const val CHANNEL_ID = "receiver_background"
        private const val NOTIFICATION_ID = 0x5755
        private const val ACTION_STOP = "com.benchan.wireless.screencasting.action.STOP_BACKGROUND_LISTEN"

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.receiver_bg_channel_name), NotificationManager.IMPORTANCE_MIN).apply {
                        description = context.getString(R.string.receiver_bg_channel_desc)
                        setShowBadge(false)
                    },
                )
            }
        }

        /** 开机广播里启动（BOOT_COMPLETED 可从后台启动前台服务）。 */
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, BackgroundListenService::class.java)) }
                .onFailure { WscLog.w(TAG, "start background listen service failed", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, BackgroundListenService::class.java)) }
        }
    }
}
