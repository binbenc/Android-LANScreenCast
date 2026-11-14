package com.benchan.wireless.screencasting.feature.receiver.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.feature.receiver.R
import com.benchan.wireless.screencasting.feature.receiver.session.ReceiverController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 后台（开机自启、App 不在前台）收到投屏时，用通知把接收端界面拉到前台（B2 / P1）。
 *
 * - 确认请求：提醒渠道（有提示音）+ full-screen intent，带“拒绝”操作；30 秒未确认自动拒绝。
 * - 开始播放：静音渠道（不响铃不振动，P3），仍用 full-screen intent 尝试打开播放页；约 10 秒内没有画面会话会结束。
 * - 类别用 CATEGORY_STATUS（不是来电）。系统不允许 full-screen intent 时退化为带 contentIntent 的通知。
 * - 需要通知权限（Android 13+，开启“开机自启”时申请）；部分电视系统不显示通知，此时只能先打开本应用。
 */
internal object ReceiverAlerts {
    private const val TAG = "ReceiverAlerts"
    private const val CHANNEL_CONSENT = "receiver_alerts"
    private const val CHANNEL_PLAYBACK = "receiver_playback_silent"
    private const val NOTIFICATION_ID = 0x5754
    internal const val ACTION_REJECT = "com.benchan.wireless.screencasting.action.REJECT_REQUEST"
    internal const val EXTRA_REQUEST_ID = "request_id"

    /** 需要用户在电视上确认的投屏请求。 */
    fun showConsent(context: Context, requestId: Long, peerName: String) {
        val reject = PendingIntent.getBroadcast(
            context, 11,
            Intent(context, ReceiverAlertActionReceiver::class.java).setAction(ACTION_REJECT).putExtra(EXTRA_REQUEST_ID, requestId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        post(
            context, CHANNEL_CONSENT,
            context.getString(R.string.receiver_alert_consent_title, peerName),
            context.getString(R.string.receiver_alert_consent_text),
        ) { it.addAction(0, context.getString(R.string.receiver_alert_reject), reject) }
    }

    /** 已开始播放（可信设备免确认 / DLNA / Apple），但界面不在前台。 */
    fun showPlayback(context: Context, peerName: String) = post(
        context, CHANNEL_PLAYBACK,
        context.getString(R.string.receiver_alert_playback_title, peerName),
        context.getString(R.string.receiver_alert_playback_text),
    ) { it.setSilent(true) }

    fun cancel(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
    }

    private fun ensureChannels(context: Context, nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL_CONSENT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_CONSENT, context.getString(R.string.receiver_alert_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.receiver_alert_channel_desc)
                    setShowBadge(false)
                },
            )
        }
        if (nm.getNotificationChannel(CHANNEL_PLAYBACK) == null) {
            // 静音渠道：保持 HIGH 以便 full-screen intent 能打开界面，但不响铃、不振动
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PLAYBACK, context.getString(R.string.receiver_alert_playback_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.receiver_alert_playback_channel_desc)
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
            )
        }
    }

    private fun post(context: Context, channel: String, title: String, text: String, extra: (NotificationCompat.Builder) -> Unit) {
        val nm = context.getSystemService(NotificationManager::class.java)
        ensureChannels(context, nm)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?: return
        val pi = PendingIntent.getActivity(context, 10, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val canFullScreen = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_receive)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setTimeoutAfter(35_000)
            .setContentIntent(pi)
            .apply { if (canFullScreen) setFullScreenIntent(pi, true) }
        extra(b)
        runCatching { nm.notify(NOTIFICATION_ID, b.build()) }.onFailure { WscLog.w(TAG, "notify failed (no permission?)", it) }
    }
}

/** 确认通知上的“拒绝”。 */
@AndroidEntryPoint
class ReceiverAlertActionReceiver : BroadcastReceiver() {
    @Inject
    lateinit var controller: ReceiverController

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReceiverAlerts.ACTION_REJECT) return
        val id = intent.getLongExtra(ReceiverAlerts.EXTRA_REQUEST_ID, -1)
        if (id >= 0) controller.respondToRequest(id, allowed = false)
        ReceiverAlerts.cancel(context)
    }
}
