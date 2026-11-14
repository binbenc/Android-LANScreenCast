package com.benchan.wireless.screencasting.protocol.airplay.api

import kotlinx.coroutines.flow.StateFlow

/** 兼容 Apple 设备接收端的运行状态。界面文案统一为“兼容 Apple 设备（非官方）”，不出现 AirPlay 字样（PRD 12）。 */
sealed interface AppleCompatState {
    data object Stopped : AppleCompatState
    data class Advertising(val pin: String?) : AppleCompatState
    data class Mirroring(val peerName: String) : AppleCompatState
    data class Failed(val reason: String) : AppleCompatState
}

/**
 * 兼容 Apple 设备（非官方）接收端接口（技术方案 3.8）。
 * 默认构建不包含实现模块（`-Pwsc.airplay=true` 才包含），此时注入的 Optional 为空，设置页隐藏开关。
 */
interface AirPlayReceiver {
    val state: StateFlow<AppleCompatState>

    /** 开启后才注册 _airplay._tcp / _raop._tcp（PRD R-08）。 */
    fun start(deviceName: String, requirePin: Boolean)

    fun stop()

    /** 镜像画面输出的 Surface（接收端播放页提供，null 表示释放）。 */
    fun setVideoSurface(surface: android.view.Surface?)
}
