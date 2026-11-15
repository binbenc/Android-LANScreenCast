package com.benchan.wireless.screencasting.feature.sender.ui.push

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.benchan.wireless.screencasting.core.data.dlna.DlnaRendererStore
import com.benchan.wireless.screencasting.core.discovery.DiscoveredDevice
import com.benchan.wireless.screencasting.feature.sender.push.MediaPushService
import com.benchan.wireless.screencasting.feature.sender.session.SenderController
import com.benchan.wireless.screencasting.feature.sender.session.SenderState
import com.benchan.wireless.screencasting.feature.sender.session.isTerminal
import com.benchan.wireless.screencasting.protocol.dlna.dmc.ContentUriSource
import com.benchan.wireless.screencasting.protocol.dlna.dmc.DmcController
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushInput
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushSessionState
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushSourceKind
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 推送界面的一次性提示。 */
sealed interface PushUiEvent {
    data class Skipped(val cloudFiles: Int, val truncated: Boolean, val unreadable: Int = 0) : PushUiEvent

    /** 音乐 / 网址的格式不在电视声明里：问“仍要尝试？”（第 1 轮 PM P2-8）。 */
    data class FormatWarning(val go: () -> Unit) : PushUiEvent
}

/**
 * DLNA 推送界面（交互稿第 13 章）的 ViewModel：把设备列表的一行变成推送目标，过滤选择器结果（云盘管道流、100 项上限），
 * 启动前台服务并把操作转给 [DmcController]。
 */
