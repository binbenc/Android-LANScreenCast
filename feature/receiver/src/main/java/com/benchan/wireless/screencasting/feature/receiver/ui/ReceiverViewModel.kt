package com.benchan.wireless.screencasting.feature.receiver.ui

import androidx.lifecycle.viewModelScope
import com.benchan.wireless.screencasting.core.control.TvControlMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.benchan.wireless.screencasting.core.protocol.control.RemoteKey
import com.benchan.wireless.screencasting.feature.receiver.session.ControlNotice
import com.benchan.wireless.screencasting.feature.receiver.session.PinchStart
import kotlinx.coroutines.flow.SharedFlow
import androidx.lifecycle.ViewModel
import com.benchan.wireless.screencasting.core.rtc.RtcEngine
import com.benchan.wireless.screencasting.feature.receiver.session.ConfirmRequest
import com.benchan.wireless.screencasting.feature.receiver.session.MirrorSession
import com.benchan.wireless.screencasting.feature.receiver.session.ReceiverController
import com.benchan.wireless.screencasting.feature.receiver.session.StandbyInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.EglBase
import org.webrtc.VideoSink
import javax.inject.Inject

@HiltViewModel
class ReceiverViewModel @Inject constructor(
    private val controller: ReceiverController,
    private val rtcEngine: RtcEngine,
    private val settingsRepo: com.benchan.wireless.screencasting.core.data.settings.SettingsRepository,
) : ViewModel() {
    val standby: StateFlow<StandbyInfo> = controller.standby
    val session: StateFlow<MirrorSession?> = controller.session
    val request: StateFlow<ConfirmRequest?> = controller.confirmRequest
    val dlnaPlayback: StateFlow<com.benchan.wireless.screencasting.protocol.dlna.DlnaPlayback?> = controller.dlnaPlayback
    val dlnaPlayer: androidx.media3.common.Player? get() = controller.dlnaPlayer

    fun stopDlna() = controller.stopDlna()
    val appleState = controller.appleState
    fun setAppleSurface(surface: android.view.Surface?) = controller.setAppleSurface(surface)
    fun disconnectApple() = controller.disconnectApple()
    val appleVideoPlayer: androidx.media3.common.Player? get() = controller.appleVideoPlayer

    val eglContext: EglBase.Context get() = rtcEngine.eglBase.eglBaseContext

    fun startListening() = controller.startListening()
    fun stopListening() = controller.stopListening()
    fun respond(id: Long, allowed: Boolean) = controller.respondToRequest(id, allowed)
    fun disconnect() = controller.disconnect()
    fun attach(sink: VideoSink) = controller.attachRenderer(sink)
    fun detach(sink: VideoSink) = controller.detachRenderer(sink)
    fun clearNotice() = controller.clearNotice()
    fun setVideoDisplay(attached: Boolean) = controller.setVideoDisplay(attached)

    // —— 反向控制（PRD F23，交互稿 R06–R08）——
    val controlNotices: SharedFlow<ControlNotice> = controller.controlNotices
    internal val control: RemoteControlActions = object : RemoteControlActions {
        override fun request() { controller.control?.request() }
        override fun cancelRequest() { controller.control?.cancelRequest() }
        override fun exit() { controller.control?.exit() }
        override fun key(k: RemoteKey) { controller.control?.key(k) }
        override fun pointerDown(viewW: Int, viewH: Int, x: Float, y: Float): Boolean = controller.control?.pointerDown(viewW, viewH, x, y) == true
        override fun pointerMove(viewW: Int, viewH: Int, x: Float, y: Float) { controller.control?.pointerMove(viewW, viewH, x, y) }
        override fun pointerUp(viewW: Int, viewH: Int, x: Float, y: Float) { controller.control?.pointerUp(viewW, viewH, x, y) }
        override fun pointerCancel() { controller.control?.pointerCancel() }
        override fun tick() { controller.control?.tick() }
        override fun touchedWhileBlocked() { controller.control?.touchedWhileBlocked() }
        override fun wheel(viewW: Int, viewH: Int, x: Float, y: Float, dy: Double) { controller.control?.wheel(viewW, viewH, x, y, dy) }
        override fun mouseWheel(viewW: Int, viewH: Int, x: Float, y: Float, notches: Double) { controller.control?.mouseWheel(viewW, viewH, x, y, notches) }
        override fun typeChar(c: Char) { controller.control?.typeChar(c) }
        override fun sendText(text: String) { controller.control?.sendTextBlock(text) }
        override fun notifyUnsupported(feature: String, force: Boolean) { controller.control?.notifyUnsupported(feature, force) }
        override fun hintOnce(notice: ControlNotice) { controller.control?.hintOnce(notice) }
        override val editorMissing: Boolean get() = controller.control?.editorMissing == true
        override fun pinchStart(viewW: Int, viewH: Int, ax: Float, ay: Float, bx: Float, by: Float): PinchStart =
            controller.control?.pinchStart(viewW, viewH, ax, ay, bx, by) ?: PinchStart.INACTIVE
        override fun pinchMove(viewW: Int, viewH: Int, ax: Float, ay: Float, bx: Float, by: Float) { controller.control?.pinchMove(viewW, viewH, ax, ay, bx, by) }
        override fun pinchEnd(): Long? = controller.control?.pinchEnd()
        override fun ctrlWheel(viewW: Int, viewH: Int, x: Float, y: Float, notches: Double) { controller.control?.ctrlWheel(viewW, viewH, x, y, notches) }
    }

    /** TV 控制模式与首次提示（记住到下次，交互稿 R10 / R11）。 */
    val tvControlMode = settingsRepo.settings.map { it.tvControlMode }.stateIn(viewModelScope, SharingStarted.Eagerly, TvControlMode.CURSOR)
    val tvControlHintShown = settingsRepo.settings.map { it.tvControlHintShown }.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    fun setTvControlMode(mode: TvControlMode) = viewModelScope.launch { settingsRepo.setTvControlMode(mode) }
    fun markTvControlHintShown() = viewModelScope.launch { settingsRepo.setTvControlHintShown() }

    /** “双指缩放会在松手后执行”提示（每次安装一次）。 */
    val pinchHintShown = settingsRepo.settings.map { it.pinchHintShown }.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    fun markPinchHintShown() = viewModelScope.launch { settingsRepo.setPinchHintShown() }
}

