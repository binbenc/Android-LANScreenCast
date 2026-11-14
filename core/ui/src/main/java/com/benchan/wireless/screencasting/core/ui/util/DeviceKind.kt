package com.benchan.wireless.screencasting.core.ui.util

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/** 设备类型判断（决策 #5：TV → 接收端；手机 / 平板 → 发送端）。界面之外（开机广播、控制器）也要用。 */
object DeviceKind {
    fun isTv(context: Context): Boolean {
        val ui = context.getSystemService(UiModeManager::class.java)
        return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
            // 兜底：没有触摸屏的设备（很多国产电视 / 盒子不声明 leanback）也按 TV 处理（S-g）
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }
}
