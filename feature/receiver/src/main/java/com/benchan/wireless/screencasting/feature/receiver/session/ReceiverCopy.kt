package com.benchan.wireless.screencasting.feature.receiver.session

import com.benchan.wireless.screencasting.core.protocol.WscProtocol

/** 接收端（TV）界面文案的纯函数部分，便于单测。错误码只写日志，界面只出现人话 + 下一步。 */
internal object ReceiverCopy {

    /** DLNA 推送播放失败（格式不支持 / 链接失效）后回到待机页的提示（C2）。 */
    const val DLNA_PLAYBACK_FAILED = "无法播放该视频（格式不支持或链接已失效），请在手机上换一个视频或清晰度再试"

    /** 后台开始的视频约 10 秒内没有打开播放画面，已停止（R1）。 */
    const val VIDEO_NO_DISPLAY = "视频已停止：播放开始时本应用没有在前台显示。请先打开本应用，再重新投屏"

    /** 识别不出推送方 App 时的名称（C3）。 */
    const val DLNA_UNKNOWN_APP = "局域网内的视频 App"

    /**
     * 投屏码不可用的原因；null 表示投屏码可用。投屏码只编码 IP 末段，固定连 8443 端口：
     * 端口被占用回退到随机端口时，投屏码会连错，必须隐藏（C1）。
     */
    fun castCodeUnavailableReason(lanAvailable: Boolean, port: Int?, networkSupported: Boolean): String? = when {
        !lanAvailable -> null // 没有网络时另有整行提示
        port != null && port != WscProtocol.PREFERRED_PORT -> "投屏码暂不可用（端口被占用），请扫码或在手机上手动输入下方地址"
        !networkSupported -> "当前网络不支持投屏码，请扫码或手动输入下方地址"
        else -> null
    }

    /**
     * DLNA 推送方的可读名称（C3 / E7）。只有已知的视频 App 显示 App 名；其余（“Linux/…”“Dalvik/…”“okhttp/…”“curl/…”
     * 之类的系统 / 库 / 工具名）一律显示通用描述，IP 在副标题里。
     */
    fun dlnaPeerLabel(userAgent: String?): String {
        val lower = userAgent?.trim()?.lowercase().orEmpty()
        if (lower.isEmpty()) return DLNA_UNKNOWN_APP
        return KNOWN_APPS.firstOrNull { (keys, _) -> keys.any { it in lower } }?.second ?: DLNA_UNKNOWN_APP
    }

    /** 发送端协议版本不兼容时待机页的提示（C4）。 */
    fun versionMismatchNotice(peerName: String, peerVersion: Int, localVersion: Int = WscProtocol.VERSION): String {
        val name = peerName.ifBlank { "对方设备" }
        return if (peerVersion < localVersion) {
            "$name 上的应用版本过旧，无法投屏到本机。请在 $name 上更新应用后再试"
        } else {
            "本机应用版本过旧，无法接收 $name 的投屏。请更新本机上的应用后再试"
        }
    }

    private val KNOWN_APPS: List<Pair<List<String>, String>> = listOf(
        listOf("bilibili") to "哔哩哔哩",
        listOf("iqiyi", "qiyi") to "爱奇艺",
        listOf("qqlive", "tencentvideo", "tencent video") to "腾讯视频",
        listOf("youku") to "优酷",
        listOf("mgtv", "hunantv", "imgo") to "芒果TV",
        listOf("vlc") to "VLC",
        listOf("kodi") to "Kodi",
        listOf("youtube") to "YouTube",
        listOf("bubbleupnp") to "BubbleUPnP",
    )
}
