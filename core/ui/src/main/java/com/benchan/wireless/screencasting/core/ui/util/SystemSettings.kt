package com.benchan.wireless.screencasting.core.ui.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** 打开本应用的系统“应用信息”页（权限被永久拒绝后的“去系统设置”入口）。 */
fun openAppDetailsSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** 打开本应用的系统通知设置页（通知权限被永久拒绝后的入口）；不支持时退回应用信息页。 */
fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure { openAppDetailsSettings(context) }
}
