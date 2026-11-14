package com.benchan.wireless.screencasting.feature.receiver.service

import android.app.Notification
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

/**
 * 接收中前台服务（PRD R-05）：正在接收时使用 mediaPlayback 类型，防止国产电视后台查杀。
 * 待机监听不需要前台服务（只在 App 前台时开启）。
 */
class ReceiverService : LifecycleService() {

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val peer = intent?.getStringExtra(EXTRA_PEER).orEmpty()
        ensureChannel(this)
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(peer), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        }.onFailure { WscLog.w(TAG, "startForeground failed", it) }
        return START_NOT_STICKY
    }

    private fun build(peer: String): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pi = launch?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_receive)
            .setContentTitle(getString(R.string.receiver_notif_title))
            .setContentText(if (peer.isBlank()) getString(R.string.receiver_notif_generic) else getString(R.string.receiver_notif_from, peer))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "ReceiverService"
        private const val CHANNEL_ID = "receiving"
        private const val NOTIFICATION_ID = 0x5752
        private const val EXTRA_PEER = "peer"
        private const val ACTION_STOP = "com.benchan.wireless.screencasting.action.STOP_RECEIVING"

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.receiver_channel_name), NotificationManager.IMPORTANCE_LOW))
            }
        }

        fun start(context: Context, peer: String) {
            runCatching {
                context.startForegroundService(Intent(context, ReceiverService::class.java).putExtra(EXTRA_PEER, peer))
            }.onFailure { WscLog.w(TAG, "start receiver service failed", it) }
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, ReceiverService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