@HiltViewModel
class PushViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dmc: DmcController,
    private val renderers: DlnaRendererStore,
    private val mirror: SenderController,
    private val discovery: com.benchan.wireless.screencasting.core.discovery.DeviceDiscovery,
    private val saved: androidx.lifecycle.SavedStateHandle,
) : ViewModel() {
    /**
     * 正在为哪台设备选内容。放在 ViewModel 里：系统选择器返回时 Activity 被重建 / 旋转也不丢（第 1 轮 PM P1-7）；
     * 进程被回收时按保存的 UDN 从发现结果或当前会话找回。
     */
    var pendingTarget: PushTarget? = null
        get() = field ?: saved.get<String>(KEY_UDN)?.let { udn ->
            dmc.session.value?.target?.takeIf { it.udn == udn }
                ?: discovery.devices.value.firstOrNull { it.dlna?.udn == udn }?.let { targetFor(it, 0) }
        }?.also { field = it }
        set(value) {
            field = value
            saved[KEY_UDN] = value?.udn
        }
    val session: StateFlow<PushSessionState?> = dmc.session
    val engineEvents: SharedFlow<com.benchan.wireless.screencasting.protocol.upnp.dmc.PushEvent> = dmc.events

    private val _events = MutableSharedFlow<PushUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<PushUiEvent> = _events.asSharedFlow()

    fun targetFor(device: DiscoveredDevice, discoveryStartedAt: Long): PushTarget? {
        val ref = device.dlna ?: return null
        return PushTarget(
            description = ref.description, server = ref.server, sink = ref.sink,
            discoveryPath = device.sources.firstOrNull()?.name,
            // 发现耗时按第一次发现的时间（第 1 轮 PM P2-3）
            discoveryMs = if (discoveryStartedAt > 0) (device.firstSeenAt - discoveryStartedAt).coerceAtLeast(0) else null,
        )
    }

    fun needsConfirmation(target: PushTarget): Boolean = dmc.needsConfirmation(target.udn)
    fun confirm(target: PushTarget) = dmc.confirm(target)
    fun forget(udn: String) = viewModelScope.launch(Dispatchers.IO) { renderers.forget(udn) }
    fun pushedBefore(udn: String): Boolean = renderers.find(udn)?.lastUsedAt?.let { it > 0 } == true

    /** 正在镜像（推送前需要先停止，交互稿 P15）。 */
    val mirroring: Boolean get() = mirror.state.value.let { it !is SenderState.Idle && !it.isTerminal }

    fun stopMirroring() = mirror.stop()

    /** 选择器结果 → 推送（[replace] = 遥控页“换内容”）。 */
    fun pushLocal(target: PushTarget, uris: List<Uri>, source: PushSourceKind, replace: Boolean) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        val truncated = uris.size > MAX_ITEMS
        val resolver = context.contentResolver
        val ok = mutableListOf<Uri>()
        var cloud = 0
        var unreadable = 0
        var audioUnsupported = false
        withContext(Dispatchers.IO) {
            uris.take(MAX_ITEMS).forEach { u ->
                val mime = resolver.getType(u)?.let(com.benchan.wireless.screencasting.protocol.upnp.MediaMime::normalize).orEmpty()
                val probe = ContentUriSource.probe(resolver, u)
                when {
                    probe == null -> unreadable++
                    // 照片会先在手机上转成 JPEG，不需要原文件可随机读取（第 1 轮 PM P2-4）
                    mime.startsWith("image/") || probe.seekable -> {
                        ok += u
                        if (mime.startsWith("audio/") && sinkSays(target, mime) == com.benchan.wireless.screencasting.protocol.upnp.SinkSupport.UNSUPPORTED) audioUnsupported = true
                    }
                    else -> cloud++
                }
            }
        }
        if (cloud > 0 || truncated || unreadable > 0) _events.tryEmit(PushUiEvent.Skipped(cloud, truncated, unreadable))
        if (ok.isEmpty()) return@launch
        val go = { start(target, ok.map { PushInput.Local(it) }, source, replace) }
        if (audioUnsupported) _events.tryEmit(PushUiEvent.FormatWarning(go)) else go()
    }

    fun pushUrl(target: PushTarget, url: String, replace: Boolean) {
        val go = { start(target, listOf(PushInput.Remote(url.trim())), PushSourceKind.URL, replace) }
        val mime = com.benchan.wireless.screencasting.protocol.upnp.MediaMime.guessForUrl(url).first
        if (mime != null && sinkSays(target, mime) == com.benchan.wireless.screencasting.protocol.upnp.SinkSupport.UNSUPPORTED) {
            _events.tryEmit(PushUiEvent.FormatWarning(go))
        } else go()
    }

    private fun sinkSays(target: PushTarget, mime: String) =
        com.benchan.wireless.screencasting.protocol.upnp.SinkMatcher.match(target.sink.orEmpty(), mime)

    private fun start(target: PushTarget, inputs: List<PushInput>, source: PushSourceKind, replace: Boolean) {
        val cur = dmc.session.value
        if (replace && cur != null && cur.isActive && cur.target.udn == target.udn) {
            dmc.replace(inputs, source)
        } else {
            MediaPushService.start(context, dmc.start(target, inputs, source))
        }
    }

    fun play() = dmc.play()
    fun pause() = dmc.pause()
    fun togglePlay() = dmc.togglePlay()
    fun seekTo(ms: Long) = dmc.seekTo(ms)
    fun setVolume(v: Int) = dmc.setVolume(v)
    fun next() = dmc.next()
    fun previous() = dmc.previous()
    fun select(i: Int) = dmc.select(i)
    fun stop() = dmc.stop()

    /** P05 取消：结束本次推送，不显示结束页。 */
    fun cancelLoading() {
        dmc.stop()
        dmc.dismiss()
    }
    fun dismiss() = dmc.dismiss()
    fun keepAlive() = dmc.keepAlive()
    fun setForeground(visible: Boolean) = dmc.setForeground(visible)
    fun resumeLast() {
        dmc.resumeLast()?.let { MediaPushService.start(context, it) }
    }
    fun acceptTranscode(videoOnly: Boolean, autoForList: Boolean) = dmc.acceptTranscode(videoOnly, autoForList)
    fun tryOriginal() = dmc.tryOriginal()
    fun cancelTranscode() = dmc.cancelTranscode()
    fun dismissTranscodeOffer() = dmc.dismissTranscodeOffer()
    fun skipItem() = dmc.skipItem()
    fun offerTranscodeFor(index: Int) = dmc.offerTranscodeFor(index)

    /** 正在转换（P15 文案追加“视频转换也会取消”）。 */
    val transcodingNow: Boolean get() = dmc.session.value?.transcode?.phase.let { it == com.benchan.wireless.screencasting.protocol.dlna.dmc.TranscodeUi.Phase.RUNNING || it == com.benchan.wireless.screencasting.protocol.dlna.dmc.TranscodeUi.Phase.PAUSED_THERMAL }

    /** 正在镜像的目标名（P15 正文）。 */
    val mirrorTargetName: String? get() = when (val st = mirror.state.value) {
        is SenderState.Casting -> st.target.name
        is SenderState.Reconnecting -> st.target.name
        is SenderState.Starting -> st.target.name
        is SenderState.Connecting -> st.target.name
        else -> null
    }

    /** 失败卡“重试”：重新推当前项。 */
    fun retry() {
        val s = dmc.session.value ?: return
        dmc.select(s.engine.index)
    }

    companion object {
        const val MAX_ITEMS = 100
        private const val KEY_UDN = "push_pending_udn"
    }
}
