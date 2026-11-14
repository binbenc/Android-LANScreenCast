package com.benchan.wireless.screencasting.protocol.dlna

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.benchan.wireless.screencasting.core.common.WscLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Media3 ExoPlayer 实现的 DMR 播放器（技术方案 3.7：播放器状态映射到 TransportState）。
 * ExoPlayer 只能在主线程使用，所有操作切到 Dispatchers.Main。
 */
class ExoMediaRendererPlayer(private val context: Context) : MediaRendererPlayer {
    private val _snapshot = MutableStateFlow(PlayerSnapshot())
    override val snapshot: StateFlow<PlayerSnapshot> = _snapshot.asStateFlow()

    /** UI（PlayerView）使用的播放器实例。 */
    @Volatile
    var exoPlayer: ExoPlayer? = null
        private set

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync(player)
        override fun onPlayerError(error: PlaybackException) {
            WscLog.w(TAG, "playback error ${error.errorCodeName}")
            _snapshot.update { it.copy(error = true, state = TransportState.STOPPED) }
        }
    }

    private fun ensurePlayer(): ExoPlayer {
        check(Looper.myLooper() == Looper.getMainLooper())
        exoPlayer?.let { return it }
        return ExoPlayer.Builder(context).build().also {
            it.addListener(listener)
            it.volume = _snapshot.value.volume / 100f
            exoPlayer = it
        }
    }

    private fun sync(p: Player) {
        val state = when {
            p.playbackState == Player.STATE_IDLE && p.mediaItemCount == 0 -> TransportState.NO_MEDIA_PRESENT
            p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED -> TransportState.STOPPED
            p.playbackState == Player.STATE_BUFFERING -> if (p.playWhenReady) TransportState.TRANSITIONING else TransportState.PAUSED_PLAYBACK
            p.isPlaying || p.playWhenReady -> TransportState.PLAYING
            else -> TransportState.PAUSED_PLAYBACK
        }
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: _snapshot.value.metadata?.durationMs ?: 0
        _snapshot.update { it.copy(state = state, durationMs = dur) }
    }

    override suspend fun load(uri: String, metadataXml: String, metadata: MediaMetadata?) = onMain {
        val p = ensurePlayer()
        val mime = metadata?.mimeType?.lowercase()
        val item = MediaItem.Builder().setUri(uri).apply {
            when {
                mime == null -> Unit
                mime.contains("mpegurl") -> setMimeType(MimeTypes.APPLICATION_M3U8)
                mime.contains("dash") -> setMimeType(MimeTypes.APPLICATION_MPD)
            }
        }.build()
        _snapshot.update { it.copy(uri = uri, metadataXml = metadataXml, metadata = metadata, error = false, state = TransportState.TRANSITIONING) }
        p.setMediaItem(item)
        p.prepare()
        // 规范要求等 Play 才播放，但不少国内 App 不发 Play；准备好后默认播放，收到 Pause 再暂停
        p.playWhenReady = true
    }

    override suspend fun play() = onMain { ensurePlayer().play() }
    override suspend fun pause() = onMain { exoPlayer?.pause(); Unit }
    override suspend fun stop() = onMain {
        exoPlayer?.let {
            it.stop()
            it.clearMediaItems()
        }
        _snapshot.update { PlayerSnapshot(volume = it.volume, muted = it.muted, state = TransportState.STOPPED) }
    }

    override suspend fun seek(positionMs: Long) = onMain { exoPlayer?.seekTo(positionMs); Unit }
    override suspend fun positionMs(): Long = onMain { exoPlayer?.currentPosition ?: 0L }

    override suspend fun setVolume(volume: Int) = onMain {
        _snapshot.update { it.copy(volume = volume) }
        if (!_snapshot.value.muted) exoPlayer?.volume = volume / 100f
    }

    override suspend fun setMute(muted: Boolean) = onMain {
        _snapshot.update { it.copy(muted = muted) }
        exoPlayer?.volume = if (muted) 0f else _snapshot.value.volume / 100f
    }

    fun release() {
        Handler(Looper.getMainLooper()).post {
            exoPlayer?.removeListener(listener)
            exoPlayer?.release()
            exoPlayer = null
            _snapshot.value = PlayerSnapshot()
        }
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    private companion object {
        const val TAG = "ExoDmr"
    }
}
