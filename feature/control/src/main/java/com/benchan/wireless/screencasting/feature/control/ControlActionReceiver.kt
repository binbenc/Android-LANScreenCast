package com.benchan.wireless.screencasting.feature.control

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** 通知上的操作：拒绝控制请求（R03 通知只给“拒绝”）/ 收回控制、继续控制（投屏通知）。不导出，只有本应用的 PendingIntent 能触发。 */
@AndroidEntryPoint
class ControlActionReceiver : BroadcastReceiver() {
    @Inject
    lateinit var coordinator: SenderControlCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DENY -> coordinator.deny()
            ACTION_REVOKE -> coordinator.revoke()
            ACTION_CONTINUE -> coordinator.continueDespiteSensitive()
        }
    }

    companion object {
        const val ACTION_DENY = "com.benchan.wireless.screencasting.action.CONTROL_DENY"
        const val ACTION_REVOKE = "com.benchan.wireless.screencasting.action.CONTROL_REVOKE"
        const val ACTION_CONTINUE = "com.benchan.wireless.screencasting.action.CONTROL_CONTINUE"

        /** 投屏通知的“继续控制”按钮（仅敏感界面暂停时出现，交互稿 v1.0.4）。 */
        fun continueIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 14, Intent(context, ControlActionReceiver::class.java).setAction(ACTION_CONTINUE), PendingIntent.FLAG_IMMUTABLE,
        )

        /** 投屏通知的“收回控制”按钮。 */
        fun revokeIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 13, Intent(context, ControlActionReceiver::class.java).setAction(ACTION_REVOKE), PendingIntent.FLAG_IMMUTABLE,
        )

        /** 被控制期间通知小图标（状态栏常驻，交互稿 R04）。 */
        val statusIcon: Int get() = R.drawable.ic_stat_control
    }
}
