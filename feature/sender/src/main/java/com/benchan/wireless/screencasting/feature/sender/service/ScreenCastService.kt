package com.benchan.wireless.screencasting.feature.sender.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.protocol.quality.QualityProfile
import com.benchan.wireless.screencasting.core.protocol.control.ControlPhase
import com.benchan.wireless.screencasting.core.protocol.control.ControlReason
import com.benchan.wireless.screencasting.feature.control.ControlActionReceiver
import com.benchan.wireless.screencasting.feature.control.ControlUi
import com.benchan.wireless.screencasting.feature.control.SenderControlCoordinator
import com.benchan.wireless.screencasting.feature.sender.R
import com.benchan.wireless.screencasting.feature.sender.session.SenderController
import com.benchan.wireless.screencasting.feature.sender.session.SenderState
import com.benchan.wireless.screencasting.feature.sender.session.isTerminal
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 镜像前台服务（PRD S-01、技术方案 3.2）：
 * Android 14+ 必须先以 mediaProjection 类型 startForeground，**然后**才能 getMediaProjection()
 * （由 SenderRtcSession 内的 ScreenCapturerAndroid.startCapture 调用）。
 * 通知显示实时延迟 / 码率并提供“停止”“切换为流畅”（交互稿 07 通知样式，不申请悬浮窗，Q1）。
 * 镜像期间保持亮屏（决策 #17：锁屏会导致系统停止投屏）。
 */
@AndroidEntryPoint
class ScreenCastService : LifecycleService() {
    @Inject
    lateinit var controller: SenderController

    @Inject
    lateinit var control: SenderControlCoordinator

    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                controller.stop()
                return START_NOT_STICKY
            }
            ACTION_SMOOTH -> {
                controller.setQuality(QualityProfile.SMOOTH)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (started) return START_NOT_STICKY
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
                if (data == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                ensureChannel(this)
                // 先 startForeground(mediaProjection)，再交给会话创建 MediaProjection
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, buildNotification(controller.state.value, control.ui.value),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                )
                started = true
                acquireWakeLock()
                controller.onProjectionGranted(this, data)
                observeState()
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun observeState() {
        lifecycleScope.launch {
            combine(controller.state, control.ui) { s, c -> s to c }.collect { (s, c) ->
                when (s) {
                    is SenderState.Starting, is SenderState.Casting, is SenderState.Reconnecting -> {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(s, c))
                    }
                    // 首个值通常还是 ReadyToCapture（onProjectionGranted 异步切到 Starting），不能据此停服务
                    else -> if (s.isTerminal) {
                        WscLog.i(TAG, "session ended ($s), stopping service")
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun buildNotification(state: SenderState, control: ControlUi): Notification {
        // 反向控制中（交互稿 R04）：标题“××正在控制本机”，按钮“收回控制 / 停止投屏”，小图标换成反向控制图标（状态栏任何界面可见）
        val controlled = control.controlled && (state is SenderState.Casting || state is SenderState.Reconnecting)
        val (title, text) = when (state) {
            is SenderState.Casting if controlled && control.phase == ControlPhase.PAUSED && control.reason == ControlReason.SENSITIVE_APP ->
                "${control.peerName} 正在控制本机" to "控制已暂停 · 此界面不可控制"
            is SenderState.Casting if controlled -> "${control.peerName} 正在控制本机" to buildString {
                append("投屏中 · ")
                state.stats.latencyMs?.let { append(getString(R.string.sender_notif_latency, it)).append(" · ") }
                append(state.profile.label)
            }
            is SenderState.Casting -> getString(R.string.sender_notif_casting_to, state.target.name) to buildString {
                state.stats.latencyMs?.let { append(getString(R.string.sender_notif_latency, it)).append(" · ") }
                if (state.stats.bitrateKbps > 0) append(String.format(java.util.Locale.US, "%.1f Mbps", state.stats.bitrateKbps / 1000.0)).append(" · ")
                append(state.profile.label)
            }
            is SenderState.Reconnecting -> getString(R.string.sender_notif_reconnecting) to getString(R.string.sender_notif_reconnecting_detail, state.target.name)
            is SenderState.Starting -> getString(R.string.sender_notif_casting_to, state.target.name) to getString(R.string.sender_notif_starting)
            else -> getString(R.string.sender_notif_title) to ""
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) }
        val contentIntent = launch?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, ScreenCastService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (controlled) ControlActionReceiver.statusIcon else R.drawable.ic_stat_cast)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent)
        if (controlled) {
            // 敏感界面暂停：[继续控制][收回控制][停止投屏]（交互稿 v1.0.4 通知示意）
            if (control.phase == ControlPhase.PAUSED && control.reason == ControlReason.SENSITIVE_APP) {
                b.addAction(0, "继续控制", ControlActionReceiver.continueIntent(this))
            }
            b.addAction(0, "收回控制", ControlActionReceiver.revokeIntent(this))
            b.addAction(0, "停止投屏", stopIntent)
            return b.build()
        }
        b.addAction(0, getString(R.string.sender_stop), stopIntent)
        if (state is SenderState.Casting && state.profile != QualityProfile.SMOOTH) {
            val smooth = PendingIntent.getService(
                this, 2, Intent(this, ScreenCastService::class.java).setAction(ACTION_SMOOTH), PendingIntent.FLAG_IMMUTABLE,
            )
            b.addAction(0, getString(R.string.sender_switch_smooth), smooth)
        }
        return b.build()
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        // SCREEN_BRIGHT_WAKE_LOCK 已标记废弃但仍有效；镜像的是其他 App，没法依赖本 Activity 的 FLAG_KEEP_SCREEN_ON
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "wsc:mirroring")
            .apply {
                setReferenceCounted(false)
                acquire(MAX_WAKE_MS)
            }
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (started && controller.isSessionActive) controller.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScreenCastService"
        const val CHANNEL_ID = "casting"
        const val NOTIFICATION_ID = 0x5753
        const val ACTION_START = "com.benchan.wireless.screencasting.action.START_MIRROR"
        const val ACTION_STOP = "com.benchan.wireless.screencasting.action.STOP_MIRROR"
        const val ACTION_SMOOTH = "com.benchan.wireless.screencasting.action.SMOOTH"
        private const val EXTRA_DATA = "projection_data"
        private const val MAX_WAKE_MS = 6L * 60 * 60 * 1000

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.sender_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                        description = context.getString(R.string.sender_channel_desc)
                        setShowBadge(false)
                    },
                )
            }
        }

        /** 录屏授权结果只能用一次（Android 14+），立即交给前台服务。 */
        fun start(context: Context, resultCode: Int, data: Intent) {
            if (resultCode != Activity.RESULT_OK) return
            val intent = Intent(context, ScreenCastService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenCastService::class.java).setAction(ACTION_STOP))
        }
    }
}
