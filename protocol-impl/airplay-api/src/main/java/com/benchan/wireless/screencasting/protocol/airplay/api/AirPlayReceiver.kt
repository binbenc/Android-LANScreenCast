package com.benchan.wireless.screencasting.protocol.airplay.api

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** 兼容 Apple 设备的会话内容类型。 */
enum class AppleSessionKind {
    /** 屏幕镜像（H.264/H.265 → MediaCodec → Surface）。 */
    MIRROR,

    /** 仅音频（RAOP：ALAC / AAC → AudioTrack）。 */
    AUDIO,

    /** 视频网址（HLS → Media3 ExoPlayer）。 */
    VIDEO_URL,
}

/** 当前正在播放的 Apple 设备会话。 */
data class AppleSession(
    val id: String,
    val peerName: String,
    val peerModel: String,
    val kind: AppleSessionKind,
    val startedAt: Long,
    val firstFrameAt: Long? = null,
    val width: Int = 0,
    val height: Int = 0,
    val codec: String? = null,
    /** 仅音频时的曲目信息（DMAP 元数据，可能为空）。 */
    val trackTitle: String? = null,
    val trackArtist: String? = null,
)

/** 兼容 Apple 设备接收端的运行状态。界面文案统一为“兼容 Apple 设备（非官方）”，不出现 AirPlay 字样（PRD 12）。 */
sealed interface AppleCompatState {
    data object Stopped : AppleCompatState

    /** 已注册 mDNS，等待连接。[pin] 非空表示发送端正在配对，需要在电视上显示该 PIN。 */
    data class Advertising(val pin: String?) : AppleCompatState

    data class Active(val session: AppleSession) : AppleCompatState

    /** 启动失败；界面按 [reason] 显示“原因 + 下一步”（文案在界面层资源里，跟随系统语言）。 */
    data class Failed(val reason: AppleFailure) : AppleCompatState
}

/** 启动失败的原因（界面映射为文案；返回码只进日志）。 */
enum class AppleFailure {
    /** 无法在局域网中公布本机（mDNS 初始化 / 注册失败）。 */
    PUBLISH_FAILED,

    /** 网络端口被占用。 */
    PORT_IN_USE,

    /** 其他启动错误。 */
    START_ERROR,

    /** 安装包不含原生库（构建开关关闭或 ABI 缺失）。 */
    NOT_PACKAGED,
}

/** 会话结束原因（用于待机页提示与本机报告）。 */
enum class AppleEndReason {
    /** 发送端停止（停止镜像 / 锁屏 / 退出播放）。iPhone 锁屏与主动停止在协议上无法区分。 */
    PEER_STOPPED,

    /** 网络中断（UxPlay conn_reset reason=1 或连接全部断开且未收到停止）。 */
    CONNECTION_LOST,

    /** 接收端断开（连按两次返回）或被其他协议的新会话替换（R-09）。 */
    RECEIVER_STOPPED,

    /** 发送端请求了不支持的内容（例如 FairPlay 保护的视频网址）。 */
    UNSUPPORTED,
}

/** 一次会话结束时的摘要，由接收端写入本机报告（F25）。 */
data class AppleSessionSummary(
    val session: AppleSession,
    val endedAt: Long,
    val reason: AppleEndReason,
)

/**
 * 是否接受新的 Apple 设备连接（R-09 / PRD 8.5）。在 UxPlay 的 httpd 线程上同步等待，实现需在超时内返回。
 */
fun interface AppleAdmission {
    suspend fun admit(peerName: String, peerModel: String, deviceId: String): Boolean
}

/**
 * 兼容 Apple 设备（非官方）接收端接口（技术方案 3.8）。
 * 默认构建不包含实现模块（`-Pwsc.airplay=true` 才包含），此时注入的 Optional 为空，设置页隐藏开关。
 */
interface AirPlayReceiver {
    val state: StateFlow<AppleCompatState>

    /** 会话结束事件（每次镜像 / 音频 / 视频网址播放结束一次）。 */
    val sessionEnded: SharedFlow<AppleSessionSummary>

    /** 连接准入回调（默认全部允许）。 */
    var admission: AppleAdmission?

    /** 开启后才注册 _airplay._tcp / _raop._tcp（PRD R-08）。参数未变化时重复调用不会重启服务。 */
    fun start(deviceName: String, requirePin: Boolean)

    fun stop()

    /** 断开当前发送端但保持可被发现（接收端连按两次返回、或被其他协议替换）。不能在原生回调线程上调用。 */
    fun disconnect()

    /** 镜像画面输出的 Surface（接收端播放页提供，null 表示释放）。 */
    fun setVideoSurface(surface: android.view.Surface?)

    /** 视频网址播放器（Media3），供播放页的 PlayerView 绑定；没有视频网址会话时为 null。 */
    val videoPlayer: androidx.media3.common.Player?
}
