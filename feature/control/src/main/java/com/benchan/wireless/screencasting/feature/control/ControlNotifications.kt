package com.benchan.wireless.screencasting.feature.control

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.benchan.wireless.screencasting.feature.control.ui.AccessibilityGuideActivity
import com.benchan.wireless.screencasting.feature.control.ui.ControlConsentActivity

/**
 * 反向控制的通知（交互稿 R03 / R05）。
 * - 请求通知：高优先级 + 全屏意图（系统不允许时退化为悬浮通知）；**只提供“拒绝”**，允许必须进入确认页完成。
 * - 服务未开启：点按进入开启步骤（R02）。
 * - 结束提示：例如“已收回控制：手机已锁屏”。
 * 被控制中的常驻提示由投屏前台服务的通知承担（标题“××正在控制本机”+“收回控制”）。
 */
internal object ControlNotifications {
    private const val CHANNEL_ID = "remote_control"
    const val REQUEST_ID = 0x5754
    private const val INFO_ID = 0x5755

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.ctl_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.ctl_channel_desc)
                    setShowBadge(false)
                },
            )
        }
    }

    fun enabled(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** 请求通知能否显示：总开关打开且“反向控制请求”渠道没有被设为不显示（IMPORTANCE_NONE）。 */
    fun requestChannelUsable(context: Context): Boolean {
        if (!enabled(context)) return false
        val ch = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID) ?: return true
        return ch.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * 请求通知；每秒刷新倒计时（只提醒一次）。[silent] = 用户已经打开过确认页又离开：保留通知方便回到确认页，但不再悬浮。
     */
    fun showRequest(context: Context, peer: String, secondsLeft: Int, silent: Boolean = false) {
        if (!enabled(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 10, ControlConsentActivity.intent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val deny = PendingIntent.getBroadcast(
            context, 11, Intent(context, ControlActionReceiver::class.java).setAction(ControlActionReceiver.ACTION_DENY), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_control)
            .setContentTitle(context.getString(R.string.ctl_request_title, peer))
            .setContentText(context.resources.getQuantityString(R.plurals.ctl_request_text, secondsLeft, secondsLeft))
            .setPriority(if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(silent)
            .setAutoCancel(true)
            .setContentIntent(open)
        if (!silent) n.setFullScreenIntent(open, true)
        n
            .setTimeoutAfter((secondsLeft + 1) * 1_000L)
            .addAction(0, context.getString(R.string.ctl_deny), deny)
        runCatching { context.getSystemService(NotificationManager::class.java).notify(REQUEST_ID, n.build()) }
    }

    fun cancelRequest(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(REQUEST_ID)
    }

    /** 对方请求控制，但本机无障碍服务未开启：引导到开启步骤（R08“对方手机已显示开启步骤”）。返回是否真的显示了。 */
    fun showServiceGuide(context: Context, peer: String): Boolean {
        if (!requestChannelUsable(context)) return false
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 12, Intent(context, AccessibilityGuideActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_control)
            .setContentTitle(context.getString(R.string.ctl_request_title, peer))
            .setContentText(context.getString(R.string.ctl_guide_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        return runCatching { context.getSystemService(NotificationManager::class.java).notify(INFO_ID, n) }.isSuccess
    }

    /** 被控制期间无障碍服务被系统 / ROM 关闭（PM 走查 G5）：结束提示 + 开启入口。 */
    fun showServiceStopped(context: Context) {
        if (!enabled(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 14, Intent(context, AccessibilityGuideActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_control)
            .setContentTitle(context.getString(R.string.ctl_service_stopped_title))
            .setContentText(context.getString(R.string.ctl_service_stopped_text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(INFO_ID, n) }
    }

    /** 控制结束提示（例如锁屏收回）。 */
    fun showEnded(context: Context, text: String) {
        if (!enabled(context)) return
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_control)
            .setContentTitle(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(INFO_ID, n) }
    }
}