/** 播放页调用的反向控制操作（主线程）。 */
internal interface RemoteControlActions {
    fun request()
    fun cancelRequest()
    fun exit()
    fun key(k: RemoteKey)
    fun pointerDown(viewW: Int, viewH: Int, x: Float, y: Float): Boolean
    fun pointerMove(viewW: Int, viewH: Int, x: Float, y: Float)
    fun pointerUp(viewW: Int, viewH: Int, x: Float, y: Float)
    fun pointerCancel()
    fun tick()
    fun touchedWhileBlocked()
    fun wheel(viewW: Int, viewH: Int, x: Float, y: Float, dy: Double)
    fun mouseWheel(viewW: Int, viewH: Int, x: Float, y: Float, notches: Double)
    fun typeChar(c: Char)
    fun sendText(text: String)
    fun notifyUnsupported(feature: String, force: Boolean = false)
    fun hintOnce(notice: ControlNotice)

    /** 发送端最近回过“没有选中的输入框”。 */
    val editorMissing: Boolean

    // C 期：双指缩放
    fun pinchStart(viewW: Int, viewH: Int, ax: Float, ay: Float, bx: Float, by: Float): PinchStart
    fun pinchMove(viewW: Int, viewH: Int, ax: Float, ay: Float, bx: Float, by: Float)
    fun pinchEnd(): Long?
    fun ctrlWheel(viewW: Int, viewH: Int, x: Float, y: Float, notches: Double)
}
