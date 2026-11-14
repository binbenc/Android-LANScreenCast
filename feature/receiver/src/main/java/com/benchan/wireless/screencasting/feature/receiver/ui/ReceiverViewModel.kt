package com.benchan.wireless.screencasting.feature.receiver.ui

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
) : ViewModel() {
    val standby: StateFlow<StandbyInfo> = controller.standby
    val session: StateFlow<MirrorSession?> = controller.session
    val request: StateFlow<ConfirmRequest?> = controller.confirmRequest
    val dlnaPlayback: StateFlow<com.benchan.wireless.screencasting.protocol.dlna.DlnaPlayback?> = controller.dlnaPlayback
    val dlnaPlayer: androidx.media3.common.Player? get() = controller.dlnaPlayer

    fun stopDlna() = controller.stopDlna()
    val appleState = controller.appleState
    fun setAppleSurface(surface: android.view.Surface?) = controller.setAppleSurface(surface)

    val eglContext: EglBase.Context get() = rtcEngine.eglBase.eglBaseContext

    fun startListening() = controller.startListening()
    fun stopListening() = controller.stopListening()
    fun respond(id: Long, allowed: Boolean) = controller.respondToRequest(id, allowed)
    fun disconnect() = controller.disconnect()
    fun attach(sink: VideoSink) = controller.attachRenderer(sink)
    fun detach(sink: VideoSink) = controller.detachRenderer(sink)
    fun clearNotice() = controller.clearNotice()
}
