package com.benchan.wireless.screencasting.protocol.dlna

import kotlinx.coroutines.flow.StateFlow

/** AVTransport TransportState（技术方案 3.7：ExoPlayer 状态映射）。 */
enum class TransportState { STOPPED, PLAYING, PAUSED_PLAYBACK, TRANSITIONING, NO_MEDIA_PRESENT }

data class PlayerSnapshot(
    val state: TransportState = TransportState.NO_MEDIA_PRESENT,
    val uri: String = "",
    val metadataXml: String = "",
    val metadata: MediaMetadata? = null,
    val durationMs: Long = 0,
    val volume: Int = 50,
    val muted: Boolean = false,
    val error: Boolean = false,
)

/** DMR 背后的播放器（Media3 ExoPlayer 实现见 [ExoMediaRendererPlayer]；单测用假实现）。 */
interface MediaRendererPlayer {
    val snapshot: StateFlow<PlayerSnapshot>
    suspend fun load(uri: String, metadataXml: String, metadata: MediaMetadata?)
    suspend fun play()
    suspend fun pause()
    suspend fun stop()
    suspend fun seek(positionMs: Long)
    suspend fun positionMs(): Long
    suspend fun setVolume(volume: Int)
    suspend fun setMute(muted: Boolean)
}

/** 陌生控制点首次推送时的确认（PRD 8.5：DLNA 推送“陌生设备需确认”默认开启）。 */
fun interface DlnaConsent {
    suspend fun allow(remoteAddress: String, userAgent: String?, title: String?): Boolean
}
