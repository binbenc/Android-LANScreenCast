package com.benchan.wireless.screencasting.feature.sender.push

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.benchan.wireless.screencasting.protocol.dlna.dmc.DmcController
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushSessionState
import com.benchan.wireless.screencasting.protocol.upnp.MediaKind
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushPhase
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * 把 DLNA 推送映射成一个“远程播放器”交给 Media3 MediaSession（PRD 8.1.1 D、技术方案 3.13.4.6）：
 * - 锁屏 / 系统媒体控制区显示播放、暂停、上一个、下一个与进度；
 * - DeviceInfo = PLAYBACK_TYPE_REMOTE：前台、后台、锁屏时手机音量键调的都是电视音量（系统远程音量）。
 * 所有调用在主线程。
 */
@OptIn(UnstableApi::class)
class RemotePushPlayer(private val controller: DmcController) : SimpleBasePlayer(Looper.getMainLooper()) {
    private var snapshot: PushSessionState? = null

    /** 锁屏通知隐私：用户设了“隐藏敏感内容”时不显示标题。 */
    var hideTitles: Boolean = false

    fun update(state: PushSessionState?) {
        snapshot = state
        invalidateState()
    }

    override fun getState(): State {
        val s = snapshot
        val e = s?.engine
        if (s == null || e == null || s.ended != null) {
            return State.Builder().setAvailableCommands(Player.Commands.EMPTY).setPlaybackState(STATE_IDLE).build()
        }
        val media = e.kind != MediaKind.IMAGE
        // 读不到电视音量时不接管音量键（交互稿 P07 / P12：回到调手机音量）
        val remoteVolume = e.hasVolume && e.volume != null
        val commands = Player.Commands.Builder().addAll(
            COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_TIMELINE, COMMAND_GET_METADATA, COMMAND_STOP,
        ).apply {
            if (remoteVolume) addAll(COMMAND_GET_DEVICE_VOLUME, COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS, COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
            if (media && e.canPause) add(COMMAND_PLAY_PAUSE)
            if (media && e.canSeek && e.durationMs != null) add(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            if (e.index > 0) add(COMMAND_SEEK_TO_PREVIOUS)
            if (e.index + 1 < e.count) add(COMMAND_SEEK_TO_NEXT)
            if (e.count > 1) add(COMMAND_SEEK_TO_MEDIA_ITEM)
        }.build()
        val items = s.items.map { item ->
            val label = when (item.kind) {
                MediaKind.VIDEO -> "视频"
                MediaKind.AUDIO -> "音乐"
                MediaKind.IMAGE -> "照片"
            }
            val count = if (s.items.size > 1) " · ${item.index + 1} / ${s.items.size}" else ""
            // 锁屏隐私跟随系统设置：隐藏敏感内容时不显示文件名（交互稿 P12）
            MediaItemData.Builder(item.index)
                .setMediaItem(MediaItem.Builder().setMediaId("push-${item.index}").build())
                // 标题“正在推送到 X”，副标题“文件名 · 3 / 12”（隐私模式下只写类型，交互稿 P12 / 第 1 轮 UX P2-37）
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle("正在推送到 ${s.target.name}")
                        .setArtist(if (hideTitles) "$label$count" else "${item.title}$count").build(),
                )
                .setDurationUs(if (item.index == e.index) e.durationMs?.times(1000) ?: androidx.media3.common.C.TIME_UNSET else androidx.media3.common.C.TIME_UNSET)
                .setIsSeekable(media && e.canSeek)
                .build()
        }
        val playbackState = when (e.phase) {
            PushPhase.PREPARING, PushPhase.LOADING -> STATE_BUFFERING
            PushPhase.PLAYING, PushPhase.PAUSED, PushPhase.SHOWING -> STATE_READY
            PushPhase.FAILED, PushPhase.IDLE, PushPhase.AWAITING_CHOICE -> STATE_IDLE
            PushPhase.ENDED -> STATE_ENDED
        }
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(items.ifEmpty { listOf(MediaItemData.Builder(0).build()) })
            .setCurrentMediaItemIndex(e.index.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
            .setPlaybackState(playbackState)
            .setPlayWhenReady(e.phase == PushPhase.PLAYING || e.phase == PushPhase.LOADING || e.phase == PushPhase.SHOWING, PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            // 播放中按 1 倍速外推位置，锁屏进度不再每 5 秒跳一下（第 1 轮 PM P2-13）
            .setContentPositionMs(
                if (e.phase == PushPhase.PLAYING) PositionSupplier.getExtrapolating(e.positionMs ?: 0, 1f)
                else PositionSupplier.getConstant(e.positionMs ?: 0),
            )
            .setDeviceInfo(
                if (remoteVolume) DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(0).setMaxVolume(100).build()
                else DeviceInfo.UNKNOWN,
            )
            .setDeviceVolume(e.volume ?: 0)
            .setIsDeviceMuted(e.muted == true)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) controller.play() else controller.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val cur = snapshot?.engine?.index ?: 0
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> controller.next()
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> controller.previous()
            COMMAND_SEEK_TO_MEDIA_ITEM -> if (mediaItemIndex != cur) controller.select(mediaItemIndex)
            else -> if (mediaItemIndex == cur && positionMs >= 0) controller.seekTo(positionMs) else if (mediaItemIndex != cur) controller.select(mediaItemIndex)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        controller.setVolume(deviceVolume)
        return Futures.immediateVoidFuture()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        controller.adjustVolume(VOLUME_STEP)
        return Futures.immediateVoidFuture()
    }

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        controller.adjustVolume(-VOLUME_STEP)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> {
        controller.setMute(muted)
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        controller.stop()
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val VOLUME_STEP = 5
    }
}
