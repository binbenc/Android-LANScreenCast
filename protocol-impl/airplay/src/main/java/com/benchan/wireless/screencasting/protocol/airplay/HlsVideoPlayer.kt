package com.benchan.wireless.screencasting.protocol.airplay

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

/**
 * 视频网址（HLS）播放（PRD R-08，技术方案 3.8）。
 *
 * UxPlay 从 iPhone 取回 master / media playlist 并改写后，在自己的 httpd 上以 `http://localhost:<port>/…` 提供
 * （即“本地 playlist 代理”）；这里用 Media3 ExoPlayer 播放该地址。分片仍由 ExoPlayer 直接从视频网站拉取 ——
 * 与 DLNA 推送网址一样，属于 PRD “只有用户推送的网址可以访问外网”的例外。FairPlay 加密的内容不支持。
 *
 * 线程：ExoPlayer 只在主线程访问；原生回调线程通过 [main] 投递。[snapshot] 由主线程每 500ms 刷新，
 * 供 UxPlay 在 httpd 线程上同步读取播放进度（/playback-info）。
 */
internal class HlsVideoPlayer(
    private val context: Context,
    private val onStarted: () -> Unit,
    private val onFinished: (error: Boolean) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var player: ExoPlayer? = null
        private set

    /** duration, position, seekStart, seekDuration, rate, bufferEmpty, bufferFull（秒），以及是否仍在播放。 */
    @Volatile
    private var snapshot = Snapshot()

    private data class Snapshot(
        val duration: Double = 0.0,
        val position: Double = 0.0,
        val rate: Double = 0.0,
        val bufferedTo: Double = 0.0,
        val playing: Boolean = true,
    )

    private val poll = object : Runnable {
        override fun run() {
            val p = player ?: return
            snapshot = Snapshot(
                duration = p.duration.takeIf { it != C.TIME_UNSET }?.div(1000.0) ?: 0.0,
                position = p.currentPosition / 1000.0,
                rate = if (p.isPlaying) p.playbackParameters.speed.toDouble() else 0.0,
                bufferedTo = p.bufferedPosition / 1000.0,
                playing = p.playbackState != Player.STATE_ENDED && p.playerError == null,
            )
            main.postDelayed(this, 500)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) onFinished(false)
        }

        override fun onPlayerError(error: PlaybackException) {
            // 不记录网址（F25 隐私要求），只记错误码
            WscLog.w(TAG, "playback error ${error.errorCodeName}")
            snapshot = snapshot.copy(playing = false)
            onFinished(true)
        }
    }

    fun play(location: String, startPositionSec: Float) = main.post {
        val p = player ?: ExoPlayer.Builder(context).build().also {
            it.addListener(listener)
            player = it
        }
        snapshot = Snapshot()
        p.setMediaItem(MediaItem.Builder().setUri(location).setMimeType(MimeTypes.APPLICATION_M3U8).build())
        p.prepare()
        if (startPositionSec > 0f) p.seekTo((startPositionSec * 1000).toLong())
        p.playWhenReady = true
        main.removeCallbacks(poll)
        main.post(poll)
        onStarted()
    }

    fun seek(positionSec: Float) = main.post { player?.seekTo((positionSec * 1000).toLong().coerceAtLeast(0)) }

    fun setRate(rate: Float) = main.post {
        val p = player ?: return@post
        when {
            rate == 0f -> p.pause()
            rate > 0f -> p.play()
        }
    }

    /** UxPlay on_video_playlist_remove：暂停并返回当前位置（秒）。 */
    fun pauseForPlaylistRemove(): Float {
        main.post { player?.pause() }
        return snapshot.position.toFloat()
    }

    /** 填充 7 项播放信息，返回是否仍在播放（见 [UxPlayCallbacks.hlsPlaybackInfo]）。 */
    fun fillPlaybackInfo(out: DoubleArray): Boolean {
        val s = snapshot
        if (out.size >= 7) {
            out[0] = s.duration
            out[1] = s.position
            out[2] = 0.0
            out[3] = s.duration
            out[4] = s.rate
            out[5] = if (s.bufferedTo <= s.position) 1.0 else 0.0
            out[6] = if (s.duration > 0 && s.bufferedTo >= s.duration) 1.0 else 0.0
        }
        return s.playing
    }

    fun release() = main.post {
        main.removeCallbacks(poll)
        player?.let {
            it.removeListener(listener)
            it.release()
        }
        player = null
        snapshot = Snapshot()
    }

    private companion object {
        const val TAG = "AppleVideoUrl"
    }
}
