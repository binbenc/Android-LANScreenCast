package com.benchan.wireless.screencasting

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/** 按设备类型选默认模式（决策 #5）：TV → 接收端；手机 / 平板 → 发送端。 */
object DeviceType {
    fun isTv(context: Context): Boolean {
        val ui = context.getSystemService(UiModeManager::class.java)
        return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
    }
}
