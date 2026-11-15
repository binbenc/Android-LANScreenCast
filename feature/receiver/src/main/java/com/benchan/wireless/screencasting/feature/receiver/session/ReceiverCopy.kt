package com.benchan.wireless.screencasting.feature.receiver.session

import com.benchan.wireless.screencasting.core.protocol.WscProtocol
import com.benchan.wireless.screencasting.feature.receiver.R

/** 接收端（TV）界面文案的纯函数部分，便于单测。错误码只写日志，界面只出现人话 + 下一步。文字在资源里（英文默认 / values-zh）。 */
internal object ReceiverCopy {

    /** DLNA 推送播放失败（格式不支持 / 链接失效）后回到待机页的提示（C2）。 */
    val DLNA_PLAYBACK_FAILED: UiText = uiText(R.string.rx_notice_dlna_failed)

    /** 后台开始的视频约 10 秒内没有打开播放画面，已停止（R1）。 */
    val VIDEO_NO_DISPLAY: UiText = uiText(R.string.rx_notice_video_no_display)

    /** 识别不出推送方 App 时的名称（C3）。 */
    val DLNA_UNKNOWN_APP: UiText = uiText(R.string.rx_dlna_unknown_app)

    /**
     * 投屏码不可用的原因；null 表示投屏码可用。投屏码只编码 IP 末段，固定连 8443 端口：
     * 端口被占用回退到随机端口时，投屏码会连错，必须隐藏（C1）。
     */
    fun castCodeUnavailableReason(lanAvailable: Boolean, port: Int?, networkSupported: Boolean): UiText? = when {
        !lanAvailable -> null // 没有网络时另有整行提示
        port != null && port != WscProtocol.PREFERRED_PORT -> uiText(R.string.rx_cast_code_port_busy)
        !networkSupported -> uiText(R.string.rx_cast_code_network_unsupported)
        else -> null
    }

    /**
     * DLNA 推送方的可读名称（C3 / E7）。只有已知的视频 App 显示 App 名；其余（“Linux/…”“Dalvik/…”“okhttp/…”“curl/…”
     * 之类的系统 / 库 / 工具名）一律显示通用描述，IP 在副标题里。
     */
    fun dlnaPeerLabel(userAgent: String?): UiText {
        val lower = userAgent?.trim()?.lowercase().orEmpty()
        if (lower.isEmpty()) return DLNA_UNKNOWN_APP
        return KNOWN_APPS.firstOrNull { (keys, _) -> keys.any { it in lower } }?.second ?: DLNA_UNKNOWN_APP
    }

    /** 发送端协议版本不兼容时待机页的提示（C4）。peerName 为空时用“对方设备”。 */
    fun versionMismatchNotice(peerName: String, peerVersion: Int, localVersion: Int = WscProtocol.VERSION): UiText {
        val name: Any = if (peerName.isBlank()) uiText(R.string.rx_peer_device) else peerName
        return if (peerVersion < localVersion) {
            uiText(R.string.rx_notice_peer_outdated, name)
        } else {
            uiText(R.string.rx_notice_local_outdated, name)
        }
    }

    private val KNOWN_APPS: List<Pair<List<String>, UiText>> = listOf(
        listOf("bilibili") to uiText(R.string.rx_app_bilibili),
        listOf("iqiyi", "qiyi") to uiText(R.string.rx_app_iqiyi),
        listOf("qqlive", "tencentvideo", "tencent video") to uiText(R.string.rx_app_tencent_video),
        listOf("youku") to uiText(R.string.rx_app_youku),
        listOf("mgtv", "hunantv", "imgo") to uiText(R.string.rx_app_mgtv),
        listOf("vlc") to UiText.Raw("VLC"),
        listOf("kodi") to UiText.Raw("Kodi"),
        listOf("youtube") to UiText.Raw("YouTube"),
        listOf("bubbleupnp") to UiText.Raw("BubbleUPnP"),
    )
}
