package com.benchan.wireless.screencasting.core.ui.util

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration

/** 设备类型判断（决策 #5：TV → 接收端；手机 / 平板 → 发送端）。界面之外（开机广播、控制器）也要用。 */
object DeviceKind {
    fun isTv(context: Context): Boolean {
        if (debugForceTv(context)) return true
        val ui = context.getSystemService(UiModeManager::class.java)
        return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
            // 兜底：没有触摸屏的设备（很多国产电视 / 盒子不声明 leanback）也按 TV 处理（S-g）
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    /**
     * 仅可调试构建：`adb shell setprop debug.wsc.force_tv 1` 让手机模拟器按 TV 界面运行
     * （官方 TV 模拟器镜像没有虚拟 Wi‑Fi，无法与另一台模拟器组网验证 TV 端反向控制）。Release 包不可调试，永远返回 false。
     */
    @SuppressLint("PrivateApi") // 仅可调试构建读取 debug.* 系统属性
    private fun debugForceTv(context: Context): Boolean {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return false
        return runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, "debug.wsc.force_tv") == "1"
        }.getOrDefault(false)
    }
}
