package com.benchan.wireless.screencasting

import android.content.Context
import com.benchan.wireless.screencasting.core.ui.util.DeviceKind

/** 按设备类型选默认模式（决策 #5）：TV → 接收端；手机 / 平板 → 发送端。 */
object DeviceType {
    fun isTv(context: Context): Boolean = DeviceKind.isTv(context)
}
