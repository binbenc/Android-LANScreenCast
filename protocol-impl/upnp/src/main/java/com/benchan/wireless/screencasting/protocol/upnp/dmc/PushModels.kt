package com.benchan.wireless.screencasting.protocol.upnp.dmc

import com.benchan.wireless.screencasting.protocol.upnp.MediaKind
import com.benchan.wireless.screencasting.protocol.upnp.MetadataLevel

enum class SeekUnit { REL_TIME, ABS_TIME }

/**
 * 每台电视试出来的有效方式（PRD 8.1.1 F：按 UDN 记在本机，下次直接用）。
 * 以字符串 Map 持久化，便于 :core:data 存储且向前兼容。
 */
data class RendererQuirks(
    val metadataLevel: MetadataLevel = MetadataLevel.FULL,
    /** 视频 / 音频换内容前先 Stop（默认开；照片默认不先 Stop，出错再 Stop）。 */
    val stopBeforeSet: Boolean = true,
    val seekUnit: SeekUnit = SeekUnit.REL_TIME,
    val seekUnsupported: Boolean = false,
) {
    fun toMap(): Map<String, String> = mapOf(
        "metadata" to metadataLevel.name, "stopBeforeSet" to stopBeforeSet.toString(),
        "seekUnit" to seekUnit.name, "seekUnsupported" to seekUnsupported.toString(),
    )

    companion object {
        fun fromMap(m: Map<String, String>?): RendererQuirks {
            if (m.isNullOrEmpty()) return RendererQuirks()
            return RendererQuirks(
                metadataLevel = m["metadata"]?.let { v -> MetadataLevel.entries.firstOrNull { it.name == v } } ?: MetadataLevel.FULL,
                stopBeforeSet = m["stopBeforeSet"]?.toBooleanStrictOrNull() ?: true,
                seekUnit = m["seekUnit"]?.let { v -> SeekUnit.entries.firstOrNull { it.name == v } } ?: SeekUnit.REL_TIME,
                seekUnsupported = m["seekUnsupported"]?.toBooleanStrictOrNull() ?: false,
            )
        }
    }
}

/** 播放列表中的一项（推送前就知道的信息）。 */
data class PlaylistEntry(val kind: MediaKind, val remote: Boolean)

/** 准备好、可以交给电视的一项（本地文件已注册到文件服务，照片已转 JPEG）。 */
data class PreparedMedia(
    val url: String,
    /** 上报给电视的 MIME；网址推断不出时为 null。 */
    val mime: String?,
    val kind: MediaKind,
    /** 只用于 DIDL-Lite（发给电视），不写日志。 */
    val title: String,
    val protocolInfo: String,
    val sizeBytes: Long? = null,
    val durationMs: Long? = null,
    val resolution: String? = null,
    val remote: Boolean = false,
    /**
     * 判断“电视当前播放的还是不是我们的”用的键：本地文件 = 会话令牌路径前缀（"/<token>/"），网址 = 主机名。
     */
    val ownershipKey: String,
)

sealed interface PrepareResult {
    data class Ready(val media: PreparedMedia) : PrepareResult
    data class Failed(val error: PushError) : PrepareResult

    /** 交给调用方处理（例如提议转换），引擎进入 FAILED 但不记为电视错误。 */
    data class Deferred(val error: PushError) : PrepareResult
}

fun interface MediaPreparer {
    suspend fun prepare(index: Int): PrepareResult
}

enum class PushPhase {
    IDLE, PREPARING, LOADING, PLAYING, PAUSED, SHOWING, FAILED,

    /** 等用户选择（推送前提议转换 / 正在转换），中性状态，不算失败（第 1 轮走查 PM P1-8）。 */
    AWAITING_CHOICE,
    ENDED,
}

enum class PushError {
    /** 电视不支持该格式（714/704、解码出错、加载后回到停止）。 */
    UNSUPPORTED_FORMAT,

    /** 电视拒绝（606 等）。 */
    REJECTED,

    /** 连不上电视。 */
    UNREACHABLE,

    /** 网址推送后电视打不开。 */
    URL_FAILED,

    /** 20 秒内没有结果。 */
    START_TIMEOUT,

    /** 本地文件读不到（授权失效等）。 */
    FILE_UNREADABLE,

    /** 需要转换（由调用方提议），或转换失败 / 被取消。 */
    NEEDS_TRANSCODE,
    TRANSCODE_FAILED,

    /** 手机没有局域网地址（开始推送前）。 */
    NO_NETWORK,

