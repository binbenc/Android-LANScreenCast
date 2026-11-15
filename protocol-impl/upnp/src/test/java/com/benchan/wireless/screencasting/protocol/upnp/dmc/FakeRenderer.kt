package com.benchan.wireless.screencasting.protocol.upnp.dmc

import com.benchan.wireless.screencasting.protocol.upnp.SoapOutcome

/** 可编排的假 DMR（单测用）：记录调用，按配置返回错误。 */
class FakeRenderer {
    var state = "NO_MEDIA_PRESENT"
    var status = "OK"
    var uri = ""
    var metadata = ""
    var positionMs = 0L
    var durationMs = 60_000L
    var volume = 30
    var muted = false
    var trackUriOverride: String? = null
    var offline = false
    var positionSupported = true
    /** SetAVTransportURI 挂起多久才返回（模拟对方确认弹窗 / 卡住的电视）。 */
    var setDelayMs = 0L
    /** 只让 GetTransportInfo 失败（模拟设置地址后失联）。 */
    var transportInfoFails = false
    var mediaInfoUri: String? = null
    val calls = mutableListOf<String>()

    /** 依次消费：SetAVTransportURI 的错误码（null = 成功）。 */
    val setFaults = ArrayDeque<Int?>()
    val playFaults = ArrayDeque<Int?>()
    val seekFaults = mutableMapOf<String, Int>()

    /** Play 之后电视进入的状态。 */
    var afterPlay: (FakeRenderer) -> Unit = { it.state = "PLAYING" }
    var pauseWorks = true

    fun count(action: String) = calls.count { it == action }

    val port = RendererPort { _, action, args, _ ->
        calls += action
        if (offline) return@RendererPort SoapOutcome.Failure(SoapOutcome.Failure.Kind.TIMEOUT)
        val a = args.toMap()
        fun fault(code: Int) = SoapOutcome.Fault(code, null, 500)
        when (action) {
            "GetTransportInfo" -> if (transportInfoFails) SoapOutcome.Failure(SoapOutcome.Failure.Kind.TIMEOUT) else SoapOutcome.Ok(mapOf("CurrentTransportState" to state, "CurrentTransportStatus" to status, "CurrentSpeed" to "1"))
            "GetPositionInfo" -> if (!positionSupported) fault(401) else SoapOutcome.Ok(
                mapOf(
                    "TrackURI" to (trackUriOverride ?: uri),
                    "RelTime" to fmt(positionMs), "TrackDuration" to fmt(durationMs),
                ),
            )
            "GetMediaInfo" -> SoapOutcome.Ok(mapOf("CurrentURI" to (mediaInfoUri ?: uri)))
            "SetAVTransportURI" -> {
                if (setDelayMs > 0) kotlinx.coroutines.delay(setDelayMs)
                val f = setFaults.removeFirstOrNull()
                if (f != null) fault(f) else {
                    uri = a["CurrentURI"].orEmpty(); metadata = a["CurrentURIMetaData"].orEmpty(); state = "STOPPED"; positionMs = 0
                    SoapOutcome.Ok(emptyMap())
                }
            }
            "Play" -> {
                val f = playFaults.removeFirstOrNull()
                if (f != null) fault(f) else {
                    afterPlay(this); SoapOutcome.Ok(emptyMap())
                }
            }
            "Pause" -> { if (pauseWorks) state = "PAUSED_PLAYBACK"; SoapOutcome.Ok(emptyMap()) }
            "Stop" -> { state = "STOPPED"; SoapOutcome.Ok(emptyMap()) }
            "Seek" -> seekFaults[a["Unit"]]?.let(::fault) ?: run {
                positionMs = com.benchan.wireless.screencasting.protocol.upnp.UpnpTime.parse(a["Target"].orEmpty()) ?: 0
                SoapOutcome.Ok(emptyMap())
            }
            "GetCurrentTransportActions" -> fault(401)
            "GetVolume" -> SoapOutcome.Ok(mapOf("CurrentVolume" to volume.toString()))
            "SetVolume" -> { volume = a["DesiredVolume"]!!.toInt(); SoapOutcome.Ok(emptyMap()) }
            "GetMute" -> SoapOutcome.Ok(mapOf("CurrentMute" to if (muted) "1" else "0"))
            "SetMute" -> { muted = a["DesiredMute"] == "1"; SoapOutcome.Ok(emptyMap()) }
            else -> fault(401)
        }
    }

    private fun fmt(ms: Long) = com.benchan.wireless.screencasting.protocol.upnp.UpnpTime.format(ms)
}