    /** 照片无法处理（解码失败，不同于授权失效）。 */
    DECODE_FAILED,
}

data class PushFailure(val error: PushError, val upnpCode: Int? = null, val httpStatus: Int? = null)

enum class PushEndReason {
    USER_STOPPED, STOPPED_ON_TV, LIST_FINISHED, TAKEN_OVER, DEVICE_LOST, NETWORK_CHANGED,

    /** 照片 30 分钟无操作。 */
    IDLE_TIMEOUT,

    /** 失败 / 等待选择后长时间没有操作（释放前台服务与 Wi-Fi 锁）。 */
    INACTIVE,

    /** 列表里的每一项电视都放不了（不能写“已放完”）。 */
    ALL_FAILED,

    /** 没能开始（没有局域网 / 文件服务启动失败）。 */
    START_FAILED,
}

enum class SyncMode { POLL, EVENTS }

data class PushState(
    val phase: PushPhase = PushPhase.IDLE,
    val index: Int = 0,
    val count: Int = 0,
    val kind: MediaKind? = null,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val volume: Int? = null,
    val muted: Boolean? = null,
    val canSeek: Boolean = true,
    val canPause: Boolean = true,
    val hasVolume: Boolean = false,
    /** 加载超过 10 秒：提示“电视上如果出现确认提示，请点允许”。 */
    val loadingSlow: Boolean = false,
    val failure: PushFailure? = null,
    /** 照片 30 分钟无操作前 1 分钟的提醒。 */
    val idleWarning: Boolean = false,
    val endReason: PushEndReason? = null,
    val syncMode: SyncMode = SyncMode.POLL,
    /** 正在等对方（本应用接收端）点“允许”：20 秒预算暂停计时（第 1 轮 EMU-1）。 */
    val awaitingConsent: Boolean = false,
) {
    val isActive: Boolean get() = phase != PushPhase.IDLE && phase != PushPhase.ENDED
    val isPlaying: Boolean get() = phase == PushPhase.PLAYING
}

sealed interface PushEvent {
    /** 乐观切换播放 / 暂停 3 秒内电视没跟上，已改回。 */
    data object NoResponse : PushEvent
    data object SeekUnsupported : PushEvent
    data object IdleWarning : PushEvent
    data class Loaded(val index: Int) : PushEvent
    data class AutoAdvanced(val toIndex: Int) : PushEvent
    data class Failed(val index: Int, val failure: PushFailure) : PushEvent
    data class Ended(val reason: PushEndReason) : PushEvent
}

/** 写进报告的统计（PRD 8.1.1 G）。 */
data class PushStats(
    val firstPlayMs: Long? = null,
    val switchCount: Int = 0,
    val autoAdvanceCount: Int = 0,
    val fallbacks: Set<String> = emptySet(),
    val lastUpnpCode: Int? = null,
    val lastHttpStatus: Int? = null,
    val eventsReceived: Boolean = false,
    val loadedCount: Int = 0,
)

/** 所有时间参数（毫秒），单测可缩短。 */
data class PushTimings(
    val stopSettleMs: Long = 300,
    val playRetryDelaysMs: List<Long> = listOf(500, 1_500, 3_000),
    val startTimeoutMs: Long = 20_000,
    val slowHintMs: Long = 10_000,
    val startPollMs: Long = 500,
    val pollForegroundMs: Long = 1_000,
    val pollBackgroundMs: Long = 5_000,
    val stopEndMs: Long = 5_000,
    val lostMs: Long = 30_000,
    val debounceMs: Long = 400,
    val photoIdleMs: Long = 30 * 60_000L,
    val photoIdleWarnMs: Long = 29 * 60_000L,
    val optimisticMs: Long = 3_000,
    val seekIgnoreMs: Long = 1_500,
    val finishSlackMs: Long = 5_000,
    val controlTimeoutMs: Long = 5_000,
    val loadTimeoutMs: Long = 15_000,
    /** 推到本应用接收端时 SetAVTransportURI 的等待上限（对方确认弹窗最长 30 秒 + 余量）。 */
    val consentTimeoutMs: Long = 40_000,
    /** 失败 / 等待选择后无操作多久结束（释放前台服务与 Wi-Fi 锁）。 */
    val inactiveMs: Long = 10 * 60_000L,
    val stopOnEndTimeoutMs: Long = 800,
    val volumeDebounceMs: Long = 300,
    val idleCheckMs: Long = 10_000,
)
